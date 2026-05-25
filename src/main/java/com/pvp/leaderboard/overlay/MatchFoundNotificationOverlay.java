package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
import java.awt.RenderingHints;
import java.awt.Stroke;

/**
 * In-game popup notification for the "fight locked in" wire moment —
 * fires on {@code lobby/fight_proposed} (both perspectives: the inviter
 * just had their invite accepted, the invitee just clicked Accept on an
 * incoming invite). Painted with the same OSRS Collection-Log palette
 * and proportions as {@link LobbyInviteNotificationOverlay} so the two
 * popups visually rhyme.
 *
 * <p>Body content branches on who started the fight:
 * <ul>
 *   <li><b>Inviter perspective</b> (you sent the invite, they accepted):
 *       caption is {@code "<opponent> accepted your invite"} with the
 *       opponent's name tinted by their rank colour, and the sub-line
 *       is the style / build / location summary.</li>
 *   <li><b>Invitee perspective</b> (you accepted their invite):
 *       caption is just the opponent's name (rank-tinted, no trailing
 *       phrase — you already saw a "PvP Invite" popup a moment ago,
 *       this one confirms the match locked in). Sub-line is the same
 *       style / build / location summary.</li>
 * </ul>
 *
 * <p>Lifecycle is single-shot: {@link #showMatch} stamps the active
 * notification + start time and {@link #render(Graphics2D)} handles the
 * fade-in / hold / fade-out animation and self-clears when the
 * hardcoded {@link #TOTAL_VISIBLE_MS} window elapses. A second match
 * while one is still on screen replaces the old one.
 *
 * <p><b>Threading:</b> {@link #showMatch} is callable from any thread;
 * the overlay's render runs on the RuneLite game thread. Active-state
 * fields are {@code volatile} so the render path sees the latest stamp
 * without taking a lock.
 *
 * <p><b>Config gate:</b> the overlay short-circuits when
 * {@link PvPLeaderboardConfig#enableMatchFoundNotification()} is false,
 * so users who find it noisy can disable it without unregistering the
 * overlay.
 *
 * <p><b>Duration:</b> hardcoded at 5 s. The user-facing config exposes
 * only an on/off toggle (per the 2026-05-24 product spec — "match
 * found is more important than an invite, fewer knobs, longer hold").
 */
@Slf4j
@Singleton
public final class MatchFoundNotificationOverlay extends Overlay
{
    /** Fade-in duration at the start of each popup. */
    private static final long FADE_IN_MS = 200L;
    /** Fade-out duration at the end of each popup. */
    private static final long FADE_OUT_MS = 400L;
    /** Total on-screen window (includes fade in + fade out). Hardcoded
     *  per the product spec — no config slider exposed for this one. */
    private static final long TOTAL_VISIBLE_MS = 5_000L;

    // ---- Collection-Log popup palette (eyedropper from the widget) ----
    /** Frame stone-brown fill — main interior + title bar share this
     *  colour, the title bar is differentiated by the separator line
     *  alone (matches the vanilla widget). */
    private static final Color FRAME_FILL = new Color(0x4D, 0x46, 0x39);
    /** Outer 1-px hard outline that bounds the whole popup. */
    private static final Color OUTER_OUTLINE = new Color(0x12, 0x0F, 0x0A);
    /** Bevel highlight running just inside the outer outline. */
    private static final Color FRAME_BEVEL = new Color(0x74, 0x69, 0x52);
    /** Horizontal separator under the title. */
    private static final Color TITLE_SEPARATOR = new Color(0x1C, 0x18, 0x10);
    /** Bevel highlight running just below the title separator. */
    private static final Color TITLE_SEPARATOR_HI = new Color(0x6E, 0x63, 0x4D);
    /** Title text — RuneScape orange. */
    private static final Color TITLE_FG = new Color(0xFF, 0x98, 0x1F);
    /** Body text — slight cream off-white so it rhymes with OSRS UI. */
    private static final Color BODY_FG = new Color(0xFF, 0xFF, 0xFF);

