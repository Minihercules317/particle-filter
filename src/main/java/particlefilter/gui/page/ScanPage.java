package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
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
import particlefilter.filter.FilterRules;
import particlefilter.filter.ParticleKey;
import particlefilter.filter.ParticlePattern;
import particlefilter.gui.RuleNames;
import particlefilter.loader.ModNames;
import particlefilter.scan.FittedZones;
import particlefilter.scan.ParticleScan;
import particlefilter.scan.ResultTree;
import particlefilter.scan.ZoneFit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static particlefilter.gui.PfScreen.CARD_DOT_X;
import static particlefilter.gui.PfScreen.CARD_RADIUS;
import static particlefilter.gui.PfScreen.CARD_TEXT_X;
import static particlefilter.gui.PfScreen.COMPACT_HEIGHT;
import static particlefilter.gui.PfScreen.DOT_SIZE;
import static particlefilter.gui.PfScreen.SMALL_BUTTON_HEIGHT;

/**
 * Records which particles spawn around what you're looking at, both those still appearing and those your rules hide,
 * to hide them everywhere or in zones there.
 *
 * Below the header: the scan's length and radius, then a Zone options switch that shows the settings for the zones Here
 * fits (how close spots count as one source, how many zones a particle gets, when zones merge) and the number of zones
 * beside each Here switch. Below those, the results with Everywhere and Here switches, then a line saying where Here
 * hides particles, with buttons to pick that zone and edit it.
 */
public class ScanPage extends Page {

    /** Width of each switch column. */
    private static final int COLUMN = 58;
    private static final int COLUMNS = 2;
    /** Width of the scan button in the header. */
    private static final int SCAN_BUTTON_WIDTH = 80;
    /** Height of a line of controls below the header, with the gap below it. */
    private static final int CONTROL_ROW = SMALL_BUTTON_HEIGHT + 4;
    /** Space from the last line of controls to the list, for the column labels. */
    private static final int LABELS_SPACE = 20;
    /** How far the column labels sit above the list. */
    private static final int COLUMN_LABELS_ABOVE = 12;
    /** Width of the number spinners. */
    private static final int SPINNER_WIDTH = 62;
    /** Gap between a control's label and the control. */
    private static final int LABEL_GAP = 6;
    /** Gap between a control and the unit or value after it ("seconds"). */
    private static final int UNIT_GAP = 5;
    /** Gap between controls sharing a line. */
    private static final int GROUP_GAP = 14;
    /** Narrowest and widest the radius slider gets; it takes the room left on its line between them. */
    private static final int RADIUS_SLIDER_MIN = 40;
    private static final int RADIUS_SLIDER_MAX = 100;
    /** Width of the merge slider. */
    private static final int MERGE_SLIDER_WIDTH = 80;
    /** Room left and right of the Zone options label inside its button. */
    private static final int OPTIONS_PAD = 6;
    /** Text top inside a line of controls. */
    private static final int CONTROL_TEXT_Y = (SMALL_BUTTON_HEIGHT - 8) / 2;

    /** Space above a result row's name, and the same below. */
    private static final int RESULT_TEXT_Y = 7;
    /** Gap between a result row's count and its first column; the frequency bar ends at the count's right edge. */
    private static final int COUNT_GAP = 8;
    /** Smallest gap between a result row's name and its count. */
    private static final int NAME_GAP = 7;
    /** How far each level of the tree is indented. */
    private static final int INDENT = 10;
    /** Gap between the Here switch and the number of zones beside it. */
    private static final int ZONE_COUNT_GAP = 3;
    /** Width of a grow notice's Grow to fit button. */
    private static final int GROW_WIDTH = 72;
    /** Gap between a notice's text and its button, and between the button and the card's right edge. */
    private static final int NOTICE_GAP = 8;
    /** Space above a notice's text, and the same below. */
    private static final int NOTICE_TEXT_Y = 6;

    /** Width of the Edit zone button at the bottom right. */
    private static final int EDIT_ZONE_WIDTH = 64;
    /** Width of the button left of Edit zone that picks where Here hides particles. */
    private static final int HERE_WIDTH = 76;
    /** Gap between those two buttons. */
    private static final int BUTTON_GAP = 4;
    /** Width both buttons take together, kept clear of the Here line beside them. */
    private static final int FOOTER_BUTTONS_WIDTH = HERE_WIDTH + BUTTON_GAP + EDIT_ZONE_WIDTH;
    /** Gap between the Here line and those buttons. */
    private static final int FOOTER_GAP = 16;

