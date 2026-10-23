package particlefilter.config;

import java.util.Locale;

/**
 * Which of the user's rules are applied right now, without changing the rules themselves. Saved in the config;
 * switched from the menu sidebar or the Cycle Active Rules keybind.
 */
public enum FilterMode {
    ALL(true, true),
    EVERYWHERE(true, false),
    ZONES(false, true),
    NONE(false, false);

    /** Particles hidden everywhere, and hidden block particles (which also apply everywhere). */
    public final boolean global;
    /** Particles hidden inside zones. */
    public final boolean zones;

    FilterMode(boolean global, boolean zones) {
        this.global = global;
        this.zones = zones;
    }

    public FilterMode next() {
        FilterMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Translation key of the mode's name; add ".tooltip" for its description. */
    public String key() {
        return "particlefilter.mode." + name().toLowerCase(Locale.ROOT);
    }
}
