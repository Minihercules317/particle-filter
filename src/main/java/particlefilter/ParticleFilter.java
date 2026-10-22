package particlefilter;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import particlefilter.screen.HiddenScreen;
import particlefilter.screen.HubScreen;
import particlefilter.screen.ScanScreen;
import particlefilter.screen.ZonesScreen;

import java.util.Optional;
import java.util.function.Supplier;

@Mod(value = ParticleFilter.MOD_ID, dist = Dist.CLIENT)
public final class ParticleFilter {

    public static final String MOD_ID = "particlefilter";
    public static final String COMMAND = "particles";
    public static final Logger LOGGER = LoggerFactory.getLogger("Particle Filter");

    private static final String CATEGORY = "key.categories.particlefilter";
    private static final KeyMapping OPEN_MENU = new KeyMapping("key.particlefilter.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    private static final KeyMapping SCAN = new KeyMapping("key.particlefilter.scan", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    private static final KeyMapping TOGGLE_BLOCK = new KeyMapping("key.particlefilter.toggle_block", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    private static final KeyMapping TOGGLE_OUTLINES = new KeyMapping("key.particlefilter.toggle_outlines", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    /** Screens requested by commands open on the next tick; the closing chat screen would otherwise replace them. */
    private static Supplier<Screen> pendingScreen;

    public ParticleFilter(IEventBus modBus, ModContainer container) {
        PfConfig.load();
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.register(OPEN_MENU);
            event.register(SCAN);
            event.register(TOGGLE_BLOCK);
            event.register(TOGGLE_OUTLINES);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onClientTick());
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, ParticleFilter::registerCommands);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, ZoneRenderer::render);
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new HubScreen(parent));
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND)
            .executes(ctx -> open(() -> new HubScreen(null)))
            .then(Commands.literal("results").executes(ctx -> open(() -> new ScanScreen(null))))
            .then(Commands.literal("hidden").executes(ctx -> open(() -> new HiddenScreen(null))))
            .then(Commands.literal("zones").executes(ctx -> open(() -> new ZonesScreen(null))))
            .then(Commands.literal("scan").executes(ctx -> {
                Census.startAtCrosshair(false);
                return 1;
            })));
    }

    private static int open(Supplier<Screen> screen) {
        pendingScreen = screen;
        return 1;
    }

    /** The block under the crosshair, if the player is looking at one. */
    public static Optional<BlockPos> targetedBlock() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) return Optional.of(blockHit.getBlockPos());
        return Optional.empty();
    }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        PfRules.tick();
        Census.tick();
        if (pendingScreen != null) {
            Supplier<Screen> screen = pendingScreen;
            pendingScreen = null;
            mc.setScreen(screen.get());
        }
        while (OPEN_MENU.consumeClick()) mc.setScreen(new HubScreen(null));
        while (SCAN.consumeClick()) {
            if (Screen.hasShiftDown()) mc.setScreen(new ScanScreen(null));
            else Census.startAtCrosshair(false);
        }
        while (TOGGLE_OUTLINES.consumeClick()) {
            ZoneRenderer.showOutlines = !ZoneRenderer.showOutlines;
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable(ZoneRenderer.showOutlines
                ? "particlefilter.outlines.on" : "particlefilter.outlines.off"), true);
        }
        while (TOGGLE_BLOCK.consumeClick()) toggleTargetedBlock(mc);
    }

    private static void toggleTargetedBlock(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        Optional<BlockPos> target = targetedBlock();
        if (target.isEmpty()) {
            player.displayClientMessage(Component.translatable("particlefilter.block.no_block").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        BlockState state = mc.level.getBlockState(target.get());
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Component name = state.getBlock().getName();
        if (PfRules.isBlockHidden(state) && !PfConfig.isBlockEntry(id)) {
            // Hidden by a wildcard or tag entry; toggling the exact id would do nothing.
            player.displayClientMessage(Component.translatable("particlefilter.block.by_rule", name).withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        boolean hide = !PfConfig.isBlockEntry(id);
        PfConfig.setBlockHidden(id, hide);
        player.displayClientMessage(Component.translatable(hide ? "particlefilter.block.hidden" : "particlefilter.block.shown", name, id), true);
    }
}
