package particlefilter.config;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A box in one world and dimension inside which certain particles are hidden.
 * Positioned by its center column (x, z) and its bottom (y); sized by width (X), height (Y) and length (Z) in blocks.
 */
public final class Zone {

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 256;

    public String id = UUID.randomUUID().toString();
    public String name = "Zone";
    public String world = "";
    public String dimension = "";
    public int x;
    public int y;
    public int z;
    public int width = 9;
    public int height = 5;
    public int length = 9;
    public List<String> particles = new ArrayList<>();

    /** Version 1.0.0 stored zones as a cube around (x, y, z); converted on load. */
    Integer radius;

    public int minX() {
        return x - width / 2;
    }

    public int minY() {
        return y;
    }

    public int minZ() {
        return z - length / 2;
    }

    public AABB box() {
        return new AABB(minX(), minY(), minZ(), minX() + width, minY() + height, minZ() + length);
    }

    public boolean contains(double px, double py, double pz) {
        return px >= minX() && px < minX() + width
            && py >= minY() && py < minY() + height
            && pz >= minZ() && pz < minZ() + length;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    public String sizeText() {
        return width + "×" + height + "×" + length;
    }

    public static int clampSize(int size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    /** Fills defaults for fields missing from older or hand-edited files and converts the 1.0.0 radius format. */
    void normalize() {
        if (id == null) id = UUID.randomUUID().toString();
        if (name == null) name = "Zone";
        if (world == null) world = "";
        if (dimension == null) dimension = "";
        if (particles == null) particles = new ArrayList<>();
        if (radius != null) {
            int r = Math.max(0, radius);
            width = height = length = 2 * r + 1;
            y -= r;
            radius = null;
        }
        width = clampSize(width);
        height = clampSize(height);
        length = clampSize(length);
    }
}
