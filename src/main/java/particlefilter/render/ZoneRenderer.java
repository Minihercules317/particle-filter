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

/**
 * Outlines zones in the world: orange normally, yellow for the zone being edited or hovered, and cyan for a zone
 * that would be created. Drawn while outlines are switched on, and always while a Particle Filter screen is open.
 */
public final class ZoneRenderer {

    public static boolean alwaysShow = false;
    /** Zone being edited or pointed at in a screen. */
    public static Zone highlighted;
    /** Zone that "hide in zone" would create, not yet added. */
    public static AABB preview;

    private ZoneRenderer() {}

    /** Called by the loader after translucent blocks are drawn, with that stage's pose and camera. */
    public static void render(PoseStack pose, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (!alwaysShow && !(mc.screen instanceof PfScreen)) return;
        var zones = PfConfig.zonesHere();
        AABB previewBox = mc.screen instanceof PfScreen ? preview : null;
        if (zones.isEmpty() && previewBox == null) return;

        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (Zone zone : zones) {
            if (zone == highlighted) LevelRenderer.renderLineBox(pose, lines, zone.box(), 1.0f, 1.0f, 0.25f, 1.0f);
            else LevelRenderer.renderLineBox(pose, lines, zone.box(), 1.0f, 0.55f, 0.0f, 1.0f);
        }
        if (previewBox != null) LevelRenderer.renderLineBox(pose, lines, previewBox, 0.25f, 0.9f, 1.0f, 1.0f);
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
