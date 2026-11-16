package particlefilter.scan;

import particlefilter.filter.ParticleKey;
import particlefilter.filter.ParticlePattern;
import particlefilter.filter.ParticlePattern.TagCheck;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Scan results grouped like folders, so a whole group can be hidden with one switch:
 *
 * <pre>
 * Minecraft                      minecraft:* and *@minecraft:*
 *   smoke                        minecraft:smoke
 *   block                        minecraft:block (made from blocks of several mods)
 *     GeOre blocks               minecraft:block@geore:*
 *       ruby_ore                 minecraft:block@geore:ruby_ore
 *     Minecraft blocks           minecraft:block@minecraft:*
 *   Made from Minecraft blocks   *@minecraft:*
 * GeOre                          geore:* and *@geore:*
 *   Made from GeOre blocks       *@geore:* (the same particles as under "block" above, listed again)
 * Other                          particles without a type, by class name; no switch of its own
 * </pre>
 *
 * Each mod is a folder for its own particles and everything made from its blocks. Folders with only one folder in them
 * take that folder's contents, and below the top level a folder with only one particle in it is replaced by it.
 */
public final class ResultTree {

    /** One particle a scan saw, by key ({@link ParticleKey}), with how often and whether the rules hid every one. */
    public record Item(String key, int count, boolean allHidden) {}

    public enum Kind {
        /** A mod: its particles, and everything made from its blocks. */
        MOD,
        /** Everything made from one mod's blocks, inside that mod's folder. */
        MOD_BLOCKS,
        /** One particle made from several blocks. */
        PARTICLE,
        /** One particle made from one mod's blocks, inside its particle folder. */
        BLOCK_MOD,
        /** Particles without a type; nothing hides them all, so it has no switch. */
        OTHER,
        /** One particle as a scan saw it. */
        LEAF
    }

    public static final class Node {
        /** Unique among the tree's nodes, the same for the same folder in every scan; for remembering what is open. */
        public final String id;
        public final Kind kind;
        /** The mod of a MOD, MOD_BLOCKS or BLOCK_MOD folder, the particle of a PARTICLE folder, the key of a LEAF. */
        public final String name;
        /** The entries that hide exactly this group; empty for OTHER. */
        public final List<String> rules;
        public final List<Node> children;
        /** The keys of every particle in this group. */
        public final List<String> leaves;
        public final int count;
        public final boolean allHidden;

        private Node(String id, Kind kind, String name, List<String> rules, List<Node> children, List<String> leaves, int count, boolean allHidden) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.rules = rules;
            this.children = children;
            this.leaves = leaves;
            this.count = count;
            this.allHidden = allHidden;
        }

        public boolean isFolder() {
            return kind != Kind.LEAF;
        }

