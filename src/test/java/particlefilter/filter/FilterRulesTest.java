package particlefilter.filter;

import org.junit.jupiter.api.Test;
import particlefilter.config.FilterMode;
import particlefilter.config.Zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiling the saved rules into what particle spawning checks. */
class FilterRulesTest {

    private static final String SMOKE = "minecraft:smoke";
    private static final String FLAME = "minecraft:flame";

    /** A zone over blocks [0, 4) on every axis hiding [particles]. */
    private static Zone zone(String... particles) {
        Zone zone = new Zone();
        zone.setBox(0, 0, 0, 4, 4, 4);
        zone.particles = new ArrayList<>(List.of(particles));
        return zone;
    }

    /** Pretends only minecraft:iron_ore is in c:ores, so tag rules run without the game. */
    private static final ParticlePattern.TagCheck ORES = (block, tag) -> tag.equals("c:ores") && block.equals("minecraft:iron_ore");

    private static FilterRules.Snapshot compile(FilterMode mode, List<String> everywhere, Zone... zones) {
        return FilterRules.compile(mode, everywhere, List.of(zones), ORES);
    }

    // ---- particles everywhere and in zones ----

    @Test
    void particleHiddenEverywhereIsHiddenAnywhere() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(SMOKE));

        assertTrue(rules.hidesParticle(SMOKE, 1000, -50, 1e6));
        assertFalse(rules.hidesParticle(FLAME, 0, 0, 0));
    }

    @Test
    void zoneParticleIsHiddenOnlyInsideTheZone() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), zone(FLAME));

        assertTrue(rules.hidesParticle(FLAME, 2, 2, 2));
        assertFalse(rules.hidesParticle(FLAME, 5, 2, 2));
        assertFalse(rules.hidesParticle(SMOKE, 2, 2, 2));
    }

    // ---- particles made from a block ----

    private static final String DUST_STONE = ParticleKey.withBlock("minecraft:block", "minecraft:stone");
    private static final String DUST_DIRT = ParticleKey.withBlock("minecraft:block", "minecraft:dirt");

    @Test
    void ruleForOneBlockHidesOnlyThatBlocksParticles() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(DUST_STONE));

        assertTrue(rules.hidesParticle(DUST_STONE, 0, 0, 0));
        assertFalse(rules.hidesParticle(DUST_DIRT, 0, 0, 0));
        assertFalse(rules.hidesParticle("minecraft:block", 0, 0, 0));
    }

    @Test
    void ruleForTheParticleHidesItFromEveryBlock() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of("minecraft:block"));

        assertTrue(rules.hidesParticle(DUST_STONE, 0, 0, 0));
        assertTrue(rules.hidesParticle(DUST_DIRT, 0, 0, 0));
    }

    @Test
    void zoneRuleForOneBlockStaysInsideTheZone() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), zone(DUST_STONE));

        assertTrue(rules.hidesParticle(DUST_STONE, 2, 2, 2));
        assertFalse(rules.hidesParticle(DUST_STONE, 5, 2, 2));
        assertFalse(rules.hidesParticle(DUST_DIRT, 2, 2, 2));
    }

    @Test
    void zoneRuleForTheParticleCoversEveryBlockInsideIt() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), zone("minecraft:block"));

        assertTrue(rules.hidesParticle(DUST_DIRT, 2, 2, 2));
        assertFalse(rules.hidesParticle(DUST_DIRT, 5, 2, 2));
    }

    @Test
    void zoneEdgesMatchZoneContains() {
        // The compiled box and Zone.contains (used for outlines and the menu) must agree on every edge.
        Zone zone = zone(FLAME);
        zone.setBox(-3, 60, 7, 5, 2, 4);
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), zone);
        double[] xs = {-3.001, -3, -0.5, 1.999, 2};
        double[] ys = {59.999, 60, 61.999, 62};
        double[] zs = {6.999, 7, 10.999, 11};

        for (double x : xs) for (double y : ys) for (double z : zs) {
            assertEquals(zone.contains(x, y, z), rules.hidesParticle(FLAME, x, y, z), "at " + x + ", " + y + ", " + z);
        }
    }

    @Test
    void zoneWithNothingSwitchedOnIsLeftOut() {
        Zone off = zone();
        off.disabledParticles = new ArrayList<>(List.of(FLAME));

        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), off);

        assertEquals(List.of(), rules.zones());
        assertFalse(rules.hasParticleRules());
    }

    @Test
    void laterChangesToTheConfigDoNotLeakIn() {
        // Particles may spawn off the client thread while the menu edits the config, so the snapshot copies.
        List<String> everywhere = new ArrayList<>(List.of(SMOKE));
        Zone zone = zone(FLAME);
        FilterRules.Snapshot rules = compile(FilterMode.ALL, everywhere, zone);

        everywhere.add(FLAME);
        zone.particles.add(SMOKE);

        assertFalse(rules.hidesParticle(FLAME, 100, 100, 100));
        assertFalse(rules.everywhere().exact().contains(FLAME));
        assertEquals(Set.of(FLAME), rules.zones().get(0).rules().exact());
    }

    // ---- groups: wildcards and tags ----

    private static final String TERRAIN = "net.minecraft.client.particle.TerrainParticle";

    @Test
    void modWildcardHidesEveryParticleOfThatMod() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of("geore:*"));

        assertTrue(rules.hidesParticle("geore:sparkle", 0, 0, 0));
        assertFalse(rules.hidesParticle(SMOKE, 0, 0, 0));
        assertFalse(rules.hidesParticle("minecraft:block@geore:ruby_ore", 0, 0, 0));
    }

    @Test
    void everythingFromAModsBlocks() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of("*@geore:*"));

        assertTrue(rules.hidesParticle("minecraft:block@geore:ruby_ore", 0, 0, 0));
        assertTrue(rules.hidesParticle(TERRAIN + "@geore:ruby_ore", 0, 0, 0));
        assertFalse(rules.hidesParticle(TERRAIN + "@minecraft:stone", 0, 0, 0));
        assertTrue(rules.everywhere().hidesEverythingFrom("geore:ruby_ore", ORES));
        assertFalse(rules.everywhere().hidesEverythingFrom("minecraft:stone", ORES));
    }

    @Test
    void oneParticleFromATagsBlocks() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of("minecraft:block@#c:ores"));

        assertTrue(rules.hidesParticle("minecraft:block@minecraft:iron_ore", 0, 0, 0));
        assertFalse(rules.hidesParticle(TERRAIN + "@minecraft:iron_ore", 0, 0, 0));
        assertFalse(rules.hidesParticle("minecraft:block@minecraft:stone", 0, 0, 0));
        assertFalse(rules.everywhere().hidesEverythingFrom("minecraft:iron_ore", ORES), "only one particle from it is hidden");
    }

    @Test
    void groupInAZoneStaysInsideTheZone() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), zone("*@#c:ores"));

        assertTrue(rules.hidesParticle(TERRAIN + "@minecraft:iron_ore", 2, 2, 2));
        assertFalse(rules.hidesParticle(TERRAIN + "@minecraft:iron_ore", 5, 2, 2));
        assertFalse(rules.everywhere().hidesEverythingFrom("minecraft:iron_ore", ORES), "zones never skip breaking particles");
    }

    @Test
    void entriesAreReadWithMissingNamespacesAndSpaces() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(" smoke ", "*@stone"));

        assertTrue(rules.hidesParticle(SMOKE, 0, 0, 0));
        assertTrue(rules.hidesParticle(TERRAIN + "@minecraft:stone", 0, 0, 0));
    }

    @Test
    void invalidEntryOnlyMatchesItsExactName() {
        // As before groups existed, so a hand-edited oddity keeps doing what it did.
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of("Weird Name"));

        assertEquals(Set.of("Weird Name"), rules.everywhere().exact());
        assertTrue(rules.hidesParticle("Weird Name", 0, 0, 0));
        assertFalse(rules.hidesParticle(SMOKE, 0, 0, 0));
    }

    // ---- Active rules ----

    private static final List<String> EVERYWHERE = List.of(SMOKE, "*@minecraft:stone");
    private static final String STONE_DEBRIS = TERRAIN + "@minecraft:stone";

    @Test
    void allKeepsEveryRule() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, EVERYWHERE, zone(FLAME));

        assertTrue(rules.hidesParticle(SMOKE, 100, 100, 100));
        assertTrue(rules.hidesParticle(STONE_DEBRIS, 100, 100, 100));
        assertTrue(rules.hidesParticle(FLAME, 1, 1, 1));
    }

    @Test
    void everywhereOnlyPausesZones() {
        FilterRules.Snapshot rules = compile(FilterMode.EVERYWHERE, EVERYWHERE, zone(FLAME));

        assertTrue(rules.hidesParticle(SMOKE, 100, 100, 100));
        assertTrue(rules.everywhere().hidesEverythingFrom("minecraft:stone", ORES));
        assertFalse(rules.hidesParticle(FLAME, 1, 1, 1));
    }

    @Test
    void zonesOnlyPausesEverywhereIncludingBlockGroups() {
        FilterRules.Snapshot rules = compile(FilterMode.ZONES, EVERYWHERE, zone(FLAME));

        assertFalse(rules.hidesParticle(SMOKE, 100, 100, 100));
        assertFalse(rules.hidesParticle(STONE_DEBRIS, 100, 100, 100));
        assertFalse(rules.everywhere().hidesEverythingFrom("minecraft:stone", ORES));
        assertTrue(rules.hidesParticle(FLAME, 1, 1, 1));
    }

    @Test
    void noneHidesNothing() {
        // Also what holding the Show All Particles key compiles.
        FilterRules.Snapshot rules = compile(FilterMode.NONE, EVERYWHERE, zone(FLAME));

        assertFalse(rules.hasParticleRules());
        assertFalse(rules.everywhere().hidesEverythingFrom("minecraft:stone", ORES));
    }
}
