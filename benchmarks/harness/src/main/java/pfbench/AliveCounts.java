package pfbench;

import net.minecraft.client.particle.ParticleRenderType;

/** Implemented on ParticleEngine by the mixin: sizes of its per-render-type queues (16,384 slots each). */
public interface AliveCounts {
    int pfbench$alive(ParticleRenderType type);

    int pfbench$aliveTotal();
}
