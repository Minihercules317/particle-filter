package pfbench;

import com.mojang.logging.LogUtils;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

/**
 * Benchmark-only harness for Particle Filter (benchmarks/HARNESS.md), Forge 1.20.1 entry point. Same behaviour as
 * the NeoForge one (../../harness/src/neoforge/java/pfbench/PfBench.java). Not part of Particle Filter and never
 * shipped to players. Without a plan (system property pfbench.plan) it registers its particle types and stays idle.
 */
@Mod(PfBench.MOD_ID)
public final class PfBench {
    public static final String MOD_ID = "pfbench";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, MOD_ID);

    /** The particle the benchmark configurations filter. overrideLimiter=false, so vanilla's distance and Particles-setting checks apply. */
    public static final RegistryObject<SimpleParticleType> TARGET = PARTICLE_TYPES.register("target", () -> new SimpleParticleType(false));
    /** Never filtered; its own render type (own 16,384 queue), so the target can't evict it. */
    public static final RegistryObject<SimpleParticleType> CONTROL = PARTICLE_TYPES.register("control", () -> new SimpleParticleType(false));

    /** The target's sprites, reused by {@link UntypedParticle}, which has no type and so no provider. */
    static volatile SpriteSet targetSprites;

    @SuppressWarnings("removal")   // FMLJavaModLoadingContext.get(): still the documented way on Forge 47
    public PfBench() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        PARTICLE_TYPES.register(modBus);
        if (FMLEnvironment.dist != Dist.CLIENT) return;   // client-only harness
        modBus.addListener(PfBench::registerProviders);

        Runner runner = Runner.fromSystemProperty();
        if (runner != null) {
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
                if (event.phase == TickEvent.Phase.START) runner.clientTickStart();
            });
            // Integrated-server saves (autosave every ~5 minutes) cost disk I/O; logged so analysis can flag windows with one.
            MinecraftForge.EVENT_BUS.addListener((LevelEvent.Save event) -> Counters.serverSaves.incrementAndGet());
            LOGGER.info("pfbench: plan {} loaded ({})", runner.plan.run_id, runner.plan.kind);
        } else {
            LOGGER.info("pfbench: no plan (system property pfbench.plan); idle");
        }
    }

    private static void registerProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(TARGET.get(), sprites -> {
            targetSprites = sprites;
            return (type, level, x, y, z, xs, ys, zs) -> new HarnessParticle(level, x, y, z, sprites.get(0, 1),
                ParticleRenderType.PARTICLE_SHEET_OPAQUE, Runner.targetLifetime, HarnessParticle.TARGET_COLOR, false);
        });
        event.registerSpriteSet(CONTROL.get(), sprites ->
            (type, level, x, y, z, xs, ys, zs) -> new HarnessParticle(level, x, y, z, sprites.get(0, 1),
                ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT, Runner.controlLifetime, HarnessParticle.CONTROL_COLOR, true));
    }
}
