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

/**
 * Base for the Scan and Diagnostic pages: a scan button in the header, a duration below it, and a list of the
 * particles found with switch columns on the right.
 */
abstract class ResultsPage extends Page {

    /** Width of each toggle column. */
    protected static final int COLUMN = 58;

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
        return 80;
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
        screen.addSpinner(screen.cx() + screen.font().width(duration) + 6, screen.bodyTop(), 62, value, PfConfig.MIN_SCAN_SECONDS, PfConfig.MAX_SCAN_SECONDS, tip, onValue);
    }

    protected void renderDurationLabels(GuiGraphics g) {
        var font = screen.font();
        int cx = screen.cx();
        int bodyTop = screen.bodyTop();
        Component duration = Component.translatable("particlefilter.scan.duration");
        Theme.text(g, font, duration, cx, bodyTop + 4, Theme.TEXT_DIM);
        Theme.text(g, font, Component.translatable("particlefilter.scan.duration_unit"), cx + font.width(duration) + 6 + 62 + 5, bodyTop + 4, Theme.TEXT_DIM);
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
        var name = screen.wrap(shownName, firstX - 26 - font.width(countText) - 8);
        int color = typed ? Theme.TYPED : Theme.UNTYPED;
        float share = count / (float) Math.max(1, most);
        return new Row(PfScreen.textHeight(name.size()) + 14, (g, x, y, w, h, hovered) -> {
            Theme.rect(g, x, y, w, h, 3, hovered ? Theme.CARD_HOVER : Theme.CARD);
            // How often it appeared compared to the most frequent particle.
            g.fill(x + 19, y + h - 3, x + 19 + Math.max(2, Math.round((firstX - 27) * share)), y + h - 2, Theme.fade(color, dim ? 0.25f : 0.55f));
            Theme.dot(g, x + 8, y + (h - 5) / 2, 5, dim ? Theme.fade(color, 0.45f) : color);
            screen.drawLines(g, name, x + 19, y + 7, dim ? Theme.TEXT_DIM : Theme.TEXT);
            Theme.text(g, font, countText, x + firstX - 8 - font.width(countText), y + (h - 8) / 2, Theme.TEXT_DIM);
            if (value != null) Theme.centered(g, font, value, x + firstX + COLUMN / 2, y + (h - 8) / 2, Theme.TEXT_DIM);
        }).tooltip(Component.translatable(typed ? "particlefilter.particle.typed.tooltip" : "particlefilter.particle.untyped.tooltip", particle));
    }

    protected static Toggle everywhereToggle(String particle) {
        return new Toggle(0, 0, PfConfig.isHiddenEverywhere(particle), Component.translatable("particlefilter.scan.col_everywhere"),
            hidden -> PfConfig.setHiddenEverywhere(particle, hidden))
            .tooltip(Component.translatable("particlefilter.scan.everywhere.tooltip", ParticleScan.displayName(particle)));
    }

    /** Column headers: PARTICLE on the left and one label per column on the right. */
    protected void renderColumnLabels(GuiGraphics g, int y, String... columnKeys) {
        var font = screen.font();
        int cx = screen.cx();
        Theme.text(g, font, Theme.caps("particlefilter.scan.col_particle"), cx + 2, y, Theme.TEXT_FAINT);
        for (int i = 0; i < columnKeys.length; i++) {
            Theme.centered(g, font, Theme.caps(columnKeys[i]), cx + column(i, columnKeys.length) + COLUMN / 2, y, Theme.TEXT_FAINT);
        }
    }

    /** The line under a result list: a colored dot and text, with [tip] (the full explanation) while hovered. */
    protected void renderFooterLine(GuiGraphics g, int mouseX, int mouseY, int color, Component text, Component tip, int maxWidth) {
        var font = screen.font();
        int cx = screen.cx();
        int y = screen.bodyBottom() - 13;
        String shown = Theme.fit(font, text.getString(), maxWidth);
        Theme.dot(g, cx + 2, y + 1, 5, color);
        Theme.text(g, font, shown, cx + 12, y, Theme.TEXT_DIM);
        if (mouseX >= cx && mouseX < cx + 12 + font.width(shown) && mouseY >= y - 4 && mouseY < y + 12) {
            screen.setTooltipForNextRenderPass(Tooltip.splitTooltip(screen.minecraft(), tip));
        }
    }
}
