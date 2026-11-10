package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import particlefilter.config.FilterMode;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.TextField;
import particlefilter.gui.widget.Toggle;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.DiagnosticScan;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Particle Filter window: a sidebar with the pages and the Active rules switch, and the current page:
 * - Scan: record which particles spawn around what you're looking at (or everywhere around you, for a diagnostic
 *   scan), and hide them everywhere or in a zone there
 * - Hidden: everything hidden and where, each entry with an on/off switch and Delete, and a box to hide by id
 * - Zones: the zones in this world, to edit, delete or create
 * The page last shown is reopened until the player leaves the world or server.
 */
public class ParticleFilterScreen extends PfScreen {

    public enum Tab { SCAN, DIAGNOSTIC, HIDDEN, ZONES }

    private static final int SIDEBAR_WIDTH = 124;
    private static final int PAD = 12;
    private static final int ROW_HEIGHT = 22;
    /** Width of each toggle column on the Scan page. */
    private static final int COLUMN = 58;

    private static Tab lastTab = Tab.SCAN;

    private Tab tab;
    // The window, and the page area right of the sidebar.
    private int wx, wy, ww, wh;
    private int cx, cw, bodyTop, bodyBottom;
    /** Right edge of the page's header buttons, left of the help and close buttons. */
    private int actionsRight;
    private int modesTop;
    private final Map<Tab, FlatButton> navButtons = new EnumMap<>(Tab.class);
    private FlatButton scanButton;
    private FlatButton editZoneButton;
    private int shownScanGeneration;
    private TextField idInput;
    private String idInputText = "";
    /** Zone whose Delete button was pressed once and now waits for confirmation. */
    private Zone confirmingDelete;

    public ParticleFilterScreen(Screen parent, Tab tab) {
        super(Component.translatable("particlefilter.title"), parent);
        this.tab = tab != null ? tab : lastTab;
    }

    /** Called when the player joins or leaves a world or server: the next menu opens on Scan again. */
    public static void forgetLastPage() {
        lastTab = Tab.SCAN;
    }

    @Override
    protected void init() {
        lastTab = tab;
        ww = Math.min(width - 16, 560);
        wh = Math.min(height - 16, 340);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        cx = wx + SIDEBAR_WIDTH + PAD;
        cw = ww - SIDEBAR_WIDTH - 2 * PAD;
        bodyTop = wy + 46;
        bodyBottom = wy + wh - PAD;
        actionsRight = wx + ww - 46;
        panel = null;
        scanButton = null;
        editZoneButton = null;
        idInput = null;

        initSidebar();
        button(Style.GHOST, Component.literal("?"), wx + ww - 40, wy + 8, 14, 14, () -> minecraft.setScreen(new GuideScreen(this)))
            .tooltip(Component.translatable("particlefilter.guide.open"));
        button(Style.GHOST, Component.literal("×"), wx + ww - 22, wy + 8, 14, 14, this::onClose)
            .tooltip(Component.translatable("particlefilter.close"));
        switch (tab) {
            case SCAN -> initScan();
            case DIAGNOSTIC -> initDiagnostic();
            case HIDDEN -> initHidden();
            case ZONES -> initZones();
        }
    }

    // ---------------------------------------------------------------- Sidebar

    private void initSidebar() {
        int x = wx + 8;
        int w = SIDEBAR_WIDTH - 16;
        int y = wy + 34;
        navButtons.clear();
        for (Tab t : Tab.values()) {
            navButtons.put(t, button(Style.NAV, Component.translatable("particlefilter.tab." + t.name().toLowerCase(Locale.ROOT)),
                x, y, w, 20, () -> switchTo(t)).selected(t == tab));
            y += 22;
        }

        FilterMode[] modes = FilterMode.values();
        modesTop = wy + wh - 8 - modes.length * 18;
        int optionY = modesTop;
        for (FilterMode mode : modes) {
            button(Style.OPTION, Component.translatable(mode.key()), x, optionY, w, 16, () -> {
                PfConfig.setMode(mode);
                refresh();
            }).selected(mode == PfConfig.mode()).tooltip(Component.translatable(mode.key() + ".tooltip"));
            optionY += 18;
        }
    }

    private void updateBadges() {
        navButtons.get(Tab.HIDDEN).badge(Integer.toString(hiddenCount()));
        navButtons.get(Tab.ZONES).badge(Integer.toString(PfConfig.zonesHere().size()));
        navButtons.get(Tab.SCAN).badge(ParticleScan.isRunning() ? ParticleScan.secondsLeft() + "s" : null);
        navButtons.get(Tab.DIAGNOSTIC).badge(DiagnosticScan.isRunning() ? DiagnosticScan.secondsLeft() + "s" : null);
    }

