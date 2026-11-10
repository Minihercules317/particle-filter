package particlefilter.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import particlefilter.config.Zone;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zones made by Here: the smallest block box around the recorded spawn points, with no margin. */
class ZoneFitTest {

    private static AABB point(double x, double y, double z) {
        return new AABB(x, y, z, x, y, z);
    }

    @Test
    void oneSpawnPointFitsItsBlock() {
        ZoneFit.Box box = ZoneFit.fit(null, List.of(point(5.5, 64.2, -3.7)));

        assertEquals(new ZoneFit.Box(5, 64, -4, 1, 1, 1, false), box);
    }

    @Test
    void pointOnABlocksLowerFaceNeedsOnlyThatBlock() {
        ZoneFit.Box box = ZoneFit.fit(null, List.of(point(5.0, 64.0, 5.0)));

        assertEquals(new ZoneFit.Box(5, 64, 5, 1, 1, 1, false), box);
    }

    @Test
    void spawnBoxEndingOnAFaceReachesIntoTheNextBlock() {
        // Zones hold [min, max), so a spawn at x = 6.0 is in block 6.
        ZoneFit.Box box = ZoneFit.fit(null, List.of(new AABB(5.2, 64.5, 5.5, 6.0, 64.5, 5.5)));

        assertEquals(new ZoneFit.Box(5, 64, 5, 2, 1, 1, false), box);
    }

    @Test
    void severalPointsGetTheSmallestBoxHoldingAll() {
        ZoneFit.Box box = ZoneFit.fit(null, List.of(point(5.5, 64.5, 0.5), point(7.9, 66.1, 0.5), point(6.0, 64.5, 2.3)));

        assertEquals(new ZoneFit.Box(5, 64, 0, 3, 3, 3, false), box);
    }

    @Test
    void negativeCoordinatesRoundDown() {
        ZoneFit.Box box = ZoneFit.fit(null, List.of(point(-0.5, -60.5, -0.1), point(0.5, -60.5, 0.1)));

        assertEquals(new ZoneFit.Box(-1, -61, -1, 2, 1, 2, false), box);
    }

    @Test
    void noSpawnsGivesTheFallbackBlock() {
        ZoneFit.Box box = ZoneFit.fit(new BlockPos(10, 20, -30), List.of());

        assertEquals(new ZoneFit.Box(10, 20, -30, 1, 1, 1, false), box);
    }

    @Test
    void noSpawnsAndNoFallbackGivesNothing() {
        assertNull(ZoneFit.fit(null, List.of()));
    }

    @Test
    void fallbackIsIgnoredWhenThereAreSpawns() {
        ZoneFit.Box box = ZoneFit.fit(new BlockPos(100, 100, 100), List.of(point(5.5, 64.5, 5.5)));

        assertEquals(new ZoneFit.Box(5, 64, 5, 1, 1, 1, false), box);
    }

    @Test
    void sideOfExactlyMaxSizeIsNotCapped() {
        ZoneFit.Box box = ZoneFit.fit(null, List.of(point(0.5, 64.5, 0.5), point(Zone.MAX_SIZE - 0.5, 64.5, 0.5)));

        assertEquals(new ZoneFit.Box(0, 64, 0, Zone.MAX_SIZE, 1, 1, false), box);
    }

    @Test
    void tooLongASideKeepsItsMiddle() {
        // Blocks 0..299 are 300 long; the middle 256 start 22 blocks in.
        ZoneFit.Box box = ZoneFit.fit(null, List.of(point(0.5, 64.5, 0.5), point(299.5, 64.5, 0.5)));

        assertEquals(new ZoneFit.Box(22, 64, 0, Zone.MAX_SIZE, 1, 1, true), box);
    }

    @Test
    void boxContainsBoxesInsideItAndItself() {
        ZoneFit.Box outer = new ZoneFit.Box(0, 0, 0, 10, 10, 10, false);

        assertTrue(outer.contains(outer));
        assertTrue(outer.contains(new ZoneFit.Box(2, 3, 4, 8, 7, 6, false)));
        assertFalse(outer.contains(new ZoneFit.Box(2, 3, 4, 9, 7, 6, false)));
        assertFalse(outer.contains(new ZoneFit.Box(-1, 0, 0, 2, 2, 2, false)));
    }

    @Test
    void boxRoundTripsThroughAZone() {
        // Even and odd sizes, below zero: the zone stores a center, so the corner must come back unchanged.
        ZoneFit.Box box = new ZoneFit.Box(-7, -60, -3, 4, 3, 5, false);
        Zone zone = new Zone();

        box.applyTo(zone);

        assertEquals(box, ZoneFit.Box.of(zone));
        assertEquals(60, box.volume());
    }

    // ---- grow: an existing zone also covering new spawn points ----

    private static final ZoneFit.Box ZONE = new ZoneFit.Box(0, 64, 0, 4, 3, 4, false);

