package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.ScrollPanel;
import particlefilter.gui.widget.TextField;
import particlefilter.render.ZoneRenderer;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Base for Particle Filter screens. They don't pause the game (so a scan keeps collecting while the menu is open)
 * and only lightly dim the world instead of blurring it, so zone outlines stay visible while editing.
 */
public abstract class PfScreen extends Screen {

    protected final Screen parent;
    /** The scrolling list of the current page, if it has one. */
    protected ScrollPanel panel;
    /** Scroll position carried across rebuilds of the same page. */
    protected double savedScroll;
    /** Set by widgets inside the list; the rebuild then happens on the next tick, outside the click. */
    private boolean refreshPending;

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

    /** Rebuilds the screen after a change, keeping the list scrolled where it was. */
    protected void refresh() {
        if (panel != null) savedScroll = panel.scrollAmount();
        rebuildWidgets();
    }

    protected void refreshNextTick() {
        refreshPending = true;
    }

    @Override
    public void tick() {
        if (refreshPending) {
            refreshPending = false;
            refresh();
        }
    }

    protected ScrollPanel addPanel(int x, int y, int width, int height, int gap) {
        panel = addRenderableWidget(new ScrollPanel(x, y, width, height, gap));
        return panel;
    }

    /** Fills the panel and restores the scroll position saved before the rebuild. */
    protected void setRows(List<ScrollPanel.Row> rows) {
        panel.setRows(rows);
        panel.setScrollAmount(savedScroll);
    }

    protected FlatButton button(FlatButton.Style style, Component label, int x, int y, int width, int height, Runnable onPress) {
        return addRenderableWidget(new FlatButton(style, label, x, y, width, height, onPress));
    }

    /** A row-sized button not yet added anywhere, for placing inside a {@link ScrollPanel.Row}. */
    protected FlatButton rowButton(FlatButton.Style style, Component label, int width, Runnable onPress) {
        return new FlatButton(style, label, 0, 0, width, 16, onPress);
    }

    public static final int SPINNER_WIDTH = 84;

    /**
     * Adds a number field between − and + buttons to [row], [SPINNER_WIDTH] px wide in total. [onValue] receives
     * every value typed or stepped that is within [min, max]; anything else is ignored until corrected.
     */
    protected void spinner(ScrollPanel.Row row, int x, int value, int min, int max, Component tooltip, IntConsumer onValue) {
        TextField box = new TextField(font, 0, 0, SPINNER_WIDTH - 36, 16, tooltip).tooltip(tooltip);
        box.setMaxLength(9);
        box.setFilter(s -> s.matches(min < 0 ? "-?\\d*" : "\\d*"));
        box.setValue(Integer.toString(value));
        box.setResponder(s -> parse(s, min, max, onValue));
        row.add(new FlatButton(FlatButton.Style.SECONDARY, Component.literal("−"), 0, 0, 16, 16, () -> step(box, -1, min, max)), x);
        row.add(box, x + 18);
        row.add(new FlatButton(FlatButton.Style.SECONDARY, Component.literal("+"), 0, 0, 16, 16, () -> step(box, 1, min, max)), x + SPINNER_WIDTH - 16);
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

    private static void step(TextField box, int delta, int min, int max) {
        int current;
        try {
            current = Integer.parseInt(box.getValue());
        } catch (NumberFormatException e) {
            current = Math.max(min, 0);
        }
        if (hasShiftDown()) delta *= 10;
        box.setValue(Integer.toString(Math.max(min, Math.min(max, current + delta))));
    }

    /** A section label row: uppercase faint text with an optional count. */
    protected ScrollPanel.Row sectionRow(String label) {
        return new ScrollPanel.Row(16, (g, x, y, w, h, hovered) -> Theme.text(g, font, label, x + 2, y + 6, Theme.TEXT_FAINT));
    }

    /** A row of dim text wrapped to the panel width, sized to fit. */
    protected ScrollPanel.Row hintRow(Component text, int width) {
        List<FormattedCharSequence> lines = font.split(text, width - 8);
        return new ScrollPanel.Row(lines.size() * 11 + 6, (g, x, y, w, h, hovered) -> {
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 4, y + 3 + i * 11, Theme.TEXT_DIM, false);
        });
    }

    /** Centered empty-state message: a title line and a wrapped explanation. */
    protected void drawEmptyState(GuiGraphics g, Component title, Component body, int centerX, int y, int width) {
        Theme.centered(g, font, title.getString(), centerX, y, Theme.TEXT);
        y += 16;
        for (FormattedCharSequence line : font.split(body, Math.min(width, 260))) {
            g.drawString(font, line, centerX - font.width(line) / 2, y, Theme.TEXT_DIM, false);
            y += 11;
        }
    }

    protected static <T extends AbstractWidget> T active(T widget, boolean active) {
        widget.active = active;
        return widget;
    }
}
