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
}
