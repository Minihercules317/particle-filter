package particlefilter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import particlefilter.ParticleFilter;
import particlefilter.filter.FilterRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent settings in config/particlefilter.json. Only used on the client thread; particle spawning reads the
 * compiled {@link FilterRules} snapshot instead.
 *
 * Particle entries are particle type ids ("ae2:energy_fx") or, for particles a mod creates without a type, the
 * particle's class name. Block entries are block ids, "namespace:*", or "#tag".
 *
 * Every kind of entry has two lists: switched on (applied) and switched off (kept in the menu but not applied).
 * Off entries live in separate fields so versions that don't know them never hide them.
 */
public final class PfConfig {

    /** Size of zones made with New zone; the block at the player's feet is the bottom layer. */
    public static final int NEW_ZONE_WIDTH = 9;
    public static final int NEW_ZONE_HEIGHT = 5;
    public static final int NEW_ZONE_LENGTH = 9;
    public static final int NEW_ZONE_DEPTH_BELOW = 2;

    public static final int MIN_SCAN_SECONDS = 1;
    public static final int MAX_SCAN_SECONDS = 60;
    public static final int DEFAULT_SCAN_SECONDS = 3;

    private static final class Data {
        List<String> hiddenParticles = new ArrayList<>();
        List<String> hiddenBlocks = new ArrayList<>();
        List<Zone> zones = new ArrayList<>();
        /** Added in 1.1.0; older files lack it and older versions ignore it. */
        FilterMode mode = FilterMode.ALL;
        /** Added after 1.2.0, like the fields below: entries switched off in the menu. */
        List<String> disabledParticles = new ArrayList<>();
        List<String> disabledBlocks = new ArrayList<>();
        Integer scanSeconds = DEFAULT_SCAN_SECONDS;
        Integer diagnosticSeconds = DEFAULT_SCAN_SECONDS;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;

    private static Data data = new Data();

    private PfConfig() {}

    public static void load(Path path) {
        file = path;
        if (Files.exists(file)) {
            try {
                Data loaded = GSON.fromJson(Files.readString(file), Data.class);
                if (loaded != null) {
                    if (loaded.hiddenParticles == null) loaded.hiddenParticles = new ArrayList<>();
                    if (loaded.hiddenBlocks == null) loaded.hiddenBlocks = new ArrayList<>();
                    if (loaded.zones == null) loaded.zones = new ArrayList<>();
                    if (loaded.mode == null) loaded.mode = FilterMode.ALL; // missing or unknown value
                    if (loaded.disabledParticles == null) loaded.disabledParticles = new ArrayList<>();
                    if (loaded.disabledBlocks == null) loaded.disabledBlocks = new ArrayList<>();
                    loaded.disabledParticles.removeAll(loaded.hiddenParticles);
                    loaded.disabledBlocks.removeAll(loaded.hiddenBlocks);
                    if (loaded.scanSeconds == null) loaded.scanSeconds = DEFAULT_SCAN_SECONDS;
                    loaded.scanSeconds = clampScanSeconds(loaded.scanSeconds);
                    if (loaded.diagnosticSeconds == null) loaded.diagnosticSeconds = DEFAULT_SCAN_SECONDS;
                    loaded.diagnosticSeconds = clampScanSeconds(loaded.diagnosticSeconds);
                    loaded.zones.forEach(Zone::normalize);
                    data = loaded;
                }
            } catch (Exception e) {
                ParticleFilter.LOGGER.error("Couldn't read {}; starting with an empty config", file, e);
            }
        }
        assignColors();
        save(); // writes defaults on first run and upgrades older formats
        FilterRules.rebuild();
    }

    private static void save() {
        try {
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            ParticleFilter.LOGGER.error("Couldn't save {}", file, e);
        }
    }

    /** Saves and recompiles the filter rules. Call after any change, including direct edits to a {@link Zone}. */
    public static void changed() {
        assignColors();
        save();
        FilterRules.rebuild();
    }

