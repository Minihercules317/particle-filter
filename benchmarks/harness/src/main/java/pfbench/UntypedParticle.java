package pfbench;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;

/**
 * The untyped variant: built by the harness and passed straight to ParticleEngine.add, like mods that spawn
 * particles without a particle type. Particle Filter matches these by class name: "pfbench.UntypedParticle".
 * Going through add skips vanilla's distance and Particles-setting checks, as it does for those mods.
 */
public final class UntypedParticle extends HarnessParticle {
    public UntypedParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z, PfBench.targetSprites.get(0, 1), ParticleRenderType.PARTICLE_SHEET_OPAQUE,
            Runner.targetLifetime, TARGET_COLOR);
    }
}
