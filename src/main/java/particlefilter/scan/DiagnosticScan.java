package particlefilter.scan;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import particlefilter.ParticleFilter;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;

import java.util.ArrayList;
import java.util.Comparator;
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
 * Its Here column makes one zone per particle, fitted to where that particle appeared (or, if it only appeared hidden,
 * to where it spawned). When one particle's box lies entirely inside another's they share the larger zone. These zones
 * are regrouped whenever a particle is switched on or off, until the player edits one by hand or runs a new diagnostic.
 */
public final class DiagnosticScan {

    /** One particle in one section: how many spawned, the box they fill, and how close the nearest came. */
    /** [blocks]: the blocks it spawned in, as {@link BlockPos#asLong} (at most {@link SpawnRecorder#MAX_BLOCKS}). */
    public record Result(String particle, boolean typed, int count, AABB spawns, double nearest, LongSet blocks) {}

    private static final SpawnRecorder recorder = new SpawnRecorder();
    private static int ticksLeft;
    private static int durationTicks = 1;
    private static int radius;
    private static boolean done;
    private static List<Result> appearing = List.of();
    private static List<Result> hidden = List.of();
    /** Spawn box per particle used for fitting: where it appeared, or where it spawned hidden if it never appeared. */
    private static Map<String, AABB> fitSpawns = Map.of();
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
        fitSpawns = Map.of();
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
        fitSpawns = Map.of();
        createdIds.clear();
        managedIds.clear();
        capped = false;
        generation++;
    }

    public static void record(String particle, boolean typed, double x, double y, double z, boolean wasHidden) {
        recorder.record(particle, typed, x, y, z, wasHidden);
    }

    public static void tick() {
        if (!recorder.isRunning() || --ticksLeft > 0) return;
        recorder.stop();
        List<Result> shownList = new ArrayList<>();
        List<Result> hiddenList = new ArrayList<>();
        Map<String, AABB> fit = new LinkedHashMap<>();
        for (SpawnRecorder.Recorded r : recorder.collect()) {
            if (r.shown() != null) shownList.add(new Result(r.particle(), r.typed(), r.shown().count(), r.shown().box(), r.shown().nearest(), r.shown().blocks()));
            if (r.hidden() != null) hiddenList.add(new Result(r.particle(), r.typed(), r.hidden().count(), r.hidden().box(), r.hidden().nearest(), r.hidden().blocks()));
            fit.put(r.particle(), (r.shown() != null ? r.shown() : r.hidden()).box());
        }
        Comparator<Result> byCount = (a, b) -> Integer.compare(b.count(), a.count());
        shownList.sort(byCount);
        hiddenList.sort(byCount);
        appearing = List.copyOf(shownList);
        hidden = List.copyOf(hiddenList);
        fitSpawns = fit;
        done = true;
        generation++;
        appearing.forEach(r -> ParticleFilter.LOGGER.info("Diagnostic: {} x {} appeared", r.count(), r.particle()));
        hidden.forEach(r -> ParticleFilter.LOGGER.info("Diagnostic: {} x {} hidden", r.count(), r.particle()));
    }

    // ---------------------------------------------------------------- Here

    /** The zone from this diagnostic that lists [particle], switched on or off; null if none. */
    public static Zone zoneFor(String particle) {
        for (String id : createdIds) {
            Zone zone = PfConfig.zoneById(id);
            if (zone != null && PfConfig.isInCurrentWorld(zone)
                && (zone.particles.contains(particle) || zone.disabledParticles.contains(particle))) return zone;
        }
        return null;
    }

    public static boolean isHiddenHere(String particle) {
        Zone zone = zoneFor(particle);
        return zone != null && zone.particles.contains(particle);
    }

    /** Where switching on Here would put a zone for [particle] on its own. */
    public static AABB previewFor(String particle) {
        var spawns = fitSpawns.get(particle);
        return spawns == null ? null : ZoneFit.fit(null, List.of(spawns)).aabb();
    }

    /** Hides [particle] in its own fitted zone (shared when nested in another's), or removes it from its zone. */
    public static void setHiddenHere(String particle, boolean hide) {
        Zone zone = zoneFor(particle);
        if (hide) {
            if (zone != null) {
                PfConfig.setHiddenInZone(zone, particle, true); // listed already: switch it back on
                return;
            }
            if (!fitSpawns.containsKey(particle)) return;
            regroup(particle);
        } else if (zone != null) {
            PfConfig.setHiddenInZone(zone, particle, false);
            if (managedIds.contains(zone.id)) regroup(null);
        }
    }

    /** How far outside a zone (or the spots already joined to it) a particle may appear and still be taken in, in blocks. */
    private static final int NEAR_ZONE = 2;

    /** A zone that hides a particle but misses some spots where it appeared, and the box covering them too. */
    public record Growth(Zone zone, ZoneFit.Box box) {}

    /**
     * For a particle that appeared despite the rules: a zone in this world that hides it, with the box it would grow to
     * so it also covers the spots where the particle appeared next to it ({@link ZoneFit#growNear}: within
     * [NEAR_ZONE] blocks, or that close to other such spots). Spots farther away are a separate source and stay out.
     * Of several such zones, the one growing least. Null if there is none. Zones this diagnostic still fits are left
     * to it.
     */
    public static Growth growthFor(String particle) {
        Result appeared = appearing.stream().filter(r -> r.particle().equals(particle)).findFirst().orElse(null);
        if (appeared == null) return null;
        List<BlockPos> blocks = new ArrayList<>();
        appeared.blocks().forEach((long b) -> blocks.add(BlockPos.of(b)));
        Growth best = null;
        for (Zone zone : PfConfig.zonesHere()) {
            if (!zone.particles.contains(particle) || managedIds.contains(zone.id)) continue;
            ZoneFit.Box current = ZoneFit.Box.of(zone);
            ZoneFit.Box grown = ZoneFit.growNear(current, blocks, NEAR_ZONE);
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

    /** Called when the player edits [zone] by hand, so it keeps its size and particles from now on. */
    static void stopFitting(Zone zone) {
        if (zone != null) managedIds.remove(zone.id);
    }

    private record Member(String particle, boolean on, ZoneFit.Box box) {}

    /**
     * Rebuilds the zones this diagnostic manages from the particles they list (plus [added]): each particle gets a box
     * fitted to its spawn points; a box inside another joins that one. Zones are reused where they already hold one of
     * a group's particles, created when needed, and deleted when no longer needed.
     */
    private static void regroup(String added) {
        List<Zone> managed = new ArrayList<>();
        for (String id : createdIds) {
            Zone zone = PfConfig.zoneById(id);
            if (zone != null && managedIds.contains(id) && PfConfig.isInCurrentWorld(zone)) managed.add(zone);
        }
        List<Member> members = new ArrayList<>();
        for (Zone zone : managed) {
            for (String p : zone.particles) members.add(member(p, true, zone));
            for (String p : zone.disabledParticles) members.add(member(p, false, zone));
        }
        if (added != null) members.add(member(added, true, null));
        members.sort(Comparator.comparingLong((Member m) -> m.box.volume()).reversed());

        List<List<Member>> groups = new ArrayList<>();
        for (Member m : members) {
            List<Member> container = groups.stream().filter(g -> g.get(0).box.contains(m.box)).findFirst().orElse(null);
            if (container != null) container.add(m);
            else groups.add(new ArrayList<>(List.of(m)));
        }

        Set<Zone> unused = new LinkedHashSet<>(managed);
        capped = false;
        for (List<Member> group : groups) {
            ZoneFit.Box box = group.get(0).box;
            capped |= box.capped();
            // Prefer the zone that already holds the group's largest particle, then any of its particles.
            String lead = group.get(0).particle;
            Zone zone = unused.stream().filter(z -> lists(z, lead)).findFirst()
                .orElse(unused.stream().filter(z -> group.stream().anyMatch(m -> lists(z, m.particle))).findFirst()
                .orElse(unused.stream().findFirst().orElse(null)));
            if (zone == null) {
                zone = PfConfig.createZone(box.minX(), box.minY(), box.minZ(), box.width(), box.height(), box.length());
                if (zone == null) return;
                createdIds.add(zone.id);
                managedIds.add(zone.id);
            }
            unused.remove(zone);
            box.applyTo(zone);
            zone.particles = new ArrayList<>(group.stream().filter(Member::on).map(Member::particle).toList());
            zone.disabledParticles = new ArrayList<>(group.stream().filter(m -> !m.on).map(Member::particle).toList());
        }
        for (Zone zone : unused) {
            PfConfig.deleteZone(zone);
            createdIds.remove(zone.id);
            managedIds.remove(zone.id);
        }
        PfConfig.changed();
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

    private static Member member(String particle, boolean on, Zone current) {
        var spawns = fitSpawns.get(particle);
        ZoneFit.Box box = spawns != null ? ZoneFit.fit(null, List.of(spawns)) : current != null ? ZoneFit.Box.of(current) : null;
        return new Member(particle, on, box);
    }
}
