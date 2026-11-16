package particlefilter.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import particlefilter.filter.ParticleKey;
import particlefilter.filter.ParticlePattern;
import particlefilter.loader.ModNames;

/**
 * Names of rule entries for lists: a particle's short name, or for a group a phrase such as "All GeOre particles" or
 * "Everything made from GeOre blocks", with mods and blocks by their display names.
 */
public final class RuleNames {

    private RuleNames() {}

    /** How [entry] reads in a list. Entries that aren't valid patterns are shown as written. */
    public static String of(String entry) {
        ParticlePattern pattern = ParticlePattern.parse(entry);
        if (pattern == null || pattern.isExact()) return ParticleKey.displayName(pattern == null ? entry : pattern.toString());
        if (pattern.block() == null) return Component.translatable("particlefilter.rule.mod_particles", ModNames.of(pattern.particleNamespace())).getString();
        return Component.translatable("particlefilter.rule.made_from", particlePart(pattern), blockPart(pattern)).getString();
    }

    /** True if [entry] stands for a group of particles rather than one. */
    public static boolean isGroup(String entry) {
        ParticlePattern pattern = ParticlePattern.parse(entry);
        return pattern != null && !pattern.isExact();
    }

    private static String particlePart(ParticlePattern pattern) {
        if (pattern.anyParticle()) return Component.translatable("particlefilter.rule.everything").getString();
        String namespace = pattern.particleNamespace();
        if (namespace != null) return Component.translatable("particlefilter.rule.mod_part", ModNames.of(namespace)).getString();
        return ParticleKey.displayName(pattern.particle());
    }

    private static String blockPart(ParticlePattern pattern) {
        String tag = pattern.blockTag();
        if (tag != null) return Component.translatable("particlefilter.rule.tag_part", "#" + tag).getString();
        String namespace = pattern.blockNamespace();
        if (namespace != null) return Component.translatable("particlefilter.rule.mod_blocks_part", ModNames.of(namespace)).getString();
        return blockName(pattern.block());
    }

    /** A block's display name ("Stone"), or its id if no such block is loaded. */
    public static String blockName(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return blockId;
        return BuiltInRegistries.BLOCK.get(id).getName().getString();
    }
}
