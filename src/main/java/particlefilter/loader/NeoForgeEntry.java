package particlefilter.loader;

//? if neoforge {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import particlefilter.ParticleFilter;
import particlefilter.render.ZoneRenderer;

/** NeoForge entry point; only loaded on the client. */
@Mod(value = ParticleFilter.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeEntry {

    public NeoForgeEntry(IEventBus modBus, ModContainer container) {
        ParticleFilter.init(FMLPaths.CONFIGDIR.get());
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> ParticleFilter.KEYS.forEach(event::register));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> ParticleFilter.onClientTick());
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> ParticleFilter.registerCommands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) ZoneRenderer.render(event.getPoseStack(), event.getCamera());
        });
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> ParticleFilter.configScreen(parent));
    }
}
//?}
