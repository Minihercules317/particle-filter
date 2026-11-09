package pfbench;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * A particle with a fixed lifetime, fixed size, no motion and no physics, so the number alive is exactly
 * rate x (lifetime + 1) until the per-render-type limit. Nothing about it is random.
 */
public class HarnessParticle extends TextureSheetParticle {
    static final float[] TARGET_COLOR = {1.0f, 0.25f, 0.25f, 1.0f};   // red, opaque
    static final float[] CONTROL_COLOR = {0.3f, 0.5f, 1.0f, 0.6f};    // blue, translucent

    private final ParticleRenderType renderType;

    public HarnessParticle(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite,
                           ParticleRenderType renderType, int lifetime, float[] rgba) {
        super(level, x, y, z);
        this.renderType = renderType;
        this.lifetime = lifetime;
        this.hasPhysics = false;
        this.gravity = 0.0f;
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        this.quadSize = 0.15f;
        this.setSprite(sprite);
        this.setColor(rgba[0], rgba[1], rgba[2]);
        this.setAlpha(rgba[3]);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) this.remove();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return renderType;
    }
}
