package particlefilter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of {@link PfConfig}, compiled for fast checks during particle spawning. Particles can be
 * spawned off the client thread (e.g. by mods that tick particles asynchronously), so checks only ever read
 * the current snapshot, which is swapped atomically on rebuild.
 */
public final class PfRules {

    private record ZoneBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Set<String> particles) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }
    }

    private record Snapshot(Set<String> particles, List<ZoneBox> zones,
                            Set<ResourceLocation> blockIds, Set<String> blockNamespaces, List<TagKey<Block>> blockTags) {
        boolean hasParticleRules() {
            return !particles.isEmpty() || !zones.isEmpty();
        }

        boolean hasBlockRules() {
            return !blockIds.isEmpty() || !blockNamespaces.isEmpty() || !blockTags.isEmpty();
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(Set.of(), List.of(), Set.of(), Set.of(), List.of());
    private static String world;
    private static String dimension;

    private PfRules() {}

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
        if (!java.util.Objects.equals(newWorld, world) || !java.util.Objects.equals(newDimension, dimension)) {
            world = newWorld;
            dimension = newDimension;
            rebuild();
        }
    }

    public static void rebuild() {
        List<ZoneBox> zones = new ArrayList<>();
        for (PfConfig.Zone zone : PfConfig.zonesHere()) {
            if (zone.particles.isEmpty()) continue;
            int r = zone.radius;
            zones.add(new ZoneBox(zone.x - r, zone.y - r, zone.z - r, zone.x + r + 1, zone.y + r + 1, zone.z + r + 1, Set.copyOf(zone.particles)));
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

    /** True if [entry] (a particle type id or particle class name) is hidden everywhere, or in a zone containing the point. */
    public static boolean isParticleHidden(String entry, double x, double y, double z) {
        Snapshot s = snapshot;
        if (s.particles.contains(entry)) return true;
        for (ZoneBox zone : s.zones) {
            if (zone.contains(x, y, z) && zone.particles.contains(entry)) return true;
        }
        return false;
    }

    /** True if breaking, mining and block-dust particles of this block are hidden. */
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
