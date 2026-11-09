package pfbench.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pfbench.Counters;
import pfbench.PfBench;
import pfbench.UntypedParticle;

import java.util.Map;
import java.util.Queue;

/**
 * Counts particles that were really created. A cancelling HEAD injector (Particle Filter's) returns before the
 * original code, so cancelled spawns never reach these RETURN/TAIL injection points.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin implements pfbench.AliveCounts {
    @Shadow
    private Map<ParticleRenderType, Queue<Particle>> particles;

    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("RETURN"))
    private void pfbench$created(ParticleOptions options, double x, double y, double z, double xs, double ys, double zs,
                                 CallbackInfoReturnable<Particle> cir) {
        if (!Counters.enabled || cir.getReturnValue() == null) return;
        ParticleType<?> type = options.getType();
        if (type == PfBench.TARGET.get()) Counters.createdTarget++;
        else if (type == PfBench.CONTROL.get()) Counters.createdControl++;
        else if (type == ParticleTypes.BLOCK) Counters.createdBlock++;
        else if (type == ParticleTypes.END_ROD) Counters.createdVanilla++;
        else Counters.createdOther++;
    }

    /** TAIL is reached only if nothing cancelled add() and the particle was queued (UntypedParticle has no group limit). */
    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("TAIL"))
    private void pfbench$added(Particle particle, CallbackInfo ci) {
        if (Counters.enabled && particle instanceof UntypedParticle) Counters.createdUntyped++;
    }

    @Override
    public int pfbench$alive(ParticleRenderType type) {
        Queue<Particle> queue = particles.get(type);
        return queue == null ? 0 : queue.size();
    }

    @Override
    public int pfbench$aliveTotal() {
        int total = 0;
        for (Queue<Particle> queue : particles.values()) total += queue.size();
        return total;
    }
}
