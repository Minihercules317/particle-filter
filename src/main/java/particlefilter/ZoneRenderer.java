package particlefilter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import particlefilter.screen.PfScreen;

/**
 * Draws zone outlines in the world: orange for zones, yellow for the zone being edited, cyan for a preview of a
 * zone that "hide here" would create. Shown when toggled on, and always while a Particle Filter screen is open.
 */
public final class ZoneRenderer {

    public static boolean showOutlines = false;
    /** Zone currently being edited, drawn highlighted. */
    public static PfConfig.Zone highlighted;
    /** Center and radius of a zone that would be created, drawn as a preview. */
    public static BlockPos previewCenter;
    public static int previewRadius;

    private ZoneRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (!showOutlines && !(mc.screen instanceof PfScreen)) return;
        var zones = PfConfig.zonesHere();
        if (zones.isEmpty() && previewCenter == null) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        for (PfConfig.Zone zone : zones) {
            if (zone == highlighted) LevelRenderer.renderLineBox(pose, lines, zone.box(), 1.0f, 1.0f, 0.2f, 1.0f);
            else LevelRenderer.renderLineBox(pose, lines, zone.box(), 1.0f, 0.55f, 0.0f, 1.0f);
        }
        if (previewCenter != null) {
            int r = previewRadius;
            AABB box = new AABB(previewCenter.getX() - r, previewCenter.getY() - r, previewCenter.getZ() - r,
                previewCenter.getX() + r + 1, previewCenter.getY() + r + 1, previewCenter.getZ() + r + 1);
            LevelRenderer.renderLineBox(pose, lines, box, 0.2f, 0.9f, 1.0f, 1.0f);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
