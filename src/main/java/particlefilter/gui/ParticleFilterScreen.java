package particlefilter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
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
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Particle Filter window: a sidebar with the pages and the Active rules switch, and the current page:
 * - Scan: record which particles spawn around what you're looking at, and hide them everywhere or here
 * - Hidden: everything currently hidden and where, with a box to hide something by id
 * - Zones: the zones in this world, to edit, delete or create
 */
public class ParticleFilterScreen extends PfScreen {

    public enum Tab { SCAN, HIDDEN, ZONES }

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
    /** Right edge of the page's header buttons, left of the close button. */
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
        actionsRight = wx + ww - 30;
        panel = null;
        scanButton = null;
        editZoneButton = null;
        idInput = null;

        initSidebar();
        button(Style.GHOST, Component.literal("×"), wx + ww - 22, wy + 8, 14, 14, this::onClose)
            .tooltip(Component.translatable("particlefilter.close"));
        switch (tab) {
            case SCAN -> initScan();
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
            navButtons.put(t, button(Style.NAV, Component.translatable("particlefilter.tab." + t.name().toLowerCase(java.util.Locale.ROOT)),
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
    }

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
        scanButton = button(Style.PRIMARY, scanLabel(), actionsRight - 96, wy + 12, 96, 20, () -> {
            ParticleScan.start();
            refresh();
        }).tooltip(Component.translatable("particlefilter.scan.button.tooltip", ParticleScan.RADIUS));
        scanButton.active = !ParticleScan.isRunning();

        addPanel(cx, bodyTop + 14, cw, bodyBottom - 24 - bodyTop - 14, 3);
        setRows(scanRows());
        shownScanGeneration = ParticleScan.generation();

        editZoneButton = button(Style.SECONDARY, Component.translatable("particlefilter.scan.edit_zone"), cx + cw - 72, bodyBottom - 18, 72, 18,
            () -> {
                Zone zone = scanTargetZone();
                if (zone != null) minecraft.setScreen(new ZoneEditScreen(this, zone));
            });
    }

    private Component scanLabel() {
        return ParticleScan.isRunning()
            ? Component.translatable("particlefilter.scan.running", ParticleScan.secondsLeft())
            : Component.translatable("particlefilter.scan.button");
    }

    private int everywhereColumn() {
        return panel.contentWidth() - 2 * COLUMN;
    }

    private int hereColumn() {
        return panel.contentWidth() - COLUMN;
    }

    private List<Row> scanRows() {
        List<Row> rows = new ArrayList<>();
        BlockPos target = ParticleScan.target();
        int most = ParticleScan.results().stream().mapToInt(ParticleScan.Result::count).max().orElse(1);
        int everywhereX = everywhereColumn();
        for (ParticleScan.Result result : ParticleScan.results()) {
            String particle = result.particle();
            String shownName = ParticleScan.displayName(particle);
            String count = "×" + result.count();
            String name = Theme.fit(font, shownName, everywhereX - 26 - font.width(count) - 8);
            int color = result.typed() ? Theme.TYPED : Theme.UNTYPED;
            float share = result.count() / (float) most;
            Row row = new Row(ROW_HEIGHT, (g, x, y, w, h, hovered) -> {
                Theme.rect(g, x, y, w, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
                // How often it appeared compared to the most frequent particle.
                g.fill(x + 19, y + h - 3, x + 19 + Math.max(2, Math.round((everywhereX - 27) * share)), y + h - 2, Theme.fade(color, 0.55f));
                Theme.dot(g, x + 8, y + (h - 5) / 2, 5, color);
                Theme.text(g, font, name, x + 19, y + (h - 8) / 2, Theme.TEXT);
                Theme.text(g, font, count, x + everywhereX - 8 - font.width(count), y + (h - 8) / 2, Theme.TEXT_DIM);
            }).tooltip(Component.translatable(result.typed() ? "particlefilter.particle.typed.tooltip" : "particlefilter.particle.untyped.tooltip", particle));

            row.add(new Toggle(0, 0, PfConfig.isHiddenEverywhere(particle), Component.translatable("particlefilter.scan.col_everywhere"),
                hidden -> PfConfig.setHiddenEverywhere(particle, hidden))
                .tooltip(Component.translatable("particlefilter.scan.everywhere.tooltip", shownName)), everywhereX + (COLUMN - Toggle.WIDTH) / 2);

            Toggle here = new Toggle(0, 0, target != null && PfConfig.isHiddenInZoneAt(target, particle), Component.translatable("particlefilter.scan.col_zone"),
                hidden -> PfConfig.setHiddenInZoneAt(target, particle, hidden))
                .tooltip(Component.translatable("particlefilter.scan.zone.tooltip", shownName));
            here.active = target != null;
            row.add(here, hereColumn() + (COLUMN - Toggle.WIDTH) / 2);
            rows.add(row);
        }
        return rows;
    }

    private static Zone scanTargetZone() {
        BlockPos target = ParticleScan.target();
        return target == null ? null : PfConfig.zoneAt(target);
    }

    private void renderScan(GuiGraphics g) {
        Component status;
        if (ParticleScan.isRunning()) status = Component.translatable("particlefilter.scan.status_running", ParticleScan.targetName(), ParticleScan.RADIUS);
        else if (ParticleScan.target() != null) status = Component.translatable(ParticleScan.results().size() == 1
            ? "particlefilter.scan.status_done_one" : "particlefilter.scan.status_done", ParticleScan.results().size(), ParticleScan.targetName());
        else status = Component.translatable("particlefilter.scan.status_idle");
        renderHeader(g, Component.translatable("particlefilter.scan.title"), status, 96);

        if (panel.isEmpty()) {
            int centerY = (bodyTop + bodyBottom - 24) / 2 - 20;
            if (ParticleScan.isRunning()) {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_running"),
                    Component.translatable("particlefilter.scan.empty_running.body", ParticleScan.RADIUS), cx + cw / 2, centerY, cw);
            } else if (ParticleScan.target() == null) {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_first"),
                    Component.translatable("particlefilter.scan.empty_first.body", ParticleScan.RADIUS), cx + cw / 2, centerY, cw);
            } else {
                drawEmptyState(g, Component.translatable("particlefilter.scan.empty_none"),
                    Component.translatable("particlefilter.scan.empty_none.body", ParticleScan.RADIUS), cx + cw / 2, centerY, cw);
            }
        } else {
            int labelY = bodyTop + 2;
            Theme.text(g, font, Theme.caps("particlefilter.scan.col_particle"), cx + 2, labelY, Theme.TEXT_FAINT);
            Theme.centered(g, font, Theme.caps("particlefilter.scan.col_everywhere"), cx + everywhereColumn() + COLUMN / 2, labelY, Theme.TEXT_FAINT);
            Theme.centered(g, font, Theme.caps("particlefilter.scan.col_zone"), cx + hereColumn() + COLUMN / 2, labelY, Theme.TEXT_FAINT);
        }

        // What "Here" refers to: the zone around the scanned spot, or the one that would be created (cyan preview).
        BlockPos target = ParticleScan.target();
        Zone zone = scanTargetZone();
        ZoneRenderer.highlighted = zone;
        ZoneRenderer.preview = null;
        editZoneButton.active = zone != null;
        Component here = null;
        int color = Theme.ZONE;
        if (zone != null) {
            here = Component.translatable("particlefilter.scan.zone_existing", zone.name, zone.sizeText());
        } else if (target != null) {
            Zone wouldCreate = PfConfig.defaultZoneAround(target);
            if (wouldCreate != null) {
                ZoneRenderer.preview = wouldCreate.box();
                here = Component.translatable("particlefilter.scan.zone_new", wouldCreate.sizeText());
                color = Theme.NEW_ZONE;
            }
        }
        if (here != null) {
            int y = bodyBottom - 13;
            Theme.dot(g, cx + 2, y + 1, 5, color);
            Theme.text(g, font, Theme.fit(font, here.getString(), cw - 92), cx + 12, y, Theme.TEXT_DIM);
        }
    }

