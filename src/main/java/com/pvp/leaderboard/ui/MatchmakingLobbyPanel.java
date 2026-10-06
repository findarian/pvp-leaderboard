package com.pvp.leaderboard.ui;

import lombok.*;
import com.google.gson.*;
import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.queue.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.ui.PlayerCard.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.*;
import lombok.extern.slf4j.*;
import javax.swing.Timer;
import static com.pvp.leaderboard.ui.Ui.*;
import static javax.swing.BorderFactory.*;
import static java.awt.BorderLayout.*;
import static javax.swing.BoxLayout.*;

/**
 * Matchmaking Lobby UI — pure presentational panel that reads through a
 * {@link LobbyService} seam. The production implementation is
 * {@code WebSocketLobbyService} (wire adapter).
 *
 * <p>The panel owns <b>presentation</b> only — every roster member,
 * incoming invite, fight-session transition, and match-found event
 * comes from the service via the {@link LobbyEventListener} callbacks
 * defined on this class. The panel never seeds its own data or runs
 * its own timers.
 *
 * <p>Outgoing-invite tracking is the one piece of state the panel still
 * keeps locally — {@link #outgoingInvitesByOpponent} mirrors what the
 * user has sent so the per-row {@code [Invited M:SS]} chip + countdown
 * can render without round-tripping the service for every paint. Keyed
 * by the opponent's canonical {@code player_id} (the lowercased display
 * name). The display-cased {@code name} is also pushed alongside
 * {@code player_id} on every roster row + invite-received push so the
 * panel can render the proper-cased label without losing the canonical
 * lookup key.
 */
@Slf4j
public class MatchmakingLobbyPanel extends JPanel implements LobbyEventListener
{
    /** Human-readable rank labels derived once from {@link RankUtils#THRESHOLDS}. */
    private static final String[] RANK_LABELS = PlayerCard.RANK_LABELS;

    /** Root card keys: the gate — the <b>queue view</b> every player
     *  rests on: region / style / build picks and the
     *  {@link QueueGateSection} with the one <b>Queue for Matchmaking</b>
     *  button —, the roster (built, never attached to the card host),
     *  and the full-screen fight-setup view (Pick Style → optional
     *  sub-location → Meet At) that takes over the panel between [Fight] /
     *  a queue match and "Back to queue". The card-key strings double
     *  as {@link Component#getName()} on each card's root panel so tests
     *  (and any future "which card am I on?" diagnostic) can find the
     *  currently-visible card by name. Every switch goes through
     *  {@link #showCard(String)}. */
    public static final String CARD_GATE = "gate";
    public static final String CARD_FIGHT = "fight";
    /** Plan 10 F.1: the matchmaking queue's "Searching…" card
     *  ({@link QueueSearchingPanel}), shown while {@code queue/state} says
     *  searching and never over a fight being set up. */
    public static final String CARD_QUEUE = "queue";

    /** Stable {@link Component#getName()} on {@code rootCardHost} so
     *  tests can locate it without walking layout indices. */
    public static final String ROOT_NAME = "matchmaking-root-cards";

    /** Shared font size for every header in the pre-lobby gate
     *  ("Set up matchmaking", "Your region", "Pick your styles") and for the
     *  region dropdown's text — so the whole gate reads as one block.
     *
     *  <p>Was 18pt; matched down to 16pt to align with
     *  {@code Dashboard.NAV_FONT_PT} (which dropped from 18→16 to keep
     *  "Player Lookup" from clipping). At 18pt + bold the wider gate
     *  headers ("Pick your styles") were also clipping on the right edge
     *  of the 215px sidepanel. */
    private static final float GATE_PT = 16f;

    /** Region short codes used both as the dropdown values and as
     *  {@link LobbyMember#region}. Keep order stable — gate / lobby dropdowns
     *  share the same indexing. */
    private static final String[] REGION_CODES =
        {"NA-E", "NA-W", "EU", "BR", "OCE", "Other"};

    /** Human-readable labels paired with {@link #REGION_CODES} (same order). */
    private static final String[] REGION_LABELS =
        {"NA East", "NA West", "EU", "Brazil", "OCE", "Other"};

    /** Default region used when the user hasn't explicitly picked one yet. */
    private static final String DEFAULT_REGION = "NA-E";
    /** The user's own region — picked once at the gate. Surfaced as the
     *  region chip on incoming-invite cards' Meet At view (so the receiver
     *  knows where the sender's coming from); never used to filter the
     *  roster. Players see everyone in the lobby regardless of region. */
    private String selfRegion = DEFAULT_REGION;

    private CardLayout rootCards;
    /** Container that owns {@link #rootCards}. Held as a field because
     *  the panel's top-level is now {@link BorderLayout} (with the error
     *  banner in NORTH); {@code rootCards.show(...)} calls must pass
     *  this host, not {@code this}. */
    private JPanel rootCardHost;

    /** Held as a field so {@link #resetGateOptions()} can put the region
     *  back to the default. */
    private JComboBox<String> regionCombo;

    // -------------------- Plan 10 F.1: matchmaking queue --------------------
    /** Queue transport. Inert until {@link #setQueue} wires the real
     *  one (the plugin does, config-gated); the gate hides the queue block
     *  while it is inert. */
    private QueueService queueService = new NoOpQueue();
    /** The gate's queue block (wait picker, queue button); visibility
     *  follows {@link QueueService#isAvailable()}. */
    private QueueGateSection queueSection;
    /** Holds the queue section's rank-range slider above the gate title;
     *  shown with the queue block while logged in. */
    private JPanel rangeHolder;
    /** The {@link #CARD_QUEUE} card; ticked at 1 Hz by
     *  {@link #onFightTick()} while it is the visible card. */
    private QueueSearchingPanel queueCard;

    /** Container for the fight-setup card (rebuilt every time the user enters
     *  or transitions through the Pick Style → sub-loc → Meet At flow). Held
     *  as a field so {@link #showSetup} can swap its contents and
     *  {@link #rootCards} can switch to it. */
    private JPanel setupBox;

    /** Active fight session occupying the FIGHT card. One at a time — set
     *  on opponent-accept (sender side) or on receiver Accept Fight click;
     *  cleared on Back to queue, confirm-window expiry, or fight
     *  completion. Null while in the lobby. */
    private LocalFightState currentFight;

    /** 1Hz ticker that drives all live countdowns: row [Invited M:SS] chips,
     *  the FIGHT card's confirm-window label, and the two TTL expiry
     *  paths (10-min invite + confirm window). Started in the constructor;
     *  stopped by {@link #shutdown()}. */
    private Timer fightTicker;

    /** How long the Confirm Fight card ignores clicks on its buttons after it appears. */
    static final long CLICK_DELAY = 1_000L;

    /** The Confirm Fight card's two buttons for a queue match. */
    static final String CONFIRM_TEXT = "Confirm";
    static final String DECLINE_TEXT = "Decline";

    /** The exit button of every fight card except a queue match's Confirm card. */
    static final String BACK_TEXT = "Back to queue";

    /** Monotonic milliseconds read by the Confirm Fight card's click delay. */
    private LongSupplier clickClockMs = MatchmakingLobbyPanel::monotonicMs;

    /** Replaces the clock the Confirm Fight card's click delay reads. */
    void setClickClock(LongSupplier clockMs)
    {
        clickClockMs = clockMs == null ? MatchmakingLobbyPanel::monotonicMs : clockMs;
    }

    /** Monotonic milliseconds read by the Confirm Fight card's confirm window. */
    private LongSupplier confirmClock = MatchmakingLobbyPanel::monotonicMs;

    /** Replaces the clock the Confirm Fight card's confirm window reads. */
    void setConfirmWindowClock(LongSupplier clockMs)
    {
        confirmClock = clockMs == null ? MatchmakingLobbyPanel::monotonicMs : clockMs;
    }

    private static long monotonicMs()
    {
        return System.nanoTime() / 1_000_000L;
    }

    /** {@code true} once {@link #CLICK_DELAY} has passed since {@code shownAtMs}. */
    private boolean clickReady(long shownAtMs)
    {
        return clickClockMs.getAsLong() - shownAtMs >= CLICK_DELAY;
    }

