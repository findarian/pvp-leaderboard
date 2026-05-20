package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.FightSession;
import com.pvp.leaderboard.lobby.IncomingInvite;
import com.pvp.leaderboard.lobby.LobbyErrorMessages;
import com.pvp.leaderboard.lobby.LobbyEventListener;
import com.pvp.leaderboard.lobby.LobbyJoinGate;
import com.pvp.leaderboard.lobby.LobbyMember;
import com.pvp.leaderboard.lobby.LobbyPreferences;
import com.pvp.leaderboard.lobby.LobbyService;
import com.pvp.leaderboard.lobby.NoOpLobbyJoinGate;
import com.pvp.leaderboard.lobby.MatchInfo;
import com.pvp.leaderboard.lobby.OutgoingInvite;
import com.pvp.leaderboard.lobby.Style;
import com.pvp.leaderboard.util.RankUtils;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
public class MatchmakingLobbyPanel extends JPanel implements LobbyEventListener
{
    /** Stable test hook — {@link com.pvp.leaderboard.DashboardPanelTest} finds the
     *  scrolling roster container by this name. Don't rename without updating tests. */
    public static final String ROSTER_NAME = "matchmaking-roster";

    /** Test hook on each row's action chip ([Fight] / [Lookup] / [×]) —
     *  the single name lets tests count rows regardless of chip variant. */
    public static final String ROW_ACTION_CHIP_NAME = "matchmaking-row-action-chip";

    /** Human-readable rank labels derived once from {@link RankUtils#THRESHOLDS}. */
    private static final String[] RANK_LABELS = buildRankLabels();

    /** Root card keys: pre-lobby gate, browseable lobby, and the full-screen
     *  fight-setup view (Pick Style → optional sub-location → Meet At) that
     *  takes over the panel between [Fight] and "Find a new match". The
     *  card-key strings double as {@link Component#getName()} on each
     *  card's root panel so tests (and any future "which card am I on?"
     *  diagnostic) can find the currently-visible card by name. */
    public static final String CARD_GATE = "gate";
    public static final String CARD_LOBBY = "lobby";
    public static final String CARD_FIGHT = "fight";

    /** Stable {@link Component#getName()} on {@code rootCardHost} so
     *  tests can locate it without walking layout indices. */
    public static final String ROOT_CARD_HOST_NAME = "matchmaking-root-cards";

    /** The single, agreed-on row font size. Player name + rank label use this; chips
     *  / pickers / Fight button derive from it. Locked at 15pt per the dev review. */
    private static final int ROW_FONT_PT = 15;

    /** Shared font size for every header in the pre-lobby gate
     *  ("Set up matchmaking", "Your region", "Pick your styles") and for the
     *  region dropdown's text — so the whole gate reads as one block.
     *
     *  <p>Was 18pt; matched down to 16pt to align with
     *  {@code DashboardPanel.NAV_FONT_PT} (which dropped from 18→16 to keep
     *  "Player Lookup" from clipping). At 18pt + bold the wider gate
     *  headers ("Pick your styles") were also clipping on the right edge
     *  of the 215px sidepanel. */
    private static final float GATE_HEADER_PT = 16f;

    /** Shared font size for the in-lobby header strip — Lobby title, presence
     *  count, "Style:" prefix + selected styles label, and the [Change] button.
     *  One step smaller than {@link #GATE_HEADER_PT} so the lobby strip reads
     *  as "subtitle" relative to the gate's larger title scale. */
    private static final float LOBBY_HEADER_PT = 15f;

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

    /** Seeded with NH so first-time users land on a sensible default
     *  (NH is the most-played bucket on the leaderboard) — they can
     *  deselect it and pick others, but the gate doesn't open with a
     *  blank style row. {@link #resetGateOptions()} restores this
     *  same default. Go-to-lobby still requires ≥1 style + ≥1 build
     *  so a user who manually deselects everything stays gated. */
    private final Set<Style> selectedStyles = EnumSet.of(Style.NH);
    /** The user's own region — picked once at the gate. Surfaced as the
     *  region chip on incoming-invite cards' Meet At view (so the receiver
     *  knows where the sender's coming from); never used to filter the
     *  roster. Players see everyone in the lobby regardless of region. */
    private String selfRegion = DEFAULT_REGION;
    /** Account builds — seeded with all three (Main + Zerker + Pure)
     *  so first-time users are by default open to every opponent
     *  build (most-permissive). They can narrow it down before
     *  joining or via Reset Options. {@link #resetGateOptions()}
     *  restores this default. Go-to-lobby still requires ≥1 build so
     *  a manual deselect-all path stays gated. */
    private final Set<BuildType> selectedBuildTypes = EnumSet.allOf(BuildType.class);
    private int rankMinIdx = 0;
    private int rankMaxIdx = RANK_LABELS.length - 1;

    /** Currently-visible roster snapshot. Mutated only by
     *  {@link #applyPendingRosterUpdate()} (presence coalescer) — never
     *  by direct presence pushes. */
    private final List<LobbyMember> roster;

    /** Latest staged roster snapshot from a presence push. The coalescer
     *  ticker copies this into {@link #roster} every
     *  {@link #ROSTER_REFRESH_INTERVAL_MS}. Aliases {@link #roster} when
     *  there's no pending update — the tick is then a no-op. */
    private List<LobbyMember> pendingRoster;

    private CardLayout rootCards;
    /** Container that owns {@link #rootCards}. Held as a field because
     *  the panel's top-level is now {@link BorderLayout} (with the error
     *  banner in NORTH); {@code rootCards.show(...)} calls must pass
     *  this host, not {@code this}. */
    private JPanel rootCardHost;
    private JPanel rosterContainer;
    /** Held as a field so we can snap the viewport back to the top when the
     *  user enters the fight-setup flow — they shouldn't have to scroll
     *  back up after returning from Find-a-new-match. */
    private JScrollPane rosterScroll;
    private JLabel presenceLabel;
    private JLabel currentStyleLabel;

    /** Gate widgets held as fields so {@link #resetGateOptions()} can clear
     *  their visual state in addition to clearing the backing model. The
     *  gate panel itself is built once at construction time and re-shown
     *  via CardLayout, so the toggles persist their selected state across
     *  visits unless we explicitly desync them. */
    private final Map<Style, JToggleButton> styleToggles = new EnumMap<>(Style.class);
    private final Map<BuildType, JToggleButton> buildToggles = new EnumMap<>(BuildType.class);
    private JComboBox<String> regionCombo;
    /** Disabled until ≥1 style is selected AND an account type is picked.
     *  Re-evaluated whenever any gate toggle changes. */
    private JButton goToLobbyBtn;

    /** Pinned strip at the top of the lobby (above the roster scroll) that
     *  holds zero or more {@link IncomingInvitePanel} cards — one per
     *  outstanding fight invite addressed to the user. */
    private JPanel invitesContainer;

    /** Container for the fight-setup card (rebuilt every time the user enters
     *  or transitions through the Pick Style → sub-loc → Meet At flow). Held
     *  as a field so {@link #showFightSetup} can swap its contents and
     *  {@link #rootCards} can switch to it. */
    private JPanel fightSetupContainer;

    /** Callback invoked when the user clicks a roster row's profile area
     *  (the whole row + the [Lookup] chip). Routes to the Player Lookup
     *  tab in the same way the right-click "PvP lookup" menu option does.
     *  Null-safe — tests / standalone usage can leave it unset. */
    private Consumer<String> onOpenProfile;

    /** Incoming invites awaiting our response — chip on these rows shows
     *  [Lookup] so the user can vet the sender before clicking Accept. */
    private final Set<String> incomingInviteNames = new HashSet<>();

    /** {@code inviteId → IncomingInvitePanel card} so
     *  {@link #onIncomingInviteCancelled} can locate the card to remove
     *  by the server-side stable id (sender-cancelled, block-cascade,
     *  leave-cascade all key on inviteId). Parallel to
     *  {@link #incomingInviteNames}; both must stay in lockstep —
     *  cleared together on accept/decline/cancel/exit. */
    private final Map<String, IncomingInvitePanel> incomingCardsById = new HashMap<>();

    /** Outgoing invites we've sent that are still in INVITED state
     *  (waiting for opponent to accept). Drives the row [Invited M:SS]
     *  chip; entries are removed on opponent-accept (transition to
     *  mutual-confirm), TTL expiry, or user-cancel.
     *
     *  <p>Keyed by opponent {@code player_id} (canonical lowercase
     *  name) — the stable per-peer wire identifier. Server guarantees
     *  it is non-empty on every roster + invite-received push.
     *  Display-cased {@code name} is pushed alongside but isn't unique
     *  enough to key by (two peers can render identically-cased names
     *  in edge cases). */
    private final Map<String, OutgoingInvite> outgoingInvitesByOpponent = new HashMap<>();

    /** Canonical {@code player_id}s the local user has blocked. Driven
     *  by {@link #onBlockListSnapshot} / {@link #onBlockAdded} /
     *  {@link #onBlockRemoved}. The server does NOT filter blocked
     *  rows out of the roster (that would leak presence — block-toggle
     *  would let the user probe who is online) so the panel greys them
     *  client-side. Clicking a greyed row routes to Player Lookup so
     *  the user can Unblock from there. */
    private final Set<String> blockedPlayerIds = new HashSet<>();

    /** Canonical {@code player_id}s the local user has tried to invite
     *  and the server reported back as {@code PEER_NOT_IN_LOBBY}. The
     *  backend's {@code OSRS-LobbyMembers} row outlives a peer's
     *  WebSocket connection — TODO p2-handler-lobby on the backend
     *  side keeps the row up to its 30-min sliding TTL even after
     *  {@code $disconnect} fires. So a row can appear in our roster
     *  while the underlying connection is dead, and the server's
     *  push to that connection silently {@code GoneException}s.
     *
     *  <p>When the user clicks [Fight] on such a row, the server
     *  rejects the invite with {@code PEER_NOT_IN_LOBBY} and we mark
     *  the player as locally-stale here. {@link #renderRoster()} then
     *  hides those rows from the visible list until the next
     *  authoritative {@code lobby/roster} push (which also clears the
     *  set — see {@link #onRosterSnapshot}). Confirms the user's
     *  intuition that "they're gone" without them having to puzzle
     *  through a banner message. */
    private final Set<String> recentlyStalePlayerIds = new HashSet<>();

    /** {@code player_id} of the most recent {@link #submitOutgoingInvite}
     *  target, with the wall-clock send time. Used by
     *  {@link #onError(String, String)} to correlate a
     *  {@code PEER_NOT_IN_LOBBY} response back to a specific row so the
     *  panel can mark only that player stale (instead of clearing
     *  every outgoing invite — the user might have multiple in
     *  flight). The send timestamp also gates the correlation: a
     *  PEER_NOT_IN_LOBBY arriving more than {@link #STALE_CORRELATION_WINDOW_MS}
     *  after the send is treated as unrelated to the last invite (e.g.
     *  the user clicked [Fight], then {@link #cancelOutgoingInvite}d,
     *  then someone else sent us {@code lobby/cancel_invite} that
     *  bounced). */
    private String lastInviteTargetPlayerId;
    private long lastInviteSentAtMs;

    /** Window for correlating a {@code PEER_NOT_IN_LOBBY} response to
     *  the most recent invite send. 5s is a generous upper bound on
     *  client-server RTT under poor network conditions; anything
     *  longer than this is almost certainly an unrelated error
     *  (and the worst case if we mis-correlate is one extra row
     *  greyed for one render cycle, cleared by the next roster push). */
    private static final long STALE_CORRELATION_WINDOW_MS = 5_000L;

    /** Active fight session occupying the FIGHT card. One at a time — set
     *  on opponent-accept (sender side) or on receiver Accept Fight click;
     *  cleared on Find-a-new-match, 30s confirm-window expiry, or fight
     *  completion. Null while in the lobby. */
    private LocalFightState currentFightSession;

    /** 1Hz ticker that drives all live countdowns: row [Invited M:SS] chips,
     *  the FIGHT card's 30s confirm-window label, and the two TTL expiry
     *  paths (10-min invite + 30-sec confirm). Started in constructor; never
     *  stopped (panel lives the lifetime of the dashboard). */
    private Timer fightTicker;

    /** Roster refresh coalescer cadence. Presence pushes (player joined,
     *  left, rank changed, etc.) buffer into {@link #pendingRoster} and only
     *  commit to the visible {@link #roster} on this cadence — so rows
     *  don't reorder under the user's cursor and cause misclicks during
     *  busy join/leave churn. User-initiated changes (filter toggles, rank
     *  slider) bypass this and re-render immediately. */
    private static final long ROSTER_REFRESH_INTERVAL_MS = 60_000L;

    /** Coalesces presence pushes — fires every {@link #ROSTER_REFRESH_INTERVAL_MS}
     *  to commit the latest {@link #pendingRoster} snapshot. */
    private Timer rosterRefreshTicker;

    /** PlayerRow → MatchmakingLobbyPanel handoff for "user clicked [Fight] on
     *  this opponent". Triggers the full-screen fight-setup card. */
    @FunctionalInterface
    interface FightStartCallback
    {
        void onFight(LobbyMember opponent);
    }

    /** PlayerRow → MatchmakingLobbyPanel handoff for "user clicked [Invited M:SS]
     *  to cancel an outstanding outgoing invite". */
    @FunctionalInterface
    interface InviteCancelCallback
    {
        void cancel(LobbyMember opponent);
    }

    // -------------------- Fight-flow state machine --------------------

    /** Outgoing invite TTL — sender sees [Invited M:SS] in the lobby for 10
     *  min unless the opponent accepts first. Same as the spec'd block window. */
    private static final long FIGHT_INVITE_TTL_MS = 10L * 60L * 1000L;

    /** Panel-local UI state for the active mutual-confirm session. Wraps
     *  the immutable {@link FightSession} pushed by the service with two
     *  mutable flags the panel toggles in response to the local user
     *  clicking Confirm + the service's {@code onFightConfirmedByPeer} push.
     *  Timer ownership lives in the service implementation, so this type
     *  is a pure UI-state record — no {@code Timer} fields.
     *
     *  <p>Fields {@link #opponent}, {@link #style}, {@link #build},
     *  {@link #location}, {@link #confirmExpiresAt} are aliased directly
     *  from the immutable session bundle so existing rendering code that
     *  reads e.g. {@code s.opponent.name} keeps working without churn. */
    private static final class LocalFightState
    {
        final FightSession session;
        final LobbyMember opponent;
        final Style style;
        final BuildType build;
        final String location;
        final long confirmExpiresAt;
        boolean iConfirmed;
        boolean peerConfirmed;

        LocalFightState(FightSession session)
        {
            this.session = session;
            this.opponent = session.opponent;
            this.style = session.style;
            this.build = session.build;
            this.location = session.location;
            this.confirmExpiresAt = session.confirmExpiresAtEpochMs;
        }

        boolean bothConfirmed() { return iConfirmed && peerConfirmed; }
    }

    /** Backing {@link LobbyService} — {@code WebSocketLobbyService} in
     *  production. */
    private final LobbyService service;

    /** Per-style match-count gate (anti-smurf: need
     *  {@link LobbyJoinGate#THRESHOLD} kills + deaths in a style before
     *  the user can advertise it). Drives the locked-style rendering on
     *  the gate's style toggles + the [Refresh count] / "Updated X min
     *  ago" status row. The server enforces the same rule at
     *  {@code lobby/join} (returning {@code SMURF_GUARD}); this client
     *  gate is UX-only.
     *
     *  <p>Default to {@link NoOpLobbyJoinGate} so the panel is
     *  constructible in tests that don't need the anti-smurf UX — the
     *  no-op reports every style unlocked, so the server-side check is
     *  the safety net. */
    private final LobbyJoinGate joinGate;

    /** Gate UI elements held as fields so the gate-listener callback can
     *  re-render them without rebuilding the whole gate panel. Built
     *  lazily inside {@link #buildStyleGate()}; {@code null} only during
     *  the brief window before the gate constructs. */
    private JLabel gateMatchCountStatusLabel;
    private JButton gateMatchCountRefreshBtn;

    /** Notice shown <i>in lieu of</i> the gate when the user isn't
     *  logged into OSRS. Driven by {@link LobbyJoinGate#isLoggedIn()}
     *  via {@link #applyLoginGateState()}; flips on/off whenever the
     *  gate listener fires. */
    private JLabel gateLoggedOutNotice;

    /** Sub-panel that holds every gate widget below the title (region
     *  picker, style/build toggles, smurf-guard row, Go-to-lobby
     *  button). Hidden as one unit when
     *  {@link LobbyJoinGate#isLoggedIn()} is {@code false} so the user
     *  sees a clean "Please log into the game" notice instead of a
     *  half-greyed-out gate they can't interact with. */
    private JPanel gateContent;

    /** Resolved-at-render-time supplier for the local OSRS player's
     *  name. Wired by {@code DashboardPanel} via
     *  {@link #setSelfIdentity}; {@code null} until then (the panel
     *  hides the self-profile preview entirely when no supplier or no
     *  name). The local OSRS player's name is the only identity the
     *  panel needs — server pushes carry display names alongside
     *  canonical player ids, and outbound cmds key by canonical id. */
    private Supplier<String> selfNameSupplier;

    /** Eager game-state signal — returns true the moment
     *  {@code GameState.LOGGED_IN} fires, before the 10-tick
     *  name-resolve delay that gates {@link LobbyJoinGate#isLoggedIn()}.
     *  Used by {@link #applyLoginGateState()} to distinguish "truly
     *  logged out" (show "Please log into the game") from "logged in
     *  but waiting for player name + match counts to resolve" (show
     *  "Loading\u2026") so the brief startup window doesn't flash a
     *  stale logged-out prompt to a user who's already in-game.
     *
     *  <p>Optional — when null, the panel falls back to the legacy
     *  two-phase view (gate-or-please-login) so test fixtures that
     *  don't wire it still render the same as before. */
    private java.util.function.BooleanSupplier isGameLoggedInSupplier;

    /** Container + row references for the self-profile preview shown
     *  above the rank slider. {@link #selfPreviewContainer} hosts the
     *  caption + the self {@link PlayerRow}; {@link #selfPreviewRow}
     *  is the actual row, swapped in place when gate selections change
     *  (style toggles, build toggles, region picker) so the chips
     *  always reflect the user's current advertised set. */
    private JPanel selfPreviewContainer;
    private PlayerRow selfPreviewRow;

    /** {@code true} once the user has clicked "Go to lobby" and hasn't
     *  subsequently hit Reset Options.
     *
     *  <p>Drives the logout → login auto-restore in
     *  {@link #applyLoginGateState()}: a user who logs out from the
     *  lobby is returned to the lobby card (not the gate) on re-login,
     *  with their previously-picked region / styles / builds still
     *  selected. Reset Options is the explicit "I want to re-pick"
     *  escape hatch — it clears this flag.
     *
     *  <p>Persisted across plugin restarts via {@link #prefs} so a user
     *  who passes the gate once stays in the auto-restore lane forever
     *  unless they Reset Options. Also surfaces if the user toggles the
     *  plugin off and back on within the same RuneLite session. */
    private boolean hasJoinedLobby;

    /** Persistence backend for gate selections (region / styles / builds /
     *  rank-slider bounds / hasJoined sticky flag). Always non-null —
     *  ctor overloads that don't supply one fall back to
     *  {@link LobbyPreferences#inMemory()}. */
    private final LobbyPreferences prefs;

    /** {@code true} once {@link #maybeAutoJoinAfterLogin()} has issued a
     *  {@code service.joinLobby(...)} for the current login session.
     *  Cleared in {@link #applyLoginGateState()} on every logout so
     *  the next login re-issues. Without this guard the gate
     *  listener firing on every hourly match-count refresh would spam
     *  the server with redundant {@code lobby/join} updates.
     *
     *  <p>The auto-issue exists because {@link WebSocketLobbyService}'s
     *  reconnect-replay cache is in-memory only — survives a
     *  within-session logout/login round-trip but NOT a fresh plugin
     *  start. Without the auto-issue, a user who'd previously passed
     *  the gate would land on {@link #CARD_LOBBY} after a RuneLite
     *  restart but the server wouldn't have an
     *  {@code OSRS-LobbyMembers} row for them — empty roster, invisible
     *  to peers. */
    private boolean autoJoinIssuedThisSession;

    /** Top-of-panel inline error banner. Persists across the gate / lobby /
     *  fight cards (lives above the {@link CardLayout}) so a SMURF_GUARD
     *  triggered from the gate doesn't vanish when the user navigates
     *  away. {@link #errorBannerTimer} auto-dismisses after
     *  {@link #ERROR_BANNER_DISMISS_MS}. Driven by
     *  {@link #onError(String, String)} via the localized message table
     *  in {@link LobbyErrorMessages}. */
    private JPanel errorBanner;
    private JLabel errorBannerLabel;
    private Timer errorBannerTimer;

    /** Auto-dismiss for the error banner. Six seconds is a tradeoff:
     *  long enough that a user with their eyes off the panel still
     *  catches the message; short enough that stale errors don't
     *  permanently clutter the UI. User can dismiss manually via the
     *  banner's [×] button anytime. */
    private static final int ERROR_BANNER_DISMISS_MS = 6_000;

