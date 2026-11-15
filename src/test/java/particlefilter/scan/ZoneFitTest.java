package particlefilter.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import particlefilter.config.Zone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ---- sources: one per group of spawn blocks close together ----

    private static Map<BlockPos, Integer> blocks(int... xs) {
        Map<BlockPos, Integer> map = new LinkedHashMap<>();
        for (int x : xs) map.put(new BlockPos(x, 64, 0), 1);
        return map;
    }

    @Test
    void oneBlockIsOneSource() {
        assertEquals(List.of(new ZoneFit.Source(new ZoneFit.Box(5, 64, 0, 1, 1, 1, false), 1)), ZoneFit.sources(blocks(5), 6));
    }

    @Test
    void noBlocksNoSources() {
        assertEquals(List.of(), ZoneFit.sources(Map.of(), 6));
    }

    @Test
    void blocksAtTheDistanceJoinAndOneFurtherSplit() {
        assertEquals(1, ZoneFit.sources(blocks(0, 6), 6).size());
        assertEquals(2, ZoneFit.sources(blocks(0, 7), 6).size());
    }

    @Test
    void sourcesDaisyChainThroughBlocksInBetween() {
        // 0 and 12 are 12 apart, but 6 sits within 6 of both.
        List<ZoneFit.Source> chained = ZoneFit.sources(blocks(0, 6, 12), 6);
        assertEquals(1, chained.size());
        assertEquals(new ZoneFit.Box(0, 64, 0, 13, 1, 1, false), chained.get(0).box());

        assertEquals(2, ZoneFit.sources(blocks(0, 12), 6).size());
    }

    @Test
    void distanceCountsOnEveryAxisSeparately() {
        // 6 apart on each axis, so within 6 even though farther in a straight line.
        Map<BlockPos, Integer> diagonal = Map.of(new BlockPos(0, 0, 0), 1, new BlockPos(6, 6, 6), 1);
        assertEquals(1, ZoneFit.sources(diagonal, 6).size());
        assertEquals(2, ZoneFit.sources(Map.of(new BlockPos(0, 0, 0), 1, new BlockPos(6, 7, 6), 1), 6).size());
    }

    @Test
    void sourcesWorkBelowZeroAndAcrossCells() {
        // -3 and 3 are 6 apart and fall in different grid cells.
        assertEquals(1, ZoneFit.sources(blocks(-3, 3), 6).size());
        assertEquals(1, ZoneFit.sources(blocks(-20, -14), 6).size());
    }

    @Test
    void busiestSourceComesFirst() {
        Map<BlockPos, Integer> counts = new LinkedHashMap<>();
        counts.put(new BlockPos(0, 64, 0), 3);
        counts.put(new BlockPos(100, 64, 0), 10);
        counts.put(new BlockPos(101, 64, 0), 5);

        List<ZoneFit.Source> sources = ZoneFit.sources(counts, 6);

        assertEquals(2, sources.size());
        assertEquals(new ZoneFit.Source(new ZoneFit.Box(100, 64, 0, 2, 1, 1, false), 15), sources.get(0));
        assertEquals(3, sources.get(1).count());
    }

    @Test
    void longChainIsCutToTheSizeLimit() {
        int[] xs = new int[100];
        for (int i = 0; i < xs.length; i++) xs[i] = i * 4; // 0..396, each 4 apart

        ZoneFit.Source source = ZoneFit.sources(blocks(xs), 6).get(0);

        assertEquals(Zone.MAX_SIZE, source.box().width());
        assertTrue(source.box().capped());
    }

    // ---- merge: zones sharing enough of the smaller one become one ----

    private static ZoneFit.Box box(int minX, int width) {
        return new ZoneFit.Box(minX, 64, 0, width, 4, 4, false);
    }

    @Test
    void boxInsideAnotherMergesEvenAt100Percent() {
        List<ZoneFit.Merged> merged = ZoneFit.merge(List.of(box(0, 10), box(2, 3)), 100);

        assertEquals(List.of(new ZoneFit.Merged(box(0, 10), List.of(0, 1))), merged);
    }

    @Test
    void halfOverlapMergesAt50ButNotAbove() {
        // 4 of the smaller box's 8 columns are shared: 50%.
        List<ZoneFit.Box> boxes = List.of(box(0, 8), box(4, 8));

        assertEquals(List.of(new ZoneFit.Merged(box(0, 12), List.of(0, 1))), ZoneFit.merge(boxes, 50));
        assertEquals(2, ZoneFit.merge(boxes, 51).size());
    }

    @Test
    void percentIsOfTheSmallerBox() {
        // The small box is entirely inside, but it is only a sliver of the big one.
        assertEquals(1, ZoneFit.merge(List.of(box(0, 100), box(10, 1)), 100).size());
    }

    @Test
    void boxesThatOnlyTouchDoNotMergeAboveZero() {
        assertEquals(2, ZoneFit.merge(List.of(box(0, 4), box(4, 4)), 1).size());
    }

    @Test
    void atZeroBoxesTouchingFaceToFaceMerge() {
        // Blocks 0-3 and 4-7 on x, the same blocks on y and z: no block shared, but a whole face.
        assertEquals(List.of(new ZoneFit.Merged(box(0, 8), List.of(0, 1))), ZoneFit.merge(List.of(box(0, 4), box(4, 4)), 0));
    }

    @Test
    void atZeroPartOfAFaceIsEnough() {
        ZoneFit.Box a = new ZoneFit.Box(0, 0, 0, 4, 4, 4, false);
        ZoneFit.Box b = new ZoneFit.Box(4, 3, 3, 4, 4, 4, false); // shares one block's worth of a's x = 4 face

        assertEquals(1, ZoneFit.merge(List.of(a, b), 0).size());
    }

    @Test
    void atZeroTouchingAlongAnEdgeOrAtACornerDoesNotMerge() {
        ZoneFit.Box a = new ZoneFit.Box(0, 0, 0, 4, 4, 4, false);
        ZoneFit.Box edge = new ZoneFit.Box(4, 4, 0, 4, 4, 4, false); // next to a on x and y: only an edge in common
        ZoneFit.Box corner = new ZoneFit.Box(4, 4, 4, 4, 4, 4, false);

        assertEquals(2, ZoneFit.merge(List.of(a, edge), 0).size());
        assertEquals(2, ZoneFit.merge(List.of(a, corner), 0).size());
    }

    @Test
    void atZeroAGapOfOneBlockDoesNotMerge() {
        assertEquals(2, ZoneFit.merge(List.of(box(0, 4), box(5, 4)), 0).size());
    }

    @Test
    void atZeroOverlappingBoxesStillMerge() {
        assertEquals(1, ZoneFit.merge(List.of(box(0, 100), box(99, 4)), 0).size());
    }

    @Test
    void mergingCarriesOnThroughTheMergedBox() {
        // A and B share 36% of A, so they merge at 30%. C sits in a corner of the box around them, touching neither
        // A nor B, so it only joins through the merged box.
        ZoneFit.Box a = new ZoneFit.Box(0, 0, 0, 10, 10, 1, false);
        ZoneFit.Box b = new ZoneFit.Box(4, 4, 0, 10, 10, 1, false);
        ZoneFit.Box c = new ZoneFit.Box(10, 0, 0, 4, 4, 1, false);

        List<ZoneFit.Merged> merged = ZoneFit.merge(List.of(a, b, c), 30);

        assertEquals(List.of(new ZoneFit.Merged(new ZoneFit.Box(0, 0, 0, 14, 14, 1, false), List.of(0, 1, 2))), merged);
        assertEquals(2, ZoneFit.merge(List.of(a, c), 30).size());
    }

    @Test
    void mergeNeverPassesTheSizeLimit() {
        assertEquals(2, ZoneFit.merge(List.of(box(0, 200), box(150, 200)), 1).size());
    }
}
