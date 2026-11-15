package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import particlefilter.config.Zone;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.ScrollPanel;
import particlefilter.gui.widget.Slider;
import particlefilter.gui.widget.TextField;
import particlefilter.gui.widget.Toggle;
import particlefilter.render.ZoneRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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

    //? if <=1.20.1 {
    /*/^* Draws the background first, as {@link Screen#render} does itself in later versions; subclasses override it. ^/
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
    }

    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }
    *///?}

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while a text box has focus, so keys type into it. */
    public boolean isTyping() {
        GuiEventListener focused = getFocused();
        while (focused instanceof ContainerEventHandler container && container.getFocused() != null && container.getFocused() != focused) {
            focused = container.getFocused();
        }
        return focused instanceof EditBox;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && getFocused() instanceof Slider slider) slider.finishDrag();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Movement keys move the player instead while walking in the menu is on ({@link MenuWalking}). */
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (MenuWalking.active() && MenuWalking.isMovementKey(key, scanCode)) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        ZoneRenderer.clear();
    }

    /** Rebuilds the screen after a change, keeping the list scrolled where it was. */
    public void refresh() {
        if (panel != null) savedScroll = panel.scrollAmount();
        rebuildWidgets();
    }

    public void refreshNextTick() {
        refreshPending = true;
    }

    @Override
    public void tick() {
        if (refreshPending) {
            refreshPending = false;
            refresh();
        }
    }

    public ScrollPanel addPanel(int x, int y, int width, int height, int gap) {
        panel = addRenderableWidget(new ScrollPanel(x, y, width, height, gap));
        return panel;
    }

    /**
     * Swaps the panel's rows for [rows] where the list is scrolled now, without rebuilding the screen, so the other
     * widgets (such as a slider being dragged) keep working.
     */
    public void replaceRows(List<ScrollPanel.Row> rows) {
        double scroll = panel.scrollAmount();
        panel.setRows(rows);
        panel.setScrollAmount(scroll);
    }

    /** Fills the panel and restores the scroll position saved before the rebuild. */
    public void setRows(List<ScrollPanel.Row> rows) {
        panel.setRows(rows);
        panel.setScrollAmount(savedScroll);
    }

    /** Height of a full-size button, such as a page's main action. */
    public static final int BUTTON_HEIGHT = 20;
    /** Height of a compact button or text box in a page's body. */
    public static final int COMPACT_HEIGHT = 18;
    /** Height of the small buttons in list rows, spinners and Back. */
    public static final int SMALL_BUTTON_HEIGHT = 16;
    /** Corner radius of the menu windows. */
    protected static final int WINDOW_RADIUS = 6;
    /** Corner radius of a card in a list. */
    public static final int CARD_RADIUS = 3;
    /** Size of the small colored dot at the start of a card or label. */
    public static final int DOT_SIZE = 5;

    public FlatButton button(FlatButton.Style style, Component label, int x, int y, int width, int height, Runnable onPress) {
        return addRenderableWidget(new FlatButton(style, label, x, y, width, height, onPress));
    }

    /** A row-sized button not yet added anywhere, for placing inside a {@link ScrollPanel.Row}. */
    public FlatButton rowButton(FlatButton.Style style, Component label, int width, Runnable onPress) {
        return new FlatButton(style, label, 0, 0, width, SMALL_BUTTON_HEIGHT, onPress);
    }

    public static final int SPINNER_WIDTH = 84;
    /** Width of a spinner's − and + buttons. */
    private static final int SPINNER_STEP = SMALL_BUTTON_HEIGHT;
    /** Gap between a spinner's number box and each of its buttons. */
    private static final int SPINNER_GAP = 2;

    /**
     * Adds a number field between − and + buttons to [row], [SPINNER_WIDTH] px wide in total. [onValue] receives
     * every value typed or stepped that is within [min, max]; anything else is ignored until corrected.
     */
    protected void spinner(ScrollPanel.Row row, int x, int value, int min, int max, Component tooltip, IntConsumer onValue) {
        List<AbstractWidget> parts = spinnerParts(SPINNER_WIDTH, value, min, max, tooltip, onValue);
        row.add(parts.get(0), x);
        row.add(parts.get(1), x + SPINNER_STEP + SPINNER_GAP);
        row.add(parts.get(2), x + SPINNER_WIDTH - SPINNER_STEP);
    }

    /** Like {@link #spinner} but added straight to the screen, [width] px wide, with its top left at (x, y). */
    public void addSpinner(int x, int y, int width, int value, int min, int max, Component tooltip, IntConsumer onValue) {
        List<AbstractWidget> parts = spinnerParts(width, value, min, max, tooltip, onValue);
        parts.get(0).setPosition(x, y);
        parts.get(1).setPosition(x + SPINNER_STEP + SPINNER_GAP, y);
        parts.get(2).setPosition(x + width - SPINNER_STEP, y);
        parts.forEach(this::addRenderableWidget);
    }

    /** −, the number box and +, in that order, not yet placed. */
    private List<AbstractWidget> spinnerParts(int width, int value, int min, int max, Component tooltip, IntConsumer onValue) {
        TextField box = new TextField(font, 0, 0, width - 2 * (SPINNER_STEP + SPINNER_GAP), SMALL_BUTTON_HEIGHT, tooltip).tooltip(tooltip);
        box.setMaxLength(9);
        box.setFilter(s -> s.matches(min < 0 ? "-?\\d*" : "\\d*"));
        box.setValue(Integer.toString(value));
        box.setResponder(s -> parse(s, min, max, onValue));
        return List.of(
            new FlatButton(FlatButton.Style.SECONDARY, Component.literal("−"), 0, 0, SPINNER_STEP, SMALL_BUTTON_HEIGHT, () -> step(box, -1, min, max)),
            box,
            new FlatButton(FlatButton.Style.SECONDARY, Component.literal("+"), 0, 0, SPINNER_STEP, SMALL_BUTTON_HEIGHT, () -> step(box, 1, min, max)));
    }

    /** A clearly labelled "← Back" button that returns to the previous screen. */
    protected FlatButton backButton(int x, int y) {
        Component label = Component.translatable("particlefilter.back_button");
        return button(FlatButton.Style.SECONDARY, label, x, y, font.width(label) + 14, SMALL_BUTTON_HEIGHT, this::onClose)
            .tooltip(Component.translatable("particlefilter.back.tooltip"));
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

    /** Height of a section label row. */
    protected static final int SECTION_HEIGHT = 16;
    /** Left inset of a section label. */
    protected static final int SECTION_TEXT_X = 2;
    /** Space above a section label. */
    protected static final int SECTION_TEXT_Y = 6;

    /** A section label row: uppercase faint text with an optional count. */
    public ScrollPanel.Row sectionRow(String label) {
        return new ScrollPanel.Row(SECTION_HEIGHT, (g, x, y, w, h, hovered) -> Theme.text(g, font, label, x + SECTION_TEXT_X, y + SECTION_TEXT_Y, Theme.TEXT_FAINT));
    }

    /** Pixels from one line of dim explanation text (a hint row or an empty-state message) to the next. */
    private static final int HINT_LINE = 11;
    /** Left and right inset of a hint row's text. */
    protected static final int HINT_TEXT_X = 4;
    /** Space above and below a hint row's text. */
    private static final int HINT_TEXT_Y = 3;

    /** A row of dim text wrapped to the panel width, sized to fit. */
    public ScrollPanel.Row hintRow(Component text, int width) {
        List<FormattedCharSequence> lines = font.split(text, width - 2 * HINT_TEXT_X);
        return new ScrollPanel.Row(lines.size() * HINT_LINE + 2 * HINT_TEXT_Y, (g, x, y, w, h, hovered) -> {
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + HINT_TEXT_X, y + HINT_TEXT_Y + i * HINT_LINE, Theme.TEXT_DIM, false);
        });
    }

    /** Width of an entry row's Delete button. */
    protected static final int DELETE_WIDTH = 44;
    /** Gap between an entry row's right edge and its Delete button. */
    private static final int ENTRY_RIGHT = 2;
    /** Gap between an entry row's switch and its Delete button, and between its name and the switch. */
    private static final int ENTRY_GAP = 8;
    /** Left inset of the dot on an entry or result card. */
    public static final int CARD_DOT_X = 8;
    /** Left inset of the name on an entry or result card, past the dot. */
    public static final int CARD_TEXT_X = 19;
    /** Padding above an entry row's name (and the same below). */
    private static final int ENTRY_TEXT_Y = 6;

    /**
     * One hidden entry as a card the width of the panel: a dot in [color], [name] wrapped to fit, a switch that turns it
     * off without forgetting it, and Delete. [id] is shown as a tooltip when it differs from [name] (null for none).
     * The dot and name dim while the entry is off or [paused]. [isOn] is read each frame; [setOn] gets the switch's new
     * state; [delete] runs before the screen is rebuilt. While hovered, [highlight] (if not null) is outlined in the world.
     */
    public ScrollPanel.Row entryRow(String name, String id, int color, boolean paused, BooleanSupplier isOn,
                                       Consumer<Boolean> setOn, Runnable delete, Zone highlight) {
        int w = panel.contentWidth();
        int switchX = w - DELETE_WIDTH - ENTRY_RIGHT - ENTRY_GAP - Toggle.WIDTH;
        List<FormattedCharSequence> shown = wrap(name, switchX - CARD_TEXT_X - ENTRY_GAP);
        ScrollPanel.Row row = new ScrollPanel.Row(textHeight(shown.size()) + 2 * ENTRY_TEXT_Y, (g, x, y, rw, h, hovered) -> {
            boolean dim = paused || !isOn.getAsBoolean();
            Theme.rect(g, x, y, rw, h, CARD_RADIUS, hovered ? Theme.CARD_HOVER : Theme.CARD);
            Theme.dot(g, x + CARD_DOT_X, y + (h - DOT_SIZE) / 2, DOT_SIZE, dim ? Theme.fade(color, 0.35f) : color);
            drawLines(g, shown, x + CARD_TEXT_X, y + ENTRY_TEXT_Y, dim ? Theme.TEXT_FAINT : Theme.TEXT);
            if (hovered && highlight != null) ZoneRenderer.highlighted = highlight;
        });
        if (id != null && !id.equals(name)) row.tooltip(Component.literal(id));
        row.add(new Toggle(0, 0, isOn.getAsBoolean(), Component.literal(name), setOn)
            .tooltip(Component.translatable("particlefilter.entry.switch.tooltip")), switchX);
        row.add(rowButton(FlatButton.Style.GHOST, Component.translatable("particlefilter.delete"), DELETE_WIDTH, () -> {
            delete.run();
            refreshNextTick();
        }).tooltip(Component.translatable("particlefilter.entry.delete.tooltip")), w - DELETE_WIDTH - ENTRY_RIGHT);
        return row;
    }

    /** Pixels from one line of wrapped text to the next. */
    public static final int LINE = 10;

    /** [text] split into lines at most [width] px wide; words too long for a line are broken. Never empty. */
    public List<FormattedCharSequence> wrap(Component text, int width) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, width));
        return lines.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : lines;
    }

    public List<FormattedCharSequence> wrap(String text, int width) {
        return wrap(Component.literal(text), width);
    }

    /** Height of [lines] lines of text, from the top of the first to the bottom of the last. */
    public static int textHeight(int lines) {
        return lines * LINE - 2;
    }

    /** Draws [lines] one under another from (x, y); colors set on the text win over [color]. */
    public void drawLines(GuiGraphics g, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, y + i * LINE, color, false);
    }

    /** [text] in [color], for building wrapped text with more than one color. */
    public static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(s -> s.withColor(color & 0xFFFFFF));
    }

    /** One part of a line of details, such as a size or a count, drawn in [color]. */
    public record Piece(String text, int color) {}

    private static final String SEPARATOR = " · ";

    /**
     * [pieces] joined by " · " (in [separatorColor]) and wrapped to [width] px. Lines break only between pieces, and
     * the separator is left out at a break, so no line starts or ends with one. A piece too long for a line of its
     * own wraps at its spaces. Never empty.
     */
    public List<FormattedCharSequence> wrapPieces(List<Piece> pieces, int separatorColor, int width) {
        width = Math.max(1, width);
        int separatorWidth = font.width(SEPARATOR);
        List<FormattedCharSequence> lines = new ArrayList<>();
        MutableComponent line = null;
        int lineWidth = 0;
        for (Piece piece : pieces) {
            int pieceWidth = font.width(piece.text());
            if (line != null && lineWidth + separatorWidth + pieceWidth <= width) {
                line.append(colored(SEPARATOR, separatorColor)).append(colored(piece.text(), piece.color()));
                lineWidth += separatorWidth + pieceWidth;
                continue;
            }
            if (line != null) lines.add(line.getVisualOrderText());
            List<String> parts = font.getSplitter().splitLines(piece.text(), width, Style.EMPTY).stream().map(FormattedText::getString).toList();
            for (int i = 0; i < parts.size() - 1; i++) lines.add(colored(parts.get(i), piece.color()).getVisualOrderText());
            String last = parts.isEmpty() ? "" : parts.get(parts.size() - 1);
            line = Component.empty().append(colored(last, piece.color()));
            lineWidth = font.width(last);
        }
        if (line != null) lines.add(line.getVisualOrderText());
        return lines.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : lines;
    }

    /** Centered empty-state message: a title line and a wrapped explanation. */
    public void drawEmptyState(GuiGraphics g, Component title, Component body, int centerX, int y, int width) {
        Theme.centered(g, font, title.getString(), centerX, y, Theme.TEXT);
        y += 16;
        for (FormattedCharSequence line : font.split(body, Math.min(width, 260))) {
            g.drawString(font, line, centerX - font.width(line) / 2, y, Theme.TEXT_DIM, false);
            y += HINT_LINE;
        }
    }

    protected static <T extends AbstractWidget> T active(T widget, boolean active) {
        widget.active = active;
        return widget;
    }
}
