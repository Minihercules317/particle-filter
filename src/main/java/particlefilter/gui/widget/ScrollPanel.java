package particlefilter.gui.widget;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import particlefilter.gui.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * A smoothly scrolling column of rows. Each row paints itself and holds widgets placed relative to its corner.
 * Rows can differ in height. Content is clipped to the panel, and clicks outside the visible area are ignored.
 */
public class ScrollPanel extends ContainerWidget {

    /** Paints a row's background and text. [hovered] is true while the mouse is over the visible row. */
    public interface Painter {
        void paint(GuiGraphics g, int x, int y, int width, int height, boolean hovered);
    }

    public static final class Row {
        /** [y] is {@link #CENTERED} for widgets kept vertically centered as the row's height changes. */
        private record Placed(AbstractWidget widget, int x, int y) {
            int top(int rowHeight) {
                return y == CENTERED ? (rowHeight - widget.getHeight()) / 2 : y;
            }
        }

        private static final int CENTERED = Integer.MIN_VALUE;

        private final IntSupplier height;
        private final Painter painter;
        private final List<Placed> placed = new ArrayList<>();
        private Component tooltip;
        private Runnable onClick;

        public Row(int height, Painter painter) {
            this(() -> height, painter);
        }

        /** A row whose height is read every frame, for text that wraps differently as it changes. */
        public Row(IntSupplier height, Painter painter) {
            this.height = height;
            this.painter = painter;
        }

        public int height() {
            return height.getAsInt();
        }

        /** Adds [widget] [x] pixels from the row's left edge, vertically centered. */
        public Row add(AbstractWidget widget, int x) {
            return add(widget, x, CENTERED);
        }

        public Row add(AbstractWidget widget, int x, int y) {
            placed.add(new Placed(widget, x, y));
            return this;
        }

        /** Paints the row as the panel would, for rows that wrap another row's look. */
        public void paint(GuiGraphics g, int x, int y, int width, int height, boolean hovered) {
            painter.paint(g, x, y, width, height, hovered);
        }

        /** Runs when the row's empty space (not its widgets) is clicked, e.g. to open or close a folder. */
        public Row onClick(Runnable onClick) {
            this.onClick = onClick;
            return this;
        }

        /** Shown after a short hover over the row's empty space (not its widgets). */
        public Row tooltip(Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }
    }

    private static final int SCROLLBAR_SPACE = 8;
    private static final long TOOLTIP_DELAY_MS = 450;

    private final int gap;
    private final List<Row> rows = new ArrayList<>();
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private double scroll;
    private double targetScroll;
    private long lastFrame = -1;
    private boolean draggingBar;
    private Row tooltipRow;
    private long tooltipSince;
    /** The row under the mouse, outside its widgets, when the panel was last drawn; clicks there go to the row. */
    private Row clickableRow;

    public ScrollPanel(int x, int y, int width, int height, int gap) {
        super(x, y, width, height, Component.empty());
        this.gap = gap;
    }

    /** Width available to rows, leaving room for the scrollbar. */
    public int contentWidth() {
        return getWidth() - SCROLLBAR_SPACE;
    }

