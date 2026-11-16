package particlefilter.config;

import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading config/particlefilter.json, including files written by older versions. */
class PfConfigTest {

    // ---- reading files ----

    @Test
    void emptyObjectGetsEveryDefault() {
        PfConfig.Data data = PfConfig.parse("{}");

        assertEquals(List.of(), data.hiddenParticles);
        assertEquals(List.of(), data.hiddenBlocks);
        assertEquals(List.of(), data.zones);
        assertEquals(FilterMode.ALL, data.mode);
        assertEquals(List.of(), data.disabledParticles);
        assertEquals(List.of(), data.disabledBlocks);
        assertEquals(PfConfig.DEFAULT_SCAN_SECONDS, data.scanSeconds);
        assertEquals(PfConfig.DEFAULT_SOURCE_DISTANCE, data.sourceDistance);
        assertEquals(PfConfig.DEFAULT_MERGE_PERCENT, data.mergePercent);
        assertEquals(PfConfig.DEFAULT_SCAN_RADIUS, data.scanRadius);
        assertEquals(PfConfig.DEFAULT_MAX_ZONES, data.maxZones);
        assertFalse(data.zoneOptionsOpen);
        assertFalse(data.walkInMenu);
        assertFalse(data.zoneTracers);
        assertNull(data.window);
    }

    @Test
    void emptyFileGivesNothing() {
        // load() then keeps the defaults it started with.
        assertNull(PfConfig.parse(""));
    }

    @Test
    void brokenJsonIsAnError() {
        assertThrows(JsonSyntaxException.class, () -> PfConfig.parse("{\"hiddenParticles\": [\"minecraft:smoke\""));
    }

    @Test
    void oldestFormatWithRadiusZones() {
        // The first build (commit fc72b78) stored each zone as a cube of radius r around (x, y, z).
        PfConfig.Data data = PfConfig.parse("""
            {
              "hiddenParticles": ["minecraft:smoke"],
              "hiddenBlocks": ["minecraft:stone"],
              "zones": [{
                "id": "a", "name": "Furnaces", "world": "My World", "dimension": "minecraft:overworld",
                "x": 10, "y": 64, "z": -20, "radius": 8,
                "particles": ["minecraft:flame"]
              }]
            }
            """);

        Zone zone = data.zones.get(0);
        assertEquals(List.of(17, 17, 17), List.of(zone.width, zone.height, zone.length));
        assertEquals(List.of(2, 56, -28), List.of(zone.minX(), zone.minY(), zone.minZ()));
        assertNull(zone.radius);
        assertEquals(List.of("minecraft:flame"), zone.particles);
        assertEquals(FilterMode.ALL, data.mode);
    }

    @Test
    void version120Format() {
        // 1.2.0 had boxes and the Active rules mode, but no switched-off entries, colors or scan lengths.
        PfConfig.Data data = PfConfig.parse("""
            {
              "hiddenParticles": ["minecraft:smoke"],
              "hiddenBlocks": ["#minecraft:logs", "create:*"],
              "zones": [{
                "id": "a", "name": "Zone 1", "world": "play.example.net", "dimension": "minecraft:overworld",
                "x": 0, "y": 60, "z": 0, "width": 4, "height": 3, "length": 6,
                "particles": ["ae2:energy_fx"]
              }],
              "mode": "ZONES"
            }
            """);

        assertEquals(FilterMode.ZONES, data.mode);
        assertEquals(List.of("#minecraft:logs", "create:*"), data.hiddenBlocks);
        Zone zone = data.zones.get(0);
        assertEquals(List.of(4, 3, 6), List.of(zone.width, zone.height, zone.length));
        assertEquals(List.of(), zone.disabledParticles);
        assertNull(zone.color); // load() gives it one
        assertEquals(PfConfig.DEFAULT_SCAN_SECONDS, data.scanSeconds);
        assertEquals(PfConfig.DEFAULT_SOURCE_DISTANCE, data.sourceDistance);
    }

