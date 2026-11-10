package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.PfScreen.Piece;
import particlefilter.gui.Theme;
import particlefilter.gui.ZoneEditScreen;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.Toggle;
import particlefilter.render.ZoneRenderer;

import java.util.ArrayList;
import java.util.List;

import static particlefilter.gui.PfScreen.CARD_RADIUS;
import static particlefilter.gui.PfScreen.LINE;
import static particlefilter.gui.PfScreen.textHeight;

/** The zones in this world, to edit, delete or create. */
public class ZonesPage extends Page {

    /** Width of the New zone button in the header. */
    private static final int NEW_ZONE_WIDTH = 96;
    /** Width of a zone card's Edit button. */
    private static final int EDIT_WIDTH = 44;
    /** Width of a zone card's Delete button. */
    private static final int DELETE_WIDTH = 56;
    /** Gap between Edit and Delete, and between Delete and the card's right edge. */
    private static final int BUTTON_GAP = 4;
    /** Gap between a zone card's text and its Edit button. */
    private static final int TEXT_GAP = 8;
    /** Left inset of a zone card's text, past the color stripe. */
    private static final int TEXT_X = 10;
    /** Space above a zone card's name, and the same below its details. */
    private static final int TEXT_Y = 6;

    /** Zone whose Delete button was pressed once and now waits for confirmation. */
    private Zone confirmingDelete;

    public ZonesPage(ParticleFilterScreen screen) {
        super(screen);
    }

    @Override
    public Component title() {
        return Component.translatable("particlefilter.zones.title");
    }

    @Override
    public Component subtitle() {
        return Component.translatable("particlefilter.zones.subtitle");
    }

    @Override
    public int actionsWidth() {
        return NEW_ZONE_WIDTH;
    }

    @Override
    public void shown() {
        confirmingDelete = null;
    }

    @Override
    public void init() {
        int cx = screen.cx(), cw = screen.cw(), bodyTop = screen.bodyTop(), bodyBottom = screen.bodyBottom();
        var minecraft = screen.minecraft();
        screen.headerButton(Style.PRIMARY, Component.translatable("particlefilter.zones.new"), () -> {
            if (minecraft.player == null) return;
            Zone zone = PfConfig.createZone(minecraft.player.blockPosition().above(PfConfig.NEW_ZONE_DEPTH_BELOW));
            if (zone != null) minecraft.setScreen(new ZoneEditScreen(screen, zone));
        }).tooltip(Component.translatable("particlefilter.zones.new.tooltip"));

        screen.addPanel(cx, bodyTop, cw, bodyBottom - bodyTop - LIST_FOOTER, 3);
        List<Row> rows = new ArrayList<>();
        int w = screen.panel().contentWidth();
        int deleteX = w - BUTTON_GAP - DELETE_WIDTH;
        int editX = deleteX - BUTTON_GAP - EDIT_WIDTH;
        // Name and details wrap in the space left of the buttons; the buttons stay centered as the row grows.
        int textWidth = editX - TEXT_GAP - TEXT_X;
        for (Zone zone : PfConfig.zonesHere()) {
            var name = screen.wrap(zone.name, textWidth);
            var details = screen.wrapPieces(List.of(
                new Piece(zone.sizeText(), Theme.TEXT_DIM),
                new Piece(ZoneEditScreen.volumeText(zone), Theme.TEXT_DIM),
                new Piece(zone.coordsText(), Theme.TEXT_DIM),
                new Piece(Component.translatable("particlefilter.zones.details_hidden", zone.particles.size()).getString(), Theme.TEXT_DIM)),
                Theme.TEXT_DIM, textWidth);
            int detailsY = TEXT_Y + name.size() * LINE + 2;
            int color = Theme.zoneColor(zone);
            Row row = new Row(detailsY + textHeight(details.size()) + TEXT_Y, (g, x, y, rw, h, hovered) -> {
                Theme.rect(g, x, y, rw, h, CARD_RADIUS, hovered ? Theme.CARD_HOVER : Theme.CARD);
                Theme.rect(g, x, y + 5, 3, h - 10, 1, color);
                screen.drawLines(g, name, x + TEXT_X, y + TEXT_Y, Theme.TEXT);
                screen.drawLines(g, details, x + TEXT_X, y + detailsY, Theme.TEXT_DIM);
                if (hovered) ZoneRenderer.highlighted = zone;
            });
            row.tooltip(Component.translatable("particlefilter.zones.row.tooltip", zone.sizeText(), ZoneEditScreen.volumeText(zone),
                zone.coordsText(), zone.particles.size()));
            row.add(screen.rowButton(Style.SECONDARY, Component.translatable("particlefilter.edit"), EDIT_WIDTH,
                () -> minecraft.setScreen(new ZoneEditScreen(screen, zone))), editX);
            boolean confirming = zone == confirmingDelete;
            row.add(screen.rowButton(Style.DANGER, Component.translatable(confirming ? "particlefilter.confirm" : "particlefilter.delete"), DELETE_WIDTH, () -> {
                if (confirmingDelete == zone) {
                    PfConfig.deleteZone(zone);
                    confirmingDelete = null;
                } else {
                    confirmingDelete = zone;
                }
                screen.refreshNextTick();
            }).selected(confirming), deleteX);
            rows.add(row);
        }
        screen.setRows(rows);

        screen.addRenderableWidget(Toggle.labelled(cx, bodyBottom - FOOTER_LINE, ZoneRenderer.alwaysShow, Component.translatable("particlefilter.zones.always_show"),
            on -> ZoneRenderer.alwaysShow = on)).tooltip(Component.translatable("particlefilter.zones.always_show.tooltip"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        int cx = screen.cx(), cw = screen.cw();
        if (screen.panel().isEmpty()) {
            screen.drawEmptyState(g, Component.translatable("particlefilter.zones.empty"), Component.translatable("particlefilter.zones.empty.body"),
                cx + cw / 2, emptyStateTop(screen.bodyTop(), screen.bodyBottom()), cw);
        }
    }
}
