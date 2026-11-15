package particlefilter.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
//? if <=1.20.1 {
/*import org.joml.Matrix3f;
import org.joml.Matrix4f;
*///?}
import org.joml.Vector3f;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.PfScreen;
import particlefilter.gui.Theme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Outlines zones in the world, each in its own color (touching zones always differ). While a zone is being edited or
 * hovered it is drawn brighter and thicker and the others dimmer; a zone that Here would create is cyan. Drawn while
 * outlines are switched on, and always while a Particle Filter screen is open. With lines to zones switched on
 * ({@link PfConfig#zoneTracers}), a line also runs from the crosshair to each zone's center, through blocks.
 */
public final class ZoneRenderer {

    public static boolean alwaysShow = false;
    /** Zone being edited or pointed at in a screen. */
    public static Zone highlighted;
    /** Zone that Here would create, not yet added. */
    public static AABB preview;
    /** More zones to highlight like [highlighted], e.g. every zone of one particle. Cleared with it. */
    public static List<Zone> highlightedMore = List.of();
    /** More boxes to preview like [preview], e.g. one per source of a particle. Cleared with it. */
    public static List<AABB> previewMore = List.of();

    /** Clears every highlight and preview. */
    public static void clear() {
        highlighted = null;
        preview = null;
        highlightedMore = List.of();
        previewMore = List.of();
    }

    private ZoneRenderer() {}

    /** Called by the loader after translucent blocks are drawn, with that stage's pose and camera. */
    public static void render(PoseStack pose, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (!alwaysShow && !(mc.screen instanceof PfScreen)) return;
        var zones = PfConfig.zonesHere();
        List<AABB> previews = new ArrayList<>();
        if (mc.screen instanceof PfScreen) {
            if (preview != null) previews.add(preview);
            previews.addAll(previewMore);
        }
        if (zones.isEmpty() && previews.isEmpty()) return;
        Set<Zone> focus = new HashSet<>(highlightedMore);
        if (highlighted != null) focus.add(highlighted);
        focus.retainAll(zones);

        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (Zone zone : zones) {
            int color = Theme.zoneColor(zone);
            if (focus.contains(zone)) {
                color = Theme.mix(color, 0xFFFFFFFF, 0.3f);
                box(pose, lines, zone.box().inflate(0.02), color); // a second line next to the first reads as thicker
            } else if (!focus.isEmpty() || !previews.isEmpty()) {
                color = Theme.mix(color, 0xFF000000, 0.5f);
            }
            box(pose, lines, zone.box(), color);
        }
        for (AABB previewBox : previews) {
            box(pose, lines, previewBox, Theme.NEW_ZONE);
            box(pose, lines, previewBox.inflate(0.02), Theme.NEW_ZONE);
        }
        buffers.endBatch(RenderType.lines());

        if (PfConfig.zoneTracers() && !zones.isEmpty()) {
            // A point just in front of the camera along the view lies under the crosshair.
            Vector3f look = camera.getLookVector();
            Vec3 start = cameraPos.add(look.x() * TRACER_START, look.y() * TRACER_START, look.z() * TRACER_START);
            VertexConsumer tracers = buffers.getBuffer(Types.TRACERS);
            for (Zone zone : zones) {
                int color = Theme.zoneColor(zone);
                if (focus.contains(zone)) color = Theme.mix(color, 0xFFFFFFFF, 0.3f);
                else if (!focus.isEmpty() || !previews.isEmpty()) color = Theme.mix(color, 0xFF000000, 0.5f);
                line(pose, tracers, start, zone.box().getCenter(), color);
            }
            buffers.endBatch(Types.TRACERS);
        }
        pose.popPose();
    }

    /** How far in front of the camera tracers start, in blocks: past the near clipping plane, under the crosshair. */
    private static final float TRACER_START = 0.5f;

    private static void line(PoseStack pose, VertexConsumer lines, Vec3 from, Vec3 to, int argb) {
        float r = (argb >> 16 & 0xFF) / 255f, g = (argb >> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        Vector3f normal = new Vector3f((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z));
        if (normal.lengthSquared() < 1e-6f) return;
        normal.normalize();
        //? if >1.20.1 {
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, (float) from.x, (float) from.y, (float) from.z).setColor(r, g, b, 1f).setNormal(last, normal.x, normal.y, normal.z);
        lines.addVertex(last, (float) to.x, (float) to.y, (float) to.z).setColor(r, g, b, 1f).setNormal(last, normal.x, normal.y, normal.z);
        //?} else {
        /*Matrix4f matrix = pose.last().pose();
        Matrix3f normalMatrix = pose.last().normal();
        lines.vertex(matrix, (float) from.x, (float) from.y, (float) from.z).color(r, g, b, 1f).normal(normalMatrix, normal.x, normal.y, normal.z).endVertex();
        lines.vertex(matrix, (float) to.x, (float) to.y, (float) to.z).color(r, g, b, 1f).normal(normalMatrix, normal.x, normal.y, normal.z).endVertex();
        *///?}
    }

    /** Render types the game doesn't offer; a subclass, because the parts they are built from are protected. */
    private abstract static class Types extends RenderType {
        /** Lines like {@link RenderType#lines()}, but drawn over blocks instead of hidden behind them. */
        static final RenderType TRACERS = create("particlefilter_tracers", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256, false, false,
            CompositeState.builder()
                .setShaderState(RENDERTYPE_LINES_SHADER)
                .setLineState(new LineStateShard(OptionalDouble.empty()))
                .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setOutputState(ITEM_ENTITY_TARGET)
                .setWriteMaskState(COLOR_WRITE)
                .setCullState(NO_CULL)
                .setDepthTestState(NO_DEPTH_TEST)
                .createCompositeState(false));

        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
            super(name, format, mode, bufferSize, crumbling, sort, setup, clear);
        }
    }

    private static void box(PoseStack pose, VertexConsumer lines, AABB box, int argb) {
        LevelRenderer.renderLineBox(pose, lines, box, (argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f, 1.0f);
    }
}
