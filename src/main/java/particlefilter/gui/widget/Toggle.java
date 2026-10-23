package particlefilter.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import particlefilter.gui.Theme;

import java.util.function.Consumer;

/** An on/off switch whose knob slides across when flipped. */
public class Toggle extends AbstractButton {

    public static final int WIDTH = 24;
    public static final int HEIGHT = 14;

    private final Consumer<Boolean> onChange;
    private final Anim knob;
    private final Anim hover = new Anim(0);
    private boolean value;

    /** [name] is only narrated; the switch shows no text. */
    public Toggle(int x, int y, boolean value, Component name, Consumer<Boolean> onChange) {
        super(x, y, WIDTH, HEIGHT, name);
        this.value = value;
        this.onChange = onChange;
        this.knob = new Anim(value ? 1 : 0);
    }

    public Toggle tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        setTooltipDelay(FlatButton.TOOLTIP_DELAY);
        return this;
    }

    public boolean value() {
        return value;
    }

    @Override
    public void onPress() {
        value = !value;
        onChange.accept(value);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float t = knob.to(value ? 1 : 0, 18);
        float h = hover.to(active && isHoveredOrFocused() ? 1 : 0, 16);
        int off = Theme.mix(0xFF3A3F48, 0xFF4A505B, h);
        int on = Theme.mix(Theme.ACCENT, Theme.ACCENT_HOVER, h);
        int track = Theme.mix(off, on, t);
        if (!active) track = Theme.fade(track, 0.4f);
        Theme.rect(g, getX(), getY(), WIDTH, HEIGHT, HEIGHT / 2, track);
        int knobX = getX() + 2 + Math.round(t * (WIDTH - HEIGHT));
        Theme.dot(g, knobX, getY() + 2, HEIGHT - 4, active ? 0xFFF4F6F8 : 0xFF8A9099);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), CommonComponents.optionStatus(value)));
    }
}
