package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
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
    /** Lines the zone's name takes at the top; the list below moves down when it wraps further while typing. */
    private int titleLines;

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

        titleLines = titleLines();
        addPanel(dx + 10, panelTop(), dw - 14, height - panelTop() - 38, 2);
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
        // Side by side when both labels fit, otherwise one above the other at full width.
        Component onMe = Component.translatable("particlefilter.zone.center_on_me");
        Component onTarget = Component.translatable("particlefilter.zone.center_on_crosshair");
        int half = (w - 4) / 2;
        boolean stacked = Math.max(font.width(onMe), font.width(onTarget)) + 6 > half;
        int buttonWidth = stacked ? w : half;
        FlatButton meButton = new FlatButton(Style.SECONDARY, onMe, 0, 0, buttonWidth, 18, () -> {
            if (minecraft.player == null) return;
            BlockPos feet = minecraft.player.blockPosition();
            moveTo(feet.getX(), feet.getY(), feet.getZ());
        }).tooltip(Component.translatable("particlefilter.zone.center_on_me.tooltip"));
        FlatButton targetButton = active(new FlatButton(Style.SECONDARY, onTarget, 0, 0, buttonWidth, 18,
            () -> ParticleFilter.targetedBlock().ifPresent(pos -> moveTo(pos.getX(), pos.getY() - zone.height / 2, pos.getZ())))
            .tooltip(Component.translatable("particlefilter.zone.center_on_crosshair.tooltip")), ParticleFilter.targetedBlock().isPresent());
        if (stacked) {
            rows.add(new Row(22, (g, x, y, rw, h, hovered) -> {}).add(meButton, 0, 3));
            rows.add(new Row(21, (g, x, y, rw, h, hovered) -> {}).add(targetButton, 0, 1));
        } else {
            rows.add(new Row(24, (g, x, y, rw, h, hovered) -> {}).add(meButton, 0).add(targetButton, w - half));
        }

        // Read each frame so they follow the fields below while typing. The volume goes on the right, or below the
        // size when they don't fit on one line.
        rows.add(new Row(() -> {
            List<FormattedCharSequence> lines = sizeLines(w);
            return 6 + textHeight(lines == null ? 1 : lines.size()) + 2;
        }, (g, x, y, rw, h, hovered) -> {
            List<FormattedCharSequence> lines = sizeLines(rw);
            if (lines != null) {
                drawLines(g, lines, x + 2, y + 6, Theme.TEXT_DIM);
                return;
            }
            Theme.text(g, font, sizeLabel(), x + 2, y + 6, Theme.TEXT_FAINT);
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
        for (String particle : listed) {
            rows.add(entryRow(ParticleScan.displayName(particle), particle, particle.contains(":") ? Theme.TYPED : Theme.UNTYPED, false,
                () -> zone.particles.contains(particle), on -> PfConfig.setZoneEntryOn(zone, particle, on),
                () -> PfConfig.setHiddenInZone(zone, particle, false), null));
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

    /** Top of the list, below the zone's name. */
    private int panelTop() {
        return 34 + (titleLines - 1) * LINE;
    }

    private int titleWidth() {
        return width - 10 - titleX - 10;
    }

    private int titleLines() {
        return wrap(zone.name, titleWidth()).size();
    }

    private String sizeLabel() {
        return Theme.caps("particlefilter.zone.size", zone.sizeText());
    }

    /**
     * The size and volume shown above the size fields: null when both fit on one line in [width] px, otherwise the
     * size and then the volume, each wrapped onto lines of their own.
     */
    private List<FormattedCharSequence> sizeLines(int width) {
        String size = sizeLabel();
        String volume = volumeText(zone);
        int room = width - 4;
        if (font.width(size) + 8 + font.width(volume) <= room) return null;
        List<FormattedCharSequence> lines = new ArrayList<>(wrap(colored(size, Theme.TEXT_FAINT), room));
        lines.addAll(wrap(colored(volume, Theme.TEXT_DIM), room));
        return lines;
    }

    /** "220 blocks affected". */
    public static String volumeText(Zone zone) {
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
        // Label and hint wrap left of the spinner; the row grows to fit them.
        int room = w - SPINNER_WIDTH - 8;
        Supplier<List<FormattedCharSequence>> lines = () -> {
            Component text = colored(label.getString(), Theme.TEXT_DIM);
            if (hint != null) text = Component.empty().append(text).append(" ").append(colored(hint.get(), Theme.TEXT_FAINT));
            return wrap(text, room);
        };
        Row row = new Row(() -> Math.max(FIELD_ROW, textHeight(lines.get().size()) + 14), (g, x, y, rw, h, hovered) -> {
            List<FormattedCharSequence> shown = lines.get();
            drawLines(g, shown, x + 2, y + (h - textHeight(shown.size())) / 2, Theme.TEXT_DIM);
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
        List<FormattedCharSequence> name = wrap(zone.name, titleWidth());
        drawLines(g, name, titleX + 10, y, color);
        if (name.size() != titleLines) { // the name was edited onto more or fewer lines
            titleLines = name.size();
            panel.setBounds(panelTop(), height - panelTop() - 38);
        }
    }
}
