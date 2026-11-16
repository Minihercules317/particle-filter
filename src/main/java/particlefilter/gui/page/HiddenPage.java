package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import particlefilter.config.FilterMode;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.filter.ParticleKey;
import particlefilter.filter.ParticlePattern;
import particlefilter.gui.RuleNames;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.PfScreen.Piece;
import particlefilter.gui.Theme;
import particlefilter.gui.ZoneEditScreen;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.TextField;
import particlefilter.render.ZoneRenderer;

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
    /** Gap between the id box and each of those buttons, and between a zone heading's Edit and Delete zone. */
    private static final int BUTTON_GAP = 4;
    /** Space between the id box and the list below it. */
    private static final int LIST_GAP = 8;
    /** Space above a section heading's text. */
    private static final int SECTION_TOP = 8;
    /** Width of a zone heading's Edit button. */
    private static final int ZONE_EDIT_WIDTH = 36;
    /** Width of a zone heading's Delete zone button. */
    private static final int ZONE_DELETE_WIDTH = 70;
    /** Gap between Delete zone and the heading's right edge, as for the Delete buttons of entries. */
    private static final int ZONE_BUTTONS_RIGHT = 2;
    /** Smallest gap between a zone heading's text and its buttons. */
    private static final int ZONE_TEXT_GAP = 8;
    /** Gap between a heading's last line and its Paused pill. */
    private static final int PILL_GAP = 6;
    /** Width {@link Theme#pill} adds around its text. */
    private static final int PILL_PADDING = 8;

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
        idInput.setResponder(s -> {
            idInputText = s;
            showValidity();
        });
        showValidity();
        screen.button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_particle"), particleX, bodyTop, ADD_BUTTON_WIDTH, BUTTON_HEIGHT,
            () -> addTyped(false)).tooltip(Component.translatable("particlefilter.hidden.add_particle.tooltip"));
        screen.button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_block"), blockX, bodyTop, ADD_BUTTON_WIDTH, BUTTON_HEIGHT,
            () -> addTyped(true)).tooltip(Component.translatable("particlefilter.hidden.add_block.tooltip"));

        int listTop = bodyTop + BUTTON_HEIGHT + LIST_GAP;
        screen.addPanel(cx, listTop, cw, bodyBottom - listTop, 2);
        screen.setRows(hiddenRows());
    }

    /** The entry the box's text hides as a particle or, with [block], as a block; null if it isn't valid. */
    private static String typedEntry(String text, boolean block) {
        String typed = text.trim();
        if (typed.isEmpty()) return null;
        ParticlePattern pattern = ParticlePattern.parse(block ? PfConfig.blockEntry(typed) : typed);
        return pattern == null ? null : pattern.toString();
    }

    /** Text that is neither a valid particle nor a valid block entry turns red. */
    private void showValidity() {
        boolean valid = idInputText.isBlank() || typedEntry(idInputText, false) != null || typedEntry(idInputText, true) != null;
        idInput.setTextColor((valid ? Theme.TEXT : Theme.DANGER) & 0xFFFFFF);
    }

    private void addTyped(boolean block) {
        String entry = typedEntry(idInputText, block);
        if (entry == null) return;
        PfConfig.setHiddenEverywhere(entry, true);
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
                rows.add(screen.entryRow(RuleNames.of(p), p, particleColor(p), !mode.global,
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
                rows.add(screen.entryRow(RuleNames.of(p), p, particleColor(p), !mode.zones,
                    () -> zone.particles.contains(p), on -> PfConfig.setZoneEntryOn(zone, p, on), () -> PfConfig.setHiddenInZone(zone, p, false),
                    here ? zone : null));
            }
        }
        return rows;
    }

    /** Dot color: groups of particles made from blocks like the old block entries, others by whether they're typed. */
    private static int particleColor(String entry) {
        ParticlePattern pattern = ParticlePattern.parse(entry);
        if (pattern != null && pattern.anyParticle()) return Theme.BLOCK;
        return ParticleKey.isTyped(entry) ? Theme.TYPED : Theme.UNTYPED;
    }

    /** Room the Paused pill takes after a heading's last line, counting the gap before it. */
    private int pausedRoom(String pausedText) {
        return PILL_GAP + screen.font().width(pausedText) + PILL_PADDING;
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
            if (paused) Theme.pill(g, font, pausedText, x + 2 + lastWidth + PILL_GAP, y + lastY - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
        });
        if (paused) row.tooltip(pausedTip);
        return row;
    }

    /** A zone's heading: name in its outline color, its coordinates, and Edit (this world only) and Delete zone. */
    private Row zoneSection(Zone zone, boolean here, boolean paused, Component pausedTip) {
        var font = screen.font();
        int w = screen.panel().contentWidth();
        String pausedText = Theme.caps("particlefilter.paused");
        // Delete zone at the right, Edit left of it in this world; the text keeps clear of the leftmost button.
        int deleteX = w - ZONE_BUTTONS_RIGHT - ZONE_DELETE_WIDTH;
        int editX = deleteX - BUTTON_GAP - ZONE_EDIT_WIDTH;
        int buttonsX = here ? editX : deleteX;
        int color = Theme.zoneColor(zone);
        int nameColor = here ? color : Theme.fade(color, 0.6f);
        // "ZONE · name · x, y, z", wrapped left of the buttons; the Paused pill follows the last line.
        int room = buttonsX - ZONE_TEXT_GAP - DOT_TEXT_X - (paused ? pausedRoom(pausedText) : 0);
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
            if (paused) Theme.pill(g, font, pausedText, x + DOT_TEXT_X + lastWidth + PILL_GAP, y + lastY - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
            if (hovered && here) ZoneRenderer.highlighted = zone;
        });
        Component where = Component.translatable("particlefilter.hidden.zone_where", zone.coordsText(), zone.sizeText(),
            ZoneEditScreen.volumeText(zone), zone.world, zone.dimension);
        row.tooltip(paused ? Component.empty().append(where).append("\n").append(pausedTip) : where);
        if (here) {
            row.add(screen.rowButton(Style.GHOST, Component.translatable("particlefilter.edit"), ZONE_EDIT_WIDTH,
                () -> screen.minecraft().setScreen(new ZoneEditScreen(screen, zone))), editX, buttonY);
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
        }).selected(confirming).tooltip(Component.translatable("particlefilter.zone.delete.tooltip")), deleteX, buttonY);
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