    // ---- which rules apply ----

    public static FilterMode mode() {
        return data.mode;
    }

    public static void setMode(FilterMode mode) {
        data.mode = mode;
        changed();
    }

    // ---- scanning ----

    public static int scanSeconds() {
        return data.scanSeconds;
    }

    public static void setScanSeconds(int seconds) {
        data.scanSeconds = clampScanSeconds(seconds);
        save();
    }

    public static int diagnosticSeconds() {
        return data.diagnosticSeconds;
    }

    public static void setDiagnosticSeconds(int seconds) {
        data.diagnosticSeconds = clampScanSeconds(seconds);
        save();
    }

    private static int clampScanSeconds(int seconds) {
        return Math.max(MIN_SCAN_SECONDS, Math.min(MAX_SCAN_SECONDS, seconds));
    }

    // ---- entry lists: hide adds or switches on, remove deletes, setOn moves between the on and off lists ----

    private static void hide(List<String> on, List<String> off, String entry) {
        off.remove(entry);
        if (!on.contains(entry)) on.add(entry);
        changed();
    }

    private static void remove(List<String> on, List<String> off, String entry) {
        on.remove(entry);
        off.remove(entry);
        changed();
    }

    private static void setOn(List<String> on, List<String> off, String entry, boolean active) {
        if (!on.contains(entry) && !off.contains(entry)) return; // deleted meanwhile
        on.remove(entry);
        off.remove(entry);
        (active ? on : off).add(entry);
        changed();
    }

    // ---- particles hidden everywhere ----

    public static List<String> hiddenParticles() {
        return List.copyOf(data.hiddenParticles);
    }

    /** Particles listed under Everywhere but switched off. */
    public static List<String> disabledParticles() {
        return List.copyOf(data.disabledParticles);
    }

    public static boolean isHiddenEverywhere(String particle) {
        return data.hiddenParticles.contains(particle);
    }

    /** Hiding adds [particle] (or switches it back on); showing deletes the entry. */
    public static void setHiddenEverywhere(String particle, boolean hidden) {
        if (hidden) hide(data.hiddenParticles, data.disabledParticles, particle);
        else remove(data.hiddenParticles, data.disabledParticles, particle);
    }

    /** Switches an Everywhere entry on or off, keeping it listed. */
    public static void setEverywhereOn(String particle, boolean on) {
        setOn(data.hiddenParticles, data.disabledParticles, particle, on);
    }

    // ---- block breaking/mining/dust particles ----

    public static List<String> hiddenBlocks() {
        return List.copyOf(data.hiddenBlocks);
    }

    public static List<String> disabledBlocks() {
        return List.copyOf(data.disabledBlocks);
    }

    /** True if [entry] is listed and switched on. */
    public static boolean hasBlockEntry(String entry) {
        return data.hiddenBlocks.contains(entry);
    }

    /** Hiding adds [entry] (or switches it back on); showing deletes the entry. */
    public static void setBlockHidden(String entry, boolean hidden) {
        if (hidden) hide(data.hiddenBlocks, data.disabledBlocks, entry);
        else remove(data.hiddenBlocks, data.disabledBlocks, entry);
    }

    public static void setBlockOn(String entry, boolean on) {
        setOn(data.hiddenBlocks, data.disabledBlocks, entry, on);
    }

    // ---- zones ----

    public static List<Zone> zones() {
        return List.copyOf(data.zones);
    }

    public static Zone zoneById(String id) {
        if (id == null) return null;
        return data.zones.stream().filter(z -> z.id.equals(id)).findFirst().orElse(null);
    }

    public static boolean isInCurrentWorld(Zone zone) {
        return zone.world.equals(FilterRules.currentWorld()) && zone.dimension.equals(FilterRules.currentDimension());
    }

    /** Zones in the world and dimension the player is in now. */
    public static List<Zone> zonesHere() {
        if (FilterRules.currentWorld() == null) return List.of();
        return data.zones.stream().filter(PfConfig::isInCurrentWorld).toList();
    }

