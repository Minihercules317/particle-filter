package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.PfScreen;
import particlefilter.gui.Theme;
import particlefilter.gui.ZoneEditScreen;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.Slider;
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

    /** Gap between "seconds" and the radius control after it. */
    private static final int RADIUS_GAP = 14;
    /** Narrowest and widest the radius slider gets; it takes the room left on the line between them. */
    private static final int RADIUS_SLIDER_MIN = 40;
    private static final int RADIUS_SLIDER_MAX = 100;

    private FlatButton editZoneButton;
    private Slider radiusSlider;
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
            return Component.translatable("particlefilter.scan.status_running", ParticleScan.targetName(), ParticleScan.radius());
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
        return Component.translatable("particlefilter.scan.button.tooltip", PfConfig.scanRadius(), PfConfig.scanSeconds());
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
        radiusSlider = screen.addRenderableWidget(new Slider(radiusSliderX(), screen.bodyTop() + (PfScreen.SMALL_BUTTON_HEIGHT - Slider.HEIGHT) / 2,
            radiusSliderWidth(), PfConfig.scanRadius(), PfConfig.MIN_SCAN_RADIUS, PfConfig.MAX_SCAN_RADIUS,
            Component.translatable("particlefilter.scan.radius"), blocks -> {
                PfConfig.setScanRadius(blocks);
                scanButton.tooltip(scanTooltip());
            }).tooltip(Component.translatable("particlefilter.scan.radius.tooltip", PfConfig.MIN_SCAN_RADIUS, PfConfig.MAX_SCAN_RADIUS)));

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

    // ---------------------------------------------------------------- Radius: on the duration's line, after it

    private int radiusLabelX() {
        return durationRowRight() + RADIUS_GAP;
    }

    private int radiusSliderX() {
        return radiusLabelX() + screen.font().width(Component.translatable("particlefilter.scan.radius")) + DURATION_GAP;
    }

    /** The room left before the value at the line's right end, kept between the narrowest and widest slider. */
    private int radiusSliderWidth() {
        int widestValue = screen.font().width(radiusText(PfConfig.MAX_SCAN_RADIUS));
        int room = screen.cx() + screen.cw() - widestValue - UNIT_GAP - radiusSliderX();
        return Math.max(RADIUS_SLIDER_MIN, Math.min(RADIUS_SLIDER_MAX, room));
    }

    private static Component radiusText(int blocks) {
        return Component.translatable(blocks == 1 ? "particlefilter.scan.radius_value_one" : "particlefilter.scan.radius_value", blocks);
    }

    private void renderRadiusLabels(GuiGraphics g) {
        var font = screen.font();
        int textY = screen.bodyTop() + 4;
        Theme.text(g, font, Component.translatable("particlefilter.scan.radius"), radiusLabelX(), textY, Theme.TEXT_DIM);
        int blocks = radiusSlider != null ? radiusSlider.intValue() : PfConfig.scanRadius();
        Theme.text(g, font, radiusText(blocks), radiusSliderX() + radiusSliderWidth() + UNIT_GAP, textY, Theme.TEXT);
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
        renderRadiusLabels(g);

        if (screen.panel().isEmpty()) {
            int centerY = emptyStateTop(listTop(), listBottom());
            if (ParticleScan.isRunning()) {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_running"),
                    Component.translatable("particlefilter.scan.empty_running.body", ParticleScan.radius()), cx + cw / 2, centerY, cw);
            } else if (ParticleScan.target() == null) {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_first"),
                    Component.translatable("particlefilter.scan.empty_first.body", PfConfig.scanRadius(), PfConfig.scanSeconds()), cx + cw / 2, centerY, cw);
            } else {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_none"),
                    Component.translatable("particlefilter.scan.empty_none.body", ParticleScan.radius()), cx + cw / 2, centerY, cw);
            }
        } else {
            renderColumnLabels(g, "particlefilter.scan.col_everywhere", "particlefilter.scan.col_zone");
        }

        ZoneRenderer.clear();
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