        @Override
        public String toString() {
            return kind + " " + name + (children.isEmpty() ? "" : " " + children);
        }
    }

    /** Folder name of particles without a type. */
    public static final String OTHER = "other";

    private ResultTree() {}

    /** The top-level folders for [items]: one per mod, then Other. */
    public static List<Node> build(List<Item> items) {
        Map<String, List<Item>> byParticleMod = new TreeMap<>();
        Map<String, List<Item>> byBlockMod = new TreeMap<>();
        List<Item> untyped = new ArrayList<>();
        for (Item item : items) {
            String base = ParticleKey.base(item.key);
            String block = ParticleKey.block(item.key);
            if (ParticleKey.isTyped(item.key)) byParticleMod.computeIfAbsent(namespace(base), k -> new ArrayList<>()).add(item);
            else untyped.add(item);
            if (block != null) byBlockMod.computeIfAbsent(namespace(block), k -> new ArrayList<>()).add(item);
        }
        Set<String> mods = new LinkedHashSet<>(byParticleMod.keySet());
        mods.addAll(byBlockMod.keySet());

        List<Node> top = new ArrayList<>();
        for (String mod : mods) {
            String id = "mod:" + mod;
            List<Node> children = new ArrayList<>(particles(id, byParticleMod.getOrDefault(mod, List.of())));
            List<Item> fromBlocks = byBlockMod.getOrDefault(mod, List.of());
            if (!fromBlocks.isEmpty()) {
                String blocksId = id + "/blocks";
                children.add(folder(blocksId, Kind.MOD_BLOCKS, mod, List.of(anyFromModBlocks(mod)), leaves(blocksId, fromBlocks)));
            }
            top.add(simplify(folder(id, Kind.MOD, mod, List.of(mod + ":*", anyFromModBlocks(mod)), children), true));
        }
        if (!untyped.isEmpty()) top.add(simplify(folder("other", Kind.OTHER, OTHER, List.of(), particles("other", untyped)), true));
        top.sort(ORDER);
        return top;
    }

    /** The folders of particles [items] (all of one mod, or all untyped) under [parentId]. */
    private static List<Node> particles(String parentId, List<Item> items) {
        Map<String, List<Item>> byBase = new LinkedHashMap<>();
        for (Item item : items) byBase.computeIfAbsent(ParticleKey.base(item.key), k -> new ArrayList<>()).add(item);
        List<Node> nodes = new ArrayList<>();
        byBase.forEach((base, group) -> {
            if (group.size() == 1 && ParticleKey.block(group.get(0).key) == null) {
                nodes.add(leaf(parentId, group.get(0)));
                return;
            }
            String id = parentId + "/particle:" + base;
            Map<String, List<Item>> byBlockMod = new TreeMap<>();
            List<Node> children = new ArrayList<>();
            for (Item item : group) {
                String block = ParticleKey.block(item.key);
                if (block == null) children.add(leaf(id, item));
                else byBlockMod.computeIfAbsent(namespace(block), k -> new ArrayList<>()).add(item);
            }
            byBlockMod.forEach((mod, fromMod) -> {
                String modId = id + "/blocks:" + mod;
                children.add(folder(modId, Kind.BLOCK_MOD, mod, List.of(base + "@" + mod + ":*"), leaves(modId, fromMod)));
            });
            nodes.add(folder(id, Kind.PARTICLE, base, List.of(base), children));
        });
        return nodes;
    }

    private static List<Node> leaves(String parentId, List<Item> items) {
        return items.stream().map(item -> leaf(parentId, item)).toList();
    }

    private static Node leaf(String parentId, Item item) {
        return new Node(parentId + "/" + item.key, Kind.LEAF, item.key, List.of(item.key), List.of(), List.of(item.key), item.count, item.allHidden);
    }

    private static Node folder(String id, Kind kind, String name, List<String> rules, List<Node> children) {
        List<Node> sorted = new ArrayList<>(children);
        sorted.sort(ORDER);
        Set<String> leaves = new LinkedHashSet<>();
        int count = 0;
        boolean allHidden = true;
        for (Node child : sorted) {
            leaves.addAll(child.leaves);
            count += child.count;
            allHidden &= child.allHidden;
        }
        // A particle listed twice (under its particle and under the mod of its block) counts once.
        if (leaves.size() < sorted.stream().mapToInt(c -> c.leaves.size()).sum()) count = recount(sorted);
        return new Node(id, kind, name, List.copyOf(rules), List.copyOf(sorted), List.copyOf(leaves), count, allHidden);
    }

    private static int recount(List<Node> children) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Node child : children) collectCounts(child, counts);
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static void collectCounts(Node node, Map<String, Integer> counts) {
        if (node.kind == Kind.LEAF) counts.put(node.name, node.count);
        else node.children.forEach(c -> collectCounts(c, counts));
    }

    /**
     * Shortens [node]'s branches: a folder holding only one folder takes that folder's contents (keeping its own rules,
     * which cover them), and below the top level a folder holding only one particle is replaced by it.
     */
    private static Node simplify(Node node, boolean top) {
        if (!node.isFolder()) return node;
        List<Node> children = node.children.stream().map(c -> simplify(c, false)).toList();
        while (children.size() == 1 && children.get(0).isFolder()) children = children.get(0).children;
        if (!top && children.size() == 1) return children.get(0);
        return folder(node.id, node.kind, node.name, node.rules, children);
    }

    /** Groups still appearing first, then the most frequent, then by name. */
    private static final Comparator<Node> ORDER = Comparator.comparing((Node n) -> n.allHidden)
        .thenComparing(Comparator.comparingInt((Node n) -> n.count).reversed())
        .thenComparing(n -> n.name);

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    private static String anyFromModBlocks(String mod) {
        return ParticlePattern.ANY + "@" + mod + ":*";
    }

    // ---------------------------------------------------------------- Switch states

    public enum State { OFF, PARTIAL, ON }

    /**
     * How a node's switch shows, for one column. [state]: whether every particle in it is hidden, some, or none.
     * [locked]: switching it off wouldn't show any of them, because entries outside the group hide every one
     * ([lockedBy]), such as a switched-on mod folder above it. Entries outside the group that hide only some of its
     * particles are in [lockedBy] too.
     */
    public record Coverage(State state, boolean locked, List<String> lockedBy) {}

    /**
     * [node]'s switch for a column whose switched-on entries applying to a particle are [entriesFor] (for Everywhere,
     * the same list for every particle; for Here, those of the zones around where it spawned).
     */
    public static Coverage coverage(Node node, Function<String, ? extends Collection<String>> entriesFor, TagCheck tags) {
        int covered = 0;
        int coveredOutside = 0;
        Set<String> lockedBy = new LinkedHashSet<>();
        for (String leaf : node.leaves) {
            boolean hidden = false;
            boolean outside = false;
            for (String entry : entriesFor.apply(leaf)) {
                if (!ParticlePattern.entryMatches(entry, leaf, tags)) continue;
                hidden = true;
                if (!owns(node, entry, tags)) {
                    outside = true;
                    lockedBy.add(entry);
                }
            }
            if (hidden) covered++;
            if (outside) coveredOutside++;
        }
        State state = covered == 0 ? State.OFF : covered == node.leaves.size() ? State.ON : State.PARTIAL;
        return new Coverage(state, coveredOutside > 0 && coveredOutside == node.leaves.size(), List.copyOf(lockedBy));
    }

    /** True if [entry] is one of [node]'s rules or covered by one, so switching the node off removes it. */
    public static boolean owns(Node node, String entry, TagCheck tags) {
        for (String rule : node.rules) if (ParticlePattern.covers(rule, entry, tags)) return true;
        return false;
    }
}
