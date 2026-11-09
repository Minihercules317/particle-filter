package pfbench;

import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * Switches Particle Filter's Active rules mode through its existing public API (PfConfig.setMode), by reflection,
 * when Particle Filter is installed. The harness never changes the rules themselves.
 */
final class PfControl {
    private final Method setMode;
    private final Method getMode;
    private final Class<?> modeClass;
    final String version;

    private PfControl(Method setMode, Method getMode, Class<?> modeClass, String version) {
        this.setMode = setMode;
        this.getMode = getMode;
        this.modeClass = modeClass;
        this.version = version;
    }

    /** Null when Particle Filter isn't loaded (configuration A). */
    static PfControl find() {
        if (!ModList.get().isLoaded("particlefilter")) return null;
        try {
            ClassLoader loader = PfControl.class.getClassLoader();
            Class<?> config = Class.forName("particlefilter.config.PfConfig", true, loader);
            Class<?> mode = Class.forName("particlefilter.config.FilterMode", true, loader);
            String version = ModList.get().getModContainerById("particlefilter")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
            return new PfControl(config.getMethod("setMode", mode), config.getMethod("mode"), mode, version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Particle Filter is loaded but PfConfig.setMode/mode weren't found", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void setMode(String name) {
        try {
            setMode.invoke(null, Enum.valueOf((Class) modeClass, name));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PfConfig.setMode(" + name + ") failed", e);
        }
    }

    String mode() {
        try {
            return String.valueOf(getMode.invoke(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PfConfig.mode() failed", e);
        }
    }
}
