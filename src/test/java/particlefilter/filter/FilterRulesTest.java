package particlefilter.filter;

import net.minecraft.resources.ResourceLocation;
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

    private static FilterRules.Snapshot compile(FilterMode mode, List<String> everywhere, List<String> blocks, Zone... zones) {
        return FilterRules.compile(mode, everywhere, blocks, List.of(zones));
    }

    // ---- particles everywhere and in zones ----

    @Test
    void particleHiddenEverywhereIsHiddenAnywhere() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(SMOKE), List.of());

        assertTrue(rules.hidesParticle(SMOKE, 1000, -50, 1e6));
        assertFalse(rules.hidesParticle(FLAME, 0, 0, 0));
    }

    @Test
    void zoneParticleIsHiddenOnlyInsideTheZone() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), List.of(), zone(FLAME));

        assertTrue(rules.hidesParticle(FLAME, 2, 2, 2));
        assertFalse(rules.hidesParticle(FLAME, 5, 2, 2));
        assertFalse(rules.hidesParticle(SMOKE, 2, 2, 2));
    }

    @Test
    void zoneEdgesMatchZoneContains() {
        // The compiled box and Zone.contains (used for outlines and the menu) must agree on every edge.
        Zone zone = zone(FLAME);
        zone.setBox(-3, 60, 7, 5, 2, 4);
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), List.of(), zone);
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

        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), List.of(), off);

        assertEquals(List.of(), rules.zones());
        assertFalse(rules.hasParticleRules());
    }

    @Test
    void laterChangesToTheConfigDoNotLeakIn() {
        // Particles may spawn off the client thread while the menu edits the config, so the snapshot copies.
        List<String> everywhere = new ArrayList<>(List.of(SMOKE));
        Zone zone = zone(FLAME);
        FilterRules.Snapshot rules = compile(FilterMode.ALL, everywhere, List.of(), zone);

        everywhere.add(FLAME);
        zone.particles.add(SMOKE);

        assertFalse(rules.hidesParticle(FLAME, 100, 100, 100));
        assertFalse(rules.particles().contains(FLAME));
        assertEquals(Set.of(FLAME), rules.zones().get(0).particles());
    }

    // ---- Active rules ----

    private static final List<String> EVERYWHERE = List.of(SMOKE);
    private static final List<String> BLOCKS = List.of("minecraft:stone");

    @Test
    void allKeepsEveryRule() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, EVERYWHERE, BLOCKS, zone(FLAME));

        assertTrue(rules.hidesParticle(SMOKE, 100, 100, 100));
        assertTrue(rules.hidesParticle(FLAME, 1, 1, 1));
        assertTrue(rules.hasBlockRules());
    }

    @Test
    void everywhereOnlyPausesZones() {
        FilterRules.Snapshot rules = compile(FilterMode.EVERYWHERE, EVERYWHERE, BLOCKS, zone(FLAME));

        assertTrue(rules.hidesParticle(SMOKE, 100, 100, 100));
        assertFalse(rules.hidesParticle(FLAME, 1, 1, 1));
        assertTrue(rules.hasBlockRules());
    }

    @Test
    void zonesOnlyPausesEverywhereAndBlocks() {
        FilterRules.Snapshot rules = compile(FilterMode.ZONES, EVERYWHERE, BLOCKS, zone(FLAME));

        assertFalse(rules.hidesParticle(SMOKE, 100, 100, 100));
        assertTrue(rules.hidesParticle(FLAME, 1, 1, 1));
        assertFalse(rules.hasBlockRules());
    }

    @Test
    void noneHidesNothing() {
        // Also what holding the Show All Particles key compiles.
        FilterRules.Snapshot rules = compile(FilterMode.NONE, EVERYWHERE, BLOCKS, zone(FLAME));

        assertFalse(rules.hasParticleRules());
        assertFalse(rules.hasBlockRules());
    }

    // ---- block entries ----

    @Test
    void blockIdsAndWholeModsAreSortedByKind() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), List.of("minecraft:stone", "create:*"));

        assertEquals(Set.of(ResourceLocation.tryParse("minecraft:stone")), rules.blockIds());
        assertEquals(Set.of("create"), rules.blockNamespaces());
        assertEquals(List.of(), rules.blockTags());
    }

    // Making a tag key loads Minecraft's registries; before 1.21 that needs the game started, so 1.20.1 can't run this.
    //? if >1.20.1 {
    @Test
    void blockTagEntriesBecomeTags() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), List.of("#minecraft:logs"));

        assertEquals(1, rules.blockTags().size());
        assertEquals(ResourceLocation.tryParse("minecraft:logs"), rules.blockTags().get(0).location());
        assertEquals(Set.of(), rules.blockIds());
    }
    //?}

    @Test
    void spacesAroundEntriesAreIgnored() {
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), List.of("  minecraft:stone ", " create:* "));

        assertEquals(Set.of(ResourceLocation.tryParse("minecraft:stone")), rules.blockIds());
        assertEquals(Set.of("create"), rules.blockNamespaces());
    }

    @Test
    void invalidBlockEntriesAreSkipped() {
        // Ids can't have capitals or spaces; the rest still apply.
        FilterRules.Snapshot rules = compile(FilterMode.ALL, List.of(), List.of("Minecraft:Stone", "#not a tag", "minecraft:dirt"));

        assertEquals(Set.of(ResourceLocation.tryParse("minecraft:dirt")), rules.blockIds());
        assertEquals(List.of(), rules.blockTags());
    }
}
