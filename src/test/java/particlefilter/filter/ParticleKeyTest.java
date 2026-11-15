package particlefilter.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Particle names with and without the block they were made from. */
class ParticleKeyTest {

    private static final String TERRAIN = "net.minecraft.client.particle.TerrainParticle";

    @Test
    void blockIsAddedAfterAnAt() {
        assertEquals("minecraft:block@minecraft:stone", ParticleKey.withBlock("minecraft:block", "minecraft:stone"));
    }

    @Test
    void baseAndBlockSplitAgain() {
        String key = ParticleKey.withBlock(TERRAIN, "geore:budding_ruby");

        assertEquals(TERRAIN, ParticleKey.base(key));
        assertEquals("geore:budding_ruby", ParticleKey.block(key));
    }

    @Test
    void plainParticleIsItsOwnBase() {
        assertSame(TERRAIN, ParticleKey.base(TERRAIN));
        assertNull(ParticleKey.block(TERRAIN));
    }

    @Test
    void typedDependsOnTheParticleNotTheBlock() {
        assertTrue(ParticleKey.isTyped("minecraft:block@minecraft:stone"));
        assertFalse(ParticleKey.isTyped(ParticleKey.withBlock(TERRAIN, "minecraft:stone")));
        assertFalse(ParticleKey.isTyped(TERRAIN));
    }

    @Test
    void displayNameShortensClassNamesAndShowsTheBlock() {
        assertEquals("minecraft:smoke", ParticleKey.displayName("minecraft:smoke"));
        assertEquals("TerrainParticle", ParticleKey.displayName(TERRAIN));
        assertEquals("TerrainParticle (minecraft:stone)", ParticleKey.displayName(ParticleKey.withBlock(TERRAIN, "minecraft:stone")));
        assertEquals("minecraft:block (minecraft:dirt)", ParticleKey.displayName("minecraft:block@minecraft:dirt"));
    }
}
