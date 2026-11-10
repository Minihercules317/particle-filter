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

    private ZoneRenderer() {}

    /** Called by the loader after translucent blocks are drawn, with that stage's pose and camera. */
    public static void render(PoseStack pose, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (!alwaysShow && !(mc.screen instanceof PfScreen)) return;
        var zones = PfConfig.zonesHere();
        AABB previewBox = mc.screen instanceof PfScreen ? preview : null;
        if (zones.isEmpty() && previewBox == null) return;
        Zone focus = highlighted != null && zones.contains(highlighted) ? highlighted : null;

        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (Zone zone : zones) {
            int color = Theme.zoneColor(zone);
            if (zone == focus) {
                color = Theme.mix(color, 0xFFFFFFFF, 0.3f);
                box(pose, lines, zone.box().inflate(0.02), color); // a second line next to the first reads as thicker
            } else if (focus != null || previewBox != null) {
                color = Theme.mix(color, 0xFF000000, 0.5f);
            }
            box(pose, lines, zone.box(), color);
        }
        if (previewBox != null) {
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
