package particlefilter.scan;

import org.junit.jupiter.api.Test;
import particlefilter.filter.ParticlePattern;
import particlefilter.scan.ResultTree.Item;
import particlefilter.scan.ResultTree.Kind;
import particlefilter.scan.ResultTree.Node;
import particlefilter.scan.ResultTree.State;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Grouping scan results into folders, and what their switches show. */
class ResultTreeTest {

    private static final String TERRAIN = "net.minecraft.client.particle.TerrainParticle";
    private static final ParticlePattern.TagCheck NO_TAGS = ParticlePattern.TagCheck.NONE;

    private static Item item(String key, int count) {
        return new Item(key, count, false);
    }

    private static Node child(Node node, String name) {
        return node.children.stream().filter(c -> c.name.equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no " + name + " in " + node));
    }

    private static Node top(List<Node> tree, String name) {
        return tree.stream().filter(n -> n.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name + " in " + tree));
    }

    // ---- shape ----

    @Test
    void eachModIsAFolderForItsParticlesAndItsBlocks() {
        List<Node> tree = ResultTree.build(List.of(item("minecraft:smoke", 5), item("minecraft:flame", 3), item("create:steam", 2)));

        Node minecraft = top(tree, "minecraft");
        assertEquals(Kind.MOD, minecraft.kind);
        assertEquals(List.of("minecraft:*", "*@minecraft:*"), minecraft.rules);
        assertEquals(List.of("minecraft:smoke", "minecraft:flame"), minecraft.children.stream().map(c -> c.name).toList(), "most frequent first");
        assertEquals(8, minecraft.count);
        assertEquals(Kind.LEAF, child(minecraft, "minecraft:smoke").kind);
        assertEquals(List.of("minecraft", "create"), tree.stream().map(n -> n.name).toList());
    }

    @Test
    void particleFromSeveralModsBlocksSplitsByMod() {
        List<Node> tree = ResultTree.build(List.of(
            item("minecraft:block@geore:ruby_ore", 4), item("minecraft:block@geore:topaz_ore", 2),
            item("minecraft:block@minecraft:stone", 3), item("minecraft:smoke", 1)));

        Node block = child(top(tree, "minecraft"), "minecraft:block");
        assertEquals(Kind.PARTICLE, block.kind);
        assertEquals(List.of("minecraft:block"), block.rules);
        Node fromGeore = child(block, "geore");
        assertEquals(Kind.BLOCK_MOD, fromGeore.kind);
        assertEquals(List.of("minecraft:block@geore:*"), fromGeore.rules);
        assertEquals(List.of("minecraft:block@geore:ruby_ore", "minecraft:block@geore:topaz_ore"), fromGeore.leaves);
        // Only one stone row: the Minecraft-blocks folder is replaced by it.
        assertEquals(Kind.LEAF, child(block, "minecraft:block@minecraft:stone").kind);
    }

    @Test
    void modWhoseBlocksMadeParticlesGetsAFolderEvenWithoutParticlesOfItsOwn() {
        List<Node> tree = ResultTree.build(List.of(
            item("minecraft:block@geore:ruby_ore", 4), item(TERRAIN + "@geore:ruby_ore", 2), item("minecraft:smoke", 1)));

        Node geore = top(tree, "geore");
        assertEquals(List.of("geore:*", "*@geore:*"), geore.rules);
        // Its only folder (Made from GeOre blocks) is merged into it.
        assertEquals(Set.of("minecraft:block@geore:ruby_ore", TERRAIN + "@geore:ruby_ore"), new HashSet<>(geore.leaves));
        assertTrue(geore.children.stream().allMatch(c -> c.kind == Kind.LEAF));
    }

    @Test
    void modFolderListsWhatItsBlocksMadeAgain() {
        List<Node> tree = ResultTree.build(List.of(item("minecraft:block@minecraft:stone", 4), item("minecraft:block@minecraft:dirt", 1),
            item("minecraft:smoke", 2)));

        Node minecraft = top(tree, "minecraft");
        Node madeFrom = child(minecraft, "minecraft");
        assertEquals(Kind.MOD_BLOCKS, madeFrom.kind);
        assertEquals(List.of("*@minecraft:*"), madeFrom.rules);
        assertEquals(7, minecraft.count, "particles listed twice count once");
    }

