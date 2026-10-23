package particlefilter.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Colors and drawing helpers for the flat, dark look shared by every Particle Filter screen. */
public final class Theme {

    // Surfaces, darkest first. The window is slightly see-through so zone outlines show behind it.
    public static final int SCRIM = 0x50000000;
    public static final int WINDOW = 0xF2121418;
    public static final int SIDEBAR = 0xF70C0E11;
    public static final int CARD = 0xFF1A1D23;
    public static final int CARD_HOVER = 0xFF22262D;
    public static final int CONTROL = 0xFF2A2F37;
    public static final int CONTROL_HOVER = 0xFF363C46;
    public static final int FIELD = 0xFF0E1013;
    public static final int DIVIDER = 0xFF23272E;

    public static final int TEXT = 0xFFE6E8EB;
    public static final int TEXT_DIM = 0xFF9AA2AD;
    public static final int TEXT_FAINT = 0xFF5D6570;

    public static final int ACCENT = 0xFF2DD4BF;
    public static final int ACCENT_HOVER = 0xFF67E8D6;
    public static final int ACCENT_DARK = 0xFF145F56;
    public static final int ON_ACCENT = 0xFF04201C;
    public static final int DANGER = 0xFFF26D6D;
    public static final int DANGER_BG = 0xFF3A1E21;
    public static final int DANGER_BG_HOVER = 0xFF4E2529;
    public static final int WARNING = 0xFFF5C451;

    // What an entry is: a typed particle, a particle created without a type, a block, or a zone.
    public static final int TYPED = 0xFF6AD3FF;
    public static final int UNTYPED = 0xFFE38BFF;
    public static final int BLOCK = 0xFFF5D76E;
    /** Same hues as the zone outlines drawn in the world. */
    public static final int ZONE = 0xFFFF8C1A;
    public static final int NEW_ZONE = 0xFF40E6FF;

    private Theme() {}

    /** Fills a rectangle with corners rounded by [radius] pixels. Rows never overlap, so translucent colors stay even. */
    public static void rect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        rect(g, x, y, w, h, radius, color, true, true);
    }

    /** Like {@link #rect}, rounding only the left and/or right corners, for shapes that meet another edge. */
    public static void rect(GuiGraphics g, int x, int y, int w, int h, int radius, int color, boolean roundLeft, boolean roundRight) {
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.floor(Math.sqrt(r * r - (r - i) * (r - i)));
            int left = x + (roundLeft ? inset : 0);
            int right = x + w - (roundRight ? inset : 0);
            g.fill(left, y + i, right, y + i + 1, color);
            g.fill(left, y + h - i - 1, right, y + h - i, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    /** A rounded outline drawn as a filled shape with [fill] inside; both colors should be opaque. */
    public static void outlined(GuiGraphics g, int x, int y, int w, int h, int radius, int border, int fill) {
        rect(g, x, y, w, h, radius, border);
        rect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
    }

    public static void text(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, text, x, y, color, false);
    }

    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        g.drawString(font, text, x, y, color, false);
    }

    public static void centered(GuiGraphics g, Font font, String text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** A small label on a rounded background; returns its width. */
    public static int pill(GuiGraphics g, Font font, String text, int x, int y, int background, int color) {
        int w = font.width(text) + 8;
        rect(g, x, y, w, 11, 3, background);
        g.drawString(font, text, x + 4, y + 2, color, false);
        return w;
    }

    /** A round dot [size] pixels across. */
    public static void dot(GuiGraphics g, int x, int y, int size, int color) {
        rect(g, x, y, size, size, size / 2, color);
    }

    /** Uppercased translation, for section labels. */
    public static String caps(String key, Object... args) {
        return Component.translatable(key, args).getString().toUpperCase(Locale.ROOT);
    }

    /** Shortens [text] to [maxWidth] pixels, ending in "…" when cut. */
    public static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    /** Blends two ARGB colors; [t] = 0 gives [from], 1 gives [to]. */
    public static int mix(int from, int to, float t) {
        t = Math.max(0, Math.min(1, t));
        int a = channel(from >>> 24, to >>> 24, t);
        int r = channel(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int gr = channel(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return a << 24 | r << 16 | gr << 8 | b;
    }

    /** [color] with its alpha multiplied by [alpha]. */
    public static int fade(int color, float alpha) {
        int a = Math.round((color >>> 24) * Math.max(0, Math.min(1, alpha)));
        return a << 24 | color & 0xFFFFFF;
    }

    private static int channel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}
