package particlefilter.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import particlefilter.gui.Theme;

import java.time.Duration;

/** A flat, rounded button. The style decides its colors and whether the label is centered or left-aligned. */
public class FlatButton extends AbstractButton {

    public enum Style {
        /** Regular action on a raised background. */
        SECONDARY,
        /** The main action of a page, in the accent color. */
        PRIMARY,
        /** Destructive action; while {@link #selected} it asks for confirmation in solid red. */
        DANGER,
        /** No background until hovered, for small actions inside rows. */
        GHOST,
        /** Sidebar page link; {@link #selected} marks the current page. */
        NAV,
        /** One choice of several, with a dot that lights up when {@link #selected}. */
        OPTION
    }

    public static final Duration TOOLTIP_DELAY = Duration.ofMillis(350);

    private final Style style;
    private final Runnable onPress;
    private final Anim hover = new Anim(0);
    private boolean selected;
    private String badge;
    private float progress = -1;

    public FlatButton(Style style, Component label, int x, int y, int width, int height, Runnable onPress) {
        super(x, y, width, height, label);
        this.style = style;
        this.onPress = onPress;
    }

    public FlatButton selected(boolean selected) {
        this.selected = selected;
        return this;
    }

    public boolean isSelected() {
        return selected;
    }

    /** Small text on the right, such as a count. */
    public FlatButton badge(String badge) {
        this.badge = badge;
        return this;
    }

    public FlatButton tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        setTooltipDelay(TOOLTIP_DELAY);
        return this;
    }

    public FlatButton enabled(boolean enabled) {
        active = enabled;
        return this;
    }

    /** Fills the button from the left as a progress bar; negative hides it. */
    public void setProgress(float progress) {
        this.progress = progress;
    }

    @Override
    public void onPress() {
        onPress.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        float h = hover.to(active && isHoveredOrFocused() ? 1 : 0, 16);
        int x = getX(), y = getY(), w = getWidth(), ht = getHeight();
        int radius = ht >= 16 ? 3 : 2;
        int textColor = Theme.TEXT;

        switch (style) {
            case PRIMARY -> {
                if (!active) {
                    Theme.rect(g, x, y, w, ht, radius, Theme.CONTROL);
                    textColor = Theme.TEXT_FAINT;
                } else {
                    Theme.rect(g, x, y, w, ht, radius, Theme.mix(Theme.ACCENT, Theme.ACCENT_HOVER, h));
                    textColor = Theme.ON_ACCENT;
                }
            }
            case SECONDARY -> {
                Theme.rect(g, x, y, w, ht, radius, active ? Theme.mix(Theme.CONTROL, Theme.CONTROL_HOVER, h) : Theme.fade(Theme.CONTROL, 0.5f));
                if (!active) textColor = Theme.TEXT_FAINT;
            }
            case DANGER -> {
                if (selected) {
                    Theme.rect(g, x, y, w, ht, radius, Theme.mix(Theme.DANGER, 0xFFFF8A8A, h));
                    textColor = 0xFF2A0E0E;
                } else {
                    Theme.rect(g, x, y, w, ht, radius, Theme.mix(Theme.DANGER_BG, Theme.DANGER_BG_HOVER, h));
                    textColor = active ? Theme.DANGER : Theme.TEXT_FAINT;
                }
            }
            case GHOST -> {
                if (h > 0) Theme.rect(g, x, y, w, ht, radius, Theme.fade(Theme.CONTROL_HOVER, h));
                textColor = active ? Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h) : Theme.TEXT_FAINT;
            }
            case NAV -> {
                if (selected) Theme.rect(g, x, y, w, ht, radius, Theme.CARD_HOVER);
                else if (h > 0) Theme.rect(g, x, y, w, ht, radius, Theme.fade(Theme.CARD, h));
                if (selected) Theme.rect(g, x, y + 4, 2, ht - 8, 1, Theme.ACCENT);
                textColor = selected ? Theme.TEXT : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h);
            }
            case OPTION -> {
                if (selected) Theme.rect(g, x, y, w, ht, radius, Theme.CARD_HOVER);
                else if (h > 0) Theme.rect(g, x, y, w, ht, radius, Theme.fade(Theme.CARD, h));
                textColor = selected ? Theme.TEXT : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h);
            }
        }

        if (progress >= 0) {
            int filled = Math.round((w - 2) * Math.min(1, progress));
            Theme.rect(g, x, y, w, ht, radius, Theme.CONTROL);
            if (filled > 0) Theme.rect(g, x + 1, y + 1, filled, ht - 2, Math.max(0, radius - 1), Theme.ACCENT_DARK);
            textColor = Theme.TEXT;
        }

        int textY = y + (ht - 8) / 2;
        String label = getMessage().getString();
        switch (style) {
            case NAV -> {
                int badgeWidth = badge == null ? 0 : font.width(badge) + 6;
                Theme.text(g, font, Theme.fit(font, label, w - 16 - badgeWidth), x + 10, textY, textColor);
                if (badge != null) {
                    Theme.text(g, font, badge, x + w - 6 - font.width(badge), textY, selected ? Theme.ACCENT : Theme.TEXT_FAINT);
                }
            }
            case OPTION -> {
                Theme.dot(g, x + 6, y + (ht - 6) / 2, 6, selected ? Theme.ACCENT : Theme.mix(Theme.CONTROL, Theme.CONTROL_HOVER, h));
                Theme.text(g, font, Theme.fit(font, label, w - 20), x + 17, textY, textColor);
            }
            default -> Theme.centered(g, font, Theme.fit(font, label, w - 6), x + w / 2, textY, textColor);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
