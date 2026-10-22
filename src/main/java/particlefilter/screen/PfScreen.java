package particlefilter.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Base for Particle Filter screens: a translucent center panel instead of the blurred, darkened background
 * (so zone outlines in the world stay visible while editing), Back to the parent screen, and paging helpers.
 */
public abstract class PfScreen extends Screen {

    protected static final int ROW = 24;
    protected static final int PANEL_HALF_WIDTH = 210;

    protected final Screen parent;
    protected int page;

    protected PfScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(width / 2 - PANEL_HALF_WIDTH, 4, width / 2 + PANEL_HALF_WIDTH, height - 4, 0xA0000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
    }

    protected int panelLeft() {
        return width / 2 - PANEL_HALF_WIDTH + 6;
    }

    protected int panelRight() {
        return width / 2 + PANEL_HALF_WIDTH - 6;
    }

    /** Rows that fit between [top] and the pager/bottom buttons. */
    protected int rowsPerPage(int top) {
        return Math.max(1, (height - top - 60) / ROW);
    }

    protected <T> List<T> pageOf(List<T> all, int perPage) {
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        return all.subList(page * perPage, Math.min(all.size(), (page + 1) * perPage));
    }

    /** Prev/Next buttons above the bottom row, only when there is more than one page. */
    protected void addPager(int total, int perPage) {
        int pages = (total + perPage - 1) / perPage;
        if (pages <= 1) return;
        int y = height - 52;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
            .bounds(width / 2 - 70, y, 20, 20).build());
        prev.active = page > 0;
        addRenderableWidget(Button.builder(Component.translatable("particlefilter.page", page + 1, pages), b -> {})
            .bounds(width / 2 - 48, y, 96, 20).build()).active = false;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
            .bounds(width / 2 + 50, y, 20, 20).build());
        next.active = page < pages - 1;
    }

    protected Button button(Component label, int x, int y, int w, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(label, onPress).bounds(x, y, w, 20).build());
    }

    /** Whole-number field; [onValue] runs on every edit that parses. Empty or "-" is left alone until completed. */
    protected EditBox intField(int x, int y, int w, int value, boolean allowNegative, Component tooltip, IntConsumer onValue) {
        EditBox box = new EditBox(font, x, y, w, 20, tooltip);
        box.setMaxLength(7);
        box.setFilter(s -> s.matches(allowNegative ? "-?\\d*" : "\\d*"));
        box.setValue(Integer.toString(value));
        box.setResponder(s -> {
            if (s.matches("-?\\d+")) {
                try {
                    onValue.accept(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    // out of int range; ignore until corrected
                }
            }
        });
        box.setTooltip(Tooltip.create(tooltip));
        return addRenderableWidget(box);
    }

    /** Shortens [text] to fit [maxWidth] pixels, adding "…" when cut. */
    protected String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }
}