    /** A new zone in the current world, not yet added. Null when not in a world. */
    private static Zone newZone() {
        if (FilterRules.currentWorld() == null) return null;
        Zone zone = new Zone();
        zone.world = FilterRules.currentWorld();
        zone.dimension = FilterRules.currentDimension();
        zone.name = nextZoneName();
        return zone;
    }

    /** Creates and adds a default-sized zone around [pos], [NEW_ZONE_DEPTH_BELOW] blocks below it. Null when not in a world. */
    public static Zone createZone(BlockPos pos) {
        Zone zone = newZone();
        if (zone == null) return null;
        zone.x = pos.getX();
        zone.y = pos.getY() - NEW_ZONE_DEPTH_BELOW;
        zone.z = pos.getZ();
        zone.width = NEW_ZONE_WIDTH;
        zone.height = NEW_ZONE_HEIGHT;
        zone.length = NEW_ZONE_LENGTH;
        return add(zone);
    }

    /** Creates and adds a zone covering the given blocks. Null when not in a world. */
    public static Zone createZone(int minX, int minY, int minZ, int width, int height, int length) {
        Zone zone = newZone();
        if (zone == null) return null;
        zone.setBox(minX, minY, minZ, width, height, length);
        return add(zone);
    }

    private static Zone add(Zone zone) {
        data.zones.add(zone);
        changed();
        return zone;
    }

    private static String nextZoneName() {
        for (int n = 1; ; n++) {
            String name = "Zone " + n;
            if (data.zones.stream().noneMatch(z -> z.name.equals(name))) return name;
        }
    }

    public static void deleteZone(Zone zone) {
        data.zones.remove(zone);
        changed();
    }

    public static boolean isHiddenInZone(Zone zone, String particle) {
        return zone.particles.contains(particle);
    }

    /** Hiding adds [particle] to [zone] (or switches it back on); showing deletes the entry. */
    public static void setHiddenInZone(Zone zone, String particle, boolean hidden) {
        if (hidden) hide(zone.particles, zone.disabledParticles, particle);
        else remove(zone.particles, zone.disabledParticles, particle);
    }

    public static void setZoneEntryOn(Zone zone, String particle, boolean on) {
        setOn(zone.particles, zone.disabledParticles, particle, on);
    }

    // ---- outline colors ----

    /**
     * Gives every zone without a color, or with the same color as an earlier zone it touches, the color least used
     * in its world among those its neighbours don't have. Earlier zones keep theirs, so colors rarely change.
     */
    private static void assignColors() {
        List<Zone> zones = data.zones;
        for (int i = 0; i < zones.size(); i++) {
            Zone zone = zones.get(i);
            boolean clash = zone.color == null || zone.color < 0 || zone.color >= Zone.COLOR_COUNT;
            for (int j = 0; j < i && !clash; j++) {
                Zone other = zones.get(j);
                clash = zone.color.equals(other.color) && zone.sameWorld(other) && zone.touches(other);
            }
            if (clash) zone.color = pickColor(zone, zones.subList(0, i));
        }
    }

    private static int pickColor(Zone zone, List<Zone> earlier) {
        int[] uses = new int[Zone.COLOR_COUNT];
        boolean[] neighbour = new boolean[Zone.COLOR_COUNT];
        for (Zone other : earlier) {
            if (!other.sameWorld(zone) || other.color == null) continue;
            uses[other.color]++;
            if (other.touches(zone)) neighbour[other.color] = true;
        }
        int best = -1;
        for (int c = 0; c < Zone.COLOR_COUNT; c++) {
            if (neighbour[c]) continue;
            if (best < 0 || uses[c] < uses[best]) best = c;
        }
        if (best >= 0) return best;
        // Every color touches this zone already: take the least used one.
        int least = 0;
        for (int c = 1; c < Zone.COLOR_COUNT; c++) if (uses[c] < uses[least]) least = c;
        return least;
    }
}