    /** Panel-local UI state for the active mutual-confirm session. Wraps
     *  the immutable {@link FightSession} pushed by the service with two
     *  mutable flags the panel toggles in response to the local user
     *  clicking Confirm + the service's {@code onFightConfirmedByPeer} push.
     *
     *  <p><b>Clock-independent countdown.</b> The confirm window is
     *  driven by the session's {@link FightSession#confirmMs}
     *  measured from {@link #startMs} (a monotonic clock reading
     *  captured the instant the fight was proposed) — NOT by comparing
     *  {@link System#currentTimeMillis()} against the server's absolute
     *  {@code confirmByMs}. The monotonic clock
     *  ({@link System#nanoTime()} unless a test replaces it) is immune
     *  to the wall clock being set wrong, to NTP step corrections, to
     *  manual clock edits, and to DST jumps. This fixes the QA report
     *  where a player whose computer clock was wrong saw the
     *  Confirm-Fight card flash and return to the lobby immediately
     *  instead of waiting the full window — with the old wall-clock
     *  comparison a clock running ahead of the server (or a
     *  seconds-as-ms units bug on the wire) produced an already-elapsed
     *  deadline, so {@code onFightTick} called {@code exitSetup()}
     *  on its very first tick.
     *
     *  <p>{@link #confirmBy} is retained as the server's advisory
     *  deadline (for logging / diagnostics) but is deliberately NOT used
     *  to time the local countdown. The server stays authoritative via
     *  its {@code lobby/match_found} and {@code lobby/session_expired}
     *  pushes, which can end the window early regardless of this timer.
     *
     *  <p>Fields {@link #opponent}, {@link #style}, {@link #build},
     *  {@link #location} are aliased directly from the immutable session
     *  bundle so existing rendering code that reads e.g.
     *  {@code s.opponent.name} keeps working without churn. */
    private static final class LocalFightState
    {
        final FightSession session;
        final LobbyMember opponent;
        final Style style;
        final BuildType build;
        final String location;
        /** Server's advisory absolute confirm deadline (epoch ms).
         *  Diagnostics only — see class doc; the countdown uses the
         *  monotonic {@link #startMs} + {@link #windowMs} instead. */
        final long confirmBy;
        /** Monotonic milliseconds the window is counted on. */
        final LongSupplier clockMs;
        /** Monotonic anchor captured at construction (fight-proposed
         *  time). Immune to wall-clock changes — the basis for all
         *  elapsed-time math below. */
        final long startMs;
        /** Confirm-window length: the session's
         *  {@link FightSession#confirmMs}. Package-private + mutable
         *  ONLY so unit tests can force the window to zero to
         *  deterministically simulate "window elapsed" without sleeping
         *  (mirrors the existing mutable
         *  {@link #iConfirmed}/{@link #peerConfirmed} test seam). */
        long windowMs;
        boolean iConfirmed;
        boolean peerConfirmed;
        /** Both players confirmed: the Fight ready view is showing. */
        boolean fightReady;

        LocalFightState(FightSession session)
        {
            this(session, MatchmakingLobbyPanel::monotonicMs);
        }

        LocalFightState(FightSession session, LongSupplier clockMs)
        {
            this.session = session;
            opponent = session.opponent;
            style = session.style;
            build = session.build;
            location = session.location;
            confirmBy = session.confirmByMs;
            this.clockMs = clockMs;
            startMs = clockMs.getAsLong();
            windowMs = session.confirmMs;
        }

        /** Real milliseconds elapsed since the fight was proposed,
         *  measured monotonically. Never negative; never affected by
         *  wall-clock changes. */
        long getElapsedMs()
        {
            return Math.max(0L, clockMs.getAsLong() - startMs);
        }

        /** Milliseconds left in the confirm window, clamped at 0. */
        long remainingMs()
        {
            return Math.max(0L, windowMs - getElapsedMs());
        }

        /** True once the full confirm window has elapsed in real time. */
        boolean confirmOver()
        {
            return getElapsedMs() >= windowMs;
        }
    }

    /** Backing {@link LobbyService} — {@code WebSocketLobbyService} in
     *  production. */
    private final LobbyService service;

    /** Per-style match-count gate (anti-smurf: need
     *  {@link JoinGate#THRESHOLD} kills + deaths in a style before
     *  the user can advertise it). Drives the locked-style rendering on
     *  the gate's style toggles + the [Refresh count] / "Updated X min
     *  ago" status row. The server enforces the same rule at
     *  {@code lobby/join} (returning {@code SMURF_GUARD}); this client
     *  gate is UX-only.
     *
     *  <p>Default to {@link NoOpGate} so the panel is
     *  constructible in tests that don't need the anti-smurf UX — the
     *  no-op reports every style unlocked, so the server-side check is
     *  the safety net. */
    private final JoinGate joinGate;

    private final Runnable gateListener = this::onGateChange;

    /** Notice shown <i>in lieu of</i> the gate when the user isn't
     *  logged into OSRS. Driven by {@link JoinGate#isLoggedIn()}
     *  via {@link #applyGate()}; flips on/off whenever the
     *  gate listener fires. */
    private JLabel loginNotice;

    /** Sub-panel that holds every gate widget below the title (region
     *  picker, queue block).
     *  Hidden as one unit when
     *  {@link JoinGate#isLoggedIn()} is {@code false} so the user
     *  sees a clean "Please log into the game" notice instead of a
     *  half-greyed-out gate they can't interact with. */
    private JPanel gateContent;

    /** Eager game-state signal — returns true the moment
     *  {@code GameState.LOGGED_IN} fires, before the 10-tick
     *  name-resolve delay that gates {@link JoinGate#isLoggedIn()}.
     *  Used by {@link #applyGate()} to distinguish "truly
     *  logged out" (show "Please log into the game") from "logged in
     *  but waiting for player name + match counts to resolve" (show
     *  "Loading\u2026") so the brief startup window doesn't flash a
     *  stale logged-out prompt to a user who's already in-game.
     *
     *  <p>Optional — when null, the panel falls back to the legacy
     *  two-phase view (gate-or-please-login) so test fixtures that
     *  don't wire it still render the same as before. */
    private BooleanSupplier inGame;

    /** In-game popup hook for the "fight locked in" wire moment (the
     *  inviter-side or invitee-side equivalent of receiving an invite
     *  popup, but at the next step in the flow — invite accepted, both
     *  parties about to confirm). Wired by the plugin from
     *  {@link com.pvp.leaderboard.overlay.MatchFoundNotificationOverlay}.
     *  Null in unit tests / during the brief startup window before the
     *  plugin wires the overlay; the notification simply doesn't fire
     *  in those cases (the panel's in-card transition to the
     *  Confirm-Fight view is still authoritative). */
    @Setter private MatchAlert matchFoundNotifier;

    /** Persistence backend for gate selections (region / styles / builds /
     *  rank-slider bounds). Always non-null —
     *  ctor overloads that don't supply one fall back to
     *  {@link LobbyPrefs#inMemory()}. */
    private final LobbyPrefs prefs;

    /** Top-of-panel inline error banner. Persists across the gate / lobby /
     *  fight cards (lives above the {@link CardLayout}) so a SMURF_GUARD
     *  triggered from the gate doesn't vanish when the user navigates
     *  away. {@link #errorTimer} auto-dismisses after
     *  {@link #DISMISS_MS}. Driven by
     *  {@link #onError(String, String)} via the localized message table
     *  in {@link LobbyErrors}. */
    private JPanel errorBanner;
    private JLabel errorLabel;
    private Timer errorTimer;

    /** Auto-dismiss for the error banner. Six seconds is a tradeoff:
     *  long enough that a user with their eyes off the panel still
     *  catches the message; short enough that stale errors don't
     *  permanently clutter the UI. User can dismiss manually via the
     *  banner's [×] button anytime. */
    private static final int DISMISS_MS = 6_000;

    /** Reconnect-status banner pinned above the error banner. Shown
     *  whenever {@link LobbyService#isConnected()} returns
     *  {@code false} and {@link LobbyService#getRetryAtMs()}
     *  returns a non-zero scheduled retry. Displays the localized
     *  reconnect copy + a live countdown of seconds remaining until
     *  the next attempt. Driven by {@link #retryTicker} at
     *  1 Hz; we deliberately use polling instead of a callback from
     *  {@link com.pvp.leaderboard.service.socket.SocketMgr}
     *  because the countdown needs a 1Hz redraw anyway — a callback
     *  would only add complexity. */
    private JPanel retryBanner;
    private JLabel retryLabel;
    private Timer retryTicker;

    public MatchmakingLobbyPanel(LobbyService service, JoinGate joinGate)
    {
        this(service, joinGate, LobbyPrefs.inMemory());
    }

