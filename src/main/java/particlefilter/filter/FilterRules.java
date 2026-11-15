package particlefilter.filter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import particlefilter.config.FilterMode;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of {@link PfConfig}, compiled for fast checks while particles spawn. Some mods spawn particles
 * off the client thread, so checks only read the current snapshot, which is replaced atomically on rebuild.
 */
public final class FilterRules {

    /** Same name as {@code ParticleFilter.LOGGER}; kept here so compiling runs without the game (unit tests). */
    private static final Logger LOGGER = LoggerFactory.getLogger("Particle Filter");

    record ZoneBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Set<String> particles) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }
    }

    record Snapshot(Set<String> particles, List<ZoneBox> zones,
                    Set<ResourceLocation> blockIds, Set<String> blockNamespaces, List<TagKey<Block>> blockTags) {
        static final Snapshot EMPTY = new Snapshot(Set.of(), List.of(), Set.of(), Set.of(), List.of());

        boolean hasParticleRules() {
            return !particles.isEmpty() || !zones.isEmpty();
        }

        boolean hasBlockRules() {
            return !blockIds.isEmpty() || !blockNamespaces.isEmpty() || !blockTags.isEmpty();
        }

        boolean hidesParticle(String particle, double x, double y, double z) {
            return hidesParticle(ParticleKey.base(particle), particle, x, y, z);
        }

        /**
         * [key] is [base] plus the block it was made from, if any ({@link ParticleKey}); a rule for either hides it.
         * Without a block, [key] is [base] itself.
         */
        boolean hidesParticle(String base, String key, double x, double y, double z) {
            boolean withBlock = !base.equals(key);
            if (particles.contains(key) || withBlock && particles.contains(base)) return true;
            for (ZoneBox zone : zones) {
                if (zone.contains(x, y, z) && (zone.particles.contains(key) || withBlock && zone.particles.contains(base))) return true;
            }
            return false;
        }
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    /** True while the Hold to Show All Particles key is held: every rule is paused without changing the saved mode. */
    private static boolean showAll;
    private static String world;
    private static String dimension;

    private FilterRules() {}

    /** Server address, or the save name in singleplayer. Null when not in a world. */
    public static String currentWorld() {
        return world;
    }

    public static String currentDimension() {
        return dimension;
    }

    /** Called every client tick: recompiles when the player changes world or dimension. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        String newWorld = null;
        String newDimension = null;
        if (mc.level != null) {
            ServerData server = mc.getCurrentServer();
            if (server != null) newWorld = server.ip;
            else if (mc.getSingleplayerServer() != null) newWorld = mc.getSingleplayerServer().getWorldData().getLevelName();
            else newWorld = "unknown";
            newDimension = mc.level.dimension().location().toString();
        }
        if (!Objects.equals(newWorld, world) || !Objects.equals(newDimension, dimension)) {
            world = newWorld;
            dimension = newDimension;
            rebuild();
        }
    }

    public static boolean isShowingAll() {
        return showAll;
    }

    /** Pauses or resumes every rule; recompiles only when the state changes. */
    public static void setShowAll(boolean show) {
        if (show == showAll) return;
        showAll = show;
        rebuild();
    }

    /** Recompiles the rules, leaving out the kinds that the current {@link FilterMode} switches off. */
    public static void rebuild() {
        FilterMode mode = showAll ? FilterMode.NONE : PfConfig.mode();
        snapshot = compile(mode, PfConfig.hiddenParticles(), PfConfig.hiddenBlocks(), mode.zones ? PfConfig.zonesHere() : List.of());
    }

    /**
     * The rules [mode] keeps, from the switched-on entries: particles hidden everywhere, block entries, and the zones
     * in the current world and dimension.
     */
    static Snapshot compile(FilterMode mode, List<String> hiddenParticles, List<String> hiddenBlocks, List<Zone> zonesHere) {
        List<ZoneBox> zones = new ArrayList<>();
        if (mode.zones) {
            for (Zone zone : zonesHere) {
                if (zone.particles.isEmpty()) continue;
                zones.add(new ZoneBox(zone.minX(), zone.minY(), zone.minZ(),
                    zone.minX() + zone.width, zone.minY() + zone.height, zone.minZ() + zone.length, Set.copyOf(zone.particles)));
            }
        }
        Set<ResourceLocation> ids = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String raw : mode.global ? hiddenBlocks : List.<String>of()) {
            String entry = raw.trim();
            if (entry.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
                if (tag != null) tags.add(TagKey.create(Registries.BLOCK, tag));
                else LOGGER.warn("Ignoring invalid block tag: {}", entry);
            } else if (entry.endsWith(":*")) {
                namespaces.add(entry.substring(0, entry.length() - 2));
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id != null) ids.add(id);
                else LOGGER.warn("Ignoring invalid block id: {}", entry);
            }
        }
        Set<String> particles = mode.global ? Set.copyOf(hiddenParticles) : Set.of();
        return new Snapshot(particles, List.copyOf(zones), Set.copyOf(ids), Set.copyOf(namespaces), List.copyOf(tags));
    }

    public static boolean hasParticleRules() {
        return snapshot.hasParticleRules();
    }

    /**
     * True if [key] is hidden everywhere, or in a zone containing the point. [key] is the type id or class name
     * [base], plus the block the particle was made from if any ({@link ParticleKey}); rules for [base] cover every block.
     */
    public static boolean isParticleHidden(String base, String key, double x, double y, double z) {
        return snapshot.hidesParticle(base, key, x, y, z);
    }

    /** True if this block's breaking, mining and block-dust particles are hidden. */
    public static boolean isBlockHidden(BlockState state) {
        Snapshot s = snapshot;
        if (!s.hasBlockRules()) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (s.blockIds.contains(id) || s.blockNamespaces.contains(id.getNamespace())) return true;
        for (TagKey<Block> tag : s.blockTags) {
            if (state.is(tag)) return true;
        }
        return false;
    }
}
