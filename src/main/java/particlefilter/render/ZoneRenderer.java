package particlefilter.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.PfScreen;
import particlefilter.gui.Theme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Outlines zones in the world, each in its own color (touching zones always differ). While a zone is being edited or
 * hovered it is drawn brighter and thicker and the others dimmer; a zone that Here would create is cyan. Drawn while
 * outlines are switched on, and always while a Particle Filter screen is open.
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
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void box(PoseStack pose, VertexConsumer lines, AABB box, int argb) {
        LevelRenderer.renderLineBox(pose, lines, box, (argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f, 1.0f);
    }
}
