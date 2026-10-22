package particlefilter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Persistent settings, stored in config/particlefilter.json. Only touched from the client thread; anything
 * that runs during particle spawning reads the compiled {@link PfRules} snapshot instead.
 *
 * Particle entries are particle type ids ("ae2:energy_fx") or, for particles a mod creates without a type,
 * the particle's class name. Block entries are block ids, "namespace:*", or "#tag" ids.
 */
public final class PfConfig {

    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 128;

    public static final class Zone {
        public String id = UUID.randomUUID().toString();
        public String name = "Zone";
        public String world = "";
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public int radius = 8;
        public List<String> particles = new ArrayList<>();

        public boolean contains(double px, double py, double pz) {
            return px >= x - radius && px < x + radius + 1
                && py >= y - radius && py < y + radius + 1
                && pz >= z - radius && pz < z + radius + 1;
        }

        public boolean contains(BlockPos pos) {
            return contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }

        public AABB box() {
            return new AABB(x - radius, y - radius, z - radius, x + radius + 1, y + radius + 1, z + radius + 1);
        }

        public BlockPos center() {
            return new BlockPos(x, y, z);
        }
    }

    private static final class Data {
        List<String> hiddenParticles = new ArrayList<>();
        List<String> hiddenBlocks = new ArrayList<>();
        List<Zone> zones = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("particlefilter.json");

    private static Data data = new Data();

    private PfConfig() {}

    public static void load() {
        if (Files.exists(FILE)) {
            try {
                Data loaded = GSON.fromJson(Files.readString(FILE), Data.class);
                if (loaded != null) {
                    if (loaded.hiddenParticles == null) loaded.hiddenParticles = new ArrayList<>();
                    if (loaded.hiddenBlocks == null) loaded.hiddenBlocks = new ArrayList<>();
                    if (loaded.zones == null) loaded.zones = new ArrayList<>();
                    for (Zone zone : loaded.zones) {
                        if (zone.particles == null) zone.particles = new ArrayList<>();
                        if (zone.id == null) zone.id = UUID.randomUUID().toString();
                        if (zone.name == null) zone.name = "Zone";
                        zone.radius = clampRadius(zone.radius);
                    }
                    data = loaded;
                }
            } catch (Exception e) {
                ParticleFilter.LOGGER.error("Couldn't read {}; starting with an empty config", FILE, e);
            }
        } else {
            save();
        }
        PfRules.rebuild();
    }

    private static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(data));
        } catch (IOException e) {
            ParticleFilter.LOGGER.error("Couldn't save {}", FILE, e);
        }
    }

    /** Saves and recompiles the rules. Call after any change, including edits to a {@link Zone}'s fields. */
    public static void changed() {
        save();
        PfRules.rebuild();
    }

    public static int clampRadius(int r) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    // ---- particles hidden everywhere ----

    public static List<String> hiddenParticles() {
        return List.copyOf(data.hiddenParticles);
    }

    public static boolean isParticleHidden(String entry) {
        return data.hiddenParticles.contains(entry);
    }

    public static void setParticleHidden(String entry, boolean hidden) {
        data.hiddenParticles.remove(entry);
        if (hidden) data.hiddenParticles.add(entry);
        changed();
    }

    // ---- block breaking/mining/dust particles ----

    public static List<String> hiddenBlocks() {
        return List.copyOf(data.hiddenBlocks);
    }

    public static boolean isBlockEntry(String entry) {
        return data.hiddenBlocks.contains(entry);
    }

    public static void setBlockHidden(String entry, boolean hidden) {
        data.hiddenBlocks.remove(entry);
        if (hidden) data.hiddenBlocks.add(entry);
        changed();
    }

    // ---- zones ----

    public static List<Zone> zones() {
        return List.copyOf(data.zones);
    }

    /** Zones in the world and dimension the player is in now. */
    public static List<Zone> zonesHere() {
        String world = PfRules.currentWorld();
        String dimension = PfRules.currentDimension();
        if (world == null) return List.of();
        return data.zones.stream().filter(z -> z.world.equals(world) && z.dimension.equals(dimension)).toList();
    }

    /** Smallest zone here containing [pos], which is where "hide here" adds to. */
    public static Zone zoneAt(BlockPos pos) {
        return zonesHere().stream().filter(z -> z.contains(pos)).min(Comparator.comparingInt(z -> z.radius)).orElse(null);
    }

    public static boolean isHiddenInZoneAt(BlockPos pos, String entry) {
        return zonesHere().stream().anyMatch(z -> z.contains(pos) && z.particles.contains(entry));
    }

    /** Creates a zone in the current world and dimension. Returns null when not in a world. */
    public static Zone createZone(BlockPos center, int radius) {
        String world = PfRules.currentWorld();
        if (world == null) return null;
        Zone zone = new Zone();
        zone.world = world;
        zone.dimension = PfRules.currentDimension();
        zone.x = center.getX();
        zone.y = center.getY();
        zone.z = center.getZ();
        zone.radius = clampRadius(radius);
        zone.name = nextZoneName();
        data.zones.add(zone);
        changed();
        return zone;
    }

    private static String nextZoneName() {
        int n = 1;
        while (true) {
            String name = "Zone " + n;
            if (data.zones.stream().noneMatch(z -> z.name.equals(name))) return name;
            n++;
        }
    }

    public static void deleteZone(Zone zone) {
        data.zones.remove(zone);
        changed();
    }

    public static void setZoneParticle(Zone zone, String entry, boolean hidden) {
        zone.particles.remove(entry);
        if (hidden) zone.particles.add(entry);
        changed();
    }

    /**
     * Hides [entry] around [pos]: adds it to the smallest zone containing [pos], or to a new zone centered there.
     * If it is already hidden there, removes it from every zone here containing [pos] instead.
     * Returns true if it is now hidden.
     */
    public static boolean toggleHiddenAt(BlockPos pos, String entry, int newZoneRadius) {
        if (isHiddenInZoneAt(pos, entry)) {
            for (Zone zone : zonesHere()) {
                if (zone.contains(pos)) zone.particles.remove(entry);
            }
            changed();
            return false;
        }
        Zone zone = zoneAt(pos);
        if (zone == null) zone = createZone(pos, newZoneRadius);
        if (zone == null) return false;
        setZoneParticle(zone, entry, true);
        return true;
    }
}
