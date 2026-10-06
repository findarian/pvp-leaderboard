package com.pvp.leaderboard.overlay;

import java.awt.*;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.*;
import org.slf4j.*;
import static java.awt.RenderingHints.*;

/**
 * The OSRS Collection-Log pop-up frame the Match found and plugin-disable
 * warning pop-ups share: the palette, the fade, the frame with its title
 * separator centred at the top of the canvas, and a render that never lets a
 * throwable reach RuneLite's overlay renderer. Each pop-up paints its own body.
 */
abstract class Popup extends Overlay
{
    /** Fade-in duration at the start of each popup. */
    static final long FADE_IN_MS = 200L;
    /** Fade-out duration at the end of each popup. */
    static final long FADE_OUT_MS = 400L;

    // ---- Collection-Log popup palette (eyedropper from the widget) ----
    /** Frame stone-brown fill — main interior + title bar share this
     *  colour, the title bar is differentiated by the separator line
     *  alone (matches the vanilla widget). */
    static final Color FRAME_FILL = new Color(0x4d4639);
    /** Outer 1-px hard outline that bounds the whole popup. */
    static final Color OUTER_OUTLINE = new Color(0x120f0a);
    /** Bevel highlight running just inside the outer outline. */
    static final Color FRAME_BEVEL = new Color(0x746952);
    /** Horizontal separator under the title. */
    static final Color TITLE_LINE = new Color(0x1c1810);
    /** Bevel highlight running just below the title separator. */
    static final Color LINE_HI = new Color(0x6e634d);
    /** Title text — RuneScape orange. */
    static final Color TITLE_FG = new Color(0xff981f);
    /** Body text. */
    static final Color BODY_FG = Color.WHITE;

    private final Client client;
    private final Logger log;
    private final String name;
    private final int width;
    private final int height;
    private final int titleBarHeight;
    private final int top;

    /** {@code log} and {@code name} are the pop-up's own, for its render warning. */
    Popup(Client client, Logger log, String name, int width, int height, int titleBarHeight, int top)
    {
        this.client = client;
        this.log = log;
        this.name = name;
        this.width = width;
        this.height = height;
        this.titleBarHeight = titleBarHeight;
        this.top = top;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // Any throwable from the pop-up is logged once with the pop-up's
        // name + a real stack (the first allocation happens here, before
        // HotSpot's OmitStackTraceInFastThrow strips the trace from repeat
        // throws) and swallowed so OverlayRenderer doesn't WARN-spam every
        // frame.
        try
        {
            return draw(g);
        }
        catch (Throwable t)
        {
            log.warn("[{}] render threw - swallowing", name, t);
            return null;
        }
    }

    /** This frame of the pop-up, or {@code null} when it shows nothing. */
    abstract Dimension draw(Graphics2D g);

    /** The pop-up's title and body, inside the frame whose top-left is ({@code x}, {@code y}). */
    abstract void paintBody(Graphics2D g, int x, int y, String text);

    /** The share of full opacity {@code elapsedMs} into a {@code totalMs}
     *  pop-up: up over {@link #FADE_IN_MS}, down over the last
     *  {@link #FADE_OUT_MS}, clamped to 0..1. */
    static float fade(long elapsedMs, long totalMs)
    {
        float share;
        if (elapsedMs < FADE_IN_MS)
        {
            share = (float) elapsedMs / FADE_IN_MS;
        }
        else if (elapsedMs > totalMs - FADE_OUT_MS)
        {
            share = 1f - (float) (elapsedMs - (totalMs - FADE_OUT_MS)) / FADE_OUT_MS;
        }
        else
        {
            share = 1f;
        }
        return Math.max(0f, Math.min(1f, share));
    }

    /** Paints the frame at {@code alpha}, centred at the top of the canvas,
     *  then the body; restores the graphics state it changes. {@code null}
     *  without a canvas. */
    Dimension paint(Graphics2D g, float alpha, String text)
    {
        Dimension canvas = client.getRealDimensions();
        if (canvas == null) return null;
        int x = Math.max(0, (canvas.width - width) / 2);

        Composite prevComposite = g.getComposite();
        Stroke prevStroke = g.getStroke();
        Font prevFont = g.getFont();
        Object prevAA = g.getRenderingHint(KEY_ANTIALIASING);
        Object prevTAA = g.getRenderingHint(KEY_TEXT_ANTIALIASING);
        try
        {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_OFF);
            g.setColor(FRAME_FILL);
            g.fillRect(x, top, width, height);
            g.setStroke(new BasicStroke(1f));
            g.setColor(FRAME_BEVEL);
            g.drawRect(x + 1, top + 1, width - 3, height - 3);
            g.setColor(OUTER_OUTLINE);
            g.drawRect(x, top, width - 1, height - 1);

            int sepY = top + titleBarHeight;
            g.setColor(TITLE_LINE);
            g.drawLine(x + 2, sepY, x + width - 3, sepY);
            g.setColor(LINE_HI);
            g.drawLine(x + 2, sepY + 1, x + width - 3, sepY + 1);

            paintBody(g, x, top, text);
        }
        finally
        {
            g.setComposite(prevComposite);
            g.setStroke(prevStroke);
            g.setFont(prevFont);
            if (prevAA != null) g.setRenderingHint(KEY_ANTIALIASING, prevAA);
            if (prevTAA != null) g.setRenderingHint(KEY_TEXT_ANTIALIASING, prevTAA);
        }
        return new Dimension(width, height);
    }
}
