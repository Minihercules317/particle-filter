package particlefilter.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import particlefilter.gui.Theme;

import java.util.function.IntConsumer;

/**
 * A slim slider in the style of {@link Toggle}: a thin track, filled up to a round knob. Drag or click it, or use the
 * arrow keys while it has focus. [onValue] gets the new whole number when the mouse is let go (or a key changes it),
 * not on every step of a drag, since applying a value can rebuild zones and save the config.
 */
public class Slider extends AbstractSliderButton {

    public static final int HEIGHT = 10;
    private static final int TRACK = 4;
    /** Vanilla maps the mouse to the value across the width minus this much on each side; the knob's center follows. */
    private static final int MOUSE_INSET = 4;

    private final Component name;
    private final int min;
    private final int max;
    private final IntConsumer onValue;
    private final Anim hover = new Anim(0);
    private int applied;

    /** [name] is only narrated; the page draws the label and the value beside the slider. */
    public Slider(int x, int y, int width, int value, int min, int max, Component name, IntConsumer onValue) {
        super(x, y, width, HEIGHT, name, (value - min) / (double) (max - min));
        this.name = name;
        this.min = min;
        this.max = max;
        this.onValue = onValue;
        this.applied = value;
        updateMessage();
    }

    public Slider tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        setTooltipDelay(FlatButton.TOOLTIP_DELAY);
        return this;
    }

    /** The value shown now, while dragging too. */
    public int intValue() {
        return min + (int) Math.round(value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(CommonComponents.optionNameValue(name, Component.literal(Integer.toString(intValue()))));
    }

    @Override
    protected void applyValue() {
        // Applied in commit(), once the drag ends.
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        commit();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (handled) commit();
        return handled;
    }

    private void commit() {
        int now = intValue();
        if (now == applied) return;
        applied = now;
        onValue.accept(now);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float h = hover.to(active && isHoveredOrFocused() ? 1 : 0, 16);
        int x = getX(), y = getY(), w = getWidth();
        int knobCenter = x + MOUSE_INSET + (int) Math.round(value * (w - 2 * MOUSE_INSET));
        int trackY = y + (HEIGHT - TRACK) / 2;
        Theme.rect(g, x + 1, trackY, w - 2, TRACK, TRACK / 2, Theme.mix(0xFF3A3F48, 0xFF4A505B, h));
        Theme.rect(g, x + 1, trackY, Math.max(TRACK, knobCenter - x - 1), TRACK, TRACK / 2, Theme.mix(Theme.ACCENT_DARK, 0xFF1C7A6F, h));
        Theme.dot(g, knobCenter - HEIGHT / 2, y, HEIGHT, Theme.mix(Theme.ACCENT, Theme.ACCENT_HOVER, h));
    }
}
