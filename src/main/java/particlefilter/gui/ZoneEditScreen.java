package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import particlefilter.ParticleFilter;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.TextField;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Edits one zone in a drawer on the right, leaving the world visible so the zone's yellow outline can be watched
 * while it moves and resizes: name, position (center X/Z, bottom Y), size (width/height/length), and the particles
 * it hides. Changes apply and save immediately.
 */
public class ZoneEditScreen extends PfScreen {

    private static final int COORD_LIMIT = 30_000_000;
    private static final int FIELD_ROW = 22;

    private final Zone zone;
    private boolean confirmingDelete;
    private int dx, dw;

    public ZoneEditScreen(Screen parent, Zone zone) {
        super(Component.translatable("particlefilter.zone.title"), parent);
        this.zone = zone;
    }

    @Override
    protected void init() {
        if (zone == null) {
            onClose();
            return;
        }
        dw = Math.min(width - 24, 236);
        dx = width - dw;

        button(Style.GHOST, Component.literal("←"), dx + 8, 9, 16, 16, this::onClose).tooltip(Component.translatable("particlefilter.back"));

        addPanel(dx + 10, 34, dw - 14, height - 34 - 38, 2);
        int w = panel.contentWidth();
        List<Row> rows = new ArrayList<>();

        rows.add(sectionRow(Theme.caps("particlefilter.zone.name")));
        TextField name = new TextField(font, 0, 0, w, 18, Component.translatable("particlefilter.zone.name"));
        name.setMaxLength(48);
        name.setValue(zone.name);
        name.setResponder(s -> {
            zone.name = s;
            PfConfig.changed();
        });
        rows.add(new Row(FIELD_ROW, (g, x, y, rw, h, hovered) -> {}).add(name, 0));

        rows.add(sectionRow(Theme.caps("particlefilter.zone.position")));
        rows.add(fieldRow("particlefilter.zone.center_x", zone.x, -COORD_LIMIT, COORD_LIMIT, v -> zone.x = v));
        rows.add(fieldRow("particlefilter.zone.bottom_y", zone.y, -COORD_LIMIT, COORD_LIMIT, v -> zone.y = v));
        rows.add(fieldRow("particlefilter.zone.center_z", zone.z, -COORD_LIMIT, COORD_LIMIT, v -> zone.z = v));
        int half = (w - 4) / 2;
        Row moveRow = new Row(24, (g, x, y, rw, h, hovered) -> {});
        moveRow.add(new FlatButton(Style.SECONDARY, Component.translatable("particlefilter.zone.center_on_me"), 0, 0, half, 18, () -> {
            if (minecraft.player == null) return;
            BlockPos feet = minecraft.player.blockPosition();
            moveTo(feet.getX(), feet.getY(), feet.getZ());
        }).tooltip(Component.translatable("particlefilter.zone.center_on_me.tooltip")), 0);
        moveRow.add(active(new FlatButton(Style.SECONDARY, Component.translatable("particlefilter.zone.center_on_crosshair"), 0, 0, half, 18,
            () -> ParticleFilter.targetedBlock().ifPresent(pos -> moveTo(pos.getX(), pos.getY() - zone.height / 2, pos.getZ())))
            .tooltip(Component.translatable("particlefilter.zone.center_on_crosshair.tooltip")), ParticleFilter.targetedBlock().isPresent()), w - half);
        rows.add(moveRow);

        // Reads the size each frame so it follows the fields below while typing.
        rows.add(new Row(16, (g, x, y, rw, h, hovered) ->
            Theme.text(g, font, Theme.caps("particlefilter.zone.size", zone.sizeText()), x + 2, y + 6, Theme.TEXT_FAINT)));
        rows.add(fieldRow("particlefilter.zone.width", zone.width, Zone.MIN_SIZE, Zone.MAX_SIZE, v -> zone.width = v));
        rows.add(fieldRow("particlefilter.zone.height", zone.height, Zone.MIN_SIZE, Zone.MAX_SIZE, v -> zone.height = v));
        rows.add(fieldRow("particlefilter.zone.length", zone.length, Zone.MIN_SIZE, Zone.MAX_SIZE, v -> zone.length = v));

        rows.add(sectionRow(Theme.caps("particlefilter.zone.hides", zone.particles.size())));
        if (zone.particles.isEmpty()) {
            rows.add(hintRow(Component.translatable("particlefilter.zone.hides_nothing"), w));
        }
        for (String particle : List.copyOf(zone.particles)) {
            String shown = Theme.fit(font, ParticleScan.displayName(particle), w - 76);
            int color = particle.contains(":") ? Theme.TYPED : Theme.UNTYPED;
            Row row = new Row(20, (g, x, y, rw, h, hovered) -> {
                Theme.rect(g, x, y, rw, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
                Theme.dot(g, x + 8, y + (h - 5) / 2, 5, color);
                Theme.text(g, font, shown, x + 19, y + (h - 8) / 2, Theme.TEXT);
            });
            if (!particle.equals(ParticleScan.displayName(particle))) row.tooltip(Component.literal(particle));
            row.add(rowButton(Style.GHOST, Component.translatable("particlefilter.unhide"), 52, () -> {
                PfConfig.setHiddenInZone(zone, particle, false);
                refreshNextTick();
            }), w - 54);
            rows.add(row);
        }
        setRows(rows);

        int footerY = height - 28;
        button(Style.DANGER, Component.translatable(confirmingDelete ? "particlefilter.zone.delete_confirm" : "particlefilter.zone.delete"),
            dx + 10, footerY, 104, 20, () -> {
                if (confirmingDelete) {
                    PfConfig.deleteZone(zone);
                    onClose();
                } else {
                    confirmingDelete = true;
                    refresh();
                }
            }).selected(confirmingDelete);
        button(Style.PRIMARY, Component.translatable("gui.done"), dx + dw - 10 - 72, footerY, 72, 20, this::onClose);
    }

    /** A labelled number field with − and + buttons; [field] receives valid values and the zone is saved. */
    private Row fieldRow(String key, int value, int min, int max, IntConsumer field) {
        int w = panel.contentWidth();
        Component label = Component.translatable(key);
        Row row = new Row(FIELD_ROW, (g, x, y, rw, h, hovered) ->
            Theme.text(g, font, Theme.fit(font, label.getString(), rw - SPINNER_WIDTH - 8), x + 2, y + (h - 8) / 2, Theme.TEXT_DIM));
        row.tooltip(Component.translatable(key + ".tooltip", Zone.MIN_SIZE, Zone.MAX_SIZE));
        spinner(row, w - SPINNER_WIDTH, value, min, max, Component.translatable(key + ".tooltip", Zone.MIN_SIZE, Zone.MAX_SIZE), v -> {
            field.accept(v);
            PfConfig.changed();
        });
        return row;
    }

    private void moveTo(int x, int y, int z) {
        zone.x = x;
        zone.y = y;
        zone.z = z;
        PfConfig.changed();
        refreshNextTick(); // show the new values in the fields
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(dx, 0, width, height, Theme.WINDOW);
        g.fill(dx, 0, dx + 1, height, Theme.DIVIDER);
        g.fill(dx + 10, height - 36, width - 10, height - 35, Theme.DIVIDER);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ZoneRenderer.highlighted = zone;
        super.render(g, mouseX, mouseY, partialTick);
        if (zone == null) return;
        Theme.text(g, font, title, dx + 30, 13, Theme.TEXT);
        String name = Theme.fit(font, zone.name, dw - 40 - font.width(title) - 8);
        Theme.text(g, font, name, dx + 30 + font.width(title) + 6, 13, Theme.ZONE);
    }
}
