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
import particlefilter.gui.widget.Toggle;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Edits one zone in a drawer on the right, leaving the world visible so the zone's outline can be watched while it
 * moves and resizes: name, position (center X/Z, bottom Y), size (Side 1 along X, height, Side 2 along Z, each side
 * labelled with the way it runs from where the player faces), and the particles it hides. Changes apply and save
 * immediately.
 */
public class ZoneEditScreen extends PfScreen {

    private static final int COORD_LIMIT = 30_000_000;
    private static final int FIELD_ROW = 22;

    private final Zone zone;
    private boolean confirmingDelete;
    private int dx, dw;
    private int titleX;

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

        FlatButton back = backButton(dx + 10, 9);
        titleX = back.getX() + back.getWidth() + 8;

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
        rows.add(fieldRow("particlefilter.zone.center_x", null, zone.x, -COORD_LIMIT, COORD_LIMIT, v -> zone.x = v));
        rows.add(fieldRow("particlefilter.zone.bottom_y", null, zone.y, -COORD_LIMIT, COORD_LIMIT, v -> zone.y = v));
        rows.add(fieldRow("particlefilter.zone.center_z", null, zone.z, -COORD_LIMIT, COORD_LIMIT, v -> zone.z = v));
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

        // Read each frame so they follow the fields below while typing.
        rows.add(new Row(16, (g, x, y, rw, h, hovered) -> {
            Theme.text(g, font, Theme.caps("particlefilter.zone.size", zone.sizeText()), x + 2, y + 6, Theme.TEXT_FAINT);
            String volume = volumeText(zone);
            Theme.text(g, font, volume, x + rw - 2 - font.width(volume), y + 6, Theme.TEXT_DIM);
        }));
        rows.add(fieldRow("particlefilter.zone.side1", () -> direction(true), zone.width, Zone.MIN_SIZE, Zone.MAX_SIZE, v -> zone.width = v));
        rows.add(fieldRow("particlefilter.zone.height", null, zone.height, Zone.MIN_SIZE, Zone.MAX_SIZE, v -> zone.height = v));
        rows.add(fieldRow("particlefilter.zone.side2", () -> direction(false), zone.length, Zone.MIN_SIZE, Zone.MAX_SIZE, v -> zone.length = v));

        rows.add(sectionRow(Theme.caps("particlefilter.zone.hides", zone.particles.size() + zone.disabledParticles.size())));
        if (!zone.hasEntries()) {
            rows.add(hintRow(Component.translatable("particlefilter.zone.hides_nothing"), w));
        }
        Set<String> listed = new LinkedHashSet<>(zone.particles);
        listed.addAll(zone.disabledParticles);
        int switchX = w - 44 - 2 - 8 - Toggle.WIDTH;
        for (String particle : listed) {
            String shown = Theme.fit(font, ParticleScan.displayName(particle), switchX - 19 - 8);
            int color = particle.contains(":") ? Theme.TYPED : Theme.UNTYPED;
            Row row = new Row(20, (g, x, y, rw, h, hovered) -> {
                boolean on = zone.particles.contains(particle);
                Theme.rect(g, x, y, rw, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
                Theme.dot(g, x + 8, y + (h - 5) / 2, 5, on ? color : Theme.fade(color, 0.35f));
                Theme.text(g, font, shown, x + 19, y + (h - 8) / 2, on ? Theme.TEXT : Theme.TEXT_FAINT);
            });
            if (!particle.equals(ParticleScan.displayName(particle))) row.tooltip(Component.literal(particle));
            row.add(new Toggle(0, 0, zone.particles.contains(particle), Component.literal(shown), on -> PfConfig.setZoneEntryOn(zone, particle, on))
                .tooltip(Component.translatable("particlefilter.entry.switch.tooltip")), switchX);
            row.add(rowButton(Style.GHOST, Component.translatable("particlefilter.delete"), 44, () -> {
                PfConfig.setHiddenInZone(zone, particle, false);
                refreshNextTick();
            }).tooltip(Component.translatable("particlefilter.entry.delete.tooltip")), w - 46);
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
            }).selected(confirmingDelete).tooltip(Component.translatable("particlefilter.zone.delete.tooltip"));
        button(Style.PRIMARY, Component.translatable("gui.done"), dx + dw - 10 - 72, footerY, 72, 20, this::onClose);
    }

    /** "220 blocks affected". */
    static String volumeText(Zone zone) {
        return Component.translatable(zone.volume() == 1 ? "particlefilter.zone.volume_one" : "particlefilter.zone.volume",
            Zone.formatCount(zone.volume())).getString();
    }

    /**
     * Which way Side 1 (X, [alongX]) or Side 2 (Z) runs from where the player faces: left–right or front–back.
     * Facing diagonally counts as facing the nearer axis.
     */
    private String direction(boolean alongX) {
        if (minecraft.player == null) return "";
        double yaw = Math.toRadians(minecraft.player.getYRot());
        boolean facingAlongZ = Math.abs(Math.cos(yaw)) >= Math.abs(Math.sin(yaw));
        boolean leftRight = alongX == facingAlongZ;
        return Component.translatable(leftRight ? "particlefilter.zone.dir.left_right" : "particlefilter.zone.dir.front_back").getString();
    }

    /**
     * A labelled number field with − and + buttons; [field] receives valid values and the zone is saved. [hint], if
     * given, is drawn faintly after the label and read every frame.
     */
    private Row fieldRow(String key, Supplier<String> hint, int value, int min, int max, IntConsumer field) {
        int w = panel.contentWidth();
        Component label = Component.translatable(key);
        Component tooltip = Component.translatable(key + ".tooltip", Zone.MIN_SIZE, Zone.MAX_SIZE);
        Row row = new Row(FIELD_ROW, (g, x, y, rw, h, hovered) -> {
            int room = rw - SPINNER_WIDTH - 8;
            String text = Theme.fit(font, label.getString(), room);
            int textY = y + (h - 8) / 2;
            Theme.text(g, font, text, x + 2, textY, Theme.TEXT_DIM);
            if (hint != null) {
                int hintX = x + 2 + font.width(text) + 5;
                Theme.text(g, font, Theme.fit(font, hint.get(), x + 2 + room - hintX), hintX, textY, Theme.TEXT_FAINT);
            }
        });
        row.tooltip(tooltip);
        spinner(row, w - SPINNER_WIDTH, value, min, max, tooltip, v -> {
            field.accept(v);
            ParticleScan.stopFitting(zone); // sized by hand now
            PfConfig.changed();
        });
        return row;
    }

    private void moveTo(int x, int y, int z) {
        zone.x = x;
        zone.y = y;
        zone.z = z;
        ParticleScan.stopFitting(zone);
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
        int y = 13;
        int color = Theme.zoneColor(zone);
        Theme.dot(g, titleX, y + 1, 6, color);
        Theme.text(g, font, Theme.fit(font, zone.name, width - 10 - titleX - 10), titleX + 10, y, color);
    }
}