    private FlatButton scanButton;
    private int shownScanGeneration;
    private FlatButton editZoneButton;
    private Slider radiusSlider;
    private Slider mergeSlider;
    /** The Zone options laid out in [init]: where each label, control and unit or value sits. */
    private final List<PlacedOption> options = new ArrayList<>();
    /** Top of the list, below the controls and the column labels; set in [init]. */
    private int listTop;
    /** Row hovered this frame (the list paints before {@link #render}), for the Here line and outlines. */
    private ResultTree.Node hoveredNode;
    /** Set while a notice is hovered this frame; it sets the outlines itself. */
    private boolean noticeHovered;

    public ScanPage(ParticleFilterScreen screen) {
        super(screen);
    }

    @Override
    public Component title() {
        return Component.translatable("particlefilter.scan.title");
    }

    @Override
    public Component subtitle() {
        List<ParticleScan.Result> results = ParticleScan.results();
        if (ParticleScan.isRunning()) {
            return Component.translatable("particlefilter.scan.status_running", ParticleScan.targetName(), ParticleScan.radius());
        } else if (ParticleScan.target() != null) {
            int found = results.size();
            long hidden = results.stream().filter(ParticleScan.Result::allHidden).count();
            if (hidden > 0) return Component.translatable("particlefilter.scan.status_done_hidden", found, ParticleScan.targetName(), hidden);
            return Component.translatable(found == 1 ? "particlefilter.scan.status_done_one" : "particlefilter.scan.status_done", found, ParticleScan.targetName());
        }
        return Component.translatable("particlefilter.scan.status_idle");
    }

    @Override
    public int actionsWidth() {
        return SCAN_BUTTON_WIDTH;
    }

    private Component buttonLabel() {
        return ParticleScan.isRunning()
            ? Component.translatable("particlefilter.scan.running", ParticleScan.secondsLeft())
            : Component.translatable("particlefilter.scan.button");
    }

    private static Component scanTooltip() {
        return Component.translatable("particlefilter.scan.button.tooltip", PfConfig.scanRadius(), PfConfig.scanSeconds());
    }

    /** Unlike the other pages, clears every outline: rows and notices set them while hovered, then {@link #render}. */
    @Override
    public void beforeRender() {
        ZoneRenderer.clear();
        if (scanButton != null) scanButton.setProgress(ParticleScan.isRunning() ? ParticleScan.progress() : -1);
    }

    @Override
    public void tick() {
        if (scanButton == null) return;
        if (ParticleScan.generation() != shownScanGeneration) screen.refresh(); // a scan started or finished
        else if (ParticleScan.isRunning()) scanButton.setMessage(buttonLabel());
    }

