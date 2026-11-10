package particlefilter.gui.page;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import particlefilter.gui.ParticleFilterScreen;
import particlefilter.render.ZoneRenderer;

/**
 * One page of the Particle Filter window. The screen draws the sidebar, header and buttons around it and passes its
 * init, render, tick and key presses on to the page shown.
 */
public abstract class Page {

    /** Space kept below a page's list for the footer line, switch or buttons under it. */
    protected static final int LIST_FOOTER = 24;
    /** Top of the footer line or switch under a page's list, up from the bottom of the body. */
    protected static final int FOOTER_LINE = 13;
    /** Left inset of the dot that starts a footer line or zone heading. */
    protected static final int DOT_X = 2;
    /** Left inset of the text after that dot. */
    protected static final int DOT_TEXT_X = 12;
    /** How far an empty-state message sits above the middle of its area, to allow for its explanation lines. */
    private static final int EMPTY_STATE_LIFT = 20;

    protected final ParticleFilterScreen screen;

    protected Page(ParticleFilterScreen screen) {
        this.screen = screen;
    }

    /** Top of an empty-state message shown in the area from [top] to [bottom]. */
    protected static int emptyStateTop(int top, int bottom) {
        return (top + bottom) / 2 - EMPTY_STATE_LIFT;
    }

    public abstract Component title();

    /** The subtitle under the title; it is wrapped when the page is laid out, so it changes only with a refresh. */
    public abstract Component subtitle();

    /** Width of the page's header buttons, left of the help and close buttons; 0 when it has none. */
    public int actionsWidth() {
        return 0;
    }

    /** Called when the player switches to this page. */
    public void shown() {
    }

    /** Adds the page's widgets and rows below the header. */
    public abstract void init();

    /** Called each frame before the widgets are drawn. */
    public void beforeRender() {
        ZoneRenderer.highlighted = null; // rows set it again while hovered
    }

    /** Draws what the page needs on top of the widgets, after the window and header. */
    public void render(GuiGraphics g, int mouseX, int mouseY) {
    }

    public void tick() {
    }

    /** True when the page used the key press. */
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        return false;
    }
}
