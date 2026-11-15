package particlefilter.filter;

/**
 * How particles are named in rules and scan results. A particle is its type id ("minecraft:smoke") or, for a particle
 * created without a type, its class name. Particles made from a block, such as breaking and mining debris or the
 * dust of a block someone lands on, also name the block after an "@" ("minecraft:block@minecraft:stone"), so one
 * block's can be hidden while another's stay. A rule for the plain particle covers it from every block.
 *
 * Neither type ids nor class names contain "@", so the two parts can always be told apart.
 */
public final class ParticleKey {

    private static final char BLOCK = '@';

    private ParticleKey() {}

    /** [particle] made from the block with id [blockId]. */
    public static String withBlock(String particle, String blockId) {
        return particle + BLOCK + blockId;
    }

    /** The particle without its block: the type id or class name. The same string if it names no block. */
    public static String base(String key) {
        int at = key.indexOf(BLOCK);
        return at < 0 ? key : key.substring(0, at);
    }

    /** The id of the block [key] was made from, or null if it names none. */
    public static String block(String key) {
        int at = key.indexOf(BLOCK);
        return at < 0 ? null : key.substring(at + 1);
    }

    /** True for a particle type id, false for a class name. */
    public static boolean isTyped(String key) {
        return base(key).contains(":");
    }

    /**
     * Short name for lists: the type id, or just the simple class name for particles without a type, followed by the
     * block in brackets: "TerrainParticle (minecraft:stone)".
     */
    public static String displayName(String key) {
        String base = base(key);
        String name = base.contains(":") ? base : base.substring(base.lastIndexOf('.') + 1);
        String block = block(key);
        return block == null ? name : name + " (" + block + ")";
    }
}
