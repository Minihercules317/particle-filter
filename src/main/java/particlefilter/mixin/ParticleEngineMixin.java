package particlefilter.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import particlefilter.filter.FilterRules;
import particlefilter.scan.ParticleScan;

/**
 * All filtering happens at the particle engine, which every particle passes through however a mod spawns it:
 * - createParticle: typed particles (by particle type id), and block-dust particles (by block)
 * - add: particles a mod constructs itself without a type (by class name)
 * - destroy / crack: block breaking and mining particles (by block)
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    /** Set while a typed particle is being created, so the add() hook doesn't filter or count it a second time. */
    @Unique
    private static final ThreadLocal<Boolean> IN_TYPED_SPAWN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    protected ClientLevel level;

    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
    private void particlefilter$filterTyped(ParticleOptions options, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfoReturnable<Particle> cir) {
        if (options instanceof BlockParticleOption block && FilterRules.isBlockHidden(block.getState())) {
            cir.setReturnValue(null);
            return;
        }
        boolean rules = FilterRules.hasParticleRules();
        boolean scanning = ParticleScan.isRunning();
        if (rules || scanning) {
            ResourceLocation id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
            String particle = id == null ? options.getType().toString() : id.toString();
            if (rules && FilterRules.isParticleHidden(particle, x, y, z)) {
                cir.setReturnValue(null);
                return;
            }
            if (scanning) ParticleScan.record(particle, true, x, y, z);
        }
        IN_TYPED_SPAWN.set(Boolean.TRUE);
    }

    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("RETURN"))
    private void particlefilter$endTyped(ParticleOptions options, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfoReturnable<Particle> cir) {
        IN_TYPED_SPAWN.set(Boolean.FALSE);
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void particlefilter$filterUntyped(Particle particle, CallbackInfo ci) {
        if (IN_TYPED_SPAWN.get()) return;
        boolean rules = FilterRules.hasParticleRules();
        boolean scanning = ParticleScan.isRunning();
        if (!rules && !scanning) return;
        Vec3 pos = particle.getBoundingBox().getCenter();
        String className = particle.getClass().getName();
        if (rules && FilterRules.isParticleHidden(className, pos.x, pos.y, pos.z)) {
            ci.cancel();
            return;
        }
        if (scanning) ParticleScan.record(className, false, pos.x, pos.y, pos.z);
    }

    @Inject(method = "destroy(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"), cancellable = true)
    private void particlefilter$filterBreaking(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (FilterRules.isBlockHidden(state)) ci.cancel();
    }

    @Inject(method = "crack(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V", at = @At("HEAD"), cancellable = true)
    private void particlefilter$filterMining(BlockPos pos, Direction side, CallbackInfo ci) {
        if (level != null && FilterRules.isBlockHidden(level.getBlockState(pos))) ci.cancel();
    }
}
