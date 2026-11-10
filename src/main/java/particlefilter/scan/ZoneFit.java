package particlefilter.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import particlefilter.config.Zone;

import java.util.Collection;

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

    /** {min, size, capped} for blocks [lo, hi]; too long a side is cut to its middle 256 blocks. */
    private static int[] axis(int lo, int hi) {
        int size = hi - lo + 1;
        if (size <= Zone.MAX_SIZE) return new int[] {lo, size, 0};
        return new int[] {lo + (size - Zone.MAX_SIZE) / 2, Zone.MAX_SIZE, 1};
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
