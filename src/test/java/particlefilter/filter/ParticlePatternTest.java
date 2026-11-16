package particlefilter.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading rule entries and matching them against particle keys. */
class ParticlePatternTest {

    private static final String TERRAIN = "net.minecraft.client.particle.TerrainParticle";
    /** Pretends only geore:ruby_ore and minecraft:iron_ore are in c:ores. */
    private static final ParticlePattern.TagCheck ORES = (block, tag) ->
        tag.equals("c:ores") && (block.equals("geore:ruby_ore") || block.equals("minecraft:iron_ore"));

    private static boolean matches(String entry, String key) {
        ParticlePattern pattern = ParticlePattern.parse(entry);
        assertTrue(pattern != null, "not a valid entry: " + entry);
        return pattern.matches(key, ORES);
    }

    // ---- reading entries ----

    @Test
    void plainIdsAndClassNamesAreExact() {
        assertTrue(ParticlePattern.parse("minecraft:smoke").isExact());
        assertTrue(ParticlePattern.parse(TERRAIN).isExact());
        assertTrue(ParticlePattern.parse("minecraft:block@minecraft:stone").isExact());
    }

    @Test
    void wildcardsAndTagsAreNotExact() {
        assertFalse(ParticlePattern.parse("geore:*").isExact());
        assertFalse(ParticlePattern.parse("*@minecraft:stone").isExact());
        assertFalse(ParticlePattern.parse("minecraft:block@geore:*").isExact());
        assertFalse(ParticlePattern.parse("minecraft:block@#c:ores").isExact());
    }

    @Test
    void missingNamespacesBecomeMinecraft() {
        assertEquals("minecraft:smoke", ParticlePattern.parse("smoke").toString());
        assertEquals("*@minecraft:stone", ParticlePattern.parse("*@stone").toString());
        assertEquals("*@#minecraft:logs", ParticlePattern.parse("*@#logs").toString());
        assertEquals(TERRAIN, ParticlePattern.parse(TERRAIN).toString()); // class names keep their dots
    }

    @Test
    void surroundingSpacesAreIgnored() {
        assertEquals("geore:*", ParticlePattern.parse("  geore:*  ").toString());
    }

    @Test
    void invalidEntriesAreRejected() {
        assertNull(ParticlePattern.parse(""));
        assertNull(ParticlePattern.parse("   "));
        assertNull(ParticlePattern.parse("*"), "would hide every particle");
        assertNull(ParticlePattern.parse("*@"));
        assertNull(ParticlePattern.parse("*@*"));
        assertNull(ParticlePattern.parse("minecraft:sm*ke"));
        assertNull(ParticlePattern.parse("#c:ores"), "a tag is only for blocks");
        assertNull(ParticlePattern.parse("Minecraft:Smoke"));
        assertNull(ParticlePattern.parse("*@geore:ore*"));
    }

    @Test
    void partsAreReadable() {
        ParticlePattern pattern = ParticlePattern.parse("geore:*@#c:ores");
        assertEquals("geore", pattern.particleNamespace());
        assertEquals("c:ores", pattern.blockTag());
        assertNull(pattern.blockNamespace());
        assertTrue(ParticlePattern.parse("*@geore:*").anyParticle());
        assertEquals("geore", ParticlePattern.parse("*@geore:*").blockNamespace());
    }

    // ---- matching ----

    @Test
    void exactEntryMatchesOnlyThatParticle() {
        assertTrue(matches("minecraft:smoke", "minecraft:smoke"));
        assertFalse(matches("minecraft:smoke", "minecraft:large_smoke"));
    }

    @Test
    void particleWithoutBlockPartMatchesItFromAnyBlock() {
        assertTrue(matches("minecraft:block", "minecraft:block@minecraft:stone"));
        assertTrue(matches("minecraft:block", "minecraft:block"));
        assertTrue(matches(TERRAIN, TERRAIN + "@geore:ruby_ore"));
    }

    @Test
    void modWildcardMatchesEveryParticleOfThatMod() {
        assertTrue(matches("geore:*", "geore:sparkle"));
        assertTrue(matches("geore:*", "geore:sparkle@minecraft:stone"));
        assertFalse(matches("geore:*", "minecraft:block@geore:ruby_ore"), "made from a GeOre block, but not a GeOre particle");
        assertFalse(matches("geore:*", "georex:sparkle"), "another mod whose name starts the same");
        assertFalse(matches("minecraft:*", TERRAIN), "class names have no mod");
    }

    @Test
    void anyParticleFromABlockOrModsBlocks() {
        assertTrue(matches("*@geore:*", "minecraft:block@geore:ruby_ore"));
        assertTrue(matches("*@geore:*", TERRAIN + "@geore:ruby_ore"));
        assertFalse(matches("*@geore:*", "minecraft:block@minecraft:stone"));
        assertFalse(matches("*@geore:*", "geore:sparkle"), "not made from a block");
        assertTrue(matches("*@minecraft:stone", TERRAIN + "@minecraft:stone"));
        assertFalse(matches("*@minecraft:stone", TERRAIN + "@minecraft:stone_bricks"));
    }

    @Test
    void oneParticleFromOneModsBlocks() {
        assertTrue(matches("minecraft:block@geore:*", "minecraft:block@geore:ruby_ore"));
        assertFalse(matches("minecraft:block@geore:*", TERRAIN + "@geore:ruby_ore"));
    }

    @Test
    void tagsMatchTheBlocksInThem() {
        assertTrue(matches("*@#c:ores", TERRAIN + "@geore:ruby_ore"));
        assertTrue(matches("*@#c:ores", "minecraft:block@minecraft:iron_ore"));
        assertFalse(matches("*@#c:ores", TERRAIN + "@minecraft:stone"));
        assertFalse(matches("*@#c:ores", "minecraft:smoke"));
    }

    @Test
    void modParticlesFromAnotherModsBlocks() {
        assertTrue(matches("minecraft:*@geore:*", "minecraft:block@geore:ruby_ore"));
        assertFalse(matches("minecraft:*@geore:*", TERRAIN + "@geore:ruby_ore"));
    }
}
