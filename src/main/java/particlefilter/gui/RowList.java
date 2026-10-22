package particlefilter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

import java.util.ArrayList;
import java.util.List;

/** Vanilla-style scrolling list whose rows draw some text and hold widgets (buttons, checkboxes) at fixed offsets. */
public class RowList extends ContainerObjectSelectionList<RowList.Row> {

    private final int rowWidth;

    public RowList(Minecraft minecraft, int width, int height, int y, int rowHeight, int rowWidth) {
        super(minecraft, width, height, y, rowHeight);
        this.rowWidth = rowWidth;
    }

    @Override
    public int getRowWidth() {
        return rowWidth;
    }

    @Override
    protected int getScrollbarPosition() {
        return getRowLeft() + rowWidth + 6;
    }

    /** Replaces all rows, keeping the scroll position where possible. */
    public void setRows(List<Row> rows) {
        double scroll = getScrollAmount();
        clearEntries();
        rows.forEach(this::addEntry);
        setScrollAmount(scroll);
    }

    public boolean isEmpty() {
        return children().isEmpty();
    }

    public static class Row extends ContainerObjectSelectionList.Entry<Row> {

        /** Draws a row's text. [left]/[top] are the row's corner; text is usually drawn at top + 6. */
        public interface Painter {
            void paint(GuiGraphics graphics, int left, int top, int width, boolean hovered);
        }

        private record Placed(AbstractWidget widget, int x) {}

        private final Painter painter;
        private final List<Placed> placed = new ArrayList<>();
        private final List<AbstractWidget> widgets = new ArrayList<>();

        public Row(Painter painter) {
            this.painter = painter;
        }

        /** Adds [widget] at [x] pixels from the row's left edge, vertically centered. */
        public Row add(AbstractWidget widget, int x) {
            placed.add(new Placed(widget, x));
            widgets.add(widget);
            return this;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            painter.paint(graphics, left, top, width, hovering);
            for (Placed p : placed) {
                p.widget.setPosition(left + p.x, top + (height - p.widget.getHeight()) / 2);
                p.widget.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}