    @Override
    public void init() {
        int cx = screen.cx(), cw = screen.cw(), bodyTop = screen.bodyTop(), bodyBottom = screen.bodyBottom();
        editZoneButton = null;
        mergeSlider = null;
        scanButton = screen.headerButton(Style.PRIMARY, buttonLabel(), () -> {
            ParticleScan.start();
            screen.refresh();
        }).tooltip(scanTooltip());
        scanButton.active = !ParticleScan.isRunning();

        // Length and radius, always shown.
        screen.addSpinner(durationSpinnerX(), bodyTop, SPINNER_WIDTH, PfConfig.scanSeconds(), PfConfig.MIN_SCAN_SECONDS, PfConfig.MAX_SCAN_SECONDS,
            Component.translatable("particlefilter.scan.duration.tooltip", PfConfig.MIN_SCAN_SECONDS, PfConfig.MAX_SCAN_SECONDS), seconds -> {
                PfConfig.setScanSeconds(seconds);
                scanButton.tooltip(scanTooltip());
            });
        radiusSlider = screen.addRenderableWidget(new Slider(radiusSliderX(), bodyTop + (SMALL_BUTTON_HEIGHT - Slider.HEIGHT) / 2,
            radiusSliderWidth(), PfConfig.scanRadius(), PfConfig.MIN_SCAN_RADIUS, PfConfig.MAX_SCAN_RADIUS,
            Component.translatable("particlefilter.scan.radius"), blocks -> {
                PfConfig.setScanRadius(blocks);
                scanButton.tooltip(scanTooltip());
            }).tooltip(Component.translatable("particlefilter.scan.radius.tooltip", PfConfig.MIN_SCAN_RADIUS, PfConfig.MAX_SCAN_RADIUS)));

        // Zone options: a switch, and the settings below it while open.
        boolean open = PfConfig.zoneOptionsOpen();
        Component optionsLabel = Component.translatable(open ? "particlefilter.scan.options_open" : "particlefilter.scan.options_closed");
        int optionsY = bodyTop + CONTROL_ROW;
        screen.button(Style.GHOST, optionsLabel, cx, optionsY, screen.font().width(optionsLabel) + 2 * OPTIONS_PAD, SMALL_BUTTON_HEIGHT, () -> {
            PfConfig.setZoneOptionsOpen(!PfConfig.zoneOptionsOpen());
            screen.refresh();
        }).tooltip(Component.translatable("particlefilter.scan.options.tooltip"));
        int lastRowY = optionsY;
        options.clear();
        if (open) lastRowY = addOptions(optionsY + CONTROL_ROW);
        listTop = lastRowY + SMALL_BUTTON_HEIGHT + LABELS_SPACE;

        screen.addPanel(cx, listTop, cw, listBottom() - listTop, 3);
        screen.setRows(rows());
        shownScanGeneration = ParticleScan.generation();

        if (ParticleScan.target() != null) {
            int editX = cx + cw - EDIT_ZONE_WIDTH;
            int buttonY = bodyBottom - COMPACT_HEIGHT;
            editZoneButton = screen.button(Style.SECONDARY, Component.translatable("particlefilter.scan.edit_zone"), editX, buttonY, EDIT_ZONE_WIDTH, COMPACT_HEIGHT,
                () -> {
                    Zone zone = editableZone();
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

    /** Bottom of the result list, above the Here line and its buttons. */
    private int listBottom() {
        return screen.bodyBottom() - LIST_FOOTER;
    }

    // ---------------------------------------------------------------- Length and radius: one line below the header

    private int durationSpinnerX() {
        return screen.cx() + screen.font().width(Component.translatable("particlefilter.scan.duration")) + LABEL_GAP;
    }

    private int radiusLabelX() {
        int durationRight = durationSpinnerX() + SPINNER_WIDTH + UNIT_GAP + screen.font().width(Component.translatable("particlefilter.scan.duration_unit"));
        return durationRight + GROUP_GAP;
    }

    private int radiusSliderX() {
        return radiusLabelX() + screen.font().width(Component.translatable("particlefilter.scan.radius")) + LABEL_GAP;
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

    private void renderLengthAndRadius(GuiGraphics g) {
        var font = screen.font();
        int textY = screen.bodyTop() + CONTROL_TEXT_Y;
        Theme.text(g, font, Component.translatable("particlefilter.scan.duration"), screen.cx(), textY, Theme.TEXT_DIM);
        Theme.text(g, font, Component.translatable("particlefilter.scan.duration_unit"), durationSpinnerX() + SPINNER_WIDTH + UNIT_GAP, textY, Theme.TEXT_DIM);
        Theme.text(g, font, Component.translatable("particlefilter.scan.radius"), radiusLabelX(), textY, Theme.TEXT_DIM);
        int blocks = radiusSlider != null ? radiusSlider.intValue() : PfConfig.scanRadius();
        Theme.text(g, font, radiusText(blocks), radiusSliderX() + radiusSliderWidth() + UNIT_GAP, textY, Theme.TEXT);
    }

    // ---------------------------------------------------------------- Zone options

    /** One of the Zone options: its label, its control's width, and the unit after it (null for the merge value). */
    private record Option(String labelKey, int controlWidth, String unitKey) {}

    /** An option placed at (x, y): label at x, control after it. */
    private record PlacedOption(Option option, int x, int y) {}

    private static final Option SOURCE_DISTANCE = new Option("particlefilter.scan.source_distance", SPINNER_WIDTH, "particlefilter.scan.source_distance_unit");
    private static final Option MAX_ZONES = new Option("particlefilter.scan.max_zones", SPINNER_WIDTH, "particlefilter.scan.max_zones_unit");
    private static final Option MERGE = new Option("particlefilter.scan.merge", MERGE_SLIDER_WIDTH, null);

    private int controlX(PlacedOption placed) {
        return placed.x + screen.font().width(Component.translatable(placed.option.labelKey)) + LABEL_GAP;
    }

    /** Width of an option with its label and unit or widest value. */
    private int optionWidth(Option option) {
        var font = screen.font();
        int after = option.unitKey != null ? font.width(Component.translatable(option.unitKey)) : font.width(percentText(PfConfig.MAX_MERGE_PERCENT));
        return font.width(Component.translatable(option.labelKey)) + LABEL_GAP + option.controlWidth + UNIT_GAP + after;
    }

    /**
     * Lays out the Zone options from [top], side by side while they fit the page's width and on further lines after,
     * and adds their controls. Returns the top of the last line.
     */
    private int addOptions(int top) {
        int x = screen.cx(), y = top, right = screen.cx() + screen.cw();
        for (Option option : List.of(SOURCE_DISTANCE, MAX_ZONES, MERGE)) {
            int width = optionWidth(option);
            if (x > screen.cx() && x + width > right) {
                x = screen.cx();
                y += CONTROL_ROW;
            }
            options.add(new PlacedOption(option, x, y));
            x += width + GROUP_GAP;
        }
        for (PlacedOption placed : options) {
            int controlX = controlX(placed);
            if (placed.option == SOURCE_DISTANCE) {
                addSpinner(controlX, placed.y, PfConfig.sourceDistance(), PfConfig.MIN_SOURCE_DISTANCE, PfConfig.MAX_SOURCE_DISTANCE,
                    Component.translatable("particlefilter.scan.source_distance.tooltip", PfConfig.MIN_SOURCE_DISTANCE, PfConfig.MAX_SOURCE_DISTANCE),
                    ParticleScan::setSourceDistance);
            } else if (placed.option == MAX_ZONES) {
                addSpinner(controlX, placed.y, PfConfig.maxZones(), PfConfig.MIN_MAX_ZONES, PfConfig.MAX_MAX_ZONES,
                    Component.translatable("particlefilter.scan.max_zones.tooltip", PfConfig.MIN_MAX_ZONES, PfConfig.MAX_MAX_ZONES),
                    ParticleScan::setMaxZones);
            } else {
                // Zones follow the slider live; only the list is rebuilt, so the slider keeps its drag and focus.
                mergeSlider = screen.addRenderableWidget(new Slider(controlX, placed.y + (SMALL_BUTTON_HEIGHT - Slider.HEIGHT) / 2,
                    MERGE_SLIDER_WIDTH, PfConfig.mergePercent(), PfConfig.MIN_MERGE_PERCENT, PfConfig.MAX_MERGE_PERCENT,
                    Component.translatable("particlefilter.scan.merge"), percent -> {
                        ParticleScan.setMergePercent(percent);
                        screen.replaceRows(rows());
                    }).live(percent -> {
                        ParticleScan.previewMergePercent(percent);
                        screen.replaceRows(rows());
                    }).tooltip(Component.translatable("particlefilter.scan.merge.tooltip", Zone.MAX_SIZE)));
            }
        }
        return y;
    }

    /** A spinner whose new value regroups the zones; the page is laid out again so notices and counts follow. */
    private void addSpinner(int x, int y, int value, int min, int max, Component tooltip, IntConsumer onValue) {
        screen.addSpinner(x, y, SPINNER_WIDTH, value, min, max, tooltip, v -> {
            onValue.accept(v);
            screen.refreshNextTick();
        });
    }

    private static String percentText(int percent) {
        return percent + "%";
    }

    private void renderOptionLabels(GuiGraphics g) {
        var font = screen.font();
        for (PlacedOption placed : options) {
            int textY = placed.y + CONTROL_TEXT_Y;
            Theme.text(g, font, Component.translatable(placed.option.labelKey), placed.x, textY, Theme.TEXT_DIM);
            int afterX = controlX(placed) + placed.option.controlWidth + UNIT_GAP;
            if (placed.option.unitKey != null) {
                Theme.text(g, font, Component.translatable(placed.option.unitKey), afterX, textY, Theme.TEXT_DIM);
            } else {
                int percent = mergeSlider != null ? mergeSlider.intValue() : PfConfig.mergePercent();
                Theme.text(g, font, percentText(percent), afterX, textY, Theme.TEXT);
            }
        }
    }

    // ---------------------------------------------------------------- Results: a tree of folders

    /** Folders the player opened or closed, by {@link ResultTree.Node#id}; the rest use {@link #openByDefault}. */
    private static final Map<String, Boolean> opened = new HashMap<>();

    /** Mod folders and Other start open, so the particles show; the folders inside them start closed. */
    private static boolean openByDefault(ResultTree.Node node) {
        return node.kind == ResultTree.Kind.MOD || node.kind == ResultTree.Kind.OTHER;
    }

    private static boolean isOpen(ResultTree.Node node) {
        return opened.getOrDefault(node.id, openByDefault(node));
    }

    /** Left edge of switch column [i], from the list's left. */
    private int column(int i) {
        return screen.panel().contentWidth() - (COLUMNS - i) * COLUMN;
    }

    /**
     * The results as a tree: a folder per mod (its particles, and what its blocks made), then Other. Open folders list
     * what is in them, indented. Below a particle, a notice when it has more sources than Here makes zones for, or when
     * a zone hiding it missed spots where it appeared.
     */
    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        List<ParticleScan.Result> results = ParticleScan.results();
        List<ResultTree.Item> items = results.stream().map(r -> new ResultTree.Item(r.particle(), r.count(), r.allHidden())).toList();
        int most = results.stream().mapToInt(ParticleScan.Result::count).max().orElse(1);
        for (ResultTree.Node node : ResultTree.build(items)) addRows(rows, node, null, 0, most);
        return rows;
    }

    private void addRows(List<Row> rows, ResultTree.Node node, ResultTree.Kind parent, int depth, int most) {
        ParticlePattern.TagCheck tags = FilterRules.tags();
        ResultTree.Coverage everywhere = ResultTree.coverage(node, key -> PfConfig.hiddenParticles(), tags);
        rows.add(nodeRow(node, parent, depth, most, everywhere, tags));
        if (node.isFolder()) {
            if (isOpen(node)) node.children.forEach(child -> addRows(rows, child, node.kind, depth + 1, most));
            return;
        }
        String particle = node.name;
        if (ParticleScan.hereZone() == null && FittedZones.hasMoreSources(particle) && everywhere.state() != ResultTree.State.ON) {
            int sources = FittedZones.sourcesOf(particle).size();
            rows.add(noticeRow(depth, Component.translatable("particlefilter.scan.many_sources", ParticleScan.displayName(particle), sources, PfConfig.maxZones()),
                noticeTextRight(), () -> ZoneRenderer.previewMore = ParticleScan.previewFor(List.of(particle))));
        }
        FittedZones.Growth growth = FittedZones.growthFor(particle);
        if (growth != null) {
            rows.add(growRow(depth, Component.translatable("particlefilter.scan.grow_notice", growth.zone().name, ParticleScan.displayName(particle)),
                growTooltip(growth.zone(), growth.box()), () -> FittedZones.grow(growth), () -> {
                    ZoneRenderer.highlighted = growth.zone();
                    ZoneRenderer.preview = growth.box().aabb();
                }));
        }
    }

    /**
     * A folder or particle row, indented by [depth]: a folder has an arrow (click the row to open or close it), a
     * particle a dot and a bar for how often it spawned next to [most]. Then the count and the Everywhere and Here
     * switches, which show partly on when only some of a folder's particles are hidden, and can't be switched off while
     * something outside the folder (such as a mod folder above) hides it all. Dimmed when the rules hid every one.
     */
    private Row nodeRow(ResultTree.Node node, ResultTree.Kind parent, int depth, int most, ResultTree.Coverage everywhere, ParticlePattern.TagCheck tags) {
        var font = screen.font();
        boolean folder = node.isFolder();
        boolean dim = node.allHidden;
        int indent = depth * INDENT;
        int firstX = column(0);
        String countText = "×" + node.count;
        int countRight = firstX - COUNT_GAP;
        var name = screen.wrap(label(node, parent), countRight - font.width(countText) - NAME_GAP - CARD_TEXT_X - indent);
        int color = folder ? Theme.TEXT_DIM : ParticleKey.isTyped(node.name) ? Theme.TYPED : Theme.UNTYPED;
        float share = node.count / (float) Math.max(1, most);
        boolean hereColumn = ParticleScan.target() != null && !node.rules.isEmpty();
        ResultTree.Coverage here = hereColumn ? ResultTree.coverage(node, ParticleScan::hereEntries, tags) : null;
        boolean counts = hereColumn && PfConfig.zoneOptionsOpen() && ParticleScan.hereZone() == null;
        Row row = new Row(PfScreen.textHeight(name.size()) + 2 * RESULT_TEXT_Y, (g, x, y, w, h, hovered) -> {
            Theme.rect(g, x + indent, y, w - indent, h, CARD_RADIUS, hovered ? Theme.CARD_HOVER : folder ? Theme.FOLDER : Theme.CARD);
            if (folder) {
                Theme.text(g, font, isOpen(node) ? "▾" : "▸", x + indent + CARD_DOT_X - 1, y + (h - 8) / 2, Theme.TEXT_DIM);
            } else {
                // How often it spawned compared to the most frequent particle.
                g.fill(x + indent + CARD_TEXT_X, y + h - 3, x + indent + CARD_TEXT_X + Math.max(2, Math.round((countRight - indent - CARD_TEXT_X) * share)), y + h - 2,
                    Theme.fade(color, dim ? 0.25f : 0.55f));
                Theme.dot(g, x + indent + CARD_DOT_X, y + (h - DOT_SIZE) / 2, DOT_SIZE, dim ? Theme.fade(color, 0.45f) : color);
            }
            screen.drawLines(g, name, x + indent + CARD_TEXT_X, y + RESULT_TEXT_Y, dim ? Theme.TEXT_DIM : Theme.TEXT);
            Theme.text(g, font, countText, x + countRight - font.width(countText), y + (h - 8) / 2, Theme.TEXT_DIM);
            if (counts) {
                int zones = ParticleScan.hereZoneCount(node.rules);
                // Cyan like the outlines previewed on hover, until it is switched on.
                if (zones > 0) Theme.text(g, font, Integer.toString(zones), x + column(1) + (COLUMN + Toggle.WIDTH) / 2 + ZONE_COUNT_GAP,
                    y + (h - 8) / 2, here.state() == ResultTree.State.ON ? Theme.TEXT_DIM : Theme.NEW_ZONE);
            }
            if (hovered) hoveredNode = node;
        }).tooltip(folder ? folderTooltip(node, parent) : rowTooltip(ParticleScan.result(node.name)));
        if (folder) row.onClick(() -> {
            opened.put(node.id, !isOpen(node));
            screen.replaceRows(rows());
        });
        if (node.rules.isEmpty()) return row;
        row.add(columnToggle(node, everywhere, "particlefilter.scan.col_everywhere", everywhereTooltip(node, everywhere),
            on -> ParticleScan.setEverywhere(node, on)), column(0) + (COLUMN - Toggle.WIDTH) / 2);
        if (hereColumn) {
            row.add(columnToggle(node, here, "particlefilter.scan.col_zone", hereTooltip(node, here),
                on -> ParticleScan.setHere(node, on)), column(1) + (COLUMN - Toggle.WIDTH) / 2);
        }
        return row;
    }

    /** A column's switch for [node]: on, partly on or off as [coverage] says, and locked while it can't go off. */
    private Toggle columnToggle(ResultTree.Node node, ResultTree.Coverage coverage, String nameKey, Component tooltip, java.util.function.Consumer<Boolean> set) {
        Toggle toggle = new Toggle(0, 0, coverage.state() == ResultTree.State.ON, Component.translatable(nameKey), on -> {
            set.accept(on);
            screen.refreshNextTick(); // folders above and below, the Here line, buttons and notices follow
        }).partial(coverage.state() == ResultTree.State.PARTIAL);
        toggle.active = !coverage.locked();
        return toggle.tooltip(tooltip);
    }

    /** How a row reads: a mod's name, "Made from GeOre blocks", a particle, or inside a particle's folder its block. */
    private static String label(ResultTree.Node node, ResultTree.Kind parent) {
        return switch (node.kind) {
            case MOD -> ModNames.of(node.name);
            case MOD_BLOCKS -> Component.translatable("particlefilter.scan.group.made_from", ModNames.of(node.name)).getString();
            case BLOCK_MOD -> Component.translatable("particlefilter.scan.group.mod_blocks", ModNames.of(node.name)).getString();
            case OTHER -> Component.translatable("particlefilter.scan.group.other").getString();
            case PARTICLE -> ParticleKey.displayName(node.name);
            case LEAF -> {
                String block = ParticleKey.block(node.name);
                boolean underParticle = parent == ResultTree.Kind.PARTICLE || parent == ResultTree.Kind.BLOCK_MOD;
                yield block != null && underParticle ? RuleNames.blockName(block) : ParticleKey.displayName(node.name);
            }
        };
    }

    /** What a folder holds and what its switches hide. */
    private static Component folderTooltip(ResultTree.Node node, ResultTree.Kind parent) {
        if (node.kind == ResultTree.Kind.OTHER) return Component.translatable("particlefilter.scan.group_other.tooltip", node.leaves.size(), node.count);
        return Component.translatable("particlefilter.scan.group.tooltip", label(node, parent), node.leaves.size(), node.count, rulesText(node));
    }

    /** The entries a folder's switches add, by name: "All GeOre particles, Everything made from GeOre blocks". */
    private static String rulesText(ResultTree.Node node) {
        return String.join(", ", node.rules.stream().map(RuleNames::of).toList());
    }

    private static Component lockedTooltip(String columnKey, ResultTree.Coverage coverage) {
        return Component.translatable(columnKey, String.join(", ", coverage.lockedBy().stream().map(RuleNames::of).toList()));
    }

    private static Component everywhereTooltip(ResultTree.Node node, ResultTree.Coverage coverage) {
        if (coverage.locked()) return lockedTooltip("particlefilter.scan.locked_everywhere.tooltip", coverage);
        if (!node.isFolder()) return Component.translatable("particlefilter.scan.everywhere.tooltip", ParticleScan.displayName(node.name));
        return Component.translatable("particlefilter.scan.group_everywhere.tooltip", rulesText(node));
    }

    /** What Here's switch does for [node]: hide it in Here's zone, or in zones fitted to it, and how many. */
    private static Component hereTooltip(ResultTree.Node node, ResultTree.Coverage coverage) {
        if (coverage.locked()) return lockedTooltip("particlefilter.scan.locked_here.tooltip", coverage);
        String name = node.isFolder() ? rulesText(node) : ParticleScan.displayName(node.name);
        Zone zone = ParticleScan.hereZone();
        if (zone != null) return Component.translatable(node.isFolder() ? "particlefilter.scan.group_zone.tooltip" : "particlefilter.scan.zone.tooltip", name, zone.name);
        String key = node.isFolder() ? "particlefilter.scan.group_here_fitted.tooltip" : "particlefilter.scan.here_fitted.tooltip";
        return Component.empty().append(Component.translatable(key, name)).append("\n").append(zoneCountText(node, coverage));
    }

    /** How many zones Here hides [node] in, or would if switched on. */
    private static Component zoneCountText(ResultTree.Node node, ResultTree.Coverage here) {
        int count = ParticleScan.hereZoneCount(node.rules);
        String name = node.isFolder() ? rulesText(node) : ParticleScan.displayName(node.name);
        String key = here.state() == ResultTree.State.ON ? "particlefilter.scan.here_count_on" : "particlefilter.scan.here_count_new";
        return count == 1 ? Component.translatable(key + "_one", name) : Component.translatable(key, name, count);
    }

    /** The particle's id and kind, how many appeared and were hidden, where, and which rules hid them. */
    private static Component rowTooltip(ParticleScan.Result result) {
        if (result == null) return Component.empty();
        String particle = result.particle();
        int appeared = result.count() - result.hiddenCount();
        String where = boxText(result.spawns());
        var tip = Component.empty().append(Component.translatable(result.typed() ? "particlefilter.particle.typed.tooltip" : "particlefilter.particle.untyped.tooltip", particle))
            .append("\n");
        if (result.hiddenCount() == 0) return tip.append(Component.translatable("particlefilter.scan.row_appeared", appeared, where));
        tip.append(result.allHidden() ? Component.translatable("particlefilter.scan.row_hidden", result.count(), where)
            : Component.translatable("particlefilter.scan.row_mixed", appeared, result.hiddenCount(), where));
        List<String> by = ParticleScan.hiddenBy(particle, result.spawns());
        String rules = by.isEmpty() ? Component.translatable("particlefilter.scan.hidden_by_changed").getString() : String.join(", ", by);
        return tip.append("\n").append(Component.translatable("particlefilter.scan.hidden_by", rules));
    }

    private static String boxText(AABB box) {
        return (int) Math.floor(box.minX) + ", " + (int) Math.floor(box.minY) + ", " + (int) Math.floor(box.minZ) + " → "
            + (int) Math.floor(box.maxX) + ", " + (int) Math.floor(box.maxY) + ", " + (int) Math.floor(box.maxZ);
    }

    // ---------------------------------------------------------------- Notices

    /**
     * A notice saying a zone misses some spots where its particles appeared: a warning dot and [text] wrapped beside a
     * Grow to fit button with [tip]. [grow] runs on click, then the page is rebuilt. [whileHovered] runs every frame the
     * card is hovered, to outline the grown zone.
     */
    private Row growRow(int depth, Component text, Component tip, Runnable grow, Runnable whileHovered) {
        int buttonX = screen.panel().contentWidth() - GROW_WIDTH - NOTICE_GAP;
        Row row = noticeRow(depth, text, buttonX - NOTICE_GAP, whileHovered);
        row.add(screen.rowButton(Style.SECONDARY, Component.translatable("particlefilter.grow"), GROW_WIDTH, () -> {
            grow.run();
            screen.refreshNextTick();
        }).tooltip(tip), buttonX);
        return row;
    }

    /**
     * A notice card under a particle, indented like it ([depth]): a warning dot and [text] wrapped to end [textRight]
     * px from the list's left edge.
     */
    private Row noticeRow(int depth, Component text, int textRight, Runnable whileHovered) {
        int indent = depth * INDENT;
        var lines = screen.wrap(text, textRight - indent - CARD_TEXT_X);
        return new Row(PfScreen.textHeight(lines.size()) + 2 * NOTICE_TEXT_Y, (g, x, y, w, h, hovered) -> {
            Theme.rect(g, x + indent, y, w - indent, h, CARD_RADIUS, hovered ? Theme.CARD_HOVER : Theme.CARD);
            Theme.dot(g, x + indent + CARD_DOT_X, y + (h - DOT_SIZE) / 2, DOT_SIZE, Theme.WARNING);
            screen.drawLines(g, lines, x + indent + CARD_TEXT_X, y + NOTICE_TEXT_Y, Theme.TEXT);
            if (hovered) {
                noticeHovered = true;
                whileHovered.run();
            }
        });
    }

    /** A notice's text right edge when it has no button. */
    private int noticeTextRight() {
        return screen.panel().contentWidth() - NOTICE_GAP;
    }

    /** Tooltip of a Grow to fit button: the zone's size now and after, and a warning if it hits the size limit. */
    private static Component growTooltip(Zone zone, ZoneFit.Box grown) {
        Component tip = Component.translatable("particlefilter.grow.tooltip", zone.name, zone.sizeText(), grown.sizeText());
        return grown.capped() ? Component.empty().append(tip).append("\n").append(Component.translatable("particlefilter.grow.capped", Zone.MAX_SIZE)) : tip;
    }

    // ---------------------------------------------------------------- Drawing

    /** The zone Edit zone opens: Here's zone, or the one zone the latest scan made. Null if there isn't exactly one. */
    private static Zone editableZone() {
        Zone zone = ParticleScan.hereZone();
        if (zone != null) return zone;
        List<Zone> made = FittedZones.managedZones();
        return made.size() == 1 ? made.get(0) : null;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        int cx = screen.cx(), cw = screen.cw();
        renderLengthAndRadius(g);
        renderOptionLabels(g);
        ResultTree.Node hovered = hoveredNode;
        hoveredNode = null;
        boolean notice = noticeHovered;
        noticeHovered = false;

        if (screen.panel().isEmpty()) {
            int centerY = emptyStateTop(listTop, listBottom());
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
            renderColumnLabels(g);
        }

        if (ParticleScan.target() == null) return;
        Zone zone = ParticleScan.hereZone();
        if (editZoneButton != null) editZoneButton.active = editableZone() != null;
        ResultTree.Coverage hoveredHere = hovered == null || hovered.rules.isEmpty() ? null
            : ResultTree.coverage(hovered, ParticleScan::hereEntries, FilterRules.tags());
        if (hovered != null) {
            // The zones hiding the hovered row's particles, or where Here would put them.
            ZoneRenderer.highlighted = zone;
            ZoneRenderer.highlightedMore = ParticleScan.zonesHiding(hovered);
            if (hoveredHere != null && hoveredHere.state() != ResultTree.State.ON) ZoneRenderer.previewMore = ParticleScan.previewFor(hovered.rules);
        } else if (!notice) {
            // Where Here hides particles: its zone, the zones this scan made, or the scanned block where they start.
            ZoneRenderer.highlighted = zone;
            if (zone == null) {
                List<Zone> made = FittedZones.managedZones();
                if (made.isEmpty()) ZoneRenderer.preview = ParticleScan.newZonePreview();
                else ZoneRenderer.highlightedMore = made;
            }
        }
        renderHereLine(g, mouseX, mouseY, zone, hovered, hoveredHere, cw - FOOTER_BUTTONS_WIDTH - FOOTER_GAP);
    }

    /** Column headers above the list: PARTICLE on the left and one label per switch column on the right. */
    private void renderColumnLabels(GuiGraphics g) {
        var font = screen.font();
        int cx = screen.cx();
        int y = listTop - COLUMN_LABELS_ABOVE;
        Theme.text(g, font, Theme.caps("particlefilter.scan.col_particle"), cx + 2, y, Theme.TEXT_FAINT);
        String[] keys = {"particlefilter.scan.col_everywhere", "particlefilter.scan.col_zone"};
        for (int i = 0; i < keys.length; i++) Theme.centered(g, font, Theme.caps(keys[i]), cx + column(i) + COLUMN / 2, y, Theme.TEXT_FAINT);
    }

    /**
     * The line under the list saying where Here hides particles: an existing zone, the zones this scan made, or new
     * zones still to be made. With Zone options open, while a row is hovered, how many zones Here gives that particle.
     */
    private void renderHereLine(GuiGraphics g, int mouseX, int mouseY, Zone zone, ResultTree.Node hovered, ResultTree.Coverage hoveredHere, int maxWidth) {
        Component text;
        Component tip;
        int color;
        if (zone != null) {
            text = Component.translatable("particlefilter.scan.zone_existing", zone.name, zone.sizeText());
            tip = Component.translatable("particlefilter.scan.zone_existing.tooltip", zone.name, zone.sizeText(), ZoneEditScreen.volumeText(zone));
            color = Theme.zoneColor(zone);
        } else {
            int made = FittedZones.managedZones().size();
            boolean capped = FittedZones.anyCapped();
            tip = Component.translatable("particlefilter.scan.zone_fitted.tooltip", Zone.MAX_SIZE);
            color = capped ? Theme.WARNING : Theme.NEW_ZONE;
            if (hoveredHere != null && PfConfig.zoneOptionsOpen() && ParticleScan.hereZoneCount(hovered.rules) > 0) {
                text = zoneCountText(hovered, hoveredHere);
                color = Theme.NEW_ZONE;
            } else if (capped) {
                text = Component.translatable("particlefilter.scan.zone_capped");
            } else if (made == 0) {
                text = Component.translatable("particlefilter.scan.zone_new");
            } else {
                text = made == 1 ? Component.translatable("particlefilter.scan.zone_made_one") : Component.translatable("particlefilter.scan.zone_made", made);
            }
        }
        var font = screen.font();
        int cx = screen.cx();
        int y = screen.bodyBottom() - FOOTER_LINE;
        String shown = Theme.fit(font, text.getString(), maxWidth);
        Theme.dot(g, cx + DOT_X, y + 1, DOT_SIZE, color);
        Theme.text(g, font, shown, cx + DOT_TEXT_X, y, Theme.TEXT_DIM);
        if (mouseX >= cx && mouseX < cx + DOT_TEXT_X + font.width(shown) && mouseY >= y - 4 && mouseY < y + 12) {
            screen.setTooltipForNextRenderPass(Tooltip.splitTooltip(screen.minecraft(), tip));
        }
    }
}
