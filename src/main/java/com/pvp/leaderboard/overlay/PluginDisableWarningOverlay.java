package com.pvp.leaderboard.overlay;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;

/**
 * In-game warning popup shown after the player disabled the plugin
 * mid-fight in an LMS arena (a {@code plugin_disabled} freeze-log).
 *
 * <p>Painted in the same OSRS Collection-Log palette as
 * {@link MatchFoundNotificationOverlay} so the two popups visually
 * rhyme, but with two deliberate differences dictated by the
 * ban-warning product spec:
 * <ul>
 *   <li>It carries an explicit <b>OK</b> button the user must click to
 *       acknowledge — it does not silently fade away like the
 *       match-found toast. (It still self-dismisses after the 10 s
 *       window as a safety net so it can never wedge on screen.)</li>
 *   <li>It is <b>not</b> config-gated or in-combat-suppressed — the
 *       whole point is that the user sees the ban warning.</li>
 * </ul>
 *
 * <p>The plugin calls {@link #showWarning()} on the next
 * {@code LOGGED_IN} after the pending-warning config marker is found,
 * registers this overlay as a {@link MouseListener} so the OK click is
 * caught, and passes a {@link #setOnDismiss dismiss callback} that
 * clears the config marker. Dismissal (OK click OR 10 s elapse) fires
 * that callback exactly once.
 *
 * <p><b>Threading:</b> {@link #showWarning()} is callable from any
 * thread; render runs on the RuneLite game thread; the mouse callback
 * runs on the AWT thread. Active-state fields are {@code volatile}.
 */
@Slf4j
@Singleton
public final class PluginDisableWarningOverlay extends Overlay implements MouseListener
{
    /** Total on-screen window (safety self-dismiss). */
    static final long TOTAL_VISIBLE_MS = 10_000L;
    private static final long FADE_IN_MS = 200L;
    private static final long FADE_OUT_MS = 400L;

    /** The exact warning copy required by the product spec. */
    static final String[] BODY_LINES = {
        "Turning off a plugin mid fight will",
        "result in you being banned from",
        "the plugin. Do not do it again.",
    };
    private static final String TITLE = "Warning";
    private static final String OK_LABEL = "OK";

    // ---- Collection-Log popup palette (matches MatchFoundNotificationOverlay) ----
    private static final Color FRAME_FILL = new Color(0x4D, 0x46, 0x39);
    private static final Color OUTER_OUTLINE = new Color(0x12, 0x0F, 0x0A);
    private static final Color FRAME_BEVEL = new Color(0x74, 0x69, 0x52);
    private static final Color TITLE_SEPARATOR = new Color(0x1C, 0x18, 0x10);
    private static final Color TITLE_SEPARATOR_HI = new Color(0x6E, 0x63, 0x4D);
    private static final Color TITLE_FG = new Color(0xFF, 0x98, 0x1F);
    private static final Color BODY_FG = new Color(0xFF, 0xFF, 0xFF);
    private static final Color OK_FILL = new Color(0x3A, 0x34, 0x2A);
    private static final Color OK_FG = new Color(0xFF, 0x98, 0x1F);

    // ---- Geometry ----
    private static final int POPUP_W = 340;
    private static final int POPUP_H = 168;
    private static final int TITLE_BAR_H = 38;
    private static final int POPUP_TOP_OFFSET = 22;
    private static final float BODY_FONT_PT = 15f;
    private static final int OK_W = 60;
    private static final int OK_H = 24;
    private static final int OK_BOTTOM_MARGIN = 12;

    private final Client client;

    private volatile long activeStartMs;
    /** OK-button screen bounds from the last paint; consulted by the
     *  mouse handler. Null when not showing / not yet painted. */
    private volatile Rectangle okButtonBounds;
    /** Fired once on dismissal (OK click or timeout) — clears the
     *  config marker. */
    private volatile Runnable onDismiss;

    @Inject
    public PluginDisableWarningOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    /** Wire the config-marker-clearing callback (plugin start-up). */
    public void setOnDismiss(Runnable onDismiss)
    {
        this.onDismiss = onDismiss;
    }

    /** Show the warning. Safe from any thread; a re-show while one is
     *  already visible simply restarts the 10 s window. */
    public void showWarning()
    {
        this.okButtonBounds = null;
        this.activeStartMs = System.currentTimeMillis();
        log.debug("[LMSWarn] showWarning stamped");
    }

    /** Drop active state without firing the dismiss callback. Called on
     *  plugin shutdown. Idempotent. */
    public void clear()
    {
        this.activeStartMs = 0L;
        this.okButtonBounds = null;
    }

    boolean isShowing()
    {
        return activeStartMs != 0L;
    }

    /** Pure hit-test used by {@link #mousePressed}. */
    boolean isWithinOkButton(int mx, int my)
    {
        Rectangle r = this.okButtonBounds;
        return r != null && r.contains(mx, my);
    }