    public MatchmakingLobbyPanel(LobbyService service, JoinGate joinGate,
                                 LobbyPrefs prefs)
    {
        if (service == null) throw new IllegalArgumentException("LobbyService is required");
        this.service = service;
        // NoOpGate is the safe fallback — reports every style
        // unlocked so a wiring bug doesn't lock the user out (server
        // still has the final word via the v2 SMURF_GUARD check).
        this.joinGate = joinGate != null ? joinGate : new NoOpGate();
        // In-memory fallback so test call sites + the existing
        // NoOpLobby production placeholder continue to build
        // without a real ConfigManager. Real persistence kicks in once
        // PvPLeaderboardPlugin wires the Guice-provided variant.
        this.prefs = prefs != null ? prefs : LobbyPrefs.inMemory();
        // Restore previously-persisted gate state into the in-memory
        // fields BEFORE buildGate() initialises the widgets — the
        // gate's region combo, style toggles and build toggles read from
        // these fields.
        loadPrefs();
        // Outer = BorderLayout so the error banner can pin to NORTH
        // above the cards. The card-switching subpanel goes in CENTER
        // and owns the gate/lobby/fight subviews via {@link #rootCards}.
        // Was a top-level CardLayout pre-banner; that meant any pinned
        // chrome had to be duplicated into every card.
        setLayout(new BorderLayout());
        setBorder(pad(4, 4, 4, 4));

        errorBanner = buildError();
        errorBanner.setVisible(false);
        retryBanner = buildBanner();
        retryBanner.setVisible(false);
        // Stack the two banners (reconnect on top, error below) in a
        // single NORTH slot. BorderLayout allows only one component
        // per region, so the vertical box is the cheapest way to keep
        // both pinned without rebuilding the outer layout. Reconnect
        // sits above the error banner because it represents a more
        // urgent, ongoing condition — a transient validation error
        // shouldn't visually hide the persistent "we're disconnected"
        // state.
        var northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, Y_AXIS));
        northStack.setOpaque(false);
        northStack.add(retryBanner);
        northStack.add(errorBanner);
        add(northStack, NORTH);

        rootCards = new CardLayout();
        rootCardHost = new JPanel(rootCards);
        rootCardHost.setName(ROOT_NAME);
        JPanel gateCard = buildGate();
        gateCard.setName(CARD_GATE);
        rootCardHost.add(gateCard, CARD_GATE);
        setupBox = new JPanel(new BorderLayout());
        setupBox.setName(CARD_FIGHT);
        rootCardHost.add(setupBox, CARD_FIGHT);
        // Plan 10 F.1: the queue's Searching card. The two buttons route
        // straight to the transport; the card itself only renders.
        queueCard = new QueueSearchingPanel(() -> queueService.expandRange(), () -> queueService.leave());
        queueCard.setName(CARD_QUEUE);
        rootCardHost.add(queueCard, CARD_QUEUE);
        add(rootCardHost, CENTER);
        showCard(CARD_GATE);

        fightTicker = new Timer(1000, e -> onFightTick());
        fightTicker.setRepeats(true);
        fightTicker.start();

        retryTicker = new Timer(1000, e -> refreshRetry());
        retryTicker.setRepeats(true);
        retryTicker.start();

        // Register for server pushes.
        this.service.setListener(this);
        this.service.start();

        // Re-render the gate's match-count status row + style-toggle
        // lock states whenever the gate refreshes. EDT marshalling lives
        // inside the gate impl (see ProfileGate#fireOnEdt).
        this.joinGate.addListener(gateListener);
    }

    // -------------------- UI construction --------------------

    /**
     * The queue view: the rank-range slider on top, then "Set up
     * matchmaking" with the user's region, the wait time and the one queue
     * button. The queue goes out as {@link QueueGateSection#QUEUE_STYLE} +
     * {@link QueueGateSection#QUEUE_BUILD}; there is nothing else to pick.
     */
    private JPanel buildGate()
    {
        var gate = new JPanel();
        gate.setLayout(new BoxLayout(gate, Y_AXIS));
        gate.setBorder(pad(18, 8, 18, 8));

        // Built first: its rank-range slider sits above the title.
        queueSection = new QueueGateSection(prefs, GATE_PT, this::onQueueClicked);
        rangeHolder = new JPanel(new BorderLayout());
        rangeHolder.setOpaque(false);
        left(rangeHolder);
        rangeHolder.setBorder(pad(0, 0, 12, 0));
        rangeHolder.add(queueSection.rangeSlider(), CENTER);
        pin(rangeHolder);
        gate.add(rangeHolder);

        var title = new JLabel("Set up matchmaking");
        bold(title, GATE_PT);
        gate.add(title);
        gate.add(lgap(8));

        // ---- Logged-out notice (shown in lieu of the rest of the gate) ----
        // Lives next to {@link #gateContent} as a sibling; exactly one
        // of the two is visible at a time. Pre-construction default is
        // "logged out" since the lobby gate is built during the
        // dashboard ctor — well before any GameState event fires.
        // applyGate() at the bottom of this method picks the
        // right initial visibility from the gate.
        //
        // Explicit {@code <br>} rather than {@code <div style='width:'>}
        // — the latter under Substance L&F miscomputes wrap and clipped
        // the word "up" out of the rendered text. With a hand-broken
        // line we get deterministic layout matching the sidepanel's
        // ~209px usable width (225 sidepanel - 16 horizontal padding).
        loginNotice = new JLabel(
            "<html>Please log into the game<br>to set up matchmaking.</html>");
        bold(loginNotice, 15f);
        loginNotice.setForeground(new Color(0xcccccc));
        // Cap height to the rendered preferred height so BoxLayout
        // doesn't stretch the label vertically into the space freed up
        // by the hidden gateContent. Was Integer.MAX_VALUE which made
        // BoxLayout give the entire empty cell to the label — text
        // visually pinned to the bottom of the gate.
        Dimension noticePref = loginNotice.getPreferredSize();
        loginNotice.setMaximumSize(new Dimension(noticePref.width, noticePref.height));
        gate.add(loginNotice);

        // Wrap everything else in a sub-panel so we can hide/show as
        // one unit without playing whack-a-mole with individual
        // setVisible() calls every time a new gate widget is added.
        gateContent = new JPanel();
        gateContent.setLayout(new BoxLayout(gateContent, Y_AXIS));
        left(gateContent);
        gateContent.setOpaque(false);

        // ---- Region picker ----
        // Header + dropdown both share the title's 18pt BOLD treatment so the
        // gate reads as one consistent typographic block (per spec: match the
        // "Set up matchmaking" title).
        var regionTitle = new JLabel("Your region");
        bold(regionTitle, GATE_PT);
        gateContent.add(regionTitle);
        gateContent.add(lgap(4));

        regionCombo = new JComboBox<>(REGION_LABELS);
        regionCombo.setSelectedIndex(regionIndex(selfRegion));
        plain(regionCombo, GATE_PT);
        // Bumped from 32 to 40 to fit the larger 18pt font without clipping descenders.
        maxH(regionCombo, 40);
        left(regionCombo);
        regionCombo.addActionListener(e ->
        {
            int idx = regionCombo.getSelectedIndex();
            if (idx >= 0)
            {
                selfRegion = REGION_CODES[idx];
                prefs.setRegion(selfRegion);
            }
        });
        gateContent.add(regionCombo);
        gateContent.add(lgap(14));

        // ---- Matchmaking queue: wait time + the queue button ----
        // Hidden until the plugin wires a real QueueService (config-gated).
        queueSection.setVisible(queueService.isAvailable());
        gateContent.add(queueSection);

        // Mount the wrapper so all gate widgets show up under the
        // title. applyGate() then flips visibility based on
        // the current JoinGate.isLoggedIn() snapshot — the panel
        // is built during the dashboard ctor (well before any GameState
        // event fires) so the initial render is correct.
        gate.add(gateContent);
        applyGate();

        return gate;
    }

    /** The rank-range slider shows with the queue block: logged in, queue on. */
    private void refreshRange()
    {
        if (rangeHolder == null || gateContent == null) return;
        rangeHolder.setVisible(gateContent.isVisible() && queueService.isAvailable());
    }

    /** Toggles the gate between "logged in" and "logged out" views by
     *  swapping visibility of {@link #loginNotice} and
     *  {@link #gateContent}. Called from the gate-listener every time
     *  the {@link JoinGate} fires (which includes onLogin /
     *  onLogout transitions). EDT-only — the listener already
     *  marshals.
     *
     *  <p>On logout a fight view with a session keeps its card; otherwise
     *  a search is left and the root card goes back to {@link #CARD_GATE}.
     *  {@link #selectedStyles}/{@link #selectedBuildTypes}/{@link #selfRegion}
     *  are kept. A login changes no card. */
    private void applyGate()
    {
        if (gateContent == null || loginNotice == null) return;
        boolean gateReady = joinGate.isLoggedIn();
        // Eager game-state probe — true the instant
        // GameState.LOGGED_IN fires, before the 10-tick name-resolve
        // delay that gates JoinGate.onLogin(). We use this to
        // pick which logged-out copy to show: a true logged-out user
        // (game state != LOGGED_IN) sees the "Please log into the
        // game" prompt, while a freshly-logged-in user inside the
        // 10-tick startup window sees "Loading…" instead — the gate
        // listener fires again once name + counts resolve and flips
        // gateReady to true, hiding the notice entirely.
        // Defaults to true when the supplier is unwired (test
        // fixtures, gateless construction paths) so the legacy
        // two-phase notice still works for those callers.
        boolean gameLoggedIn = inGame == null
            || inGame.getAsBoolean();

        if (gateReady)
        {
            loginNotice.setVisible(false);
            gateContent.setVisible(true);
        }
        else if (gameLoggedIn)
        {
            // Logged into OSRS but plugin still resolving identity +
            // smurf-guard counts. Show a low-key "Loading…" so the
            // user knows the panel saw their login and isn't broken.
            // Same hand-broken <br> markup as the logged-out copy
            // (see field doc) to dodge Substance's HTML-wrap clip bug.
            loginNotice.setText("<html>Loading\u2026</html>");
            loginNotice.setVisible(true);
            gateContent.setVisible(false);
        }
        else
        {
            loginNotice.setText(
                "<html>Please log into the game<br>to set up matchmaking.</html>");
            loginNotice.setVisible(true);
            gateContent.setVisible(false);
        }
        refreshRange();
        // BoxLayout doesn't auto-revalidate on child visibility changes
        // — force the parent gate panel to recompute its layout so the
        // viewport collapses around whichever child is visible.
        Container parent = gateContent.getParent();
        if (parent != null)
        {
            parent.revalidate();
            parent.repaint();
        }

        if (!gateReady)
        {
            // Active fight session pinning: the user is mid-confirm
            // (CARD_FIGHT) or already in MeetAt. World hops can briefly
            // pass through LOGIN_SCREEN, and an intentional logout
            // mid-confirm shouldn't yank the dialog out from under
            // the user. Keep CARD_FIGHT visible — the local
            // confirm-window ticker still drives exitSetup() if
            // the window elapses AND the peer hasn't confirmed
            // (onFightTick), and "Back to queue" is the user's manual
            // escape hatch. MeetAt has no auto-expiry; the user clicks
            // "Back to queue" when ready.
            // Backend rules: a logged-out user's fight session is
            // expired server-side on $disconnect, so a re-login won't
            // resume a real session — but the panel staying on
            // CARD_FIGHT gives the user visual continuity so they can
            // hop to the meeting world without losing their place.
            if (currentFight != null) return;
            // Plan 10 F.1: a logged-out player cannot fight — leave the
            // queue so the server stops searching for them.
            if (isOnCard(CARD_QUEUE)) queueService.leave();
            showCard(CARD_GATE);
        }
    }

    // -------------------- Plan 10 F.1: matchmaking queue --------------------

    /** Wires the queue transport. The plugin passes
     *  {@code WebSocketQueueService} while the {@code enableQuickMatch}
     *  config is on and {@link NoOpQueue} otherwise; either way the
     *  panel becomes the listener, starts the service and shows / hides the
     *  gate's queue block. Safe to call again on a config flip — a user
     *  left on the Searching card by an inert service is returned to the
     *  gate. */
    public void setQueue(QueueService svc)
    {
        QueueService previous = queueService;
        if (previous != null && previous != svc) previous.setListener(null);
        queueService = svc == null ? new NoOpQueue() : svc;
        queueService.setListener(new QueueEvents());
        queueService.start();
        boolean available = queueService.isAvailable();
        if (queueSection != null)
        {
            queueSection.setVisible(available);
            // G-2: the wait time / rank range live in one row shared with
            // Discord — write the touched key on a local pick, read the row
            // back on wire-up. One key per frame: pushing both would let a
            // wait change overwrite a rank range set on Discord.
            queueSection.setOnWaitChanged(this::pushWait);
            queueSection.setOnRangeChanged(this::pushRange);
        }
        refreshRange();
        if (available) queueService.requestPrefs();
        if (!available) exitQueue(null);
    }

    /** Local wait pick → the shared prefs row, so the Discord modal prefills with it. */
    private void pushWait()
    {
        if (queueSection != null) queueService.sendWaitPref(queueSection.waitPrefS());
    }

    /** Local rank-range pick → the shared prefs row. The full span sends an
     *  explicit null so the row is cleared rather than left at Discord's value. */
    private void pushRange()
    {
        if (queueSection == null) return;
        boolean rangeOn = queueSection.rangeEnabled();
        queueService.sendRange(rangeOn ? queueSection.rangeMinIdx() : QueueState.UNKNOWN,
            rangeOn ? queueSection.rangeMaxIdx() : QueueState.UNKNOWN);
    }

    /** "Queue for Matchmaking": NH + Main, the user's region, the slider's
     *  bounds when it is narrower than every rank, and the wait preference. */
    private void onQueueClicked()
    {
        if (queueSection == null) return;
        boolean rangeOn = queueSection.rangeEnabled();
        int minIdx = rangeOn ? queueSection.rangeMinIdx() : QueueState.UNKNOWN;
        int maxIdx = rangeOn ? queueSection.rangeMaxIdx() : QueueState.UNKNOWN;
        queueService.join(selfRegion, QueueGateSection.QUEUE_STYLE, QueueGateSection.QUEUE_BUILD,
            minIdx, maxIdx, queueSection.waitPrefS());
    }

    /** Every rank label, lowest first — the queue's rank-range slider reads them. */
    static String[] rankLabels()
    {
        return RANK_LABELS.clone();
    }

    /** Walks {@link #rootCardHost}'s children for the visible card and asks
     *  whether its name is {@code cardName}. CardLayout updates visibility
     *  synchronously on the EDT, so this read sees the live state. */
    private boolean isOnCard(String cardName)
    {
        if (rootCardHost == null) return false;
        for (Component c : rootCardHost.getComponents())
        {
            if (c.isVisible() && cardName.equals(c.getName())) return true;
        }
        return false;
    }

    // -------------------- cards --------------------

    /** The one way to switch root cards. A request for the roster lands
     *  on the queue view, and the incoming-invite strip above the cards
     *  is re-evaluated (hidden over the fight views). */
    private void showCard(String card)
    {
        if (rootCards == null || rootCardHost == null) return;
        rootCards.show(rootCardHost, card);
    }

    /** Stops the panel's timers and invite countdowns and unregisters it
     *  from the gate and the services. Idempotent. */
    public void shutdown()
    {
        joinGate.removeListener(gateListener);
        stopTimer(fightTicker);
        stopTimer(retryTicker);
        stopTimer(errorTimer);
        service.setListener(null);
        queueService.setListener(null);
    }

    private static void stopTimer(Timer t)
    {
        if (t != null) t.stop();
    }

    /** {@code queue/state searching}: render the card and show it — unless
     *  a fight is being set up (Confirm / Meet-At), which always outranks
     *  the queue card. */
    private void showSearch(QueueState state)
    {
        if (queueCard == null) return;
        queueCard.render(state);
        if (currentFight != null) return;
        showCard(CARD_QUEUE);
    }

    /** Leaves the Searching card (idle / timeout / inert service) for the
     *  gate; an optional banner explains why (expired, opponent declined,
     *  timeout). */
    private void exitQueue(String banner)
    {
        if (isOnCard(CARD_QUEUE)) showCard(CARD_GATE);
        if (banner != null) showError(banner);
    }

    /** The queue's server pushes (EDT — the service marshals). A match
     *  needs no handling here: the lobby's own {@code lobby/fight_proposed}
     *  arrives alongside {@code queue/matched} and {@link #onFightProposed}
     *  swaps to the Confirm Fight view as for any invite. An idle state that
     *  ends a queue match ({@link QueueState#endsMatch()}) closes its card. */
    private final class QueueEvents implements QueueEventListener
    {
        @Override
        public void onQueueState(QueueState state)
        {
            if (state == null) return;
            if (state.isSearching())
            {
                showSearch(state);
                return;
            }
            if (state.endsMatch()) closeMatch(state.fightId);
            exitQueue(QueueText.forIdleReason(state.reason));
        }

        @Override
        public void onQueueTimeout(QueueState state)
        {
            exitQueue(QueueText.forTimeout(state == null ? 0 : state.waitPrefS));
        }

        @Override
        public void onQueuePrefs(JsonObject prefs)
        {
            // G-2: the shared row, possibly last written from Discord.
            if (queueSection != null) queueSection.applyPrefs(QueuePrefs.fromJson(prefs));
        }

        @Override
        public void onQueueError(String code, String message)
        {
            showError(QueueText.forError(code, message));
        }
    }

    /** EDT callback fired by {@link JoinGate#addListener}. Swaps the
     *  gate between "logged in" and "Please log into the game" views via
     *  {@link #applyGate()} — onLogin / onLogout transitions both
     *  fire through this listener. */
    private void onGateChange()
    {
        applyGate();
    }

    /** Pulls the last persisted gate selections (region / styles / builds /
     *  rank-slider bounds) into the in-memory fields.
     *  Called once from the ctor BEFORE {@link #buildGate()} so the
     *  widgets read the restored values when they construct.
     *
     *  <p>The field initialisers above remain the "first launch / no
     *  persistence" defaults; this method overrides them when the user
     *  has previously completed the gate. An invalid persisted set
     *  (zero styles or zero builds — possible if an older plugin version
     *  stored those) falls back to the field defaults so the gate stays
     *  usable. Out-of-range rank indices are clamped against
     *  {@link #RANK_LABELS}.length so a future RANK_LABELS shrink can't
     *  leave the slider pointing past the end. */
    private void loadPrefs()
    {
        selfRegion = prefs.getRegion(DEFAULT_REGION);
    }

    /** The lobby's form of a region code ({@code "na-w"} → {@code "NA-W"}); an unknown one upper-cased as sent, a blank one {@code null}. */
    static String regionCode(String code)
    {
        if (code == null || code.trim().isEmpty()) return null;
        String wanted = code.trim();
        for (String known : REGION_CODES)
        {
            if (known.equalsIgnoreCase(wanted)) return known;
        }
        return wanted.toUpperCase(Locale.ROOT);
    }

    /** Returns the index in {@link #REGION_CODES} matching {@code code}, or 0 if missing. */
    private static int regionIndex(String code)
    {
        for (int i = 0; i < REGION_CODES.length; i++)
        {
            if (REGION_CODES[i].equals(code)) return i;
        }
        return 0;
    }

    /** Wires the eager "is the local player in {@code GameState.LOGGED_IN}
     *  right now" signal — see {@link #inGame} doc for
     *  why this is separate from {@link JoinGate#isLoggedIn()}.
     *  Triggers an immediate {@link #applyGate()} so the
     *  notice flips to "Loading\u2026" the instant the supplier
     *  reports true (which it might already, if the user opened the
     *  panel post-login). */
    public void setInGame(BooleanSupplier supplier)
    {
        inGame = supplier;
        applyGate();
    }

    /** Run on the EDT after a queue match's Confirm Fight card is shown. */
    private Runnable shownHook = () -> { };

    /** Registers the callback run after a queue match's Confirm Fight card is shown. */
    void setShownHook(Runnable listener)
    {
        shownHook = listener == null ? () -> { } : listener;
    }

    private void notifyShown()
    {
        try
        {
            shownHook.run();
        }
        catch (RuntimeException e)
        {
        }
    }

    /** Strategy hook for the match-found popup. Mirror of
     *  {@link LobbyInviteNotifier} for the next step in the lobby
     *  flow. The production implementation is
     *  {@link com.pvp.leaderboard.overlay.MatchFoundNotificationOverlay}
     *  via a lambda wired from the plugin.
     *
     *  <p>{@code isInviter} carries the perspective: {@code true} when
     *  the local user originated the invite ({@link #outgoingInvitesByOpponent}
     *  contained this opponent at the moment {@code lobby/fight_proposed}
     *  arrived), {@code false} when the local user clicked Accept on
     *  an incoming card. The overlay flips its caption tail
     *  accordingly ({@code "<opp> accepted your invite"} vs just
     *  {@code "<opp>"}).
     *
     *  <p>{@code opponentNameColor} follows the same rank-tint rule
     *  as {@link LobbyInviteNotifier#showInvite}'s
     *  {@code senderNameColor} so the popup feels of-a-piece with the
     *  rest of the matchmaking UI.
     */
    @FunctionalInterface
    public interface MatchAlert
    {
        void showMatch(String fightId,
                       String opponentName,
                       String subtext,
                       Color opponentNameColor);
    }

    /** Public re-entry point for {@link #applyGate()} so the
     *  hosting plugin / dashboard can re-render the gate notice the
     *  instant a {@code GameStateChanged} event fires — the gate
     *  listener wired in this panel only fires after
     *  {@link JoinGate#onLogin()}, which is delayed 10 ticks for
     *  player-name resolve, so without an external poke from
     *  {@code GameState.LOGGED_IN} the "Loading\u2026" copy would
     *  never paint (the listener-driven re-render would already see
     *  {@code gateReady=true} and skip past it). EDT-only. Safe to
     *  call before {@link #setInGame} has wired the
     *  supplier. */
    public void refreshGate()
    {
        applyGate();
    }

    /** User-facing display name for a roster row / invite card.
     *  Prefers the display-cased {@link LobbyMember#name}, falls back
     *  to the canonical {@link LobbyMember#playerId} (lowercased
     *  form) if the display name is empty, and finally {@code
     *  "Unknown"} only if both are empty. */
    private static String nameOf(LobbyMember m)
    {
        return PlayerCard.nameOf(m);
    };;

    /** Common card-swap path. {@link #wrapInScroll(JPanel)} always returns
     *  a fresh JScrollPane so the default scrollbar value is 0, but we
     *  also snap the viewport explicitly after the layout pass — defensive
     *  cover against any PLAF that initialises the viewport to a non-zero
     *  position based on the previous card's geometry. */
    private void showSetup(JComponent view)
    {
        setupBox.removeAll();
        setupBox.add(view, CENTER);
        setupBox.revalidate();
        setupBox.repaint();
        showCard(CARD_FIGHT);
        if (view instanceof JScrollPane)
        {
            final var sp = (JScrollPane) view;
            SwingUtilities.invokeLater(() ->
            {
                JViewport vp = sp.getViewport();
                if (vp != null) vp.setViewPosition(new Point(0, 0));
            });
        }
    }

    /** Cleans up any in-flight FIGHT session + outgoing invite for the same
     *  opponent (Back to queue exit, window expiry, both-confirmed exit, etc.)
     *  and returns the user to the queue view. The server's session TTL keeps
     *  running server-side; the panel just drops its local state and
     *  ignores the late {@link #onFightConfirmedByPeer onFightConfirmedByPeer}
     *  / {@link #onMatchFound onMatchFound} push since
     *  {@code currentFight} is already null. */
    private void exitSetup()
    {
        currentFight = null;
        showCard(CARD_GATE);
    }

    /** User clicked Confirm Fight in the ConfirmFight view. Marks local
     *  state {@code iConfirmed=true}, fires the service confirm, and
     *  optimistically renders the Waiting view. If the peer had already
     *  confirmed, {@link #onMatchFound} fires shortly after, at which
     *  point the listener swaps the view to MeetAt — the
     *  {@code if (currentFight != null)} guard below
     *  avoids double-rendering the Waiting view in that case. */
    /** Confirm-Fight click handler.
     *
     *  <p>Takes an optional {@link JButton} so the in-card button can be
     *  instantly disabled + relabeled as visible evidence the click
     *  registered. Two-mode UX:
     *  <ul>
     *    <li>Normal path: button flips to "Confirming\u2026", disabled;
     *        the view immediately swaps to the Waiting card so the
     *        relabel only flashes for a frame. The relabel still
     *        matters because if the swap is delayed (Swing repaint
     *        scheduling under load), the user sees the click took.</li>
     *    <li>Stale-session path ({@code currentFight == null}):
     *        the button flips to "(expired)", disabled, and the view
     *        DOES NOT swap — the user is left on the Confirm Fight
     *        card with the dead button so they can read the label and
     *        click Back to queue. Pre-debounce, this code path was
     *        a silent no-op (matches the "I clicked Confirm and
     *        nothing happened" QA report when a race clears the
     *        session between view-build and click).</li>
     *  </ul>
     *
     *  <p>Also emits a {@code DEBUG} log on every click so the receiver
     *  side's debug log proves whether their click ever fired —
     *  critical for diagnosing "did they click or not" in
     *  asymmetric-visibility reports where only one side's log is
     *  available. */
    private void onConfirm(JButton confirmBtn)
    {
        LocalFightState s = currentFight;
        if (s == null)
        {
            log.warn("MatchmakingLobbyPanel: Confirm Fight clicked but currentFightSession=null -"
                + " session was cleared between view-build and click (most likely cause: late"
                + " match_found / session_expired). Click is a no-op; user must click Back to queue.");
            if (confirmBtn != null)
            {
                confirmBtn.setEnabled(false);
                confirmBtn.setText("(expired)");
            }
            return;
        }
        if (confirmBtn != null)
        {
            confirmBtn.setEnabled(false);
            confirmBtn.setText("Confirming\u2026");
        }
        s.iConfirmed = true;
        service.confirmFight();
        // Always swap to the Waiting view after the local confirm fires.
        // Pre-fix, this was guarded by !bothConfirmed() under the
        // assumption that match_found would land before the user notices
        // — but a peer who confirmed FIRST already set peerConfirmed=true,
        // so the second-to-confirm hits bothConfirmed=true at click time
        // and the guard skipped the swap. Result: stuck-on-Confirm-Fight
        // with a disabled "Confirming\u2026" button until the local timer
        // (now unconditional) kicks them out when the window ends. Swapping every
        // time keeps the visual transition consistent for both confirm
        // orderings; if match_found lands milliseconds later,
        // {@link #onMatchFound} immediately swaps Waiting \u2192 MeetAt.
        if (currentFight != null)
        {
            showSetup(buildWaiting());
        }
    }

    /** Live label on the FIGHT card that the ticker rewrites every second. */
    private JLabel clockLabel;

    /** Confirm Fight view — shown to the INVITER and for a queue match while
     *  they decide to lock in (the acceptor auto-confirms in
     *  {@link #onFightProposed} and skips straight to the Waiting view).
     *  Shows a big [Get Match Location] button
     *  and the live countdown; clicking it confirms the fight and, once
     *  both sides are in, the server reveals the world + meeting place — hence
     *  the label describes that outcome rather than the mechanical "Confirm".
     *  If the opponent has already confirmed, an extra subheader makes that
     *  visible. Both buttons ignore clicks for the card's first
     *  {@link #CLICK_DELAY}. For a queue match the buttons read
     *  {@link #CONFIRM_TEXT} and {@link #DECLINE_TEXT}. */
    private JComponent buildConfirm()
    {
        LocalFightState s = currentFight;
        final long shownAtMs = clickClockMs.getAsLong();
        JPanel card = newGateCard();
        card.add(makeHeader("Confirm fight"));
        card.add(lgap(8));
        addOpponentLines(card, s);
        if (s != null && s.peerConfirmed)
        {
            card.add(lgap(6));
            card.add(makeSubhead("Confirmed by other player"));
        }
        card.add(lgap(14));

        JButton confirm = makeAction(CONFIRM_TEXT, true);
        // Primary CTA — green (the colour the exit button used to be) so
        // it reads as the prominent positive action; the secondary
        // "Back to queue" exit below now uses the neutral default
        // button colour (2026-05-29 request). Label is "Get Match Location"
        // (2026-05-30 request) since confirming reveals the match world +
        // meeting place.
        confirm.setBackground(ACCENT);
        confirm.setForeground(Color.WHITE);
        confirm.setOpaque(true);
        confirm.setBorderPainted(false);
        confirm.addActionListener(e ->
        {
            if (clickReady(shownAtMs)) onConfirm(confirm);
        });
        card.add(confirm);
        card.add(lgap(8));

        clockLabel = makeSubhead(formatLeft(s));
        card.add(clockLabel);
        card.add(lgap(12));

        card.add(makeCancel(DECLINE_TEXT, () ->
        {
            if (clickReady(shownAtMs)) exitSetup();
        }));
        return wrapInScroll(card);
    }

    /** Waiting view — user has confirmed; shown until the opponent does
     *  too (header "Waiting on other player to confirm") or both have
     *  confirmed (header "Finalizing match details\u2026") or the confirm
     *  window expires. The two headers describe the same wait but
     *  attribute it correctly: in the first case we're waiting on the
     *  peer's wire confirm, in the second we're waiting on the server's
     *  {@code lobby/match_found} push. Without the second header the
     *  second-to-confirm player saw "Waiting on other player to
     *  confirm" even though the peer had already confirmed (the same
     *  scenario the QA test surfaced when match_found never landed). */
    private JComponent buildWaiting()
    {
        LocalFightState s = currentFight;
        JPanel card = newGateCard();
        String header = (s != null && s.peerConfirmed)
            ? "Finalizing match details\u2026"
            : "Waiting on other player to confirm";
        card.add(makeHeader(header));
        card.add(lgap(8));
        addOpponentLines(card, s);
        card.add(lgap(14));

        clockLabel = makeSubhead(formatLeft(s));
        card.add(clockLabel);
        card.add(lgap(12));

        card.add(makeCancel(BACK_TEXT, this::exitSetup));
        return wrapInScroll(card);
    }

    /** Terminal MeetAt view — shown after both confirmed. The server
     *  resolves the world + meeting place authoritatively via
     *  {@code resolve_match_world} / {@code resolve_meeting_place} in
     *  {@code backend/core/lobby.py}: NH Arena → W370 (AUS) / W558 (EU)
     *  / W578 (NA) with random-pick-of-the-two on mixed regions
     *  defaulting to W578; NH Wildy/FFA + Multi → world + Ferox Enclave;
     *  Veng → random PvP world from the member list + Grand Exchange;
     *  DMM → W345 + Grand Exchange. The plugin renders the values
     *  verbatim from {@link MatchInfo#world} / {@link MatchInfo#meetingPlace}
     *  per the handoff "render verbatim, no client-side world picking"
     *  rule — keeping the resolution logic server-side means the
     *  world tables can be updated (e.g. when Jagex retires a PvP
     *  world) without a plugin release.
     *
     *  <p>{@code match} is null only when the view is being re-rendered
     *  outside an {@link #onMatchFound} flow (currently unreachable —
     *  the view's only entry point is from {@link #onMatchFound}).
     *  Falls back to "TBD" defensively so a future refactor that
     *  invokes the view from another path doesn't NPE. */
    private JComponent buildMeetAt(MatchInfo match)
    {
        LocalFightState s = currentFight;
        JPanel card = newGateCard();
        card.add(makeHeader("Fight ready"));
        card.add(lgap(8));
        addOpponentLines(card, s);
        card.add(lgap(14));

        String worldText = match != null && match.world != null && !match.world.isEmpty()
            ? match.world : "TBD";
        String meetText = match != null && match.meetingPlace != null && !match.meetingPlace.isEmpty()
            ? match.meetingPlace
            : (s == null ? "TBD" : meetAtPlace(s.style, s.location));

        card.add(makeMeetRow("World:", worldText));
        card.add(lgap(6));
        card.add(makeMeetRow("Meet at:", meetText));
        card.add(lgap(14));

        // Per spec: this screen also has a Back to queue exit; auto-return
        // on real fight submission is a future hook tied to the in-game match
        // submission pipeline ().
        card.add(makeCancel(BACK_TEXT, this::exitSetup));
        return wrapInScroll(card);
    }

    /** Appends the opponent block used by Confirm/Waiting/MeetAt: the player
     *  name as a full-size header plus a same-sized but de-emphasized
     *  subheader carrying "Style - Build @ Place" for context (matches the
     *  incoming invite card's info-row format). Per spec, every text line
     *  on these screens must read at button size. */
    private static void addOpponentLines(JPanel card, LocalFightState s)
    {
        if (s == null) return;
        card.add(makeHeader(s.opponent.name));
        card.add(lgap(2));
        card.add(makeSubhead(formatSetup(s.style, s.build, s.location)));
    }

    /** "Style - Build @ Place" shared formatter. Used by the FightSession
     *  views (Confirm / Waiting / MeetAt opponent line) and the incoming
     *  invite card's info row, so both surfaces read identically. Falls
     *  back through {@link #inviteLabel(Style, String)} for
     *  Veng / DMM (no sub-loc picker, so the location reads as a generic
     *  "PvP World" / "DMM World" instead of being blank). */
    private static String formatSetup(Style style, BuildType build, String location)
    {
        var sb = new StringBuilder(style.label);
        if (build != null) sb.append(" - ").append(build.label);
        String loc = inviteLabel(style, location);
        if (!loc.isEmpty()) sb.append(" @ ").append(loc);
        return sb.toString();
    }

    /** Display name for the @-place portion of an invite info row.
     *  Returns the sub-location verbatim when one was picked (NH / Multi),
     *  otherwise the per-style default — "PvP World" for Veng, "DMM World"
     *  for DMM. Empty string for unknown style+null-location combos so the
     *  caller can omit the "@ ..." segment. */
    private static String inviteLabel(Style style, String location)
    {
        if (location != null && !location.isEmpty()) return location;
        if (style == Style.VENG) return "PvP World";
        if (style == Style.DMM) return "DMM World";
        return "";
    }

    /** The Confirm Fight card's countdown: {@code "M:SS remaining"}, whole seconds rounded up. */
    private static String formatLeft(LocalFightState s)
    {
        if (s == null) return "";
        // Monotonic remaining time — independent of the wall clock so the
        // displayed countdown always counts the full window down to zero.
        // See LocalFightState doc.
        long secs = (s.remainingMs() + 999L) / 1000L;
        return mmss((int) secs) + " remaining";
    }

    /** 1Hz tick: expires INVITED outgoing invites whose 10-min TTL has run out
     *  (refreshes affected rows), updates the FIGHT card's countdown
     *  label, and force-exits the FIGHT card when the confirm window
     *  elapses <em>and the peer has not confirmed</em>.
     *
     *  <p><b>Expiry policy (2026-05-29 user request).</b> On window
     *  elapse the panel returns to the lobby ONLY when
     *  {@code !peerConfirmed}. Once the other player has confirmed the
     *  screen stays put — the user explicitly does not want to be bounced
     *  back to the lobby while a match is being finalized. In that state
     *  the panel waits for the server's authoritative
     *  {@code lobby/match_found} (\u2192 MeetAt) or
     *  {@code lobby/session_expired} (\u2192 lobby) push, or the user's
     *  manual "Back to queue" click. When {@code match_found} lands in
     *  time, {@link #handleMatchFound} clears {@code currentFight}
     *  so this branch is moot — the outer {@code if (s != null)} guard
     *  skips.
     *
     *  <p>Trade-off: if the backend ever loses BOTH confirms (the old
     *  {@code confirmed_by} overwrite bug) and never pushes
     *  match_found/session_expired, a both-confirmed user is no longer
     *  auto-evicted when the window ends — the manual "Back to queue" button is
     *  the intended escape hatch in that (server-bug) scenario. */
    private void onFightTick()
    {
        LocalFightState s = currentFight;
        if (s != null)
        {
            // Monotonic, clock-independent window — see LocalFightState
            // doc. The window only elapses after windowMs of
            // REAL time, so a wrong / skewed wall clock (or a
            // seconds-as-ms deadline on the wire) can no longer pop the
            // card on the first tick. If match_found landed in time,
            // handleMatchFound already nulled currentFight and we
            // never reach here.
            if (s.confirmOver())
            {
                // A queue match the player never confirmed also returns to the queue view.
                if (!s.peerConfirmed || !s.iConfirmed)
                {
                    // Peer never confirmed within the window — return to
                    // the lobby so a one-sided confirm doesn't strand the
                    // user. serverDeadlineMs is logged next to the
                    // monotonic elapsedMs so client clock skew is obvious
                    // in a bug report (e.g. a serverDeadlineMs already in
                    // the past when elapsedMs reaches windowMs).
                    exitSetup();
                }
                // else: the peer HAS confirmed — per the 2026-05-29 user
                // request the screen must stay put (a match is being
                // finalized; don't bounce the user back to the lobby).
                // The server stays authoritative: lobby/match_found swaps
                // to MeetAt and lobby/session_expired returns to the
                // lobby. The user's manual "Back to queue" button is
                // always available as the escape hatch. We stop updating
                // the countdown label here so it freezes instead of
                // showing a stale "0:00 remaining".
            }
            else if (clockLabel != null)
            {
                clockLabel.setText(formatLeft(s));
            }
        }

        // Plan 10 F.1: the Searching card's elapsed clock between server pushes.
        if (queueCard != null && isOnCard(CARD_QUEUE)) queueCard.tick();
    }

    /** Resolves the sender's name colour for the in-game popup using
     *  the same rule the lobby row applies (see {@code addLobbyRow}'s
     *  {@code rankColor} branch): if {@code peakRankIdx} is a known
     *  index, tint by {@link RankUtils#getRankColor(String)} keyed on
     *  the rank label; otherwise default to {@link Color#WHITE} so a
     *  freshly-joined player whose shard hasn't published yet still
     *  reads cleanly. Package-private + static for testability.
     *
     *  <p>Note: {@code blocked} doesn't apply here — by the time an
     *  invite from a blocked sender reaches us, server-side filtering
     *  has already dropped it. The lobby row's grey-blocked path is
     *  intentionally NOT mirrored. */
    static Color senderColor(LobbyMember sender)
    {
        if (sender == null) return Color.WHITE;
        boolean rankKnown = sender.peakRankIdx >= 0 && sender.peakRankIdx < RANK_LABELS.length;
        if (!rankKnown) return Color.WHITE;
        return RankUtils.getRankColor(RANK_LABELS[sender.peakRankIdx]);
    }

    /** {@inheritDoc}
     *
     *  <p>Fired for both flows: (a) sender — opponent accepted our invite,
     *  drop the [Invited M:SS] chip and swap to ConfirmFight; (b) receiver
     *  — we just clicked Accept on an incoming card, server promoted us
     *  to mutual-confirm. Either way the lobby is hidden until the user
     *  exits via Back to queue, the confirm window expires
     *  ({@link #onFightSessionExpired}), or both sides confirm
     *  ({@link #onMatchFound}). (c) Neither: a queue match gets the same
     *  Confirm Fight card, labelled Confirm / Decline, and nothing is
     *  confirmed until Confirm is clicked. */
    @Override
    public void onFightProposed(FightSession session)
    {
        if (session == null || session.opponent == null)
        {
            log.warn("MatchmakingLobbyPanel.onFightProposed: dropped - session or opponent null"
                + " (session={}, opponent={})",
                session, session == null ? "n/a" : session.opponent);
            return;
        }
        // Diagnostic: prove from the user's debug log that their UI
        // transitioned to the Confirm Fight card. Critical for triaging
        // "the other player couldn't click Confirm" reports — if this
        // line is missing from the receiver's log, the panel never got
        // the swap, meaning the issue is upstream (no fight_proposed
        // arrived, or service-level filtering dropped it). If the line
        // IS present but no subsequent "Confirm Fight clicked" line
        // shows up, the user never clicked / the click didn't fire.
        currentFight = new LocalFightState(session, confirmClock);
        showSetup(buildConfirm());
        notifyShown();

        // In-game popup so users not actively looking at the sidepanel
        // still notice the match locking in. Config-gated inside the
        // overlay itself so a user who finds it noisy can disable it
        // without unregistering anything. No-op when no notifier wired.
        MatchAlert mfn = matchFoundNotifier;
        if (mfn != null)
        {
            String opponentDisplay = nameOf(session.opponent);
            String sub = buildSubtext(session);
            Color opponentColor = senderColor(session.opponent);
            mfn.showMatch(session.fightId, opponentDisplay, sub, opponentColor);
        }
    }

    /** Builds the second-line summary for the match-found popup —
     *  "{style} ({build}) at {location}" with sensible omissions when
     *  fields are absent (e.g. Veng has no sub-location). Keyed off the
     *  {@link FightSession} carrier. Package-private + static for
     *  testability. */
    static String buildSubtext(FightSession session)
    {
        if (session == null) return "";
        var sb = new StringBuilder();
        if (session.style != null) sb.append(session.style.label);
        if (session.build != null)
        {
            if (sb.length() > 0) sb.append(' ');
            sb.append('(').append(session.build.label).append(')');
        }
        if (session.location != null && !session.location.isEmpty())
        {
            if (sb.length() > 0) sb.append(" at ");
            sb.append(session.location);
        }
        return sb.toString();
    }

    @Override
    public void onFightConfirmedByPeer(String fightId)
    {
        LocalFightState s = currentFight;
        if (s == null) return;
        if (s.session.fightId != null
            && !s.session.fightId.equals(fightId)) return;
        s.peerConfirmed = true;
        // Two paths:
        //   (a) user hasn't confirmed yet  -> rebuild Confirm Fight view
        //       so the new "Confirmed by other player" subheader paints
        //       above the Confirm Fight button.
        //   (b) user already confirmed     -> we're on the Waiting view;
        //       rebuild it so the header flips from "Waiting on other
        //       player to confirm" to "Finalizing match details\u2026"
        //       (peer just confirmed; we're now waiting on the server's
        //       match_found, not on the peer's wire).
        if (!s.iConfirmed)
        {
            showSetup(buildConfirm());
        }
        else
        {
            showSetup(buildWaiting());
        }
    }

    @Override
    public void onMatchFound(MatchInfo match)
    {
        if (match == null) return;
        LocalFightState s = currentFight;
        if (s == null) return;
        if (s.session.fightId != null
            && !s.session.fightId.equals(match.fightId)) return;
        // Both sides confirmed — MeetAt is terminal until Back to queue.
        // Server-resolved world + meeting_place travel through `match`
        // so the view can render them verbatim (see buildMeetAt).
        s.fightReady = true;
        showSetup(buildMeetAt(match));
    }

    @Override
    public void onFightSessionExpired(String fightId)
    {
        LocalFightState s = currentFight;
        if (s == null) return;
        if (s.session.fightId != null
            && !s.session.fightId.equals(fightId)) return;
        // Mirror exitSetup() but without the cancelInvite — the
        // session is already torn down server-side.
        currentFight = null;
        showCard(CARD_GATE);
    }

    /** Closes a queue match's Confirm or Waiting card whose session the server ended, as
     *  {@link #onFightSessionExpired} does. {@code fightId} is the ended session, or
     *  {@code null} when the frame names none. An invite's card, Fight ready and another
     *  session's card stay. */
    private void closeMatch(String fightId)
    {
        LocalFightState s = currentFight;
        if (s == null || s.fightReady) return;
        if (fightId != null && !fightId.equals(s.session.fightId)) return;
        onFightSessionExpired(s.session.fightId);
    }

    @Override
    public void onError(String code, String message)
    {
        // Localized via LobbyErrors — never display the raw
        // server message; it's English-only debug text that may change
        // without notice. Unknown codes get a generic fallback so a
        // server that adds a new code without a plugin release degrades
        // gracefully.
        showError(LobbyErrors.forCode(code));

        // FIGHT_SESSION_EXPIRED answers a confirm whose session already ended.
        if ("FIGHT_SESSION_EXPIRED".equals(code)) closeMatch(null);
    }

    /**
     * Builds the top-of-panel error banner. Red-tinted strip with the
     * localized message + a [×] dismiss button on the right. Hidden by
     * default; {@link #showError} flips visibility + starts the
     * auto-dismiss timer.
     */
    private JPanel buildError()
    {
        var banner = new JPanel();
        banner.setLayout(new BoxLayout(banner, X_AXIS));
        banner.setBackground(new Color(0x6e1f1f));
        banner.setBorder(createCompoundBorder(
            createMatteBorder(0, 0, 1, 0, new Color(0x401010)),
            pad(6, 8, 6, 8)));
        maxH(banner, 80);

        errorLabel = new JLabel(" ");
        errorLabel.setForeground(new Color(0xffeeee));
        plain(errorLabel, 12f);
        // Wrap long messages inside the sidepanel width — SMURF_GUARD's
        // "Play casual PvP to build…" doesn't fit on one line at 225px.
        banner.add(errorLabel);
        banner.add(Box.createHorizontalGlue());

        var dismiss = new JLabel("\u00d7");
        dismiss.setForeground(new Color(0xffcccc));
        bold(dismiss, 14f);
        dismiss.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dismiss.setToolTipText("Dismiss");
        dismiss.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e) { hideError(); }
        });
        banner.add(dismiss);

        return banner;
    }

    /** Show the localized message in the banner, restart the
     *  auto-dismiss timer, and force a re-layout so the banner becomes
     *  visible (BorderLayout doesn't auto-fire revalidate on
     *  setVisible). Safe to call from the EDT only. */
    private void showError(String localizedMessage)
    {
        if (errorBanner == null || errorLabel == null) return;
        // 200px hard cap matches the gate status label — keeps long
        // strings wrapping inside the sidepanel rather than punching
        // out the right edge.
        errorLabel.setText("<html><div style='width:200px'>"
            + escape(localizedMessage).replace("\"", "&quot;") + "</div></html>");
        errorBanner.setVisible(true);
        revalidate();
        repaint();

        if (errorTimer != null && errorTimer.isRunning())
        {
            errorTimer.stop();
        }
        errorTimer = new Timer(DISMISS_MS, e -> hideError());
        errorTimer.setRepeats(false);
        errorTimer.start();
    }

    private void hideError()
    {
        if (errorBanner == null) return;
        errorBanner.setVisible(false);
        revalidate();
        repaint();
        if (errorTimer != null) errorTimer.stop();
    }

    /**
     * Builds the reconnect-status banner pinned above {@link #errorBanner}.
     * Amber-tinted strip with a wrapped two-line message + a live
     * countdown to the next reconnect attempt. Hidden by default;
     * {@link #refreshRetry} flips visibility based on the
     * {@link LobbyService#isConnected()} / {@link
     * LobbyService#getRetryAtMs()} pair.
     *
     * <p>No dismiss button: the banner is informational and self-clears
     * when the socket reconnects. A manual dismiss would let users
     * hide a real ongoing problem and then wonder why nothing works.
     */
    private JPanel buildBanner()
    {
        var banner = new JPanel();
        banner.setLayout(new BoxLayout(banner, X_AXIS));
        // Amber, distinct from the red error banner — error = "your
        // last action failed", reconnect = "we're trying to get back
        // online". Different colors so a user who has both visible
        // doesn't conflate the two.
        banner.setBackground(new Color(0x6e551f));
        banner.setBorder(createCompoundBorder(
            createMatteBorder(0, 0, 1, 0, new Color(0x403010)),
            pad(6, 8, 6, 8)));

        retryLabel = new JLabel(" ");
        retryLabel.setForeground(new Color(0xfff2dd));
        plain(retryLabel, 12f);
        banner.add(retryLabel);
        banner.add(Box.createHorizontalGlue());
        return banner;
    }

    /**
     * 1Hz tick that polls the underlying service for connection state
     * and either shows the reconnect banner (with the live countdown)
     * or hides it. Called from {@link #retryTicker}; safe to
     * call from the EDT only.
     *
     * <p>We poll rather than wire a callback from
     * {@link com.pvp.leaderboard.service.socket.SocketMgr}
     * because the countdown needs a 1Hz redraw regardless — adding a
     * callback would only mean two notification paths for the same
     * state. The {@link LobbyService} interface deliberately exposes
     * the raw "is connected" + "next attempt epoch ms" pair instead of
     * a derived "seconds remaining" so the panel owns the
     * presentation logic (countdown formatting, edge cases when the
     * scheduled time is in the past while a reconnect is mid-flight).
     *
     * <p>{@link com.pvp.leaderboard.lobby.NoOpLobby} inherits the
     * interface defaults — {@code isConnected()=true} +
     * {@code getRetryAtMs()=0} — so the banner stays
     * hidden in unit-test runs without any extra stubbing.
     */
    private void refreshRetry()
    {
        if (retryBanner == null || retryLabel == null) return;
        boolean connected = true;
        long nextRetryMs = 0L;
        try
        {
            connected = service.isConnected();
            nextRetryMs = service.getRetryAtMs();
        }
        catch (Exception ignored)
        {
            // Defensive: a service impl that throws here should not
            // take down the panel's UI thread. Treat as "no banner".
        }

        // Two suppression cases:
        //   1. Connected — nothing to reconnect to.
        //   2. Disconnected but no retry scheduled — pre-login, or the
        //      manager is in the middle of a tick. Showing a banner
        //      with "0 seconds" would flicker; hide instead.
        if (connected || nextRetryMs <= 0L)
        {
            if (retryBanner.isVisible())
            {
                retryBanner.setVisible(false);
                revalidate();
                repaint();
            }
            return;
        }

        long remainingMs = nextRetryMs - System.currentTimeMillis();
        // Floor at 0 so a tick that lands after the scheduled time
        // (e.g. JVM pause, reconnect mid-flight) doesn't render a
        // negative countdown. Once the attempt fires the manager
        // zeroes retryAtMs and the next tick hides the
        // banner.
        long remainingSec = Math.max(0L, (remainingMs + 999L) / 1000L);

        // Two-line copy: instruction first, countdown second. Using
        // an HTML <div style='width:...'> caps wrap inside the
        // sidepanel — same trick as the gate's match-count status
        // label. 170px matches that label so both pieces of chrome
        // wrap at the same column.
        String html = "<html><div style='width:170px'>"
            + "Attempting to reconnect, if this doesn't disappear contact Toyco in discord."
            + "<br>"
            + remainingSec + " seconds remaining until next reconnect attempt."
            + "</div></html>";
        retryLabel.setText(html);
        if (!retryBanner.isVisible())
        {
            retryBanner.setVisible(true);
            revalidate();
            repaint();
        }
    }

    @Override
    public void onBlockListSnapshot(Set<String> playerIds)
    {
        if (playerIds == null) return;
        // Mirror to the static UI-side BlockList so the
        // Player-Lookup-tab Block/Unblock button on Dashboard
        // reflects the canonical server state (e.g. block from
        // another device, or rehydrate after a socket reconnect).
        // Both sets normalise on the same lowercased display-name
        // key, so they round-trip cleanly.
        BlockList.replaceAll(playerIds);
    }

    @Override
    public void onBlockAdded(String playerId)
    {
        if (playerId == null || playerId.isEmpty()) return;
        // Idempotent mirror to the dashboard-tab block list. Always
        // call regardless of the {@code .add()} return value: an
        // optimistic local Dashboard click already added it
        // here, so this push is a redundant set-mutation, but the
        // BlockList side may have missed the optimistic
        // path (e.g. when the click came from a different device).
        BlockList.block(playerId);
    }

    @Override
    public void onBlockRemoved(String playerId)
    {
        if (playerId == null || playerId.isEmpty()) return;
        BlockList.unblock(playerId);
    }

    /** Meeting place per (style, sub-location). Per spec:
     *  NH Arena → Arena; NH Wildy / FFA Portal → Ferox Enclave;
     *  Veng → Grand Exchange; Multi → Ferox Enclave; DMM → Grand Exchange. */
    private static String meetAtPlace(Style style, String location)
    {
        if (style == Style.NH)
        {
            if ("Arena".equalsIgnoreCase(location)) return "Arena";
            return "Ferox Enclave";
        }
        if (style == Style.VENG) return "Grand Exchange";
        if (style == Style.MULTI) return "Ferox Enclave";
        if (style == Style.DMM) return "Grand Exchange";
        return "TBD";
    }

    // -------------------- Fight setup widget helpers (gate-like styling) --------------------

    private JPanel newGateCard()
    {
        var card = new JPanel();
        card.setLayout(new BoxLayout(card, Y_AXIS));
        card.setBorder(pad(18, 8, 18, 8));
        return card;
    }

    private static JLabel makeHeader(String text)
    {
        return bold(new JLabel(text), GATE_PT);
    }

    /** Same size + weight as {@link #makeHeader} but with a muted color
     *  — used on Confirm/Waiting/MeetAt where every line needs to read at
     *  button size per spec, but secondary lines (style/loc context,
     *  "Confirmed by other player", countdown) should still be visually
     *  de-emphasized vs the primary header + opponent name. */
    private static JLabel makeSubhead(String text)
    {
        JLabel l = makeHeader(text);
        l.setForeground(new Color(0xcccccc));
        return l;
    }

    /** Same visual weight as the gate's per-style toggle buttons so the fight
     *  setup feels like the same widget family. */
    private static JButton makeAction(String text, boolean enabled)
    {
        JButton b = maxH(bold(new JButton(text), 15f), 36);
        b.setMargin(new Insets(6, 12, 6, 12));
        b.setFocusPainted(false);
        b.setEnabled(enabled);
        return b;
    }

    /** Bottom exit button ("Back to queue"). Neutral default button
     *  colour — it deliberately does NOT set a background so it matches
     *  the colour the Confirm button used to be. The green tint moved to
     *  the Confirm CTA (see {@link #buildConfirm}) so green now
     *  reads exclusively as the prominent positive action and the exit
     *  is the secondary, de-emphasised affordance (2026-05-29 request). */
    private static JButton makeCancel(String text, Runnable onClick)
    {
        JButton b = maxH(bold(new JButton(text), 16f), 44);
        b.setMargin(new Insets(10, 12, 10, 12));
        b.setFocusPainted(false);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** Two-column "label : value" row used in the Meet At terminal view. */
    private static JPanel makeMeetRow(String label, String value)
    {
        JPanel row = maxH(left(new JPanel(new BorderLayout(8, 0))), 32);
        row.setOpaque(false);
        JLabel v = bold(new JLabel(value), GATE_PT);
        v.setForeground(Color.WHITE);
        row.add(makeSubhead(label), WEST);
        row.add(v, CENTER);
        return row;
    }

    /** Wrap a fight-setup card in a scrollpane — RuneLite sidepanels can be
     *  short enough that the buttons + summary overflow vertically. */
    private static JComponent wrapInScroll(JPanel card)
    {
        var sp = new JScrollPane(card,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** Live "current value" label for a rank-range endpoint — bold,
     *  painted in the rank's own colour so the user can spot which
     *  tier they're on without reading the text. 15pt matches the
     *  lobby's body-text scale (style toggles + presence label).
     *
     *  <p>Backed by {@link ChipLabel} (not vanilla JLabel) because
     *  Substance L&F's LabelUI was reporting an undersized preferred
     *  width for the slider's min / max value labels — visible to
     *  users as "Bronz\u2026" / "3rd A\u2026" ellipsis truncation
     *  even though there's plenty of horizontal slack inside the
     *  225px sidepanel. ChipLabel paints the text directly in
     *  paintComponent so the L&F's clip-and-ellipsify path is
     *  bypassed entirely; the alignment-aware draw inside
     *  ChipLabel honours the LEFT / RIGHT setHorizontalAlignment
     *  set on the min / max labels respectively. Shared with
     *  {@link QueueRangeSlider}. */
    static JLabel newRankLabel(int idx)
    {
        var l = new ChipLabel(RANK_LABELS[idx]);
        bold(l, 15f);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        l.setForeground(RankUtils.getRankColor(RANK_LABELS[idx]));
        l.setBorder(pad(0, 0, 0, 0));
        return l;
    }

    static void setRankLabel(JLabel l, int idx)
    {
        String label = RANK_LABELS[idx];
        l.setText(label);
        l.setForeground(RankUtils.getRankColor(label));
    }

}