    @Test
    void growWithSpawnsInsideChangesNothing() {
        ZoneFit.Box grown = ZoneFit.grow(ZONE, List.of(point(0.5, 64.5, 0.5), point(3.9, 66.9, 3.9)));

        assertTrue(grown.sameBlocks(ZONE));
    }

    @Test
    void growWithNoSpawnsChangesNothing() {
        assertTrue(ZoneFit.grow(ZONE, List.of()).sameBlocks(ZONE));
    }

    @Test
    void growReachesSpawnsOutsideOnEverySide() {
        ZoneFit.Box grown = ZoneFit.grow(ZONE, List.of(point(-1.5, 63.5, 1.5), point(5.5, 67.5, 4.0)));

        assertEquals(new ZoneFit.Box(-2, 63, 0, 8, 5, 5, false), grown);
    }

    @Test
    void growNeverShrinks() {
        // Only one corner has spawns; the rest of the zone stays.
        ZoneFit.Box grown = ZoneFit.grow(ZONE, List.of(point(0.5, 64.5, 0.5)));

        assertTrue(grown.sameBlocks(ZONE));
        assertTrue(ZoneFit.grow(ZONE, List.of(point(10.5, 64.5, 0.5))).contains(ZONE));
    }

    @Test
    void growOnTheUpperFaceTakesTheNextBlock() {
        // The zone holds x in [0, 4); a spawn at x = 4.0 is in block 4.
        ZoneFit.Box grown = ZoneFit.grow(ZONE, List.of(point(4.0, 64.5, 0.5)));

        assertEquals(5, grown.width());
    }

    @Test
    void growPastTheLimitKeepsTheWholeZone() {
        // Spawns 400 blocks east: the side is cut to 256, but the zone at 0..3 stays inside.
        ZoneFit.Box grown = ZoneFit.grow(ZONE, List.of(point(400.5, 64.5, 0.5)));

        assertEquals(new ZoneFit.Box(0, 64, 0, Zone.MAX_SIZE, 3, 4, true), grown);
    }

    @Test
    void growPastTheLimitOnBothSidesKeepsTheMiddleAroundTheZone() {
        // Blocks -200..203 are 404 long; their middle 256 start at -126, which still holds the zone.
        ZoneFit.Box grown = ZoneFit.grow(ZONE, List.of(point(-199.5, 64.5, 0.5), point(203.5, 64.5, 0.5)));

        assertEquals(new ZoneFit.Box(-126, 64, 0, Zone.MAX_SIZE, 3, 4, true), grown);
        assertTrue(grown.contains(ZONE));
    }

    @Test
    void zoneAlreadyAtTheLimitStaysPut() {
        ZoneFit.Box full = new ZoneFit.Box(0, 64, 0, Zone.MAX_SIZE, 3, 4, false);

        ZoneFit.Box grown = ZoneFit.grow(full, List.of(point(300.5, 64.5, 0.5)));

        assertTrue(grown.sameBlocks(full));
        assertTrue(grown.capped());
    }

    // ---- growNear: only spots joined to the zone ----

    private static final int NEAR = 2;

    @Test
    void growNearTakesInSpotsNextToTheZone() {
        // The zone covers x 0..3; a spot at x 5 is 2 blocks out.
        ZoneFit.Box grown = ZoneFit.growNear(ZONE, List.of(new BlockPos(5, 65, 1)), NEAR);

        assertEquals(new ZoneFit.Box(0, 64, 0, 6, 3, 4, false), grown);
    }

    @Test
    void growNearLeavesASeparateSourceOut() {
        // One spot right next to the zone, another source 50 blocks away.
        ZoneFit.Box grown = ZoneFit.growNear(ZONE, List.of(new BlockPos(4, 64, 0), new BlockPos(54, 64, 0)), NEAR);

        assertEquals(new ZoneFit.Box(0, 64, 0, 5, 3, 4, false), grown);
    }

    @Test
    void growNearIgnoresSpotsJustTooFar() {
        ZoneFit.Box grown = ZoneFit.growNear(ZONE, List.of(new BlockPos(6, 64, 0)), NEAR);

        assertTrue(grown.sameBlocks(ZONE));
    }

    @Test
    void growNearFollowsAChainOfSpots() {
        // Each spot is within 2 blocks of the one before, so the whole trail joins the zone.
        ZoneFit.Box grown = ZoneFit.growNear(ZONE, List.of(new BlockPos(9, 64, 0), new BlockPos(7, 64, 0), new BlockPos(5, 64, 0)), NEAR);

        assertEquals(10, grown.width());
    }

    @Test
    void growNearWithNothingNearbyChangesNothing() {
        assertTrue(ZoneFit.growNear(ZONE, List.of(), NEAR).sameBlocks(ZONE));
        assertTrue(ZoneFit.growNear(ZONE, List.of(new BlockPos(1, 65, 1)), NEAR).sameBlocks(ZONE));
    }
}
