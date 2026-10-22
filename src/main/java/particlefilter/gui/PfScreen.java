package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import particlefilter.render.ZoneRenderer;

import java.util.function.IntConsumer;

/**
 * Base for Particle Filter screens. They don't pause the game (so a scan keeps collecting while the menu is open)
 * and only lightly dim the world instead of blurring it, so zone outlines stay visible while editing.
 */
public abstract class PfScreen extends Screen {

    protected static final int ROW_HEIGHT = 24;
    protected static final int BUTTON_HEIGHT = 20;
    protected static final int GRAY = 0xA0A0A0;
    protected static final int WHITE = 0xFFFFFF;

    protected final Screen parent;

    protected PfScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        ZoneRenderer.highlighted = null;
        ZoneRenderer.preview = null;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x70000000);
        graphics.fill(0, height - 34, width, height, 0xA0000000); // footer band, so the hotbar doesn't show through
    }

    /** Width of the centered content column. */
    protected int contentWidth() {
        return Math.min(420, width - 20);
    }

    protected int contentLeft() {
        return (width - contentWidth()) / 2;
    }

    protected Button button(Component label, int x, int y, int w, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(label, onPress).bounds(x, y, w, BUTTON_HEIGHT).build());
    }

    protected Button button(Component label, Component tooltip, int x, int y, int w, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(label, onPress).bounds(x, y, w, BUTTON_HEIGHT).tooltip(Tooltip.create(tooltip)).build());
    }

    /**
     * Number field between − and + buttons, 100 px wide in total. [onValue] receives every value typed or stepped
     * that is within [min, max]; anything else is ignored until corrected.
     */
    protected EditBox spinner(int x, int y, int value, int min, int max, Component tooltip, IntConsumer onValue) {
        EditBox box = new EditBox(font, x + 22, y, 56, BUTTON_HEIGHT, tooltip);
        box.setMaxLength(9);
        box.setFilter(s -> s.matches(min < 0 ? "-?\\d*" : "\\d*"));
        box.setValue(Integer.toString(value));
        box.setResponder(s -> parse(s, min, max, onValue));
        box.setTooltip(Tooltip.create(tooltip));
        addRenderableWidget(box);
        button(Component.literal("−"), x, y, 20, b -> step(box, -1, min, max));
        button(Component.literal("+"), x + 80, y, 20, b -> step(box, 1, min, max));
        return box;
    }

    private static void parse(String text, int min, int max, IntConsumer onValue) {
        if (!text.matches("-?\\d+")) return;
        try {
            int v = Integer.parseInt(text);
            if (v >= min && v <= max) onValue.accept(v);
        } catch (NumberFormatException ignored) {
            // beyond int range; wait for the user to correct it
        }
    }

    private static void step(EditBox box, int delta, int min, int max) {
        int current;
        try {
            current = Integer.parseInt(box.getValue());
        } catch (NumberFormatException e) {
            current = Math.max(min, 0);
        }
        box.setValue(Integer.toString(Math.max(min, Math.min(max, current + delta))));
    }

    /** Shortens [text] to [maxWidth] pixels, ending in "…" when cut. */
    protected String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }

    protected void drawCenteredHint(GuiGraphics graphics, Component text, int y) {
        for (var line : font.split(text, contentWidth() - 20)) {
            graphics.drawCenteredString(font, line, width / 2, y, GRAY);
            y += 11;
        }
    }
}
