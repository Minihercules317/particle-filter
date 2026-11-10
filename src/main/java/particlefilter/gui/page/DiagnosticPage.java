package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.PfScreen;
import particlefilter.gui.Theme;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.Toggle;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.DiagnosticScan;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.List;

/** Records every particle around you, both those still appearing and those your rules hide. */
public class DiagnosticPage extends ResultsPage {

    /** Gap between the footer line and the source distance label right of it. */
    private static final int FOOTER_GAP = 12;

    public DiagnosticPage(ParticleFilterScreen screen) {
        super(screen);
    }

    @Override
    public Component title() {
        return Component.translatable("particlefilter.diagnostic.title");
    }

    @Override
    public Component subtitle() {
        int radius = DiagnosticScan.radius();
        if (DiagnosticScan.isRunning()) {
            return Component.translatable("particlefilter.diagnostic.status_running", radius);
        } else if (DiagnosticScan.hasRun()) {
            return Component.translatable("particlefilter.diagnostic.status_done", DiagnosticScan.appearing().size(), DiagnosticScan.hidden().size(), radius);
        }
        return Component.translatable("particlefilter.diagnostic.status_idle");
    }

    @Override
    protected int generation() {
        return DiagnosticScan.generation();
    }

    @Override
    protected boolean isRunning() {
        return DiagnosticScan.isRunning();
    }

    @Override
    protected float progress() {
        return DiagnosticScan.progress();
    }

    @Override
    protected Component buttonLabel() {
        return DiagnosticScan.isRunning()
            ? Component.translatable("particlefilter.scan.running", DiagnosticScan.secondsLeft())
            : Component.translatable("particlefilter.diagnostic.button");
    }

    @Override
    public void beforeRender() {
        ZoneRenderer.clear(); // rows set them again while hovered
        super.beforeRender();
    }

    @Override
    public void init() {
        scanButton = screen.headerButton(Style.PRIMARY, buttonLabel(), () -> {
            DiagnosticScan.start();
            screen.refresh();
        }).tooltip(Component.translatable("particlefilter.diagnostic.button.tooltip", renderDistanceBlocks(), PfConfig.diagnosticSeconds()));
        scanButton.active = !DiagnosticScan.isRunning();
        addDurationSpinner(PfConfig.diagnosticSeconds(), seconds -> {
            PfConfig.setDiagnosticSeconds(seconds);
            scanButton.tooltip(Component.translatable("particlefilter.diagnostic.button.tooltip", renderDistanceBlocks(), seconds));
        });

        addList();
        screen.setRows(diagnosticRows());
        shownScanGeneration = DiagnosticScan.generation();

        if (!screen.panel().isEmpty()) { // with the footer line, which the empty states leave out
            screen.addSpinner(sourceSpinnerX(), sourceSpinnerY(), DURATION_WIDTH, PfConfig.sourceDistance(),
                PfConfig.MIN_SOURCE_DISTANCE, PfConfig.MAX_SOURCE_DISTANCE,
                Component.translatable("particlefilter.diagnostic.source_distance.tooltip", PfConfig.MIN_SOURCE_DISTANCE, PfConfig.MAX_SOURCE_DISTANCE),
                blocks -> {
                    DiagnosticScan.setSourceDistance(blocks);
                    screen.refreshNextTick(); // previews, notices and zones follow the new distance
                });
        }
    }

    /** Left edge of the source distance spinner at the bottom right, before "blocks". */
    private int sourceSpinnerX() {
        return screen.cx() + screen.cw() - screen.font().width(Component.translatable("particlefilter.diagnostic.source_distance_unit")) - UNIT_GAP - DURATION_WIDTH;
    }

    /** Top of the source distance spinner, centered on the footer line. */
    private int sourceSpinnerY() {
        return screen.bodyBottom() - FOOTER_LINE - (PfScreen.SMALL_BUTTON_HEIGHT - 8) / 2;
    }

    /** Left edge of "Same source within", before the spinner. */
    private int sourceLabelX() {
        return sourceSpinnerX() - DURATION_GAP - screen.font().width(Component.translatable("particlefilter.diagnostic.source_distance"));
    }

