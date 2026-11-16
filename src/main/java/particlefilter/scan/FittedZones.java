package particlefilter.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The zones Here makes when it isn't set to an existing zone: one per source of a particle. A source is a group of
 * spots where the particle appeared (or, if it only appeared hidden, where it spawned) at most
 * {@link PfConfig#sourceDistance()} blocks apart ({@link ZoneFit#sources}); a particle gets zones for its busiest
 * {@link PfConfig#maxZones()} sources. Boxes overlapping by at least {@link PfConfig#mergePercent()} of the smaller one
 * merge into one zone around both ({@link ZoneFit#merge}).
 *
 * The zones the latest scan made are regrouped whenever a particle is switched on or off or a setting changes, until
 * the player edits one by hand or starts a new scan. Zones from earlier scans and zones made by hand count as hiding a
 * particle here when they list it and overlap where it spawned in this scan.
 */
public final class FittedZones {

    /** Sources per particle at [sourcesDistance], worked out when first needed. */
    private static final Map<String, List<ZoneFit.Source>> sources = new HashMap<>();
    private static int sourcesDistance;
    /** Zones the latest scan made and still resizes and regroups. */
    private static final Set<String> managedIds = new LinkedHashSet<>();
    private static boolean capped;

    private static final Map<String, List<ZoneFit.Box>> hereBoxesCache = new HashMap<>();
    private static int hereBoxesStamp;

    private FittedZones() {}

    /** Called when a scan starts or the results are forgotten: zones made so far keep their particles but no longer adjust. */
    static void reset() {
        sources.clear();
        managedIds.clear();
        hereBoxesCache.clear();
        capped = false;
    }

    /** Called when a scan finishes, so sources are worked out from its results. */
    static void resultsChanged() {
        sources.clear();
        hereBoxesCache.clear();
    }

    /** True if a zone the latest scan made would have been larger than the size limit, so it misses some spawn points. */
    public static boolean anyCapped() {
        return capped;
    }

    /** Every source of [particle] at the current distance, busiest first; empty if the scan didn't record it. */
    public static List<ZoneFit.Source> sourcesOf(String particle) {
        int distance = PfConfig.sourceDistance();
        if (distance != sourcesDistance) {
            sources.clear();
            sourcesDistance = distance;
        }
        ParticleScan.Result result = ParticleScan.result(particle);
        if (result == null) return List.of();
        return sources.computeIfAbsent(particle, p -> List.copyOf(ZoneFit.sources(result.fitBlocks(), distance)));
    }

    /** The sources Here makes zones for: the busiest {@link PfConfig#maxZones()}. */
    private static List<ZoneFit.Source> hereSources(String particle) {
        List<ZoneFit.Source> all = sourcesOf(particle);
        int most = PfConfig.maxZones();
        return all.size() > most ? all.subList(0, most) : all;
    }

    /** True if [particle] has more sources than Here makes zones for. */
    public static boolean hasMoreSources(String particle) {
        return sourcesOf(particle).size() > PfConfig.maxZones();
    }

    /** The zones the latest scan made that it still adjusts, in this world. */
    public static List<Zone> managedZones() {
        List<Zone> managed = new ArrayList<>();
        for (String id : managedIds) {
            Zone zone = PfConfig.zoneById(id);
            if (zone != null && PfConfig.isInCurrentWorld(zone)) managed.add(zone);
        }
        return managed;
    }

    /**
     * Zones in this world listing [particle], switched on or off, that hide it here: those the latest scan made, and
     * any other zone containing or touching a block where it spawned during the scan.
     */
    public static List<Zone> zonesFor(String particle) {
        ParticleScan.Result result = ParticleScan.result(particle);
        List<Zone> zones = new ArrayList<>();
        for (Zone zone : PfConfig.zonesHere()) {
            if (!lists(zone, particle)) continue;
            if (managedIds.contains(zone.id) || result != null && touchesAny(zone, result.spawnBlocks().keySet())) zones.add(zone);
        }
        return zones;
    }

    private static boolean touchesAny(Zone zone, Iterable<BlockPos> blocks) {
        AABB near = zone.box().inflate(1);
        for (BlockPos block : blocks) if (near.contains(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5)) return true;
        return false;
    }

    public static boolean isHidden(String particle) {
        return zonesFor(particle).stream().anyMatch(z -> z.particles.contains(particle));
    }

    /**
     * The zones [particle] would be hidden in with Here switched on, as boxes: its sources merged with each other and
     * with the zones the latest scan made, as {@link #regroup} would. While it is listed in zones here, those zones.
     * Worked out once per change.
     */
    public static List<ZoneFit.Box> hereBoxes(String particle) {
        int stamp = Objects.hash(PfConfig.revision(), ParticleScan.generation(), PfConfig.sourceDistance(), PfConfig.mergePercent(), PfConfig.maxZones());
        if (stamp != hereBoxesStamp) {
            hereBoxesCache.clear();
            hereBoxesStamp = stamp;
        }
        return hereBoxesCache.computeIfAbsent(particle, FittedZones::planHereBoxes);
    }

    private static List<ZoneFit.Box> planHereBoxes(String particle) {
        List<Zone> listed = zonesFor(particle);
        if (!listed.isEmpty() && listed.stream().noneMatch(z -> managedIds.contains(z.id))) {
            return listed.stream().map(ZoneFit.Box::of).toList();
        }
        Plan plan = plan(particle);
        List<ZoneFit.Box> boxes = new ArrayList<>();
        for (ZoneFit.Merged group : plan.groups()) {
            if (group.members().stream().anyMatch(i -> plan.members().get(i).particle.equals(particle))) boxes.add(group.box());
        }
        return List.copyOf(boxes);
    }

    /** Where switching on Here would put zones for [particle], or the zones it is in: see {@link #hereBoxes}. */
    public static List<AABB> previewFor(String particle) {
        return hereBoxes(particle).stream().map(ZoneFit.Box::aabb).toList();
    }

    /**
     * Hides [particle] in one fitted zone per source (merged with zones it overlaps enough), or removes it from every
     * zone here listing it. A particle already listed in zones here is switched back on in them instead.
     */
    static void setHidden(String particle, boolean hide) {
        List<Zone> zones = zonesFor(particle);
        boolean managed = zones.stream().anyMatch(z -> managedIds.contains(z.id));
        PfConfig.batch(() -> {
            if (hide) {
                if (!zones.isEmpty()) {
                    zones.forEach(z -> PfConfig.setHiddenInZone(z, particle, true));
                    if (managed) regroup(null);
                } else if (!hereSources(particle).isEmpty()) {
                    regroup(particle);
                }
            } else {
                zones.forEach(z -> PfConfig.setHiddenInZone(z, particle, false));
                if (managed) regroup(null);
            }
        });
    }

    /** Regroups the zones the latest scan made after a setting changed. */
    static void settingsChanged() {
        if (!managedZones().isEmpty()) regroup(null);
    }

    /** Called when the player edits [zone] by hand, so it keeps its size and particles from now on. */
    static void stopFitting(Zone zone) {
        if (zone != null) managedIds.remove(zone.id);
    }

    public static boolean isFitting(Zone zone) {
        return zone != null && managedIds.contains(zone.id);
    }

    /** A zone that hides a particle but misses some spots where it appeared, and the box covering them too. */
    public record Growth(Zone zone, ZoneFit.Box box) {}

    /**
     * For a particle that appeared despite the rules: a zone in this world that hides it, with the box it would grow to
     * so it also covers the spots where the particle appeared next to it ({@link ZoneFit#growNear}: within
     * {@link PfConfig#sourceDistance()} blocks, or that close to other such spots). Spots farther away are a separate
     * source and stay out. Of several such zones, the one growing least. Null if there is none. Zones the latest scan
     * still fits are left to it.
     */
    public static Growth growthFor(String particle) {
        ParticleScan.Result result = ParticleScan.result(particle);
        if (result == null || result.appeared().isEmpty()) return null;
        Growth best = null;
        for (Zone zone : PfConfig.zonesHere()) {
            if (!zone.particles.contains(particle) || managedIds.contains(zone.id)) continue;
            ZoneFit.Box current = ZoneFit.Box.of(zone);
            ZoneFit.Box grown = ZoneFit.growNear(current, result.appeared().keySet(), PfConfig.sourceDistance());
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

    // ---------------------------------------------------------------- Regrouping

    /** One source of a particle in a zone the latest scan made; [on] is false while switched off. */
    private record Member(String particle, boolean on, ZoneFit.Box box) {}

    /**
     * How the zones the latest scan made would be grouped with [added] (null for none) switched on: every source of
     * each particle they list, and the groups {@link ZoneFit#merge} makes of them.
     */
    private record Plan(List<Zone> managed, List<Member> members, List<ZoneFit.Merged> groups) {}

    /**
     * Rebuilds the zones the latest scan made from the particles they list (plus [added]): each particle gets a box per
     * source, and boxes overlapping enough merge ({@link ZoneFit#merge}). Zones are reused where they overlap a group's
     * box (then where they list one of its particles), created when needed, and deleted when no longer needed.
     */
    private static void regroup(String added) {
        PfConfig.batch(() -> applyPlan(plan(added)));
    }

    private static Plan plan(String added) {
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
        return new Plan(managed, members, merged);
    }

    /** Makes the managed zones match [plan]: reuses, resizes, creates and deletes zones. */
    private static void applyPlan(Plan plan) {
        List<Member> members = plan.members();
        List<Zone> unused = new ArrayList<>(plan.managed());
        capped = false;
        for (ZoneFit.Merged zoneGroup : plan.groups()) {
            List<Member> group = zoneGroup.members().stream().map(members::get).toList();
            ZoneFit.Box box = zoneGroup.box();
            capped |= box.capped();
            Zone zone = reusable(unused, group, box);
            if (zone == null) {
                zone = PfConfig.createZone(box.minX(), box.minY(), box.minZ(), box.width(), box.height(), box.length());
                if (zone == null) return;
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
}
