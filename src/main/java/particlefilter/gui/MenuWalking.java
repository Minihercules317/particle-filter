package particlefilter.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;
import particlefilter.config.PfConfig;

import java.util.List;

/**
 * Walking while a Particle Filter screen is open, when switched on in the settings ({@link PfConfig#walkInMenu}).
 * The loaders report movement keys as released while any screen is open, so the player's input reads the keys
 * itself instead (see {@code KeyboardInputMixin}), and the screen leaves those keys alone. Not while typing in a
 * box, where the keys are letters.
 */
public final class MenuWalking {

    private MenuWalking() {}

    /** True while movement keys should move the player instead of reaching the screen. */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return PfConfig.walkInMenu() && mc.screen instanceof PfScreen screen && !screen.isTyping();
    }

    /** Move, jump and sneak: the keys walking takes over. */
    private static List<KeyMapping> movementKeys(Options options) {
        return List.of(options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump, options.keyShift);
    }

    /** True if the key or mouse button bound to [mapping] is held now. */
    public static boolean isHeld(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        long window = Minecraft.getInstance().getWindow().getWindow();
        return switch (key.getType()) {
            case KEYSYM -> key.getValue() != InputConstants.UNKNOWN.getValue() && InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> false; // GLFW can't be asked about a scancode
        };
    }

    /** True if the key pressed is bound to moving, jumping or sneaking. */
    public static boolean isMovementKey(int keyCode, int scanCode) {
        for (KeyMapping mapping : movementKeys(Minecraft.getInstance().options)) {
            if (mapping.matches(keyCode, scanCode)) return true;
        }
        return false;
    }
}
