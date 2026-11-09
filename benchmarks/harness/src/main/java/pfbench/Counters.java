package pfbench;

/**
 * Per-tick counters, client thread only. The mixins add to the "created" fields; {@link Runner} adds attempts,
 * copies everything into its tick log once per tick and resets the per-tick fields. Always on in every
 * configuration, so their cost is part of the shared baseline.
 */
public final class Counters {
    private Counters() {}

    public static boolean enabled;

    public static long attemptsTarget;
    public static long attemptsControl;

    /** createParticle returned a particle (Particle Filter's cancellation returns before RETURN, so cancelled ones never count). */
    public static long createdTarget;
    public static long createdControl;
    public static long createdBlock;
    public static long createdVanilla;
    public static long createdOther;
    /** UntypedParticle that got past add() (Particle Filter cancels at add HEAD). */
    public static long createdUntyped;

    /** Nanoseconds spent in the last Minecraft.tick(). */
    public static long clientTickNs;

    static void resetTick() {
        attemptsTarget = attemptsControl = 0;
        createdTarget = createdControl = createdBlock = createdVanilla = createdOther = createdUntyped = 0;
    }
}