    private int renderDistanceBlocks() {
        return screen.minecraft().options.getEffectiveRenderDistance() * 16;
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
        rows.add(screen.sectionRow(Theme.caps("particlefilter.diagnostic.section_appearing", appearing.size())));
        if (appearing.isEmpty()) rows.add(screen.hintRow(Component.translatable("particlefilter.diagnostic.none_appearing"), screen.panel().contentWidth()));
        for (DiagnosticScan.Result r : appearing) {
            rows.add(diagnosticRow(r, most, false));
            int sources = DiagnosticScan.sourcesOf(r.particle()).size();
            if (sources > DiagnosticScan.MAX_SOURCES && !PfConfig.isHiddenEverywhere(r.particle())) {
                String particle = r.particle();
                rows.add(noticeRow(Component.translatable("particlefilter.diagnostic.many_sources", ParticleScan.displayName(particle), sources, DiagnosticScan.MAX_SOURCES),
                    noticeTextRight(), () -> ZoneRenderer.previewMore = DiagnosticScan.previewFor(particle)));
            }
            DiagnosticScan.Growth growth = DiagnosticScan.growthFor(r.particle());
            if (growth != null) {
                rows.add(growRow(Component.translatable("particlefilter.diagnostic.grow_notice", growth.zone().name, ParticleScan.displayName(r.particle())),
                    growTooltip(growth.zone(), growth.box()), () -> DiagnosticScan.grow(growth), () -> {
                        ZoneRenderer.highlighted = growth.zone();
                        ZoneRenderer.preview = growth.box().aabb();
                    }));
            }
        }
        if (!hidden.isEmpty()) {
            rows.add(screen.sectionRow(Theme.caps("particlefilter.diagnostic.section_hidden", hidden.size())));
            for (DiagnosticScan.Result r : hidden) rows.add(diagnosticRow(r, most, true));
        }
        return rows;
    }

    private Row diagnosticRow(DiagnosticScan.Result result, int most, boolean wasHidden) {
        String particle = result.particle();
        long distance = Math.round(result.nearest());
        String nearest = Component.translatable(distance == 1 ? "particlefilter.scan.blocks_away_one" : "particlefilter.scan.blocks_away", distance).getString();
        Row base = resultRow(particle, result.typed(), result.count(), most, 3, wasHidden, nearest);
        Row row = new Row(base::height, (g, x, y, w, h, hovered) -> {
            base.paint(g, x, y, w, h, hovered);
            if (hovered) {
                List<Zone> zones = DiagnosticScan.zonesFor(particle);
                ZoneRenderer.highlightedMore = zones;
                ZoneRenderer.previewMore = zones.isEmpty() ? DiagnosticScan.previewFor(particle) : List.of();
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
            screen.refreshNextTick();
        }).tooltip(Component.translatable("particlefilter.diagnostic.here.tooltip", ParticleScan.displayName(particle))), column(2, 3) + (COLUMN - Toggle.WIDTH) / 2);
        return row;
    }

    private static String boxText(net.minecraft.world.phys.AABB box) {
        return (int) Math.floor(box.minX) + ", " + (int) Math.floor(box.minY) + ", " + (int) Math.floor(box.minZ) + " → "
            + (int) Math.floor(box.maxX) + ", " + (int) Math.floor(box.maxY) + ", " + (int) Math.floor(box.maxZ);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        int cx = screen.cx(), cw = screen.cw();
        int radius = DiagnosticScan.radius();
        renderDurationLabels(g);

        if (screen.panel().isEmpty()) {
            int centerY = emptyStateTop(listTop(), listBottom());
            if (DiagnosticScan.isRunning()) {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_running"),
                    Component.translatable("particlefilter.diagnostic.empty_running.body", radius), cx + cw / 2, centerY, cw);
            } else if (!DiagnosticScan.hasRun()) {
                screen.drawEmptyState(g, Component.translatable("particlefilter.diagnostic.empty_first"),
                    Component.translatable("particlefilter.diagnostic.empty_first.body", renderDistanceBlocks(), PfConfig.diagnosticSeconds()), cx + cw / 2, centerY, cw);
            } else {
                screen.drawEmptyState(g, Component.translatable("particlefilter.scan.empty_none"),
                    Component.translatable("particlefilter.scan.empty_none.body", radius), cx + cw / 2, centerY, cw);
            }
            return;
        }
        renderColumnLabels(g, "particlefilter.scan.col_nearest", "particlefilter.scan.col_everywhere", "particlefilter.scan.col_zone");
        boolean capped = DiagnosticScan.anyCapped();
        int labelX = sourceLabelX();
        renderFooterLine(g, mouseX, mouseY, capped ? Theme.WARNING : Theme.NEW_ZONE,
            Component.translatable(capped ? "particlefilter.diagnostic.here_capped" : "particlefilter.diagnostic.here_line"),
            Component.translatable("particlefilter.diagnostic.here_line.tooltip", DiagnosticScan.MAX_SOURCES, Zone.MAX_SIZE),
            labelX - FOOTER_GAP - cx - DOT_TEXT_X);
        var font = screen.font();
        int textY = screen.bodyBottom() - FOOTER_LINE;
        Theme.text(g, font, Component.translatable("particlefilter.diagnostic.source_distance"), labelX, textY, Theme.TEXT_DIM);
        Theme.text(g, font, Component.translatable("particlefilter.diagnostic.source_distance_unit"), sourceSpinnerX() + DURATION_WIDTH + UNIT_GAP, textY, Theme.TEXT_DIM);
    }
}
