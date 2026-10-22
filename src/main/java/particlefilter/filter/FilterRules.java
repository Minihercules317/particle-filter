package particlefilter.filter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import particlefilter.ParticleFilter;
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

    private record ZoneBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Set<String> particles) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }
    }

    private record Snapshot(Set<String> particles, List<ZoneBox> zones,
                            Set<ResourceLocation> blockIds, Set<String> blockNamespaces, List<TagKey<Block>> blockTags) {
        static final Snapshot EMPTY = new Snapshot(Set.of(), List.of(), Set.of(), Set.of(), List.of());

        boolean hasParticleRules() {
            return !particles.isEmpty() || !zones.isEmpty();
        }

        boolean hasBlockRules() {
            return !blockIds.isEmpty() || !blockNamespaces.isEmpty() || !blockTags.isEmpty();
        }
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;
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

    public static void rebuild() {
        List<ZoneBox> zones = new ArrayList<>();
        for (Zone zone : PfConfig.zonesHere()) {
            if (zone.particles.isEmpty()) continue;
            zones.add(new ZoneBox(zone.minX(), zone.minY(), zone.minZ(),
                zone.minX() + zone.width, zone.minY() + zone.height, zone.minZ() + zone.length, Set.copyOf(zone.particles)));
        }
        Set<ResourceLocation> ids = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String raw : PfConfig.hiddenBlocks()) {
            String entry = raw.trim();
            if (entry.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
                if (tag != null) tags.add(TagKey.create(Registries.BLOCK, tag));
                else ParticleFilter.LOGGER.warn("Ignoring invalid block tag: {}", entry);
            } else if (entry.endsWith(":*")) {
                namespaces.add(entry.substring(0, entry.length() - 2));
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id != null) ids.add(id);
                else ParticleFilter.LOGGER.warn("Ignoring invalid block id: {}", entry);
            }
        }
        snapshot = new Snapshot(Set.copyOf(PfConfig.hiddenParticles()), List.copyOf(zones), Set.copyOf(ids), Set.copyOf(namespaces), List.copyOf(tags));
    }

    public static boolean hasParticleRules() {
        return snapshot.hasParticleRules();
    }

    /** True if [particle] (type id or class name) is hidden everywhere, or in a zone containing the point. */
    public static boolean isParticleHidden(String particle, double x, double y, double z) {
        Snapshot s = snapshot;
        if (s.particles.contains(particle)) return true;
        for (ZoneBox zone : s.zones) {
            if (zone.contains(x, y, z) && zone.particles.contains(particle)) return true;
        }
        return false;
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
