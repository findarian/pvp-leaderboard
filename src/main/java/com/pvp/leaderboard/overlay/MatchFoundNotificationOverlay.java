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
import java.util.function.BooleanSupplier;

@Slf4j
@Singleton
public final class MatchFoundNotificationOverlay extends Overlay
{
    /** Fade-in duration at the start of each popup. */
    static final long FADE_IN_MS = 200L;
    /** Fade-out duration at the end of each popup. */
    static final long FADE_OUT_MS = 400L;
    /** Total on-screen window (includes fade in + fade out). Hardcoded
     *  per the product spec — no config slider exposed for this one. */
    static final long TOTAL_VISIBLE_MS = 5_000L;
    static final float PEAK_ALPHA = 0.8f;

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

    static final int POPUP_W = 155;
    static final int POPUP_H = 66;
    /** Title bar height (top section above the separator). */
    static final int TITLE_BAR_H = 19;
    /** Distance from the top of the viewport. */
    static final int POPUP_TOP_OFFSET = 11;
    static final float TITLE_FONT_PT = 10f;
    private static final int BODY_SIDE_MARGIN = 6;

    private final Client client;
    private final PvPLeaderboardConfig config;

    /** Wired post-construction by {@link com.pvp.leaderboard.PvPLeaderboardPlugin#startUp()}
     *  to {@code FightMonitor::isInCombat}. {@code null} until that
     *  wiring lands; the render path treats null as "not in combat"
     *  so a missing wiring never inadvertently suppresses every popup. */
    private volatile BooleanSupplier inCombatProvider;

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

    /** Diagnostic state for the in-combat suppression DEBUG log —
     *  mirrors {@link LobbyInviteNotificationOverlay}. See its
     *  comments for the contract. */
    private volatile long lifecycleId;
    private volatile boolean firstDecisionLogged;
    private volatile boolean wasDeferred;