    // ---- Popup geometry (eyedropper-tuned to mirror the vanilla widget) ----
    private static final int POPUP_W = 310;
    private static final int POPUP_H = 132;
    /** Title bar height (top section above the separator). */
    private static final int TITLE_BAR_H = 38;
    /** Distance from the top of the viewport. */
    private static final int POPUP_TOP_OFFSET = 22;

    /** Body text size — caption + sub-line share one bold font. */
    private static final float BODY_FONT_PT = 16f;

    private final Client client;
    private final PvPLeaderboardConfig config;

    /** {@code fight_session_id} of the most-recently-shown match. Sticky
     *  for the lifetime of the singleton so a re-push of the same
     *  fight_proposed event (server replay, bus double-fire) does NOT
     *  pop the same popup twice. Cleared by {@link #clear()} on
     *  plugin shutdown. */
    private volatile String lastShownFightSessionId;

    private volatile String activeOpponentName;
    private volatile String activeSubtext;
    /** When true, the caption is {@code "<opponent> accepted your invite"}
     *  (inviter perspective); when false, the caption is just the
     *  opponent name (invitee perspective). */
    private volatile boolean activeIsInviter;
    /** Rank-tier colour for the opponent name in the caption. Null is
     *  coerced to {@link Color#WHITE} at intake. */
    private volatile Color activeOpponentColor;
    private volatile long activeStartMs;

    @Inject
    public MatchFoundNotificationOverlay(Client client, PvPLeaderboardConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    /**
     * Triggers a fresh popup for a locked-in match against
     * {@code opponentName}. {@code subtext} is a short summary
     * ({@code "NH (Main) at Arena"}) that becomes the body's second
     * line. {@code isInviter} selects which caption variant the body
     * renders. Safe to call from any thread.
     *
     * <p><b>De-duplication:</b> if {@code fightSessionId} matches the
     * last fight this overlay rendered, the call is silently dropped.
     * This is the source-of-truth dedupe; the panel side does not need
     * to guard.
     *
     * <p><b>Config gate:</b> when the popup is disabled in config we
     * still update {@link #lastShownFightSessionId} so re-enabling the
     * popup later doesn't suddenly surface a stale match the user
     * "missed" while it was off.
     */
    public void showMatch(String fightSessionId,
                          String opponentName,
                          String subtext,
                          Color opponentColor,
                          boolean isInviter)
    {
        if (opponentName == null || opponentName.isEmpty()) return;
        if (fightSessionId != null && fightSessionId.equals(this.lastShownFightSessionId)) return;
        this.lastShownFightSessionId = fightSessionId;
        if (!config.enableMatchFoundNotification())
        {
            // Stamped as seen so a later toggle-on doesn't replay it,
            // but no on-screen state mutated.
            return;
        }
        this.activeOpponentName = opponentName;
        this.activeSubtext = subtext == null ? "" : subtext;
        this.activeIsInviter = isInviter;
        this.activeOpponentColor = (opponentColor != null) ? opponentColor : Color.WHITE;
        this.activeStartMs = System.currentTimeMillis();
    }

    /** Clears any pending popup AND the dedupe state. Called on
     *  plugin shutdown so the next plugin start can pop fresh matches
     *  without remembering ids from a prior session. Idempotent. */
    public void clear()
    {
        this.activeOpponentName = null;
        this.activeSubtext = null;
        this.activeIsInviter = false;
        this.activeOpponentColor = null;
        this.activeStartMs = 0L;
        this.lastShownFightSessionId = null;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.enableMatchFoundNotification())
        {
            // Disabled mid-popup: drop active state so re-enabling
            // doesn't resume a partially-faded render.
            if (activeStartMs != 0L)
            {
                this.activeOpponentName = null;
                this.activeSubtext = null;
                this.activeIsInviter = false;
                this.activeOpponentColor = null;
                this.activeStartMs = 0L;
            }
            return null;
        }
        String opponent = activeOpponentName;
        long start = activeStartMs;
        if (opponent == null || start == 0L) return null;
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= TOTAL_VISIBLE_MS)
        {
            // End-of-life: drop active state but DO NOT clear
            // lastShownFightSessionId — that's the dedupe key and
            // must outlive the visual window so a later re-push of
            // the same fight_proposed stays suppressed.
            this.activeOpponentName = null;
            this.activeSubtext = null;
            this.activeIsInviter = false;
            this.activeOpponentColor = null;
            this.activeStartMs = 0L;
            return null;
        }

