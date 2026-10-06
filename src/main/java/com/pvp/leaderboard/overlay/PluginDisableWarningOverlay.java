package com.pvp.leaderboard.overlay;

import lombok.*;
import java.awt.*;
import java.awt.event.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.client.ui.*;
import net.runelite.client.input.MouseAdapter;
import static java.awt.RenderingHints.*;

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
 * registers {@link #mouse} with RuneLite's mouse manager so the OK click is
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
public final class PluginDisableWarningOverlay extends Popup
{
    /** Total on-screen window (safety self-dismiss). */
    static final long VISIBLE_MS = 10_000L;

    /** The exact warning copy required by the product spec. */
    static final String[] BODY_LINES = {
        "Turning off a plugin mid fight will",
        "result in you being banned from",
        "the plugin. Do not do it again.",
    };
    private static final String TITLE = "Warning";
    private static final String OK_LABEL = "OK";

    // ---- Collection-Log popup palette: Popup's, plus the OK button's fill ----
    private static final Color OK_FILL = new Color(0x3a342a);

    // ---- Geometry ----
    private static final int POPUP_W = 340;
    private static final int POPUP_H = 168;
    private static final int TITLE_BAR_H = 38;
    private static final int TOP_OFFSET = 22;
    private static final float BODY_FONT_PT = 15f;
    private static final int OK_W = 60;
    private static final int OK_H = 24;
    private static final int OK_MARGIN = 12;

    private volatile long shownSince;
    /** OK-button screen bounds from the last paint; consulted by the
     *  mouse handler. Null when not showing / not yet painted. */
    private volatile Rectangle okBounds;
    /** Fired once on dismissal (OK click or timeout) — clears the
     *  config marker. */
    @Setter private volatile Runnable onDismiss;

    @Inject
    public PluginDisableWarningOverlay(Client client)
    {
        super(client, log, "PluginDisableWarningOverlay", POPUP_W, POPUP_H, TITLE_BAR_H, TOP_OFFSET);
    }

    /** Show the warning. Safe from any thread; a re-show while one is
     *  already visible simply restarts the 10 s window. */
    public void showWarning()
    {
        okBounds = null;
        shownSince = System.currentTimeMillis();
    }

    /** Drop active state without firing the dismiss callback. Called on
     *  plugin shutdown. Idempotent. */
    public void clear()
    {
        shownSince = 0L;
        okBounds = null;
    }

    boolean isShowing()
    {
        return shownSince != 0L;
    }

    /** Dismiss (OK click or timeout): drop state + fire the callback
     *  exactly once. */
    void dismiss()
    {
        boolean wasShowing = isShowing();
        clear();
        Runnable cb = onDismiss;
        if (wasShowing && cb != null)
        {
            try
            {
                cb.run();
            }
            catch (Exception e)
            {
            }
        }
    }

    @Override
    Dimension draw(Graphics2D g)
    {
        long start = shownSince;
        if (start == 0L)
        {
            return null;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= VISIBLE_MS)
        {
            // Safety self-dismiss so the popup can never wedge on screen.
            dismiss();
            return null;
        }
        return paint(g, fade(elapsed, VISIBLE_MS), null);
    }

    /** The title, the warning lines and the OK button. */
    @Override
    void paintBody(Graphics2D g, int x, int y, String text)
    {
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        g.setRenderingHint(KEY_TEXT_ANTIALIASING,
            VALUE_TEXT_ANTIALIAS_ON);

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

        int okX = x + (POPUP_W - OK_W) / 2;
        int okY = y + POPUP_H - OK_H - OK_MARGIN;
        // Record the screen-space bounds so the mouse handler can hit-test.
        okBounds = new Rectangle(okX, okY, OK_W, OK_H);

        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_OFF);
        g.setColor(OK_FILL);
        g.fillRect(okX, okY, OK_W, OK_H);
        g.setColor(FRAME_BEVEL);
        g.drawRect(okX, okY, OK_W - 1, OK_H - 1);
        g.setColor(OUTER_OUTLINE);
        g.drawRect(okX - 1, okY - 1, OK_W + 1, OK_H + 1);

        Font okFont = FontManager.getRunescapeBoldFont();
        g.setFont(okFont);
        FontMetrics fm = g.getFontMetrics();
        int lw = fm.stringWidth(OK_LABEL);
        int lx = okX + (OK_W - lw) / 2;
        int ly = okY + (OK_H + fm.getAscent()) / 2 - 2;
        g.setColor(TITLE_FG);
        g.drawString(OK_LABEL, lx, ly);
    }

    /** Registered with RuneLite's mouse manager: a press on the OK button
     *  (while showing) dismisses and is consumed; every other event passes
     *  through unchanged ({@link MouseAdapter} returns it). */
    public final MouseAdapter mouse = new MouseAdapter()
    {
        @Override
        public MouseEvent mousePressed(MouseEvent e)
        {
            Rectangle r;
            if (isShowing() && (r = okBounds) != null && r.contains(e.getX(), e.getY()))
            {
                dismiss();
                e.consume();
            }
            return e;
        }
    };
}