    @Test
    void unknownModeFallsBackToAll() {
        // e.g. a mode added by a newer version
        assertEquals(FilterMode.ALL, PfConfig.parse("{\"mode\": \"SOMETHING_NEW\"}").mode);
    }

    @Test
    void unknownFieldsAreIgnored() {
        PfConfig.Data data = PfConfig.parse("{\"hiddenParticles\": [\"minecraft:smoke\"], \"addedLater\": 5}");

        assertEquals(List.of("minecraft:smoke"), data.hiddenParticles);
    }

    @Test
    void entryBothOnAndOffStaysOn() {
        PfConfig.Data data = PfConfig.parse("""
            {
              "hiddenParticles": ["minecraft:smoke"], "disabledParticles": ["minecraft:smoke", "minecraft:flame"],
              "hiddenBlocks": ["minecraft:stone"], "disabledBlocks": ["minecraft:stone"]
            }
            """);

        assertEquals(List.of("minecraft:flame"), data.disabledParticles);
        assertEquals(List.of(), data.disabledBlocks);
    }

    @Test
    void scanLengthIsPutInRange() {
        assertEquals(PfConfig.MIN_SCAN_SECONDS, PfConfig.parse("{\"scanSeconds\": 0}").scanSeconds);
        assertEquals(PfConfig.MAX_SCAN_SECONDS, PfConfig.parse("{\"scanSeconds\": 999}").scanSeconds);
    }

    @Test
    void diagnosticLengthFromBeforeTheMergeIsIgnored() {
        // Development builds of 1.3.0 had a separate Diagnostic page with its own scan length.
        PfConfig.Data data = PfConfig.parse("{\"scanSeconds\": 10, \"diagnosticSeconds\": 20, \"sourceDistance\": 4}");

        assertEquals(10, data.scanSeconds);
        assertEquals(4, data.sourceDistance);
    }

    @Test
    void maxZonesIsPutInRange() {
        assertEquals(PfConfig.MIN_MAX_ZONES, PfConfig.parse("{\"maxZones\": 0}").maxZones);
        assertEquals(PfConfig.MAX_MAX_ZONES, PfConfig.parse("{\"maxZones\": 100}").maxZones);
        assertEquals(3, PfConfig.parse("{\"maxZones\": 3}").maxZones);
    }

    @Test
    void zoneOptionsOpenIsKept() {
        assertTrue(PfConfig.parse("{\"zoneOptionsOpen\": true}").zoneOptionsOpen);
    }

    @Test
    void sourceDistanceIsPutInRange() {
        assertEquals(PfConfig.MIN_SOURCE_DISTANCE, PfConfig.parse("{\"sourceDistance\": 0}").sourceDistance);
        assertEquals(PfConfig.MAX_SOURCE_DISTANCE, PfConfig.parse("{\"sourceDistance\": 500}").sourceDistance);
        assertEquals(10, PfConfig.parse("{\"sourceDistance\": 10}").sourceDistance);
    }

    @Test
    void mergePercentIsPutInRange() {
        assertEquals(PfConfig.MIN_MERGE_PERCENT, PfConfig.parse("{\"mergePercent\": -5}").mergePercent);
        assertEquals(0, PfConfig.parse("{\"mergePercent\": 0}").mergePercent);
        assertEquals(PfConfig.MAX_MERGE_PERCENT, PfConfig.parse("{\"mergePercent\": 150}").mergePercent);
    }

    @Test
    void scanRadiusIsPutInRange() {
        assertEquals(PfConfig.MIN_SCAN_RADIUS, PfConfig.parse("{\"scanRadius\": 0}").scanRadius);
        assertEquals(PfConfig.MAX_SCAN_RADIUS, PfConfig.parse("{\"scanRadius\": 600}").scanRadius);
        assertEquals(20, PfConfig.parse("{\"scanRadius\": 20}").scanRadius);
    }

