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

import static particlefilter.gui.PfScreen.BUTTON_HEIGHT;
import static particlefilter.gui.PfScreen.DOT_SIZE;
import static particlefilter.gui.PfScreen.LINE;
import static particlefilter.gui.PfScreen.SMALL_BUTTON_HEIGHT;
import static particlefilter.gui.PfScreen.textHeight;

/** Everything hidden and where, each entry with an on/off switch and Delete, and a box to hide by id. */
public class HiddenPage extends Page {

    /** Width of the Hide particle and Hide block buttons right of the id box. */
    private static final int ADD_BUTTON_WIDTH = 80;
    /** Gap between the id box and each of those buttons. */
    private static final int BUTTON_GAP = 4;
    /** Space between the id box and the list below it. */
    private static final int LIST_GAP = 8;
    /** Space above a section heading's text. */
    private static final int SECTION_TOP = 8;
    /** Width of a zone heading's Edit button. */
    private static final int ZONE_EDIT_WIDTH = 36;
    /** Width of a zone heading's Delete zone button. */
    private static final int ZONE_DELETE_WIDTH = 70;

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
        int blockX = cx + cw - ADD_BUTTON_WIDTH;
        int particleX = blockX - BUTTON_GAP - ADD_BUTTON_WIDTH;
        idInput = screen.addRenderableWidget(new TextField(screen.font(), cx, bodyTop, particleX - BUTTON_GAP - cx, BUTTON_HEIGHT, Component.translatable("particlefilter.hidden.input")));
        idInput.setMaxLength(256);
        idInput.setHint(Component.translatable("particlefilter.hidden.input_hint").withStyle(s -> s.withColor(Theme.TEXT_FAINT & 0xFFFFFF)));
        idInput.setValue(idInputText);
        idInput.setResponder(s -> idInputText = s);
        screen.button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_particle"), particleX, bodyTop, ADD_BUTTON_WIDTH, BUTTON_HEIGHT,
            () -> addTyped(false)).tooltip(Component.translatable("particlefilter.hidden.add_particle.tooltip"));
        screen.button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_block"), blockX, bodyTop, ADD_BUTTON_WIDTH, BUTTON_HEIGHT,
            () -> addTyped(true)).tooltip(Component.translatable("particlefilter.hidden.add_block.tooltip"));

        int listTop = bodyTop + BUTTON_HEIGHT + LIST_GAP;
        screen.addPanel(cx, listTop, cw, bodyBottom - listTop, 2);
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

    /** Room the Paused pill takes after a heading's last line, counting the gap before it. */
    private int pausedRoom(String pausedText) {
        return screen.font().width(pausedText) + 14;
    }

    /** Section label, with a Paused pill while Active rules leaves this kind of rule out. */
    private Row section(String label, boolean paused, Component pausedTip) {
        var font = screen.font();
        String pausedText = Theme.caps("particlefilter.paused");
        var shown = screen.wrap(label, screen.panel().contentWidth() - 4 - (paused ? pausedRoom(pausedText) : 0));
        int lastY = SECTION_TOP + (shown.size() - 1) * LINE;
        int lastWidth = font.width(shown.get(shown.size() - 1));
        Row row = new Row(SECTION_TOP + textHeight(shown.size()) + 6, (g, x, y, w, h, hovered) -> {
            screen.drawLines(g, shown, x + 2, y + SECTION_TOP, Theme.TEXT_FAINT);
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
        // Width kept clear at the right for Edit and Delete zone, each with a gap.
        int buttons = (here ? ZONE_EDIT_WIDTH + 4 : 0) + ZONE_DELETE_WIDTH + 4;
        int color = Theme.zoneColor(zone);
        int nameColor = here ? color : Theme.fade(color, 0.6f);
        // "ZONE · name · x, y, z", wrapped left of the buttons; the Paused pill follows the last line.
        int room = w - buttons - DOT_TEXT_X - 2 - (paused ? pausedRoom(pausedText) : 0);
        var shown = screen.wrapPieces(List.of(
            new Piece(Theme.caps(here ? "particlefilter.hidden.section_zone" : "particlefilter.hidden.section_zone_elsewhere"), nameColor),
            new Piece(zone.name, nameColor),
            new Piece(zone.coordsText(), Theme.TEXT_FAINT)), Theme.TEXT_FAINT, room);
        int lastY = SECTION_TOP + (shown.size() - 1) * LINE;
        int lastWidth = font.width(shown.get(shown.size() - 1));
        // The buttons are centered on the first line of text.
        int buttonY = SECTION_TOP + (textHeight(1) - SMALL_BUTTON_HEIGHT) / 2;
        Row row = new Row(SECTION_TOP + textHeight(shown.size()) + 8, (g, x, y, rw, h, hovered) -> {
            Theme.dot(g, x + DOT_X, y + SECTION_TOP + 1, DOT_SIZE, color);
            screen.drawLines(g, shown, x + DOT_TEXT_X, y + SECTION_TOP, Theme.TEXT_FAINT);
            if (paused) Theme.pill(g, font, pausedText, x + DOT_TEXT_X + lastWidth + 8, y + lastY - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
            if (hovered && here) ZoneRenderer.highlighted = zone;
        });
        Component where = Component.translatable("particlefilter.hidden.zone_where", zone.coordsText(), zone.sizeText(),
            ZoneEditScreen.volumeText(zone), zone.world, zone.dimension);
        row.tooltip(paused ? Component.empty().append(where).append("\n").append(pausedTip) : where);
        if (here) {
            row.add(screen.rowButton(Style.GHOST, Component.translatable("particlefilter.edit"), ZONE_EDIT_WIDTH,
                () -> screen.minecraft().setScreen(new ZoneEditScreen(screen, zone))), w - buttons, buttonY);
        }
        boolean confirming = zone == confirmingDelete;
        row.add(screen.rowButton(Style.DANGER, Component.translatable(confirming ? "particlefilter.confirm" : "particlefilter.zone.delete"), ZONE_DELETE_WIDTH, () -> {
            if (confirmingDelete == zone) {
                PfConfig.deleteZone(zone);
                confirmingDelete = null;
            } else {
                confirmingDelete = zone;
            }
            screen.refreshNextTick();
        }).selected(confirming).tooltip(Component.translatable("particlefilter.zone.delete.tooltip")), w - ZONE_DELETE_WIDTH - 2, buttonY);
        return row;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (screen.panel().isEmpty()) {
            screen.drawEmptyState(g, Component.translatable("particlefilter.hidden.empty"), Component.translatable("particlefilter.hidden.empty.body"),
                screen.cx() + screen.cw() / 2, emptyStateTop(screen.panel().getY(), screen.bodyBottom()), screen.cw());
        }
    }
}
