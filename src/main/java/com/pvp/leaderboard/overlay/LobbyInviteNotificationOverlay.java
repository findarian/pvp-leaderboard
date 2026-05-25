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
 * In-game popup notification for incoming PvP lobby invites. Painted
 * to mimic the OSRS Collection-Log "New item:" popup 1:1 — same
 * stone-brown frame, four L-bracket corner ornaments, top title bar
 * with an orange centred title and a darker separator line, white
 * centred body text underneath.
 *
 * <p>Visual reference: the in-game Collection-Log popup that fires
 * when a new item is logged. All colours / proportions tuned by
 * eyedropper against a screenshot of that widget so the two
 * notifications visually rhyme.
 *
 * <p>Lifecycle is single-shot: {@link #showInvite(String, String)}
 * stamps the active notification + start time and the overlay's
 * {@link #render(Graphics2D)} handles the fade-in / hold / fade-out
 * animation and self-clears when the duration elapses. A second
 * invite while one is still on screen replaces the old one.
 *
 * <p><b>Threading:</b> {@link #showInvite} is callable from any
 * thread; the overlay's render runs on the RuneLite game thread.
 * Active-state fields are {@code volatile} so the render path sees
 * the latest stamp without taking a lock.
 *
 * <p><b>Config gate:</b> the overlay short-circuits when
 * {@link PvPLeaderboardConfig#enableLobbyInviteNotification()} is
 * false, so users who find it noisy can disable it without
 * unregistering the overlay.
 */
@Slf4j
@Singleton
public final class LobbyInviteNotificationOverlay extends Overlay
{
    /** Fade-in duration at the start of each popup. Independent of
     *  the user-configurable hold duration — small enough that even
     *  the minimum 1-s total still has a visible hold segment. */
    private static final long FADE_IN_MS = 200L;
    /** Fade-out duration at the end of each popup. */
    private static final long FADE_OUT_MS = 400L;
    /** Floor for the total visible window so the fade-in + fade-out
     *  always fit with at least 200 ms of full-opacity hold. The
     *  config slider's {@code min=1} already enforces 1 s but this
     *  is the source-of-truth safety floor (defends against a
     *  hand-edited runelite.properties slipping a lower value past
     *  the {@code @Range} guard). */
    private static final long MIN_TOTAL_VISIBLE_MS = FADE_IN_MS + FADE_OUT_MS + 200L;

    // ---- Collection-Log popup palette (eyedropper from the widget) ----
    /** Frame stone-brown fill — main interior + title bar share this
     *  colour, the title bar is differentiated by the separator line
     *  alone (matches the vanilla widget). */
    private static final Color FRAME_FILL = new Color(0x4D, 0x46, 0x39);
    /** Outer 1-px hard outline that bounds the whole popup. */
    private static final Color OUTER_OUTLINE = new Color(0x12, 0x0F, 0x0A);
    /** Bevel highlight running just inside the outer outline — gives
     *  the frame its faint 3-D edge against the game viewport. */
    private static final Color FRAME_BEVEL = new Color(0x74, 0x69, 0x52);
    /** Horizontal separator under the title — same dark tone as the
     *  outer outline so the title visually detaches from the body. */
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
    /** Distance from the top of the viewport. Sits below the OSRS
     *  XP-drop / wintertodt slot so it doesn't overlap vanilla
     *  notifications. */
    private static final int POPUP_TOP_OFFSET = 22;

    /** Body text size — caption + sub-line share one bold font so
     *  "Giezey wants to fight:" and "NH (Pure) at wildy" render at
     *  the same weight/size (user-facing spec 2026-05-24). */
    private static final float BODY_FONT_PT = 16f;

    private final Client client;
    private final PvPLeaderboardConfig config;

    /** {@code invite_id} of the most-recently-shown invite. Sticky for
     *  the lifetime of the singleton (i.e. the lifetime of the
     *  plugin instance) so a re-push of the same invite — server-side
     *  replay on reconnect, a panel-side double-fire from racy
     *  bus events, etc. — does NOT pop the same popup twice. Cleared
     *  by {@link #clear()} which is only called on plugin shutdown,
     *  so the dedupe span is "until plugin off". */
    private volatile String lastShownInviteId;

    private volatile String activeSenderName;
    private volatile String activeSubtext;
    /** Tint for {@link #activeSenderName} in the body line. By spec
     *  the panel passes the same rank-tier colour the lobby row uses
     *  for this player, or {@link Color#WHITE} when the shard hasn't
     *  resolved their rank yet (the "Waiting" state). Falls back to
     *  white if the panel passes null (defensive, never happens in
     *  production). */
    private volatile Color activeSenderColor;
    private volatile long activeStartMs;

    @Inject
    public LobbyInviteNotificationOverlay(Client client, PvPLeaderboardConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    /**
     * Triggers a fresh popup for an incoming fight invite from
     * {@code senderName}. {@code subtext} is a short summary
     * ({@code "NH (Main) at Arena"}) that becomes the body's second
     * line. Safe to call from any thread.
     *
     * <p><b>De-duplication:</b> if {@code inviteId} matches the last
     * invite this overlay rendered, the call is silently dropped —
     * the user has already seen the popup for this invite and a
     * second pop (server replay on reconnect, late bus delivery,
     * etc.) would just be visual noise. This is the source-of-truth
     * dedupe; the panel side does not need to guard.
     *
     * <p><b>Config gate:</b> when the popup is disabled in config we
     * still update {@link #lastShownInviteId} so re-enabling the
     * popup later doesn't suddenly surface a stale invite the user
     * "missed" while it was off.
     */
    public void showInvite(String inviteId, String senderName, String subtext, Color senderColor)
    {
        if (senderName == null || senderName.isEmpty()) return;
        // Dedupe by invite id — once per id, ever (within this
        // plugin session). A null inviteId is treated as "no
        // dedupe info available" and always shows; in practice the
        // panel always passes a real id.
        if (inviteId != null && inviteId.equals(this.lastShownInviteId)) return;
        this.lastShownInviteId = inviteId;
        if (!config.enableLobbyInviteNotification())
        {
            // Stamped as seen so a later toggle-on doesn't replay it,
            // but no on-screen state mutated.
            return;
        }
        this.activeSenderName = senderName;
        this.activeSubtext = subtext == null ? "" : subtext;
        this.activeSenderColor = (senderColor != null) ? senderColor : Color.WHITE;
        this.activeStartMs = System.currentTimeMillis();
    }

    /** Clears any pending popup AND the dedupe state. Called on
     *  plugin shutdown so the next plugin start can pop fresh
     *  invites without remembering ids from a prior session.
     *  Idempotent. */
    public void clear()
    {
        this.activeSenderName = null;
        this.activeSubtext = null;
        this.activeSenderColor = null;
        this.activeStartMs = 0L;
        this.lastShownInviteId = null;
    }

    /** Effective total-visible window, in ms, derived from the
     *  user-configurable duration with the {@link #MIN_TOTAL_VISIBLE_MS}
     *  safety floor applied. Read each render so a mid-popup config
     *  change takes effect immediately. */
    private long totalVisibleMs()
    {
        long fromConfig = config.lobbyInviteNotificationDurationSeconds() * 1000L;
        return Math.max(MIN_TOTAL_VISIBLE_MS, fromConfig);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.enableLobbyInviteNotification())
        {
            // Disabled mid-popup: drop active state so re-enabling
            // doesn't resume a partially-faded render.
            if (activeStartMs != 0L)
            {
                this.activeSenderName = null;
                this.activeSubtext = null;
                this.activeSenderColor = null;
                this.activeStartMs = 0L;
            }
            return null;
        }
        String sender = activeSenderName;
        long start = activeStartMs;
        if (sender == null || start == 0L) return null;
        long totalMs = totalVisibleMs();
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= totalMs)
        {
            // End-of-life: drop active state but DO NOT clear
            // lastShownInviteId — that's the dedupe key and must
            // outlive the visual window so a later re-push of the
            // same invite stays suppressed.
            this.activeSenderName = null;
            this.activeSubtext = null;
            this.activeSenderColor = null;
            this.activeStartMs = 0L;
            return null;
        }

        // Three-segment alpha curve: 0 → 1 over FADE_IN_MS, 1.0 hold,
        // 1 → 0 over FADE_OUT_MS at the end. The hold segment
        // shrinks as the configured duration drops; the MIN guard in
        // totalVisibleMs() keeps it positive.
        float alpha;
        if (elapsed < FADE_IN_MS)
        {
            alpha = (float) elapsed / (float) FADE_IN_MS;
        }
        else if (elapsed > (totalMs - FADE_OUT_MS))
        {
            long fadeStart = totalMs - FADE_OUT_MS;
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
            paintText(g, x, y, sender);
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

    /** Paints the stone-brown rectangle + outer outline + inner bevel
     *  highlight. Anti-aliasing is forced OFF for the frame strokes
     *  so the 1-px outline lands on whole pixels (matches the crisp
     *  pixel-art aesthetic of vanilla OSRS UI; AA strokes would
     *  smear the outline to 2 px and look out-of-place). */
    private void paintFrame(Graphics2D g, int x, int y)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        // Main fill
        g.setColor(FRAME_FILL);
        g.fillRect(x, y, POPUP_W, POPUP_H);
        // Inner bevel highlight (drawn first so the outer outline
        // overdraws it at the very edge — pixel-perfect 1-px gap
        // between bevel and outer outline).
        g.setStroke(new BasicStroke(1f));
        g.setColor(FRAME_BEVEL);
        g.drawRect(x + 1, y + 1, POPUP_W - 3, POPUP_H - 3);
        // Outer outline
        g.setColor(OUTER_OUTLINE);
        g.drawRect(x, y, POPUP_W - 1, POPUP_H - 1);
    }

    /** Paints the dark separator strip (outline + bevel highlight
     *  pair) between the title bar and the body — same recipe as
     *  the outer frame's outline + bevel. */
    private void paintTitleSeparator(Graphics2D g, int x, int y)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setStroke(new BasicStroke(1f));
        int sepY = y + TITLE_BAR_H;
        // Dark separator
        g.setColor(TITLE_SEPARATOR);
        g.drawLine(x + 2, sepY, x + POPUP_W - 3, sepY);
        // Light bevel just below
        g.setColor(TITLE_SEPARATOR_HI);
        g.drawLine(x + 2, sepY + 1, x + POPUP_W - 3, sepY + 1);
    }

    /** Paints the title (orange, centred in the title bar) and the
     *  body's two centred lines: a "{@code Sender wants to fight}"
     *  caption followed by the {@code style/build/location} sub-text.
     *  Both body lines use {@link #BODY_FONT_PT} Runescape Bold so
     *  the caption and summary render at the same size/weight.
     *  Text is rendered with AA on (smoother glyphs) while the
     *  frame paint above forced AA off for crisp 1-px strokes. */
    private void paintText(Graphics2D g, int x, int y, String sender)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Title — centred in the title bar
        Font titleFont = FontManager.getRunescapeBoldFont().deriveFont(20f);
        g.setFont(titleFont);
        FontMetrics tfm = g.getFontMetrics();
        String title = "PvP Invite";
        int titleX = x + (POPUP_W - tfm.stringWidth(title)) / 2;
        // Vertical centre of the title bar (inside the outer outline).
        int titleCenterY = y + 1 + TITLE_BAR_H / 2;
        int titleY = titleCenterY + (tfm.getAscent() - tfm.getDescent()) / 2;
        g.setColor(TITLE_FG);
        g.drawString(title, titleX, titleY);

        // Body — two centred lines: caption + sub-line. Both use the
        // same bold Runescape font at {@link #BODY_FONT_PT} so the
        // name/caption and the style/build/location summary read as
        // one uniform block (not a small plain caption + oversized
        // headline sub-line).
        Font bodyFont = FontManager.getRunescapeBoldFont().deriveFont(BODY_FONT_PT);
        g.setFont(bodyFont);
        FontMetrics bodyFm = g.getFontMetrics();
        final String captionTail = " wants to fight:";
        int senderW = bodyFm.stringWidth(sender);
        int tailW = bodyFm.stringWidth(captionTail);
        int captionX = x + (POPUP_W - (senderW + tailW)) / 2;
        // Body region spans (TITLE_BAR_H + 2 [separator]) to POPUP_H.
        int bodyTop = y + TITLE_BAR_H + 2;
        int bodyBottom = y + POPUP_H;
        int bodyMidY = (bodyTop + bodyBottom) / 2;
        int captionY = bodyMidY - (bodyFm.getHeight() / 2);
        // Sender name in rank-tier colour (or white when "Waiting").
        // {@link #activeSenderColor} is set by {@link #showInvite}
        // from the panel's RankUtils-based resolution; null only for
        // overlapping clear/render races, so guard defensively.
        Color senderColor = activeSenderColor;
        g.setColor(senderColor != null ? senderColor : BODY_FG);
        g.drawString(sender, captionX, captionY);
        g.setColor(BODY_FG);
        g.drawString(captionTail, captionX + senderW, captionY);

        String sub = activeSubtext;
        if (sub == null) sub = "";
        String trimmed = truncateToFit(sub, bodyFm, POPUP_W - 24);
        int subX = x + (POPUP_W - bodyFm.stringWidth(trimmed)) / 2;
        int subY = captionY + bodyFm.getHeight() - 2;
        g.drawString(trimmed, subX, subY);
    }

    /** Truncates {@code text} with a trailing ellipsis so it fits in
     *  {@code maxWidth} pixels under {@code fm}. Returns the input
     *  verbatim when no truncation is needed. */
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
