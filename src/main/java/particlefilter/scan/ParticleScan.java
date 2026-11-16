package particlefilter.scan;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import particlefilter.ParticleFilter;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.filter.FilterRules;
import particlefilter.filter.ParticlePattern;
import particlefilter.filter.ParticleKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Records which particles spawn near the block under the crosshair for a few seconds, and where: both those that
 * appeared and those the rules hid, so players can see what is left once their rules are in place. Results list
 * particles that appeared first, most frequent first; typed particles are keyed by particle type id, particles created
 * without a type by class name.
 *
 * The "Here" column hides particles either in one existing zone (the one at the scanned block, or another the player
 * picks) or in new zones fitted to each source of the particle ({@link FittedZones}).
 */
public final class ParticleScan {

    /**
     * One particle the scan saw: how many spawned and how many of those the rules hid, the box they fill, how close the
     * nearest came, how many spawned in each block, how many appeared in each block, and the blocks Here fits zones to:
     * where it appeared, or where it spawned hidden if it never appeared. Block maps hold at most
     * {@link SpawnRecorder#MAX_BLOCKS} blocks of each kind.
     */
    public record Result(String particle, boolean typed, int count, int hiddenCount, AABB spawns, double nearest,
                         Map<BlockPos, Integer> spawnBlocks, Map<BlockPos, Integer> appeared, Map<BlockPos, Integer> fitBlocks) {
        /** True if the rules hid every one that spawned. */
        public boolean allHidden() {
            return hiddenCount == count;
        }
    }

    /** Radius of the running or last scan, in blocks ({@link PfConfig#scanRadius()} when it started). */
    private static int radius;

    private static final SpawnRecorder recorder = new SpawnRecorder();
    private static int ticksLeft;
    private static int durationTicks = 1;

    private static List<Result> results = List.of();
    private static BlockPos target;
    private static Component targetName = Component.empty();
    private static int generation;
    /** Existing zone the Here column uses; null means new zones fitted to each source. */
    private static String hereZoneId;
    /** Zone Here used before switching to new zones, offered to switch back to. */
    private static String previousZoneId;

    private ParticleScan() {}

    public static boolean isRunning() {
        return recorder.isRunning();
    }

    public static int secondsLeft() {
        return (ticksLeft + 19) / 20;
    }

    /** How far the running scan is, from 0 to 1. */
    public static float progress() {
        return isRunning() ? 1 - ticksLeft / (float) durationTicks : 0;
    }

    public static List<Result> results() {
        return results;
    }

    /** How far from its center the running or last scan records, in blocks. */
    public static int radius() {
        return radius;
    }

    /** Block the last scan was centered on; Here works around it. Null before the first scan. */
    public static BlockPos target() {
        return target;
    }

    /** What the last scan was centered on: the block's name, or the player. */
    public static Component targetName() {
        return targetName;
    }

    /** Increases whenever a scan starts or finishes, so screens know to refresh. */
    public static int generation() {
        return generation;
    }

