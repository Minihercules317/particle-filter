package particlefilter.gui.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import particlefilter.gui.Theme;

/** Vanilla text box drawn on a flat dark field with an accent outline while focused. */
public class TextField extends net.minecraft.client.gui.components.EditBox {

    private final Anim focus = new Anim(0);
    /** True while vanilla draws the text, so it skips its own frame but keeps the bordered text inset. */
    private boolean drawingText;

    public TextField(Font font, int x, int y, int width, int height, Component name) {
        super(font, x, y, width, height, name);
        setTextShadow(false);
        setTextColor(Theme.TEXT & 0xFFFFFF);
    }

    public TextField tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        setTooltipDelay(FlatButton.TOOLTIP_DELAY);
        return this;
    }

    @Override
    public boolean isBordered() {
        return !drawingText && super.isBordered();
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float f = focus.to(isFocused() ? 1 : 0, 16);
        int border = Theme.mix(isHovered() ? Theme.CONTROL_HOVER : Theme.CONTROL, Theme.ACCENT, f);
        Theme.outlined(g, getX(), getY(), getWidth(), getHeight(), 3, border, Theme.FIELD);
        // Vanilla insets bordered text by 4 px but only reserves that space while it also draws its frame;
        // narrowing the box for the draw keeps long text inside the field.
        int width = getWidth();
        drawingText = true;
        setWidth(width - 8);
        try {
            super.renderWidget(g, mouseX, mouseY, partialTick);
        } finally {
            setWidth(width);
            drawingText = false;
        }
    }
}