    @Inject
    public MatchFoundNotificationOverlay(Client client, PvPLeaderboardConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    /** Plugin wires {@code FightMonitor::isInCombat} here on
     *  startup. Pass {@code null} to disable the combat-suppression
     *  branch entirely; pass a const supplier in tests. */
    public void setInCombatProvider(BooleanSupplier provider)
    {
        this.inCombatProvider = provider;
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
        if (fightSessionId != null && fightSessionId.equals(this.lastShownFightSessionId))
        {
            log.debug("[MatchFoundPopup] showMatch SKIPPED (dedupe) fightSessionId={} opponent={}",
                fightSessionId, opponentName);
            return;
        }
        this.lastShownFightSessionId = fightSessionId;
        boolean enabled = config.enableMatchFoundNotification();
        boolean suppressInCombat = config.suppressNotificationsInCombat();
        BooleanSupplier provider = this.inCombatProvider;
        boolean providerWired = provider != null;
        boolean inCombatNow = isInCombatSafe();
        log.debug("[MatchFoundPopup] showMatch ACCEPTED fightSessionId={} opponent={} popupEnabled={}"
                + " suppressInCombat={} inCombatProviderWired={} inCombatNow={}",
            fightSessionId, opponentName, enabled, suppressInCombat, providerWired, inCombatNow);
        if (!enabled)
        {
            // Stamped as seen so a later toggle-on doesn't replay it,
            // but no on-screen state mutated.
            return;
        }
        this.activeOpponentName = opponentName;
        this.activeSubtext = subtext == null ? "" : subtext;
        this.activeIsInviter = isInviter;
        this.activeOpponentColor = (opponentColor != null) ? opponentColor : Color.WHITE;
        long stamp = System.currentTimeMillis();
        this.activeStartMs = stamp;
        this.lifecycleId = stamp;
        this.firstDecisionLogged = false;
        this.wasDeferred = false;
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
        this.lifecycleId = 0L;
        this.firstDecisionLogged = false;
        this.wasDeferred = false;
    }

    /** Null-safe wrapper around {@link #inCombatProvider}. Returns
     *  {@code false} (out-of-combat) when the provider hasn't been
     *  wired yet — defends against a startup-order race. Mirrors
     *  the LobbyInviteNotificationOverlay companion helper. */
    private boolean isInCombatSafe()
    {
        BooleanSupplier provider = this.inCombatProvider;
        if (provider == null) return false;
        try
        {
            return provider.getAsBoolean();
        }
        catch (Throwable t)
        {
            log.warn("[MatchFoundNotificationOverlay] inCombatProvider threw - treating as out-of-combat", t);
            return false;
        }
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // Catch-all guard mirrors LobbyInviteNotificationOverlay —
        // any throwable from the inner body is logged once with the
        // overlay class name + a real stack (the first allocation
        // happens here, before HotSpot's OmitStackTraceInFastThrow
        // strips the trace from repeat throws) and swallowed so
        // OverlayRenderer doesn't WARN-spam every frame at 60 FPS.
        try
        {
            return renderInner(g);
        }
        catch (Throwable t)
        {
            log.warn("[MatchFoundNotificationOverlay] render threw - swallowing", t);
            return null;
        }
    }

    private Dimension renderInner(Graphics2D g)
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
        // In-combat suppression — DEFER the popup instead of
        // dropping it. Active state is preserved + activeStartMs is
        // pinned forward each suppressed frame so the visible-window
        // timer freezes during combat. Mirror of
        // {@link LobbyInviteNotificationOverlay} so both popups
        // share the 2026-05-25 user-spec contract: "still show the
        // notification after the combat has ended". The match-found
        // popup carries the meeting world + place — the user MUST
        // see it eventually, just not while taking hits.
        if (config.suppressNotificationsInCombat() && isInCombatSafe())
        {
            if (activeStartMs != 0L)
            {
                if (!firstDecisionLogged)
                {
                    log.debug("[MatchFoundPopup] DEFER (in combat) lifecycleId={} opponent={}",
                        lifecycleId, activeOpponentName);
                    this.firstDecisionLogged = true;
                }
                this.wasDeferred = true;
                this.activeStartMs = System.currentTimeMillis();
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
        if (!firstDecisionLogged || wasDeferred)
        {
            log.debug("[MatchFoundPopup] PAINT lifecycleId={} opponent={} afterDefer={}",
                lifecycleId, opponent, wasDeferred);
            this.firstDecisionLogged = true;
            this.wasDeferred = false;
        }

        float alpha = alphaAt(elapsed);

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

    static float alphaAt(long elapsedMs)
    {
        float share;
        if (elapsedMs < FADE_IN_MS)
        {
            share = (float) elapsedMs / (float) FADE_IN_MS;
        }
        else if (elapsedMs > (TOTAL_VISIBLE_MS - FADE_OUT_MS))
        {
            long fadeStart = TOTAL_VISIBLE_MS - FADE_OUT_MS;
            share = 1f - ((float) (elapsedMs - fadeStart) / (float) FADE_OUT_MS);
        }
        else
        {
            share = 1f;
        }
        if (share < 0f) share = 0f;
        if (share > 1f) share = 1f;
        return share * PEAK_ALPHA;
    }

    static Font bodyFont()
    {
        return FontManager.getRunescapeSmallFont();
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

    private void paintText(Graphics2D g, int x, int y, String opponent)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Title — centred in the title bar.
        Font titleFont = FontManager.getRunescapeBoldFont().deriveFont(TITLE_FONT_PT);
        g.setFont(titleFont);
        FontMetrics tfm = g.getFontMetrics();
        String title = "Match found!";
        int titleX = x + (POPUP_W - tfm.stringWidth(title)) / 2;
        int titleCenterY = y + 1 + TITLE_BAR_H / 2;
        int titleY = titleCenterY + (tfm.getAscent() - tfm.getDescent()) / 2;
        g.setColor(TITLE_FG);
        g.drawString(title, titleX, titleY);

        g.setFont(bodyFont());
        FontMetrics bodyFm = g.getFontMetrics();
        int maxBodyW = POPUP_W - 2 * BODY_SIDE_MARGIN;
        String name = truncateToFit(opponent, bodyFm, maxBodyW);
        String tail = activeIsInviter ? " accepted your invite" : "";
        int opponentW = bodyFm.stringWidth(name);
        tail = tail.isEmpty() ? tail : truncateToFit(tail, bodyFm, maxBodyW - opponentW);
        if (bodyFm.stringWidth(tail) + opponentW > maxBodyW) tail = "";
        int tailW = bodyFm.stringWidth(tail);
        int captionX = x + (POPUP_W - (opponentW + tailW)) / 2;
        int bodyTop = y + TITLE_BAR_H + 2;
        int bodyBottom = y + POPUP_H;
        int lineH = bodyFm.getHeight();
        int blockTop = (bodyTop + bodyBottom - 2 * lineH) / 2;
        int captionY = blockTop + bodyFm.getAscent();
        Color opponentColor = activeOpponentColor;
        g.setColor(opponentColor != null ? opponentColor : BODY_FG);
        g.drawString(name, captionX, captionY);
        if (tailW > 0)
        {
            g.setColor(BODY_FG);
            g.drawString(tail, captionX + opponentW, captionY);
        }

        String sub = activeSubtext;
        if (sub == null) sub = "";
        String trimmed = truncateToFit(sub, bodyFm, maxBodyW);
        int subX = x + (POPUP_W - bodyFm.stringWidth(trimmed)) / 2;
        int subY = captionY + lineH;
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
