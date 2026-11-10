package particlefilter.scan;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import particlefilter.ParticleFilter;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records every particle around the player within render distance for a few seconds, split into particles that still
 * appeared and particles the rules hid, so players can tell what is still costing them.
 *
 * Its Here column makes one zone per source of a particle: the spots where it appeared (or, if it only appeared hidden,
 * where it spawned) split into groups at most {@link PfConfig#sourceDistance()} blocks apart ({@link ZoneFit#sources}),
 * the busiest [MAX_SOURCES] of them. Boxes overlapping by at least {@link PfConfig#mergePercent()} of the smaller one
 * merge into one zone around both ({@link ZoneFit#merge}). These zones are regrouped whenever a particle is switched on
 * or off or either setting changes, until the player edits one by hand or runs a new diagnostic.
 */
public final class DiagnosticScan {

    /**
     * One particle in one section: how many spawned, the box they fill, how close the nearest came, and how many
     * spawned in each block (at most {@link SpawnRecorder#MAX_BLOCKS} blocks).
     */
    public record Result(String particle, boolean typed, int count, AABB spawns, double nearest, Map<BlockPos, Integer> blocks) {}

    /** Most zones Here makes for one particle: its busiest sources. Above this, Everywhere is suggested instead. */
    public static final int MAX_SOURCES = 8;

    private static final SpawnRecorder recorder = new SpawnRecorder();
    private static int ticksLeft;
    private static int durationTicks = 1;
    private static int radius;
    private static boolean done;
    private static List<Result> appearing = List.of();
    private static List<Result> hidden = List.of();
    /** Spawn blocks per particle used for fitting: where it appeared, or where it spawned hidden if it never appeared. */
    private static Map<String, Map<BlockPos, Integer>> fitBlocks = Map.of();
    /** Sources per particle at [sourcesDistance], worked out when first needed. */
    private static final Map<String, List<ZoneFit.Source>> sources = new HashMap<>();
    private static int sourcesDistance;
    private static int generation;
    /** Zones diagnostics created in this world (Here shows them), and those the latest one still resizes and regroups. */
    private static final List<String> createdIds = new ArrayList<>();
    private static final Set<String> managedIds = new HashSet<>();
    private static boolean capped;

    private DiagnosticScan() {}

    public static boolean isRunning() {
        return recorder.isRunning();
    }

    /** True once a diagnostic has finished in this world. */
    public static boolean hasRun() {
        return done;
    }

    public static int secondsLeft() {
        return (ticksLeft + 19) / 20;
    }

    public static float progress() {
        return isRunning() ? 1 - ticksLeft / (float) durationTicks : 0;
    }

    /** Distance in blocks the running or last diagnostic records within. */
    public static int radius() {
        return radius;
    }

    /** Particles that appeared despite the rules, most frequent first. */
    public static List<Result> appearing() {
        return appearing;
    }

    /** Particles the rules hid, most frequent first. */
    public static List<Result> hidden() {
        return hidden;
    }

    public static int generation() {
        return generation;
    }

    /** True if a zone this diagnostic made would have been larger than the size limit, so it misses some spawn points. */
    public static boolean anyCapped() {
        return capped;
    }

    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        radius = mc.options.getEffectiveRenderDistance() * 16;
        appearing = hidden = List.of();
        fitBlocks = Map.of();
        sources.clear();
        managedIds.clear(); // zones from earlier diagnostics keep their particles but are no longer resized
        capped = false;
        durationTicks = ticksLeft = PfConfig.diagnosticSeconds() * 20;
        recorder.start(mc.player.getEyePosition(), radius);
        generation++;
    }

    public static void reset() {
        recorder.stop();
        done = false;
        appearing = hidden = List.of();
        fitBlocks = Map.of();
        sources.clear();
        createdIds.clear();
        managedIds.clear();
        capped = false;
        generation++;
    }

    private static Result result(String particle, boolean typed, SpawnRecorder.Spawns spawns) {
        return new Result(particle, typed, spawns.count(), spawns.box(), spawns.nearest(), Map.copyOf(spawns.blockCounts()));
    }

    public static void record(String particle, boolean typed, double x, double y, double z, boolean wasHidden) {
        recorder.record(particle, typed, x, y, z, wasHidden);
    }

    public static void tick() {
        if (!recorder.isRunning() || --ticksLeft > 0) return;
        recorder.stop();
        List<Result> shownList = new ArrayList<>();
        List<Result> hiddenList = new ArrayList<>();
        Map<String, Map<BlockPos, Integer>> fit = new LinkedHashMap<>();
        for (SpawnRecorder.Recorded r : recorder.collect()) {
            Result shown = r.shown() == null ? null : result(r.particle(), r.typed(), r.shown());
            Result hid = r.hidden() == null ? null : result(r.particle(), r.typed(), r.hidden());
            if (shown != null) shownList.add(shown);
            if (hid != null) hiddenList.add(hid);
            fit.put(r.particle(), (shown != null ? shown : hid).blocks());
        }
        Comparator<Result> byCount = (a, b) -> Integer.compare(b.count(), a.count());
        shownList.sort(byCount);
        hiddenList.sort(byCount);
        appearing = List.copyOf(shownList);
        hidden = List.copyOf(hiddenList);
        fitBlocks = fit;
        sources.clear();
        done = true;
        generation++;
        appearing.forEach(r -> ParticleFilter.LOGGER.info("Diagnostic: {} x {} appeared", r.count(), r.particle()));
        hidden.forEach(r -> ParticleFilter.LOGGER.info("Diagnostic: {} x {} hidden", r.count(), r.particle()));
    }

    // ---------------------------------------------------------------- Here

    /** Every source of [particle] at the current distance, busiest first; empty if it wasn't recorded. */
    public static List<ZoneFit.Source> sourcesOf(String particle) {
        int distance = PfConfig.sourceDistance();
        if (distance != sourcesDistance) {
            sources.clear();
            sourcesDistance = distance;
        }
        Map<BlockPos, Integer> blocks = fitBlocks.get(particle);
        if (blocks == null) return List.of();
        return sources.computeIfAbsent(particle, p -> List.copyOf(ZoneFit.sources(blocks, distance)));
    }

    /** The sources Here makes zones for: the busiest [MAX_SOURCES]. */
    private static List<ZoneFit.Source> hereSources(String particle) {
        List<ZoneFit.Source> all = sourcesOf(particle);
        return all.size() > MAX_SOURCES ? all.subList(0, MAX_SOURCES) : all;
    }

    /** Changes how far apart spots may be and still count as one source, and regroups this diagnostic's zones. */
    public static void setSourceDistance(int blocks) {
        PfConfig.setSourceDistance(blocks);
        if (!managedZones().isEmpty()) regroup(null);
    }

    /** Changes how much zones must overlap to merge, and regroups this diagnostic's zones. */
    public static void setMergePercent(int percent) {
        PfConfig.setMergePercent(percent);
        if (!managedZones().isEmpty()) regroup(null);
    }

    /** Zones from diagnostics in this world that list [particle], switched on or off. */
    public static List<Zone> zonesFor(String particle) {
        List<Zone> zones = new ArrayList<>();
        for (String id : createdIds) {
            Zone zone = PfConfig.zoneById(id);
            if (zone != null && PfConfig.isInCurrentWorld(zone) && lists(zone, particle)) zones.add(zone);
        }
        return zones;
    }

    public static boolean isHiddenHere(String particle) {
        return zonesFor(particle).stream().anyMatch(z -> z.particles.contains(particle));
    }

    /** Where switching on Here would put zones for [particle] on its own: one box per source it gets a zone for. */
    public static List<AABB> previewFor(String particle) {
        return hereSources(particle).stream().map(s -> s.box().aabb()).toList();
    }

    /**
     * Hides [particle] in one fitted zone per source (merged with zones it overlaps enough), or removes it from every
     * diagnostic zone listing it.
     */
    public static void setHiddenHere(String particle, boolean hide) {
        List<Zone> zones = zonesFor(particle);
        boolean managed = zones.stream().anyMatch(z -> managedIds.contains(z.id));
        if (hide) {
            if (!zones.isEmpty()) {
                zones.forEach(z -> PfConfig.setHiddenInZone(z, particle, true)); // listed already: switch it back on
                if (managed) regroup(null);
                return;
            }
            if (hereSources(particle).isEmpty()) return;
            regroup(particle);
        } else {
            zones.forEach(z -> PfConfig.setHiddenInZone(z, particle, false));
            if (managed) regroup(null);
        }
    }

    /** Called when the player edits [zone] by hand, so it keeps its size and particles from now on. */
    static void stopFitting(Zone zone) {
        if (zone != null) managedIds.remove(zone.id);
    }

    /** A zone that hides a particle but misses some spots where it appeared, and the box covering them too. */
    public record Growth(Zone zone, ZoneFit.Box box) {}

    /**
     * For a particle that appeared despite the rules: a zone in this world that hides it, with the box it would grow to
     * so it also covers the spots where the particle appeared next to it ({@link ZoneFit#growNear}: within
     * {@link PfConfig#sourceDistance()} blocks, or that close to other such spots). Spots farther away are a separate
     * source and stay out. Of several such zones, the one growing least. Null if there is none. Zones this diagnostic
     * still fits are left to it.
     */
    public static Growth growthFor(String particle) {
        Result appeared = appearing.stream().filter(r -> r.particle().equals(particle)).findFirst().orElse(null);
        if (appeared == null) return null;
        Growth best = null;
        for (Zone zone : PfConfig.zonesHere()) {
            if (!zone.particles.contains(particle) || managedIds.contains(zone.id)) continue;
            ZoneFit.Box current = ZoneFit.Box.of(zone);
            ZoneFit.Box grown = ZoneFit.growNear(current, appeared.blocks().keySet(), PfConfig.sourceDistance());
            if (grown.sameBlocks(current)) continue;
            if (best == null || grown.volume() - current.volume() < best.box.volume() - best.zone.volume()) best = new Growth(zone, grown);
        }
        return best;
    }

    /** Grows the zone in [growth]; it keeps that size, as if edited by hand. */
    public static void grow(Growth growth) {
        if (growth == null || PfConfig.zoneById(growth.zone().id) == null) return;
        ParticleScan.stopFitting(growth.zone());
        growth.box().applyTo(growth.zone());
        PfConfig.changed();
    }

    /** One source of a particle in a zone this diagnostic manages; [on] is false while switched off. */
    private record Member(String particle, boolean on, ZoneFit.Box box) {}

    private static List<Zone> managedZones() {
        List<Zone> managed = new ArrayList<>();
        for (String id : createdIds) {
            Zone zone = PfConfig.zoneById(id);
            if (zone != null && managedIds.contains(id) && PfConfig.isInCurrentWorld(zone)) managed.add(zone);
        }
        return managed;
    }

    /**
     * Rebuilds the zones this diagnostic manages from the particles they list (plus [added]): each particle gets a box
     * per source, and boxes overlapping enough merge ({@link ZoneFit#merge}). Zones are reused where they overlap a
     * group's box (then where they list one of its particles), created when needed, and deleted when no longer needed.
     */
    private static void regroup(String added) {
        List<Zone> managed = managedZones();
        Map<String, Boolean> particles = new LinkedHashMap<>();
        Map<String, Zone> fallback = new HashMap<>();
        for (Zone zone : managed) {
            for (String p : zone.particles) particles.put(p, true);
            for (String p : zone.disabledParticles) particles.putIfAbsent(p, false);
            for (String p : zone.particles) fallback.putIfAbsent(p, zone);
            for (String p : zone.disabledParticles) fallback.putIfAbsent(p, zone);
        }
        if (added != null) particles.put(added, true);

        List<Member> members = new ArrayList<>();
        particles.forEach((particle, on) -> {
            List<ZoneFit.Source> own = hereSources(particle);
            if (!own.isEmpty()) own.forEach(s -> members.add(new Member(particle, on, s.box())));
            else if (fallback.containsKey(particle)) members.add(new Member(particle, on, ZoneFit.Box.of(fallback.get(particle))));
        });
        members.sort(Comparator.comparingLong((Member m) -> m.box.volume()).reversed());
        List<ZoneFit.Merged> merged = ZoneFit.merge(members.stream().map(Member::box).toList(), PfConfig.mergePercent());

        List<Zone> unused = new ArrayList<>(managed);
        capped = false;
        for (ZoneFit.Merged zoneGroup : merged) {
            List<Member> group = zoneGroup.members().stream().map(members::get).toList();
            ZoneFit.Box box = zoneGroup.box();
            capped |= box.capped();
            Zone zone = reusable(unused, group, box);
            if (zone == null) {
                zone = PfConfig.createZone(box.minX(), box.minY(), box.minZ(), box.width(), box.height(), box.length());
                if (zone == null) return;
                createdIds.add(zone.id);
                managedIds.add(zone.id);
            }
            unused.remove(zone);
            box.applyTo(zone);
            Set<String> on = new LinkedHashSet<>();
            Set<String> off = new LinkedHashSet<>();
            for (Member m : group) (m.on ? on : off).add(m.particle);
            off.removeAll(on);
            zone.particles = new ArrayList<>(on);
            zone.disabledParticles = new ArrayList<>(off);
        }
        for (Zone zone : unused) {
            PfConfig.deleteZone(zone);
            createdIds.remove(zone.id);
            managedIds.remove(zone.id);
        }
        PfConfig.changed();
    }

    /**
     * The unused zone to keep for [group] in [groupBox], so zones keep their names and colors: the one overlapping it
     * most, else one listing its largest particle, else one listing any of its particles. Null if none: make a zone.
     */
    private static Zone reusable(List<Zone> unused, List<Member> group, ZoneFit.Box groupBox) {
        AABB box = groupBox.aabb();
        Zone best = null;
        double bestOverlap = 0;
        for (Zone zone : unused) {
            if (!zone.box().intersects(box)) continue;
            AABB overlap = zone.box().intersect(box);
            double volume = overlap.getXsize() * overlap.getYsize() * overlap.getZsize();
            if (volume > bestOverlap) {
                best = zone;
                bestOverlap = volume;
            }
        }
        if (best != null) return best;
        String lead = group.get(0).particle;
        for (Zone zone : unused) if (lists(zone, lead)) return zone;
        for (Zone zone : unused) if (group.stream().anyMatch(m -> lists(zone, m.particle))) return zone;
        return null;
    }

    private static boolean lists(Zone zone, String particle) {
        return zone.particles.contains(particle) || zone.disabledParticles.contains(particle);
    }

    /** Names of the rules hiding [particle] around [spawns] right now: "Everywhere" and zone names. */
    public static List<String> hiddenBy(String particle, AABB spawns) {
        List<String> by = new ArrayList<>();
        if (PfConfig.isHiddenEverywhere(particle)) by.add(net.minecraft.network.chat.Component.translatable("particlefilter.scan.col_everywhere").getString());
        for (Zone zone : PfConfig.zonesHere()) {
            if (zone.particles.contains(particle) && zone.box().intersects(spawns.inflate(0.5))) by.add("\"" + zone.name + "\"");
        }
        return by;
    }
}
