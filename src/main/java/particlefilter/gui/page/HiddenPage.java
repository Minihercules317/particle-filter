package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import particlefilter.config.FilterMode;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.PfScreen.Piece;
import particlefilter.gui.Theme;
import particlefilter.gui.ZoneEditScreen;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.TextField;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static particlefilter.gui.PfScreen.LINE;
import static particlefilter.gui.PfScreen.textHeight;

/** Everything hidden and where, each entry with an on/off switch and Delete, and a box to hide by id. */
public class HiddenPage extends Page {

    private TextField idInput;
    private String idInputText = "";
    /** Zone whose Delete button was pressed once and now waits for confirmation. */
    private Zone confirmingDelete;

    public HiddenPage(ParticleFilterScreen screen) {
        super(screen);
    }

    @Override
    public Component title() {
        return Component.translatable("particlefilter.hidden.title");
    }

    @Override
    public Component subtitle() {
        return Component.translatable("particlefilter.hidden.subtitle");
    }

    @Override
    public void shown() {
        confirmingDelete = null;
    }

    @Override
    public void init() {
        int cx = screen.cx(), cw = screen.cw(), bodyTop = screen.bodyTop(), bodyBottom = screen.bodyBottom();
        int buttonWidth = 80;
        idInput = screen.addRenderableWidget(new TextField(screen.font(), cx, bodyTop, cw - 2 * buttonWidth - 8, 20, Component.translatable("particlefilter.hidden.input")));
        idInput.setMaxLength(256);
        idInput.setHint(Component.translatable("particlefilter.hidden.input_hint").withStyle(s -> s.withColor(Theme.TEXT_FAINT & 0xFFFFFF)));
        idInput.setValue(idInputText);
        idInput.setResponder(s -> idInputText = s);
        screen.button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_particle"), cx + cw - 2 * buttonWidth - 4, bodyTop, buttonWidth, 20,
            () -> addTyped(false)).tooltip(Component.translatable("particlefilter.hidden.add_particle.tooltip"));
        screen.button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_block"), cx + cw - buttonWidth, bodyTop, buttonWidth, 20,
            () -> addTyped(true)).tooltip(Component.translatable("particlefilter.hidden.add_block.tooltip"));

        screen.addPanel(cx, bodyTop + 28, cw, bodyBottom - bodyTop - 28, 2);
        screen.setRows(hiddenRows());
    }

    private void addTyped(boolean block) {
        String id = idInputText.trim();
        if (id.isEmpty()) return;
        if (block) PfConfig.setBlockHidden(id, true);
        else PfConfig.setHiddenEverywhere(id, true);
        idInputText = "";
        screen.refresh();
    }

    /** Enter in the id box hides that particle. */
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (idInput != null && idInput.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            addTyped(false);
            return true;
        }
        return false;
    }

    /** Switched-on entries first, then switched-off ones, each in the order they were added. */
    private static List<String> listed(List<String> on, List<String> off) {
        Set<String> all = new LinkedHashSet<>(on);
        all.addAll(off);
        return List.copyOf(all);
    }

    private List<Row> hiddenRows() {
        List<Row> rows = new ArrayList<>();
        FilterMode mode = PfConfig.mode();
        Component pausedTip = Component.translatable("particlefilter.paused.tooltip", Component.translatable(mode.key()));

        List<String> everywhere = listed(PfConfig.hiddenParticles(), PfConfig.disabledParticles());
        if (!everywhere.isEmpty()) {
            rows.add(section(Theme.caps("particlefilter.hidden.section_everywhere", everywhere.size()), !mode.global, pausedTip));
            for (String p : everywhere) {
                rows.add(screen.entryRow(ParticleScan.displayName(p), p, particleColor(p), !mode.global,
                    () -> PfConfig.isHiddenEverywhere(p), on -> PfConfig.setEverywhereOn(p, on), () -> PfConfig.setHiddenEverywhere(p, false), null));
            }
        }

        List<Zone> zones = new ArrayList<>(PfConfig.zones());
        zones.removeIf(z -> !z.hasEntries());
        zones.sort(Comparator.comparing(z -> !PfConfig.isInCurrentWorld(z))); // this world's zones first
        for (Zone zone : zones) {
            boolean here = PfConfig.isInCurrentWorld(zone);
            rows.add(zoneSection(zone, here, !mode.zones, pausedTip));
            for (String p : listed(zone.particles, zone.disabledParticles)) {
                rows.add(screen.entryRow(ParticleScan.displayName(p), p, particleColor(p), !mode.zones,
                    () -> zone.particles.contains(p), on -> PfConfig.setZoneEntryOn(zone, p, on), () -> PfConfig.setHiddenInZone(zone, p, false),
                    here ? zone : null));
            }
        }

        List<String> blocks = listed(PfConfig.hiddenBlocks(), PfConfig.disabledBlocks());
        if (!blocks.isEmpty()) {
            rows.add(section(Theme.caps("particlefilter.hidden.section_blocks", blocks.size()), !mode.global, pausedTip));
            for (String b : blocks) {
                rows.add(screen.entryRow(b, null, Theme.BLOCK, !mode.global,
                    () -> PfConfig.hasBlockEntry(b), on -> PfConfig.setBlockOn(b, on), () -> PfConfig.setBlockHidden(b, false), null));
            }
        }
        return rows;
    }

    private static int particleColor(String particle) {
        return particle.contains(":") ? Theme.TYPED : Theme.UNTYPED;
    }

    /** Section label, with a Paused pill while Active rules leaves this kind of rule out. */
    private Row section(String label, boolean paused, Component pausedTip) {
        var font = screen.font();
        String pausedText = Theme.caps("particlefilter.paused");
        var shown = screen.wrap(label, screen.panel().contentWidth() - 4 - (paused ? font.width(pausedText) + 14 : 0));
        int top = 8;
        int lastY = top + (shown.size() - 1) * LINE;
        int lastWidth = font.width(shown.get(shown.size() - 1));
        Row row = new Row(top + textHeight(shown.size()) + 6, (g, x, y, w, h, hovered) -> {
            screen.drawLines(g, shown, x + 2, y + top, Theme.TEXT_FAINT);
            if (paused) Theme.pill(g, font, pausedText, x + 8 + lastWidth, y + lastY - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
        });
        if (paused) row.tooltip(pausedTip);
        return row;
    }

    /** A zone's heading: name in its outline color, its coordinates, and Edit (this world only) and Delete zone. */
    private Row zoneSection(Zone zone, boolean here, boolean paused, Component pausedTip) {
        var font = screen.font();
        int w = screen.panel().contentWidth();
        String pausedText = Theme.caps("particlefilter.paused");
        int buttons = (here ? 40 : 0) + 74;
        int color = Theme.zoneColor(zone);
        int nameColor = here ? color : Theme.fade(color, 0.6f);
        // "ZONE · name · x, y, z", wrapped left of the buttons; the Paused pill follows the last line.
        int room = w - 14 - buttons - (paused ? font.width(pausedText) + 14 : 0);
        var shown = screen.wrapPieces(List.of(
            new Piece(Theme.caps(here ? "particlefilter.hidden.section_zone" : "particlefilter.hidden.section_zone_elsewhere"), nameColor),
            new Piece(zone.name, nameColor),
            new Piece(zone.coordsText(), Theme.TEXT_FAINT)), Theme.TEXT_FAINT, room);
        int top = 8;
        int lastY = top + (shown.size() - 1) * LINE;
        int lastWidth = font.width(shown.get(shown.size() - 1));
        Row row = new Row(top + textHeight(shown.size()) + 8, (g, x, y, rw, h, hovered) -> {
            Theme.dot(g, x + 2, y + top + 1, 5, color);
            screen.drawLines(g, shown, x + 12, y + top, Theme.TEXT_FAINT);
            if (paused) Theme.pill(g, font, pausedText, x + 12 + lastWidth + 8, y + lastY - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
            if (hovered && here) ZoneRenderer.highlighted = zone;
        });
        Component where = Component.translatable("particlefilter.hidden.zone_where", zone.coordsText(), zone.sizeText(),
            ZoneEditScreen.volumeText(zone), zone.world, zone.dimension);
        row.tooltip(paused ? Component.empty().append(where).append("\n").append(pausedTip) : where);
        if (here) {
            row.add(screen.rowButton(Style.GHOST, Component.translatable("particlefilter.edit"), 36,
                () -> screen.minecraft().setScreen(new ZoneEditScreen(screen, zone))), w - buttons, 4);
        }
        boolean confirming = zone == confirmingDelete;
        row.add(screen.rowButton(Style.DANGER, Component.translatable(confirming ? "particlefilter.confirm" : "particlefilter.zone.delete"), 70, () -> {
            if (confirmingDelete == zone) {
                PfConfig.deleteZone(zone);
                confirmingDelete = null;
            } else {
                confirmingDelete = zone;
            }
            screen.refreshNextTick();
        }).selected(confirming).tooltip(Component.translatable("particlefilter.zone.delete.tooltip")), w - 72, 4);
        return row;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (screen.panel().isEmpty()) {
            screen.drawEmptyState(g, Component.translatable("particlefilter.hidden.empty"), Component.translatable("particlefilter.hidden.empty.body"),
                screen.cx() + screen.cw() / 2, (screen.panel().getY() + screen.bodyBottom()) / 2 - 20, screen.cw());
        }
    }
}