    /** Entries switched on; switched-off ones are listed on the page but don't count as hidden. */
    private static int hiddenCount() {
        return PfConfig.hiddenParticles().size() + PfConfig.hiddenBlocks().size()
            + PfConfig.zones().stream().mapToInt(z -> z.particles.size()).sum();
    }

    private void switchTo(Tab t) {
        tab = t;
        confirmingDelete = null;
        savedScroll = 0;
        ZoneRenderer.highlighted = null;
        ZoneRenderer.preview = null;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- Scan

    private void initScan() {
        scanButton = button(Style.PRIMARY, scanLabel(), actionsRight - 80, wy + 12, 80, 20, () -> {
            ParticleScan.start();
            refresh();
        }).tooltip(scanTooltip());
        scanButton.active = !ParticleScan.isRunning();
        addDurationSpinner(PfConfig.scanSeconds(), seconds -> {
            PfConfig.setScanSeconds(seconds);
            scanButton.tooltip(scanTooltip());
        });

        addPanel(cx, bodyTop + 36, cw, bodyBottom - 24 - bodyTop - 36, 3);
        setRows(scanRows());
        shownScanGeneration = ParticleScan.generation();

        if (ParticleScan.target() != null) {
            editZoneButton = button(Style.SECONDARY, Component.translatable("particlefilter.scan.edit_zone"), cx + cw - 64, bodyBottom - 18, 64, 18,
                () -> {
                    Zone zone = ParticleScan.hereZone();
                    if (zone != null) minecraft.setScreen(new ZoneEditScreen(this, zone));
                }).tooltip(Component.translatable("particlefilter.scan.edit_zone.tooltip"));
            Zone current = ParticleScan.hereZone();
            Zone next = ParticleScan.nextHereChoice();
            if (current != null || next != null) {
                Component label = next == null ? Component.translatable("particlefilter.scan.here_new")
                    : Component.literal(Theme.fit(font, Component.translatable("particlefilter.scan.here_use", next.name).getString(), 70));
                Component tip = next == null ? Component.translatable("particlefilter.scan.here_new.tooltip")
                    : Component.translatable("particlefilter.scan.here_use.tooltip", next.name);
                button(Style.SECONDARY, label, cx + cw - 64 - 4 - 76, bodyBottom - 18, 76, 18, () -> {
                    ParticleScan.cycleHere();
                    refresh();
                }).tooltip(tip);
            }
        }
    }

    /** "Scan for [− n +] seconds" below the header, shared by Scan and Diagnostic. */
    private void addDurationSpinner(int value, java.util.function.IntConsumer onValue) {
        Component duration = Component.translatable("particlefilter.scan.duration");
        Component tip = Component.translatable("particlefilter.scan.duration.tooltip", PfConfig.MIN_SCAN_SECONDS, PfConfig.MAX_SCAN_SECONDS);
        addSpinner(cx + font.width(duration) + 6, bodyTop, 62, value, PfConfig.MIN_SCAN_SECONDS, PfConfig.MAX_SCAN_SECONDS, tip, onValue);
    }

    private void renderDurationLabels(GuiGraphics g) {
        Component duration = Component.translatable("particlefilter.scan.duration");
        Theme.text(g, font, duration, cx, bodyTop + 4, Theme.TEXT_DIM);
        Theme.text(g, font, Component.translatable("particlefilter.scan.duration_unit"), cx + font.width(duration) + 6 + 62 + 5, bodyTop + 4, Theme.TEXT_DIM);
    }

    private static Component scanTooltip() {
        return Component.translatable("particlefilter.scan.button.tooltip", ParticleScan.RADIUS, PfConfig.scanSeconds());
    }

    private Component scanLabel() {
        return ParticleScan.isRunning()
            ? Component.translatable("particlefilter.scan.running", ParticleScan.secondsLeft())
            : Component.translatable("particlefilter.scan.button");
    }

    /** Left edge of column [i] of [n] switch or value columns at the right of a result row. */
    private int column(int i, int n) {
        return panel.contentWidth() - (n - i) * COLUMN;
    }

    /** A result row: dot, name, a bar for how often it appeared next to [most], and the count; [columns] sit right. */
    private Row resultRow(String particle, boolean typed, int count, int most, int columns, boolean dim, String value) {
        int firstX = column(0, columns);
        String shownName = ParticleScan.displayName(particle);
        String countText = "×" + count;
        String name = Theme.fit(font, shownName, firstX - 26 - font.width(countText) - 8);
        int color = typed ? Theme.TYPED : Theme.UNTYPED;
        float share = count / (float) Math.max(1, most);
        return new Row(ROW_HEIGHT, (g, x, y, w, h, hovered) -> {
            Theme.rect(g, x, y, w, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
            // How often it appeared compared to the most frequent particle.
            g.fill(x + 19, y + h - 3, x + 19 + Math.max(2, Math.round((firstX - 27) * share)), y + h - 2, Theme.fade(color, dim ? 0.25f : 0.55f));
            Theme.dot(g, x + 8, y + (h - 5) / 2, 5, dim ? Theme.fade(color, 0.45f) : color);
            Theme.text(g, font, name, x + 19, y + (h - 8) / 2, dim ? Theme.TEXT_DIM : Theme.TEXT);
            Theme.text(g, font, countText, x + firstX - 8 - font.width(countText), y + (h - 8) / 2, Theme.TEXT_DIM);
            if (value != null) Theme.centered(g, font, value, x + firstX + COLUMN / 2, y + (h - 8) / 2, Theme.TEXT_DIM);
        }).tooltip(Component.translatable(typed ? "particlefilter.particle.typed.tooltip" : "particlefilter.particle.untyped.tooltip", particle));
    }

    private Toggle everywhereToggle(String particle) {
        return new Toggle(0, 0, PfConfig.isHiddenEverywhere(particle), Component.translatable("particlefilter.scan.col_everywhere"),
            hidden -> PfConfig.setHiddenEverywhere(particle, hidden))
            .tooltip(Component.translatable("particlefilter.scan.everywhere.tooltip", ParticleScan.displayName(particle)));
    }

    /** One row per particle, with Everywhere and Here switches. */
    private List<Row> scanRows() {
        List<Row> rows = new ArrayList<>();
        boolean here = ParticleScan.target() != null;
        int most = ParticleScan.results().stream().mapToInt(ParticleScan.Result::count).max().orElse(1);
        for (ParticleScan.Result result : ParticleScan.results()) {
            String particle = result.particle();
            Row row = resultRow(particle, result.typed(), result.count(), most, 2, false, null);
            row.add(everywhereToggle(particle), column(0, 2) + (COLUMN - Toggle.WIDTH) / 2);
            if (here) {
                row.add(new Toggle(0, 0, ParticleScan.isHiddenHere(particle), Component.translatable("particlefilter.scan.col_zone"),
                    hidden -> {
                        ParticleScan.setHiddenHere(particle, hidden);
                        refreshNextTick(); // the Here line and buttons follow a zone created or removed
                    }).tooltip(Component.translatable("particlefilter.scan.zone.tooltip", ParticleScan.displayName(particle))),
                    column(1, 2) + (COLUMN - Toggle.WIDTH) / 2);
            }
            rows.add(row);
        }
        return rows;
    }

    private void renderScan(GuiGraphics g, int mouseX, int mouseY) {
        int found = ParticleScan.results().size();
        Component status;
        if (ParticleScan.isRunning()) {
            status = Component.translatable("particlefilter.scan.status_running", ParticleScan.targetName(), ParticleScan.RADIUS);
        } else if (ParticleScan.target() != null) {
            status = Component.translatable(found == 1 ? "particlefilter.scan.status_done_one" : "particlefilter.scan.status_done", found, ParticleScan.targetName());
        } else {
            status = Component.translatable("particlefilter.scan.status_idle");
        }
        renderHeader(g, Component.translatable("particlefilter.scan.title"), status, 80);
        renderDurationLabels(g);

        int listTop = bodyTop + 36;
        if (panel.isEmpty()) {
            int centerY = (listTop + bodyBottom - 24) / 2 - 20;
            if (ParticleScan.isRunning()) {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_running"),
                    Component.translatable("particlefilter.scan.empty_running.body", ParticleScan.RADIUS), cx + cw / 2, centerY, cw);
            } else if (ParticleScan.target() == null) {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_first"),
                    Component.translatable("particlefilter.scan.empty_first.body", ParticleScan.RADIUS, PfConfig.scanSeconds()), cx + cw / 2, centerY, cw);
            } else {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_none"),
                    Component.translatable("particlefilter.scan.empty_none.body", ParticleScan.RADIUS), cx + cw / 2, centerY, cw);
            }
        } else {
            renderColumnLabels(g, listTop - 12, "particlefilter.scan.col_everywhere", "particlefilter.scan.col_zone");
        }

