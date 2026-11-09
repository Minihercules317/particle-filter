package pfbench.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pfbench.Counters;
import pfbench.Runner;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Unique
    private long pfbench$tickStart;

    /** End of a frame: after buffer swap, frame limiter and pause handling. */
    @Inject(method = "runTick(Z)V", at = @At("TAIL"))
    private void pfbench$frameEnd(boolean renderLevel, CallbackInfo ci) {
        Runner.frameEnd();
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void pfbench$tickStart(CallbackInfo ci) {
        pfbench$tickStart = System.nanoTime();
    }

    /** After the particle engine ticked, so alive counts are this tick's. */
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void pfbench$tickEnd(CallbackInfo ci) {
        Counters.clientTickNs = System.nanoTime() - pfbench$tickStart;
        Runner.tickEnd();
    }
}
