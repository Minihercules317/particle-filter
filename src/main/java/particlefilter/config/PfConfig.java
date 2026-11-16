package particlefilter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import particlefilter.filter.FilterRules;
import particlefilter.filter.ParticlePattern;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Persistent settings in config/particlefilter.json. Only used on the client thread; particle spawning reads the
 * compiled {@link FilterRules} snapshot instead.
 *
 * Particle entries are {@link particlefilter.filter.ParticlePattern}s: particle type ids ("ae2:energy_fx") or, for
 * particles a mod creates without a type, class names, optionally with the block they were made from after "@", and
 * "mod:*", "*@block", "*@mod:*" or "*@#tag" for groups. Block entries from before 1.3.0 ("geore:*" under "Hide block")
 * are read once and turned into "*@" entries ({@link #blockEntry}).
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

    /** How far from its center a scan on the Scan page records, in blocks. Minecraft rarely creates particles past 32. */
    public static final int MIN_SCAN_RADIUS = 1;
    public static final int MAX_SCAN_RADIUS = 32;
    public static final int DEFAULT_SCAN_RADIUS = 6;

    /** Most zones Here makes for one particle, one per source, for its busiest sources. */
    public static final int MIN_MAX_ZONES = 1;
    public static final int MAX_MAX_ZONES = 32;
    public static final int DEFAULT_MAX_ZONES = 8;

    /**
     * How much of the smaller of two zones fitted by a scan must overlap the other for them to merge, in percent. At 0,
     * zones touching face to face merge too.
     */
    public static final int MIN_MERGE_PERCENT = 0;
    public static final int MAX_MERGE_PERCENT = 100;
    public static final int DEFAULT_MERGE_PERCENT = 50;

    /** The saved settings, as stored in the file. */
    static final class Data {
        List<String> hiddenParticles = new ArrayList<>();
        /** Before 1.3.0: blocks whose particles were hidden. Read once, moved to [hiddenParticles], never written again. */
        List<String> hiddenBlocks;
        List<Zone> zones = new ArrayList<>();
        /** Added in 1.1.0; older files lack it and older versions ignore it. */
        FilterMode mode = FilterMode.ALL;
        /** Added after 1.2.0, like the fields below: entries switched off in the menu. */
        List<String> disabledParticles = new ArrayList<>();
        /** Like [hiddenBlocks], for those switched off; moved to [disabledParticles]. */
        List<String> disabledBlocks;
        Integer scanSeconds = DEFAULT_SCAN_SECONDS;
        Integer sourceDistance = DEFAULT_SOURCE_DISTANCE;
        Integer mergePercent = DEFAULT_MERGE_PERCENT;
        Integer scanRadius = DEFAULT_SCAN_RADIUS;
        Integer maxZones = DEFAULT_MAX_ZONES;
        /** Whether the Scan page's Zone options are expanded. */
        Boolean zoneOptionsOpen = false;
        /** Settings from the mod list's Config screen. */
        Boolean walkInMenu = false;
        Boolean zoneTracers = false;
        /** Where the menu window was moved and resized to, in GUI pixels; null until it is. */
        Window window;

        /** Fills defaults for fields missing from older or hand-edited files and puts values back in range. */
        void normalize() {
            if (hiddenParticles == null) hiddenParticles = new ArrayList<>();
            if (zones == null) zones = new ArrayList<>();
            if (mode == null) mode = FilterMode.ALL; // missing or unknown value
            if (disabledParticles == null) disabledParticles = new ArrayList<>();
            moveBlockEntries(hiddenBlocks, hiddenParticles);
            moveBlockEntries(disabledBlocks, disabledParticles);
            hiddenBlocks = disabledBlocks = null;
            disabledParticles.removeAll(hiddenParticles);
            if (scanSeconds == null) scanSeconds = DEFAULT_SCAN_SECONDS;
            scanSeconds = clampScanSeconds(scanSeconds);
            if (sourceDistance == null) sourceDistance = DEFAULT_SOURCE_DISTANCE;
            sourceDistance = clampSourceDistance(sourceDistance);
            if (mergePercent == null) mergePercent = DEFAULT_MERGE_PERCENT;
            mergePercent = clampMergePercent(mergePercent);
            if (scanRadius == null) scanRadius = DEFAULT_SCAN_RADIUS;
            scanRadius = clampScanRadius(scanRadius);
            if (maxZones == null) maxZones = DEFAULT_MAX_ZONES;
            maxZones = clampMaxZones(maxZones);
            if (zoneOptionsOpen == null) zoneOptionsOpen = false;
            if (walkInMenu == null) walkInMenu = false;
            if (zoneTracers == null) zoneTracers = false;
            if (window != null && !window.valid()) window = null;
            zones.forEach(Zone::normalize);
        }

        /** Adds each block entry from before 1.3.0 to [particles] as the entry hiding everything made from it. */
        private static void moveBlockEntries(List<String> blocks, List<String> particles) {
            if (blocks == null) return;
            for (String block : blocks) {
                if (block == null || block.isBlank()) continue;
                String entry = blockEntry(block);
                if (!particles.contains(entry)) particles.add(entry);
            }
        }
    }

    /**
     * The entry hiding every particle made from [block]: a block id, "mod:*" or "#tag" ("*@minecraft:stone"). Blocks
     * that aren't valid are kept as written after "*@", so they stay listed and can be fixed or deleted.
     */
    public static String blockEntry(String block) {
        String entry = "*@" + block.trim();
        ParticlePattern pattern = ParticlePattern.parse(entry);
        return pattern == null ? entry : pattern.toString();
    }

    /**
     * The menu window's position and size in GUI pixels, as the player left it. Screens fit it to the screen they
     * open on, since the GUI scale or game window may have changed since.
     */
    public record Window(int x, int y, int width, int height) {
        boolean valid() {
            return width > 0 && height > 0;
        }
    }

    /** Same name as {@code ParticleFilter.LOGGER}; kept here so the config code runs without the game (unit tests). */
    private static final Logger LOGGER = LoggerFactory.getLogger("Particle Filter");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;

    private static Data data = new Data();
    /**
     * Set when the file existed but couldn't be read and couldn't be backed up either. The in-memory defaults are
     * used, but nothing is written until the next {@link #load}, so the unreadable file is never overwritten.
     */
    private static boolean saveBlocked;
    /** While above 0, saves wait: see {@link #batch} and {@link #unsaved}. */
    private static int saveHolds;
    /** A save was asked for while saves were held. */
    private static boolean savePending;
    /** Goes up with every change, so screens can tell when what they worked out from the settings is out of date. */
    private static int revision;

    private PfConfig() {}

    /** Increases whenever a setting, rule or zone changes. */
    public static int revision() {
        return revision;
    }

    /** Runs [changes], writing the file once at the end instead of after each change. */
    public static void batch(Runnable changes) {
        saveHolds++;
        try {
            changes.run();
        } finally {
            saveHolds--;
            if (saveHolds == 0 && savePending) {
                savePending = false;
                save();
            }
        }
    }

    /**
     * Runs [changes] without writing them to the file, for live previews such as a slider being dragged: they apply
     * in the game at once, and the next save (when the drag ends) writes them along with everything else.
     */
    public static void unsaved(Runnable changes) {
        saveHolds++;
        try {
            changes.run();
        } finally {
            saveHolds--;
        }
    }

    public static void load(Path path) {
        file = path;
        Loaded loaded = read(file);
        data = loaded.data();
        saveBlocked = !loaded.safeToSave();
        assignColors(data.zones);
        save(); // writes defaults on first run and upgrades older formats
        FilterRules.rebuild();
    }

    /** What [read] found: the settings, and whether the file may be overwritten by later saves. */
    record Loaded(Data data, boolean safeToSave) {}

    /**
     * The settings in [file], or the defaults if it is missing or empty. Saving replaces the file, so one that can't
     * be read (a typo from editing it by hand, say) is first copied next to it as particlefilter.broken-<time>.json.
     * If that copy fails too, the defaults are used but the result is marked unsafe to save.
     * Only a file known not to exist counts as missing; if its existence can't be determined it is treated as present.
     */
    static Loaded read(Path file) {
        if (Files.notExists(file)) return new Loaded(new Data(), true);
        try {
            Data loaded = parse(Files.readString(file));
            return new Loaded(loaded != null ? loaded : new Data(), true);
        } catch (Exception e) {
            try {
                Path copy = brokenCopyPath(file);
                Files.copy(file, copy);
                LOGGER.error("Couldn't read {}; saved it as {} and started with an empty config", file, copy.getFileName(), e);
                return new Loaded(new Data(), true);
            } catch (Exception copyError) {
                LOGGER.error("Couldn't read {} or save a copy of it; starting with an empty config and not saving "
                        + "until the config is loaded again", file, e);
                return new Loaded(new Data(), false);
            }
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

    /**
     * The file a save really replaces. If the config path is a symlink (or a chain of them) that is the final target,
     * so the links stay in place, as they would with a direct write.
     *
     * A dangling link is refused rather than followed: creating whatever it points at could put a file somewhere
     * the user never meant, so such a config is simply not saved. A file that isn't writable is refused too, since a
     * direct write would have failed and replacing it through a writable folder would silently get around that.
     */
    private static Path saveTarget() throws IOException {
        Path path = file.toAbsolutePath();
        Path target;
        try {
            target = path.toRealPath();
        } catch (NoSuchFileException e) {
            if (Files.isSymbolicLink(path)) throw new IOException(path + " is a symbolic link to a missing file", e);
            return path; // first run: the file doesn't exist yet
        }
        if (!Files.isWritable(target)) throw new IOException(target + " is not writable");
        return target;
    }

    private static void save() {
        revision++;
        if (saveHolds > 0) {
            savePending = true;
            return;
        }
        savePending = false;
        if (saveBlocked) {
            LOGGER.error("Not saving {}: it couldn't be read or backed up when it was loaded", file);
            return;
        }
        Path temp = null; // only set once this save has created its own file
        try {
            Path target = saveTarget();
            // A bare filename has no parent, so saveTarget() works on an absolute path.
            temp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(toJson(data).getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            // The temp file starts out private (0600, owned by us); give it the old file's access rules. Any failure
            // here keeps the previous file, like a failed write.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) copyAccessMetadata(target, temp);
            // No fallback to a non-atomic replace: if the filesystem can't move atomically, the previous file is kept.
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Couldn't save {}", file, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // leave it; its name is unique, so it never affects later saves
                }
            }
        }
    }

    /**
     * Copies who may access [from] onto [to], using the standard NIO attribute views the platform offers: the ACL
     * (if exposed), and on POSIX the group, the owner and the permission bits. Views the platform doesn't offer are
     * skipped. Throws if a supported attribute can't be read or applied, so the caller keeps the previous file.
     *
     * Limits: Java has no portable view for POSIX default/extended ACLs, SELinux labels, other extended attributes,
     * Windows owner/DACL inheritance details beyond what {@link AclFileAttributeView} reports, or macOS/NTFS
     * metadata. Changing the owner normally needs privileges, so a file owned by someone else can't be replaced by an
     * unprivileged user; the save then fails and the old file stays. The owner and group are only set when they differ.
     */
    private static void copyAccessMetadata(Path from, Path to) throws IOException {
        // A view being available isn't enough (the Windows JDK offers the ACL view on volumes without persistent
        // ACLs, e.g. FAT), so the volumes themselves are asked too.
        AclFileAttributeView fromAcl = supportedView(from, AclFileAttributeView.class);
        AclFileAttributeView toAcl = supportedView(to, AclFileAttributeView.class);
        if (fromAcl != null && toAcl != null) toAcl.setAcl(applicableAcl(from, fromAcl.getAcl()));

        PosixFileAttributeView fromPosix = supportedView(from, PosixFileAttributeView.class);
        PosixFileAttributeView toPosix = supportedView(to, PosixFileAttributeView.class);
        if (fromPosix != null && toPosix != null) {
            PosixFileAttributes wanted = fromPosix.readAttributes();
            PosixFileAttributes current = toPosix.readAttributes();
            if (!wanted.group().equals(current.group())) toPosix.setGroup(wanted.group());
            if (!wanted.owner().equals(current.owner())) toPosix.setOwner(wanted.owner());
            // Last, because changing the owner or group can clear setuid/setgid bits.
            toPosix.setPermissions(wanted.permissions());
        }
    }

    /** The view of [path], or null if the file's volume doesn't really support it (or the JDK has no such view). */
    private static <V extends FileAttributeView> V supportedView(Path path, Class<V> type) throws IOException {
        if (!Files.getFileStore(path).supportsFileAttributeView(type)) return null;
        return Files.getFileAttributeView(path, type);
    }

    /**
     * The ACL to apply, or an exception if it can't be applied faithfully. The JDK reads both a missing (null) DACL,
     * which allows everyone, and an empty DACL, which allows no one, as an empty list, so it can't be told which one
     * it is. Applying either could silently widen or lock out access, and substituting the folder's defaults could
     * weaken a restrictive ACL, so the save is refused and the previous file kept.
     */
    static List<AclEntry> applicableAcl(Path from, List<AclEntry> acl) throws IOException {
        if (acl.isEmpty()) {
            throw new IOException("the ACL of " + from + " is empty, which the JDK can't tell apart from no ACL at all,"
                    + " so it can't be copied safely");
        }
        return acl;
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

    private static int clampScanSeconds(int seconds) {
        return Math.max(MIN_SCAN_SECONDS, Math.min(MAX_SCAN_SECONDS, seconds));
    }

    /** How far from what you look at a scan on the Scan page records, in blocks. */
    public static int scanRadius() {
        return data.scanRadius;
    }

    public static void setScanRadius(int blocks) {
        data.scanRadius = clampScanRadius(blocks);
        save();
    }

    private static int clampScanRadius(int blocks) {
        return Math.max(MIN_SCAN_RADIUS, Math.min(MAX_SCAN_RADIUS, blocks));
    }

    /** Most zones Here makes for one particle. */
    public static int maxZones() {
        return data.maxZones;
    }

    public static void setMaxZones(int zones) {
        data.maxZones = clampMaxZones(zones);
        save();
    }

    private static int clampMaxZones(int zones) {
        return Math.max(MIN_MAX_ZONES, Math.min(MAX_MAX_ZONES, zones));
    }

    /** Whether the Scan page shows its Zone options. */
    public static boolean zoneOptionsOpen() {
        return data.zoneOptionsOpen;
    }

    public static void setZoneOptionsOpen(boolean open) {
        data.zoneOptionsOpen = open;
        save();
    }

    // ---- settings from the mod list's Config screen ----

    /** Walking with the movement keys while a Particle Filter screen is open. */
    public static boolean walkInMenu() {
        return data.walkInMenu;
    }

    public static void setWalkInMenu(boolean on) {
        data.walkInMenu = on;
        save();
    }

    /** Lines from the crosshair to each zone's center, drawn with the outlines. */
    public static boolean zoneTracers() {
        return data.zoneTracers;
    }

    public static void setZoneTracers(boolean on) {
        data.zoneTracers = on;
        save();
    }

    // ---- the menu window ----

    /** Where the player last moved and resized the menu window; null for the default. */
    public static Window window() {
        return data.window;
    }

    /** Remembers the menu window's place; null goes back to the default. */
    public static void setWindow(Window window) {
        if (java.util.Objects.equals(window, data.window)) return;
        data.window = window;
        save();
    }

    /** Spots where a particle appeared at most this many blocks apart are one source (Scan page, Here). */
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

    /** Zones fitted by a scan merge when at least this percent of the smaller one overlaps the other. */
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

    /** Adds [entry] to [on]; narrower entries it covers ("geore:sparkle" under "geore:*") go, as they'd change nothing. */
    private static void hide(List<String> on, List<String> off, String entry) {
        ParticlePattern.TagCheck tags = FilterRules.tags();
        on.removeIf(e -> !e.equals(entry) && ParticlePattern.covers(entry, e, tags));
        off.removeIf(e -> ParticlePattern.covers(entry, e, tags));
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

    /** Deletes every Everywhere entry [which] accepts, switched on or off, e.g. those a folder of scan results owns. */
    public static void showEverywhere(Predicate<String> which) {
        data.hiddenParticles.removeIf(which);
        data.disabledParticles.removeIf(which);
        changed();
    }

    /** Switches an Everywhere entry on or off, keeping it listed. */
    public static void setEverywhereOn(String particle, boolean on) {
        setOn(data.hiddenParticles, data.disabledParticles, particle, on);
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

    /** Deletes every entry of [zone] that [which] accepts, switched on or off. */
    public static void showInZone(Zone zone, Predicate<String> which) {
        zone.particles.removeIf(which);
        zone.disabledParticles.removeIf(which);
        changed();
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
