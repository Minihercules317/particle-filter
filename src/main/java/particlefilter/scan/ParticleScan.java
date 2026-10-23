package particlefilter.scan;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import particlefilter.ParticleFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records which particles spawn near a point for a few seconds. Results are listed most frequent first; typed
 * particles are keyed by particle type id, particles created without a type by class name.
 */
public final class ParticleScan {

    public record Result(String particle, int count, boolean typed) {}

    public static final int RADIUS = 6;
    private static final int DURATION_TICKS = 100;

    private static volatile Vec3 center;
    private static int ticksLeft;
    private static final Map<String, Integer> counts = new HashMap<>();
    private static final Map<String, Boolean> typedByParticle = new HashMap<>();

    private static List<Result> results = List.of();
    private static BlockPos target;
    private static Component targetName = Component.empty();
    private static int generation;

    private ParticleScan() {}

    public static boolean isRunning() {
        return center != null;
    }

    public static int secondsLeft() {
        return (ticksLeft + 19) / 20;
    }

    /** How far the running scan is, from 0 to 1. */
    public static float progress() {
        return isRunning() ? 1 - ticksLeft / (float) DURATION_TICKS : 0;
    }

    public static List<Result> results() {
        return results;
    }

    /** Block the last scan was centered on; "hide in zone" works around it. Null before the first scan. */
    public static BlockPos target() {
        return target;
    }

    /** What the last scan was centered on: the block's name, or the player. */
    public static Component targetName() {
        return targetName;
    }

    /** Increases whenever a scan finishes, so screens know to refresh. */
    public static int generation() {
        return generation;
    }

    /** Starts scanning around the block under the crosshair, or around the player when not looking at a block. */
    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Optional<BlockPos> block = ParticleFilter.targetedBlock();
        Vec3 at = block.map(BlockPos::getCenter).orElse(mc.player.getEyePosition());
        targetName = block.map(pos -> mc.level.getBlockState(pos).getBlock().getName())
            .orElse(Component.translatable("particlefilter.scan.target_you"));
        synchronized (ParticleScan.class) {
            counts.clear();
            typedByParticle.clear();
        }
        target = BlockPos.containing(at);
        ticksLeft = DURATION_TICKS;
        center = at;
    }

    public static void record(String particle, boolean typed, double x, double y, double z) {
        Vec3 c = center;
        if (c == null || c.distanceToSqr(x, y, z) > RADIUS * RADIUS) return;
        synchronized (ParticleScan.class) {
            counts.merge(particle, 1, Integer::sum);
            typedByParticle.put(particle, typed);
        }
    }

    public static void tick() {
        if (center == null || --ticksLeft > 0) return;
        center = null;
        List<Result> list = new ArrayList<>();
        synchronized (ParticleScan.class) {
            counts.forEach((particle, count) -> list.add(new Result(particle, count, typedByParticle.get(particle))));
        }
        list.sort((a, b) -> Integer.compare(b.count(), a.count()));
        results = List.copyOf(list);
        generation++;
        list.forEach(r -> ParticleFilter.LOGGER.info("Scan: {} x {}{}", r.count(), r.particle(), r.typed() ? "" : " (untyped)"));
    }

    /** Short display name: the type id, or just the simple class name for untyped particles. */
    public static String displayName(String particle) {
        return particle.contains(":") ? particle : particle.substring(particle.lastIndexOf('.') + 1);
    }
}
