package particlefilter.scan;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import particlefilter.ParticleFilter;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Records which particles spawn near the block under the crosshair for a few seconds, and where. Results are listed
 * most frequent first; typed particles are keyed by particle type id, particles created without a type by class name.
 * Particles that are already hidden are recorded too, so they can be shown again from the results.
 *
 * The "Here" column hides particles in one zone: an existing zone around the scanned block, or a new one sized to the
 * spawn points of the particles switched on there. See {@link DiagnosticScan} for the scan of everything around the
 * player.
 */
public final class ParticleScan {

    /** One particle the scan saw: how often, the box its spawn points fill, and the closest one to the center. */
    public record Result(String particle, int count, boolean typed, AABB spawns, double nearest) {}

    public static final int RADIUS = 6;

    private static final SpawnRecorder recorder = new SpawnRecorder();
    private static int ticksLeft;
    private static int durationTicks = 1;

    private static List<Result> results = List.of();
    private static BlockPos target;
    private static Component targetName = Component.empty();
    private static int generation;
    /** Zone the Here column uses; null means a new zone, created when the first particle is switched on. */
    private static String hereZoneId;
    /** Zone this scan created, resized to fit its particles until the player edits it or scans again. */
    private static String fittedZoneId;
    /** Zone Here used before switching to a new zone, offered to switch back to. */
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
        fittedZoneId = null;
        previousZoneId = null;
        Zone existing = zonesAtTarget().stream().findFirst().orElse(null);
        hereZoneId = existing == null ? null : existing.id;
        durationTicks = ticksLeft = PfConfig.scanSeconds() * 20;
        recorder.start(at, RADIUS);
        generation++;
    }

    /** Forgets the running scan and the results, e.g. after leaving the world they belong to. */
    public static void reset() {
        recorder.stop();
        results = List.of();
        target = null;
        hereZoneId = null;
        fittedZoneId = null;
        previousZoneId = null;
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
            list.add(new Result(r.particle(), all.count(), r.typed(), all.box(), all.nearest()));
        }
        results = List.copyOf(list);
        generation++;
        list.forEach(r -> ParticleFilter.LOGGER.info("Scan: {} x {}{}", r.count(), r.particle(), r.typed() ? "" : " (untyped)"));
    }

    private static Result result(String particle) {
        for (Result r : results) if (r.particle().equals(particle)) return r;
        return null;
    }

    // ---------------------------------------------------------------- Here

    /** Zones here containing the scanned block, smallest first. */
    public static List<Zone> zonesAtTarget() {
        if (target == null) return List.of();
        return PfConfig.zonesHere().stream().filter(z -> z.contains(target)).sorted(Comparator.comparingLong(Zone::volume)).toList();
    }

    /** The zone Here hides particles in, or null when switching one on will create a zone. */
    public static Zone hereZone() {
        Zone zone = PfConfig.zoneById(hereZoneId);
        return zone != null && PfConfig.isInCurrentWorld(zone) ? zone : null;
    }

    /** True while Here uses a zone this scan made and keeps resizing. */
    public static boolean isFitting(Zone zone) {
        return zone != null && zone.id.equals(fittedZoneId);
    }

    /**
     * What the Here button switches to: from a zone, always a new zone; from a new zone, back to the zone used before
     * (or the smallest zone around the scanned block). Null means a new zone, and also "nothing to switch to" while
     * Here is already a new zone with no zone around.
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
        hereZoneId = next == null ? null : next.id;
    }

    public static boolean isHiddenHere(String particle) {
        Zone zone = hereZone();
        return zone != null && PfConfig.isHiddenInZone(zone, particle);
    }

    /**
     * Hides or shows [particle] in the Here zone. Hiding with no zone chosen creates one fitted to the particle's spawn
     * points. A zone the scan created keeps fitting the particles switched on in it, and is deleted again when the last
     * one is switched off.
     */
    public static void setHiddenHere(String particle, boolean hidden) {
        if (target == null) return;
        Zone zone = hereZone();
        if (zone == null) {
            if (!hidden) return;
            ZoneFit.Box box = fittedBox(List.of(particle));
            zone = PfConfig.createZone(box.minX(), box.minY(), box.minZ(), box.width(), box.height(), box.length());
            if (zone == null) return;
            hereZoneId = fittedZoneId = zone.id;
        }
        PfConfig.setHiddenInZone(zone, particle, hidden);
        if (!isFitting(zone)) return;
        if (!zone.hasEntries()) {
            PfConfig.deleteZone(zone);
            hereZoneId = fittedZoneId = null;
            return;
        }
        fittedBox(zone.particles).applyTo(zone);
        PfConfig.changed();
    }

    /** Called when the player edits [zone] by hand, so no scan resizes it any more. */
    public static void stopFitting(Zone zone) {
        if (isFitting(zone)) fittedZoneId = null;
        DiagnosticScan.stopFitting(zone);
    }

    /** The box a new zone would start from: the scanned block and the margin around it. */
    public static AABB newZonePreview() {
        if (target == null) return null;
        return fittedBox(List.of()).aabb();
    }

    /** Box holding the scanned block and the spawn points of [particles], plus the margin. */
    private static ZoneFit.Box fittedBox(Collection<String> particles) {
        List<AABB> spawns = new ArrayList<>();
        for (String particle : particles) {
            Result r = result(particle);
            if (r != null) spawns.add(r.spawns());
        }
        return ZoneFit.fit(target, spawns);
    }

    /** Short display name: the type id, or just the simple class name for untyped particles. */
    public static String displayName(String particle) {
        return particle.contains(":") ? particle : particle.substring(particle.lastIndexOf('.') + 1);
    }
}