        // Three-segment alpha curve: 0 → 1 over FADE_IN_MS, hold,
        // 1 → 0 over FADE_OUT_MS at the end.
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
        int canvasW = canvas.width;
        int x = Math.max(0, (canvasW - POPUP_W) / 2);
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
            paintText(g, x, y, opponent);
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

    /** Paints the title (orange, centred in the title bar) + body's
     *  two centred lines. Caption layout branches on
     *  {@link #activeIsInviter} — sender-perspective glues
     *  {@code " accepted your invite"} onto the opponent name (split
     *  across two drawString calls so the opponent name keeps its
     *  rank-tier tint while the trailing phrase stays body off-white).
     *  Invitee-perspective draws just the rank-tinted opponent name.
     *  Both body lines use {@link #BODY_FONT_PT} Runescape Bold
     *  (matches {@link LobbyInviteNotificationOverlay} body styling). */
    private void paintText(Graphics2D g, int x, int y, String opponent)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Title — centred in the title bar.
        Font titleFont = FontManager.getRunescapeBoldFont().deriveFont(20f);
        g.setFont(titleFont);
        FontMetrics tfm = g.getFontMetrics();
        String title = "Match found!";
        int titleX = x + (POPUP_W - tfm.stringWidth(title)) / 2;
        int titleCenterY = y + 1 + TITLE_BAR_H / 2;
        int titleY = titleCenterY + (tfm.getAscent() - tfm.getDescent()) / 2;
        g.setColor(TITLE_FG);
        g.drawString(title, titleX, titleY);

        // Body caption + sub-line — both use the same bold Runescape
        // font at {@link #BODY_FONT_PT} (matches invite popup styling).
        Font bodyFont = FontManager.getRunescapeBoldFont().deriveFont(BODY_FONT_PT);
        g.setFont(bodyFont);
        FontMetrics bodyFm = g.getFontMetrics();
        boolean inviter = activeIsInviter;
        String tail = inviter ? " accepted your invite" : "";
        int opponentW = bodyFm.stringWidth(opponent);
        int tailW = bodyFm.stringWidth(tail);
        int captionX = x + (POPUP_W - (opponentW + tailW)) / 2;
        int bodyTop = y + TITLE_BAR_H + 2;
        int bodyBottom = y + POPUP_H;
        int bodyMidY = (bodyTop + bodyBottom) / 2;
        int captionY = bodyMidY - (bodyFm.getHeight() / 2);
        Color opponentColor = activeOpponentColor;
        g.setColor(opponentColor != null ? opponentColor : BODY_FG);
        g.drawString(opponent, captionX, captionY);
        if (tailW > 0)
        {
            g.setColor(BODY_FG);
            g.drawString(tail, captionX + opponentW, captionY);
        }

        String sub = activeSubtext;
        if (sub == null) sub = "";
        String trimmed = truncateToFit(sub, bodyFm, POPUP_W - 24);
        int subX = x + (POPUP_W - bodyFm.stringWidth(trimmed)) / 2;
        int subY = captionY + bodyFm.getHeight() - 2;
        g.setColor(BODY_FG);
        g.drawString(trimmed, subX, subY);
    }

    /** Truncates {@code text} with a trailing ellipsis so it fits in
     *  {@code maxWidth} pixels under {@code fm}. */
    private static String truncateToFit(String text, FontMetrics fm, int maxWidth)
    {
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ell = "\u2026";
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