    /** Reconnect-status banner pinned above the error banner. Shown
     *  whenever {@link LobbyService#isConnected()} returns
     *  {@code false} and {@link LobbyService#getNextReconnectAttemptEpochMs()}
     *  returns a non-zero scheduled retry. Displays the localized
     *  reconnect copy + a live countdown of seconds remaining until
     *  the next attempt. Driven by {@link #reconnectBannerTicker} at
     *  1 Hz; we deliberately use polling instead of a callback from
     *  {@link com.pvp.leaderboard.service.socket.WebSocketManager}
     *  because the countdown needs a 1Hz redraw anyway — a callback
     *  would only add complexity. */
    private JPanel reconnectBanner;
    private JLabel reconnectBannerLabel;
    private Timer reconnectBannerTicker;

    public MatchmakingLobbyPanel(LobbyService service)
    {
        this(service, new NoOpLobbyJoinGate(), LobbyPreferences.inMemory());
    }

    public MatchmakingLobbyPanel(LobbyService service, LobbyJoinGate joinGate)
    {
        this(service, joinGate, LobbyPreferences.inMemory());
    }

    public MatchmakingLobbyPanel(LobbyService service, LobbyJoinGate joinGate,
                                 LobbyPreferences prefs)
    {
        if (service == null) throw new IllegalArgumentException("LobbyService is required");
        this.service = service;
        // NoOpLobbyJoinGate is the safe fallback — reports every style
        // unlocked so a wiring bug doesn't lock the user out (server
        // still has the final word via the v2 SMURF_GUARD check).
        this.joinGate = joinGate != null ? joinGate : new NoOpLobbyJoinGate();
        // In-memory fallback so test call sites + the existing
        // NoOpLobbyService production placeholder continue to build
        // without a real ConfigManager. Real persistence kicks in once
        // PvPLeaderboardPlugin wires the Guice-provided variant.
        this.prefs = prefs != null ? prefs : LobbyPreferences.inMemory();
        // Restore previously-persisted gate state into the in-memory
        // fields BEFORE buildStyleGate() initialises the widgets — the
        // gate's region combo, style toggles, build toggles, and the
        // sticky hasJoinedLobby flag all read from these fields.
        applyPersistedPreferences();
        this.roster = new ArrayList<>();
        // Initial pending == visible so the first coalescer tick is a no-op
        // until a roster snapshot arrives via onRosterSnapshot(...).
        this.pendingRoster = roster;
        // Outer = BorderLayout so the error banner can pin to NORTH
        // above the cards. The card-switching subpanel goes in CENTER
        // and owns the gate/lobby/fight subviews via {@link #rootCards}.
        // Was a top-level CardLayout pre-banner; that meant any pinned
        // chrome had to be duplicated into every card.
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        errorBanner = buildErrorBanner();
        errorBanner.setVisible(false);
        reconnectBanner = buildReconnectBanner();
        reconnectBanner.setVisible(false);
        // Stack the two banners (reconnect on top, error below) in a
        // single NORTH slot. BorderLayout allows only one component
        // per region, so the vertical box is the cheapest way to keep
        // both pinned without rebuilding the outer layout. Reconnect
        // sits above the error banner because it represents a more
        // urgent, ongoing condition — a transient validation error
        // shouldn't visually hide the persistent "we're disconnected"
        // state.
        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setOpaque(false);
        northStack.add(reconnectBanner);
        northStack.add(errorBanner);
        add(northStack, BorderLayout.NORTH);

        rootCards = new CardLayout();
        rootCardHost = new JPanel(rootCards);
        rootCardHost.setName(ROOT_CARD_HOST_NAME);
        JPanel gateCard = buildStyleGate();
        gateCard.setName(CARD_GATE);
        rootCardHost.add(gateCard, CARD_GATE);
        JPanel lobbyCard = buildLobbyView();
        lobbyCard.setName(CARD_LOBBY);
        rootCardHost.add(lobbyCard, CARD_LOBBY);
        fightSetupContainer = new JPanel(new BorderLayout());
        fightSetupContainer.setName(CARD_FIGHT);
        rootCardHost.add(fightSetupContainer, CARD_FIGHT);
        add(rootCardHost, BorderLayout.CENTER);
        rootCards.show(rootCardHost, CARD_GATE);

        fightTicker = new Timer(1000, e -> onFightTick());
        fightTicker.setRepeats(true);
        fightTicker.start();

        rosterRefreshTicker = new Timer((int) ROSTER_REFRESH_INTERVAL_MS, e -> applyPendingRosterUpdate());
        rosterRefreshTicker.setRepeats(true);
        rosterRefreshTicker.start();

        reconnectBannerTicker = new Timer(1000, e -> refreshReconnectBanner());
        reconnectBannerTicker.setRepeats(true);
        reconnectBannerTicker.start();

        // Register for server pushes. The roster + initial incoming-invite
        // cards arrive via these callbacks when the user passes the gate
        // by calling service.joinLobby(...).
        this.service.setListener(this);
        this.service.start();

        // Re-render the gate's match-count status row + style-toggle
        // lock states whenever the gate refreshes. EDT marshalling lives
        // inside the gate impl (see UserProfileLobbyJoinGate#fireListenersOnEdt).
        this.joinGate.addListener(this::onJoinGateChanged);
    }

    // -------------------- UI construction --------------------

