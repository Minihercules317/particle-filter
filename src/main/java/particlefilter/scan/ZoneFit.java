package particlefilter.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import particlefilter.config.Zone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Sizes zones to fit particles. Particles are filtered by where they spawn, so the smallest block-aligned box holding
 * every recorded spawn point hides them all; a zone is never made bigger than that.
 */
public final class ZoneFit {

    /** A block box: blocks [minX, minX + width) and so on. [capped] is true if a side was cut to {@link Zone#MAX_SIZE}. */
    public record Box(int minX, int minY, int minZ, int width, int height, int length, boolean capped) {
        public boolean contains(Box o) {
            return minX <= o.minX && minY <= o.minY && minZ <= o.minZ
                && minX + width >= o.minX + o.width && minY + height >= o.minY + o.height && minZ + length >= o.minZ + o.length;
        }

        /** True if both cover the same blocks. */
        public boolean sameBlocks(Box o) {
            return minX == o.minX && minY == o.minY && minZ == o.minZ && width == o.width && height == o.height && length == o.length;
        }

        public String sizeText() {
            return width + " × " + height + " × " + length;
        }

        public long volume() {
            return (long) width * height * length;
        }

        public AABB aabb() {
            return new AABB(minX, minY, minZ, minX + width, minY + height, minZ + length);
        }

        public void applyTo(Zone zone) {
            zone.setBox(minX, minY, minZ, width, height, length);
        }

        public static Box of(Zone zone) {
            return new Box(zone.minX(), zone.minY(), zone.minZ(), zone.width, zone.height, zone.length, false);
        }
    }

    private ZoneFit() {}

    /**
     * Smallest box holding every spawn box, each side at most 256 blocks. A point on a block's lower face belongs to
     * that block (zones hold [min, max)), so a point at x = 5.0 needs block 5 only. With no spawn boxes it is just
     * the [fallback] block, or null without one.
     */
    public static Box fit(BlockPos fallback, Collection<AABB> spawns) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        if (spawns.isEmpty() && fallback != null) {
            x0 = x1 = fallback.getX();
            y0 = y1 = fallback.getY();
            z0 = z1 = fallback.getZ();
        }
        for (AABB s : spawns) {
            x0 = Math.min(x0, floor(s.minX));
            y0 = Math.min(y0, floor(s.minY));
            z0 = Math.min(z0, floor(s.minZ));
            x1 = Math.max(x1, floor(s.maxX));
            y1 = Math.max(y1, floor(s.maxY));
            z1 = Math.max(z1, floor(s.maxZ));
        }
        if (x0 == Integer.MAX_VALUE) return null;
        int[] x = axis(x0, x1);
        int[] y = axis(y0, y1);
        int[] z = axis(z0, z1);
        return new Box(x[0], y[0], z[0], x[1], y[1], z[1], x[2] + y[2] + z[2] > 0);
    }

    /**
     * Smallest box holding [box] and every spawn box: what a zone grows to so it also covers [spawns]. It never loses
     * a block of [box]; a side that would pass 256 blocks keeps [box] and as much of the middle of the rest as fits.
     * Spawn points on a block's lower face belong to that block, as in {@link #fit}.
     */
    public static Box grow(Box box, Collection<AABB> spawns) {
        int x0 = box.minX, y0 = box.minY, z0 = box.minZ;
        int x1 = box.minX + box.width - 1, y1 = box.minY + box.height - 1, z1 = box.minZ + box.length - 1;
        for (AABB s : spawns) {
            x0 = Math.min(x0, floor(s.minX));
            y0 = Math.min(y0, floor(s.minY));
            z0 = Math.min(z0, floor(s.minZ));
            x1 = Math.max(x1, floor(s.maxX));
            y1 = Math.max(y1, floor(s.maxY));
            z1 = Math.max(z1, floor(s.maxZ));
        }
        int[] x = axis(x0, x1, box.minX, box.minX + box.width - 1);
        int[] y = axis(y0, y1, box.minY, box.minY + box.height - 1);
        int[] z = axis(z0, z1, box.minZ, box.minZ + box.length - 1);
        return new Box(x[0], y[0], z[0], x[1], y[1], z[1], x[2] + y[2] + z[2] > 0);
    }

    /**
     * [box] grown to take in each block of [blocks] that lies at most [near] blocks outside it, then each block that
     * close to what it took in, and so on: spots joined to the box, but not a separate group farther away. Never
     * loses a block of [box]; past 256 blocks a side is cut as in {@link #grow}.
     */
    public static Box growNear(Box box, Collection<BlockPos> blocks, int near) {
        int x0 = box.minX, y0 = box.minY, z0 = box.minZ;
        int x1 = box.minX + box.width - 1, y1 = box.minY + box.height - 1, z1 = box.minZ + box.length - 1;
        List<BlockPos> left = new ArrayList<>(blocks);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Iterator<BlockPos> it = left.iterator(); it.hasNext(); ) {
                BlockPos p = it.next();
                if (p.getX() < x0 - near || p.getX() > x1 + near || p.getY() < y0 - near || p.getY() > y1 + near
                    || p.getZ() < z0 - near || p.getZ() > z1 + near) continue;
                x0 = Math.min(x0, p.getX());
                y0 = Math.min(y0, p.getY());
                z0 = Math.min(z0, p.getZ());
                x1 = Math.max(x1, p.getX());
                y1 = Math.max(y1, p.getY());
                z1 = Math.max(z1, p.getZ());
                it.remove();
                grew = true;
            }
        }
        int[] x = axis(x0, x1, box.minX, box.minX + box.width - 1);
        int[] y = axis(y0, y1, box.minY, box.minY + box.height - 1);
        int[] z = axis(z0, z1, box.minZ, box.minZ + box.length - 1);
        return new Box(x[0], y[0], z[0], x[1], y[1], z[1], x[2] + y[2] + z[2] > 0);
    }

    /** {min, size, capped} for blocks [lo, hi]; too long a side is cut to its middle 256 blocks. */
    private static int[] axis(int lo, int hi) {
        int size = hi - lo + 1;
        if (size <= Zone.MAX_SIZE) return new int[] {lo, size, 0};
        return new int[] {lo + (size - Zone.MAX_SIZE) / 2, Zone.MAX_SIZE, 1};
    }

    /** Like {@link #axis(int, int)}, but a cut side still holds blocks [keepLo, keepHi]. */
    private static int[] axis(int lo, int hi, int keepLo, int keepHi) {
        int[] a = axis(lo, hi);
        if (a[2] == 0) return a;
        a[0] = Math.max(keepHi - Zone.MAX_SIZE + 1, Math.min(keepLo, a[0]));
        return a;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
