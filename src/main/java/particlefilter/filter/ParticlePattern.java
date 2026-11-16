package particlefilter.filter;

import java.util.regex.Pattern;

/**
 * A rule's entry, matched against particle keys ({@link ParticleKey}): "particle" or "particle@block".
 *
 * The particle part is a type id ("minecraft:smoke"), a class name for particles without a type, "mod:*" for every
 * particle type of a mod, or "*" for any particle (only with a block part). The block part, when present, limits the
 * entry to particles made from a block: a block id ("minecraft:stone"), "mod:*" for every block of a mod, or "#tag" for
 * the blocks in a block tag. Without a block part, an entry matches the particle from any block or none.
 *
 * Examples: "geore:*" (every particle GeOre adds), "minecraft:block@geore:*" (block dust from GeOre's blocks),
 * "*@geore:*" (anything made from GeOre's blocks, the old "Hide block geore:*"), "*@#c:ores" (anything made from ores).
 *
 * Ids without a namespace get "minecraft:", as in the game, except class names (which contain dots).
 */
public record ParticlePattern(String particle, String block) {

    /** Any particle, for {@link #particle}. */
    public static final String ANY = "*";
    private static final String WILDCARD = ":*";
    private static final char TAG = '#';

    /** Characters allowed in a namespace or path; class names may also have "$". */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.\\-]+(:[a-z0-9_.\\-/]+)?");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.\\-]+");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z0-9_$.]+");

    /** Whether a block is in a block tag: (block id, tag id without "#") → true if it is. */
    @FunctionalInterface
    public interface TagCheck {
        boolean inTag(String blockId, String tagId);

        TagCheck NONE = (block, tag) -> false;
    }

    /** [entry] as a pattern, with namespaces filled in, or null if it isn't a valid entry. */
    public static ParticlePattern parse(String entry) {
        if (entry == null) return null;
        String s = entry.trim();
        if (s.isEmpty()) return null;
        int at = s.indexOf('@');
        String particle = at < 0 ? s : s.substring(0, at);
        String block = at < 0 ? null : s.substring(at + 1);
        particle = particlePart(particle);
        if (particle == null) return null;
        if (block != null) {
            block = blockPart(block);
            if (block == null) return null;
        } else if (particle.equals(ANY)) {
            return null; // would hide every particle
        }
        return new ParticlePattern(particle, block);
    }

    private static String particlePart(String s) {
        if (s.equals(ANY)) return ANY;
        if (s.endsWith(WILDCARD)) return namespaceWildcard(s);
        if (s.contains("*") || s.indexOf(TAG) >= 0) return null;
        if (s.contains(".") && !s.contains(":")) return CLASS_NAME.matcher(s).matches() ? s : null; // a class name
        return id(s);
    }

    private static String blockPart(String s) {
        if (s.endsWith(WILDCARD)) return namespaceWildcard(s);
        if (s.contains("*")) return null;
        if (s.indexOf(TAG) == 0) {
            String tag = id(s.substring(1));
            return tag == null ? null : TAG + tag;
        }
        return id(s);
    }

    private static String namespaceWildcard(String s) {
        String namespace = s.substring(0, s.length() - WILDCARD.length());
        return NAMESPACE.matcher(namespace).matches() ? namespace + WILDCARD : null;
    }

    private static String id(String s) {
        if (!ID.matcher(s).matches()) return null;
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** True if this is a plain particle key: no wildcard, no tag. Such entries are looked up directly. */
    public boolean isExact() {
        return !particle.equals(ANY) && !particle.endsWith(WILDCARD) && (block == null || !block.endsWith(WILDCARD) && block.charAt(0) != TAG);
    }

    /** True if this matches every particle made from a block it matches, whatever the particle. */
    public boolean anyParticle() {
        return particle.equals(ANY);
    }

    /** The mod a "mod:*" particle part names, or null. */
    public String particleNamespace() {
        return particle.endsWith(WILDCARD) ? particle.substring(0, particle.length() - WILDCARD.length()) : null;
    }

    /** The mod a "mod:*" block part names, or null. */
    public String blockNamespace() {
        return block != null && block.endsWith(WILDCARD) ? block.substring(0, block.length() - WILDCARD.length()) : null;
    }

    /** The tag a "#tag" block part names (without "#"), or null. */
    public String blockTag() {
        return block != null && block.charAt(0) == TAG ? block.substring(1) : null;
    }

    /** True if a particle [base] made from [blockId] (null when made from no block) matches. */
    public boolean matches(String base, String blockId, TagCheck tags) {
        if (!matchesParticle(base)) return false;
        if (block == null) return true;
        if (blockId == null) return false;
        String tag = blockTag();
        if (tag != null) return tags.inTag(blockId, tag);
        String namespace = blockNamespace();
        return namespace != null ? inNamespace(blockId, namespace) : block.equals(blockId);
    }

    /** True if [key] (a particle key, with its block after "@" if any) matches. */
    public boolean matches(String key, TagCheck tags) {
        return matches(ParticleKey.base(key), ParticleKey.block(key), tags);
    }

    private boolean matchesParticle(String base) {
        if (particle.equals(ANY)) return true;
        String namespace = particleNamespace();
        return namespace != null ? inNamespace(base, namespace) : particle.equals(base);
    }

    private static boolean inNamespace(String id, String namespace) {
        return id.length() > namespace.length() && id.startsWith(namespace) && id.charAt(namespace.length()) == ':';
    }

    /** The entry as saved: "particle" or "particle@block". */
    @Override
    public String toString() {
        return block == null ? particle : particle + "@" + block;
    }
}