    /**
     * Pre-lobby gate. The user picks their own region (single-select dropdown),
     * toggles one or more fight styles (≥1 required), and picks an account
     * build type (Pure / Zerker / Main — exactly 1 required). "Go to lobby"
     * stays disabled until both required picks are satisfied. After Reset
     * Options the user returns here and must re-pick from scratch.
     */
    private JPanel buildStyleGate()
    {
        JPanel gate = new JPanel();
        gate.setLayout(new BoxLayout(gate, BoxLayout.Y_AXIS));
        gate.setBorder(BorderFactory.createEmptyBorder(18, 8, 18, 8));

        JLabel title = new JLabel("Set up matchmaking");
        title.setFont(title.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        title.setAlignmentX(LEFT_ALIGNMENT);
        gate.add(title);
        gate.add(leftAlignedStrut(8));

        // ---- Logged-out notice (shown in lieu of the rest of the gate) ----
        // Lives next to {@link #gateContent} as a sibling; exactly one
        // of the two is visible at a time. Pre-construction default is
        // "logged out" since the lobby gate is built during the
        // dashboard ctor — well before any GameState event fires.
        // applyLoginGateState() at the bottom of this method picks the
        // right initial visibility from the gate.
        //
        // Explicit {@code <br>} rather than {@code <div style='width:'>}
        // — the latter under Substance L&F miscomputes wrap and clipped
        // the word "up" out of the rendered text. With a hand-broken
        // line we get deterministic layout matching the sidepanel's
        // ~209px usable width (225 sidepanel - 16 horizontal padding).
        gateLoggedOutNotice = new JLabel(
            "<html>Please log into the game<br>to set up matchmaking.</html>");
        gateLoggedOutNotice.setFont(gateLoggedOutNotice.getFont().deriveFont(Font.BOLD, 15f));
        gateLoggedOutNotice.setForeground(new Color(0xcc, 0xcc, 0xcc));
        gateLoggedOutNotice.setAlignmentX(LEFT_ALIGNMENT);
        // Cap height to the rendered preferred height so BoxLayout
        // doesn't stretch the label vertically into the space freed up
        // by the hidden gateContent. Was Integer.MAX_VALUE which made
        // BoxLayout give the entire empty cell to the label — text
        // visually pinned to the bottom of the gate.
        Dimension noticePref = gateLoggedOutNotice.getPreferredSize();
        gateLoggedOutNotice.setMaximumSize(new Dimension(noticePref.width, noticePref.height));
        gate.add(gateLoggedOutNotice);

        // Wrap everything else in a sub-panel so we can hide/show as
        // one unit without playing whack-a-mole with individual
        // setVisible() calls every time a new gate widget is added.
        gateContent = new JPanel();
        gateContent.setLayout(new BoxLayout(gateContent, BoxLayout.Y_AXIS));
        gateContent.setAlignmentX(LEFT_ALIGNMENT);
        gateContent.setOpaque(false);

        // ---- Region picker ----
        // Header + dropdown both share the title's 18pt BOLD treatment so the
        // gate reads as one consistent typographic block (per spec: match the
        // "Set up matchmaking" title).
        JLabel regionTitle = new JLabel("Your region");
        regionTitle.setFont(regionTitle.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        regionTitle.setAlignmentX(LEFT_ALIGNMENT);
        gateContent.add(regionTitle);
        gateContent.add(leftAlignedStrut(4));

        regionCombo = new JComboBox<>(REGION_LABELS);
        regionCombo.setSelectedIndex(indexOfRegion(selfRegion));
        regionCombo.setFont(regionCombo.getFont().deriveFont(Font.PLAIN, GATE_HEADER_PT));
        // Bumped from 32 to 40 to fit the larger 18pt font without clipping descenders.
        regionCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        regionCombo.setAlignmentX(LEFT_ALIGNMENT);
        regionCombo.addActionListener(e ->
        {
            int idx = regionCombo.getSelectedIndex();
            if (idx >= 0)
            {
                selfRegion = REGION_CODES[idx];
                prefs.setRegion(selfRegion);
                // Region chip lives on the self-preview row above the
                // slider — keep it in sync with the picker.
                renderSelfPreview();
            }
        });
        gateContent.add(regionCombo);
        gateContent.add(leftAlignedStrut(14));

        // ---- Style multi-select ----
        // Header alone — the "NH is on by default…" subtitle was removed per spec
        // (the multi-select toggles + the single-style enforcement are obvious
        // from the highlighted state alone).
        JLabel styleTitle = new JLabel("Pick your styles");
        styleTitle.setFont(styleTitle.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        styleTitle.setAlignmentX(LEFT_ALIGNMENT);
        gateContent.add(styleTitle);
        gateContent.add(leftAlignedStrut(8));

        styleToggles.clear();
        for (Style s : Style.values())
        {
            JToggleButton tog = new JToggleButton(s.label);
            tog.setSelected(selectedStyles.contains(s));
            tog.setFont(tog.getFont().deriveFont(Font.BOLD, 15f));
            tog.setMargin(new Insets(6, 12, 6, 12));
            tog.setAlignmentX(LEFT_ALIGNMENT);
            tog.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            tog.setFocusPainted(false);
            // Green-on-selected / red-on-unselected paint, applied on
            // construction + via an item listener so every programmatic
            // setSelected (resetGateOptions, applyStyleToggleLockState
            // force-deselect, login restore) auto-repaints without a
            // separate refresh pass.
            applyToggleVisualState(tog);
            tog.addItemListener(e -> applyToggleVisualState(tog));
            tog.addActionListener(e ->
            {
                if (tog.isSelected())
                {
                    selectedStyles.add(s);
                }
                else if (selectedStyles.size() == 1 && selectedStyles.contains(s))
                {
                    // Last remaining style — re-select it; we always need ≥1
                    // once the user has picked any (Go-to-lobby gate ensures
                    // we never enter the lobby with zero styles).
                    tog.setSelected(true);
                }
                else
                {
                    selectedStyles.remove(s);
                }
                prefs.setStyles(selectedStyles);
                refreshCurrentStyleLabel();
                updateGoToLobbyEnabled();
            });
            styleToggles.put(s, tog);
            gateContent.add(tog);
            gateContent.add(leftAlignedStrut(4));
        }

        // ---- Anti-smurf match-count status row (drives style-toggle lock state) ----
        // Layout: explanation line on top, per-style remaining counts
        // below, [Refresh count] button at the bottom. Both widgets
        // live as fields so the gate-listener callback can update them
        // in place without rebuilding the gate. Font sizes mirror the
        // style-toggle row above (15pt BOLD).
        gateContent.add(leftAlignedStrut(8));

        gateMatchCountStatusLabel = new JLabel(" ");
        gateMatchCountStatusLabel.setFont(gateMatchCountStatusLabel.getFont().deriveFont(Font.BOLD, 15f));
        gateMatchCountStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
        gateMatchCountStatusLabel.setForeground(new Color(0xcc, 0xcc, 0xcc));
        gateContent.add(gateMatchCountStatusLabel);
        gateContent.add(leftAlignedStrut(6));

        // Left-aligned single-button row. Was a row of button + updated
        // label; the label was removed but keeping the row scaffolding
        // gives us a single attach-point if we ever re-add chrome here.
        JPanel refreshRow = new JPanel();
        refreshRow.setLayout(new BoxLayout(refreshRow, BoxLayout.X_AXIS));
        refreshRow.setAlignmentX(LEFT_ALIGNMENT);
        refreshRow.setOpaque(false);
        // 32px max-height accommodates the bigger 15pt font without
        // letting BoxLayout stretch the button vertically inside the
        // sidepanel. 28 was for the 12pt button.
        refreshRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        gateMatchCountRefreshBtn = new JButton("Refresh count");
        gateMatchCountRefreshBtn.setFont(gateMatchCountRefreshBtn.getFont().deriveFont(Font.BOLD, 15f));
        gateMatchCountRefreshBtn.setMargin(new Insets(3, 8, 3, 8));
        gateMatchCountRefreshBtn.setFocusPainted(false);
        // joinGate.refresh() is itself debounced — back-to-back clicks
        // collapse into one in-flight fetch — but we still flip the
        // button's enabled state for clearer feedback while refreshing.
        gateMatchCountRefreshBtn.addActionListener(e -> joinGate.refresh());
        refreshRow.add(gateMatchCountRefreshBtn);
        refreshRow.add(Box.createHorizontalGlue());
        gateContent.add(refreshRow);

        // Apply the initial counts (might still be unknown — the gate is
        // a no-op pre-login; once UserProfileLobbyJoinGate.onLogin fires
        // the listener will repaint). applyStyleToggleLockState() also
        // disables the toggle for any below-threshold style now so the
        // first user pass through the gate already sees the right
        // lock state.
        renderJoinGateStatus();
        applyStyleToggleLockState();

        gateContent.add(leftAlignedStrut(12));

        // ---- Account type multi-select ----
        // Required pick (no default), 1–3 builds. Mirrors the style toggles'
        // multi-select pattern: turning a build on adds it; turning the last
        // remaining build off is rejected (we always need ≥1 once any has
        // been picked). Use Reset Options to return to the "none" state.
        JLabel accountTitle = new JLabel("Pick your build");
        accountTitle.setFont(accountTitle.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        accountTitle.setAlignmentX(LEFT_ALIGNMENT);
        gateContent.add(accountTitle);
        gateContent.add(leftAlignedStrut(8));

        buildToggles.clear();
        for (BuildType a : BuildType.values())
        {
            JToggleButton tog = new JToggleButton(a.label);
            tog.setSelected(selectedBuildTypes.contains(a));
            tog.setFont(tog.getFont().deriveFont(Font.BOLD, 15f));
            tog.setMargin(new Insets(6, 12, 6, 12));
            tog.setAlignmentX(LEFT_ALIGNMENT);
            tog.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            tog.setFocusPainted(false);
            applyToggleVisualState(tog);
            tog.addItemListener(e -> applyToggleVisualState(tog));
            tog.addActionListener(e ->
            {
                if (tog.isSelected())
                {
                    selectedBuildTypes.add(a);
                }
                else if (selectedBuildTypes.size() == 1 && selectedBuildTypes.contains(a))
                {
                    // Last remaining build — re-select it; once any has been
                    // picked we always need ≥1 (Go-to-lobby gate prevents
                    // entering the lobby with zero builds).
                    tog.setSelected(true);
                }
                else
                {
                    selectedBuildTypes.remove(a);
                }
                prefs.setBuilds(selectedBuildTypes);
                refreshCurrentStyleLabel();
                updateGoToLobbyEnabled();
            });
            buildToggles.put(a, tog);
            gateContent.add(tog);
            gateContent.add(leftAlignedStrut(4));
        }

        gateContent.add(leftAlignedStrut(12));

        goToLobbyBtn = new JButton("Go to lobby");
        goToLobbyBtn.setFont(goToLobbyBtn.getFont().deriveFont(Font.BOLD, 16f));
        goToLobbyBtn.setMargin(new Insets(10, 12, 10, 12));
        goToLobbyBtn.setAlignmentX(LEFT_ALIGNMENT);
        goToLobbyBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        goToLobbyBtn.setFocusPainted(false);
        goToLobbyBtn.setBackground(new Color(0x2e, 0x7d, 0x32));
        goToLobbyBtn.setForeground(Color.WHITE);
        goToLobbyBtn.setOpaque(true);
        goToLobbyBtn.setBorderPainted(false);
        goToLobbyBtn.addActionListener(e ->
        {
            // Region picker only sets the user's own region — the lobby
            // shows everyone regardless of region. Forward the gate picks
            // to the service; it'll push the roster + any pending incoming
            // invites back through onRosterSnapshot / onIncomingInvite.
            refreshCurrentStyleLabel();
            service.joinLobby(selfRegion, selectedStyles, selectedBuildTypes,
                rankMinIdx, rankMaxIdx, pickSortBucket());
            // Sticky-flag the lobby so a logout → login round-trip
            // (or a full plugin restart) auto-returns the user to this
            // card with their picks preserved, rather than dropping them
            // back at the gate. Persisted via prefs so restarts honour
            // it; the in-memory field handles intra-session navigation.
            hasJoinedLobby = true;
            // The user just sent a fresh lobby/join via the click above
            // — flag the auto-issue path as done for this session so a
            // subsequent gate-listener tick (e.g. hourly count refresh)
            // doesn't re-fire a redundant lobby/join with the same args.
            autoJoinIssuedThisSession = true;
            saveGateSelections();
            prefs.setHasJoined(true);
            renderRoster();
            rootCards.show(rootCardHost, CARD_LOBBY);
        });
        gateContent.add(goToLobbyBtn);
        updateGoToLobbyEnabled();

        // Mount the wrapper so all gate widgets show up under the
        // title. applyLoginGateState() then flips visibility based on
        // the current LobbyJoinGate.isLoggedIn() snapshot — the panel
        // is built during the dashboard ctor (well before any GameState
        // event fires) so the initial render is correct.
        gate.add(gateContent);
        applyLoginGateState();

        return gate;
    }

    /** Toggles the gate between "logged in" and "logged out" views by
     *  swapping visibility of {@link #gateLoggedOutNotice} and
     *  {@link #gateContent}. Called from the gate-listener every time
     *  the {@link LobbyJoinGate} fires (which includes onLogin /
     *  onLogout transitions). EDT-only — the listener already
     *  marshals.
     *
     *  <p>Drives the OSRS-login/logout lifecycle for the lobby:
     *  <ul>
     *    <li><b>Logout</b> — clear any active fight setup (the server
     *    expires the session on disconnect anyway) and force the root
     *    card back to {@link #CARD_GATE}. The gate's logged-out notice
     *    is what the user then sees. {@link #hasJoinedLobby} +
     *    {@link #selectedStyles}/{@link #selectedBuildTypes}/{@link #selfRegion}
     *    are deliberately preserved so the next login can auto-restore
     *    without forcing the user to re-pick.</li>
     *    <li><b>Login auto-restore</b> — if the user was previously in
     *    the lobby (per {@link #hasJoinedLobby}) and they're not
     *    mid-fight, jump back to {@link #CARD_LOBBY}. The
     *    {@code WebSocketLobbyService}'s reconnect-replay re-issues
     *    {@code lobby/join} on the OkHttp reconnect, so the panel just
     *    needs to navigate; it doesn't need to call
     *    {@code service.joinLobby} again itself.</li>
     *  </ul> */
    private void applyLoginGateState()
    {
        if (gateContent == null || gateLoggedOutNotice == null) return;
        boolean gateReady = joinGate.isLoggedIn();
        // Eager game-state probe — true the instant
        // GameState.LOGGED_IN fires, before the 10-tick name-resolve
        // delay that gates LobbyJoinGate.onLogin(). We use this to
        // pick which logged-out copy to show: a true logged-out user
        // (game state != LOGGED_IN) sees the "Please log into the
        // game" prompt, while a freshly-logged-in user inside the
        // 10-tick startup window sees "Loading…" instead — the gate
        // listener fires again once name + counts resolve and flips
        // gateReady to true, hiding the notice entirely.
        // Defaults to true when the supplier is unwired (test
        // fixtures, gateless construction paths) so the legacy
        // two-phase notice still works for those callers.
        boolean gameLoggedIn = isGameLoggedInSupplier == null
            || isGameLoggedInSupplier.getAsBoolean();

        if (gateReady)
        {
            gateLoggedOutNotice.setVisible(false);
            gateContent.setVisible(true);
        }
        else if (gameLoggedIn)
        {
            // Logged into OSRS but plugin still resolving identity +
            // smurf-guard counts. Show a low-key "Loading…" so the
            // user knows the panel saw their login and isn't broken.
            // Same hand-broken <br> markup as the logged-out copy
            // (see field doc) to dodge Substance's HTML-wrap clip bug.
            gateLoggedOutNotice.setText("<html>Loading\u2026</html>");
            gateLoggedOutNotice.setVisible(true);
            gateContent.setVisible(false);
        }
        else
        {
            gateLoggedOutNotice.setText(
                "<html>Please log into the game<br>to set up matchmaking.</html>");
            gateLoggedOutNotice.setVisible(true);
            gateContent.setVisible(false);
        }
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
            // Tear down any in-flight fight session so a relogin doesn't
            // resume into a stale CARD_FIGHT — the server already
            // expires the session on disconnect, this just keeps the
            // local state honest.
            if (currentFightSession != null) exitFightSetup();
            // Reset the auto-join sticky so the next login re-issues
            // service.joinLobby(...). The server already drops the
            // OSRS-LobbyMembers row on $disconnect (60s grace, then
            // TTL), so a fresh join is the right thing on re-login.
            autoJoinIssuedThisSession = false;
            rootCards.show(rootCardHost, CARD_GATE);
            return;
        }

        if (hasJoinedLobby && currentFightSession == null)
        {
            // Auto-restore safety net: if the persisted gate picks have
            // become invalid since the user last logged in (e.g. all
            // their advertised styles fell below the SMURF_GUARD
            // threshold and applyStyleToggleLockState force-deselected
            // them), drop back to the gate so the user re-picks. Sending
            // lobby/join with an empty styles set would just trip
            // server-side validation. applyStyleToggleLockState() is
            // called BEFORE this method in onJoinGateChanged() so the
            // empty-set check below sees the post-cleanup state.
            if (selectedStyles.isEmpty() || selectedBuildTypes.isEmpty())
            {
                rootCards.show(rootCardHost, CARD_GATE);
                return;
            }
            // currentVisibleCard() requires walking the rootCardHost's
            // children to find the one with isVisible()==true; cheaper
            // to just always re-issue the show(CARD_LOBBY) — CardLayout
            // is a no-op if we're already on that card.
            rootCards.show(rootCardHost, CARD_LOBBY);
        }
    }

    /** Re-issues {@code service.joinLobby(...)} once per login session
     *  when the persisted "I'm in the lobby" sticky flag is set and the
     *  gate picks are valid (every advertised style has a known +
     *  unlocked match count). Idempotent — once
     *  {@link #autoJoinIssuedThisSession} flips true, subsequent calls
     *  no-op until the next logout clears it.
     *
     *  <p>Defers when match counts haven't loaded yet — the gate
     *  listener will fire again when they do, and this method gets
     *  called from {@link #onJoinGateChanged()} on every refresh. The
     *  server's {@code SMURF_GUARD v2} would reject locked-style joins
     *  anyway, but issuing them at all generates a noisy
     *  {@code error/lobby} push that the user briefly sees in the
     *  banner — better to wait for trusted counts.
     *
     *  <p>Defers when {@link #selectedStyles} or
     *  {@link #selectedBuildTypes} is empty — that path is
     *  drop-to-gate, handled by {@link #applyLoginGateState()}. */
    private void maybeAutoJoinAfterLogin()
    {
        if (autoJoinIssuedThisSession) return;
        if (!hasJoinedLobby) return;
        if (currentFightSession != null) return;
        if (!joinGate.isLoggedIn()) return;
        if (selectedStyles.isEmpty() || selectedBuildTypes.isEmpty()) return;

        Map<Style, Integer> counts = joinGate.getMatchCounts();
        for (Style s : selectedStyles)
        {
            Integer c = counts.get(s);
            // Unknown count → defer to a later refresh. Locked count →
            // applyStyleToggleLockState() will force-deselect on this
            // same gate-listener tick, and the next call to this method
            // will see selectedStyles cleaned up (or empty → drop-to-gate
            // via applyLoginGateState).
            if (c == null || !LobbyJoinGate.isUnlocked(c)) return;
        }

        service.joinLobby(selfRegion, selectedStyles, selectedBuildTypes,
            rankMinIdx, rankMaxIdx, pickSortBucket());
        autoJoinIssuedThisSession = true;
    }

    /** Enables {@link #goToLobbyBtn} only when all gate picks are
     *  satisfied: ≥1 style, ≥1 account build, AND every selected style
     *  has cleared the anti-smurf threshold via {@link #joinGate}. The
     *  disabled state also visually fades the green fill so users see
     *  why they can't proceed yet. */
    private void updateGoToLobbyEnabled()
    {
        if (goToLobbyBtn == null) return;
        boolean basicPicksOk = !selectedStyles.isEmpty() && !selectedBuildTypes.isEmpty();
        boolean allStylesUnlocked = true;
        Map<Style, Integer> counts = joinGate != null ? joinGate.getMatchCounts() : null;
        if (basicPicksOk && counts != null)
        {
            for (Style s : selectedStyles)
            {
                // Missing key in the counts map = "unknown" (gate hasn't
                // refreshed yet); treat as locked so the user doesn't
                // hit the lobby and bounce off the server's SMURF_GUARD.
                Integer c = counts.get(s);
                if (c == null || !LobbyJoinGate.isUnlocked(c))
                {
                    allStylesUnlocked = false;
                    break;
                }
            }
        }
        boolean ok = basicPicksOk && allStylesUnlocked;
        goToLobbyBtn.setEnabled(ok);
        // Disabled state: gray fill (Swing won't auto-fade the custom bg).
        goToLobbyBtn.setBackground(ok ? new Color(0x2e, 0x7d, 0x32) : new Color(0x55, 0x55, 0x55));
    }

    /** EDT callback fired by {@link LobbyJoinGate#addListener}. Re-renders
     *  the gate's status row + re-applies the style-toggle lock state +
     *  re-runs {@link #updateGoToLobbyEnabled()} so a refresh that flips
     *  a style from locked to unlocked (or vice-versa) immediately
     *  enables / disables Go-to-lobby. Also swaps the gate between
     *  "logged in" and "Please log into the game" views via
     *  {@link #applyLoginGateState()} — onLogin / onLogout transitions
     *  both fire through this listener. */
    private void onJoinGateChanged()
    {
        // Order matters: lock-state runs first so applyLoginGateState's
        // empty-picks branch sees post-cleanup selectedStyles. Without
        // this ordering a user whose persisted styles are all locked
        // would briefly land on CARD_LOBBY before getting redirected to
        // CARD_GATE on the next listener tick.
        applyStyleToggleLockState();
        applyLoginGateState();
        renderJoinGateStatus();
        updateGoToLobbyEnabled();
        // The self-name supplier returns null pre-login and then
        // non-null after; rebuild the preview row on every gate
        // event so the row appears the instant the user logs in.
        renderSelfPreview();
        // Auto-issue lobby/join after applyLoginGateState has decided
        // CARD_LOBBY — covers fresh-plugin-start where the WSLS
        // reconnect-replay cache is empty. Idempotent within a session.
        maybeAutoJoinAfterLogin();
    }

    /** Repaints {@link #gateMatchCountStatusLabel} + the [Refresh count]
     *  button's enabled state from the current {@link #joinGate}
     *  snapshot. The status label is HTML-wrapped so long
     *  "NH: 2 more · Multi: 20 more · …" lines wrap inside a 225px
     *  sidepanel. */
    private void renderJoinGateStatus()
    {
        if (gateMatchCountStatusLabel == null) return;
        Map<Style, Integer> counts = joinGate.getMatchCounts();
        boolean refreshing = joinGate.isRefreshing();

        StringBuilder remainingCsv = new StringBuilder();
        boolean anyUnknown = counts.isEmpty();
        boolean anyLocked = false;
        for (Style s : Style.values())
        {
            Integer c = counts.get(s);
            if (c == null)
            {
                anyUnknown = true;
                continue;
            }
            if (!LobbyJoinGate.isUnlocked(c))
            {
                anyLocked = true;
                int rem = LobbyJoinGate.remaining(c);
                if (remainingCsv.length() > 0) remainingCsv.append(" \u00b7 ");
                remainingCsv.append(s.label).append(": ").append(rem).append(" more");
            }
        }

        String headline = "You need " + LobbyJoinGate.THRESHOLD
            + " kills or deaths per style to queue.";
        String detail;
        if (anyUnknown && counts.isEmpty())
        {
            // Pre-login / pre-first-refresh state — gate hasn't seen
            // any counts yet. Show a low-key message instead of "all
            // styles locked" which would be alarming.
            detail = "Loading your match count\u2026";
        }
        else if (!anyLocked && !anyUnknown)
        {
            detail = "All styles unlocked.";
        }
        else
        {
            detail = remainingCsv.toString();
        }
        // 170px wrap target — the gate panel's inner content area
        // is narrower than the 225px sidepanel because of the
        // panel's left/right padding (~6px each side) + the
        // gateContent box's own insets. At 200px the headline
        // "You need 20 kills or deaths per style to queue." was
        // clipping the trailing "r style to queue." in the
        // sidepanel screenshot. 170 leaves ~10px slack against the
        // narrowest reasonable RuneLite sidepanel.
        gateMatchCountStatusLabel.setText("<html><div style='width:170px'>"
            + escapeHtml(headline) + "<br>" + escapeHtml(detail) + "</div></html>");

        if (gateMatchCountRefreshBtn != null)
        {
            // Mid-refresh: disable + relabel so the user sees the click
            // registered without the now-removed "Refreshing…" label.
            gateMatchCountRefreshBtn.setEnabled(!refreshing);
            gateMatchCountRefreshBtn.setText(refreshing ? "Refreshing\u2026" : "Refresh count");
        }
    }

    /** Disables the toggle for any style under {@link LobbyJoinGate#THRESHOLD},
     *  and force-deselects it if previously selected (so a user who
     *  passed the gate with NH selected, then dropped below 20 NH
     *  matches by some external means, doesn't ship a locked style to
     *  the server). Tooltip explains why.
     *
     *  <p><b>Logged-out / unknown count handling.</b> If {@link
     *  LobbyJoinGate#getMatchCounts()} returns an empty map (pre-login,
     *  during initial refresh, or post-logout) we DO NOT force-deselect
     *  — the user's picks are preserved so a logout → login round-trip
     *  doesn't silently wipe their setup. Toggles are still disabled
     *  while counts are unknown so the user can't change picks during
     *  the unknown window, but the existing selection is honoured.
     *  Force-deselect only happens when we have a real count below
     *  threshold (legitimate "you lost matches since you picked this
     *  style" path). */
    private void applyStyleToggleLockState()
    {
        if (styleToggles.isEmpty()) return;
        Map<Style, Integer> counts = joinGate.getMatchCounts();
        boolean countsUnknown = counts.isEmpty();
        for (Map.Entry<Style, JToggleButton> e : styleToggles.entrySet())
        {
            Style s = e.getKey();
            JToggleButton tog = e.getValue();
            Integer boxedCount = counts.get(s);
            boolean countKnown = boxedCount != null;
            // Defaulted to 0 when unknown so the unboxing is safe; the
            // value is only consumed when countKnown is true, so the
            // sentinel 0 never reaches user-visible UI.
            int count = boxedCount == null ? 0 : boxedCount.intValue();
            boolean unlocked = countKnown && LobbyJoinGate.isUnlocked(count);
            // Logged-out / pre-login: leave toggles enabled so the user
            // can pre-pick their styles before logging into the game.
            // The gateContent panel is hidden anyway via
            // applyLoginGateState() so these toggles aren't visible —
            // but if they're pre-picked here, they'll be honoured the
            // moment the user logs in.
            tog.setEnabled(unlocked || countsUnknown);

            if (countKnown && !unlocked)
            {
                int rem = LobbyJoinGate.remaining(count);
                tog.setToolTipText(s.label + " locked: " + count + "/"
                    + LobbyJoinGate.THRESHOLD + " (" + rem + " more needed)");
                // Count is known AND below threshold — force-deselect.
                // Server would otherwise reject lobby/join with
                // SMURF_GUARD.
                if (tog.isSelected())
                {
                    tog.setSelected(false);
                    selectedStyles.remove(s);
                }
            }
            else if (!countKnown)
            {
                // Don't tooltip-shame the user during the unknown
                // window; they can't action on it.
                tog.setToolTipText(null);
            }
            else
            {
                tog.setToolTipText(null);
            }
        }
    }

    /** Green-on-selected / red-on-unselected paint for the gate's
     *  style + build toggles. The selected state also gets a 2px
     *  green {@link javax.swing.border.LineBorder} outline so the
     *  "picked" state reads instantly without relying on the L&F's
     *  pressed-button shading (Substance under RuneLite paints both
     *  states with very similar greys; the previous toggle UI was
     *  confusing because users couldn't tell which styles they'd
     *  selected at a glance).
     *
     *  <p>Border thickness is offset by reduced inner padding so the
     *  toggle's overall width/height is identical in both states —
     *  no layout reflow on click. Disabled (locked) toggles still
     *  get the green/red palette; Substance's grey-out overlay
     *  composites on top, which makes the locked state read as
     *  "muted green/red" rather than ambiguous-grey. */
    private static void applyToggleVisualState(JToggleButton tog)
    {
        if (tog.isSelected())
        {
            tog.setForeground(SELECTED_TOGGLE_FG);
            tog.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(SELECTED_TOGGLE_FG, 2),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        }
        else
        {
            tog.setForeground(UNSELECTED_TOGGLE_FG);
            // Same total inset as the selected state (2px line + 2px
            // inner padding == 4px empty border) so flipping the
            // selection doesn't resize the button.
            tog.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }
    }

    /** Bright green that reads as "this style/build is picked".
     *  Matches the {@link #goToLobbyBtn}'s background hue family
     *  so the picked toggles + the active CTA share a palette. */
    private static final Color SELECTED_TOGGLE_FG = new Color(0x6c, 0xd1, 0x6a);

    /** Muted red for the unselected state — strong enough to read
     *  as "not picked" at a glance without screaming for attention
     *  the way a saturated red would. */
    private static final Color UNSELECTED_TOGGLE_FG = new Color(0xef, 0x53, 0x50);

    /** Minimal HTML escaping — only the four chars Swing's HTML view
     *  actually reinterprets. Inputs here are bounded enum labels +
     *  small integers so we don't need a full sanitiser. */
    private static String escapeHtml(String in)
    {
        if (in == null) return "";
        return in.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    /** Pulls the last persisted gate selections (region / styles / builds /
     *  rank-slider bounds / hasJoinedLobby) into the in-memory fields.
     *  Called once from the ctor BEFORE {@link #buildStyleGate()} so the
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
    private void applyPersistedPreferences()
    {
        selfRegion = prefs.getRegion(DEFAULT_REGION);

        Set<Style> persistedStyles = prefs.getStyles(EnumSet.of(Style.NH));
        if (!persistedStyles.isEmpty())
        {
            selectedStyles.clear();
            selectedStyles.addAll(persistedStyles);
        }

        Set<BuildType> persistedBuilds = prefs.getBuilds(EnumSet.allOf(BuildType.class));
        if (!persistedBuilds.isEmpty())
        {
            selectedBuildTypes.clear();
            selectedBuildTypes.addAll(persistedBuilds);
        }

        int min = clampRankIdx(prefs.getMinRankIdx(0));
        int max = clampRankIdx(prefs.getMaxRankIdx(RANK_LABELS.length - 1));
        // Defensive — a corrupt write could leave min > max. Swap
        // rather than reject so the user still sees a usable slider.
        if (min > max) { int t = min; min = max; max = t; }
        rankMinIdx = min;
        rankMaxIdx = max;

        hasJoinedLobby = prefs.getHasJoined();
    }

    /** Clamps {@code idx} into {@code [0, RANK_LABELS.length - 1]}. */
    private static int clampRankIdx(int idx)
    {
        if (idx < 0) return 0;
        if (idx >= RANK_LABELS.length) return RANK_LABELS.length - 1;
        return idx;
    }

    /** Snapshots the user's current gate picks (region / styles / builds /
     *  rank-slider bounds) into {@link #prefs}. Called from the gate
     *  widget action listeners after each mutation, AND from the
     *  Go-to-lobby click handler so the canonical "I'm in the lobby"
     *  payload is what's persisted. */
    private void saveGateSelections()
    {
        prefs.setRegion(selfRegion);
        prefs.setStyles(selectedStyles);
        prefs.setBuilds(selectedBuildTypes);
        prefs.setMinRankIdx(rankMinIdx);
        prefs.setMaxRankIdx(rankMaxIdx);
    }

    /** Clears all three gate picks (styles, account builds, region) and
     *  rebinds the gate widgets' visual state to match. The user must
     *  re-pick before Go-to-lobby re-enables. Called by [Reset Options] in
     *  the current-style bar; the caller is responsible for switching
     *  cards back to the gate. */
    private void resetGateOptions()
    {
        // Restore the same first-launch defaults: NH style + every
        // build. Matches the {@link #selectedStyles} / {@link
        // #selectedBuildTypes} field initialisers — keep these two
        // sites in lock-step. User can deselect immediately after a
        // reset, but they always start from a non-empty, lobby-ready
        // state so Go-to-lobby is enabled by default.
        selectedStyles.clear();
        selectedStyles.add(Style.NH);
        selectedBuildTypes.clear();
        for (BuildType bt : BuildType.values()) selectedBuildTypes.add(bt);
        selfRegion = DEFAULT_REGION;
        rankMinIdx = 0;
        rankMaxIdx = RANK_LABELS.length - 1;
        // Explicit "I want to re-pick" — clear the sticky lobby flag so
        // the next login doesn't bypass the gate, AND wipe the persisted
        // values so a plugin restart starts at the gate too.
        hasJoinedLobby = false;
        prefs.clear();

        for (Map.Entry<Style, JToggleButton> e : styleToggles.entrySet())
        {
            e.getValue().setSelected(selectedStyles.contains(e.getKey()));
        }
        for (Map.Entry<BuildType, JToggleButton> e : buildToggles.entrySet())
        {
            e.getValue().setSelected(selectedBuildTypes.contains(e.getKey()));
        }
        if (regionCombo != null) regionCombo.setSelectedIndex(indexOfRegion(DEFAULT_REGION));

        // Re-apply the lock state since clearing selection above also
        // cleared the disabled visual styling; without this the locked
        // styles look toggleable until the user clicks one and it
        // silently doesn't take.
        applyStyleToggleLockState();
        renderJoinGateStatus();
        refreshCurrentStyleLabel();
        updateGoToLobbyEnabled();
    }

    /** Returns the index in {@link #REGION_CODES} matching {@code code}, or 0 if missing. */
    private static int indexOfRegion(String code)
    {
        for (int i = 0; i < REGION_CODES.length; i++)
        {
            if (REGION_CODES[i].equals(code)) return i;
        }
        return 0;
    }

    /** Updates the "Build: X, Y / Style: A, B" label at the top of the lobby
     *  card to reflect the current {@link #selectedBuildTypes} and
     *  {@link #selectedStyles} sets. Rendered as HTML so each CSV wraps
     *  when it overflows the sidepanel width (NH+Veng+Multi+DMM doesn't fit
     *  on one 15pt-bold line at default 225px). Two-line layout (build above
     *  styles) keeps each label's prefix readable. */
    private void refreshCurrentStyleLabel()
    {
        if (currentStyleLabel == null) return;
        // "Any Style" / "Any Build" collapses the full CSV when the user
        // selected every option in the gate — reads cleaner than the
        // verbose "NH, Veng, Multi, DMM" on a 200px-wide wrap.
        String styleValue;
        if (selectedStyles.size() == Style.values().length)
        {
            styleValue = "Any Style";
        }
        else if (selectedStyles.isEmpty())
        {
            styleValue = "(none)";
        }
        else
        {
            StringBuilder styles = new StringBuilder();
            for (Style s : Style.values())
            {
                if (selectedStyles.contains(s))
                {
                    if (styles.length() > 0) styles.append(", ");
                    styles.append(s.label);
                }
            }
            styleValue = styles.toString();
        }
        String buildValue;
        if (selectedBuildTypes.size() == BuildType.values().length)
        {
            buildValue = "Any Build";
        }
        else if (selectedBuildTypes.isEmpty())
        {
            buildValue = "(none)";
        }
        else
        {
            StringBuilder builds = new StringBuilder();
            for (BuildType a : BuildType.values())
            {
                if (selectedBuildTypes.contains(a))
                {
                    if (builds.length() > 0) builds.append(", ");
                    builds.append(a.label);
                }
            }
            buildValue = builds.toString();
        }
        // Wrap target ~200px = sidepanel (225px) - bar inset (4px) - margin.
        // Inline style: muted prefix matches the prior gray; yellow span
        // matches the prior accent for each value.
        currentStyleLabel.setText(
            "<html><div style='width:200px'>"
                + "<span style='color:#aaaaaa'>Build: </span>"
                + "<span style='color:#ffc107'>" + buildValue + "</span><br/>"
                + "<span style='color:#aaaaaa'>Style: </span>"
                + "<span style='color:#ffc107'>" + styleValue + "</span>"
                + "</div></html>");

        // Self-preview row mirrors the same gate selections — repaint
        // its chips in lock-step so the user sees an instant "this is
        // what others see" update as they toggle styles/builds. Safe
        // before {@link #buildLobbyView}: the helper bails out when
        // the container hasn't been instantiated yet.
        renderSelfPreview();
    }

    /** Lobby card: presence + style indicator + filters NORTH; invites strip
     *  + roster scroll CENTER. The lobby fills the entire card vertically
     *  (no chat strip on the initial release). */
    private JPanel buildLobbyView()
    {
        JPanel lobby = new JPanel(new BorderLayout(0, 4));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JComponent presence = buildPresenceBar();
        presence.setAlignmentX(LEFT_ALIGNMENT);
        top.add(presence);
        top.add(leftAlignedStrut(4));

        JComponent currentStyle = buildCurrentStyleBar();
        currentStyle.setAlignmentX(LEFT_ALIGNMENT);
        top.add(currentStyle);
        top.add(leftAlignedStrut(4));

        // Self-profile preview — caption + a single PlayerRow built
        // from the gate selections. Re-rendered in place via
        // renderSelfPreview() whenever the user toggles a style /
        // build / region or the local OSRS identity changes.
        selfPreviewContainer = new JPanel();
        selfPreviewContainer.setLayout(new BoxLayout(selfPreviewContainer, BoxLayout.Y_AXIS));
        selfPreviewContainer.setAlignmentX(LEFT_ALIGNMENT);
        selfPreviewContainer.setOpaque(false);
        renderSelfPreview();
        top.add(selfPreviewContainer);
        top.add(leftAlignedStrut(4));

        JComponent filters = buildFilterRow();
        filters.setAlignmentX(LEFT_ALIGNMENT);
        top.add(filters);

        lobby.add(top, BorderLayout.NORTH);

        invitesContainer = new JPanel();
        invitesContainer.setLayout(new BoxLayout(invitesContainer, BoxLayout.Y_AXIS));
        invitesContainer.setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(0, 0, 1, 0, new Color(0x40, 0x40, 0x40)),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)));

        JComponent scroll = buildRosterScroll();
        JPanel center = new JPanel(new BorderLayout(0, 0));
        center.add(invitesContainer, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);

        // Seed invites used to be inlined here. They now arrive
        // asynchronously via {@link LobbyEventListener#onIncomingInvite}
        // after the user passes the gate.
        refreshInvitesContainer();

        lobby.add(center, BorderLayout.CENTER);
        return lobby;
    }

    /** Wire a callback to forward profile-row clicks to (e.g. the dashboard's
     *  {@code openPlayerLookup}). Safe to call before {@link #renderRoster()}
     *  because new rows pull the callback at construction time. */
    public void setOnOpenProfile(Consumer<String> cb)
    {
        this.onOpenProfile = cb;
    }

    /** Wire the local OSRS name supplier so the panel can render the
     *  "Your profile displayed to others" preview row above the rank
     *  slider. Read at every {@link #renderSelfPreview} call (i.e.
     *  lazily) so it picks up game-state transitions without needing
     *  a re-wire.
     *
     *  <p>Wired from {@code DashboardPanel} just like
     *  {@link #setOnOpenProfile}; not part of the constructor so
     *  existing test call sites (and the source-compat 1-arg ctor)
     *  don't need updating. */
    public void setSelfIdentity(Supplier<String> nameSup)
    {
        this.selfNameSupplier = nameSup;
        renderSelfPreview();
    }

    /** Wires the eager "is the local player in {@code GameState.LOGGED_IN}
     *  right now" signal — see {@link #isGameLoggedInSupplier} doc for
     *  why this is separate from {@link LobbyJoinGate#isLoggedIn()}.
     *  Triggers an immediate {@link #applyLoginGateState()} so the
     *  notice flips to "Loading\u2026" the instant the supplier
     *  reports true (which it might already, if the user opened the
     *  panel post-login). */
    public void setIsGameLoggedInSupplier(java.util.function.BooleanSupplier supplier)
    {
        this.isGameLoggedInSupplier = supplier;
        applyLoginGateState();
    }

    /** Public re-entry point for {@link #applyLoginGateState()} so the
     *  hosting plugin / dashboard can re-render the gate notice the
     *  instant a {@code GameStateChanged} event fires — the gate
     *  listener wired in this panel only fires after
     *  {@link LobbyJoinGate#onLogin()}, which is delayed 10 ticks for
     *  player-name resolve, so without an external poke from
     *  {@code GameState.LOGGED_IN} the "Loading\u2026" copy would
     *  never paint (the listener-driven re-render would already see
     *  {@code gateReady=true} and skip past it). EDT-only. Safe to
     *  call before {@link #setIsGameLoggedInSupplier} has wired the
     *  supplier. */
    public void refreshLoginGateView()
    {
        applyLoginGateState();
    }

    /** Rebuilds the contents of {@link #selfPreviewContainer} —
     *  caption + a single self-mode {@link PlayerRow} reflecting the
     *  user's currently advertised region / styles / builds. Hidden
     *  entirely when there's no name supplier wired or the name is
     *  null (pre-login).
     *
     *  <p>Called from {@link #setSelfIdentity}, after gate toggle
     *  changes (style / build / region), and from {@link
     *  #renderRoster()} so the preview chips stay in sync with the
     *  lobby's currentStyleLabel + the actual {@code lobby/join}
     *  payload the service most-recently emitted. */
    private void renderSelfPreview()
    {
        if (selfPreviewContainer == null) return;
        selfPreviewContainer.removeAll();

        String name = selfNameSupplier != null ? selfNameSupplier.get() : null;
        if (name == null || name.trim().isEmpty())
        {
            // Pre-login or pre-wire — leave the container empty (and
            // collapsed via the lack of children); the BoxLayout
            // parent skips zero-height children, so no visual gap.
            selfPreviewContainer.setVisible(false);
            selfPreviewContainer.revalidate();
            selfPreviewContainer.repaint();
            return;
        }
        selfPreviewContainer.setVisible(true);

        JLabel caption = new JLabel("Your profile displayed to others");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, (float) ROW_FONT_PT));
        caption.setForeground(new Color(0xaa, 0xaa, 0xaa));
        caption.setAlignmentX(LEFT_ALIGNMENT);
        caption.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        selfPreviewContainer.add(caption);

