package pfbench;

import com.mojang.logging.LogUtils;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Benchmark-only harness for Particle Filter (benchmarks/HARNESS.md). Not part of Particle Filter and never
 * shipped to players. Without a plan (system property pfbench.plan) it registers its particle types and stays idle.
 */
@Mod(value = PfBench.MOD_ID, dist = Dist.CLIENT)
public final class PfBench {
    public static final String MOD_ID = "pfbench";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES = DeferredRegister.create(Registries.PARTICLE_TYPE, MOD_ID);

    /** The particle the benchmark configurations filter. overrideLimiter=false, so vanilla's distance and Particles-setting checks apply. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TARGET = PARTICLE_TYPES.register("target", () -> new SimpleParticleType(false));
    /** Never filtered; its own render type (own 16,384 queue), so the target can't evict it. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CONTROL = PARTICLE_TYPES.register("control", () -> new SimpleParticleType(false));

    /** The target's sprites, reused by {@link UntypedParticle}, which has no type and so no provider. */
    static volatile SpriteSet targetSprites;

    public PfBench(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
        modBus.addListener(PfBench::registerProviders);

        Runner runner = Runner.fromSystemProperty();
        if (runner != null) {
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> runner.clientTickStart());
            LOGGER.info("pfbench: plan {} loaded ({})", runner.plan.run_id, runner.plan.kind);
        } else {
            LOGGER.info("pfbench: no plan (system property pfbench.plan); idle");
        }
    }

    private static void registerProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(TARGET.get(), sprites -> {
            targetSprites = sprites;
            return (type, level, x, y, z, xs, ys, zs) -> new HarnessParticle(level, x, y, z, sprites.get(0, 1),
                ParticleRenderType.PARTICLE_SHEET_OPAQUE, Runner.targetLifetime, HarnessParticle.TARGET_COLOR);
        });
        event.registerSpriteSet(CONTROL.get(), sprites ->
            (type, level, x, y, z, xs, ys, zs) -> new HarnessParticle(level, x, y, z, sprites.get(0, 1),
                ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT, Runner.controlLifetime, HarnessParticle.CONTROL_COLOR));
    }
}
