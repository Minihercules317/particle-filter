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

    /** The window's size when the player hasn't resized it, and its smallest size; never more than the screen allows. */
    private static final int DEFAULT_WIDTH = 560;
    private static final int DEFAULT_HEIGHT = 340;
    /** Smallest size: the Scan page's duration and radius need this width on one line, the sidebar this height. */
    private static final int MIN_WIDTH = 444;
    private static final int MIN_HEIGHT = 250;
    /** Space kept between the window and the screen's edges when it is as big as the screen allows. */
    private static final int SCREEN_MARGIN = 8;
    /** Half the width of the band along the right and bottom edges that resizes the window when dragged. */
    private static final int EDGE_GRAB = 3;
    /** Size of the square at the bottom right corner that resizes both ways; the grip dots are drawn in it. */
    private static final int CORNER_GRAB = 10;
    /** Two clicks on the window's background this close together, in milliseconds, put it back to its default place. */
    private static final long DOUBLE_CLICK_MS = 300;

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
    /** Where the player put the window, or null for the default: centered, at the default size. */
    private PfConfig.Window placed = PfConfig.window();

    /** What dragging with the left button does: nothing, moves the window, or resizes it from an edge or the corner. */
    private enum Drag { NONE, MOVE, RIGHT, BOTTOM, CORNER }

    private Drag drag = Drag.NONE;
    /** Mouse position and window when the drag began. */
    private double dragMouseX, dragMouseY;
    private int dragX, dragY, dragWidth, dragHeight;
    /** Set while dragging; the widgets are laid out again once per frame instead of once per mouse event. */
    private boolean relayout;
    private long lastBackgroundClick;

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
        placeWindow();
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

    // ---------------------------------------------------------------- Moving and resizing

    /**
     * Sets the window from where the player put it, fitted to this screen (the GUI scale or game window may have
     * changed since): between the smallest size and the screen's size, and kept on screen. Without a place, it is
     * centered at the default size, leaving a margin on small screens.
     */
    private void placeWindow() {
        if (placed == null) {
            ww = Math.max(1, Math.min(width - 2 * SCREEN_MARGIN, DEFAULT_WIDTH));
            wh = Math.max(1, Math.min(height - 2 * SCREEN_MARGIN, DEFAULT_HEIGHT));
            wx = (width - ww) / 2;
            wy = (height - wh) / 2;
            return;
        }
        ww = clamp(placed.width(), minWidth(), width);
        wh = clamp(placed.height(), minHeight(), height);
        wx = clamp(placed.x(), 0, width - ww);
        wy = clamp(placed.y(), 0, height - wh);
    }

    /** The smallest size, or less on a screen too small for it, as the default size allows. */
    private int minWidth() {
        return Math.max(1, Math.min(MIN_WIDTH, width - 2 * SCREEN_MARGIN));
    }

    private int minHeight() {
        return Math.max(1, Math.min(MIN_HEIGHT, height - 2 * SCREEN_MARGIN));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** What pressing at the point starts: resizing on the right or bottom edge or the corner, else nothing. */
    private Drag resizeAt(double mouseX, double mouseY) {
        int right = wx + ww, bottom = wy + wh;
        boolean nearRight = mouseX >= right - EDGE_GRAB && mouseX < right + EDGE_GRAB && mouseY >= wy && mouseY < bottom + EDGE_GRAB;
        boolean nearBottom = mouseY >= bottom - EDGE_GRAB && mouseY < bottom + EDGE_GRAB && mouseX >= wx && mouseX < right + EDGE_GRAB;
        boolean inCorner = mouseX >= right - CORNER_GRAB && mouseX < right + EDGE_GRAB && mouseY >= bottom - CORNER_GRAB && mouseY < bottom + EDGE_GRAB;
        if (inCorner || nearRight && nearBottom) return Drag.CORNER;
        if (nearRight) return Drag.RIGHT;
        if (nearBottom) return Drag.BOTTOM;
        return Drag.NONE;
    }

    private void startDrag(Drag kind, double mouseX, double mouseY) {
        drag = kind;
        dragMouseX = mouseX;
        dragMouseY = mouseY;
        dragX = wx;
        dragY = wy;
        dragWidth = ww;
        dragHeight = wh;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            Drag resize = resizeAt(mouseX, mouseY);
            if (resize != Drag.NONE) {
                startDrag(resize, mouseX, mouseY);
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        // The window's background, outside the widgets and the list, moves it; a double click puts it back.
        boolean onWindow = mouseX >= wx && mouseX < wx + ww && mouseY >= wy && mouseY < wy + wh;
        boolean onList = panel != null && panel.isMouseOver(mouseX, mouseY);
        if (button != 0 || !onWindow || onList) return false;
        long now = net.minecraft.Util.getMillis();
        if (now - lastBackgroundClick < DOUBLE_CLICK_MS) {
            lastBackgroundClick = 0;
            placed = null;
            PfConfig.setWindow(null);
            refresh();
            return true;
        }
        lastBackgroundClick = now;
        startDrag(Drag.MOVE, mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (drag == Drag.NONE) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        int dx = (int) Math.round(mouseX - dragMouseX), dy = (int) Math.round(mouseY - dragMouseY);
        int x = this.dragX, y = this.dragY, w = dragWidth, h = dragHeight;
        if (drag == Drag.MOVE) {
            x += dx;
            y += dy;
        }
        if (drag == Drag.RIGHT || drag == Drag.CORNER) w = clamp(w + dx, minWidth(), width - x);
        if (drag == Drag.BOTTOM || drag == Drag.CORNER) h = clamp(h + dy, minHeight(), height - y);
        // Resizing keeps the top left corner where it is; moving is kept on screen when the window is placed.
        PfConfig.Window next = new PfConfig.Window(x, y, w, h);
        if (!next.equals(placed)) {
            placed = next;
            relayout = true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (drag == Drag.NONE) return super.mouseReleased(mouseX, mouseY, button);
        drag = Drag.NONE;
        if (relayout) {
            relayout = false;
            refresh();
        }
        // Saved as fitted to this screen, so it reopens where it was seen.
        placed = new PfConfig.Window(wx, wy, ww, wh);
        PfConfig.setWindow(placed);
        return true;
    }

    /** Three dots across the bottom right corner, where the window can be resized. */
    private void renderGrip(GuiGraphics g) {
        int right = wx + ww - 4, bottom = wy + wh - 4;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j <= i; j++) g.fill(right - 1 - 3 * j, bottom - 1 - 3 * (i - j), right - 3 * j, bottom - 3 * (i - j), Theme.TEXT_FAINT);
        }
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
        if (relayout) {
            relayout = false;
            refresh();
        }
        page().beforeRender();
        updateBadges();
        super.render(g, mouseX, mouseY, partialTick);

        // Sidebar title and the Active rules label.
        Theme.dot(g, wx + 12, wy + 14, 6, Theme.ACCENT);
        Theme.text(g, font, title, wx + 23, wy + 13, Theme.TEXT);
        Theme.text(g, font, Theme.caps("particlefilter.mode.title"), wx + 10, modesTop - 12, Theme.TEXT_FAINT);

        renderHeader(g, page().title());
        page().render(g, mouseX, mouseY);
        renderGrip(g);
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
