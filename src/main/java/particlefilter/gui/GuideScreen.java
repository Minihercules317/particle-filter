package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import particlefilter.gui.widget.ScrollPanel.Row;

import java.util.ArrayList;
import java.util.List;

/** A short quick start guide, opened with the ? button: what each page is for and how hiding works. */
public class GuideScreen extends PfScreen {

    /** Sections in order; each has a ".title" and a ".body" translation under particlefilter.guide. */
    private static final String[] SECTIONS = {"scan", "hide", "diagnostic", "hidden", "zones", "rules", "keys"};
    /** Inset of the Back button from the window's top left, gap between it and the title, and space below the list. */
    private static final int PAD = 10;
    /** Inset of the divider and the list from the window's left edge (and the divider's from the right). */
    private static final int LIST_X = 12;
    /** Top of the divider under the title, from the window top. */
    private static final int DIVIDER_Y = 31;
    /** Top of the list, from the window top. */
    private static final int LIST_TOP = DIVIDER_Y + 5;

    private int wx, wy, ww, wh;
    private int titleX;

    public GuideScreen(Screen parent) {
        super(Component.translatable("particlefilter.guide.title"), parent);
    }

    @Override
    protected void init() {
        ww = Math.min(width - 16, 380);
        wh = Math.min(height - 16, 320);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;

        var back = backButton(wx + PAD, wy + PAD);
        titleX = back.getX() + back.getWidth() + PAD;
        addPanel(wx + LIST_X, wy + LIST_TOP, ww - 18, wh - LIST_TOP - PAD, 2);
        int w = panel.contentWidth();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < SECTIONS.length; i++) {
            String key = "particlefilter.guide." + SECTIONS[i];
            String heading = (i + 1) + ". " + Component.translatable(key + ".title").getString();
            // Styled like a section label, but lined up with the hint text below it.
            rows.add(new Row(SECTION_HEIGHT, (g, x, y, rw, h, hovered) -> Theme.text(g, font, heading, x + HINT_TEXT_X, y + SECTION_TEXT_Y, Theme.ACCENT)));
            rows.add(hintRow(Component.translatable(key + ".body"), w));
        }
        setRows(rows);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Theme.SCRIM);
        Theme.rect(g, wx, wy, ww, wh, WINDOW_RADIUS, Theme.WINDOW);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // Centered on the Back button.
        Theme.text(g, font, title, titleX, wy + PAD + (SMALL_BUTTON_HEIGHT - textHeight(1)) / 2, Theme.TEXT);
        g.fill(wx + LIST_X, wy + DIVIDER_Y, wx + ww - LIST_X, wy + DIVIDER_Y + 1, Theme.DIVIDER);
    }
}
