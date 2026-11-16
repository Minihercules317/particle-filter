package particlefilter.filter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import particlefilter.config.FilterMode;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;

import particlefilter.filter.ParticlePattern.TagCheck;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable snapshot of {@link PfConfig}, compiled for fast checks while particles spawn. Some mods spawn particles
 * off the client thread, so checks only read the current snapshot, which is replaced atomically on rebuild.
 *
 * Every rule is a {@link ParticlePattern}: a particle, a mod's particles or any particle, optionally only when made
 * from a block, a mod's blocks or a tag's blocks. Exact entries are looked up in a set; the rest are checked in turn.
 */
public final class FilterRules {

    /** Same name as {@code ParticleFilter.LOGGER}; kept here so compiling runs without the game (unit tests). */
    private static final Logger LOGGER = LoggerFactory.getLogger("Particle Filter");

    /** One list of entries, compiled: exact keys, and the patterns with wildcards or tags. */
    record Rules(Set<String> exact, List<ParticlePattern> patterns) {
        static final Rules EMPTY = new Rules(Set.of(), List.of());

        static Rules of(Collection<String> entries) {
            Set<String> exact = new HashSet<>();
            List<ParticlePattern> patterns = new ArrayList<>();
            for (String entry : entries) {
                ParticlePattern pattern = ParticlePattern.parse(entry);
                if (pattern == null) {
                    // Kept as it is, as before patterns existed: it matches a particle with exactly that name, if any.
                    LOGGER.warn("Not a valid particle entry, matching it as written: {}", entry);
                    exact.add(entry);
                } else if (pattern.isExact()) {
                    exact.add(pattern.toString());
                } else {
                    patterns.add(pattern);
                }
            }
            return new Rules(Set.copyOf(exact), List.copyOf(patterns));
        }

        boolean isEmpty() {
            return exact.isEmpty() && patterns.isEmpty();
        }

        /** [key] is [base] plus "@" and [block] when made from a block; [block] is null otherwise. */
        boolean hides(String base, String key, String block, TagCheck tags) {
            if (exact.contains(key) || block != null && exact.contains(base)) return true;
            for (ParticlePattern pattern : patterns) {
                if (pattern.matches(base, block, tags)) return true;
            }
            return false;
        }

        /** True if every particle made from [block] matches, whatever the particle. */
        boolean hidesEverythingFrom(String block, TagCheck tags) {
            for (ParticlePattern pattern : patterns) {
                if (pattern.anyParticle() && pattern.matches(ParticlePattern.ANY, block, tags)) return true;
            }
            return false;
        }
    }

    record ZoneBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Rules rules) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }
    }

    record Snapshot(Rules everywhere, List<ZoneBox> zones, TagCheck tags) {
        static final Snapshot EMPTY = new Snapshot(Rules.EMPTY, List.of(), TagCheck.NONE);

        boolean hasParticleRules() {
            return !everywhere.isEmpty() || !zones.isEmpty();
        }

        boolean hidesParticle(String key, double x, double y, double z) {
            return hidesParticle(ParticleKey.base(key), key, x, y, z);
        }

        /**
         * [key] is [base] plus the block it was made from, if any ({@link ParticleKey}); a rule for either hides it.
         * Without a block, [key] is [base] itself.
         */
        boolean hidesParticle(String base, String key, double x, double y, double z) {
            String block = base.length() == key.length() ? null : key.substring(base.length() + 1);
            if (everywhere.hides(base, key, block, tags)) return true;
            for (ZoneBox zone : zones) {
                if (zone.contains(x, y, z) && zone.rules.hides(base, key, block, tags)) return true;
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
        snapshot = compile(mode, PfConfig.hiddenParticles(), mode.zones ? PfConfig.zonesHere() : List.of(), new GameTags());
    }

    /**
     * The rules [mode] keeps, from the switched-on entries: particles hidden everywhere and the zones in the current
     * world and dimension. [tags] answers "#tag" entries.
     */
    static Snapshot compile(FilterMode mode, List<String> everywhere, List<Zone> zonesHere, TagCheck tags) {
        List<ZoneBox> zones = new ArrayList<>();
        if (mode.zones) {
            for (Zone zone : zonesHere) {
                if (zone.particles.isEmpty()) continue;
                zones.add(new ZoneBox(zone.minX(), zone.minY(), zone.minZ(),
                    zone.minX() + zone.width, zone.minY() + zone.height, zone.minZ() + zone.length, Rules.of(zone.particles)));
            }
        }
        return new Snapshot(mode.global ? Rules.of(everywhere) : Rules.EMPTY, List.copyOf(zones), tags);
    }

    /** Block tags from the game's registry, remembered per block and tag until the rules are compiled again. */
    private static final class GameTags implements TagCheck {
        private final Map<String, Boolean> known = new ConcurrentHashMap<>();

        @Override
        public boolean inTag(String blockId, String tagId) {
            return known.computeIfAbsent(blockId + "#" + tagId, k -> {
                ResourceLocation block = ResourceLocation.tryParse(blockId);
                ResourceLocation tag = ResourceLocation.tryParse(tagId);
                if (block == null || tag == null || !BuiltInRegistries.BLOCK.containsKey(block)) return false;
                return BuiltInRegistries.BLOCK.get(block).builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, tag));
            });
        }
    }

    /**
     * True if the rule [entry] covers the particle [key], as the compiled rules would (tags answered by the game). An
     * entry that isn't a valid pattern only covers a particle with exactly that name.
     */
    public static boolean entryMatches(String entry, String key) {
        return ParticlePattern.entryMatches(entry, key, snapshot.tags);
    }

    /** Block tags as the compiled rules see them: from the game once compiled in a world. */
    public static TagCheck tags() {
        return snapshot.tags;
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

    /**
     * True if every particle made from [blockId] is hidden everywhere (e.g. by "*@minecraft:stone" or "*@#c:ores"), so
     * breaking and mining it can skip making particles at all.
     */
    public static boolean hidesEverythingFrom(String blockId) {
        Snapshot s = snapshot;
        return s.everywhere.hidesEverythingFrom(blockId, s.tags);
    }
}
