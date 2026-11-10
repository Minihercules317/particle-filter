package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import particlefilter.config.PfConfig;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.gui.PfScreen;
import particlefilter.gui.Theme;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.ScrollPanel.Row;
import particlefilter.gui.widget.Toggle;
import particlefilter.scan.ParticleScan;

import static particlefilter.gui.PfScreen.CARD_DOT_X;
import static particlefilter.gui.PfScreen.CARD_RADIUS;
import static particlefilter.gui.PfScreen.CARD_TEXT_X;
import static particlefilter.gui.PfScreen.DOT_SIZE;

/**
 * Base for the Scan and Diagnostic pages: a scan button in the header, a duration below it, and a list of the
 * particles found with switch columns on the right.
 */
abstract class ResultsPage extends Page {

    /** Width of each toggle column. */
    protected static final int COLUMN = 58;
    /** Width of the scan button in the header. */
    private static final int SCAN_BUTTON_WIDTH = 80;
    /** Space from the top of the body to the list, for the duration spinner and the column labels. */
    private static final int LIST_TOP = 36;
    /** How far the column labels sit above the list. */
    private static final int COLUMN_LABELS_ABOVE = 12;
    /** Width of the duration spinner. */
    private static final int DURATION_WIDTH = 62;
    /** Gap between "Scan for" and the duration spinner. */
    private static final int DURATION_GAP = 6;
    /** Space above a result row's name, and the same below. */
    private static final int RESULT_TEXT_Y = 7;
    /** Gap between a result row's count and its first column; the frequency bar ends at the count's right edge. */
    private static final int COUNT_GAP = 8;
    /** Smallest gap between a result row's name and its count. */
    private static final int NAME_GAP = 7;

    protected FlatButton scanButton;
    protected int shownScanGeneration;

    protected ResultsPage(ParticleFilterScreen screen) {
        super(screen);
    }

    /** Changes when this page's scan starts or finishes. */
    protected abstract int generation();

    protected abstract boolean isRunning();

    protected abstract float progress();

    /** The scan button's label, with the seconds left while running. */
    protected abstract Component buttonLabel();

    @Override
    public int actionsWidth() {
        return SCAN_BUTTON_WIDTH;
    }

    /** Top of the result list, below the duration spinner and the column labels. */
    protected int listTop() {
        return screen.bodyTop() + LIST_TOP;
    }

    /** Bottom of the result list, above the footer. */
    protected int listBottom() {
        return screen.bodyBottom() - LIST_FOOTER;
    }

    /** Adds the result list between [listTop] and [listBottom]. */
    protected void addList() {
        screen.addPanel(screen.cx(), listTop(), screen.cw(), listBottom() - listTop(), 3);
    }

    /** Unlike the other pages, leaves the zone highlight alone; the Scan page sets it while rendering. */
    @Override
    public void beforeRender() {
        if (scanButton != null) scanButton.setProgress(isRunning() ? progress() : -1);
    }

    @Override
    public void tick() {
        if (scanButton == null) return;
        if (generation() != shownScanGeneration) screen.refresh(); // a scan started or finished
        else if (isRunning()) scanButton.setMessage(buttonLabel());
    }

    /** "Scan for [− n +] seconds" below the header, shared by Scan and Diagnostic. */
    protected void addDurationSpinner(int value, java.util.function.IntConsumer onValue) {
        Component duration = Component.translatable("particlefilter.scan.duration");
        Component tip = Component.translatable("particlefilter.scan.duration.tooltip", PfConfig.MIN_SCAN_SECONDS, PfConfig.MAX_SCAN_SECONDS);
        screen.addSpinner(durationSpinnerX(), screen.bodyTop(), DURATION_WIDTH, value, PfConfig.MIN_SCAN_SECONDS, PfConfig.MAX_SCAN_SECONDS, tip, onValue);
    }

    /** Left edge of the duration spinner, after "Scan for". */
    private int durationSpinnerX() {
        return screen.cx() + screen.font().width(Component.translatable("particlefilter.scan.duration")) + DURATION_GAP;
    }

