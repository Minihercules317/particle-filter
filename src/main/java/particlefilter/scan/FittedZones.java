package particlefilter.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.filter.FilterRules;
import particlefilter.filter.ParticlePattern;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The zones Here makes when it isn't set to an existing zone: one per source of an entry, a particle or a group of
 * them. A source is a group of spots where the entry's particles appeared (or, for those that only appeared hidden,
 * where they spawned) at most {@link PfConfig#sourceDistance()} blocks apart ({@link ZoneFit#sources}); an entry gets
 * zones for its busiest {@link PfConfig#maxZones()} sources. Boxes overlapping by at least {@link PfConfig#mergePercent()}
 * of the smaller one merge into one zone around both ({@link ZoneFit#merge}).
 *
 * The zones the latest scan made are regrouped whenever an entry is switched on or off or a setting changes, until the
 * player edits one by hand or starts a new scan. Zones from earlier scans and zones made by hand count as hiding a
 * particle here when they list an entry for it and contain or touch a block where it spawned in this scan.
 */
public final class FittedZones {

    /** Sources per entry at [sourcesDistance], worked out when first needed. */
    private static final Map<String, List<ZoneFit.Source>> sources = new HashMap<>();
    private static int sourcesDistance;
    /** Zones the latest scan made and still resizes and regroups. */
    private static final Set<String> managedIds = new LinkedHashSet<>();
    private static boolean capped;

    private static final Map<List<String>, List<ZoneFit.Box>> hereBoxesCache = new HashMap<>();
    private static int hereBoxesStamp;

    private FittedZones() {}

    /** Called when a scan starts or the results are forgotten: zones made so far keep their entries but no longer adjust. */
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

    /** Every source of [entry]'s particles at the current distance, busiest first; empty if the scan saw none. */
    public static List<ZoneFit.Source> sourcesOf(String entry) {
        int distance = PfConfig.sourceDistance();
        if (distance != sourcesDistance) {
            sources.clear();
            sourcesDistance = distance;
        }
        Map<BlockPos, Integer> blocks = ParticleScan.blocksFor(entry).fit();
        if (blocks.isEmpty()) return List.of();
        return sources.computeIfAbsent(entry, e -> List.copyOf(ZoneFit.sources(blocks, distance)));
    }

    /** The sources Here makes zones for: the busiest {@link PfConfig#maxZones()}. */
    private static List<ZoneFit.Source> hereSources(String entry) {
        List<ZoneFit.Source> all = sourcesOf(entry);
        int most = PfConfig.maxZones();
        return all.size() > most ? all.subList(0, most) : all;
    }

    /** True if [entry] has more sources than Here makes zones for. */
    public static boolean hasMoreSources(String entry) {
        return sourcesOf(entry).size() > PfConfig.maxZones();
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

    /** Zones that hide things here for the particles [keys]: the latest scan's, and any containing or touching their spawns. */
    public static List<Zone> zonesAround(Collection<String> keys) {
        Set<Zone> zones = new LinkedHashSet<>(managedZones());
        for (String key : keys) zones.addAll(touching(key));
        return List.copyOf(zones);
    }

    private static final Map<String, List<Zone>> touchingCache = new HashMap<>();
    private static int touchingStamp;

    /** Zones in this world containing or touching a block where [entry]'s particles spawned; worked out once per change. */
    private static List<Zone> touching(String entry) {
        int stamp = Objects.hash(PfConfig.revision(), ParticleScan.generation());
        if (stamp != touchingStamp) {
            touchingCache.clear();
            touchingStamp = stamp;
        }
        return touchingCache.computeIfAbsent(entry, e -> {
            Set<BlockPos> blocks = ParticleScan.blocksFor(e).spawn().keySet();
            return PfConfig.zonesHere().stream().filter(zone -> touchesAny(zone, blocks)).toList();
        });
    }

    /** The switched-on entries of the zones around where [key] spawned: what Here's switches read for that particle. */
    public static List<String> entriesAround(String key) {
        List<String> entries = new ArrayList<>();
        for (Zone zone : zonesAround(List.of(key))) entries.addAll(zone.particles);
        return entries;
    }

    /** Zones listing [entry], switched on or off: the latest scan's, and any around where its particles spawned. */
    public static List<Zone> zonesFor(String entry) {
        List<Zone> zones = new ArrayList<>();
        for (Zone zone : PfConfig.zonesHere()) {
            if (!lists(zone, entry)) continue;
            if (managedIds.contains(zone.id) || touching(entry).contains(zone)) zones.add(zone);
        }
        return zones;
    }

    private static boolean touchesAny(Zone zone, Iterable<BlockPos> blocks) {
        AABB near = zone.box().inflate(1);
        for (BlockPos block : blocks) if (near.contains(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5)) return true;
        return false;
    }

    /**
     * The zones [entries] would be hidden in with Here switched on, as boxes: their sources merged with each other and
     * with the zones the latest scan made, as {@link #regroup} would. While all of them are listed in zones here and
     * none of those is the latest scan's, those zones. Worked out once per change.
     */
    public static List<ZoneFit.Box> hereBoxes(List<String> entries) {
        int stamp = Objects.hash(PfConfig.revision(), ParticleScan.generation(), PfConfig.sourceDistance(), PfConfig.mergePercent(), PfConfig.maxZones());
        if (stamp != hereBoxesStamp) {
            hereBoxesCache.clear();
            hereBoxesStamp = stamp;
        }
        return hereBoxesCache.computeIfAbsent(List.copyOf(entries), FittedZones::planHereBoxes);
    }

    private static List<ZoneFit.Box> planHereBoxes(List<String> entries) {
        Set<Zone> listed = new LinkedHashSet<>();
        boolean allListed = true;
        for (String entry : entries) {
            List<Zone> zones = zonesFor(entry);
            allListed &= !zones.isEmpty();
            listed.addAll(zones);
        }
        if (allListed && listed.stream().noneMatch(z -> managedIds.contains(z.id))) return listed.stream().map(ZoneFit.Box::of).toList();
        Plan plan = plan(entries);
        List<ZoneFit.Box> boxes = new ArrayList<>();
        for (ZoneFit.Merged group : plan.groups()) {
            if (group.members().stream().anyMatch(i -> entries.contains(plan.members().get(i).entry))) boxes.add(group.box());
        }
        return List.copyOf(boxes);
    }

    /** Where switching on Here would put zones for [entries], or the zones they are in: see {@link #hereBoxes}. */
    public static List<AABB> previewFor(List<String> entries) {
        return hereBoxes(entries).stream().map(ZoneFit.Box::aabb).toList();
    }

    /**
     * Hides [entries] in fitted zones, one per source, merged with zones they overlap enough. An entry already listed
     * in zones here is switched back on in them instead.
     */
    static void hide(List<String> entries) {
        PfConfig.batch(() -> {
            List<String> added = new ArrayList<>();
            boolean managed = false;
            for (String entry : entries) {
                List<Zone> zones = zonesFor(entry);
                if (zones.isEmpty()) {
                    if (!hereSources(entry).isEmpty()) added.add(entry);
                    continue;
                }
                for (Zone zone : zones) {
                    PfConfig.setHiddenInZone(zone, entry, true);
                    managed |= managedIds.contains(zone.id);
                }
            }
            if (!added.isEmpty() || managed) regroup(added);
        });
    }

    /** Deletes the entries [which] accepts from the zones around the particles [keys]; the latest scan's zones regroup. */
    static void show(Collection<String> keys, Predicate<String> which) {
        PfConfig.batch(() -> {
            boolean managed = false;
            for (Zone zone : zonesAround(keys)) {
                if (zone.particles.stream().noneMatch(which) && zone.disabledParticles.stream().noneMatch(which)) continue;
                PfConfig.showInZone(zone, which);
                managed |= managedIds.contains(zone.id);
            }
            if (managed) regroup(List.of());
        });
    }

    /** Regroups the zones the latest scan made after a setting changed. */
    static void settingsChanged() {
        if (!managedZones().isEmpty()) regroup(List.of());
    }

    /** Called when the player edits [zone] by hand, so it keeps its size and entries from now on. */
    static void stopFitting(Zone zone) {
        if (zone != null) managedIds.remove(zone.id);
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
            if (managedIds.contains(zone.id) || zone.particles.stream().noneMatch(e -> FilterRules.entryMatches(e, particle))) continue;
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

    /** One source of an entry in a zone the latest scan made; [on] is false while switched off. */
    private record Member(String entry, boolean on, ZoneFit.Box box) {}

    /**
     * How the zones the latest scan made would be grouped with [added] switched on: every source of each entry they
     * list, and the groups {@link ZoneFit#merge} makes of them.
     */
    private record Plan(List<Zone> managed, List<Member> members, List<ZoneFit.Merged> groups) {}

    /**
     * Rebuilds the zones the latest scan made from the entries they list (plus [added]): each entry gets a box per
     * source, and boxes overlapping enough merge ({@link ZoneFit#merge}). Zones are reused where they overlap a group's
     * box (then where they list one of its entries), created when needed, and deleted when no longer needed.
     */
    private static void regroup(List<String> added) {
        PfConfig.batch(() -> applyPlan(plan(added)));
    }

    private static Plan plan(List<String> added) {
        List<Zone> managed = managedZones();
        Map<String, Boolean> entries = new LinkedHashMap<>();
        Map<String, Zone> fallback = new HashMap<>();
        for (Zone zone : managed) {
            for (String e : zone.particles) entries.put(e, true);
            for (String e : zone.disabledParticles) entries.putIfAbsent(e, false);
            for (String e : zone.particles) fallback.putIfAbsent(e, zone);
            for (String e : zone.disabledParticles) fallback.putIfAbsent(e, zone);
        }
        for (String e : added) entries.put(e, true);

        List<Member> members = new ArrayList<>();
        entries.forEach((entry, on) -> {
            List<ZoneFit.Source> own = hereSources(entry);
            if (!own.isEmpty()) own.forEach(s -> members.add(new Member(entry, on, s.box())));
            else if (fallback.containsKey(entry)) members.add(new Member(entry, on, ZoneFit.Box.of(fallback.get(entry))));
        });
        members.sort(Comparator.comparingLong((Member m) -> m.box.volume()).reversed());
        List<ZoneFit.Merged> merged = ZoneFit.merge(members.stream().map(Member::box).toList(), PfConfig.mergePercent());
        return new Plan(managed, members, merged);
    }

    /** Makes the managed zones match [plan]: reuses, resizes, creates and deletes zones. */
    private static void applyPlan(Plan plan) {
        List<Member> members = plan.members();
        List<Zone> unused = new ArrayList<>(plan.managed());
        ParticlePattern.TagCheck tags = FilterRules.tags();
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
            for (Member m : group) (m.on ? on : off).add(m.entry);
            // A group switched on in the zone makes narrower entries in it redundant.
            on.removeIf(e -> on.stream().anyMatch(o -> !o.equals(e) && ParticlePattern.covers(o, e, tags)));
            off.removeIf(e -> on.stream().anyMatch(o -> ParticlePattern.covers(o, e, tags)));
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
     * most, else one listing its largest entry, else one listing any of its entries. Null if none: make a zone.
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
        String lead = group.get(0).entry;
        for (Zone zone : unused) if (lists(zone, lead)) return zone;
        for (Zone zone : unused) if (group.stream().anyMatch(m -> lists(zone, m.entry))) return zone;
        return null;
    }

    private static boolean lists(Zone zone, String entry) {
        return zone.particles.contains(entry) || zone.disabledParticles.contains(entry);
    }
}
