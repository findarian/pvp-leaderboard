package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.game.*;
import java.awt.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.client.ui.*;
import static java.awt.RenderingHints.*;

@Slf4j
@Singleton
public final class MatchFoundNotificationOverlay extends Popup
{
    /** Total on-screen window (includes fade in + fade out). Hardcoded
     *  per the product spec — no config slider exposed for this one. */
    static final long VISIBLE_MS = 5_000L;
    static final float PEAK_ALPHA = 0.8f;

    static final int POPUP_W = 155;
    static final int POPUP_H = 66;
    /** Title bar height (top section above the separator). */
    static final int TITLE_BAR_H = 19;
    /** Distance from the top of the viewport. */
    static final int TOP_OFFSET = 11;
    static final float TITLE_PT = 10f;
    private static final int SIDE_MARGIN = 6;

    private final PvPLeaderboardConfig config;
    private final FightMonitor fightMonitor;

    /** {@code fight_session_id} of the most-recently-shown match. Sticky
     *  for the lifetime of the singleton so a re-push of the same
     *  fight_proposed event (server replay, bus double-fire) does NOT
     *  pop the same popup twice. Cleared by {@link #clear()} on
     *  plugin shutdown. */
    private volatile String lastShownId;

    private volatile String activeOpp;
    private volatile String activeSubtext;
    /** Rank-tier colour for the opponent name in the caption. Null is
     *  coerced to {@link Color#WHITE} at intake. */
    private volatile Color activeColor;
    private volatile long shownSince;

    @Inject
    public MatchFoundNotificationOverlay(Client client, PvPLeaderboardConfig config, FightMonitor fightMonitor)
    {
        super(client, log, "MatchFoundNotificationOverlay", POPUP_W, POPUP_H, TITLE_BAR_H, TOP_OFFSET);
        this.config = config;
        this.fightMonitor = fightMonitor;
    }

    /**
     * Triggers a fresh popup for a locked-in match against
     * {@code opponentName}. {@code subtext} is a short summary
     * ({@code "NH (Main) at Arena"}) that becomes the body's second
     * line. Safe to call from any thread.
     *
     * <p><b>De-duplication:</b> if {@code fightId} matches the
     * last fight this overlay rendered, the call is silently dropped.
     * This is the source-of-truth dedupe; the panel side does not need
     * to guard.
     *
     * <p><b>Config gate:</b> when the popup is disabled in config we
     * still update {@link #lastShownId} so re-enabling the
     * popup later doesn't suddenly surface a stale match the user
     * "missed" while it was off.
     */
    public void showMatch(String fightId,
                          String opponentName,
                          String subtext,
                          Color opponentColor)
    {
        if (opponentName == null || opponentName.isEmpty()) return;
        if (fightId != null && fightId.equals(lastShownId))
        {
            return;
        }
        lastShownId = fightId;
        boolean enabled = config.enableMatchFoundNotification();
        if (!enabled)
        {
            // Stamped as seen so a later toggle-on doesn't replay it,
            // but no on-screen state mutated.
            return;
        }
        activeOpp = opponentName;
        activeSubtext = subtext == null ? "" : subtext;
        activeColor = (opponentColor != null) ? opponentColor : Color.WHITE;
        shownSince = System.currentTimeMillis();
    }

    /** Clears any pending popup AND the dedupe state. Called on
     *  plugin shutdown so the next plugin start can pop fresh matches
     *  without remembering ids from a prior session. Idempotent. */
    public void clear()
    {
        drop();
        lastShownId = null;
    }

    /** Drops the popup on screen; the dedupe key stays. */
    private void drop()
    {
        activeOpp = null;
        activeSubtext = null;
        activeColor = null;
        shownSince = 0L;
    }

    @Override
    Dimension draw(Graphics2D g)
    {
        if (!config.enableMatchFoundNotification())
        {
            // Disabled mid-popup: drop active state so re-enabling
            // doesn't resume a partially-faded render.
            if (shownSince != 0L) drop();
            return null;
        }
        // In-combat suppression — DEFER the popup instead of
        // dropping it. Active state is preserved + shownSince is
        // pinned forward each suppressed frame so the visible-window
        // timer freezes during combat (2026-05-25 user spec: "still show
        // the notification after the combat has ended"). The match-found
        // popup carries the meeting world + place — the user MUST
        // see it eventually, just not while taking hits.
        if (config.suppressNotificationsInCombat() && fightMonitor.isInCombat())
        {
            if (shownSince != 0L) this.shownSince = System.currentTimeMillis();
            return null;
        }
        String opponent = activeOpp;
        long start = shownSince;
        if (opponent == null || start == 0L) return null;
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= VISIBLE_MS)
        {
            // End-of-life: drop active state but DO NOT clear
            // lastShownId — that's the dedupe key and
            // must outlive the visual window so a later re-push of
            // the same fight_proposed stays suppressed.
            drop();
            return null;
        }
        return paint(g, alphaAt(elapsed), opponent);
    }

    static float alphaAt(long elapsedMs)
    {
        return fade(elapsedMs, VISIBLE_MS) * PEAK_ALPHA;
    }

    @Override
    void paintBody(Graphics2D g, int x, int y, String opponent)
    {
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);

        // Title — centred in the title bar.
        Font titleFont = FontManager.getRunescapeBoldFont().deriveFont(TITLE_PT);
        g.setFont(titleFont);
        FontMetrics tfm = g.getFontMetrics();
        String title = "Match found!";
        int titleX = x + (POPUP_W - tfm.stringWidth(title)) / 2;
        int titleCenterY = y + 1 + TITLE_BAR_H / 2;
        int titleY = titleCenterY + (tfm.getAscent() - tfm.getDescent()) / 2;
        g.setColor(TITLE_FG);
        g.drawString(title, titleX, titleY);

        g.setFont(FontManager.getRunescapeSmallFont());
        FontMetrics bodyFm = g.getFontMetrics();
        int maxBodyW = POPUP_W - 2 * SIDE_MARGIN;
        String name = fitText(opponent, bodyFm, maxBodyW);
        int opponentW = bodyFm.stringWidth(name);
        int captionX = x + (POPUP_W - opponentW) / 2;
        int bodyTop = y + TITLE_BAR_H + 2;
        int bodyBottom = y + POPUP_H;
        int lineH = bodyFm.getHeight();
        int blockTop = (bodyTop + bodyBottom - 2 * lineH) / 2;
        int captionY = blockTop + bodyFm.getAscent();
        Color opponentColor = activeColor;
        g.setColor(opponentColor != null ? opponentColor : BODY_FG);
        g.drawString(name, captionX, captionY);

        String sub = activeSubtext;
        if (sub == null) sub = "";
        String trimmed = fitText(sub, bodyFm, maxBodyW);
        int subX = x + (POPUP_W - bodyFm.stringWidth(trimmed)) / 2;
        int subY = captionY + lineH;
        g.setColor(BODY_FG);
        g.drawString(trimmed, subX, subY);
    }

    /** Truncates {@code text} with a trailing ellipsis so it fits in
     *  {@code maxWidth} pixels under {@code fm}. */
    private static String fitText(String text, FontMetrics fm, int maxWidth)
    {
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ell = "…";
        int ellW = fm.stringWidth(ell);
        if (ellW >= maxWidth) return ell;
        int lo = 0;
        int hi = text.length();
        while (lo < hi)
        {
            int mid = (lo + hi + 1) >>> 1;
            int w = fm.stringWidth(text.substring(0, mid)) + ellW;
            if (w <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + ell;
    }
}
