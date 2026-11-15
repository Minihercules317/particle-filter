package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import particlefilter.config.PfConfig;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.Toggle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Settings opened with Config in the mod list, kept out of the menu so it stays uncluttered: walking while a
 * Particle Filter screen is open, and lines to zones. In a world it also opens the menu itself.
 */
public class SettingsScreen extends PfScreen {

    /** Inset of the Back button from the window's top left, gap between it and the title, and space below the list. */
    private static final int PAD = 10;
    /** Inset of the divider and the list from the window's left edge (and the divider's from the right). */
    private static final int LIST_X = 12;
    /** Top of the divider under the title, from the window top. */
    private static final int DIVIDER_Y = 31;
    /** Top of the list, from the window top. */
    private static final int LIST_TOP = DIVIDER_Y + 5;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    /** Space above a setting's name, and the same below its description. */
    private static final int CARD_TEXT_Y = 7;
    /** Gap between a setting's text and its switch, and between the switch and the card's right edge. */
    private static final int SWITCH_GAP = 10;
    /** Width of the button that opens the menu. */
    private static final int OPEN_MENU_WIDTH = 140;

    private int wx, wy, ww, wh;
    private int titleX;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("particlefilter.settings.title"), parent);
    }

    @Override
    protected void init() {
        ww = Math.min(width - 16, WIDTH);
        wh = Math.min(height - 16, HEIGHT);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;

        var back = backButton(wx + PAD, wy + PAD);
        titleX = back.getX() + back.getWidth() + PAD;
        addPanel(wx + LIST_X, wy + LIST_TOP, ww - 18, wh - LIST_TOP - PAD, 3);
        int w = panel.contentWidth();
        List<Row> rows = new ArrayList<>();
        rows.add(settingRow("walk", PfConfig.walkInMenu(), PfConfig::setWalkInMenu, w));
        rows.add(settingRow("tracers", PfConfig.zoneTracers(), PfConfig::setZoneTracers, w));
        if (minecraft.level != null) {
            Row open = new Row(BUTTON_HEIGHT + 2 * SECTION_TEXT_Y, (g, x, y, rw, h, hovered) -> {});
            open.add(rowButton(Style.SECONDARY, Component.translatable("particlefilter.settings.open_menu"), OPEN_MENU_WIDTH,
                () -> minecraft.setScreen(new ParticleFilterScreen(this, null))), (w - OPEN_MENU_WIDTH) / 2);
            rows.add(open);
        }
        setRows(rows);
    }

    /**
     * A card for one setting: its name, a description wrapped below it, and a switch on the right. Texts are
     * particlefilter.settings.[key] and [key].description.
     */
    private Row settingRow(String key, boolean value, Consumer<Boolean> onChange, int width) {
        int switchX = width - SWITCH_GAP - Toggle.WIDTH;
        int textWidth = switchX - SWITCH_GAP - CARD_TEXT_X;
        String base = "particlefilter.settings." + key;
        List<FormattedCharSequence> name = wrap(Component.translatable(base), textWidth);
        List<FormattedCharSequence> description = wrap(Component.translatable(base + ".description"), textWidth);
        int height = textHeight(name.size()) + textHeight(description.size()) + LINE / 2 + 2 * CARD_TEXT_Y;
        Row row = new Row(height, (g, x, y, rw, h, hovered) -> {
            Theme.rect(g, x, y, rw, h, CARD_RADIUS, hovered ? Theme.CARD_HOVER : Theme.CARD);
            Theme.dot(g, x + CARD_DOT_X, y + CARD_TEXT_Y + 2, DOT_SIZE, Theme.ACCENT);
            drawLines(g, name, x + CARD_TEXT_X, y + CARD_TEXT_Y, Theme.TEXT);
            drawLines(g, description, x + CARD_TEXT_X, y + CARD_TEXT_Y + textHeight(name.size()) + LINE / 2, Theme.TEXT_DIM);
        });
        row.add(new Toggle(0, 0, value, Component.translatable(base), onChange), switchX);
        return row;
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
