package particlefilter.loader;

//? if forge {
/*import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import particlefilter.ParticleFilter;
import particlefilter.render.ZoneRenderer;

/^* Forge entry point. Forge loads it on servers too, where it only declares that servers don't need the mod. ^/
@Mod(ParticleFilter.MOD_ID)
public final class ForgeEntry {

    public ForgeEntry() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (version, fromServer) -> true));
        if (FMLEnvironment.dist == Dist.CLIENT) Client.init(FMLJavaModLoadingContext.get().getModEventBus());
    }

    /^* Separate class, so a dedicated server never loads the client classes it refers to. ^/
    private static final class Client {
        static void init(IEventBus modBus) {
            ParticleFilter.init(FMLPaths.CONFIGDIR.get());
            modBus.addListener(EventPriority.NORMAL, false, RegisterKeyMappingsEvent.class,
                event -> ParticleFilter.KEYS.forEach(event::register));
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, event -> {
                if (event.phase == TickEvent.Phase.END) ParticleFilter.onClientTick();
            });
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterClientCommandsEvent.class,
                event -> ParticleFilter.registerCommands(event.getDispatcher()));
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, event -> {
                if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) ZoneRenderer.render(event.getPoseStack(), event.getCamera());
            });
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> ParticleFilter.configScreen(parent)));
        }
    }
}
*///?}
