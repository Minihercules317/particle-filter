package particlefilter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import particlefilter.filter.FilterRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** How far apart spots where a particle appeared may be and still count as one source, in blocks. */
    public static final int MIN_SOURCE_DISTANCE = 1;
    public static final int MAX_SOURCE_DISTANCE = 32;
    public static final int DEFAULT_SOURCE_DISTANCE = 6;

    /** How much of the smaller of two Diagnostic zones must overlap the other for them to merge, in percent. */
    public static final int MIN_MERGE_PERCENT = 1;
    public static final int MAX_MERGE_PERCENT = 100;
    public static final int DEFAULT_MERGE_PERCENT = 50;

    /** The saved settings, as stored in the file. */
    static final class Data {
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
        Integer sourceDistance = DEFAULT_SOURCE_DISTANCE;
        Integer mergePercent = DEFAULT_MERGE_PERCENT;

        /** Fills defaults for fields missing from older or hand-edited files and puts values back in range. */
        void normalize() {
            if (hiddenParticles == null) hiddenParticles = new ArrayList<>();
            if (hiddenBlocks == null) hiddenBlocks = new ArrayList<>();
            if (zones == null) zones = new ArrayList<>();
            if (mode == null) mode = FilterMode.ALL; // missing or unknown value
            if (disabledParticles == null) disabledParticles = new ArrayList<>();
            if (disabledBlocks == null) disabledBlocks = new ArrayList<>();
            disabledParticles.removeAll(hiddenParticles);
            disabledBlocks.removeAll(hiddenBlocks);
            if (scanSeconds == null) scanSeconds = DEFAULT_SCAN_SECONDS;
            scanSeconds = clampScanSeconds(scanSeconds);
            if (diagnosticSeconds == null) diagnosticSeconds = DEFAULT_SCAN_SECONDS;
            diagnosticSeconds = clampScanSeconds(diagnosticSeconds);
            if (sourceDistance == null) sourceDistance = DEFAULT_SOURCE_DISTANCE;
            sourceDistance = clampSourceDistance(sourceDistance);
            if (mergePercent == null) mergePercent = DEFAULT_MERGE_PERCENT;
            mergePercent = clampMergePercent(mergePercent);
            zones.forEach(Zone::normalize);
        }
    }

    /** Same name as {@code ParticleFilter.LOGGER}; kept here so the config code runs without the game (unit tests). */
    private static final Logger LOGGER = LoggerFactory.getLogger("Particle Filter");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;

    private static Data data = new Data();

    private PfConfig() {}

    public static void load(Path path) {
        file = path;
        data = read(file);
        assignColors(data.zones);
        save(); // writes defaults on first run and upgrades older formats
        FilterRules.rebuild();
    }

    /**
     * The settings in [file], or the defaults if it is missing or empty. Saving replaces the file, so one that can't
     * be read (a typo from editing it by hand, say) is first copied next to it as particlefilter.broken-<time>.json.
     */
    static Data read(Path file) {
        if (!Files.exists(file)) return new Data();
        try {
            Data loaded = parse(Files.readString(file));
            return loaded != null ? loaded : new Data();
        } catch (Exception e) {
            try {
                Path copy = brokenCopyPath(file);
                Files.copy(file, copy);
                LOGGER.error("Couldn't read {}; saved it as {} and started with an empty config", file, copy.getFileName(), e);
            } catch (IOException copyError) {
                LOGGER.error("Couldn't read {} or save a copy of it; starting with an empty config", file, e);
            }
            return new Data();
        }
    }

    /** A free name next to [file]: particlefilter.broken-20261110-123100.json, then -2, -3... in the same second. */
    private static Path brokenCopyPath(Path file) {
        String name = file.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        Path copy = file.resolveSibling(base + ".broken-" + stamp + ".json");
        for (int n = 2; Files.exists(copy); n++) copy = file.resolveSibling(base + ".broken-" + stamp + "-" + n + ".json");
        return copy;
    }

    /** The settings in [json], with defaults filled in and values put in range; null for an empty file. */
    static Data parse(String json) {
        Data parsed = GSON.fromJson(json, Data.class);
        if (parsed != null) parsed.normalize();
        return parsed;
    }

    static String toJson(Data data) {
        return GSON.toJson(data);
    }

    private static void save() {
        try {
            Files.writeString(file, toJson(data));
        } catch (IOException e) {
            LOGGER.error("Couldn't save {}", file, e);
        }
    }

    /** Saves and recompiles the filter rules. Call after any change, including direct edits to a {@link Zone}. */
    public static void changed() {
        assignColors(data.zones);
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

    /** Spots where a particle appeared at most this many blocks apart are one source (Diagnostic page). */
    public static int sourceDistance() {
        return data.sourceDistance;
    }

    public static void setSourceDistance(int blocks) {
        data.sourceDistance = clampSourceDistance(blocks);
        save();
    }

    private static int clampSourceDistance(int blocks) {
        return Math.max(MIN_SOURCE_DISTANCE, Math.min(MAX_SOURCE_DISTANCE, blocks));
    }

    /** Diagnostic zones merge when at least this percent of the smaller one overlaps the other. */
    public static int mergePercent() {
        return data.mergePercent;
    }

    public static void setMergePercent(int percent) {
        data.mergePercent = clampMergePercent(percent);
        save();
    }

    private static int clampMergePercent(int percent) {
        return Math.max(MIN_MERGE_PERCENT, Math.min(MAX_MERGE_PERCENT, percent));
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
    static void assignColors(List<Zone> zones) {
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
