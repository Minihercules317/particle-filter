package particlefilter;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.phys.Vec3;
import particlefilter.screen.ScanScreen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts particles spawned near a point for a few seconds, then reports them most-frequent first.
 * Typed particles are keyed by particle type id; particles added without a type are keyed by class name.
 */
public final class Census {

    public record Result(String entry, int count, boolean typed) {
        /** Short display name: the type id, or just the simple class name for untyped particles. */
        public String displayName() {
            return typed ? entry : entry.substring(entry.lastIndexOf('.') + 1);
        }
    }

    public static final double RADIUS = 6.0;
    private static final int DURATION_TICKS = 100;
    private static final int MAX_CHAT_LINES = 8;

    /** Set while a typed particle is being created, so the untyped hook doesn't count or filter it twice. */
    public static final ThreadLocal<Boolean> IN_TYPED_SPAWN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile Vec3 center;
    private static int ticksLeft;
    private static boolean openResultsWhenDone;
    private static final Map<String, Integer> typed = new HashMap<>();
    private static final Map<String, Integer> untyped = new HashMap<>();

    private static List<Result> lastResults = List.of();
    private static BlockPos lastCenter;

    private Census() {}

    public static boolean isActive() {
        return center != null;
    }

    public static List<Result> lastResults() {
        return lastResults;
    }

    /** Block the last scan was centered on; "hide here" zones are placed around it. */
    public static BlockPos lastCenter() {
        return lastCenter;
    }

    /** Starts a scan around the block under the crosshair, or the player when not looking at a block. */
    public static void startAtCrosshair(boolean openResults) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Vec3 at = ParticleFilter.targetedBlock().map(BlockPos::getCenter).orElse(player.getEyePosition());
        synchronized (Census.class) {
            typed.clear();
            untyped.clear();
        }
        ticksLeft = DURATION_TICKS;
        openResultsWhenDone = openResults;
        lastCenter = BlockPos.containing(at);
        center = at;
        player.displayClientMessage(Component.translatable("particlefilter.scan.started", (int) RADIUS), true);
    }

    public static void recordTyped(String entry, double x, double y, double z) {
        Vec3 c = center;
        if (c == null || c.distanceToSqr(x, y, z) > RADIUS * RADIUS) return;
        synchronized (Census.class) {
            typed.merge(entry, 1, Integer::sum);
        }
    }

    public static void recordUntyped(String className, Vec3 pos) {
        Vec3 c = center;
        if (c == null || c.distanceToSqr(pos) > RADIUS * RADIUS) return;
        synchronized (Census.class) {
            untyped.merge(className, 1, Integer::sum);
        }
    }

    public static void tick() {
        if (center == null || --ticksLeft > 0) return;
        center = null;
        List<Result> results = new ArrayList<>();
        synchronized (Census.class) {
            typed.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> results.add(new Result(e.getKey(), e.getValue(), true)));
            untyped.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> results.add(new Result(e.getKey(), e.getValue(), false)));
        }
        lastResults = List.copyOf(results);
        for (Result r : results) {
            ParticleFilter.LOGGER.info("Scan: {} x {}{}", r.count(), r.entry(), r.typed() ? "" : " (untyped)");
        }

        Minecraft mc = Minecraft.getInstance();
        if (openResultsWhenDone && mc.screen == null) {
            mc.setScreen(new ScanScreen(null));
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (results.isEmpty()) {
            player.displayClientMessage(Component.translatable("particlefilter.scan.none", (int) RADIUS).withStyle(ChatFormatting.GRAY), false);
            return;
        }
        player.displayClientMessage(Component.translatable("particlefilter.scan.header", (int) RADIUS).withStyle(ChatFormatting.GOLD), false);
        results.stream().limit(MAX_CHAT_LINES).forEach(r -> player.displayClientMessage(
            Component.literal("  " + r.count() + "× ").append(Component.literal(r.displayName())
                .withStyle(r.typed() ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE)), false));
        player.displayClientMessage(Component.translatable("particlefilter.scan.open_results").withStyle(style -> style
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + ParticleFilter.COMMAND + " results"))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("particlefilter.scan.open_results.hover")))
            .withColor(ChatFormatting.GREEN).withUnderlined(true)), false);
    }
}