        // Self-preview LobbyMember.playerId is the lowercased display
        // name purely as a stable in-process identifier — this
        // synthetic member never crosses the wire (the server filters
        // the viewer's own row out of every roster push), so the
        // value only has to be non-null + non-empty for the renderer.
        String selfPlayerId = name != null && !name.isEmpty() ? name.toLowerCase() : "self";
        // Pick the displayed rank by Style priority NH > Veng > Multi
        // > DMM among the user's currently-selected styles (Style
        // enum declaration order matches the priority). Falls back
        // to "any style with a known rank" if none of the selected
        // styles has a rank yet (user picked styles they haven't
        // played) so the preview still shows something meaningful
        // pre-first-match. peakRankIdx = -1 remains the "unknown"
        // sentinel; PlayerRow then suppresses the rank chip rather
        // than mis-rendering as Bronze 3.
        int peakIdx = pickSelfPreviewRankIdx();
        // Self preview is display-only — it never enters the slider
        // matchmaking gate (the user's own row is hidden from rosters
        // server-side and the gate excludes self). Pass currentRankIdx
        // = peakIdx so any code that reads .currentRankIdx on the self
        // row gets a sensible non-sentinel value.
        LobbyMember self = new LobbyMember(
            selfPlayerId,
            name,
            selectedStyles,
            selectedBuildTypes,
            /* currentRankIdx */ peakIdx,
            /* peakRankIdx */ peakIdx,
            selfRegion,
            false);

        selfPreviewRow = new PlayerRow(self, /*fontBase=*/ ROW_FONT_PT,
            /*isInvited=*/ m -> false,
            /*getOutgoing=*/ m -> null,
            this::routeOpenProfile,
            /*onFight=*/ null,
            /*onCancelInvite=*/ null,
            /*isBlocked=*/ m -> false,
            /*selfPreview=*/ true);
        selfPreviewRow.setAlignmentX(LEFT_ALIGNMENT);
        selfPreviewContainer.add(selfPreviewRow);

