package particlefilter.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import particlefilter.gui.MenuWalking;

/**
 * Lets the player walk while a Particle Filter screen is open ({@link MenuWalking}). The loaders report every
 * movement key as released while a screen is open, so this reads the keys directly instead, the same way vanilla
 * turns them into movement. Sprinting isn't handled, so it needs the menu closed.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "tick(ZF)V", at = @At("HEAD"), cancellable = true)
    private void particlefilter$walkInMenu(boolean sneaking, float sneakFactor, CallbackInfo ci) {
        if (!MenuWalking.active()) return;
        Options options = Minecraft.getInstance().options;
        Input input = (Input) (Object) this;
        input.up = MenuWalking.isHeld(options.keyUp);
        input.down = MenuWalking.isHeld(options.keyDown);
        input.left = MenuWalking.isHeld(options.keyLeft);
        input.right = MenuWalking.isHeld(options.keyRight);
        input.forwardImpulse = particlefilter$impulse(input.up, input.down);
        input.leftImpulse = particlefilter$impulse(input.left, input.right);
        input.jumping = MenuWalking.isHeld(options.keyJump);
        input.shiftKeyDown = MenuWalking.isHeld(options.keyShift);
        if (sneaking) {
            input.leftImpulse *= sneakFactor;
            input.forwardImpulse *= sneakFactor;
        }
        ci.cancel();
    }

    @Unique
    private static float particlefilter$impulse(boolean positive, boolean negative) {
        return positive == negative ? 0 : positive ? 1 : -1;
    }
}
