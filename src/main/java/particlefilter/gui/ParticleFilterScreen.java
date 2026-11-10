package particlefilter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import particlefilter.config.FilterMode;
import particlefilter.config.PfConfig;
import particlefilter.gui.page.DiagnosticPage;
import particlefilter.gui.page.HiddenPage;
import particlefilter.gui.page.Page;
import particlefilter.gui.page.ScanPage;
import particlefilter.gui.page.ZonesPage;
import particlefilter.gui.widget.FlatButton;
import particlefilter.gui.widget.FlatButton.Style;
import particlefilter.gui.widget.ScrollPanel;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.DiagnosticScan;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Particle Filter window: a sidebar with the pages and the Active rules switch, a header, and the current page
 * (each in {@code gui.page}):
 * - Scan: record which particles spawn around what you're looking at, and hide them everywhere or in a zone there
 * - Diagnostic: record every particle around you, both those still appearing and those your rules hide
 * - Hidden: everything hidden and where, each entry with an on/off switch and Delete, and a box to hide by id
 * - Zones: the zones in this world, to edit, delete or create
 * The page last shown is reopened until the player leaves the world or server.
 */
public class ParticleFilterScreen extends PfScreen {

    public enum Tab { SCAN, DIAGNOSTIC, HIDDEN, ZONES }

    private static final int SIDEBAR_WIDTH = 124;
    private static final int PAD = 12;
    /** Inset of the sidebar's buttons from the window's left, right and bottom edges. */
    private static final int SIDEBAR_PAD = 8;
    /** Top of the first page button in the sidebar, from the window top. */
    private static final int NAV_TOP = 34;
    /** Height of an Active rules option in the sidebar. */
    private static final int OPTION_HEIGHT = 16;
    /** Gap between the sidebar's buttons. */
    private static final int SIDEBAR_GAP = 2;
    /** Size of the help and close buttons in the window's top right corner. */
    private static final int CORNER_BUTTON = 14;
    /** Inset of the close button from the window's top and right edges. */
    private static final int CORNER_PAD = 8;
    /** Gap between the help and close buttons. */
    private static final int CORNER_GAP = 4;
    /** Gap between the page's header buttons and the help button. */
    private static final int ACTIONS_GAP = 6;
    /** Gap between the page title and subtitle and whatever is right of them. */
    private static final int HEADER_GAP = 8;
    /** Top of the page title and the header buttons, from the window top. */
    private static final int HEADER_TOP = 12;
    /** Top of the page subtitle, from the window top. */
    private static final int SUBTITLE_TOP = 25;
    /** Space between the subtitle's last line and the divider below it. */
    private static final int DIVIDER_GAP = 6;
    /** Space from the divider to the top of the page body. */
    private static final int BODY_GAP = 7;
    /** Most lines a page subtitle wraps to; the page body moves down to make room. */
    private static final int MAX_HEADER_LINES = 3;

    private static Tab lastTab = Tab.SCAN;

    private Tab tab;
    /** The pages, made once per screen so what they hold (such as text typed on Hidden) lasts across switches. */
    private final Map<Tab, Page> pages = new EnumMap<>(Tab.class);
    // The window, and the page area right of the sidebar.
    private int wx, wy, ww, wh;
    private int cx, cw, bodyTop, bodyBottom;
    /** Right edge of the page's header buttons, left of the help and close buttons. */
    private int actionsRight;
    /** Lines the page subtitle wraps to, set in init so the body can start below it. */
    private int headerLines = 1;
    private int modesTop;
    private final Map<Tab, FlatButton> navButtons = new EnumMap<>(Tab.class);

    public ParticleFilterScreen(Screen parent, Tab tab) {
        super(Component.translatable("particlefilter.title"), parent);
        this.tab = tab != null ? tab : lastTab;
        pages.put(Tab.SCAN, new ScanPage(this));
        pages.put(Tab.DIAGNOSTIC, new DiagnosticPage(this));
        pages.put(Tab.HIDDEN, new HiddenPage(this));
        pages.put(Tab.ZONES, new ZonesPage(this));
    }

    /** Called when the player joins or leaves a world or server: the next menu opens on Scan again. */
    public static void forgetLastPage() {
        lastTab = Tab.SCAN;
    }

    private Page page() {
        return pages.get(tab);
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
        int closeX = wx + ww - CORNER_PAD - CORNER_BUTTON;
        int helpX = closeX - CORNER_GAP - CORNER_BUTTON;
        actionsRight = helpX - ACTIONS_GAP;
        headerLines = Math.min(MAX_HEADER_LINES, wrap(page().subtitle(), headerWidth()).size());
        bodyTop = dividerY() + BODY_GAP;
        bodyBottom = wy + wh - PAD;
        panel = null;

        initSidebar();
        button(Style.GHOST, Component.literal("?"), helpX, wy + CORNER_PAD, CORNER_BUTTON, CORNER_BUTTON, () -> minecraft.setScreen(new GuideScreen(this)))
            .tooltip(Component.translatable("particlefilter.guide.open"));
        button(Style.GHOST, Component.literal("×"), closeX, wy + CORNER_PAD, CORNER_BUTTON, CORNER_BUTTON, this::onClose)
            .tooltip(Component.translatable("particlefilter.close"));
        page().init();
    }