    /** Starts scanning around the block under the crosshair, or around the player when not looking at a block. */
    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Optional<BlockPos> block = ParticleFilter.targetedBlock();
        Vec3 at = block.map(BlockPos::getCenter).orElse(mc.player.getEyePosition());
        targetName = block.map(pos -> mc.level.getBlockState(pos).getBlock().getName())
            .orElse(Component.translatable("particlefilter.scan.target_you"));
        target = BlockPos.containing(at);
        results = List.of();
        entryBlocks.clear();
        FittedZones.reset(); // zones from earlier scans keep their particles but are no longer resized
        previousZoneId = null;
        Zone existing = zonesAtTarget().stream().findFirst().orElse(null);
        hereZoneId = existing == null ? null : existing.id;
        durationTicks = ticksLeft = PfConfig.scanSeconds() * 20;
        radius = PfConfig.scanRadius();
        recorder.start(at, radius);
        generation++;
    }

    /** Forgets the running scan and the results, e.g. after leaving the world they belong to. */
    public static void reset() {
        recorder.stop();
        results = List.of();
        target = null;
        entryBlocks.clear();
        hereZoneId = null;
        previousZoneId = null;
        FittedZones.reset();
        generation++;
    }

    public static void record(String particle, boolean typed, double x, double y, double z, boolean hidden) {
        recorder.record(particle, typed, x, y, z, hidden);
    }

    public static void tick() {
        if (!recorder.isRunning() || --ticksLeft > 0) return;
        recorder.stop();
        List<Result> list = new ArrayList<>();
        for (SpawnRecorder.Recorded r : recorder.collect()) {
            SpawnRecorder.Spawns all = r.all();
            int hiddenCount = r.hidden() == null ? 0 : r.hidden().count();
            Map<BlockPos, Integer> appeared = blocks(r.shown());
            list.add(new Result(r.particle(), r.typed(), all.count(), hiddenCount, all.box(), all.nearest(), blocks(all),
                appeared, appeared.isEmpty() ? blocks(r.hidden()) : appeared));
        }
        list.sort(Comparator.comparing(Result::allHidden).thenComparing(Comparator.comparingInt(Result::count).reversed()));
        results = List.copyOf(list);
        entryBlocks.clear();
        FittedZones.resultsChanged();
        generation++;
        list.forEach(r -> ParticleFilter.LOGGER.info("Scan: {} x {}{}, {} hidden", r.count(), r.particle(), r.typed() ? "" : " (untyped)", r.hiddenCount()));
    }

    private static Map<BlockPos, Integer> blocks(SpawnRecorder.Spawns spawns) {
        return spawns == null ? Map.of() : Map.copyOf(spawns.blockCounts());
    }

    /** What the last scan recorded for [particle], or null if it didn't see it. */
    public static Result result(String particle) {
        for (Result r : results) if (r.particle().equals(particle)) return r;
        return null;
    }

    // ---------------------------------------------------------------- Here

    /**
     * Zones here containing or touching the scanned block: those containing it first, then smallest first. Fitted
     * zones only cover where particles spawned, which is often next to the block rather than in it.
     */
    public static List<Zone> zonesAtTarget() {
        if (target == null) return List.of();
        AABB around = new AABB(target).inflate(1);
        return PfConfig.zonesHere().stream().filter(z -> z.box().intersects(around))
            .sorted(Comparator.comparing((Zone z) -> !z.contains(target)).thenComparingLong(Zone::volume)).toList();
    }

    /** The existing zone Here hides particles in, or null when Here makes new zones fitted to each source. */
    public static Zone hereZone() {
        Zone zone = PfConfig.zoneById(hereZoneId);
        return zone != null && PfConfig.isInCurrentWorld(zone) ? zone : null;
    }

    /**
     * What the Here button switches to: from a zone, always new zones; from new zones, back to the zone used before
     * (or the smallest zone around the scanned block). Null means new zones, and also "nothing to switch to" while
     * Here already makes new zones with no zone around.
     */
    public static Zone nextHereChoice() {
        if (hereZone() != null) return null;
        Zone previous = PfConfig.zoneById(previousZoneId);
        if (previous != null && PfConfig.isInCurrentWorld(previous)) return previous;
        return zonesAtTarget().stream().findFirst().orElse(null);
    }

    public static void cycleHere() {
        Zone current = hereZone();
        Zone next = nextHereChoice();
        if (current != null) previousZoneId = current.id;
        if (next != null) stopFitting(next); // particles are added to it as it is from now on
        hereZoneId = next == null ? null : next.id;
    }

    /**
     * The switched-on entries Here's switches read for the particle [key]: those of Here's zone, or of the zones around
     * where it spawned.
     */
    public static List<String> hereEntries(String key) {
        Zone zone = hereZone();
        return zone != null ? zone.particles : FittedZones.entriesAround(key);
    }

    /** Switches [node] on or off in Here: its rules added, or every entry it owns deleted ({@link ResultTree#owns}). */
    public static void setHere(ResultTree.Node node, boolean hidden) {
        if (target == null || node.rules.isEmpty()) return;
        Zone zone = hereZone();
        ParticlePattern.TagCheck tags = FilterRules.tags();
        PfConfig.batch(() -> {
            if (zone != null && hidden) node.rules.forEach(rule -> PfConfig.setHiddenInZone(zone, rule, true));
            else if (zone != null) PfConfig.showInZone(zone, e -> ResultTree.owns(node, e, tags));
            else if (hidden) FittedZones.hide(node.rules);
            else FittedZones.show(node.leaves, e -> ResultTree.owns(node, e, tags));
        });
    }

    /** Switches [node] on or off everywhere: its rules added, or every entry it owns deleted. */
    public static void setEverywhere(ResultTree.Node node, boolean hidden) {
        if (node.rules.isEmpty()) return;
        ParticlePattern.TagCheck tags = FilterRules.tags();
        PfConfig.batch(() -> {
            if (hidden) node.rules.forEach(rule -> PfConfig.setHiddenEverywhere(rule, true));
            else PfConfig.showEverywhere(e -> ResultTree.owns(node, e, tags));
        });
    }

    /** The zones Here reads that hide some of [node]'s particles: Here's zone, or zones around where they spawned. */
    public static List<Zone> zonesHiding(ResultTree.Node node) {
        Zone zone = hereZone();
        List<Zone> around = zone != null ? List.of(zone) : FittedZones.zonesAround(node.leaves);
        return around.stream().filter(z -> z.particles.stream().anyMatch(e -> node.leaves.stream().anyMatch(l -> FilterRules.entryMatches(e, l)))).toList();
    }

    /** How many zones Here hides [entries] in, or would if switched on. */
    public static int hereZoneCount(List<String> entries) {
        return hereZone() != null ? 1 : FittedZones.hereBoxes(entries).size();
    }

    /** Where switching on Here would put zones for [entries]; empty when Here uses an existing zone. */
    public static List<AABB> previewFor(List<String> entries) {
        return hereZone() != null ? List.of() : FittedZones.previewFor(entries);
    }

    /** Spawn blocks of the particles an entry covers: all of them, and those Here fits zones to. */
    public record Blocks(Map<BlockPos, Integer> spawn, Map<BlockPos, Integer> fit) {
        static final Blocks NONE = new Blocks(Map.of(), Map.of());
    }

    private static final Map<String, Blocks> entryBlocks = new HashMap<>();

    /** Where the particles [entry] covers spawned in the last scan: one particle's, or a group's added up. */
    public static Blocks blocksFor(String entry) {
        Result exact = result(entry);
        if (exact != null) return new Blocks(exact.spawnBlocks(), exact.fitBlocks());
        return entryBlocks.computeIfAbsent(entry, e -> {
            Map<BlockPos, Integer> spawn = new HashMap<>();
            Map<BlockPos, Integer> fit = new HashMap<>();
            for (Result r : results) {
                if (!FilterRules.entryMatches(e, r.particle())) continue;
                r.spawnBlocks().forEach((pos, n) -> spawn.merge(pos, n, Integer::sum));
                r.fitBlocks().forEach((pos, n) -> fit.merge(pos, n, Integer::sum));
            }
            return spawn.isEmpty() ? Blocks.NONE : new Blocks(Map.copyOf(spawn), Map.copyOf(fit));
        });
    }

    /** Called when the player edits [zone] by hand, so no scan resizes it any more. */
    public static void stopFitting(Zone zone) {
        FittedZones.stopFitting(zone);
    }

    /** Marks the scanned block, where new zones start, before any particle is switched on. */
    public static AABB newZonePreview() {
        if (target == null) return null;
        return ZoneFit.fit(target, List.of()).aabb();
    }

    /** Changes how far apart spots may be and still count as one source, and regroups the latest scan's zones. */
    public static void setSourceDistance(int blocks) {
        PfConfig.batch(() -> {
            PfConfig.setSourceDistance(blocks);
            FittedZones.settingsChanged();
        });
    }

    /** Changes how many zones Here makes per particle at most, and regroups the latest scan's zones. */
    public static void setMaxZones(int zones) {
        PfConfig.batch(() -> {
            PfConfig.setMaxZones(zones);
            FittedZones.settingsChanged();
        });
    }

    /** Changes how much zones must overlap to merge, and regroups the latest scan's zones. */
    public static void setMergePercent(int percent) {
        PfConfig.batch(() -> {
            PfConfig.setMergePercent(percent);
            FittedZones.settingsChanged();
        });
    }

    /**
     * Like {@link #setMergePercent}, but nothing is written to the file: for each step while the slider is dragged,
     * so zones follow it live. The drag ends with {@link #setMergePercent}, which saves.
     */
    public static void previewMergePercent(int percent) {
        PfConfig.unsaved(() -> {
            PfConfig.setMergePercent(percent);
            FittedZones.settingsChanged();
        });
    }

    /**
     * Names of the rules hiding [particle] around [spawns] right now: "Everywhere" and zone names. Rules for groups it
     * belongs to count too, such as the particle from any block or a mod's particles ({@link FilterRules#entryMatches}).
     */
    public static List<String> hiddenBy(String particle, AABB spawns) {
        List<String> by = new ArrayList<>();
        if (anyMatches(PfConfig.hiddenParticles(), particle)) {
            by.add(Component.translatable("particlefilter.scan.col_everywhere").getString());
        }
        for (Zone zone : PfConfig.zonesHere()) {
            if (anyMatches(zone.particles, particle) && zone.box().intersects(spawns.inflate(0.5))) by.add("\"" + zone.name + "\"");
        }
        return by;
    }

    private static boolean anyMatches(List<String> entries, String particle) {
        for (String entry : entries) if (FilterRules.entryMatches(entry, particle)) return true;
        return false;
    }

    /** Short display name: see {@link ParticleKey#displayName}. */
    public static String displayName(String particle) {
        return ParticleKey.displayName(particle);
    }
}
