package particlefilter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.loading.FMLPaths;
import particlefilter.ParticleFilter;
import particlefilter.filter.FilterRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persistent settings in config/particlefilter.json. Only used on the client thread; particle spawning reads the
 * compiled {@link FilterRules} snapshot instead.
 *
 * Particle entries are particle type ids ("ae2:energy_fx") or, for particles a mod creates without a type, the
 * particle's class name. Block entries are block ids, "namespace:*", or "#tag".
 */
public final class PfConfig {

    /** Default size of zones created from a scan; the scanned block sits 2 blocks above the zone's bottom. */
    public static final int NEW_ZONE_WIDTH = 9;
    public static final int NEW_ZONE_HEIGHT = 5;
    public static final int NEW_ZONE_LENGTH = 9;
    public static final int NEW_ZONE_DEPTH_BELOW = 2;

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
                    loaded.zones.forEach(Zone::normalize);
                    data = loaded;
                }
            } catch (Exception e) {
                ParticleFilter.LOGGER.error("Couldn't read {}; starting with an empty config", FILE, e);
            }
        }
        save(); // writes defaults on first run and upgrades older formats
        FilterRules.rebuild();
    }

    private static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(data));
        } catch (IOException e) {
            ParticleFilter.LOGGER.error("Couldn't save {}", FILE, e);
        }
    }

    /** Saves and recompiles the filter rules. Call after any change, including direct edits to a {@link Zone}. */
    public static void changed() {
        save();
        FilterRules.rebuild();
    }

    // ---- particles hidden everywhere ----

    public static List<String> hiddenParticles() {
        return List.copyOf(data.hiddenParticles);
    }

    public static boolean isHiddenEverywhere(String particle) {
        return data.hiddenParticles.contains(particle);
    }

    public static void setHiddenEverywhere(String particle, boolean hidden) {
        data.hiddenParticles.remove(particle);
        if (hidden) data.hiddenParticles.add(particle);
        changed();
    }

    // ---- block breaking/mining/dust particles ----

    public static List<String> hiddenBlocks() {
        return List.copyOf(data.hiddenBlocks);
    }

    public static boolean hasBlockEntry(String entry) {
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

    public static boolean isInCurrentWorld(Zone zone) {
        return zone.world.equals(FilterRules.currentWorld()) && zone.dimension.equals(FilterRules.currentDimension());
    }

    /** Zones in the world and dimension the player is in now. */
    public static List<Zone> zonesHere() {
        if (FilterRules.currentWorld() == null) return List.of();
        return data.zones.stream().filter(PfConfig::isInCurrentWorld).toList();
    }

    /** Smallest zone here containing [pos]: where "hide in zone" adds to. */
    public static Zone zoneAt(BlockPos pos) {
        return zonesHere().stream().filter(z -> z.contains(pos))
            .min(Comparator.comparingLong(z -> (long) z.width * z.height * z.length)).orElse(null);
    }

    public static boolean isHiddenInZoneAt(BlockPos pos, String particle) {
        return zonesHere().stream().anyMatch(z -> z.contains(pos) && z.particles.contains(particle));
    }

    /** A default-sized zone around [pos] in the current world, without adding it. Null when not in a world. */
    public static Zone defaultZoneAround(BlockPos pos) {
        if (FilterRules.currentWorld() == null) return null;
        Zone zone = new Zone();
        zone.world = FilterRules.currentWorld();
        zone.dimension = FilterRules.currentDimension();
        zone.x = pos.getX();
        zone.y = pos.getY() - NEW_ZONE_DEPTH_BELOW;
        zone.z = pos.getZ();
        zone.width = NEW_ZONE_WIDTH;
        zone.height = NEW_ZONE_HEIGHT;
        zone.length = NEW_ZONE_LENGTH;
        zone.name = nextZoneName();
        return zone;
    }

    /** Creates and adds a default-sized zone around [pos]. Null when not in a world. */
    public static Zone createZone(BlockPos pos) {
        Zone zone = defaultZoneAround(pos);
        if (zone == null) return null;
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

    public static void setHiddenInZone(Zone zone, String particle, boolean hidden) {
        zone.particles.remove(particle);
        if (hidden) zone.particles.add(particle);
        changed();
    }

    /**
     * Hides or shows [particle] around [pos]. Hiding adds it to the smallest zone containing [pos], creating a
     * default zone there if none does. Showing removes it from every zone here that contains [pos].
     */
    public static void setHiddenInZoneAt(BlockPos pos, String particle, boolean hidden) {
        if (!hidden) {
            for (Zone zone : zonesHere()) {
                if (zone.contains(pos)) zone.particles.remove(particle);
            }
            changed();
            return;
        }
        Zone zone = zoneAt(pos);
        if (zone == null) zone = createZone(pos);
        if (zone != null) setHiddenInZone(zone, particle, true);
    }
}