        selfPreviewContainer.revalidate();
        selfPreviewContainer.repaint();
    }

    /** Picks the rank index to display on the self-preview row given
     *  the user's current style picks. Priority is NH &gt; Veng &gt;
     *  Multi &gt; DMM (matches {@link Style} declaration order); the
     *  first selected style with a known rank wins. If none of the
     *  selected styles has a known rank, falls back to the
     *  highest-known rank across <i>any</i> style so a brand-new
     *  user who hasn't played their selected style yet still sees
     *  something meaningful instead of a blank row. Returns -1 if
     *  the gate has no rank data at all (pre-login / pre-first-fetch)
     *  — PlayerRow honours that as "unknown" and skips the chip. */
    private int pickSelfPreviewRankIdx()
    {
        if (joinGate == null) return -1;
        Map<Style, Integer> ranks = joinGate.getRankIdxByStyle();
        if (ranks == null || ranks.isEmpty()) return -1;
        // Style.values() is declared NH, VENG, MULTI, DMM — the same
        // ordering the user spec'd, so a single pass over the enum
        // gives us the right priority without a separate lookup
        // table. selectedStyles guards against showing a rank for a
        // style the user isn't even advertising.
        for (Style s : Style.values())
        {
            if (!selectedStyles.contains(s)) continue;
            Integer idx = ranks.get(s);
            if (idx != null && idx >= 0) return idx;
        }
        // Fallback — no selected style had a rank. Pick the highest
        // known rank across all styles so new users who haven't
        // played their selected style yet still see their best
        // overall rank instead of an empty chip.
        int best = -1;
        for (Style s : Style.values())
        {
            Integer idx = ranks.get(s);
            if (idx != null && idx > best) best = idx;
        }
        return best;
    }

    // -------------------- Fight setup flow --------------------
    //
    // Sender path:
    //   [Fight] click -> Pick Style -> (sub-loc if NH/Multi) -> submitOutgoingInvite()
    //   -> back to LOBBY with [Invited M:SS] chip on opponent's row
    //   -> opponent accepts (server push) -> ConfirmFight view
    //   -> user clicks Confirm -> Waiting view (or MeetAt if opponent already confirmed)
    //   -> opponent confirms (server push) -> MeetAt
    //
    // Receiver path:
    //   IncomingInvitePanel "Accept Fight" -> ConfirmFight view (skips Pick Style)
    //   -> rest is identical to sender path
    //
    // Termination paths (all clear the [Invited] block + currentFightSession):
    //   - Both confirm -> MeetAt -> Find a new match -> LOBBY
    //   - 30s confirm window expires -> LOBBY
    //   - Find a new match clicked from any FIGHT view -> LOBBY
    //   - 10-min original invite TTL elapses (only meaningful in INVITED state)

    private boolean isPlayerInvited(LobbyMember p)
    {
        if (p == null || p.name == null) return false;
        // [Lookup] chip follows the same rank-range gate as the invite card —
        // if the sender is outside the user's slider range, the user
        // shouldn't see any indication of the invite (card + chip both hidden).
        return incomingInviteNames.contains(p.name) && rankInRange(p);
    }

    /** User-facing display name for a roster row / invite card.
     *  Prefers the display-cased {@link LobbyMember#name}, falls back
     *  to the canonical {@link LobbyMember#playerId} (lowercased
     *  form) if the display name is empty, and finally {@code
     *  "Unknown"} only if both are empty. */
    private static String displayNameOf(LobbyMember m)
    {
        if (m == null) return "";
        String n = m.name;
        if (n != null && !n.isEmpty()) return n;
        String pid = m.playerId;
        if (pid != null && !pid.isEmpty()) return pid;
        return "Unknown";
    }

    /** Returns the active outgoing invite to {@code p} if one is pending
     *  acceptance (drives the [Invited M:SS] row chip). Null otherwise. */
    private OutgoingInvite getOutgoingInvite(LobbyMember p)
    {
        if (p == null || p.playerId == null || p.playerId.isEmpty()) return null;
        return outgoingInvitesByOpponent.get(p.playerId);
    }

    private void routeOpenProfile(String name)
    {
        if (onOpenProfile != null) onOpenProfile.accept(name);
    }

    /** Style sub-location options. NH and Multi require a sub-location pick;
     *  Veng / DMM are world-only. */
    private static final String[] NH_LOCATIONS = {"Arena", "Wildy", "FFA Portal"};
    private static final String[] MULTI_LOCATIONS = {"Wilderness", "Clan Wars"};

    /** Sender flow entry — clicked [Fight] on {@code opponent}. Opens the
     *  full-screen Pick Style step. */
    private void onFightClicked(LobbyMember opponent)
    {
        if (opponent == null || opponent.playerId == null) return;
        if (!fightAllowed(opponent)) return;
        if (outgoingInvitesByOpponent.containsKey(opponent.playerId)) return; // already invited; chip should have been [Invited]
        scrollRosterToTop();
        showFightSetup(buildPickStyleView(opponent));
    }

    /** Common card-swap path. {@link #wrapInScroll(JPanel)} always returns
     *  a fresh JScrollPane so the default scrollbar value is 0, but we
     *  also snap the viewport explicitly after the layout pass — defensive
     *  cover against any PLAF that initialises the viewport to a non-zero
     *  position based on the previous card's geometry. */
    private void showFightSetup(JComponent view)
    {
        fightSetupContainer.removeAll();
        fightSetupContainer.add(view, BorderLayout.CENTER);
        fightSetupContainer.revalidate();
        fightSetupContainer.repaint();
        rootCards.show(rootCardHost, CARD_FIGHT);
        if (view instanceof JScrollPane)
        {
            final JScrollPane sp = (JScrollPane) view;
            SwingUtilities.invokeLater(() ->
            {
                JViewport vp = sp.getViewport();
                if (vp != null) vp.setViewPosition(new Point(0, 0));
            });
        }
    }

    /** Cleans up any in-flight FIGHT session + outgoing invite for the same
     *  opponent (Find-a-new-match exit, 30s expiry, both-confirmed exit, etc.)
     *  and returns the user to the lobby. The server's session TTL keeps
     *  running server-side; the panel just drops its local state and
     *  ignores the late {@link #onFightConfirmedByPeer onFightConfirmedByPeer}
     *  / {@link #onMatchFound onMatchFound} push since
     *  {@code currentFightSession} is already null. */
    private void exitFightSetup()
    {
        if (currentFightSession != null)
        {
            // Per spec: any FIGHT-view exit clears the 10-min block on that opponent
            // (only the natural 10-min TTL holds the block). Cancel any matching
            // outgoing invite that may still be in INVITED state too.
            cancelOutgoingInvite(currentFightSession.opponent.playerId, false);
            currentFightSession = null;
        }
        rootCards.show(rootCardHost, CARD_LOBBY);
        renderRoster();
    }

    /** Pick a fight style for {@code opponent}. Same visual treatment as the
     *  pre-lobby Style Gate. Greyed buttons for styles the opponent hasn't
     *  advertised. */
    private JComponent buildPickStyleView(LobbyMember opponent)
    {
        JPanel card = newGateLikeCard();
        card.add(makeGateHeader("Opponent"));
        card.add(leftAlignedStrut(8));
        // Player-name line uses the same big-bold treatment as headers per
        // request — it is the data, not a sub-caption.
        card.add(makeGateHeader(opponent.name));
        card.add(leftAlignedStrut(14));
        card.add(makeGateHeader("Pick a style"));
        card.add(leftAlignedStrut(8));

        for (Style s : Style.values())
        {
            boolean advertised = opponent.styles.contains(s);
            JButton btn = makeGateActionButton(s.label, advertised);
            if (advertised)
            {
                btn.addActionListener(e -> onStyleChosen(opponent, s));
            }
            card.add(btn);
            card.add(leftAlignedStrut(4));
        }

        card.add(leftAlignedStrut(12));
        card.add(makeGateCancelButton("Find a new match", this::exitFightSetup));
        return wrapInScroll(card);
    }

    private void onStyleChosen(LobbyMember opponent, Style style)
    {
        if (style == Style.NH)
        {
            showFightSetup(buildPickLocationView(opponent, style, "Pick where to fight (NH)", NH_LOCATIONS));
        }
        else if (style == Style.MULTI)
        {
            showFightSetup(buildPickLocationView(opponent, style, "Pick venue (Multi)", MULTI_LOCATIONS));
        }
        else
        {
            // Veng / DMM are world-only — straight to Pick Build (no sub-loc).
            showFightSetup(buildPickBuildView(opponent, style, null));
        }
    }

    /** Sub-location picker reused for NH (Arena/Wildy/FFA Portal) and Multi
     *  (Wilderness/Clan Wars). Veng / DMM bypass this step entirely. */
    private JComponent buildPickLocationView(LobbyMember opponent, Style style, String header, String[] locations)
    {
        JPanel card = newGateLikeCard();
        card.add(makeGateHeader("Opponent"));
        card.add(leftAlignedStrut(8));
        // Big-bold name; chosen style is implied by the next "Pick where to
        // fight (NH)" / "Pick venue (Multi)" header below.
        card.add(makeGateHeader(opponent.name));
        card.add(leftAlignedStrut(14));
        card.add(makeGateHeader(header));
        card.add(leftAlignedStrut(8));

        for (String loc : locations)
        {
            final String chosen = loc;
            JButton btn = makeGateActionButton(loc, true);
            btn.addActionListener(e -> showFightSetup(buildPickBuildView(opponent, style, chosen)));
            card.add(btn);
            card.add(leftAlignedStrut(4));
        }

        card.add(leftAlignedStrut(12));
        card.add(makeGateCancelButton("Find a new match", this::exitFightSetup));
        return wrapInScroll(card);
    }

    /** Build picker — final step before the invite is submitted. Filters by
     *  the opponent's advertised builds (mirroring {@link #buildPickStyleView}'s
     *  filter on advertised styles): the sender can only request a build
     *  the opponent actually plays. Greyed-out buttons for builds the
     *  opponent doesn't advertise. */
    private JComponent buildPickBuildView(LobbyMember opponent, Style style, String location)
    {
        JPanel card = newGateLikeCard();
        card.add(makeGateHeader("Opponent"));
        card.add(leftAlignedStrut(8));
        card.add(makeGateHeader(opponent.name));
        card.add(leftAlignedStrut(14));
        card.add(makeGateHeader("Pick a build"));
        card.add(leftAlignedStrut(8));

        for (BuildType a : BuildType.values())
        {
            boolean advertised = opponent.builds.contains(a);
            JButton btn = makeGateActionButton(a.label, advertised);
            if (advertised)
            {
                btn.addActionListener(e -> submitOutgoingInvite(opponent, style, a, location));
            }
            card.add(btn);
            card.add(leftAlignedStrut(4));
        }

        card.add(leftAlignedStrut(12));
        card.add(makeGateCancelButton("Find a new match", this::exitFightSetup));
        return wrapInScroll(card);
    }

    /** Sender flow terminal step — invite is created (locally and via
     *  {@link LobbyService#sendInvite}), the user is dropped back into the
     *  lobby, and the opponent's row chip flips to [Invited M:SS]. The
     *  opponent's eventual acceptance arrives asynchronously via
     *  {@link #onFightProposed} (the backend pushes when the opponent
     *  clicks Accept). */
    private void submitOutgoingInvite(LobbyMember opponent, Style style, BuildType build, String location)
    {
        if (opponent == null || style == null || build == null) return;
        if (opponent.playerId == null || opponent.playerId.isEmpty()) return;
        // Defensive: don't double-invite. The service is also idempotent
        // per the LobbyService contract, but skipping the call here avoids
        // a redundant socket round-trip.
        if (outgoingInvitesByOpponent.containsKey(opponent.playerId)) return;

        // Local tracking record for the [Invited M:SS] chip + the
        // 10-min client-side TTL countdown. Invite-id is locally-minted
        // here; when the service's ack comes back the real
        // server-assigned id replaces it.
        long now = System.currentTimeMillis();
        final OutgoingInvite oi = new OutgoingInvite(
            "local-" + UUID.randomUUID(), opponent, style, build, location,
            now, now + FIGHT_INVITE_TTL_MS);
        outgoingInvitesByOpponent.put(opponent.playerId, oi);

        // Stash the target so onError() can correlate a
        // PEER_NOT_IN_LOBBY response back to this exact row. A row
        // re-entering the visible roster after a fresh server snapshot
        // will clear this — see onRosterSnapshot().
        lastInviteTargetPlayerId = opponent.playerId;
        lastInviteSentAtMs = now;

        service.sendInvite(opponent, style, build, location);

        rootCards.show(rootCardHost, CARD_LOBBY);
        renderRoster();
    }

    /** User clicked [Invited M:SS] on a row to abort their pending invite.
     *  Drops the local tracking record (so the chip flips back to [Fight])
     *  and asks the service to cancel the invite server-side.
     *
     *  <p>Keyed by canonical {@code player_id} (not display name) —
     *  display names can collide across peers (case-insensitive
     *  matches, leading/trailing whitespace edge cases) but
     *  {@code player_id} is the canonical lowercase form the server
     *  derives from {@code canon_name()} and uses as the wire key
     *  for {@code lobby/invite} / {@code lobby/cancel_invite}.
     *  Keying by it here keeps panel state in lockstep with what
     *  the server will accept for the cancel cmd. */
    private void cancelOutgoingInvite(String opponentPlayerId, boolean rerender)
    {
        if (opponentPlayerId == null || opponentPlayerId.isEmpty()) return;
        OutgoingInvite oi = outgoingInvitesByOpponent.remove(opponentPlayerId);
        if (oi != null) service.cancelInvite(oi.opponent);
        if (rerender) renderRoster();
    }

    /** User clicked Confirm Fight in the ConfirmFight view. Marks local
     *  state {@code iConfirmed=true}, fires the service confirm, and
     *  optimistically renders the Waiting view. If the peer had already
     *  confirmed, {@link #onMatchFound} fires shortly after, at which
     *  point the listener swaps the view to MeetAt — the
     *  {@code if (currentFightSession != null)} guard below
     *  avoids double-rendering the Waiting view in that case. */
    private void onUserConfirmedFight()
    {
        LocalFightState s = currentFightSession;
        if (s == null) return;
        s.iConfirmed = true;
        service.confirmFight();
        if (currentFightSession != null && !s.bothConfirmed())
        {
            showFightSetup(buildWaitingView());
        }
    }

    /** Live label on the FIGHT card that the ticker rewrites every second. */
    private JLabel fightCountdownLabel;

    /** Confirm Fight view — both sides land here when mutual-confirm starts.
     *  Shows a big [Confirm Fight] button and the live 30s countdown. If the
     *  opponent has already confirmed, an extra subheader makes that visible. */
    private JComponent buildConfirmFightView()
    {
        LocalFightState s = currentFightSession;
        JPanel card = newGateLikeCard();
        card.add(makeGateHeader("Confirm fight"));
        card.add(leftAlignedStrut(8));
        addOpponentLines(card, s);
        if (s != null && s.peerConfirmed)
        {
            card.add(leftAlignedStrut(6));
            card.add(makeGateBigSubHeader("Confirmed by other player"));
        }
        card.add(leftAlignedStrut(14));

        JButton confirm = makeGateActionButton("Confirm Fight", true);
        confirm.addActionListener(e -> onUserConfirmedFight());
        card.add(confirm);
        card.add(leftAlignedStrut(8));

        fightCountdownLabel = makeGateBigSubHeader(formatRemaining(s));
        card.add(fightCountdownLabel);
        card.add(leftAlignedStrut(12));

        card.add(makeGateCancelButton("Find a new match", this::exitFightSetup));
        return wrapInScroll(card);
    }

    /** Waiting view — user has confirmed; shown until the opponent does too
     *  or the 30s window expires. */
    private JComponent buildWaitingView()
    {
        LocalFightState s = currentFightSession;
        JPanel card = newGateLikeCard();
        card.add(makeGateHeader("Waiting on other player to confirm"));
        card.add(leftAlignedStrut(8));
        addOpponentLines(card, s);
        card.add(leftAlignedStrut(14));

        fightCountdownLabel = makeGateBigSubHeader(formatRemaining(s));
        card.add(fightCountdownLabel);
        card.add(leftAlignedStrut(12));

        card.add(makeGateCancelButton("Find a new match", this::exitFightSetup));
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
    private JComponent buildMeetAtView(MatchInfo match)
    {
        LocalFightState s = currentFightSession;
        JPanel card = newGateLikeCard();
        card.add(makeGateHeader("Fight ready"));
        card.add(leftAlignedStrut(8));
        addOpponentLines(card, s);
        card.add(leftAlignedStrut(14));

        String worldText = match != null && match.world != null && !match.world.isEmpty()
            ? match.world : "TBD";
        String meetingPlaceText = match != null && match.meetingPlace != null && !match.meetingPlace.isEmpty()
            ? match.meetingPlace
            : (s == null ? "TBD" : meetAtPlace(s.style, s.location));

        card.add(makeMeetAtRow("World:", worldText));
        card.add(leftAlignedStrut(6));
        card.add(makeMeetAtRow("Meet at:", meetingPlaceText));
        card.add(leftAlignedStrut(14));

        // Per spec: this screen also has a Find-a-new-match exit; auto-return
        // on real fight submission is a future hook tied to the in-game match
        // submission pipeline (TODO meet-at-auto-return).
        card.add(makeGateCancelButton("Find a new match", this::exitFightSetup));
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
        card.add(makeGateHeader(s.opponent.name));
        card.add(leftAlignedStrut(2));
        card.add(makeGateBigSubHeader(formatStyleBuildPlace(s.style, s.build, s.location)));
    }

    /** "Style - Build @ Place" shared formatter. Used by the FightSession
     *  views (Confirm / Waiting / MeetAt opponent line) and the incoming
     *  invite card's info row, so both surfaces read identically. Falls
     *  back through {@link #inviteLocationLabel(Style, String)} for
     *  Veng / DMM (no sub-loc picker, so the location reads as a generic
     *  "PvP World" / "DMM World" instead of being blank). */
    private static String formatStyleBuildPlace(Style style, BuildType build, String location)
    {
        StringBuilder sb = new StringBuilder(style.label);
        if (build != null) sb.append(" - ").append(build.label);
        String loc = inviteLocationLabel(style, location);
        if (!loc.isEmpty()) sb.append(" @ ").append(loc);
        return sb.toString();
    }

    /** Display name for the @-place portion of an invite info row.
     *  Returns the sub-location verbatim when one was picked (NH / Multi),
     *  otherwise the per-style default — "PvP World" for Veng, "DMM World"
     *  for DMM. Empty string for unknown style+null-location combos so the
     *  caller can omit the "@ ..." segment. */
    private static String inviteLocationLabel(Style style, String location)
    {
        if (location != null && !location.isEmpty()) return location;
        if (style == Style.VENG) return "PvP World";
        if (style == Style.DMM) return "DMM World";
        return "";
    }

    private static String formatRemaining(LocalFightState s)
    {
        if (s == null) return "";
        long remainMs = Math.max(0L, s.confirmExpiresAt - System.currentTimeMillis());
        long secs = (remainMs + 999L) / 1000L;
        return "0:" + (secs < 10 ? "0" + secs : Long.toString(secs)) + " remaining";
    }

    /** Format the remaining time on an outgoing invite as M:SS for the row chip. */
    private static String formatInviteRemaining(OutgoingInvite oi)
    {
        long remainMs = Math.max(0L, oi.expiresAtEpochMs - System.currentTimeMillis());
        long totalSecs = (remainMs + 999L) / 1000L;
        long mins = totalSecs / 60L;
        long secs = totalSecs % 60L;
        return "Invited " + mins + ":" + (secs < 10 ? "0" + secs : Long.toString(secs));
    }

    /** 1Hz tick: expires INVITED outgoing invites whose 10-min TTL has run out
     *  (refreshes affected rows), updates the FIGHT card's 30s countdown
     *  label, and force-exits the FIGHT card if the confirm window elapsed
     *  without both confirmations. */
    private void onFightTick()
    {
        long now = System.currentTimeMillis();

        boolean rosterDirty = false;
        Iterator<Map.Entry<String, OutgoingInvite>> it = outgoingInvitesByOpponent.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, OutgoingInvite> e = it.next();
            if (now >= e.getValue().expiresAtEpochMs)
            {
                // Local-side TTL expiry — ask the service to cancel
                // its own pending state too (idempotent).
                service.cancelInvite(e.getValue().opponent);
                it.remove();
                rosterDirty = true;
            }
        }
        // Even if no invite expired, the M:SS countdown text needs to refresh
        // each tick. Cheaper than rebuilding the whole roster: re-render only
        // rows that actually have an active outgoing invite.
        if (rosterDirty)
        {
            renderRoster();
        }
        else if (!outgoingInvitesByOpponent.isEmpty())
        {
            refreshRowsWithOutgoingInvites();
        }

        LocalFightState s = currentFightSession;
        if (s != null)
        {
            if (now >= s.confirmExpiresAt && !s.bothConfirmed())
            {
                exitFightSetup();
            }
            else if (fightCountdownLabel != null)
            {
                fightCountdownLabel.setText(formatRemaining(s));
            }
        }
    }

    /** Walks {@link #rosterContainer} and re-renders only rows whose player
     *  has an active outgoing invite. Cheap (typically 0–1 rows) and avoids a
     *  full {@link #renderRoster()} every tick. */
    private void refreshRowsWithOutgoingInvites()
    {
        if (rosterContainer == null) return;
        for (java.awt.Component c : rosterContainer.getComponents())
        {
            if (c instanceof PlayerRow)
            {
                PlayerRow row = (PlayerRow) c;
                if (row.p != null && row.p.playerId != null
                    && outgoingInvitesByOpponent.containsKey(row.p.playerId))
                {
                    row.render();
                }
            }
        }
    }

    // -------------------- Presence coalescer (60-s roster refresh) --------------------

    /** Tick handler for the 60-s roster coalescer. Commits the latest
     *  pending presence snapshot to the visible roster and re-renders.
     *  No-op if no presence push has landed since the last tick. */
    private void applyPendingRosterUpdate()
    {
        if (pendingRoster == null || pendingRoster == roster) return;
        roster.clear();
        roster.addAll(pendingRoster);
        // Re-alias so the next tick is a no-op until onRosterSnapshot
        // stages a new snapshot.
        pendingRoster = roster;
        renderRoster();
    }

    // -------------------- LobbyEventListener --------------------

    /** {@inheritDoc}
     *
     *  <p>Commits the server snapshot immediately so another player's
     *  card appears as soon as {@code lobby/roster} lands — the 60-s
     *  {@link #rosterRefreshTicker} is only a backstop for any future
     *  code path that stages into {@link #pendingRoster} without
     *  calling {@link #applyPendingRosterUpdate()}. Defensive-copies the
     *  list so the caller can keep mutating its own collection. */
    @Override
    public void onRosterSnapshot(List<LobbyMember> snapshot)
    {
        if (snapshot == null) return;
        // A fresh authoritative snapshot supersedes any client-side
        // staleness assumptions: if the server still has a row for a
        // previously-marked-stale peer, either (a) the peer reconnected
        // and is genuinely back, or (b) the row is still there but
        // the underlying connection is still dead — in case (b) the
        // user will get another PEER_NOT_IN_LOBBY on the next invite
        // attempt and we'll re-stale them. Either way: don't carry
        // staleness across snapshots.
        recentlyStalePlayerIds.clear();
        this.pendingRoster = new ArrayList<>(snapshot);
        applyPendingRosterUpdate();
    }

    @Override
    public void onIncomingInvite(IncomingInvite invite)
    {
        if (invite == null) return;
        // Spec'd filter: only show invite cards from senders whose rank
        // sits inside the user's [rankMinIdx, rankMaxIdx] slider band.
        // The panel re-applies the same rule the PlayerRow renderer uses
        // for the lobby roster, so a push that arrives just before the
        // user widens their slider doesn't sneak past the filter.
        // Matchmaking decisions key on **current** rank (currentRankIdx);
        // peakRankIdx is display-only and never gates the slider band.
        if (invite.sender != null && rankIdxKnown(invite.sender.currentRankIdx)
            && (invite.sender.currentRankIdx < rankMinIdx
                || invite.sender.currentRankIdx > rankMaxIdx)) return;
        addIncomingInvite(invite);
    }

    @Override
    public void onIncomingInviteCancelled(String inviteId)
    {
        // Server pushes this on four cascades: (a) sender cancelled,
        // (b) receiver declined (push to sender), (c) block-cascade,
        // (d) leave-cascade. The inviteId is the only stable correlation
        // key — sender / receiver perspectives both resolve here.
        if (inviteId == null || inviteId.isEmpty()) return;

        // ---- Receiver perspective: cancel an incoming card we showed ----
        IncomingInvitePanel card = incomingCardsById.remove(inviteId);
        if (card != null)
        {
            // Rebuild incomingInviteNames from the remaining cards so
            // the cancelled sender's row flips back to [Fight] on the
            // next renderRoster(). O(n) on a typically-small list
            // (< ~10 invites visible at once); avoids the bookkeeping
            // bug of trying to track names + ids in parallel.
            incomingInviteNames.clear();
            for (IncomingInvitePanel remaining : incomingCardsById.values())
            {
                LobbyMember s = senderOfCard(remaining);
                if (s != null) incomingInviteNames.add(s.name);
            }
            removeInvite(card);
            renderRoster();
            return;
        }

        // ---- Sender perspective: opponent declined / cascaded our outgoing ----
        // OutgoingInvites are keyed by opponent player_id in the
        // panel's map; look up by inviteId via a scan (small map, < ~10
        // entries).
        String opponentPlayerId = null;
        for (Map.Entry<String, OutgoingInvite> e : outgoingInvitesByOpponent.entrySet())
        {
            OutgoingInvite oi = e.getValue();
            if (oi != null && inviteId.equals(oi.inviteId))
            {
                opponentPlayerId = e.getKey();
                break;
            }
        }
        if (opponentPlayerId != null)
        {
            // Inline rather than calling cancelOutgoingInvite() because
            // that helper echoes service.cancelInvite() back to the
            // server — but in the cascade path the cancel ORIGINATED
            // server-side, so echoing it back would risk a benign-but-
            // noisy unknown-invite_id log on the server. Drop the local
            // record + re-render the roster directly.
            outgoingInvitesByOpponent.remove(opponentPlayerId);
            renderRoster();
        }
    }

    /** Reverse-lookup of an {@link IncomingInvitePanel}'s sender for
     *  {@link #onIncomingInviteCancelled}'s name-set rebuild path.
     *  Returns {@code null} only when the card itself is null. */
    private static LobbyMember senderOfCard(IncomingInvitePanel card)
    {
        return card != null ? card.getSender() : null;
    }

    /** {@inheritDoc}
     *
     *  <p>Fired for both flows: (a) sender — opponent accepted our invite,
     *  drop the [Invited M:SS] chip and swap to ConfirmFight; (b) receiver
     *  — we just clicked Accept on an incoming card, server promoted us
     *  to mutual-confirm. Either way the lobby is hidden until the user
     *  exits via Find-a-new-match, the 30s window expires
     *  ({@link #onFightSessionExpired}), or both sides confirm
     *  ({@link #onMatchFound}). */
    @Override
    public void onFightProposed(FightSession session)
    {
        if (session == null || session.opponent == null) return;
        // Drop any local outgoing-invite tracking for this opponent — the
        // invite has been promoted into a real fight session and the
        // [Invited M:SS] chip should disappear from the lobby roster.
        if (session.opponent.playerId != null)
        {
            outgoingInvitesByOpponent.remove(session.opponent.playerId);
        }
        // Also drop any incoming-invite chip-state we may still be tracking.
        // The card itself was already torn down by addIncomingInvite()'s
        // Accept runnable; this is defensive cleanup for the
        // race-where-server-promotes-without-our-click case. FightSession
        // doesn't carry inviteId so scan by opponent name to drop the
        // stale card from incomingCardsById.
        incomingInviteNames.remove(session.opponent.name);
        incomingCardsById.entrySet().removeIf(e ->
        {
            LobbyMember s = senderOfCard(e.getValue());
            return s != null && s.name != null && s.name.equals(session.opponent.name);
        });

        currentFightSession = new LocalFightState(session);
        // showFightSetup() already snaps the JScrollPane viewport to (0,0)
        // post-layout, so no extra scroll-to-top is needed here.
        showFightSetup(buildConfirmFightView());
    }

    @Override
    public void onFightConfirmedByPeer(String fightSessionId)
    {
        LocalFightState s = currentFightSession;
        if (s == null) return;
        if (s.session.fightSessionId != null
            && !s.session.fightSessionId.equals(fightSessionId)) return;
        s.peerConfirmed = true;
        // If we already confirmed too, server will follow up with
        // onMatchFound — leave the view in Waiting until that lands so
        // the MeetAt swap stays a single transition.
        if (!s.iConfirmed)
        {
            showFightSetup(buildConfirmFightView());
        }
    }

    @Override
    public void onMatchFound(MatchInfo match)
    {
        if (match == null) return;
        LocalFightState s = currentFightSession;
        if (s == null) return;
        if (s.session.fightSessionId != null
            && !s.session.fightSessionId.equals(match.fightSessionId)) return;
        // Both sides confirmed — MeetAt is terminal until Find-a-new-match.
        // Server-resolved world + meeting_place travel through `match`
        // so the view can render them verbatim (see buildMeetAtView).
        showFightSetup(buildMeetAtView(match));
    }

    @Override
    public void onFightSessionExpired(String fightSessionId)
    {
        LocalFightState s = currentFightSession;
        if (s == null) return;
        if (s.session.fightSessionId != null
            && !s.session.fightSessionId.equals(fightSessionId)) return;
        // Mirror exitFightSetup() but without the cancelInvite — the
        // session is already torn down server-side.
        currentFightSession = null;
        rootCards.show(rootCardHost, CARD_LOBBY);
        renderRoster();
    }

    @Override
    public void onError(String code, String message)
    {
        // Localized via LobbyErrorMessages — never display the raw
        // server message; it's English-only debug text that may change
        // without notice. Unknown codes get a generic fallback so a
        // server that adds a new code without a plugin release degrades
        // gracefully.
        showErrorBanner(LobbyErrorMessages.forCode(code));

        // PEER_NOT_IN_LOBBY arriving on the heels of a fresh
        // submitOutgoingInvite() means the row we just clicked is dead
        // (the OSRS-LobbyMembers row exists but the underlying socket
        // disconnected without a graceful lobby/leave — see the
        // recentlyStalePlayerIds field doc for the systemic backend
        // gap). Pull the local outgoing invite back so the chip
        // doesn't park at [Invited M:SS] for the full 10-min TTL, and
        // hide that row from the renderer until the next authoritative
        // roster snapshot. Other error codes (BLOCKED,
        // RANK_OUT_OF_RANGE, DUPLICATE_INVITE, etc.) leave the row
        // alone — the row IS still valid, just the action is gated.
        if ("PEER_NOT_IN_LOBBY".equals(code)
            && lastInviteTargetPlayerId != null
            && (System.currentTimeMillis() - lastInviteSentAtMs) <= STALE_CORRELATION_WINDOW_MS)
        {
            String stalePlayerId = lastInviteTargetPlayerId;
            lastInviteTargetPlayerId = null;
            recentlyStalePlayerIds.add(stalePlayerId);
            // Drop the now-orphaned [Invited M:SS] tracking record so
            // the chip flips back to [Fight] for any future re-render.
            // Pass rerender=false because we re-render below ourselves
            // after also pruning from `roster` so the row vanishes in
            // a single repaint instead of two.
            cancelOutgoingInvite(stalePlayerId, false);
            renderRoster();
        }
    }

    /**
     * Builds the top-of-panel error banner. Red-tinted strip with the
     * localized message + a [×] dismiss button on the right. Hidden by
     * default; {@link #showErrorBanner} flips visibility + starts the
     * auto-dismiss timer.
     */
    private JPanel buildErrorBanner()
    {
        JPanel banner = new JPanel();
        banner.setLayout(new BoxLayout(banner, BoxLayout.X_AXIS));
        banner.setBackground(new Color(0x6e, 0x1f, 0x1f));
        banner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x40, 0x10, 0x10)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        errorBannerLabel = new JLabel(" ");
        errorBannerLabel.setForeground(new Color(0xff, 0xee, 0xee));
        errorBannerLabel.setFont(errorBannerLabel.getFont().deriveFont(Font.PLAIN, 12f));
        // Wrap long messages inside the sidepanel width — SMURF_GUARD's
        // "Play casual PvP to build…" doesn't fit on one line at 225px.
        banner.add(errorBannerLabel);
        banner.add(Box.createHorizontalGlue());

        JLabel dismiss = new JLabel("\u00d7");
        dismiss.setForeground(new Color(0xff, 0xcc, 0xcc));
        dismiss.setFont(dismiss.getFont().deriveFont(Font.BOLD, 14f));
        dismiss.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dismiss.setToolTipText("Dismiss");
        dismiss.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e) { hideErrorBanner(); }
        });
        banner.add(dismiss);

        return banner;
    }

    /** Show the localized message in the banner, restart the
     *  auto-dismiss timer, and force a re-layout so the banner becomes
     *  visible (BorderLayout doesn't auto-fire revalidate on
     *  setVisible). Safe to call from the EDT only. */
    private void showErrorBanner(String localizedMessage)
    {
        if (errorBanner == null || errorBannerLabel == null) return;
        // 200px hard cap matches the gate status label — keeps long
        // strings wrapping inside the sidepanel rather than punching
        // out the right edge.
        errorBannerLabel.setText("<html><div style='width:200px'>"
            + escapeHtml(localizedMessage) + "</div></html>");
        errorBanner.setVisible(true);
        revalidate();
        repaint();

        if (errorBannerTimer != null && errorBannerTimer.isRunning())
        {
            errorBannerTimer.stop();
        }
        errorBannerTimer = new Timer(ERROR_BANNER_DISMISS_MS, e -> hideErrorBanner());
        errorBannerTimer.setRepeats(false);
        errorBannerTimer.start();
    }

    private void hideErrorBanner()
    {
        if (errorBanner == null) return;
        errorBanner.setVisible(false);
        revalidate();
        repaint();
        if (errorBannerTimer != null) errorBannerTimer.stop();
    }

    /**
     * Builds the reconnect-status banner pinned above {@link #errorBanner}.
     * Amber-tinted strip with a wrapped two-line message + a live
     * countdown to the next reconnect attempt. Hidden by default;
     * {@link #refreshReconnectBanner} flips visibility based on the
     * {@link LobbyService#isConnected()} / {@link
     * LobbyService#getNextReconnectAttemptEpochMs()} pair.
     *
     * <p>No dismiss button: the banner is informational and self-clears
     * when the socket reconnects. A manual dismiss would let users
     * hide a real ongoing problem and then wonder why nothing works.
     */
    private JPanel buildReconnectBanner()
    {
        JPanel banner = new JPanel();
        banner.setLayout(new BoxLayout(banner, BoxLayout.X_AXIS));
        // Amber, distinct from the red error banner — error = "your
        // last action failed", reconnect = "we're trying to get back
        // online". Different colors so a user who has both visible
        // doesn't conflate the two.
        banner.setBackground(new Color(0x6e, 0x55, 0x1f));
        banner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x40, 0x30, 0x10)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        reconnectBannerLabel = new JLabel(" ");
        reconnectBannerLabel.setForeground(new Color(0xff, 0xf2, 0xdd));
        reconnectBannerLabel.setFont(reconnectBannerLabel.getFont().deriveFont(Font.PLAIN, 12f));
        banner.add(reconnectBannerLabel);
        banner.add(Box.createHorizontalGlue());
        return banner;
    }

    /**
     * 1Hz tick that polls the underlying service for connection state
     * and either shows the reconnect banner (with the live countdown)
     * or hides it. Called from {@link #reconnectBannerTicker}; safe to
     * call from the EDT only.
     *
     * <p>We poll rather than wire a callback from
     * {@link com.pvp.leaderboard.service.socket.WebSocketManager}
     * because the countdown needs a 1Hz redraw regardless — adding a
     * callback would only mean two notification paths for the same
     * state. The {@link LobbyService} interface deliberately exposes
     * the raw "is connected" + "next attempt epoch ms" pair instead of
     * a derived "seconds remaining" so the panel owns the
     * presentation logic (countdown formatting, edge cases when the
     * scheduled time is in the past while a reconnect is mid-flight).
     *
     * <p>{@link com.pvp.leaderboard.lobby.NoOpLobbyService} inherits the
     * interface defaults — {@code isConnected()=true} +
     * {@code getNextReconnectAttemptEpochMs()=0} — so the banner stays
     * hidden in unit-test runs without any extra stubbing.
     */
    private void refreshReconnectBanner()
    {
        if (reconnectBanner == null || reconnectBannerLabel == null) return;
        boolean connected = true;
        long nextAttemptEpochMs = 0L;
        try
        {
            connected = service.isConnected();
            nextAttemptEpochMs = service.getNextReconnectAttemptEpochMs();
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
        if (connected || nextAttemptEpochMs <= 0L)
        {
            if (reconnectBanner.isVisible())
            {
                reconnectBanner.setVisible(false);
                revalidate();
                repaint();
            }
            return;
        }

        long remainingMs = nextAttemptEpochMs - System.currentTimeMillis();
        // Floor at 0 so a tick that lands after the scheduled time
        // (e.g. JVM pause, reconnect mid-flight) doesn't render a
        // negative countdown. Once the attempt fires the manager
        // zeroes nextReconnectEpochMs and the next tick hides the
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
        reconnectBannerLabel.setText(html);
        if (!reconnectBanner.isVisible())
        {
            reconnectBanner.setVisible(true);
            revalidate();
            repaint();
        }
    }

    @Override
    public void onBlockListSnapshot(Set<String> playerIds)
    {
        if (playerIds == null) return;
        blockedPlayerIds.clear();
        blockedPlayerIds.addAll(playerIds);
        // User-state change — bypass the 60s roster coalescer so the
        // grey-out lands immediately. Block toggles are user-initiated
        // (or initiated from a different device, which is still a
        // single-user action) so misclick concerns don't apply.
        renderRoster();
    }

    @Override
    public void onBlockAdded(String playerId)
    {
        if (playerId == null || playerId.isEmpty()) return;
        if (blockedPlayerIds.add(playerId)) renderRoster();
    }

    @Override
    public void onBlockRemoved(String playerId)
    {
        if (playerId == null || playerId.isEmpty()) return;
        if (blockedPlayerIds.remove(playerId)) renderRoster();
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

    private JPanel newGateLikeCard()
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(18, 8, 18, 8));
        return card;
    }

    private static JLabel makeGateHeader(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    /** Same size + weight as {@link #makeGateHeader} but with a muted color
     *  — used on Confirm/Waiting/MeetAt where every line needs to read at
     *  button size per spec, but secondary lines (style/loc context,
     *  "Confirmed by other player", countdown) should still be visually
     *  de-emphasized vs the primary header + opponent name. */
    private static JLabel makeGateBigSubHeader(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        l.setForeground(new Color(0xcc, 0xcc, 0xcc));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    /** Same visual weight as the gate's per-style toggle buttons so the fight
     *  setup feels like the same widget family. */
    private static JButton makeGateActionButton(String text, boolean enabled)
    {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
        b.setMargin(new Insets(6, 12, 6, 12));
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        b.setFocusPainted(false);
        b.setEnabled(enabled);
        return b;
    }

    /** Bottom exit button — green-tinted to mirror the gate's "Go to lobby". */
    private static JButton makeGateCancelButton(String text, Runnable onClick)
    {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 16f));
        b.setMargin(new Insets(10, 12, 10, 12));
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        b.setFocusPainted(false);
        b.setBackground(new Color(0x2e, 0x7d, 0x32));
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** Two-column "label : value" row used in the Meet At terminal view. */
    private static JPanel makeMeetAtRow(String label, String value)
    {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        // Bumped 28→32 to absorb the bigger font (16pt) without clipping.
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        // 16pt bold matches the rest of the post-accept FIGHT-card text per spec.
        JLabel l = new JLabel(label);
        l.setFont(l.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        l.setForeground(new Color(0xcc, 0xcc, 0xcc));

        JLabel v = new JLabel(value);
        v.setFont(v.getFont().deriveFont(Font.BOLD, GATE_HEADER_PT));
        v.setForeground(Color.WHITE);

        row.add(l, BorderLayout.WEST);
        row.add(v, BorderLayout.CENTER);
        return row;
    }

    /** Wrap a fight-setup card in a scrollpane — RuneLite sidepanels can be
     *  short enough that the buttons + summary overflow vertically. */
    private static JComponent wrapInScroll(JPanel card)
    {
        JScrollPane sp = new JScrollPane(card,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** Compact strip showing the user's currently-selected build + style with
     *  a "Reset Options" affordance that clears all gate picks and returns
     *  to the gate. Lives between the presence bar and the rank filters.
     *  Fonts match {@link #LOBBY_HEADER_PT} so the strip reads at the same
     *  scale as the Lobby title row above.
     *
     *  Stacked vertically so the Build line + (possibly-multi-style) Style
     *  line can wrap across multiple lines instead of getting "..." truncated
     *  when the user has enabled NH+Veng+Multi+DMM in a 225px sidepanel. */
    private JPanel buildCurrentStyleBar()
    {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        // 108px = ~4 lines of 15pt + button row at its natural height.
        // Build line + style line (which can wrap to 2 lines with all 4
        // styles) + the Reset Options button row. Bumped from 96 to 108
        // so the button row gets its preferred height (~32px at 15pt bold
        // + Substance border insets) instead of being squeezed to 28px,
        // which Substance's ButtonUI was responding to by ellipsifying
        // the label horizontally as well — visible to users as
        // "Reset Opti\u2026" truncation. Real height auto-shrinks when
        // the style line collapses to a single row.
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 108));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        // "Build: X / Style: Y, Z" rendered as one HTML-wrapped JLabel so the
        // CSV values can wrap when they overflow the sidepanel.
        currentStyleLabel = new JLabel();
        currentStyleLabel.setFont(currentStyleLabel.getFont().deriveFont(Font.BOLD, LOBBY_HEADER_PT));
        currentStyleLabel.setAlignmentX(LEFT_ALIGNMENT);
        bar.add(currentStyleLabel);

        // Reset Options button on its own row, right-aligned so it doesn't
        // compete with the wrapped label for horizontal space. Horizontal-glue
        // pushes the button to the right within a row that stretches across
        // the bar's full width (FlowLayout RIGHT wouldn't work here because
        // BoxLayout sizes the row to its content's preferred width).
        JPanel buttonRow = new JPanel();
        buttonRow.setLayout(new BoxLayout(buttonRow, BoxLayout.X_AXIS));
        buttonRow.setOpaque(false);
        buttonRow.setAlignmentX(LEFT_ALIGNMENT);
        buttonRow.add(Box.createHorizontalGlue());
        JButton reset = new JButton("Reset Options");
        reset.setMargin(new Insets(2, 8, 2, 8));
        reset.setFont(reset.getFont().deriveFont(Font.BOLD, LOBBY_HEADER_PT));
        reset.setFocusPainted(false);
        reset.addActionListener(e ->
        {
            // Hard reset: clear styles, clear account type, reset region to
            // default, desync all gate toggle visuals, then return to the
            // gate. Go-to-lobby will be disabled until the user re-picks.
            resetGateOptions();
            rootCards.show(rootCardHost, CARD_GATE);
        });
        // Lock the button to its natural preferred size so neither the
        // outer BoxLayout (which would otherwise shrink it to fit a
        // squeezed cross-axis) nor Substance's ButtonUI (which falls
        // back to ellipsis when allocated less than preferred width)
        // can clip "Reset Options" to "Reset Opti\u2026". Computed
        // AFTER setFont/setMargin so the metrics reflect the bold
        // 15pt run we actually paint with.
        Dimension resetPref = reset.getPreferredSize();
        reset.setMinimumSize(resetPref);
        reset.setMaximumSize(resetPref);
        // Match the row's max height to the button so BoxLayout doesn't
        // squeeze the button vertically — Substance's ButtonUI clips
        // the label horizontally when it's given less than its
        // preferred height (the height squeeze cascades into the text
        // layout's available horizontal run, which is what produces
        // the "Reset Opti\u2026" symptom in narrow sidepanels).
        buttonRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, resetPref.height));
        buttonRow.add(reset);
        bar.add(buttonRow);

        return bar;
    }

    /** {@link Box#createVerticalStrut(int)} returns a {@link Box.Filler} whose
     *  X alignment defaults to {@code CENTER}. Mixed with LEFT-aligned siblings
     *  this breaks BoxLayout's off-axis math. Wrap the strut so we control it. */
    private static Component leftAlignedStrut(int height)
    {
        Box.Filler strut = (Box.Filler) Box.createVerticalStrut(height);
        strut.setAlignmentX(LEFT_ALIGNMENT);
        return strut;
    }

    private JPanel buildPresenceBar()
    {
        // "Lobby" + presence count both use the same font as player names
        // (Font.BOLD, ROW_FONT_PT) so the lobby strip reads as a single
        // typographic unit with the roster below it. Stacked vertically
        // because "Showing X out of Y players online" at 15pt bold is too
        // wide to share a row with the "Lobby" header in a 225px sidepanel.
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        // 80px gives the presence label room to wrap to 2 lines without
        // BoxLayout cropping it. HTML-wrap target width below is 200px;
        // anything narrower than ~190px effective triggers wrap.
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        JLabel title = new JLabel("Lobby");
        title.setFont(title.getFont().deriveFont(Font.BOLD, (float) ROW_FONT_PT));
        title.setAlignmentX(LEFT_ALIGNMENT);
        bar.add(title);

        // HTML wrap because the full presence string at 15pt bold overruns the
        // sidepanel's native width and JLabel otherwise truncates with "...".
        presenceLabel = new JLabel();
        presenceLabel.setFont(presenceLabel.getFont().deriveFont(Font.BOLD, (float) ROW_FONT_PT));
        presenceLabel.setForeground(new Color(0x6cd16a));
        presenceLabel.setAlignmentX(LEFT_ALIGNMENT);
        bar.add(presenceLabel);
        return bar;
    }

    private JPanel buildFilterRow()
    {
        JPanel filters = new JPanel();
        filters.setLayout(new BoxLayout(filters, BoxLayout.Y_AXIS));
        filters.setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(1, 0, 1, 0, new Color(60, 60, 60)),
            BorderFactory.createEmptyBorder(6, 2, 6, 2)));
        // Caption now uses ROW_FONT_PT (15pt bold) to match player names per
        // request, which forces a 2-line wrap at 200px → ~44px tall.
        // Value row (~20) + slider (~28) + container padding (12) → ~104.
        filters.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));

        // Caption above the slider — explains the range semantics so the user
        // doesn't have to infer "what does this slider do?" from the rank
        // colours alone. Word-wraps when the sidepanel is narrow.
        JLabel caption = new JLabel(
            "<html><div style='width: 200px;'>Receive or give invites within the ratings you choose</div></html>");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, (float) ROW_FONT_PT));
        caption.setForeground(new Color(0xaa, 0xaa, 0xaa));
        caption.setAlignmentX(LEFT_ALIGNMENT);
        caption.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 2));

        JLabel minValue = makeRankValueLabel(rankMinIdx);
        JLabel maxValue = makeRankValueLabel(rankMaxIdx);
        // Each value sits flush against its slider end (left / right). No
        // "Rank range:" prefix any more — the caption above explains the
        // widget and the rank-coloured labels speak for themselves.
        minValue.setHorizontalAlignment(SwingConstants.LEFT);
        maxValue.setHorizontalAlignment(SwingConstants.RIGHT);

        // Single-track double-handled range slider (replaces the earlier
        // pair of stacked JSliders). One row instead of two saves ~50px of
        // vertical real estate so the chat panel stays visible without
        // scrolling on the default sidepanel height.
        RangeSlider rankRange = new RangeSlider(0, RANK_LABELS.length - 1, rankMinIdx, rankMaxIdx);
        rankRange.setToolTipText("Drag the handles to set min / max opponent rank");
        rankRange.addChangeListener(e ->
        {
            rankMinIdx = rankRange.getLow();
            rankMaxIdx = rankRange.getHigh();
            updateRankValueLabel(minValue, rankMinIdx);
            updateRankValueLabel(maxValue, rankMaxIdx);
            if (!rankRange.getValueIsAdjusting())
            {
                // Persist on commit (drag-end) rather than every drag
                // tick — avoids hammering the config writer with N writes
                // per drag while still ensuring the final value lands.
                prefs.setMinRankIdx(rankMinIdx);
                prefs.setMaxRankIdx(rankMaxIdx);
                renderRoster();
                // Slider also gates incoming invite cards — re-evaluate per-card
                // visibility so out-of-range invites disappear (and reappear if
                // the user widens the range later).
                refreshInvitesContainer();
            }
        });

        // Header row: min rank LEFT, max rank RIGHT. No center label.
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        header.setAlignmentX(LEFT_ALIGNMENT);
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        header.add(minValue, BorderLayout.WEST);
        header.add(maxValue, BorderLayout.EAST);

        filters.add(caption);
        filters.add(header);
        rankRange.setAlignmentX(LEFT_ALIGNMENT);
        filters.add(rankRange);

        return filters;
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
     *  set on the min / max labels respectively. */
    private static JLabel makeRankValueLabel(int idx)
    {
        ChipLabel l = new ChipLabel(RANK_LABELS[idx]);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        l.setForeground(RankUtils.getRankColor(RANK_LABELS[idx]));
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        return l;
    }

    private static void updateRankValueLabel(JLabel l, int idx)
    {
        String label = RANK_LABELS[idx];
        l.setText(label);
        l.setForeground(RankUtils.getRankColor(label));
    }

    private JScrollPane buildRosterScroll()
    {
        rosterContainer = new ScrollableRosterPanel();
        rosterContainer.setLayout(new BoxLayout(rosterContainer, BoxLayout.Y_AXIS));
        rosterContainer.setName(ROSTER_NAME);
        rosterContainer.setOpaque(false);

        renderRoster();

        rosterScroll = new JScrollPane(rosterContainer,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rosterScroll.getViewport().setOpaque(false);
        rosterScroll.setOpaque(false);
        rosterScroll.setBorder(BorderFactory.createEmptyBorder());
        rosterScroll.getVerticalScrollBar().setUnitIncrement(16);
        rosterScroll.setAlignmentX(LEFT_ALIGNMENT);
        // Take all remaining vertical space — the parent uses BoxLayout Y_AXIS.
        rosterScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return rosterScroll;
    }

    /** Snaps the roster scroll back to the top. Called when entering the
     *  fight-setup flow so the user lands at the top of the roster on
     *  return (Find-a-new-match / both-confirmed / window-expired) rather
     *  than at whatever position they had clicked Fight from.
     *
     *  Implementation note: a direct {@code setValue(0)} on the scrollbar
     *  is unreliable when called mid-EDT before the viewport has been
     *  re-laid out (the bar's model clamps the new value against an
     *  out-of-date {@code max}). Schedule via invokeLater so the snap
     *  runs after the current event handler's layout pass completes, and
     *  drive the viewport directly so we don't depend on the scrollbar
     *  model being in sync. */
    private void scrollRosterToTop()
    {
        if (rosterScroll == null) return;
        // rosterScroll is single-assignment in buildLobbyView() and never
        // re-nulled, so no second guard is needed inside the lambda.
        SwingUtilities.invokeLater(() ->
        {
            JViewport vp = rosterScroll.getViewport();
            if (vp != null) vp.setViewPosition(new Point(0, 0));
        });
    }

    /**
     * JPanel that tells its enclosing JViewport "always size me to the viewport
     * width". Without this, the JScrollPane sizes the view to its <i>preferred</i>
     * width (the widest child's preferred width) which is much narrower than the
     * 215px sidepanel — and child rows then sit centered in the empty space.
     * That was the visible bug in the first beta builds.
     */
    private static class ScrollableRosterPanel extends JPanel implements Scrollable
    {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }

        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return Math.max(visibleRect.height - 16, 16);
        }

        /** KEY: forces the view to be exactly viewport-wide so child rows fill horizontally. */
        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private void renderRoster()
    {
        rosterContainer.removeAll();

        List<LobbyMember> visible = new ArrayList<>();
        for (LobbyMember p : roster)
        {
            // No region filter — lobby shows everyone regardless of region.
            // Style/build overlap only greys the [Fight] chip (see
            // fightAllowed); rank outside the slider hides the row
            // entirely (server also filters, this is defense-in-depth).
            if (!rankInRange(p)) continue;
            // Hide locally-stale rows (last invite to this player_id
            // bounced with PEER_NOT_IN_LOBBY — see onError). The set
            // is cleared on the next authoritative server snapshot.
            if (p.playerId != null && recentlyStalePlayerIds.contains(p.playerId)) continue;
            visible.add(p);
        }
        Collections.sort(visible, (a, b) -> Integer.compare(b.peakRankIdx, a.peakRankIdx));

        for (LobbyMember p : visible)
        {
            // Chip precedence: [Blocked Lookup] > [Invited M:SS] > [Lookup] > [Fight].
            //   [Blocked Lookup] — local user has blocked this player_id, card greyed
            //   [Invited M:SS]   — pending outgoing invite, click cancels
            //   [Lookup]         — outstanding incoming invite from this player
            //   [Fight]          — default; opens full-screen fight-setup card
            rosterContainer.add(new PlayerRow(p, ROW_FONT_PT,
                this::isPlayerInvited,
                this::getOutgoingInvite,
                this::routeOpenProfile,
                this::onFightClicked,
                opp -> cancelOutgoingInvite(opp.playerId, true),
                m -> m != null && m.playerId != null && blockedPlayerIds.contains(m.playerId),
                this::fightAllowed,
                false));
            rosterContainer.add(Box.createVerticalStrut(2));
        }

        // Always seed at least one row of placeholder space so the scroll pane
        // computes a non-zero preferred size when filters drop everything.
        // Two distinct copies so the user can tell whether the empty
        // state is "the lobby is genuinely empty" vs "you filtered
        // everyone out" without inspecting their own slider:
        //   - roster.isEmpty()                → nobody else online
        //   - roster non-empty but visible==0 → filter rejects all
        // The Reset-Options nudge is only useful in the second case
        // (resetting changes nothing when the roster itself is empty).
        if (visible.isEmpty())
        {
            String message = roster.isEmpty()
                ? "No one else is currently in the lobby, please wait while others join or invite some friends to the plugin"
                : "No players match your filters, click Reset Options and widen your search";
            // HTML wrap forces line breaks inside the ~200px lobby
            // column — JLabel won't wrap plain text inside a
            // BoxLayout.Y_AXIS parent. 15pt BOLD matches the lobby's
            // body-text scale used by the style toggles + presence label.
            JLabel empty = new JLabel("<html><div style='width:200px'>" + message + "</div></html>");
            empty.setFont(empty.getFont().deriveFont(Font.BOLD, 15f));
            empty.setForeground(new Color(0x888888));
            empty.setAlignmentX(LEFT_ALIGNMENT);
            empty.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
            rosterContainer.add(empty);
        }

        if (presenceLabel != null)
        {
            // Explicit <br> wrap (no <div style='width:Npx'>) so the
            // label adapts to the actual sidepanel width — RuneLite
            // sidepanels can be resized by the user and a fixed 200px
            // wrap target was causing the JLabel to render at 200px
            // wide regardless and get clipped at the panel edge,
            // visible as "...players onlin" with the trailing "e"
            // shaved off. With a hard <br> the line break happens at
            // a stable point and each half ("Showing X out of Y" /
            // "players online") fits comfortably in even a narrow
            // (~180px) sidepanel at 15pt bold.
            presenceLabel.setText("<html>"
                + "Showing " + visible.size() + " out of " + roster.size() + "<br>"
                + "players online"
                + "</html>");
        }

        rosterContainer.revalidate();
        rosterContainer.repaint();
    }

    /** {@code true} when the local user's advertised styles/builds overlap
     *  the opponent's — drives whether [Fight] is clickable vs greyed.
     *  Rows stay visible either way; only rank-out-of-range hides a card. */
    private boolean fightAllowed(LobbyMember p)
    {
        if (p == null) return false;
        boolean styleOverlap = false;
        for (Style s : p.styles)
        {
            if (selectedStyles.contains(s))
            {
                styleOverlap = true;
                break;
            }
        }
        if (!styleOverlap) return false;
        for (BuildType b : p.builds)
        {
            if (selectedBuildTypes.contains(b)) return true;
        }
        return false;
    }

    private boolean rankInRange(LobbyMember p)
    {
        // Matchmaking gate: keys on **current** rank, NOT peak. Peak is
        // display-only (the big rank label on each card). The server's
        // RANK_OUT_OF_RANGE check on lobby/invite reads
        // current_mmr_per_bucket, so the slider must filter on the same
        // signal or invites will be rejected by the server even though
        // the user could see the row.
        int idx = p.currentRankIdx;
        // Unknown / sentinel rank — show the row rather than hiding
        // everyone when the server omits rank_idx on a roster push.
        if (idx < 0 || idx >= RANK_LABELS.length) return true;
        return idx >= rankMinIdx && idx <= rankMaxIdx;
    }

    /** True if {@code idx} is a real rank (server returned a value), not
     *  a -1 sentinel or a label-overflow. Used to distinguish "no current
     *  rank yet" (don't filter out) from "current rank known and out of
     *  band" (filter out). */
    private boolean rankIdxKnown(int idx)
    {
        return idx >= 0 && idx < RANK_LABELS.length;
    }

    /** Picks the canonical bucket key the server should compute
     *  per-row {@code rank_idx} / {@code peak_rank_idx} for. Priority
     *  follows the {@link Style} enum declaration order
     *  (NH &gt; Veng &gt; Multi &gt; DMM) — first selected style wins,
     *  with {@code "overall"} as the fallback when nothing is selected
     *  (gate state, mid-toggle race, etc.). The slider matchmaking gate
     *  reads the rank for this bucket, so the bucket must reflect the
     *  user's primary advertised style or the slider hides players
     *  based on the wrong bucket's MMR. */
    private String pickSortBucket()
    {
        for (Style s : Style.values())
        {
            if (selectedStyles.contains(s))
            {
                return s.name().toLowerCase();
            }
        }
        return "overall";
    }

    // -------------------- Player row ([Fight] or [Lookup], no inline picker) --------------------

    /**
     * Roster entry. EAST chip cycles through three states (precedence top-down):
     * <ul>
     *   <li><b>[Invited M:SS]</b> — outgoing invite pending acceptance.
     *       Click cancels the invite (clears the 10-min block).</li>
     *   <li><b>[Lookup]</b> — outstanding incoming invite from this player
     *       (their card is up top). Click opens Player Lookup.</li>
     *   <li><b>[Fight]</b> (default) — opens the full-screen fight-setup card.</li>
     * </ul>
     *
     * <p>Clicking the profile area also opens Player Lookup — matches the
     * legacy right-click "PvP lookup" muscle memory.
     */
    private static class PlayerRow extends JPanel
    {
        /** Single fixed border used in both idle and hover states. The white
         *  hover outline is drawn manually in {@link #paintBorder(Graphics)}
         *  rather than via setBorder-swap so:
         *    1. The component's preferred size never changes on hover (the
         *       bottom matte and the hover outline both occupy the same 1px
         *       gutter at the row edge), so the inner scrollbar doesn't
         *       flicker between hidden/shown when the cursor moves between
         *       rows.
         *    2. We avoid a setBorder() call per hover transition. setBorder
         *       fires a PropertyChangeEvent + repaint per call; mouse-wheel
         *       scrolling can fire dozens of those per second as rows pass
         *       under the cursor, which was the residual scroll stutter. */
        private static final Border ROW_BORDER_FIXED = BorderFactory.createCompoundBorder(
            new MatteBorder(0, 0, 1, 0, new Color(0x40, 0x40, 0x40)),
            BorderFactory.createEmptyBorder(4, 6, 4, 6));

        private static final Color CHIP_BORDER_COLOR = new Color(0x88, 0x88, 0x88);
        /** Yellow for [Invited M:SS] — same family as Confirm-fight emphasis. */
        private static final Color CHIP_INVITED_COLOR = new Color(0xE5, 0xC0, 0x6B);
        /** Muted foreground used everywhere the row is greyed (blocked
         *  state). Matches the JLabel default-disabled tone so it reads
         *  as "inactive" without changing the panel's overall colour
         *  palette. The rank label is recoloured to the same grey so it
         *  doesn't pop against the muted name. */
        private static final Color BLOCKED_FG = new Color(0x70, 0x70, 0x70);
        private static final Color BLOCKED_BORDER = new Color(0x50, 0x50, 0x50);

        final LobbyMember p; // package-private so the lobby's tick refresher can key on it
        private final int fontBase;
        /** Re-queried on every {@link #render()} so the chip stays live. */
        private final Predicate<LobbyMember> isInvited;
        private final Function<LobbyMember, OutgoingInvite> getOutgoing;
        private final Consumer<String> onOpenProfile;
        private final FightStartCallback onFight;
        private final InviteCancelCallback onCancelInvite;
        /** Re-queried at construction so the next renderRoster picks up
         *  block changes immediately (block listener overrides call
         *  renderRoster directly). */
        private final boolean blocked;
        /** Re-queried at construction — false when style/build
         *  advertisement doesn't overlap the local user's gate picks;
         *  [Fight] renders greyed and is a no-op. */
        private final boolean fightEnabled;
        /** {@code true} for the "Your profile displayed to others"
         *  row above the slider — suppresses the right-side action
         *  chip (no Fight / no Lookup chip — clicking the row body
         *  still opens the lookup) and any rank-text rendering when
         *  {@link LobbyMember#peakRankIdx} is sentinel-negative. */
        private final boolean selfPreview;

        /** Tracks the cursor across child boundaries so child→child crossings
         *  don't false-trigger an unhover on the leaf JLabel. */
        private boolean hovered;

        /** Legacy 7-arg constructor used by the lobby roster — defaults
         *  {@code selfPreview} to {@code false}. New call sites should
         *  use the 8-arg variant. */
        PlayerRow(LobbyMember p, int fontBase,
                  Predicate<LobbyMember> isInvited,
                  Function<LobbyMember, OutgoingInvite> getOutgoing,
                  Consumer<String> onOpenProfile,
                  FightStartCallback onFight,
                  InviteCancelCallback onCancelInvite,
                  Predicate<LobbyMember> isBlocked)
        {
            this(p, fontBase, isInvited, getOutgoing, onOpenProfile, onFight,
                onCancelInvite, isBlocked, m -> true, false);
        }

        PlayerRow(LobbyMember p, int fontBase,
                  Predicate<LobbyMember> isInvited,
                  Function<LobbyMember, OutgoingInvite> getOutgoing,
                  Consumer<String> onOpenProfile,
                  FightStartCallback onFight,
                  InviteCancelCallback onCancelInvite,
                  Predicate<LobbyMember> isBlocked,
                  boolean selfPreview)
        {
            this(p, fontBase, isInvited, getOutgoing, onOpenProfile, onFight,
                onCancelInvite, isBlocked, m -> true, selfPreview);
        }

        PlayerRow(LobbyMember p, int fontBase,
                  Predicate<LobbyMember> isInvited,
                  Function<LobbyMember, OutgoingInvite> getOutgoing,
                  Consumer<String> onOpenProfile,
                  FightStartCallback onFight,
                  InviteCancelCallback onCancelInvite,
                  Predicate<LobbyMember> isBlocked,
                  Predicate<LobbyMember> fightEnabled,
                  boolean selfPreview)
        {
            this.p = p;
            this.fontBase = fontBase;
            this.isInvited = isInvited;
            this.getOutgoing = getOutgoing;
            this.onOpenProfile = onOpenProfile;
            this.onFight = onFight;
            this.onCancelInvite = onCancelInvite;
            this.blocked = isBlocked != null && isBlocked.test(p);
            this.fightEnabled = fightEnabled == null || fightEnabled.test(p);
            this.selfPreview = selfPreview;
            setLayout(new BorderLayout());
            setBackground(new Color(0x2b, 0x2b, 0x2b));
            setBorder(ROW_BORDER_FIXED);
            setAlignmentX(LEFT_ALIGNMENT);
            render();
        }

        /** Draw the white hover outline manually so swapping it on/off doesn't
         *  fire setBorder()'s PropertyChangeEvent + repaint per row per scroll
         *  tick — that was the residual stutter source. The line is drawn over
         *  the matte's bottom pixel and the row's other 3 edges; total visual
         *  size is unchanged. */
        @Override
        protected void paintBorder(Graphics g)
        {
            super.paintBorder(g);
            if (hovered)
            {
                g.setColor(Color.WHITE);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        }

        /** Pin row height to preferred so BoxLayout Y_AXIS doesn't spread
         *  rows to fill the viewport (regression from beta 1). */
        @Override
        public Dimension getMaximumSize()
        {
            Dimension d = super.getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, d.height);
        }

        private void render()
        {
            removeAll();
            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setOpaque(false);
            center.add(buildHeaderRow());
            center.add(Box.createVerticalStrut(3));
            center.add(buildChipsRow());
            // Build chips render on a separate row below so [Pure][Zerker]
            // [Main] don't compete with the longer [Region][NH][Veng][Multi]
            // [DMM] strip — 8 chips on one FlowLayout row wraps unpredictably
            // in the 225px sidepanel.
            center.add(Box.createVerticalStrut(2));
            center.add(buildBuildsRow());

            add(center, BorderLayout.CENTER);

            // Self-preview suppresses the EAST action chip — there's
            // no Fight option (can't fight yourself) and no Lookup
            // chip either (the whole row body is already clickable
            // and routes to routeOpenProfile). Other rows still get
            // their chip per buildActionChip() rules.
            if (!selfPreview)
            {
                // Wrap the chip so the 6px margin lives on the wrapper, not the
                // chip's own border (chip border tracks its text width).
                JComponent action = buildActionChip();
                JPanel actionWrap = new JPanel(new BorderLayout());
                actionWrap.setOpaque(false);
                actionWrap.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
                actionWrap.add(action, BorderLayout.CENTER);
                add(actionWrap, BorderLayout.EAST);
            }

            // Profile-click handler walks only the CENTER subtree so the
            // action chip can never double-fire as a profile click.
            installProfileInteraction(center);

            revalidate();
            repaint();
        }

        private JComponent buildActionChip()
        {
            if (blocked)
            {
                // Blocked card: action chip is Lookup so the user has a
                // visible route to Player Lookup → Unblock without a
                // dedicated "Unblock" widget on the row. Colour matches
                // the greyed body so the chip reads "muted / inactive".
                JLabel chip = makeActionChip("Lookup", BLOCKED_FG, BLOCKED_BORDER,
                    () -> { if (onOpenProfile != null) onOpenProfile.accept(p.name); });
                chip.setName(ROW_ACTION_CHIP_NAME);
                chip.setToolTipText("Blocked — open " + p.name + "'s profile to Unblock");
                return chip;
            }
            OutgoingInvite oi = getOutgoing != null ? getOutgoing.apply(p) : null;
            if (oi != null)
            {
                JLabel chip = makeActionChip(formatInviteRemaining(oi), CHIP_INVITED_COLOR, CHIP_INVITED_COLOR,
                    () -> { if (onCancelInvite != null) onCancelInvite.cancel(p); });
                chip.setName(ROW_ACTION_CHIP_NAME);
                chip.setToolTipText("Pending invite to " + p.name + " — click to cancel");
                return chip;
            }
            boolean invited = isInvited != null && isInvited.test(p);
            if (invited)
            {
                JLabel chip = makeActionChip("Lookup", Color.WHITE, CHIP_BORDER_COLOR,
                    () -> { if (onOpenProfile != null) onOpenProfile.accept(p.name); });
                chip.setName(ROW_ACTION_CHIP_NAME);
                chip.setToolTipText("Open " + p.name + "'s profile in Player Lookup");
                return chip;
            }
            if (!fightEnabled)
            {
                JLabel chip = makeActionChip("Fight", BLOCKED_FG, BLOCKED_BORDER, () -> {});
                chip.setName(ROW_ACTION_CHIP_NAME);
                chip.setCursor(Cursor.getDefaultCursor());
                chip.setToolTipText("No matching style/build — widen your picks via Reset Options");
                return chip;
            }
            JLabel chip = makeActionChip("Fight", Color.WHITE, CHIP_BORDER_COLOR,
                () -> { if (onFight != null) onFight.onFight(p); });
            chip.setName(ROW_ACTION_CHIP_NAME);
            chip.setToolTipText("Set up a fight with " + p.name);
            return chip;
        }

        /** Recursively installs the row's hover/click handler on every
         *  component inside {@code root}. We have to walk the tree because
         *  Swing dispatches mouse events to the deepest hit component — a
         *  listener on just the row would never see clicks that land on the
         *  name JLabel or a chip. */
        private void installProfileInteraction(Container root)
        {
            MouseAdapter ma = new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (onOpenProfile != null) onOpenProfile.accept(p.name);
                }
                @Override
                public void mouseEntered(MouseEvent e)
                {
                    setHover(true);
                }
                @Override
                public void mouseExited(MouseEvent e)
                {
                    // Child→child crossings fire exit on the leaf — only
                    // unhover when the cursor really left the row bounds.
                    Point inRow = SwingUtilities.convertPoint(
                        e.getComponent(), e.getPoint(), PlayerRow.this);
                    if (!PlayerRow.this.contains(inRow)) setHover(false);
                }
            };
            addMouseListener(ma);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            attachListenerRecursively(root, ma);
        }

        private static void attachListenerRecursively(Container c, MouseListener ml)
        {
            for (Component child : c.getComponents())
            {
                child.addMouseListener(ml);
                if (child instanceof JComponent)
                {
                    ((JComponent) child).setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
                if (child instanceof Container)
                {
                    attachListenerRecursively((Container) child, ml);
                }
            }
        }

        private void setHover(boolean on)
        {
            if (this.hovered == on) return;
            this.hovered = on;
            // repaint() only — no setBorder() means no PropertyChangeEvent and
            // no parent revalidation cascade during mouse-wheel scroll.
            repaint();
        }

        /** name (base, full row width, no ellipsis) + rank (overlay,
         *  right-anchored, opaque on row bg, drawn on top via z-order).
         *  Long names render their full text and visually clip "under"
         *  the rank chip — the rank's opaque background masks the overflow
         *  and a 4px left padding on the rank gives a minimal readable
         *  gap between the last visible name char and the rank text. */
        private JComponent buildHeaderRow()
        {
            // peakRankIdx < 0 is the self-preview sentinel — render
            // the name in plain white instead of a rank-coloured hue
            // and skip the right-side rank chip entirely. Used by
            // {@link MatchmakingLobbyPanel#renderSelfPreview} which
            // doesn't currently have an authoritative self-rank
            // source. Bounds-check also defends against any future
            // out-of-range index from a malformed wire payload.
            boolean rankKnown = p.peakRankIdx >= 0 && p.peakRankIdx < RANK_LABELS.length;
            String rankLabel = rankKnown ? RANK_LABELS[p.peakRankIdx] : "";
            // Blocked rows render in a muted grey across the entire
            // header (name + rank chip) so the row reads as inactive.
            // The rank label's normal hue would otherwise pop against
            // the greyed name.
            Color rankColor = blocked
                ? BLOCKED_FG
                : (rankKnown ? RankUtils.getRankColor(rankLabel) : Color.WHITE);

            String displayName = displayNameOf(p);
            NonEllipsisLabel name = new NonEllipsisLabel(displayName);
            name.setFont(name.getFont().deriveFont(Font.BOLD, (float) fontBase));
            name.setForeground(rankColor);
            name.setToolTipText(blocked
                ? displayName + " (blocked — click to Unblock from Player Lookup)"
                : displayName);

            if (!rankKnown)
            {
                // No rank chip on the right — just the name flush-left.
                // OverlayRightRow expects two children; substitute an
                // empty placeholder so the layout invariants hold.
                JLabel placeholder = new JLabel("");
                placeholder.setOpaque(false);
                return new OverlayRightRow(name, placeholder);
            }

            JLabel rank = new JLabel(rankLabel);
            rank.setFont(rank.getFont().deriveFont(Font.BOLD, (float) fontBase));
            rank.setForeground(rankColor);
            rank.setHorizontalAlignment(SwingConstants.RIGHT);
            // Opaque + matching row bg = masks the overflowing name text
            // wherever the two would otherwise visually overlap. The 4px
            // left inset is the "minimal spacing" gap.
            rank.setOpaque(true);
            rank.setBackground(new Color(0x2b, 0x2b, 0x2b));
            rank.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

            return new OverlayRightRow(name, rank);
        }

        /** [Region] [NH] [Veng] [Multi] [DMM] — style chips dim when not
         *  advertised. The MOD chip lives on the build row below, not here.
         *  When the player advertises <i>every</i> style, the four
         *  per-style chips collapse into a single [Any Style] chip so
         *  the row matches the "Style: Any Style" wording from the
         *  pre-lobby gate's current-style bar. */
        private JPanel buildChipsRow()
        {
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            chips.setOpaque(false);
            chips.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

            int chipFont = Math.max(10, fontBase - 3);
            // Palette: region=white, style=yellow. Blocked rows force
            // all chips to the inactive grey palette so the whole card
            // reads as muted.
            chips.add(makeChip(p.region, !blocked, Color.WHITE, chipFont));
            Color styleColor = new Color(0xff, 0xc1, 0x07);
            if (p.styles.size() == Style.values().length)
            {
                chips.add(makeChip("Any Style", !blocked, styleColor, chipFont));
            }
            else
            {
                for (Style s : Style.values())
                {
                    boolean advertised = !blocked && p.styles.contains(s);
                    chips.add(makeChip(s.label, advertised, styleColor, chipFont));
                }
            }
            return chips;
        }

        /** [MOD]? [Main] [Zerker] [Pure] — build chips dim when the player
         *  doesn't advertise that build. Cyan so they're visually distinct
         *  from the yellow style chips on the row above; MOD stays red.
         *  p.builds is guaranteed ≥1 by LobbyMember's contract, so at least
         *  one build chip is always lit. When the player advertises
         *  <i>every</i> build type, the per-build chips collapse into a
         *  single [Any Build] chip mirroring the {@code buildChipsRow}
         *  [Any Style] collapse. */
        private JPanel buildBuildsRow()
        {
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            chips.setOpaque(false);
            chips.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

            int chipFont = Math.max(10, fontBase - 3);
            if (p.isMod)
            {
                chips.add(makeChip("MOD", !blocked, new Color(0xff, 0x55, 0x55), chipFont));
            }
            Color buildColor = new Color(0x4f, 0xc3, 0xf7);
            if (p.builds.size() == BuildType.values().length)
            {
                chips.add(makeChip("Any Build", !blocked, buildColor, chipFont));
            }
            else
            {
                for (BuildType a : BuildType.values())
                {
                    boolean advertised = !blocked && p.builds.contains(a);
                    chips.add(makeChip(a.label, advertised, buildColor, chipFont));
                }
            }
            return chips;
        }

        private static JLabel makeChip(String text, boolean active, Color activeColor, int fontPt)
        {
            // ChipLabel (not a vanilla JLabel) bypasses Substance L&F's
            // LabelUI text painting. Substance treats MouseEntered /
            // MouseExited from the row-level MouseAdapter (installed
            // via attachListenerRecursively, which fans out to every
            // child including these chips) as a label-rollover state
            // change and triggers a delegate repaint that briefly
            // erases the foreground text — visible to the user as the
            // chip's contents disappearing while the cursor is over a
            // row. Painting the text ourselves in paintComponent skips
            // the delegate entirely; the border still paints normally
            // because paintBorder is unaffected by the L&F text path.
            ChipLabel chip = new ChipLabel(text);
            chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, (float) fontPt));
            chip.setForeground(active ? activeColor : new Color(0x55, 0x55, 0x55));
            Color borderColor = active ? activeColor : new Color(0x3a, 0x3a, 0x3a);
            chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1),
                BorderFactory.createEmptyBorder(1, 5, 1, 5)));
            chip.setOpaque(false);
            return chip;
        }

        /** Tight JLabel chip — much smaller than a JButton under Substance L&F.
         *  textColor / borderColor are split so callers can paint white text
         *  inside a quiet grey outline ([Fight] / [Lookup] convention).
         *
         *  <p>ChipLabel (not vanilla JLabel) so the action text doesn't
         *  flicker / disappear on hover under Substance — same fix as
         *  {@link #makeChip}. Otherwise users can lose track of which
         *  chip says [Fight] vs [Invited 9:42] mid-hover. */
        private JLabel makeActionChip(String text, Color textColor, Color borderColor, Runnable onClick)
        {
            ChipLabel chip = new ChipLabel(text);
            chip.setFont(chip.getFont().deriveFont(Font.BOLD, (float) fontBase));
            chip.setForeground(textColor);
            chip.setHorizontalAlignment(SwingConstants.CENTER);
            chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1),
                BorderFactory.createEmptyBorder(1, 3, 1, 3)));
            chip.setOpaque(false);
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chip.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e) { onClick.run(); }
            });
            return chip;
        }
    }

    // -------------------- Incoming invites --------------------

    /**
     * One incoming-invite card. Receiver-side only — the sender has already
     * picked style + location, so the receiver sees the full proposed match
     * upfront and only has to choose Accept or Decline.
     *
     * <p>Layout:
     * <pre>
     * +---------------------------------------------+
     * | Zezima             3rd Age      [Lookup ]  |   header (rank-coloured) + Lookup chip
     * | [NA-W] [NH] [Veng]                         |   region + style chips
     * | [MOD?] [Main] [Zerker] [Pure]              |   MOD? + build chips
     * | NH - Main @ Arena * 9:43                   |   style - build @ place * countdown
     * |   [ Accept Fight ]   [ Decline Fight ]     |   actions (full-width row)
     * +---------------------------------------------+
     * </pre>
     */
    private final class IncomingInvitePanel extends JPanel
    {
        private final LobbyMember sender;

        /** Read-only accessor used by {@link #onIncomingInviteCancelled}
         *  to rebuild {@link #incomingInviteNames} after removing a
         *  card. Package-private would be cleaner but the inner-class
         *  scope already restricts it to this file. */
        LobbyMember getSender() { return sender; }
        private final Style style;
        /** Build the sender picked for this specific invite (one of the
         *  receiver's advertised builds). Drives the info text alongside
         *  style + location. */
        private final BuildType build;
        private final String location;
        private final long expiresAt;
        private final Runnable onAccept;
        private final Runnable onDecline;
        private final Consumer<String> openProfile;
        private Timer countdown;
        private JLabel infoLabel;

        IncomingInvitePanel(LobbyMember sender, Style style, BuildType build,
                            String location, long ttlMs,
                            Runnable onAccept, Runnable onDecline, Consumer<String> openProfile)
        {
            this.sender = sender;
            this.style = style;
            this.build = build;
            this.location = location;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
            this.onAccept = onAccept;
            this.onDecline = onDecline;
            this.openProfile = openProfile;

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(new Color(0x33, 0x2a, 0x1e));
            setOpaque(true);
            // Amber LEFT bar + bottom divider — reads as "incoming notification".
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(
                    new MatteBorder(0, 3, 0, 0, new Color(0xff, 0xc1, 0x07)),
                    new MatteBorder(0, 0, 1, 0, new Color(0x40, 0x40, 0x40))),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));
            setAlignmentX(LEFT_ALIGNMENT);

            buildHeaderRow();
            add(Box.createVerticalStrut(3));
            buildChipsRow();
            // Mirrors the roster row layout: build chips go on their own line
            // directly below the [Region]+style strip so the card reads as
            // "same profile, fewer affordances".
            add(Box.createVerticalStrut(2));
            buildBuildsRow();
            add(Box.createVerticalStrut(3));
            buildInfoRow();
            add(Box.createVerticalStrut(4));
            buildActionRow();
            startCountdown();
        }

        @Override
        public Dimension getMaximumSize()
        {
            Dimension d = super.getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, d.height);
        }

        private void buildHeaderRow()
        {
            String rankLabel = RANK_LABELS[sender.peakRankIdx];
            Color rankColor = RankUtils.getRankColor(rankLabel);

            // Base: name spans full row width, paints its full text and
            // clips under the rank/lookup overlay (no ellipsis).
            String displayName = displayNameOf(sender);
            NonEllipsisLabel name = new NonEllipsisLabel(displayName);
            name.setFont(name.getFont().deriveFont(Font.BOLD, (float) ROW_FONT_PT));
            name.setForeground(rankColor);
            name.setToolTipText(displayName);

            JLabel rank = new JLabel(rankLabel);
            rank.setFont(rank.getFont().deriveFont(Font.BOLD, (float) ROW_FONT_PT));
            rank.setForeground(rankColor);
            rank.setHorizontalAlignment(SwingConstants.RIGHT);

            // [Lookup] chip — opens Player Lookup so the receiver can vet
            // the sender before accept/decline. NOT the accept path.
            JLabel lookup = new JLabel("Lookup");
            lookup.setFont(lookup.getFont().deriveFont(Font.BOLD, (float) (ROW_FONT_PT - 1)));
            lookup.setForeground(Color.WHITE);
            lookup.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x88, 0x88, 0x88), 1),
                BorderFactory.createEmptyBorder(1, 4, 1, 4)));
            lookup.setOpaque(false);
            lookup.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            lookup.setToolTipText("Open " + displayName + "'s profile in Player Lookup");
            lookup.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (openProfile != null) openProfile.accept(sender.name);
                }
            });

            // Overlay: rank + [Lookup] cluster, opaque card-bg so name
            // overflow is visually masked. 4px left inset = minimal
            // breathing room before the rank text. Inner BorderLayout's
            // 6px hgap is retained because rank and [Lookup] are two
            // distinct chips that should not visually merge.
            JPanel east = new JPanel(new BorderLayout(6, 0));
            east.setOpaque(true);
            east.setBackground(new Color(0x33, 0x2a, 0x1e));
            east.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
            east.add(rank, BorderLayout.CENTER);
            east.add(lookup, BorderLayout.EAST);

            OverlayRightRow row = new OverlayRightRow(name, east);
            row.setAlignmentX(LEFT_ALIGNMENT);
            add(row);
        }

        private void buildChipsRow()
        {
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            chips.setOpaque(false);
            chips.setAlignmentX(LEFT_ALIGNMENT);
            int chipFont = Math.max(10, ROW_FONT_PT - 3);
            chips.add(makeChipStatic(sender.region, true, Color.WHITE, chipFont));
            for (Style s : Style.values())
            {
                boolean advertised = sender.styles.contains(s);
                chips.add(makeChipStatic(s.label, advertised, new Color(0xff, 0xc1, 0x07), chipFont));
            }
            add(chips);
        }

        /** [MOD]? [Main] [Zerker] [Pure] — second chip row, cyan to distinguish
         *  from the yellow style chips above. MOD (red) sits to the left of
         *  the build chips on this row. Same advertised/dim pattern the
         *  roster row uses. */
        private void buildBuildsRow()
        {
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            chips.setOpaque(false);
            chips.setAlignmentX(LEFT_ALIGNMENT);
            int chipFont = Math.max(10, ROW_FONT_PT - 3);
            if (sender.isMod)
            {
                chips.add(makeChipStatic("MOD", true, new Color(0xff, 0x55, 0x55), chipFont));
            }
            Color buildColor = new Color(0x4f, 0xc3, 0xf7);
            for (BuildType a : BuildType.values())
            {
                boolean advertised = sender.builds.contains(a);
                chips.add(makeChipStatic(a.label, advertised, buildColor, chipFont));
            }
            add(chips);
        }

        private void buildInfoRow()
        {
            infoLabel = new JLabel(buildInfoText(System.currentTimeMillis()));
            // Match the Accept Fight / Decline Fight button font (BOLD, ROW_FONT_PT-1)
            // so the "Style - Build @ Place * X:XX" line is the visual
            // focal point of the card.
            infoLabel.setFont(infoLabel.getFont().deriveFont(Font.BOLD,
                (float) (ROW_FONT_PT - 1)));
            infoLabel.setForeground(new Color(0xdd, 0xdd, 0xdd));
            infoLabel.setAlignmentX(LEFT_ALIGNMENT);
            add(infoLabel);
        }

        private String buildInfoText(long now)
        {
            long remaining = Math.max(0, expiresAt - now);
            long totalSec = (remaining + 999) / 1000;
            long mins = totalSec / 60;
            long secs = totalSec % 60;
            // "Style - Build @ Place * X:XX" per spec — shared formatter
            // keeps this identical to the opponent line on Confirm/Waiting/MeetAt.
            return formatStyleBuildPlace(style, build, location)
                + " * " + String.format("%d:%02d", mins, secs);
        }

        private void buildActionRow()
        {
            JPanel actions = new JPanel(new GridLayout(1, 2, 6, 0));
            actions.setOpaque(false);
            actions.setAlignmentX(LEFT_ALIGNMENT);
            actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

            JButton accept = new JButton("Accept Fight");
            accept.setFont(accept.getFont().deriveFont(Font.BOLD, (float) (ROW_FONT_PT - 1)));
            accept.setMargin(new Insets(2, 6, 2, 6));
            accept.setFocusPainted(false);
            accept.setBackground(new Color(0x2e, 0x7d, 0x32));
            accept.setForeground(Color.WHITE);
            accept.setOpaque(true);
            accept.setBorderPainted(false);
            accept.addActionListener(e -> { stopCountdown(); onAccept.run(); });

            JButton decline = new JButton("Decline Fight");
            decline.setFont(decline.getFont().deriveFont(Font.BOLD, (float) (ROW_FONT_PT - 1)));
            decline.setMargin(new Insets(2, 6, 2, 6));
            decline.setFocusPainted(false);
            decline.setBackground(new Color(0x66, 0x33, 0x33));
            decline.setForeground(Color.WHITE);
            decline.setOpaque(true);
            decline.setBorderPainted(false);
            decline.addActionListener(e -> { stopCountdown(); onDecline.run(); });

            actions.add(accept);
            actions.add(decline);
            add(actions);
        }

        private void startCountdown()
        {
            countdown = new Timer(1000, ev ->
            {
                long now = System.currentTimeMillis();
                if (now >= expiresAt)
                {
                    stopCountdown();
                    onDecline.run();
                    return;
                }
                if (infoLabel != null) infoLabel.setText(buildInfoText(now));
            });
            countdown.setRepeats(true);
            countdown.start();
        }

        private void stopCountdown()
        {
            if (countdown != null)
            {
                countdown.stop();
                countdown = null;
            }
        }
    }

    /** Outer-class twin of {@link PlayerRow#makeChip}. */
    private static JLabel makeChipStatic(String text, boolean active, Color activeColor, int fontPt)
    {
        // See PlayerRow.makeChip for why this is a ChipLabel rather
        // than a vanilla JLabel — same Substance L&F rollover-erasure
        // bug applies to incoming-invite chips when the user hovers
        // the receiver-side card.
        ChipLabel chip = new ChipLabel(text);
        chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, (float) fontPt));
        chip.setForeground(active ? activeColor : new Color(0x55, 0x55, 0x55));
        Color borderColor = active ? activeColor : new Color(0x3a, 0x3a, 0x3a);
        chip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, 1),
            BorderFactory.createEmptyBorder(1, 5, 1, 5)));
        chip.setOpaque(false);
        return chip;
    }

    /** JLabel subclass whose {@link #paintComponent} bypasses the L&F UI
     *  delegate's text rendering — under Substance L&F (which RuneLite
     *  ships with), JLabels auto-truncate with {@code "…"} when their
     *  bounds are narrower than the rendered text's preferred width. By
     *  drawing the text ourselves with {@link Graphics2D#drawString}, the
     *  text simply clips at the component's right edge (no ellipsis).
     *
     *  Intended for player-name labels paired with an {@link OverlayRightRow}
     *  that lets the right-side rank chip visually mask the overflowing
     *  characters. Not a drop-in for general JLabels — single-line LTR
     *  text only, no icon, no border insets, vertical-centered baseline. */
    private static final class NonEllipsisLabel extends JLabel
    {
        NonEllipsisLabel(String text)
        {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getForeground());
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int baselineY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                String t = getText();
                if (t != null) g2.drawString(t, 0, baselineY);
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    /** Border-aware sibling of {@link NonEllipsisLabel} used for the
     *  per-row style/build/region chips ([Main], [Pure], [Any Build],
     *  [NA-W], etc.). Same rationale as NonEllipsisLabel — bypass the
     *  Substance L&F text-rendering path — but here the motivation is
     *  hover stability, not ellipsis suppression: under Substance, a
     *  vanilla JLabel parented inside a panel that has a row-level
     *  MouseListener fanout (PlayerRow's attachListenerRecursively
     *  installs the same adapter on every descendant) repaints with a
     *  blank foreground on hover state changes, making chip text
     *  intermittently disappear while the cursor sits over a row.
     *
     *  <p>Painting the text directly in paintComponent skips
     *  Substance's LabelUI entirely. The chip's CompoundBorder
     *  (line + empty padding) still paints normally because
     *  {@link JComponent#paintBorder(Graphics)} is independent of the
     *  L&F text path. Honours horizontal-alignment + border insets so
     *  centred and left-aligned chip variants both render correctly. */
    private static final class ChipLabel extends JLabel
    {
        ChipLabel(String text)
        {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getForeground());
                g2.setFont(getFont());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                String t = getText();
                if (t == null || t.isEmpty()) return;
                int textW = fm.stringWidth(t);
                // Centre vertically inside the inner content box (height
                // minus border insets). Centre or left-align horizontally
                // based on the JLabel's horizontalAlignment setting so
                // makeActionChip-style centred chips and the default
                // left-aligned chips both render at the right x.
                int innerH = getHeight() - in.top - in.bottom;
                int baselineY = in.top + ((innerH - fm.getHeight()) / 2) + fm.getAscent();
                int x = in.left;
                if (getHorizontalAlignment() == SwingConstants.CENTER)
                {
                    int innerW = getWidth() - in.left - in.right;
                    x = in.left + Math.max(0, (innerW - textW) / 2);
                }
                else if (getHorizontalAlignment() == SwingConstants.RIGHT
                    || getHorizontalAlignment() == SwingConstants.TRAILING)
                {
                    int innerW = getWidth() - in.left - in.right;
                    x = in.left + Math.max(0, innerW - textW);
                }
                g2.drawString(t, x, baselineY);
            }
            finally
            {
                g2.dispose();
            }
        }

        /** Substance L&F's LabelUI computes a preferred width using its
         *  own font metrics that under-report glyph widths for the
         *  derived bold 15pt run we use on the rank slider — the
         *  result is BorderLayout assigning a width slightly less
         *  than the rendered text needs, which Swing then clips at
         *  paint time. Recomputing preferred width from the actual
         *  AWT FontMetrics here gets the layout-time and paint-time
         *  metrics back in sync, so labels like "Bronze 3" /
         *  "3rd Age 1" get exactly the width they paint into.
         *
         *  Pure JLabels (no border, no icon) so the calc is just
         *  insets + text width + tiny safety margin. The +2px
         *  belt-and-braces guards against sub-pixel rounding when
         *  the parent uses a non-integer Graphics2D scale (HiDPI).
         *  Height defers to super so vertical-centring inside
         *  paintComponent agrees with the parent's row height. */
        @Override
        public Dimension getPreferredSize()
        {
            String t = getText();
            if (t == null || t.isEmpty()) return super.getPreferredSize();
            FontMetrics fm = getFontMetrics(getFont());
            Insets in = getInsets();
            int w = fm.stringWidth(t) + in.left + in.right + 2;
            int h = super.getPreferredSize().height;
            return new Dimension(w, h);
        }
    }

    /** Two-child header row where {@code base} spans the row's full width
     *  and {@code overlay} is right-anchored on top of it (in Swing
     *  z-order). The overlay must be opaque with the row's background
     *  colour — that's what visually masks any base content that would
     *  otherwise overflow into the overlay's territory.
     *
     *  Used for player-name rows so long names render their full text and
     *  visually clip under the rank/lookup cluster instead of ellipsizing.
     *  The 4px {@code EmptyBorder(0, 4, 0, 0)} the caller sets on the
     *  overlay provides the "minimal spacing" gap the user requested
     *  between the last visible character of the name and the rank text. */
    private static final class OverlayRightRow extends JPanel
    {
        private final JComponent base;
        private final JComponent overlay;

        OverlayRightRow(JComponent base, JComponent overlay)
        {
            super(null); // manual layout — neither BorderLayout nor BoxLayout supports z-stacked overlap
            setOpaque(false);
            this.base = base;
            this.overlay = overlay;
            // Swing paints children in REVERSE component-array order, so
            // index 0 is drawn last (= on top). Add overlay first so it
            // wins the z-order against the base.
            add(overlay);
            add(base);
        }

        @Override
        public void doLayout()
        {
            int w = getWidth();
            int h = getHeight();
            Dimension op = overlay.getPreferredSize();
            int ow = Math.min(op.width, w);
            overlay.setBounds(w - ow, 0, ow, h);
            // Base spans the entire row; its right portion is visually
            // masked by the overlay's opaque background where they overlap.
            base.setBounds(0, 0, w, h);
        }

        @Override
        public Dimension getPreferredSize()
        {
            Dimension bp = base.getPreferredSize();
            Dimension op = overlay.getPreferredSize();
            // Preferred = base + overlay so the parent BoxLayout knows the
            // "natural" width. Actual width comes from the parent and may
            // be smaller, in which case base clips under overlay.
            return new Dimension(bp.width + op.width, Math.max(bp.height, op.height));
        }

        @Override
        public Dimension getMaximumSize()
        {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public Dimension getMinimumSize()
        {
            // Allow the row to shrink horizontally to just the overlay's
            // width — the base happily clips under it.
            Dimension op = overlay.getPreferredSize();
            return new Dimension(op.width, getPreferredSize().height);
        }
    }

    /** Adds an incoming-invite card to the top stack and flips the sender's
     *  roster chip to [Lookup] for the lifetime of the invite. Driven by
     *  {@link #onIncomingInvite} listener pushes; never seeded inline.
     *
     *  <p>The accept / decline runnables forward to the {@link LobbyService}
     *  using the original {@link IncomingInvite} (so the server can match
     *  by {@code inviteId}). The ConfirmFight transition is NOT triggered
     *  here — it arrives asynchronously as an {@link #onFightProposed}
     *  push the server fires to both players once the receiver accepts. */
    private void addIncomingInvite(IncomingInvite invite)
    {
        if (invitesContainer == null || invite == null || invite.sender == null) return;
        final LobbyMember sender = invite.sender;
        final String inviteId = invite.inviteId;
        incomingInviteNames.add(sender.name);
        IncomingInvitePanel[] holder = new IncomingInvitePanel[1];
        Runnable accept = () ->
        {
            incomingInviteNames.remove(sender.name);
            if (inviteId != null) incomingCardsById.remove(inviteId);
            removeInvite(holder[0]);
            // Receiver flow: server creates the fight session and pushes
            // onFightProposed to both players — that listener swaps the
            // panel into ConfirmFight (30s window). Find-a-new-match exits
            // the view at any point.
            service.acceptInvite(invite);
            renderRoster();
        };
        Runnable decline = () ->
        {
            incomingInviteNames.remove(sender.name);
            if (inviteId != null) incomingCardsById.remove(inviteId);
            removeInvite(holder[0]);
            service.declineInvite(invite);
            renderRoster();
        };
        long ttlMs = Math.max(0L, invite.expiresAtEpochMs - System.currentTimeMillis());
        IncomingInvitePanel card = new IncomingInvitePanel(
            sender, invite.style, invite.build, invite.location, ttlMs,
            accept, decline,
            this::routeOpenProfile);
        holder[0] = card;
        if (inviteId != null) incomingCardsById.put(inviteId, card);
        invitesContainer.add(card);
        invitesContainer.add(Box.createVerticalStrut(4));
        refreshInvitesContainer();
        renderRoster();
    }

    private void removeInvite(IncomingInvitePanel card)
    {
        if (invitesContainer == null || card == null) return;
        // Drop the card + its trailing strut so the gap collapses too.
        Component[] kids = invitesContainer.getComponents();
        for (int i = 0; i < kids.length; i++)
        {
            if (kids[i] == card)
            {
                invitesContainer.remove(card);
                if (i + 1 < kids.length && kids[i + 1] instanceof Box.Filler)
                {
                    invitesContainer.remove(kids[i + 1]);
                }
                break;
            }
        }
        refreshInvitesContainer();
    }

    /** Hides the strip when empty so it doesn't reserve vertical space, AND
     *  applies the rank-range slider gate per-card: cards whose sender is
     *  outside the user's current min/max range are setVisible(false) (with
     *  their trailing strut) so the invite is invisible. The card object
     *  stays in the container so it reappears immediately if the user
     *  widens the slider — no need to rebuild + lose its countdown timer. */
    private void refreshInvitesContainer()
    {
        if (invitesContainer == null) return;
        Component[] kids = invitesContainer.getComponents();
        boolean anyVisible = false;
        for (int i = 0; i < kids.length; i++)
        {
            Component c = kids[i];
            if (c instanceof IncomingInvitePanel)
            {
                IncomingInvitePanel card = (IncomingInvitePanel) c;
                boolean inRange = rankInRange(card.sender);
                card.setVisible(inRange);
                // Hide the trailing strut too so the gap collapses with the card.
                if (i + 1 < kids.length && kids[i + 1] instanceof Box.Filler)
                {
                    kids[i + 1].setVisible(inRange);
                }
                if (inRange) anyVisible = true;
            }
        }
        invitesContainer.setVisible(anyVisible);
        invitesContainer.revalidate();
        invitesContainer.repaint();
    }

    /** Builds the human-readable rank labels (e.g. "Bronze 3", "3rd Age") in
     *  the same index order as {@link RankUtils#THRESHOLDS}. */
    private static String[] buildRankLabels()
    {
        String[] out = new String[RankUtils.THRESHOLDS.length];
        for (int i = 0; i < RankUtils.THRESHOLDS.length; i++)
        {
            String name = RankUtils.THRESHOLDS[i][0];
            String div = RankUtils.THRESHOLDS[i][1];
            out[i] = "0".equals(div) ? name : name + " " + div;
        }
        return out;
    }
}
