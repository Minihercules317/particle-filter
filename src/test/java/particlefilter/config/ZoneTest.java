package particlefilter.config;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneTest {

    /** A zone covering blocks [minX, minX + width) and so on. */
    private static Zone zone(int minX, int minY, int minZ, int width, int height, int length) {
        Zone zone = new Zone();
        zone.setBox(minX, minY, minZ, width, height, length);
        return zone;
    }

    // contains: zones hold [min, max) on every axis

    @Test
    void lowerEdgesAreInsideAndUpperEdgesOutside() {
        Zone zone = zone(0, 64, 0, 4, 2, 4);

        assertTrue(zone.contains(0, 64, 0));
        assertTrue(zone.contains(3.999, 65.999, 3.999));
        assertFalse(zone.contains(4, 64, 0));
        assertFalse(zone.contains(0, 66, 0));
        assertFalse(zone.contains(0, 64, 4));
        assertFalse(zone.contains(-0.001, 64, 0));
    }

    @Test
    void blockIsInsideWhenItsCenterIs() {
        Zone zone = zone(0, 64, 0, 4, 2, 4);

        assertTrue(zone.contains(new BlockPos(3, 65, 3)));
        assertFalse(zone.contains(new BlockPos(4, 65, 3)));
        assertFalse(zone.contains(new BlockPos(-1, 64, 0)));
    }

    // setBox: stored as center column and bottom, so the corner must survive the round trip

    @Test
    void setBoxKeepsTheCornerForEvenAndOddSizes() {
        Zone even = zone(-7, -60, 10, 4, 3, 6);
        Zone odd = zone(-7, -60, 10, 5, 3, 7);

        assertEquals(-7, even.minX());
        assertEquals(-60, even.minY());
        assertEquals(10, even.minZ());
        assertEquals(-7, odd.minX());
        assertEquals(10, odd.minZ());
    }

    @Test
    void setBoxClampsSizes() {
        Zone zone = zone(0, 0, 0, 0, -5, Zone.MAX_SIZE + 100);

        assertEquals(Zone.MIN_SIZE, zone.width);
        assertEquals(Zone.MIN_SIZE, zone.height);
        assertEquals(Zone.MAX_SIZE, zone.length);
        assertEquals(0, zone.minX());
    }

    // touches: sharing space, a face, an edge or a corner counts

    @Test
    void zonesSharingAFaceTouch() {
        assertTrue(zone(0, 0, 0, 4, 4, 4).touches(zone(4, 0, 0, 4, 4, 4)));
    }

    @Test
    void zonesSharingOnlyACornerTouch() {
        assertTrue(zone(0, 0, 0, 4, 4, 4).touches(zone(4, 4, 4, 2, 2, 2)));
    }

    @Test
    void overlappingZonesTouch() {
        assertTrue(zone(0, 0, 0, 4, 4, 4).touches(zone(1, 1, 1, 2, 2, 2)));
    }

    @Test
    void zonesWithAGapDoNotTouch() {
        assertFalse(zone(0, 0, 0, 4, 4, 4).touches(zone(5, 0, 0, 4, 4, 4)));
        assertFalse(zone(5, 0, 0, 4, 4, 4).touches(zone(0, 0, 0, 4, 4, 4)));
    }

    @Test
    void sameWorldNeedsWorldAndDimension() {
        Zone a = new Zone();
        a.world = "server.example";
        a.dimension = "minecraft:overworld";
        Zone b = new Zone();
        b.world = "server.example";
        b.dimension = "minecraft:the_nether";

        assertTrue(a.sameWorld(a));
        assertFalse(a.sameWorld(b));
    }

    // text shown in the menu

    @Test
    void menuText() {
        Zone zone = zone(-2, 64, 8, 5, 2, 22);

        assertEquals("5 × 2 × 22", zone.sizeText());
        assertEquals("0, 64, 19", zone.coordsText());
        assertEquals(220, zone.volume());
        assertEquals("16,777,216", Zone.formatCount(zone(0, 0, 0, 256, 256, 256).volume()));
    }

    // normalize: loading older or hand-edited config files

    @Test
    void missingFieldsGetDefaults() {
        Zone zone = new Zone();
        zone.id = null;
        zone.name = null;
        zone.world = null;
        zone.dimension = null;
        zone.particles = null;
        zone.disabledParticles = null;

        zone.normalize();

        assertNotNull(zone.id);
        assertEquals("Zone", zone.name);
        assertEquals("", zone.world);
        assertEquals("", zone.dimension);
        assertEquals(List.of(), zone.particles);
        assertEquals(List.of(), zone.disabledParticles);
    }

    @Test
    void particleBothOnAndOffStaysOn() {
        Zone zone = new Zone();
        zone.particles = new ArrayList<>(List.of("minecraft:smoke"));
        zone.disabledParticles = new ArrayList<>(List.of("minecraft:smoke", "minecraft:flame"));

        zone.normalize();

        assertEquals(List.of("minecraft:smoke"), zone.particles);
        assertEquals(List.of("minecraft:flame"), zone.disabledParticles);
    }

    @Test
    void oldRadiusZoneBecomesACubeAroundTheSamePoint() {
        // The first build (commit fc72b78) stored a cube of radius r around (x, y, z).
        Zone zone = new Zone();
        zone.x = 10;
        zone.y = 64;
        zone.z = -20;
        zone.radius = 2;

        zone.normalize();

        assertNull(zone.radius);
        assertEquals(5, zone.width);
        assertEquals(5, zone.height);
        assertEquals(5, zone.length);
        assertEquals(8, zone.minX());
        assertEquals(62, zone.minY());
        assertEquals(-22, zone.minZ());
    }

    @Test
    void negativeRadiusBecomesOneBlock() {
        Zone zone = new Zone();
        zone.radius = -3;

        zone.normalize();

        assertEquals(1, zone.width);
        assertEquals(1, zone.height);
        assertEquals(1, zone.length);
    }

    @Test
    void handEditedSizesAreClamped() {
        Zone zone = new Zone();
        zone.width = 0;
        zone.height = 1000;
        zone.length = -4;

        zone.normalize();

        assertEquals(Zone.MIN_SIZE, zone.width);
        assertEquals(Zone.MAX_SIZE, zone.height);
        assertEquals(Zone.MIN_SIZE, zone.length);
    }
}