    // ---------------------------------------------------------------- Hidden

    private void initHidden() {
        int buttonWidth = 80;
        idInput = addRenderableWidget(new TextField(font, cx, bodyTop, cw - 2 * buttonWidth - 8, 20, Component.translatable("particlefilter.hidden.input")));
        idInput.setMaxLength(256);
        idInput.setHint(Component.translatable("particlefilter.hidden.input_hint").withColor(Theme.TEXT_FAINT & 0xFFFFFF));
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

    private List<Row> hiddenRows() {
        List<Row> rows = new ArrayList<>();
        FilterMode mode = PfConfig.mode();
        Component pausedTip = Component.translatable("particlefilter.paused.tooltip", Component.translatable(mode.key()));

        List<String> everywhere = PfConfig.hiddenParticles();
        if (!everywhere.isEmpty()) {
            rows.add(section(Theme.caps("particlefilter.hidden.section_everywhere", everywhere.size()), !mode.global, pausedTip, null));
            for (String p : everywhere) {
                rows.add(entry(ParticleScan.displayName(p), p, particleColor(p), !mode.global, null, () -> PfConfig.setHiddenEverywhere(p, false)));
            }
        }

        List<Zone> zones = new ArrayList<>(PfConfig.zones());
        zones.removeIf(z -> z.particles.isEmpty());
        zones.sort(Comparator.comparing(z -> !PfConfig.isInCurrentWorld(z))); // this world's zones first
        for (Zone zone : zones) {
            boolean here = PfConfig.isInCurrentWorld(zone);
            String label = Theme.caps(here ? "particlefilter.hidden.section_zone" : "particlefilter.hidden.section_zone_elsewhere")
                + " · " + zone.name + " · " + zone.particles.size();
            rows.add(section(label, !mode.zones, pausedTip, here ? zone : null));
            for (String p : List.copyOf(zone.particles)) {
                rows.add(entry(ParticleScan.displayName(p), p, particleColor(p), !mode.zones, here ? zone : null, () -> PfConfig.setHiddenInZone(zone, p, false)));
            }
        }

        List<String> blocks = PfConfig.hiddenBlocks();
        if (!blocks.isEmpty()) {
            rows.add(section(Theme.caps("particlefilter.hidden.section_blocks", blocks.size()), !mode.global, pausedTip, null));
            for (String b : blocks) {
                rows.add(entry(b, null, Theme.BLOCK, !mode.global, null, () -> PfConfig.setBlockHidden(b, false)));
            }
        }
        return rows;
    }

    private static int particleColor(String particle) {
        return particle.contains(":") ? Theme.TYPED : Theme.UNTYPED;
    }

    /** Section label; zone sections get an Edit button. */
    private Row section(String label, boolean paused, Component pausedTip, Zone editable) {
        String pausedText = Theme.caps("particlefilter.paused");
        int maxLabel = panel.contentWidth() - 4 - (editable != null ? 44 : 0) - (paused ? font.width(pausedText) + 14 : 0);
        String shown = Theme.fit(font, label, maxLabel);
        int top = 8;
        Row row = new Row(22, (g, x, y, w, h, hovered) -> {
            Theme.text(g, font, shown, x + 2, y + top, editable != null ? Theme.ZONE : Theme.TEXT_FAINT);
            if (paused) Theme.pill(g, font, pausedText, x + 8 + font.width(shown), y + top - 2, Theme.fade(Theme.WARNING, 0.16f), Theme.WARNING);
            if (hovered && editable != null) ZoneRenderer.highlighted = editable;
        });
        if (paused) row.tooltip(pausedTip);
        if (editable != null) {
            row.add(rowButton(Style.GHOST, Component.translatable("particlefilter.edit"), 40,
                () -> minecraft.setScreen(new ZoneEditScreen(this, editable))), panel.contentWidth() - 40, 4);
        }
        return row;
    }

    /** One hidden entry with an Unhide button. [id] is shown as a tooltip when it differs from [name]. */
    private Row entry(String name, String id, int color, boolean paused, Zone zone, Runnable unhide) {
        int w = panel.contentWidth();
        String shown = Theme.fit(font, name, w - 80);
        Row row = new Row(20, (g, x, y, rw, h, hovered) -> {
            Theme.rect(g, x, y, rw, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
            Theme.dot(g, x + 8, y + (h - 5) / 2, 5, paused ? Theme.fade(color, 0.35f) : color);
            Theme.text(g, font, shown, x + 19, y + (h - 8) / 2, paused ? Theme.TEXT_FAINT : Theme.TEXT);
            if (hovered && zone != null) ZoneRenderer.highlighted = zone;
        });
        if (id != null && !id.equals(name)) row.tooltip(Component.literal(id));
        row.add(rowButton(Style.GHOST, Component.translatable("particlefilter.unhide"), 52, () -> {
            unhide.run();
            refreshNextTick();
        }), w - 54);
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

        addPanel(cx, bodyTop, cw, bodyBottom - bodyTop - 26, 3);
        List<Row> rows = new ArrayList<>();
        int w = panel.contentWidth();
        for (Zone zone : PfConfig.zonesHere()) {
            String name = Theme.fit(font, zone.name, w - 120);
            Row row = new Row(32, (g, x, y, rw, h, hovered) -> {
                Theme.rect(g, x, y, rw, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
                Theme.rect(g, x, y + 6, 2, h - 12, 1, Theme.ZONE);
                Theme.text(g, font, name, x + 10, y + 6, Theme.TEXT);
                String details = Component.translatable("particlefilter.zones.details", zone.sizeText(), zone.x + ", " + zone.y + ", " + zone.z,
                    zone.particles.size()).getString();
                Theme.text(g, font, Theme.fit(font, details, rw - 120), x + 10, y + 18, Theme.TEXT_DIM);
                if (hovered) ZoneRenderer.highlighted = zone;
            });
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

        addRenderableWidget(new Toggle(cx, bodyBottom - 16, ZoneRenderer.alwaysShow, Component.translatable("particlefilter.zones.always_show"),
            on -> ZoneRenderer.alwaysShow = on)).tooltip(Component.translatable("particlefilter.zones.always_show.tooltip"));
    }

    private void renderZones(GuiGraphics g) {
        renderHeader(g, Component.translatable("particlefilter.zones.title"), Component.translatable("particlefilter.zones.subtitle"), 96);
        if (panel.isEmpty()) {
            drawEmptyState(g, Component.translatable("particlefilter.zones.empty"), Component.translatable("particlefilter.zones.empty.body"),
                cx + cw / 2, (bodyTop + bodyBottom - 26) / 2 - 20, cw);
        }
        Theme.text(g, font, Theme.fit(font, Component.translatable("particlefilter.zones.always_show").getString(), cw - 32),
            cx + Toggle.WIDTH + 8, bodyBottom - 13, Theme.TEXT_DIM);
    }

    // ----------------------------------------------------------------

    /** Page title and subtitle; [actionsWidth] is kept free on the right for the page's buttons. */
    private void renderHeader(GuiGraphics g, Component title, Component subtitle, int actionsWidth) {
        int maxWidth = (actionsWidth > 0 ? actionsRight - actionsWidth : wx + ww - 30) - cx - 8;
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
        if (tab != Tab.SCAN || scanButton == null) return;
        if (ParticleScan.generation() != shownScanGeneration) refresh(); // a scan finished: show its results
        else if (ParticleScan.isRunning()) scanButton.setMessage(scanLabel());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (tab != Tab.SCAN) ZoneRenderer.highlighted = null; // rows set it again while hovered
        updateBadges();
        if (scanButton != null) scanButton.setProgress(ParticleScan.isRunning() ? ParticleScan.progress() : -1);
        super.render(g, mouseX, mouseY, partialTick);

        // Sidebar title and the Active rules label.
        Theme.dot(g, wx + 12, wy + 14, 6, Theme.ACCENT);
        Theme.text(g, font, title, wx + 23, wy + 13, Theme.TEXT);
        Theme.text(g, font, Theme.caps("particlefilter.mode.title"), wx + 10, modesTop - 12, Theme.TEXT_FAINT);

        switch (tab) {
            case SCAN -> renderScan(g);
            case HIDDEN -> renderHidden(g);
            case ZONES -> renderZones(g);
        }
    }
}