    public void setRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        widgets.clear();
        rows.forEach(r -> r.placed.forEach(p -> widgets.add(p.widget)));
        layout();
        setScrollAmount(scroll);
    }

    /** Moves the top edge to [y] and sets the height, e.g. when wrapped text above the panel gains a line. */
    public void setBounds(int y, int height) {
        setY(y);
        this.height = height;
        setScrollAmount(targetScroll);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public double scrollAmount() {
        return targetScroll;
    }

    /** Jumps to [amount] without easing. */
    public void setScrollAmount(double amount) {
        scroll = targetScroll = clampScroll(amount);
    }

    private int contentHeight() {
        int total = 0;
        for (Row row : rows) total += row.height() + gap;
        return Math.max(0, total - gap);
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - getHeight());
    }

    private double clampScroll(double amount) {
        return Math.max(0, Math.min(maxScroll(), amount));
    }

    /** Places every row's widgets for the current scroll position. */
    private void layout() {
        int top = getY() - (int) Math.round(scroll);
        for (Row row : rows) {
            int h = row.height();
            for (Row.Placed p : row.placed) p.widget.setPosition(getX() + p.x, top + p.top(h));
            top += h + gap;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrame < 0 ? 1 : (now - lastFrame) / 1000f;
        lastFrame = now;
        targetScroll = clampScroll(targetScroll);
        scroll += (targetScroll - scroll) * Math.min(1, dt * 20);
        if (Math.abs(targetScroll - scroll) < 0.3) scroll = targetScroll;
        layout();

        boolean mouseInside = isMouseOver(mouseX, mouseY);
        Row hoveredRow = null;
        boolean overWidget = false;
        g.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        int top = getY() - (int) Math.round(scroll);
        for (Row row : rows) {
            int h = row.height();
            if (top + h >= getY() && top < getY() + getHeight()) {
                boolean hovered = mouseInside && mouseX < getX() + contentWidth() && mouseY >= top && mouseY < top + h;
                if (hovered) hoveredRow = row;
                row.painter.paint(g, getX(), top, contentWidth(), h, hovered);
                for (Row.Placed p : row.placed) {
                    p.widget.render(g, mouseX, mouseY, partialTick);
                    if (p.widget.isHovered()) overWidget = true;
                }
            }
            top += h + gap;
        }
        g.disableScissor();
        renderScrollbar(g, mouseX, mouseY);
        clickableRow = overWidget ? null : hoveredRow;
        updateRowTooltip(clickableRow, now);
    }

    private void renderScrollbar(GuiGraphics g, int mouseX, int mouseY) {
        double max = maxScroll();
        if (max <= 0) return;
        int trackX = getX() + getWidth() - 3;
        int thumbHeight = Math.max(16, (int) ((long) getHeight() * getHeight() / contentHeight()));
        int thumbY = getY() + (int) ((getHeight() - thumbHeight) * (scroll / max));
        boolean active = draggingBar || (isMouseOver(mouseX, mouseY) && mouseX >= trackX - 4);
        Theme.rect(g, trackX, getY(), 3, getHeight(), 1, Theme.fade(Theme.CONTROL, 0.4f));
        Theme.rect(g, trackX, thumbY, 3, thumbHeight, 1, active ? 0xFF5A626E : 0xFF3E444E);
    }

    private void updateRowTooltip(Row row, long now) {
        if (row != tooltipRow) {
            tooltipRow = row;
            tooltipSince = now;
        }
        if (row != null && row.tooltip != null && now - tooltipSince >= TOOLTIP_DELAY_MS
            && Minecraft.getInstance().screen != null) {
            Minecraft.getInstance().screen.setTooltipForNextRenderPass(Tooltip.splitTooltip(Minecraft.getInstance(), row.tooltip));
        }
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return widgets;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        if (maxScroll() > 0 && mouseX >= getX() + getWidth() - SCROLLBAR_SPACE + 2 && button == 0) {
            draggingBar = true;
            scrollToMouse(mouseY);
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0 && clickableRow != null && clickableRow.onClick != null) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            clickableRow.onClick.run();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void scrollToMouse(double mouseY) {
        double max = maxScroll();
        int thumbHeight = Math.max(16, (int) ((long) getHeight() * getHeight() / Math.max(1, contentHeight())));
        double fraction = (mouseY - getY() - thumbHeight / 2.0) / Math.max(1, getHeight() - thumbHeight);
        setScrollAmount(fraction * max);
    }

    //? if >1.20.1 {
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (!isMouseOver(mouseX, mouseY) || maxScroll() <= 0) return false;
        targetScroll = clampScroll(targetScroll - scrollY * 28);
        return true;
    }

    /** Losing focus as a whole also unfocuses the text box or button inside. */
    @Override
    public void setFocused(boolean focused) {
        if (!focused) setFocused((GuiEventListener) null);
    }

    /** Scrolls a widget into view when keyboard navigation focuses it. */
    @Override
    public void setFocused(GuiEventListener child) {
        super.setFocused(child);
        if (!(child instanceof AbstractWidget widget)) return;
        int top = 0;
        for (Row row : rows) {
            for (Row.Placed p : row.placed) {
                if (p.widget != widget) continue;
                if (top < targetScroll) targetScroll = top;
                else if (top + row.height() > targetScroll + getHeight()) targetScroll = top + row.height() - getHeight();
                targetScroll = clampScroll(targetScroll);
                return;
            }
            top += row.height() + gap;
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