    protected void renderDurationLabels(GuiGraphics g) {
        var font = screen.font();
        int textY = screen.bodyTop() + 4;
        Theme.text(g, font, Component.translatable("particlefilter.scan.duration"), screen.cx(), textY, Theme.TEXT_DIM);
        Theme.text(g, font, Component.translatable("particlefilter.scan.duration_unit"), durationSpinnerX() + DURATION_WIDTH + 5, textY, Theme.TEXT_DIM);
    }

    /** Left edge of column [i] of [n] switch or value columns at the right of a result row. */
    protected int column(int i, int n) {
        return screen.panel().contentWidth() - (n - i) * COLUMN;
    }

    /** A result row: dot, name, a bar for how often it appeared next to [most], and the count; [columns] sit right. */
    protected Row resultRow(String particle, boolean typed, int count, int most, int columns, boolean dim, String value) {
        var font = screen.font();
        int firstX = column(0, columns);
        String shownName = ParticleScan.displayName(particle);
        String countText = "×" + count;
        int countRight = firstX - COUNT_GAP;
        var name = screen.wrap(shownName, countRight - font.width(countText) - NAME_GAP - CARD_TEXT_X);
        int color = typed ? Theme.TYPED : Theme.UNTYPED;
        float share = count / (float) Math.max(1, most);
        return new Row(PfScreen.textHeight(name.size()) + 2 * RESULT_TEXT_Y, (g, x, y, w, h, hovered) -> {
            Theme.rect(g, x, y, w, h, CARD_RADIUS, hovered ? Theme.CARD_HOVER : Theme.CARD);
            // How often it appeared compared to the most frequent particle.
            g.fill(x + CARD_TEXT_X, y + h - 3, x + CARD_TEXT_X + Math.max(2, Math.round((countRight - CARD_TEXT_X) * share)), y + h - 2, Theme.fade(color, dim ? 0.25f : 0.55f));
            Theme.dot(g, x + CARD_DOT_X, y + (h - DOT_SIZE) / 2, DOT_SIZE, dim ? Theme.fade(color, 0.45f) : color);
            screen.drawLines(g, name, x + CARD_TEXT_X, y + RESULT_TEXT_Y, dim ? Theme.TEXT_DIM : Theme.TEXT);
            Theme.text(g, font, countText, x + countRight - font.width(countText), y + (h - 8) / 2, Theme.TEXT_DIM);
            if (value != null) Theme.centered(g, font, value, x + firstX + COLUMN / 2, y + (h - 8) / 2, Theme.TEXT_DIM);
        }).tooltip(Component.translatable(typed ? "particlefilter.particle.typed.tooltip" : "particlefilter.particle.untyped.tooltip", particle));
    }

    protected static Toggle everywhereToggle(String particle) {
        return new Toggle(0, 0, PfConfig.isHiddenEverywhere(particle), Component.translatable("particlefilter.scan.col_everywhere"),
            hidden -> PfConfig.setHiddenEverywhere(particle, hidden))
            .tooltip(Component.translatable("particlefilter.scan.everywhere.tooltip", ParticleScan.displayName(particle)));
    }

    /** Column headers above the list: PARTICLE on the left and one label per column on the right. */
    protected void renderColumnLabels(GuiGraphics g, String... columnKeys) {
        var font = screen.font();
        int cx = screen.cx();
        int y = listTop() - COLUMN_LABELS_ABOVE;
        Theme.text(g, font, Theme.caps("particlefilter.scan.col_particle"), cx + 2, y, Theme.TEXT_FAINT);
        for (int i = 0; i < columnKeys.length; i++) {
            Theme.centered(g, font, Theme.caps(columnKeys[i]), cx + column(i, columnKeys.length) + COLUMN / 2, y, Theme.TEXT_FAINT);
        }
    }

    /** The line under a result list: a colored dot and text, with [tip] (the full explanation) while hovered. */
    protected void renderFooterLine(GuiGraphics g, int mouseX, int mouseY, int color, Component text, Component tip, int maxWidth) {
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
