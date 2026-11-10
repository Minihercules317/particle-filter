package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.Theme;
import particlefilter.gui.ZoneEditScreen;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.Toggle;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.ParticleScan;
import particlefilter.scan.ZoneFit;

import java.util.ArrayList;
import java.util.List;

import static particlefilter.gui.PfScreen.COMPACT_HEIGHT;

/** Records which particles spawn around what you're looking at, to hide them everywhere or in a zone there. */
public class ScanPage extends ResultsPage {

    /** Width of the Edit zone button at the bottom right. */
    private static final int EDIT_ZONE_WIDTH = 64;
    /** Width of the button left of Edit zone that picks which zone Here uses. */
    private static final int HERE_WIDTH = 76;
    /** Gap between those two buttons. */
    private static final int BUTTON_GAP = 4;
    /** Width both buttons take together, kept clear of the Here line beside them. */
    private static final int FOOTER_BUTTONS_WIDTH = HERE_WIDTH + BUTTON_GAP + EDIT_ZONE_WIDTH;

    private FlatButton editZoneButton;
    /** Set while the grow notice is hovered (the list paints before {@link #render}), to outline the grown zone. */
    private boolean growHovered;

    public ScanPage(ParticleFilterScreen screen) {
        super(screen);
    }

    @Override
    public Component title() {
        return Component.translatable("particlefilter.scan.title");
    }

    @Override
    public Component subtitle() {
        int found = ParticleScan.results().size();
        if (ParticleScan.isRunning()) {
            return Component.translatable("particlefilter.scan.status_running", ParticleScan.targetName(), ParticleScan.RADIUS);
        } else if (ParticleScan.target() != null) {
            return Component.translatable(found == 1 ? "particlefilter.scan.status_done_one" : "particlefilter.scan.status_done", found, ParticleScan.targetName());
        }
        return Component.translatable("particlefilter.scan.status_idle");
    }

    @Override
    protected int generation() {
        return ParticleScan.generation();
    }

    @Override
    protected boolean isRunning() {
        return ParticleScan.isRunning();
    }

    @Override
    protected float progress() {
        return ParticleScan.progress();
    }

    @Override
    protected Component buttonLabel() {
        return ParticleScan.isRunning()
            ? Component.translatable("particlefilter.scan.running", ParticleScan.secondsLeft())
            : Component.translatable("particlefilter.scan.button");
    }

    private static Component scanTooltip() {
        return Component.translatable("particlefilter.scan.button.tooltip", ParticleScan.RADIUS, PfConfig.scanSeconds());
    }

    @Override
    public void init() {
        int cx = screen.cx(), cw = screen.cw(), bodyBottom = screen.bodyBottom();
        editZoneButton = null;
        scanButton = screen.headerButton(Style.PRIMARY, buttonLabel(), () -> {
            ParticleScan.start();
            screen.refresh();
        }).tooltip(scanTooltip());
        scanButton.active = !ParticleScan.isRunning();
        addDurationSpinner(PfConfig.scanSeconds(), seconds -> {
            PfConfig.setScanSeconds(seconds);
            scanButton.tooltip(scanTooltip());
        });

        addList();
        screen.setRows(scanRows());
        shownScanGeneration = ParticleScan.generation();

        if (ParticleScan.target() != null) {
            int editX = cx + cw - EDIT_ZONE_WIDTH;
            int buttonY = bodyBottom - COMPACT_HEIGHT;
            editZoneButton = screen.button(Style.SECONDARY, Component.translatable("particlefilter.scan.edit_zone"), editX, buttonY, EDIT_ZONE_WIDTH, COMPACT_HEIGHT,
                () -> {
                    Zone zone = ParticleScan.hereZone();
                    if (zone != null) screen.minecraft().setScreen(new ZoneEditScreen(screen, zone));
                }).tooltip(Component.translatable("particlefilter.scan.edit_zone.tooltip"));
            Zone current = ParticleScan.hereZone();
            Zone next = ParticleScan.nextHereChoice();
            if (current != null || next != null) {
                Component label = next == null ? Component.translatable("particlefilter.scan.here_new")
                    : Component.literal(Theme.fit(screen.font(), Component.translatable("particlefilter.scan.here_use", next.name).getString(), HERE_WIDTH - 6));
                Component tip = next == null ? Component.translatable("particlefilter.scan.here_new.tooltip")
                    : Component.translatable("particlefilter.scan.here_use.tooltip", next.name);
                screen.button(Style.SECONDARY, label, editX - BUTTON_GAP - HERE_WIDTH, buttonY, HERE_WIDTH, COMPACT_HEIGHT, () -> {
                    ParticleScan.cycleHere();
                    screen.refresh();
                }).tooltip(tip);
            }
        }
    }

    /** One row per particle, with Everywhere and Here switches. */
    private List<Row> scanRows() {
        List<Row> rows = new ArrayList<>();
        boolean here = ParticleScan.target() != null;
        ZoneFit.Box grown = here ? ParticleScan.growBox() : null;
        Zone zone = ParticleScan.hereZone();
        if (grown != null && zone != null) {
            rows.add(growRow(Component.translatable("particlefilter.scan.grow_notice", zone.name), growTooltip(zone, grown),
                ParticleScan::growHereZone, () -> growHovered = true));
        }
        int most = ParticleScan.results().stream().mapToInt(ParticleScan.Result::count).max().orElse(1);
        for (ParticleScan.Result result : ParticleScan.results()) {
            String particle = result.particle();
            Row row = resultRow(particle, result.typed(), result.count(), most, 2, false, null);
            row.add(everywhereToggle(particle), column(0, 2) + (COLUMN - Toggle.WIDTH) / 2);
            if (here) {
                row.add(new Toggle(0, 0, ParticleScan.isHiddenHere(particle), Component.translatable("particlefilter.scan.col_zone"),
                    hidden -> {
                        ParticleScan.setHiddenHere(particle, hidden);
                        screen.refreshNextTick(); // the Here line and buttons follow a zone created or removed
                    }).tooltip(Component.translatable("particlefilter.scan.zone.tooltip", ParticleScan.displayName(particle))),
                    column(1, 2) + (COLUMN - Toggle.WIDTH) / 2);
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        int cx = screen.cx(), cw = screen.cw();
        renderDurationLabels(g);

        if (screen.panel().isEmpty()) {
            int centerY = emptyStateTop(listTop(), listBottom());
            if (ParticleScan.isRunning()) {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_running"),
                    Component.translatable("particlefilter.scan.empty_running.body", ParticleScan.RADIUS), cx + cw / 2, centerY, cw);
            } else if (ParticleScan.target() == null) {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_first"),
                    Component.translatable("particlefilter.scan.empty_first.body", ParticleScan.RADIUS, PfConfig.scanSeconds()), cx + cw / 2, centerY, cw);
            } else {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_none"),
                    Component.translatable("particlefilter.scan.empty_none.body", ParticleScan.RADIUS), cx + cw / 2, centerY, cw);
            }
        } else {
            renderColumnLabels(g, "particlefilter.scan.col_everywhere", "particlefilter.scan.col_zone");
        }

        ZoneRenderer.highlighted = null;
        ZoneRenderer.preview = null;
        if (ParticleScan.target() == null) return;
        // What Here refers to: a zone around the scanned block, or a new one fitted to the particles (cyan preview).
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
        renderFooterLine(g, mouseX, mouseY, color, here, tip, cw - FOOTER_BUTTONS_WIDTH - 16);
        if (growHovered) {
            ZoneFit.Box grown = ParticleScan.growBox();
            if (grown != null) ZoneRenderer.preview = grown.aabb();
            growHovered = false;
        }
    }
}