    /** Dismiss (OK click or timeout): drop state + fire the callback
     *  exactly once. */
    void dismiss()
    {
        boolean wasShowing = this.activeStartMs != 0L;
        this.activeStartMs = 0L;
        this.okButtonBounds = null;
        if (wasShowing)
        {
            Runnable cb = this.onDismiss;
            if (cb != null)
            {
                try
                {
                    cb.run();
                }
                catch (Exception e)
                {
                    log.debug("[LMSWarn] onDismiss callback threw: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        try
        {
            return renderInner(g);
        }
        catch (Throwable t)
        {
            log.warn("[PluginDisableWarningOverlay] render threw - swallowing", t);
            return null;
        }
    }

    private Dimension renderInner(Graphics2D g)
    {
        long start = activeStartMs;
        if (start == 0L)
        {
            return null;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= TOTAL_VISIBLE_MS)
        {
            // Safety self-dismiss so the popup can never wedge on screen.
            dismiss();
            return null;
        }

        float alpha;
        if (elapsed < FADE_IN_MS)
        {
            alpha = (float) elapsed / (float) FADE_IN_MS;
        }
        else if (elapsed > (TOTAL_VISIBLE_MS - FADE_OUT_MS))
        {
            long fadeStart = TOTAL_VISIBLE_MS - FADE_OUT_MS;
            alpha = 1f - ((float) (elapsed - fadeStart) / (float) FADE_OUT_MS);
        }
        else
        {
            alpha = 1f;
        }
        if (alpha < 0f) alpha = 0f;
        if (alpha > 1f) alpha = 1f;

        Dimension canvas = client.getRealDimensions();
        if (canvas == null) return null;
        int x = Math.max(0, (canvas.width - POPUP_W) / 2);
        int y = POPUP_TOP_OFFSET;

        Composite prevComposite = g.getComposite();
        Stroke prevStroke = g.getStroke();
        Font prevFont = g.getFont();
        Object prevAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object prevTAA = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        try
        {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            paintFrame(g, x, y);
            paintTitleSeparator(g, x, y);
            paintText(g, x, y);
            paintOkButton(g, x, y);
        }
        finally
        {
            g.setComposite(prevComposite);
            g.setStroke(prevStroke);
            g.setFont(prevFont);
            if (prevAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAA);
            if (prevTAA != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, prevTAA);
        }
        return new Dimension(POPUP_W, POPUP_H);
    }

    private void paintFrame(Graphics2D g, int x, int y)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(FRAME_FILL);
        g.fillRect(x, y, POPUP_W, POPUP_H);
        g.setStroke(new BasicStroke(1f));
        g.setColor(FRAME_BEVEL);
        g.drawRect(x + 1, y + 1, POPUP_W - 3, POPUP_H - 3);
        g.setColor(OUTER_OUTLINE);
        g.drawRect(x, y, POPUP_W - 1, POPUP_H - 1);
    }

    private void paintTitleSeparator(Graphics2D g, int x, int y)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setStroke(new BasicStroke(1f));
        int sepY = y + TITLE_BAR_H;
        g.setColor(TITLE_SEPARATOR);
        g.drawLine(x + 2, sepY, x + POPUP_W - 3, sepY);
        g.setColor(TITLE_SEPARATOR_HI);
        g.drawLine(x + 2, sepY + 1, x + POPUP_W - 3, sepY + 1);
    }

    private void paintText(Graphics2D g, int x, int y)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeBoldFont();
        g.setFont(titleFont);
        FontMetrics tfm = g.getFontMetrics();
        int titleW = tfm.stringWidth(TITLE);
        int titleX = x + (POPUP_W - titleW) / 2;
        int titleY = y + (TITLE_BAR_H + tfm.getAscent()) / 2 - 2;
        g.setColor(TITLE_FG);
        g.drawString(TITLE, titleX, titleY);

        Font bodyFont = FontManager.getRunescapeBoldFont().deriveFont(BODY_FONT_PT);
        g.setFont(bodyFont);
        FontMetrics bfm = g.getFontMetrics();
        g.setColor(BODY_FG);
        int lineH = bfm.getHeight();
        int startY = y + TITLE_BAR_H + 8 + bfm.getAscent();
        for (int i = 0; i < BODY_LINES.length; i++)
        {
            String line = BODY_LINES[i];
            int lw = bfm.stringWidth(line);
            int lx = x + (POPUP_W - lw) / 2;
            g.drawString(line, lx, startY + i * lineH);
        }
    }

    private void paintOkButton(Graphics2D g, int x, int y)
    {
        int okX = x + (POPUP_W - OK_W) / 2;
        int okY = y + POPUP_H - OK_H - OK_BOTTOM_MARGIN;
        // Record the screen-space bounds so the mouse handler can hit-test.
        this.okButtonBounds = new Rectangle(okX, okY, OK_W, OK_H);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(OK_FILL);
        g.fillRect(okX, okY, OK_W, OK_H);
        g.setStroke(new BasicStroke(1f));
        g.setColor(FRAME_BEVEL);
        g.drawRect(okX, okY, OK_W - 1, OK_H - 1);
        g.setColor(OUTER_OUTLINE);
        g.drawRect(okX - 1, okY - 1, OK_W + 1, OK_H + 1);

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font okFont = FontManager.getRunescapeBoldFont();
        g.setFont(okFont);
        FontMetrics fm = g.getFontMetrics();
        int lw = fm.stringWidth(OK_LABEL);
        int lx = okX + (OK_W - lw) / 2;
        int ly = okY + (OK_H + fm.getAscent()) / 2 - 2;
        g.setColor(OK_FG);
        g.drawString(OK_LABEL, lx, ly);
    }

    // ---------------- MouseListener ----------------

    @Override
    public MouseEvent mousePressed(MouseEvent e)
    {
        if (isShowing() && isWithinOkButton(e.getX(), e.getY()))
        {
            log.debug("[LMSWarn] OK clicked - dismissing");
            dismiss();
            e.consume();
        }
        return e;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent e)
    {
        return e;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent e)
    {
        return e;
    }

    @Override
    public MouseEvent mouseEntered(MouseEvent e)
    {
        return e;
    }

    @Override
    public MouseEvent mouseExited(MouseEvent e)
    {
        return e;
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent e)
    {
        return e;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent e)
    {
        return e;
    }
}
