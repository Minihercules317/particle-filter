package particlefilter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import particlefilter.config.FilterMode;
import particlefilter.config.PfConfig;
import particlefilter.filter.FilterRules;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.ParticleFilterScreen.Tab;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.ParticleScan;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Client-only core: config, commands, keybinds, and per-tick work. The loader entry points in
 * {@code particlefilter.loader} hook these into each mod loader's events.
 */
public final class ParticleFilter {

    public static final String MOD_ID = "particlefilter";
    public static final String COMMAND = "particles";
    public static final Logger LOGGER = LoggerFactory.getLogger("Particle Filter");

    private static final String CATEGORY = "key.categories.particlefilter";
    private static final KeyMapping OPEN_MENU = key("open_menu");
    private static final KeyMapping SCAN = key("scan");
    private static final KeyMapping TOGGLE_BLOCK = key("toggle_block");
    private static final KeyMapping TOGGLE_OUTLINES = key("toggle_outlines");
    private static final KeyMapping CYCLE_MODE = key("cycle_mode");
    private static final KeyMapping SHOW_ALL = key("show_all");

    /** Screens requested by commands open on the next tick; the closing chat screen would otherwise replace them. */
    private static Supplier<Screen> pendingScreen;

    /** Keybinds for the loader to register; all unbound by default. */
    public static final List<KeyMapping> KEYS = List.of(OPEN_MENU, SCAN, TOGGLE_BLOCK, TOGGLE_OUTLINES, CYCLE_MODE, SHOW_ALL);

    private ParticleFilter() {}

    public static void init(Path configDir) {
        PfConfig.load(configDir.resolve("particlefilter.json"));
    }

    /** The menu, opened from the loader's mod list. */
    public static Screen configScreen(Screen parent) {
        return new ParticleFilterScreen(parent, null);
    }

    private static KeyMapping key(String name) {
        return new KeyMapping("key.particlefilter." + name, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(COMMAND)
            .executes(ctx -> openNextTick(() -> new ParticleFilterScreen(null, null)))
            .then(Commands.literal("scan").executes(ctx -> openNextTick(ParticleFilter::scanAndOpen)))
            .then(Commands.literal("hidden").executes(ctx -> openNextTick(() -> new ParticleFilterScreen(null, Tab.HIDDEN))))
            .then(Commands.literal("zones").executes(ctx -> openNextTick(() -> new ParticleFilterScreen(null, Tab.ZONES)))));
    }

    private static int openNextTick(Supplier<Screen> screen) {
        pendingScreen = screen;
        return 1;
    }

    /** Starts a scan at the crosshair and opens the Scan tab, which fills in when the scan finishes. */
    private static Screen scanAndOpen() {
        ParticleScan.start();
        return new ParticleFilterScreen(null, Tab.SCAN);
    }

    /** The block under the crosshair, if the player is looking at one. */
    public static Optional<BlockPos> targetedBlock() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) return Optional.of(blockHit.getBlockPos());
        return Optional.empty();
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        FilterRules.tick();
        ParticleScan.tick();
        if (pendingScreen != null) {
            Supplier<Screen> screen = pendingScreen;
            pendingScreen = null;
            mc.setScreen(screen.get());
        }
        while (OPEN_MENU.consumeClick()) mc.setScreen(new ParticleFilterScreen(null, null));
        while (SCAN.consumeClick()) mc.setScreen(scanAndOpen());
        while (TOGGLE_OUTLINES.consumeClick()) {
            ZoneRenderer.alwaysShow = !ZoneRenderer.alwaysShow;
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable(ZoneRenderer.alwaysShow
                ? "particlefilter.outlines.on" : "particlefilter.outlines.off"), true);
        }
        while (TOGGLE_BLOCK.consumeClick()) toggleTargetedBlock(mc);
        updateShowAll(mc);
        while (CYCLE_MODE.consumeClick()) {
            FilterMode mode = PfConfig.mode().next();
            PfConfig.setMode(mode);
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("particlefilter.mode.changed",
                Component.translatable(mode.key()).withStyle(ChatFormatting.AQUA)), true);
        }
    }

    /**
     * Pauses every rule while Hold to Show All Particles is held. Opening a screen releases all keys, so this can't
     * stay stuck on. The action bar message is refreshed while held, since it fades after a few seconds.
     */
    private static void updateShowAll(Minecraft mc) {
        boolean held = SHOW_ALL.isDown();
        boolean changed = held != FilterRules.isShowingAll();
        FilterRules.setShowAll(held);
        if (mc.player == null || mc.level == null) return;
        if (held && (changed || mc.level.getGameTime() % 40 == 0)) {
            mc.player.displayClientMessage(Component.translatable("particlefilter.show_all.on").withStyle(ChatFormatting.AQUA), true);
        } else if (changed) {
            mc.player.displayClientMessage(Component.translatable("particlefilter.mode.changed",
                Component.translatable(PfConfig.mode().key()).withStyle(ChatFormatting.AQUA)), true);
        }
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
        if (FilterRules.isBlockHidden(state) && !PfConfig.hasBlockEntry(id)) {
            // Hidden by a wildcard or tag entry; toggling the exact id would do nothing.
            player.displayClientMessage(Component.translatable("particlefilter.block.by_rule", name).withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        boolean hide = !PfConfig.hasBlockEntry(id);
        PfConfig.setBlockHidden(id, hide);
        Component message = Component.translatable(hide ? "particlefilter.block.hidden" : "particlefilter.block.shown", name);
        if (hide && !PfConfig.mode().global) {
            // Saved, but not applied until Active rules includes it again.
            message = Component.translatable("particlefilter.block.hidden_paused", name, Component.translatable(PfConfig.mode().key()));
        }
        player.displayClientMessage(message, true);
    }
}
