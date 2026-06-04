package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.PvPLeaderboardPlugin;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

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
 * <p>Lifecycle is single-shot: {@link #showInvite} stamps the active
 * notification + start time and the overlay's
 * {@link #render(Graphics2D)} handles the fade-in / hold / fade-out
 * animation and self-clears when the duration elapses. A second
 * invite while one is still on screen replaces the old one.
 *
 * <p><b>Body copy (2026-05-29 spec):</b> the body is a single
 * instruction sentence — "Click the [icon] on the right to accept a
 * fight from {sender}" — where {@code [icon]} is the plugin's RuneLite
 * sidebar nav icon drawn inline and {sender} is tinted with the
 * sender's rank-tier colour. The earlier "{sender} wants to fight:" +
 * style/build/location sub-line was dropped per the same spec, so the
 * overlay no longer carries any sub-text state.
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

    /** Body text size for the instruction sentence. */
    private static final float BODY_FONT_PT = 16f;

    /** Horizontal inset applied to each side of the body text block so
     *  the wrapped instruction never runs into the frame bevel. */
    private static final int BODY_SIDE_PAD = 12;

    /** The plugin's RuneLite sidebar nav icon, drawn inline in the
     *  body so "Click the [icon] on the right …" points the user at
     *  the exact toolbar button they need to press. Loaded once from
     *  the same resource the plugin's {@code NavigationButton} uses
     *  (anchored on {@link PvPLeaderboardPlugin} so the classpath
     *  lookup matches). {@code null} if the resource can't be read —
     *  the body still renders, just without the inline glyph. */
    private static final BufferedImage PANEL_ICON = loadPanelIcon();

    private static BufferedImage loadPanelIcon()
    {
        try
        {
            return ImageUtil.loadImageResource(PvPLeaderboardPlugin.class, "panel-icon.png");
        }
        catch (Throwable t)
        {
            log.warn("[LobbyInviteNotificationOverlay] failed to load panel-icon.png for inline popup glyph", t);
            return null;
        }
    }

    private final Client client;
    private final PvPLeaderboardConfig config;

    /** Wired post-construction by {@link com.pvp.leaderboard.PvPLeaderboardPlugin#startUp()}
     *  to {@code FightMonitor::isInCombat}. {@code null} until that
     *  wiring lands (and forever if the plugin runs without
     *  FightMonitor in some test harness) — the render path treats
     *  null as "not in combat" so a missing wiring never
     *  inadvertently suppresses every popup. */
    private volatile BooleanSupplier inCombatProvider;

    /** {@code invite_id} of the most-recently-shown invite. Sticky for
     *  the lifetime of the singleton (i.e. the lifetime of the
     *  plugin instance) so a re-push of the same invite — server-side
     *  replay on reconnect, a panel-side double-fire from racy
     *  bus events, etc. — does NOT pop the same popup twice. Cleared
     *  by {@link #clear()} which is only called on plugin shutdown,
     *  so the dedupe span is "until plugin off". */
    private volatile String lastShownInviteId;

    private volatile String activeSenderName;
    /** Tint for {@link #activeSenderName} in the body line. By spec
     *  the panel passes the same rank-tier colour the lobby row uses
     *  for this player, or {@link Color#WHITE} when the shard hasn't
     *  resolved their rank yet (the "Waiting" state). Falls back to
     *  white if the panel passes null (defensive, never happens in
     *  production). */
    private volatile Color activeSenderColor;
    private volatile long activeStartMs;

    /** Diagnostic state for the in-combat suppression DEBUG log. We
     *  emit at most THREE log lines per popup lifecycle:
     *  <ol>
     *    <li>{@code showInvite} call (always, when not deduped) —
     *        captures the in-combat / config / wiring state at the
     *        moment the invite enters the overlay.</li>
     *    <li>First decision in {@code renderInner} for THIS lifecycle
     *        — either "first paint" or "first defer".</li>
     *    <li>Transition from defer → paint (combat ended, popup
     *        finally surfaces).</li>
     *  </ol>
     *  Keyed off {@link #activeStartMs} (which is the per-lifecycle
     *  identifier — pinned forward each suppressed frame, so we
     *  compare against the FIRST stamp captured at {@code showInvite}
     *  time and stored in {@link #lifecycleId}). Reset each fresh
     *  {@code showInvite} call. Emitting at DEBUG (not INFO) so the
     *  default log level stays quiet — user must run with the
     *  {@code logback-debug.xml} profile to surface them. */
    private volatile long lifecycleId;
    /** True once we've emitted the first-decision (paint OR defer)
     *  log for {@link #lifecycleId}. Defends against 60 FPS log
     *  spam. */
    private volatile boolean firstDecisionLogged;
    /** True once we've started deferring this lifecycle. Used to
     *  detect the defer→paint transition for the third log line.
     *  Independent of {@link #firstDecisionLogged} so a popup that
     *  defers→paints emits both "first decision: defer" and
     *  "transitioned to paint" lines (full timeline visibility). */
    private volatile boolean wasDeferred;

    @Inject
    public LobbyInviteNotificationOverlay(Client client, PvPLeaderboardConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    /** Plugin wires {@code FightMonitor::isInCombat} here on
     *  startup. Pass {@code null} (or never call) to disable the
     *  combat-suppression branch entirely; pass a const-true / const-
     *  false supplier in tests. */
    public void setInCombatProvider(BooleanSupplier provider)
    {
        this.inCombatProvider = provider;
    }

    /**
     * Triggers a fresh popup for an incoming fight invite from
     * {@code senderName}. The body renders the fixed instruction
     * "Click the [icon] on the right to accept a fight from
     * {senderName}", with {@code senderName} tinted in
     * {@code senderColor}. Safe to call from any thread.
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
    public void showInvite(String inviteId, String senderName, Color senderColor)
    {
        if (senderName == null || senderName.isEmpty()) return;
        // Dedupe by invite id — once per id, ever (within this
        // plugin session). A null inviteId is treated as "no
        // dedupe info available" and always shows; in practice the
        // panel always passes a real id.
        if (inviteId != null && inviteId.equals(this.lastShownInviteId))
        {
            log.debug("[LobbyInvitePopup] showInvite SKIPPED (dedupe) inviteId={} sender={}",
                inviteId, senderName);
            return;
        }
        this.lastShownInviteId = inviteId;
        boolean enabled = config.enableLobbyInviteNotification();
        boolean suppressInCombat = config.suppressNotificationsInCombat();
        BooleanSupplier provider = this.inCombatProvider;
        boolean providerWired = provider != null;
        boolean inCombatNow = isInCombatSafe();
        log.debug("[LobbyInvitePopup] showInvite ACCEPTED inviteId={} sender={} popupEnabled={}"
                + " suppressInCombat={} inCombatProviderWired={} inCombatNow={}",
            inviteId, senderName, enabled, suppressInCombat, providerWired, inCombatNow);
        if (!enabled)
        {
            // Stamped as seen so a later toggle-on doesn't replay it,
            // but no on-screen state mutated.
            return;
        }
        this.activeSenderName = senderName;
        this.activeSenderColor = (senderColor != null) ? senderColor : Color.WHITE;
        long stamp = System.currentTimeMillis();
        this.activeStartMs = stamp;
        this.lifecycleId = stamp;
        this.firstDecisionLogged = false;
        this.wasDeferred = false;
    }

    /** Clears any pending popup AND the dedupe state. Called on
     *  plugin shutdown so the next plugin start can pop fresh
     *  invites without remembering ids from a prior session.
     *  Idempotent. */
    public void clear()
    {
        this.activeSenderName = null;
        this.activeSenderColor = null;
        this.activeStartMs = 0L;
        this.lastShownInviteId = null;
        this.lifecycleId = 0L;
        this.firstDecisionLogged = false;
        this.wasDeferred = false;
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

    /** Null-safe wrapper around {@link #inCombatProvider}. Returns
     *  {@code false} (out-of-combat) when the provider hasn't been
     *  wired yet — defends against a startup-order race where the
     *  overlay registers with OverlayManager before
     *  {@code PvPLeaderboardPlugin.startUp} sets the provider. */
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
            // The render-path catch-all above would catch this too,
            // but failing a popup gate due to FightMonitor weirdness
            // shouldn't take out the whole render — log + treat as
            // out-of-combat (safer side: at worst the user sees a
            // popup during combat, never the reverse).
            log.warn("[LobbyInviteNotificationOverlay] inCombatProvider threw - treating as out-of-combat", t);
            return false;
        }
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // Catch-all guard: any throwable from the inner body is logged
        // once with the overlay class name + a real stack (the first
        // allocation happens here, before HotSpot's
        // OmitStackTraceInFastThrow strips the trace from repeat
        // throws) and swallowed so OverlayRenderer doesn't WARN-spam
        // every frame at 60 FPS. See {@code RankOverlayRenderSafetyTest}
        // and the companion test in this class for the contract.
        try
        {
            return renderInner(g);
        }
        catch (Throwable t)
        {
            log.warn("[LobbyInviteNotificationOverlay] render threw - swallowing", t);
            return null;
        }
    }

    private Dimension renderInner(Graphics2D g)
    {
        if (!config.enableLobbyInviteNotification())
        {
            // Disabled mid-popup: drop active state so re-enabling
            // doesn't resume a partially-faded render.
            if (activeStartMs != 0L)
            {
                this.activeSenderName = null;
                this.activeSenderColor = null;
                this.activeStartMs = 0L;
            }
            return null;
        }
        // In-combat suppression — DEFER the popup instead of
        // dropping it (user-spec refinement 2026-05-25: "still show
        // the notification after the combat has ended"). Active
        // state (sender/subtext/colour) is preserved so the next
        // out-of-combat frame paints normally; activeStartMs is
        // pinned forward to "now" each suppressed frame so the
        // visible-window timer effectively freezes during combat —
        // otherwise an invite arriving at t=0 of a long combat
        // would burn its full N-second TTL before combat ends and
        // never render. {@code lastShownInviteId} stays intact so
        // a server replay of the same invite still dedupes.
        if (config.suppressNotificationsInCombat() && isInCombatSafe())
        {
            if (activeStartMs != 0L)
            {
                if (!firstDecisionLogged)
                {
                    log.debug("[LobbyInvitePopup] DEFER (in combat) lifecycleId={} sender={}",
                        lifecycleId, activeSenderName);
                    this.firstDecisionLogged = true;
                }
                this.wasDeferred = true;
                this.activeStartMs = System.currentTimeMillis();
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
            this.activeSenderColor = null;
            this.activeStartMs = 0L;
            return null;
        }
        // First-paint diagnostic: emits exactly once per lifecycle when
        // the popup actually paints (TTL not expired, sender stamped).
        // If it follows a "DEFER" line it's the defer→paint transition
        // (combat ended); if it's the first line for this lifecycleId
        // it's an immediate paint (no suppression engaged). This pair
        // of cases answers the critical user-facing question: "did
        // suppression engage at all for this invite, and if so when
        // did it release?"
        if (!firstDecisionLogged || wasDeferred)
        {
            log.debug("[LobbyInvitePopup] PAINT lifecycleId={} sender={} afterDefer={}",
                lifecycleId, sender, wasDeferred);
            this.firstDecisionLogged = true;
            this.wasDeferred = false;
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

    /** Sentinel token marking where the inline sidebar icon is drawn
     *  inside the wrapped instruction sentence. Identity-compared in
     *  the layout loop (never rendered as text). */
    private static final Object ICON_TOKEN = new Object();

    /** Wrapper marking the rank-tier-coloured sender-name token so the
     *  layout loop can tint it differently from the plain body words. */
    private static final class NameToken
    {
        final String text;
        NameToken(String text) { this.text = text; }
    }

    /** Paints the title (orange, centred in the title bar) and the
     *  body instruction: "Click the [icon] on the right to accept a
     *  fight from {sender}". The sentence is word-wrapped to the body
     *  width with the sidebar icon drawn inline and the sender name
     *  tinted in its rank-tier colour. Text is rendered with AA on
     *  (smoother glyphs) while the frame paint above forced AA off for
     *  crisp 1-px strokes. */
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

        // Body — the single instruction sentence, word-wrapped.
        Font bodyFont = FontManager.getRunescapeBoldFont().deriveFont(BODY_FONT_PT);
        g.setFont(bodyFont);
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight();
        int spaceW = fm.stringWidth(" ");
        // Inline glyph sized to the text cap-height so it sits in the
        // run of words rather than towering over them.
        int iconSize = fm.getAscent();
        int maxW = POPUP_W - 2 * BODY_SIDE_PAD;

        // "Click the [icon] on the right to accept a fight from {name}"
        List<Object> tokens = new ArrayList<>();
        for (String w : new String[]{"Click", "the"}) tokens.add(w);
        tokens.add(ICON_TOKEN);
        for (String w : new String[]{"on", "the", "right", "to", "accept", "a", "fight", "from"}) tokens.add(w);
        tokens.add(new NameToken(sender));

        // Greedy word-wrap into lines that fit within maxW.
        List<List<Object>> lines = new ArrayList<>();
        List<Object> cur = new ArrayList<>();
        int curW = 0;
        for (Object t : tokens)
        {
            int tw = tokenWidth(t, fm, iconSize);
            int add = cur.isEmpty() ? tw : spaceW + tw;
            if (!cur.isEmpty() && curW + add > maxW)
            {
                lines.add(cur);
                cur = new ArrayList<>();
                curW = 0;
                add = tw;
            }
            cur.add(t);
            curW += add;
        }
        if (!cur.isEmpty()) lines.add(cur);

        // Vertically centre the wrapped block in the body region.
        int bodyTop = y + TITLE_BAR_H + 2;
        int bodyBottom = y + POPUP_H;
        int blockH = lines.size() * lineH;
        int blockTop = bodyTop + Math.max(0, ((bodyBottom - bodyTop) - blockH) / 2);

        // Sender colour set at showInvite from the panel's RankUtils
        // resolution; guard defensively against a clear/render race.
        Color senderColor = activeSenderColor != null ? activeSenderColor : BODY_FG;

        for (int i = 0; i < lines.size(); i++)
        {
            List<Object> line = lines.get(i);
            int lineW = lineWidth(line, fm, iconSize, spaceW);
            int cx = x + (POPUP_W - lineW) / 2;
            int baseline = blockTop + i * lineH + fm.getAscent();
            for (Object t : line)
            {
                int tw = tokenWidth(t, fm, iconSize);
                if (t == ICON_TOKEN)
                {
                    if (PANEL_ICON != null)
                    {
                        // Vertically centre the glyph on the text run.
                        int iconY = baseline - fm.getAscent()
                            + ((fm.getAscent() + fm.getDescent()) - iconSize) / 2;
                        g.drawImage(PANEL_ICON, cx, iconY, iconSize, iconSize, null);
                    }
                }
                else if (t instanceof NameToken)
                {
                    g.setColor(senderColor);
                    g.drawString(((NameToken) t).text, cx, baseline);
                }
                else
                {
                    g.setColor(BODY_FG);
                    g.drawString((String) t, cx, baseline);
                }
                cx += tw + spaceW;
            }
        }
    }

    /** Pixel width of a single layout token under {@code fm}. The icon
     *  token is a fixed {@code iconSize} square; name + word tokens
     *  measure their glyph run. */
    private static int tokenWidth(Object t, FontMetrics fm, int iconSize)
    {
        if (t == ICON_TOKEN) return iconSize;
        if (t instanceof NameToken) return fm.stringWidth(((NameToken) t).text);
        return fm.stringWidth((String) t);
    }

    /** Total pixel width of one wrapped line: the sum of its token
     *  widths plus a single space between adjacent tokens. */
    private static int lineWidth(List<Object> line, FontMetrics fm, int iconSize, int spaceW)
    {
        int w = 0;
        for (int i = 0; i < line.size(); i++)
        {
            if (i > 0) w += spaceW;
            w += tokenWidth(line.get(i), fm, iconSize);
        }
        return w;
    }
}