    @Test
    void windowReadsBackAndAnEmptyOneIsDropped() {
        assertEquals(new PfConfig.Window(10, 20, 500, 300),
            PfConfig.parse("{\"window\": {\"x\": 10, \"y\": 20, \"width\": 500, \"height\": 300}}").window);
        assertNull(PfConfig.parse("{\"window\": {\"x\": 10}}").window);
    }

    @Test
    void configScreenSettingsReadBack() {
        PfConfig.Data data = PfConfig.parse("{\"walkInMenu\": true, \"zoneTracers\": true}");

        assertTrue(data.walkInMenu);
        assertTrue(data.zoneTracers);
    }

    @Test
    void savedFileReadsBackTheSame() {
        PfConfig.Data data = PfConfig.parse("""
            {
              "hiddenParticles": ["minecraft:smoke"], "disabledParticles": ["minecraft:flame"],
              "zones": [{"id": "a", "x": 3, "y": 4, "z": 5, "width": 2, "height": 2, "length": 2, "color": 3}],
              "mode": "EVERYWHERE", "scanSeconds": 10
            }
            """);

        String saved = PfConfig.toJson(data);

        assertEquals(saved, PfConfig.toJson(PfConfig.parse(saved)));
    }

    // ---- outline colors ----

    private static Zone zone(String world, int minX, Integer color) {
        Zone zone = new Zone();
        zone.world = world;
        zone.dimension = "minecraft:overworld";
        zone.setBox(minX, 0, 0, 4, 4, 4);
        zone.color = color;
        return zone;
    }

    @Test
    void zonesWithValidColorsKeepThem() {
        Zone a = zone("w", 0, 5);
        Zone b = zone("w", 100, 5);

        PfConfig.assignColors(List.of(a, b));

        assertEquals(5, a.color);
        assertEquals(5, b.color); // far apart, so sharing a color is fine
    }

    @Test
    void touchingZonesWithTheSameColorAreSplitAndTheEarlierKeepsIt() {
        Zone a = zone("w", 0, 2);
        Zone b = zone("w", 4, 2);

        PfConfig.assignColors(List.of(a, b));

        assertEquals(2, a.color);
        assertNotEquals(2, b.color);
    }

    @Test
    void zoneWithoutColorGetsTheLeastUsedOne() {
        Zone a = zone("w", 0, 0);
        Zone b = zone("w", 100, 1);
        Zone c = zone("w", 200, null);

        PfConfig.assignColors(List.of(a, b, c));

        assertEquals(2, c.color);
    }

    @Test
    void colorOutOfRangeIsReplaced() {
        Zone a = zone("w", 0, Zone.COLOR_COUNT);
        Zone b = zone("w", 100, -1);

        PfConfig.assignColors(List.of(a, b));

        assertEquals(0, a.color);
        assertEquals(1, b.color);
    }

    @Test
    void zonesInOtherWorldsDoNotCount() {
        Zone a = zone("other", 0, 0);
        Zone b = zone("w", 0, 0);

        PfConfig.assignColors(List.of(a, b));

        assertEquals(0, b.color); // same place and color, but a different world
    }

    @Test
    void whenEveryColorTouchesTheZoneItTakesTheLeastUsed() {
        // Eight zones around one spot, one of each color, plus a second zone of color 0 far away.
        List<Zone> zones = new java.util.ArrayList<>();
        for (int c = 0; c < Zone.COLOR_COUNT; c++) {
            Zone z = new Zone();
            z.world = "w";
            z.dimension = "minecraft:overworld";
            z.setBox(c, 0, 0, 1, 1, 1);
            z.color = c;
            zones.add(z);
        }
        zones.add(zone("w", 100, 0));
        Zone middle = new Zone();
        middle.world = "w";
        middle.dimension = "minecraft:overworld";
        middle.setBox(0, 0, 0, Zone.COLOR_COUNT, 1, 1);
        zones.add(middle);

        PfConfig.assignColors(zones);

        assertEquals(1, middle.color);
    }
}
