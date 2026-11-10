package particlefilter.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
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
     * Spawn points of one particle: how many, the box they fill, the closest one to the center, and the blocks they
     * were in (as {@link BlockPos#asLong}, at most [MAX_BLOCKS]).
     */
    record Spawns(int count, AABB box, double nearest, LongSet blocks) {
        static Spawns merge(Spawns a, Spawns b) {
            if (a == null) return b;
            if (b == null) return a;
            LongSet blocks = new LongOpenHashSet(a.blocks);
            blocks.addAll(b.blocks);
            return new Spawns(a.count + b.count, a.box.minmax(b.box), Math.min(a.nearest, b.nearest), LongSets.unmodifiable(blocks));
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
        final LongSet blocks = new LongOpenHashSet();

        void add(double x, double y, double z, double distSq) {
            count++;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            nearestSq = Math.min(nearestSq, distSq);
            if (blocks.size() < MAX_BLOCKS) blocks.add(BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        }

        Spawns build() {
            return count == 0 ? null : new Spawns(count, new AABB(minX, minY, minZ, maxX, maxY, maxZ), Math.sqrt(nearestSq),
                LongSets.unmodifiable(new LongOpenHashSet(blocks)));
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