    @Test
    void particlesWithoutATypeGoUnderOther() {
        List<Node> tree = ResultTree.build(List.of(item(TERRAIN + "@minecraft:stone", 3), item(TERRAIN + "@minecraft:dirt", 1),
            item("com.example.Spark", 2)));

        Node other = top(tree, ResultTree.OTHER);
        assertEquals(Kind.OTHER, other.kind);
        assertEquals(List.of(), other.rules);
        Node terrain = child(other, TERRAIN);
        assertEquals(List.of(TERRAIN), terrain.rules);
        assertEquals(2, terrain.leaves.size());
        assertEquals(Kind.LEAF, child(other, "com.example.Spark").kind);
        // Their blocks still give Minecraft a folder.
        assertEquals(List.of("minecraft:*", "*@minecraft:*"), top(tree, "minecraft").rules);
    }

    @Test
    void allHiddenGroupsComeLast() {
        List<Node> tree = ResultTree.build(List.of(new Item("minecraft:smoke", 50, true), item("minecraft:flame", 1)));

        assertEquals(List.of("minecraft:flame", "minecraft:smoke"), top(tree, "minecraft").children.stream().map(c -> c.name).toList());
        assertFalse(top(tree, "minecraft").allHidden);
    }

    @Test
    void idsAreUniqueAndStable() {
        List<Item> items = List.of(item("minecraft:block@geore:ruby_ore", 4), item("minecraft:block@minecraft:stone", 3),
            item("minecraft:block@minecraft:dirt", 3));
        Set<String> ids = new HashSet<>();
        List<Node> tree = ResultTree.build(items);
        tree.forEach(n -> collectIds(n, ids));
        Set<String> again = new HashSet<>();
        ResultTree.build(items).forEach(n -> collectIds(n, again));

        assertEquals(ids, again);
        assertEquals(count(tree), ids.size());
    }

    private static void collectIds(Node node, Set<String> ids) {
        ids.add(node.id);
        node.children.forEach(c -> collectIds(c, ids));
    }

    private static int count(List<Node> nodes) {
        return nodes.stream().mapToInt(n -> 1 + count(n.children)).sum();
    }

    // ---- switches ----

    private static final List<Item> ITEMS = List.of(item("minecraft:block@geore:ruby_ore", 4), item("minecraft:block@geore:topaz_ore", 2),
        item("minecraft:block@minecraft:stone", 3), item("minecraft:smoke", 1));

    private static ResultTree.Coverage coverage(Node node, List<String> entries) {
        return ResultTree.coverage(node, leaf -> entries, NO_TAGS);
    }

    @Test
    void folderIsOnWhenEveryParticleInItIsHidden() {
        Node fromGeore = child(child(top(ResultTree.build(ITEMS), "minecraft"), "minecraft:block"), "geore");

        assertEquals(State.OFF, coverage(fromGeore, List.of()).state());
        assertEquals(State.PARTIAL, coverage(fromGeore, List.of("minecraft:block@geore:ruby_ore")).state());
        assertEquals(State.ON, coverage(fromGeore, List.of("minecraft:block@geore:ruby_ore", "minecraft:block@geore:topaz_ore")).state());
        assertEquals(State.ON, coverage(fromGeore, List.of("minecraft:block@geore:*")).state());
    }

    @Test
    void groupAboveLocksTheSwitchesBelowIt() {
        List<Node> tree = ResultTree.build(ITEMS);
        Node minecraft = top(tree, "minecraft");
        Node smoke = child(minecraft, "minecraft:smoke");
        List<String> entries = List.of("minecraft:*", "*@minecraft:*");

        ResultTree.Coverage folder = coverage(minecraft, entries);
        assertEquals(State.ON, folder.state());
        assertFalse(folder.locked(), "its own rules: switching it off works");

        ResultTree.Coverage leaf = coverage(smoke, entries);
        assertEquals(State.ON, leaf.state());
        assertTrue(leaf.locked());
        assertEquals(List.of("minecraft:*"), leaf.lockedBy());
    }

