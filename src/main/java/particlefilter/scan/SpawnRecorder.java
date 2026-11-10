package particlefilter.scan;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the spawn points of every particle within a radius of a center while running, split into particles that
 * appeared and particles that the rules hid. Particles can spawn off the client thread, so recording is synchronized.
 */
final class SpawnRecorder {

    /** Most distinct blocks remembered per particle and kind; spawns in further blocks still count and widen the box. */
    static final int MAX_BLOCKS = 1024;

    /**
     * Spawn points of one particle: how many, the box they fill, the closest one to the center, and how many spawned
     * in each block (keyed by {@link BlockPos#asLong}, at most [MAX_BLOCKS] blocks).
     */
    record Spawns(int count, AABB box, double nearest, Long2IntMap blocks) {
        static Spawns merge(Spawns a, Spawns b) {
            if (a == null) return b;
            if (b == null) return a;
            Long2IntOpenHashMap blocks = new Long2IntOpenHashMap(a.blocks);
            b.blocks.long2IntEntrySet().forEach(e -> blocks.addTo(e.getLongKey(), e.getIntValue()));
            return new Spawns(a.count + b.count, a.box.minmax(b.box), Math.min(a.nearest, b.nearest), Long2IntMaps.unmodifiable(blocks));
        }

        /** [blocks] as block positions and counts. */
        Map<BlockPos, Integer> blockCounts() {
            Map<BlockPos, Integer> map = new HashMap<>();
            blocks.long2IntEntrySet().forEach(e -> map.put(BlockPos.of(e.getLongKey()), e.getIntValue()));
            return map;
        }
    }

    /** Everything recorded for one particle; [shown] or [hidden] is null when none of that kind spawned. */
    record Recorded(String particle, boolean typed, Spawns shown, Spawns hidden) {
        Spawns all() {
            return Spawns.merge(shown, hidden);
        }
    }

    private static final class Builder {
        int count;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double nearestSq = Double.MAX_VALUE;
        final Long2IntOpenHashMap blocks = new Long2IntOpenHashMap();

        void add(double x, double y, double z, double distSq) {
            count++;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            nearestSq = Math.min(nearestSq, distSq);
            long block = BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (blocks.size() < MAX_BLOCKS || blocks.containsKey(block)) blocks.addTo(block, 1);
        }

        Spawns build() {
            return count == 0 ? null : new Spawns(count, new AABB(minX, minY, minZ, maxX, maxY, maxZ), Math.sqrt(nearestSq),
                Long2IntMaps.unmodifiable(new Long2IntOpenHashMap(blocks)));
        }
    }

    private static final class Entry {
        boolean typed;
        final Builder shown = new Builder();
        final Builder hidden = new Builder();
    }

    private volatile Vec3 center;
    private volatile double radiusSq;
    private final Map<String, Entry> entries = new HashMap<>();

    boolean isRunning() {
        return center != null;
    }

    void start(Vec3 at, double radius) {
        synchronized (this) {
            entries.clear();
        }
        radiusSq = radius * radius;
        center = at;
    }

    void stop() {
        center = null;
    }

    void record(String particle, boolean typed, double x, double y, double z, boolean hidden) {
        Vec3 c = center;
        if (c == null) return;
        double distSq = c.distanceToSqr(x, y, z);
        if (distSq > radiusSq) return;
        synchronized (this) {
            Entry e = entries.computeIfAbsent(particle, k -> new Entry());
            e.typed = typed;
            (hidden ? e.hidden : e.shown).add(x, y, z, distSq);
        }
    }

    /** What was recorded, most frequent first. */
    synchronized List<Recorded> collect() {
        List<Recorded> list = new ArrayList<>();
        entries.forEach((particle, e) -> list.add(new Recorded(particle, e.typed, e.shown.build(), e.hidden.build())));
        list.sort((a, b) -> Integer.compare(b.all().count(), a.all().count()));
        return list;
    }
}