        ZoneRenderer.highlighted = null;
        ZoneRenderer.preview = null;
        if (ParticleScan.target() == null) return;
        // What Here refers to: a zone around the scanned block, or a new one fitted to the particles (cyan preview).
        int y = bodyBottom - 13;
        Zone zone = ParticleScan.hereZone();
        ZoneRenderer.highlighted = zone;
        if (editZoneButton != null) editZoneButton.active = zone != null;
        String key;
        Component here;
        int color;
        if (zone != null) {
            key = ParticleScan.isFitting(zone) ? "particlefilter.scan.zone_fitted" : "particlefilter.scan.zone_existing";
            here = Component.translatable(key, zone.name, zone.sizeText());
            color = Theme.zoneColor(zone);
        } else {
            ZoneRenderer.preview = ParticleScan.newZonePreview();
            key = "particlefilter.scan.zone_new";
            here = Component.translatable(key);
            color = Theme.NEW_ZONE;
        }
        Component tip = zone == null ? Component.translatable(key + ".tooltip")
            : Component.translatable(key + ".tooltip", zone.name, zone.sizeText(), ZoneEditScreen.volumeText(zone));
        renderFooterLine(g, mouseX, mouseY, color, here, tip, cw - 160);
    }

    /** Column headers: PARTICLE on the left and one label per column on the right. */
    private void renderColumnLabels(GuiGraphics g, int y, String... columnKeys) {
        Theme.text(g, font, Theme.caps("particlefilter.scan.col_particle"), cx + 2, y, Theme.TEXT_FAINT);
        for (int i = 0; i < columnKeys.length; i++) {
            Theme.centered(g, font, Theme.caps(columnKeys[i]), cx + column(i, columnKeys.length) + COLUMN / 2, y, Theme.TEXT_FAINT);
        }
    }

    /** The line under a result list: a colored dot and text, with [tip] (the full explanation) while hovered. */
    private void renderFooterLine(GuiGraphics g, int mouseX, int mouseY, int color, Component text, Component tip, int maxWidth) {
        int y = bodyBottom - 13;
        String shown = Theme.fit(font, text.getString(), maxWidth);
        Theme.dot(g, cx + 2, y + 1, 5, color);
        Theme.text(g, font, shown, cx + 12, y, Theme.TEXT_DIM);
        if (mouseX >= cx && mouseX < cx + 12 + font.width(shown) && mouseY >= y - 4 && mouseY < y + 12) {
            setTooltipForNextRenderPass(Tooltip.splitTooltip(minecraft, tip));
        }
    }

    // ---------------------------------------------------------------- Diagnostic

    private void initDiagnostic() {
        scanButton = button(Style.PRIMARY, diagnosticLabel(), actionsRight - 80, wy + 12, 80, 20, () -> {
            DiagnosticScan.start();
            refresh();
        }).tooltip(Component.translatable("particlefilter.diagnostic.button.tooltip", renderDistanceBlocks(), PfConfig.diagnosticSeconds()));
        scanButton.active = !DiagnosticScan.isRunning();
        addDurationSpinner(PfConfig.diagnosticSeconds(), seconds -> {
            PfConfig.setDiagnosticSeconds(seconds);
            scanButton.tooltip(Component.translatable("particlefilter.diagnostic.button.tooltip", renderDistanceBlocks(), seconds));
        });

        addPanel(cx, bodyTop + 36, cw, bodyBottom - 24 - bodyTop - 36, 3);
        setRows(diagnosticRows());
        shownScanGeneration = DiagnosticScan.generation();
    }

    private int renderDistanceBlocks() {
        return minecraft.options.getEffectiveRenderDistance() * 16;
    }

    private Component diagnosticLabel() {
        return DiagnosticScan.isRunning()
            ? Component.translatable("particlefilter.scan.running", DiagnosticScan.secondsLeft())
            : Component.translatable("particlefilter.diagnostic.button");
    }

    /**
     * Two sections, Still appearing and Hidden by your rules, each row with how close the particle came and Everywhere
     * and Here switches. Hovering a row shows its zone, or where Here would put one.
     */
    private List<Row> diagnosticRows() {
        List<Row> rows = new ArrayList<>();
        List<DiagnosticScan.Result> appearing = DiagnosticScan.appearing();
        List<DiagnosticScan.Result> hidden = DiagnosticScan.hidden();
        if (appearing.isEmpty() && hidden.isEmpty()) return rows;
        int most = java.util.stream.Stream.concat(appearing.stream(), hidden.stream()).mapToInt(DiagnosticScan.Result::count).max().orElse(1);
        rows.add(sectionRow(Theme.caps("particlefilter.diagnostic.section_appearing", appearing.size())));
        if (appearing.isEmpty()) rows.add(hintRow(Component.translatable("particlefilter.diagnostic.none_appearing"), panel.contentWidth()));
        for (DiagnosticScan.Result r : appearing) rows.add(diagnosticRow(r, most, false));
        if (!hidden.isEmpty()) {
            rows.add(sectionRow(Theme.caps("particlefilter.diagnostic.section_hidden", hidden.size())));
            for (DiagnosticScan.Result r : hidden) rows.add(diagnosticRow(r, most, true));
        }
        return rows;
    }

    private Row diagnosticRow(DiagnosticScan.Result result, int most, boolean wasHidden) {
        String particle = result.particle();
        long distance = Math.round(result.nearest());
        String nearest = Component.translatable(distance == 1 ? "particlefilter.scan.blocks_away_one" : "particlefilter.scan.blocks_away", distance).getString();
        Row base = resultRow(particle, result.typed(), result.count(), most, 3, wasHidden, nearest);
        Row row = new Row(ROW_HEIGHT, (g, x, y, w, h, hovered) -> {
            base.paint(g, x, y, w, h, hovered);
            if (hovered) {
                Zone zone = DiagnosticScan.zoneFor(particle);
                ZoneRenderer.highlighted = zone;
                ZoneRenderer.preview = zone == null ? DiagnosticScan.previewFor(particle) : null;
            }
        });
        if (wasHidden) {
            List<String> by = DiagnosticScan.hiddenBy(particle, result.spawns());
            String rules = by.isEmpty() ? Component.translatable("particlefilter.diagnostic.hidden_by_changed").getString() : String.join(", ", by);
            row.tooltip(Component.translatable("particlefilter.diagnostic.row_hidden.tooltip", particle, result.count(), boxText(result.spawns()), rules));
        } else {
            row.tooltip(Component.translatable("particlefilter.diagnostic.row_appearing.tooltip", particle, result.count(), boxText(result.spawns())));
        }
        row.add(everywhereToggle(particle), column(1, 3) + (COLUMN - Toggle.WIDTH) / 2);
        row.add(new Toggle(0, 0, DiagnosticScan.isHiddenHere(particle), Component.translatable("particlefilter.scan.col_zone"), on -> {
            DiagnosticScan.setHiddenHere(particle, on);
            refreshNextTick();
        }).tooltip(Component.translatable("particlefilter.diagnostic.here.tooltip", ParticleScan.displayName(particle))), column(2, 3) + (COLUMN - Toggle.WIDTH) / 2);
        return row;
    }

    private static String boxText(net.minecraft.world.phys.AABB box) {
        return (int) Math.floor(box.minX) + ", " + (int) Math.floor(box.minY) + ", " + (int) Math.floor(box.minZ) + " → "
            + (int) Math.floor(box.maxX) + ", " + (int) Math.floor(box.maxY) + ", " + (int) Math.floor(box.maxZ);
    }

    private void renderDiagnostic(GuiGraphics g, int mouseX, int mouseY) {
        int radius = DiagnosticScan.radius();
        Component status;
        if (DiagnosticScan.isRunning()) {
            status = Component.translatable("particlefilter.diagnostic.status_running", radius);
        } else if (DiagnosticScan.hasRun()) {
            status = Component.translatable("particlefilter.diagnostic.status_done", DiagnosticScan.appearing().size(), DiagnosticScan.hidden().size(), radius);
        } else {
            status = Component.translatable("particlefilter.diagnostic.status_idle");
        }
        renderHeader(g, Component.translatable("particlefilter.diagnostic.title"), status, 80);
        renderDurationLabels(g);

        int listTop = bodyTop + 36;
        if (panel.isEmpty()) {
            int centerY = (listTop + bodyBottom - 24) / 2 - 20;
            if (DiagnosticScan.isRunning()) {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_running"),
                    Component.translatable("particlefilter.diagnostic.empty_running.body", radius), cx + cw / 2, centerY, cw);
            } else if (!DiagnosticScan.hasRun()) {
                drawEmptyState(g, Component.translatable("particlefilter.diagnostic.empty_first"),
                    Component.translatable("particlefilter.diagnostic.empty_first.body", renderDistanceBlocks(), PfConfig.diagnosticSeconds()), cx + cw / 2, centerY, cw);
            } else {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_none"),
                    Component.translatable("particlefilter.scan.empty_none.body", radius), cx + cw / 2, centerY, cw);
            }
            return;
        }
        renderColumnLabels(g, listTop - 12, "particlefilter.scan.col_nearest", "particlefilter.scan.col_everywhere", "particlefilter.scan.col_zone");
        boolean capped = DiagnosticScan.anyCapped();
        renderFooterLine(g, mouseX, mouseY, capped ? Theme.WARNING : Theme.NEW_ZONE,
            Component.translatable(capped ? "particlefilter.diagnostic.here_capped" : "particlefilter.diagnostic.here_line"),
            Component.translatable("particlefilter.diagnostic.here_line.tooltip", Zone.MAX_SIZE), cw - 4);
    }

    // ---------------------------------------------------------------- Hidden

    private void initHidden() {
        int buttonWidth = 80;
        idInput = addRenderableWidget(new TextField(font, cx, bodyTop, cw - 2 * buttonWidth - 8, 20, Component.translatable("particlefilter.hidden.input")));
        idInput.setMaxLength(256);
        idInput.setHint(Component.translatable("particlefilter.hidden.input_hint").withStyle(s -> s.withColor(Theme.TEXT_FAINT & 0xFFFFFF)));
        idInput.setValue(idInputText);
        idInput.setResponder(s -> idInputText = s);
        button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_particle"), cx + cw - 2 * buttonWidth - 4, bodyTop, buttonWidth, 20,
            () -> addTyped(false)).tooltip(Component.translatable("particlefilter.hidden.add_particle.tooltip"));
        button(Style.SECONDARY, Component.translatable("particlefilter.hidden.add_block"), cx + cw - buttonWidth, bodyTop, buttonWidth, 20,
            () -> addTyped(true)).tooltip(Component.translatable("particlefilter.hidden.add_block.tooltip"));

        addPanel(cx, bodyTop + 28, cw, bodyBottom - bodyTop - 28, 2);
        setRows(hiddenRows());
    }

    private void addTyped(boolean block) {
        String id = idInputText.trim();
        if (id.isEmpty()) return;
        if (block) PfConfig.setBlockHidden(id, true);
        else PfConfig.setHiddenEverywhere(id, true);
        idInputText = "";
        refresh();
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (idInput != null && idInput.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            addTyped(false);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
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
                rows.add(entry(ParticleScan.displayName(p), p, particleColor(p), !mode.global, null,
                    () -> PfConfig.isHiddenEverywhere(p), on -> PfConfig.setEverywhereOn(p, on), () -> PfConfig.setHiddenEverywhere(p, false)));
            }
        }

        List<Zone> zones = new ArrayList<>(PfConfig.zones());
        zones.removeIf(z -> !z.hasEntries());
        zones.sort(Comparator.comparing(z -> !PfConfig.isInCurrentWorld(z))); // this world's zones first
        for (Zone zone : zones) {
            boolean here = PfConfig.isInCurrentWorld(zone);
            rows.add(zoneSection(zone, here, !mode.zones, pausedTip));
            for (String p : listed(zone.particles, zone.disabledParticles)) {
                rows.add(entry(ParticleScan.displayName(p), p, particleColor(p), !mode.zones, here ? zone : null,
                    () -> zone.particles.contains(p), on -> PfConfig.setZoneEntryOn(zone, p, on), () -> PfConfig.setHiddenInZone(zone, p, false)));
            }
        }

        List<String> blocks = listed(PfConfig.hiddenBlocks(), PfConfig.disabledBlocks());
        if (!blocks.isEmpty()) {
            rows.add(section(Theme.caps("particlefilter.hidden.section_blocks", blocks.size()), !mode.global, pausedTip));
            for (String b : blocks) {
                rows.add(entry(b, null, Theme.BLOCK, !mode.global, null,
                    () -> PfConfig.hasBlockEntry(b), on -> PfConfig.setBlockOn(b, on), () -> PfConfig.setBlockHidden(b, false)));
            }
        }
        return rows;
    }

    private static int particleColor(String particle) {
        return particle.contains(":") ? Theme.TYPED : Theme.UNTYPED;
    }

    /** Section label, with a Paused pill while Active rules leaves this kind of rule out. */
    private Row section(String label, boolean paused, Component pausedTip) {
        String pausedText = Theme.caps("particlefilter.paused");
        String shown = Theme.fit(font, label, panel.contentWidth() - 4 - (paused ? font.width(pausedText) + 14 : 0));
        int top = 8;
        Row row = new Row(22, (g, x, y, w, h, hovered) -> {
            Theme.text(g, font, shown, x + 2, y + top, Theme.TEXT_FAINT);
            if (paused) Theme.pill(g, font, pausedText, x + 8 + font.width(shown), y + top - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
        });
        if (paused) row.tooltip(pausedTip);
        return row;
    }

    /** A zone's heading: name in its outline color, its coordinates, and Edit (this world only) and Delete zone. */
    private Row zoneSection(Zone zone, boolean here, boolean paused, Component pausedTip) {
        int w = panel.contentWidth();
        String pausedText = Theme.caps("particlefilter.paused");
        int buttons = (here ? 40 : 0) + 74;
        String label = Theme.caps(here ? "particlefilter.hidden.section_zone" : "particlefilter.hidden.section_zone_elsewhere") + " · " + zone.name;
        String coords = zone.coordsText();
        int room = w - 14 - buttons - (paused ? font.width(pausedText) + 14 : 0);
        String shownLabel = Theme.fit(font, label, room);
        String shownCoords = Theme.fit(font, coords, room - font.width(shownLabel) - 8);
        int color = Theme.zoneColor(zone);
        int top = 8;
        Row row = new Row(24, (g, x, y, rw, h, hovered) -> {
            Theme.dot(g, x + 2, y + top + 1, 5, color);
            Theme.text(g, font, shownLabel, x + 12, y + top, here ? color : Theme.fade(color, 0.6f));
            int after = x + 12 + font.width(shownLabel) + 8;
            Theme.text(g, font, shownCoords, after, y + top, Theme.TEXT_FAINT);
            if (paused) Theme.pill(g, font, pausedText, after + font.width(shownCoords) + 8, y + top - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
            if (hovered && here) ZoneRenderer.highlighted = zone;
        });
        Component where = Component.translatable("particlefilter.hidden.zone_where", zone.coordsText(), zone.sizeText(),
            ZoneEditScreen.volumeText(zone), zone.world, zone.dimension);
        row.tooltip(paused ? Component.empty().append(where).append("\n").append(pausedTip) : where);
        if (here) {
            row.add(rowButton(Style.GHOST, Component.translatable("particlefilter.edit"), 36,
                () -> minecraft.setScreen(new ZoneEditScreen(this, zone))), w - buttons, 4);
        }
        boolean confirming = zone == confirmingDelete;
        row.add(rowButton(Style.DANGER, Component.translatable(confirming ? "particlefilter.confirm" : "particlefilter.zone.delete"), 70, () -> {
            if (confirmingDelete == zone) {
                PfConfig.deleteZone(zone);
                confirmingDelete = null;
            } else {
                confirmingDelete = zone;
            }
            refreshNextTick();
        }).selected(confirming).tooltip(Component.translatable("particlefilter.zone.delete.tooltip")), w - 72, 4);
        return row;
    }

    /**
     * One hidden entry: a switch that turns it off without forgetting it, and Delete. [id] is shown as a tooltip when
     * it differs from [name]. Hovering an entry of a zone in this world highlights that zone.
     */
    private Row entry(String name, String id, int color, boolean paused, Zone zone, BooleanSupplier isOn, Consumer<Boolean> setOn, Runnable delete) {
        int w = panel.contentWidth();
        int deleteWidth = 44;
        int switchX = w - deleteWidth - 2 - 8 - Toggle.WIDTH;
        String shown = Theme.fit(font, name, switchX - 19 - 8);
        Row row = new Row(20, (g, x, y, rw, h, hovered) -> {
            boolean dim = paused || !isOn.getAsBoolean();
            Theme.rect(g, x, y, rw, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
            Theme.dot(g, x + 8, y + (h - 5) / 2, 5, dim ? Theme.fade(color, 0.35f) : color);
            Theme.text(g, font, shown, x + 19, y + (h - 8) / 2, dim ? Theme.TEXT_FAINT : Theme.TEXT);
            if (hovered && zone != null) ZoneRenderer.highlighted = zone;
        });
        if (id != null && !id.equals(name)) row.tooltip(Component.literal(id));
        row.add(new Toggle(0, 0, isOn.getAsBoolean(), Component.literal(name), setOn)
            .tooltip(Component.translatable("particlefilter.entry.switch.tooltip")), switchX);
        row.add(rowButton(Style.GHOST, Component.translatable("particlefilter.delete"), deleteWidth, () -> {
            delete.run();
            refreshNextTick();
        }).tooltip(Component.translatable("particlefilter.entry.delete.tooltip")), w - deleteWidth - 2);
        return row;
    }

    private void renderHidden(GuiGraphics g) {
        renderHeader(g, Component.translatable("particlefilter.hidden.title"), Component.translatable("particlefilter.hidden.subtitle"), 0);
        if (panel.isEmpty()) {
            drawEmptyState(g, Component.translatable("particlefilter.hidden.empty"), Component.translatable("particlefilter.hidden.empty.body"),
                cx + cw / 2, (panel.getY() + bodyBottom) / 2 - 20, cw);
        }
    }

    // ---------------------------------------------------------------- Zones

    private void initZones() {
        button(Style.PRIMARY, Component.translatable("particlefilter.zones.new"), actionsRight - 96, wy + 12, 96, 20, () -> {
            if (minecraft.player == null) return;
            Zone zone = PfConfig.createZone(minecraft.player.blockPosition().above(PfConfig.NEW_ZONE_DEPTH_BELOW));
            if (zone != null) minecraft.setScreen(new ZoneEditScreen(this, zone));
        }).tooltip(Component.translatable("particlefilter.zones.new.tooltip"));

        addPanel(cx, bodyTop, cw, bodyBottom - bodyTop - 24, 3);
        List<Row> rows = new ArrayList<>();
        int w = panel.contentWidth();
        for (Zone zone : PfConfig.zonesHere()) {
            String name = Theme.fit(font, zone.name, w - 120);
            int color = Theme.zoneColor(zone);
            Row row = new Row(32, (g, x, y, rw, h, hovered) -> {
                Theme.rect(g, x, y, rw, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
                Theme.rect(g, x, y + 5, 3, h - 10, 1, color);
                Theme.text(g, font, name, x + 10, y + 6, Theme.TEXT);
                String details = Component.translatable("particlefilter.zones.details", zone.sizeText(),
                    ZoneEditScreen.volumeText(zone), zone.coordsText(), zone.particles.size()).getString();
                Theme.text(g, font, Theme.fit(font, details, rw - 120), x + 10, y + 18, Theme.TEXT_DIM);
                if (hovered) ZoneRenderer.highlighted = zone;
            });
            row.tooltip(Component.translatable("particlefilter.zones.row.tooltip", zone.sizeText(), ZoneEditScreen.volumeText(zone),
                zone.coordsText(), zone.particles.size()));
            row.add(rowButton(Style.SECONDARY, Component.translatable("particlefilter.edit"), 44,
                () -> minecraft.setScreen(new ZoneEditScreen(this, zone))), w - 108);
            boolean confirming = zone == confirmingDelete;
            row.add(rowButton(Style.DANGER, Component.translatable(confirming ? "particlefilter.confirm" : "particlefilter.delete"), 56, () -> {
                if (confirmingDelete == zone) {
                    PfConfig.deleteZone(zone);
                    confirmingDelete = null;
                } else {
                    confirmingDelete = zone;
                }
                refreshNextTick();
            }).selected(confirming), w - 60);
            rows.add(row);
        }
        setRows(rows);

        addRenderableWidget(Toggle.labelled(cx, bodyBottom - 13, ZoneRenderer.alwaysShow, Component.translatable("particlefilter.zones.always_show"),
            on -> ZoneRenderer.alwaysShow = on)).tooltip(Component.translatable("particlefilter.zones.always_show.tooltip"));
    }

    private void renderZones(GuiGraphics g) {
        renderHeader(g, Component.translatable("particlefilter.zones.title"), Component.translatable("particlefilter.zones.subtitle"), 96);
        if (panel.isEmpty()) {
            drawEmptyState(g, Component.translatable("particlefilter.zones.empty"), Component.translatable("particlefilter.zones.empty.body"),
                cx + cw / 2, (bodyTop + bodyBottom - 24) / 2 - 20, cw);
        }
    }

    // ----------------------------------------------------------------

    /** Page title and subtitle; [actionsWidth] is kept free on the right for the page's buttons. */
    private void renderHeader(GuiGraphics g, Component title, Component subtitle, int actionsWidth) {
        int maxWidth = (actionsWidth > 0 ? actionsRight - actionsWidth : wx + ww - 46) - cx - 8;
        Theme.text(g, font, Theme.fit(font, title.getString(), maxWidth), cx, wy + 12, Theme.TEXT);
        Theme.text(g, font, Theme.fit(font, subtitle.getString(), maxWidth), cx, wy + 25, Theme.TEXT_DIM);
        g.fill(cx, wy + 39, cx + cw, wy + 40, Theme.DIVIDER);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Theme.SCRIM);
        Theme.rect(g, wx, wy, SIDEBAR_WIDTH, wh, 6, Theme.SIDEBAR, true, false);
        Theme.rect(g, wx + SIDEBAR_WIDTH, wy, ww - SIDEBAR_WIDTH, wh, 6, Theme.WINDOW, false, true);
    }

    @Override
    public void tick() {
        super.tick();
        if (scanButton == null) return;
        if (tab == Tab.SCAN) {
            if (ParticleScan.generation() != shownScanGeneration) refresh(); // a scan started or finished
            else if (ParticleScan.isRunning()) scanButton.setMessage(scanLabel());
        } else if (tab == Tab.DIAGNOSTIC) {
            if (DiagnosticScan.generation() != shownScanGeneration) refresh();
            else if (DiagnosticScan.isRunning()) scanButton.setMessage(diagnosticLabel());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (tab != Tab.SCAN) ZoneRenderer.highlighted = null; // rows set it again while hovered
        if (tab == Tab.DIAGNOSTIC) ZoneRenderer.preview = null;
        updateBadges();
        if (scanButton != null) {
            boolean diagnostic = tab == Tab.DIAGNOSTIC;
            boolean running = diagnostic ? DiagnosticScan.isRunning() : ParticleScan.isRunning();
            scanButton.setProgress(running ? (diagnostic ? DiagnosticScan.progress() : ParticleScan.progress()) : -1);
        }
        super.render(g, mouseX, mouseY, partialTick);

        // Sidebar title and the Active rules label.
        Theme.dot(g, wx + 12, wy + 14, 6, Theme.ACCENT);
        Theme.text(g, font, title, wx + 23, wy + 13, Theme.TEXT);
        Theme.text(g, font, Theme.caps("particlefilter.mode.title"), wx + 10, modesTop - 12, Theme.TEXT_FAINT);

        switch (tab) {
            case SCAN -> renderScan(g, mouseX, mouseY);
            case DIAGNOSTIC -> renderDiagnostic(g, mouseX, mouseY);
            case HIDDEN -> renderHidden(g);
            case ZONES -> renderZones(g);
        }
    }
}