    @Test
    void folderWhoseParticlesWereSwitchedOnOneByOneIsNotLocked() {
        Node fromGeore = child(child(top(ResultTree.build(ITEMS), "minecraft"), "minecraft:block"), "geore");

        assertFalse(coverage(fromGeore, List.of("minecraft:block@geore:ruby_ore", "minecraft:block@geore:topaz_ore")).locked());
    }

    @Test
    void partlyHiddenFromOutsideIsNotLocked() {
        Node fromGeore = child(child(top(ResultTree.build(ITEMS), "minecraft"), "minecraft:block"), "geore");

        ResultTree.Coverage coverage = coverage(fromGeore, List.of("*@geore:ruby_ore", "minecraft:block@geore:topaz_ore"));
        assertEquals(State.ON, coverage.state());
        assertFalse(coverage.locked(), "switching off still shows topaz");
        assertEquals(List.of("*@geore:ruby_ore"), coverage.lockedBy());
    }

    @Test
    void ownsItsRulesAndWhatTheyCover() {
        Node geore = top(ResultTree.build(ITEMS), "geore");

        assertTrue(ResultTree.owns(geore, "*@geore:*", NO_TAGS));
        assertTrue(ResultTree.owns(geore, "minecraft:block@geore:ruby_ore", NO_TAGS));
        assertTrue(ResultTree.owns(geore, "geore:sparkle", NO_TAGS));
        assertFalse(ResultTree.owns(geore, "minecraft:block", NO_TAGS));
    }

    @Test
    void hereCanDifferPerParticle() {
        Node fromGeore = child(child(top(ResultTree.build(ITEMS), "minecraft"), "minecraft:block"), "geore");

        // Only the zone around ruby ore lists the group.
        ResultTree.Coverage coverage = ResultTree.coverage(fromGeore,
            leaf -> leaf.endsWith("ruby_ore") ? List.of("minecraft:block@geore:*") : List.of(), NO_TAGS);
        assertEquals(State.PARTIAL, coverage.state());
    }

    @Test
    void folderSharingARuleWithASwitchedOnParentIsLocked() {
        // "Made from Minecraft blocks" adds *@minecraft:*, one of the Minecraft folder's own two rules.
        // Debris without a particle type: only *@minecraft:* covers it, not minecraft:*.
        List<Node> tree = ResultTree.build(List.of(item(TERRAIN + "@minecraft:stone", 4), item(TERRAIN + "@minecraft:dirt", 1),
            item("minecraft:smoke", 2)));
        Node minecraft = top(tree, "minecraft");
        Node madeFrom = child(minecraft, "minecraft");
        List<String> entries = List.of("minecraft:*", "*@minecraft:*");

        ResultTree.Coverage parent = coverage(minecraft, entries);
        assertTrue(parent.asGroup());
        ResultTree.Coverage own = coverage(madeFrom, entries);
        assertFalse(own.locked(), "on its own it looks like its own rule");
        ResultTree.Coverage inside = ResultTree.inside(own, minecraft, parent);
        assertTrue(inside.locked());
        assertEquals(minecraft.rules, inside.lockedBy());
    }

    @Test
    void foldersInsideAParentSwitchedOnPieceByPieceStayFree() {
        List<Node> tree = ResultTree.build(List.of(item(TERRAIN + "@minecraft:stone", 4), item(TERRAIN + "@minecraft:dirt", 1),
            item("minecraft:smoke", 2)));
        Node minecraft = top(tree, "minecraft");
        Node madeFrom = child(minecraft, "minecraft");
        List<String> entries = List.of("*@minecraft:*", "minecraft:smoke");

        ResultTree.Coverage parent = coverage(minecraft, entries);
        assertEquals(State.ON, parent.state());
        assertFalse(parent.asGroup(), "smoke is hidden by its own entry, not the folder's");
        assertFalse(ResultTree.inside(coverage(madeFrom, entries), minecraft, parent).locked());
    }
}
