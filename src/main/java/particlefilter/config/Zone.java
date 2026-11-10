package particlefilter.config;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A box in one world and dimension inside which certain particles are hidden.
 * Positioned by its center column (x, z) and its bottom (y); sized by width (X, "Side 1" in the menu), height (Y)
 * and length (Z, "Side 2") in blocks.
 */
public final class Zone {

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 256;
    /** Number of outline colors; {@code gui.Theme.ZONE_COLORS} holds them. */
    public static final int COLOR_COUNT = 8;

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
    /** Added after 1.2.0: entries switched off in the menu, listed but not hidden. Older versions drop them. */
    public List<String> disabledParticles = new ArrayList<>();
    /** Added after 1.2.0: index into the outline palette, chosen to differ from overlapping zones. */
    public Integer color;

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

    /** Moves and resizes the zone to cover blocks [minX, minX + width) and so on. */
    public void setBox(int minX, int minY, int minZ, int width, int height, int length) {
        this.width = clampSize(width);
        this.height = clampSize(height);
        this.length = clampSize(length);
        this.x = minX + this.width / 2;
        this.y = minY;
        this.z = minZ + this.length / 2;
    }

    public boolean contains(double px, double py, double pz) {
        return px >= minX() && px < minX() + width
            && py >= minY() && py < minY() + height
            && pz >= minZ() && pz < minZ() + length;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** True if the two boxes share any space, face, edge or corner, so their outlines can meet. */
    public boolean touches(Zone o) {
        return minX() <= o.minX() + o.width && o.minX() <= minX() + width
            && minY() <= o.minY() + o.height && o.minY() <= minY() + height
            && minZ() <= o.minZ() + o.length && o.minZ() <= minZ() + length;
    }

    public boolean sameWorld(Zone o) {
        return world.equals(o.world) && dimension.equals(o.dimension);
    }

    /** Number of blocks inside the zone. */
    public long volume() {
        return (long) width * height * length;
    }

    /** Side 1 × Height × Side 2, e.g. "5 × 2 × 22". */
    public String sizeText() {
        return width + " × " + height + " × " + length;
    }

    /** Center column and bottom, as shown in the menu. */
    public String coordsText() {
        return x + ", " + y + ", " + z;
    }

    public boolean hasEntries() {
        return !particles.isEmpty() || !disabledParticles.isEmpty();
    }

    public static int clampSize(int size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    /** A block count with thousands separators. */
    public static String formatCount(long count) {
        return String.format(Locale.ROOT, "%,d", count);
    }

    /** Fills defaults for fields missing from older or hand-edited files and converts the 1.0.0 radius format. */
    void normalize() {
        if (id == null) id = UUID.randomUUID().toString();
        if (name == null) name = "Zone";
        if (world == null) world = "";
        if (dimension == null) dimension = "";
        if (particles == null) particles = new ArrayList<>();
        if (disabledParticles == null) disabledParticles = new ArrayList<>();
        disabledParticles.removeAll(particles);
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