    // ---------------------------------------------------------------- Sidebar

    private void initSidebar() {
        int x = wx + SIDEBAR_PAD;
        int w = SIDEBAR_WIDTH - 2 * SIDEBAR_PAD;
        int y = wy + NAV_TOP;
        navButtons.clear();
        for (Tab t : Tab.values()) {
            navButtons.put(t, button(Style.NAV, Component.translatable("particlefilter.tab." + t.name().toLowerCase(Locale.ROOT)),
                x, y, w, BUTTON_HEIGHT, () -> switchTo(t)).selected(t == tab));
            y += BUTTON_HEIGHT + SIDEBAR_GAP;
        }

        FilterMode[] modes = FilterMode.values();
        modesTop = wy + wh - SIDEBAR_PAD - modes.length * (OPTION_HEIGHT + SIDEBAR_GAP);
        int optionY = modesTop;
        for (FilterMode mode : modes) {
            button(Style.OPTION, Component.translatable(mode.key()), x, optionY, w, OPTION_HEIGHT, () -> {
                PfConfig.setMode(mode);
                refresh();
            }).selected(mode == PfConfig.mode()).tooltip(Component.translatable(mode.key() + ".tooltip"));
            optionY += OPTION_HEIGHT + SIDEBAR_GAP;
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
        page().shown();
        savedScroll = 0;
        ZoneRenderer.clear();
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- Header

    /** Width for the page title and subtitle, left of the page's header buttons. */
    private int headerWidth() {
        return actionsRight - page().actionsWidth() - HEADER_GAP - cx;
    }

    /** Top of the divider under the subtitle, which moves down as the subtitle wraps. */
    private int dividerY() {
        return wy + SUBTITLE_TOP + textHeight(headerLines) + DIVIDER_GAP;
    }

    /** Page title and the subtitle wrapped to [headerLines] lines, then a divider above the page body. */
    private void renderHeader(GuiGraphics g, Component title) {
        int maxWidth = headerWidth();
        Theme.text(g, font, Theme.fit(font, title.getString(), maxWidth), cx, wy + HEADER_TOP, Theme.TEXT);
        Component subtitle = page().subtitle();
        List<FormattedCharSequence> lines = wrap(subtitle, maxWidth);
        if (lines.size() > headerLines) {
            // Longer than MAX_HEADER_LINES, or than when the page was laid out: the last line ends in "…".
            List<String> parts = font.getSplitter().splitLines(subtitle, maxWidth, net.minecraft.network.chat.Style.EMPTY).stream().map(FormattedText::getString).toList();
            lines = new ArrayList<>(lines.subList(0, headerLines - 1));
            lines.add(Component.literal(Theme.fit(font, String.join(" ", parts.subList(headerLines - 1, parts.size())), maxWidth)).getVisualOrderText());
        }
        drawLines(g, lines, cx, wy + SUBTITLE_TOP, Theme.TEXT_DIM);
        int dividerY = dividerY();
        g.fill(cx, dividerY, cx + cw, dividerY + 1, Theme.DIVIDER);
    }

    // ---------------------------------------------------------------- Screen

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (page().keyPressed(key, scanCode, modifiers)) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Theme.SCRIM);
        Theme.rect(g, wx, wy, SIDEBAR_WIDTH, wh, WINDOW_RADIUS, Theme.SIDEBAR, true, false);
        Theme.rect(g, wx + SIDEBAR_WIDTH, wy, ww - SIDEBAR_WIDTH, wh, WINDOW_RADIUS, Theme.WINDOW, false, true);
    }

    @Override
    public void tick() {
        super.tick();
        page().tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        page().beforeRender();
        updateBadges();
        super.render(g, mouseX, mouseY, partialTick);

        // Sidebar title and the Active rules label.
        Theme.dot(g, wx + 12, wy + 14, 6, Theme.ACCENT);
        Theme.text(g, font, title, wx + 23, wy + 13, Theme.TEXT);
        Theme.text(g, font, Theme.caps("particlefilter.mode.title"), wx + 10, modesTop - 12, Theme.TEXT_FAINT);

        renderHeader(g, page().title());
        page().render(g, mouseX, mouseY);
    }

    // ---------------------------------------------------------------- For pages
    // The pages live in another package, so the screen state they use is made public here. PfScreen's helpers are
    // public already.

    public Font font() {
        return font;
    }

    public Minecraft minecraft() {
        return minecraft;
    }

    /** The current page's list, once the page has added it. */
    public ScrollPanel panel() {
        return panel;
    }

    /** Left edge of the page area right of the sidebar. */
    public int cx() {
        return cx;
    }

    /** Width of the page area. */
    public int cw() {
        return cw;
    }

    /** Top of the page body, below the header and its divider. */
    public int bodyTop() {
        return bodyTop;
    }

    public int bodyBottom() {
        return bodyBottom;
    }

    /**
     * Adds the current page's header button, {@link Page#actionsWidth} px wide, at the top right left of the help
     * and close buttons, level with the title.
     */
    public FlatButton headerButton(Style style, Component label, Runnable onPress) {
        int width = page().actionsWidth();
        return button(style, label, actionsRight - width, wy + HEADER_TOP, width, BUTTON_HEIGHT, onPress);
    }

    @Override
    public <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
        return super.addRenderableWidget(widget);
    }
}
