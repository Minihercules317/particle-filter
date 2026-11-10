package particlefilter.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import particlefilter.gui.Theme;

import java.util.function.Consumer;

/**
 * A slim on/off switch: a thin track with a round knob that slides across when flipped. With a label, the label sits
 * to the right and is part of the clickable area.
 */
public class Toggle extends AbstractButton {

    public static final int WIDTH = 18;
    public static final int HEIGHT = 10;
    private static final int TRACK = 4;
    private static final int LABEL_GAP = 6;

    private final Consumer<Boolean> onChange;
    private final Anim knob;
    private final Anim hover = new Anim(0);
    private final boolean labelled;
    private boolean value;

    /** [name] is only narrated; the switch shows no text. */
    public Toggle(int x, int y, boolean value, Component name, Consumer<Boolean> onChange) {
        this(x, y, value, name, onChange, false);
    }

    private Toggle(int x, int y, boolean value, Component name, Consumer<Boolean> onChange, boolean labelled) {
        super(x, y, labelled ? WIDTH + LABEL_GAP + Minecraft.getInstance().font.width(name) : WIDTH, HEIGHT, name);
        this.value = value;
        this.onChange = onChange;
        this.knob = new Anim(value ? 1 : 0);
        this.labelled = labelled;
    }

    /** A switch with [label] drawn after it; clicking the label flips it too. */
    public static Toggle labelled(int x, int y, boolean value, Component label, Consumer<Boolean> onChange) {
        return new Toggle(x, y, value, label, onChange, true);
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
        int x = getX(), y = getY();
        int trackOff = Theme.mix(0xFF3A3F48, 0xFF4A505B, h);
        int trackOn = Theme.mix(Theme.ACCENT_DARK, 0xFF1C7A6F, h);
        int track = Theme.mix(trackOff, trackOn, t);
        int knobColor = Theme.mix(Theme.mix(0xFF9AA2AD, 0xFFC4CAD2, h), Theme.mix(Theme.ACCENT, Theme.ACCENT_HOVER, h), t);
        if (!active) {
            track = Theme.fade(track, 0.4f);
            knobColor = 0xFF5D6570;
        }
        Theme.rect(g, x + 1, y + (HEIGHT - TRACK) / 2, WIDTH - 2, TRACK, TRACK / 2, track);
        Theme.dot(g, x + Math.round(t * (WIDTH - HEIGHT)), y, HEIGHT, knobColor);
        if (labelled) {
            Font font = Minecraft.getInstance().font;
            Theme.text(g, font, getMessage(), x + WIDTH + LABEL_GAP, y + (HEIGHT - 8) / 2 + 1,
                active ? Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h) : Theme.TEXT_FAINT);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), CommonComponents.optionStatus(value)));
    }
}
