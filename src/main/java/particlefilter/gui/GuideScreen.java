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

    private int wx, wy, ww, wh;

    public GuideScreen(Screen parent) {
        super(Component.translatable("particlefilter.guide.title"), parent);
    }

    @Override
    protected void init() {
        ww = Math.min(width - 16, 380);
        wh = Math.min(height - 16, 320);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;

        backButton(wx + 10, wy + 10);
        addPanel(wx + 12, wy + 36, ww - 18, wh - 46, 2);
        int w = panel.contentWidth();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < SECTIONS.length; i++) {
            String key = "particlefilter.guide." + SECTIONS[i];
            String heading = (i + 1) + ". " + Component.translatable(key + ".title").getString();
            rows.add(new Row(16, (g, x, y, rw, h, hovered) -> Theme.text(g, font, heading, x + 4, y + 6, Theme.ACCENT)));
            rows.add(hintRow(Component.translatable(key + ".body"), w));
        }
        setRows(rows);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Theme.SCRIM);
        Theme.rect(g, wx, wy, ww, wh, 6, Theme.WINDOW);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int titleX = wx + 10 + font.width(Component.translatable("particlefilter.back_button")) + 14 + 10;
        Theme.text(g, font, title, titleX, wy + 14, Theme.TEXT);
        g.fill(wx + 12, wy + 31, wx + ww - 12, wy + 32, Theme.DIVIDER);
    }
}
