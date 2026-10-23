package particlefilter.gui.widget;

import net.minecraft.Util;

/** A value that eases toward a target over real time, for hover fades and sliding toggles. */
public final class Anim {

    private float value;
    private long lastMillis = -1;

    public Anim(float initial) {
        value = initial;
    }

    /** Moves toward [target] by the time passed since the last call; [speed] is roughly 1 / seconds to settle. */
    public float to(float target, float speed) {
        long now = Util.getMillis();
        float dt = lastMillis < 0 ? 1 : (now - lastMillis) / 1000f;
        lastMillis = now;
        value += (target - value) * Math.min(1, dt * speed);
        if (Math.abs(target - value) < 0.002f) value = target;
        return value;
    }
}
