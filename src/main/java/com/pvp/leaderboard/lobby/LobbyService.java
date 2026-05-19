package com.pvp.leaderboard.lobby;

import java.util.Set;

/**
 * The seam between {@code MatchmakingLobbyPanel} and whatever drives lobby
 * state. Two known implementations:
 * <ul>
 *   <li>{@link DevLobbyFixture} — the mock data + auto-accept/auto-confirm
 *   timers extracted from the panel's pre-refactor inline mock code. Used
 *   for visual review and tests pre-backend.</li>
 *   <li>{@code WebSocketLobbyService} — the real production implementation
 *   that dispatches {@link com.pvp.leaderboard.lobby lobby/*} commands over
 *   the WebSocket established in {@code p1-plugin}. Lands at
 *   {@code p2-plugin-service} (see {@code docs/PLUGIN_PROGRESS.md}).</li>
 * </ul>
 *
 * <p>All methods are <b>fire-and-forget</b> from the panel's perspective —
 * outcomes (success, errors, opponent reactions) arrive asynchronously via
 * the registered {@link LobbyEventListener}. The panel never reads state
 * directly off this object; it only sends commands and renders events.
 * This mirrors the architecture's "server is source of truth; client only
 * renders" rule (see {@code SOCKET_LOBBY_ARCHITECTURE.md} → Phase 2 →
 * Plugin → {@code LobbyRoster}).
 *
 * <p>Implementations must marshal all listener callbacks onto the EDT
 * (Swing's event-dispatch thread). Both known implementations honour this
 * via {@code SwingUtilities.invokeLater}.
 *
 * <p>Created as part of {@code p1-plugin-mock-refactor}.
 */
public interface LobbyService
{
    /** Registers the single listener the service pushes events to. Replaces
     *  any previously-set listener. Pass {@code null} to clear. Must be set
     *  <b>before</b> calling {@link #start()} or {@link #joinLobby} — events
     *  fired before a listener is registered are dropped silently. */
    void setListener(LobbyEventListener listener);

    /** Begins background work (timers, socket connect, etc.). Idempotent —
     *  calling twice is a no-op. The panel calls this once on construction. */
    void start();

    /** Stops all background work and releases resources. Idempotent. The
     *  panel calls this when the user leaves the matchmaking sub-tab so
     *  mock timers don't keep firing. */
    void stop();

    /** Asks the server (or fixture) to add the local user to the lobby /
     *  update their advertised preferences. In mock mode this triggers the
     *  fixture to push an initial {@link LobbyEventListener#onRosterSnapshot
     *  onRosterSnapshot} + seed two demonstration incoming invites. Calling
     *  again with different preferences is treated as an update — the server
     *  is responsible for idempotency. */
    void joinLobby(String region, Set<Style> styles, Set<BuildType> builds,
                   int minDisplayRankIdx, int maxDisplayRankIdx);

    /** Asks the server to remove the local user from the lobby. Outstanding
     *  outgoing invites and active fight sessions are cancelled server-side. */
    void leaveLobby();

    /** Sends an invite to {@code opponent} with the picked {@code style},
     *  {@code build} (which of the opponent's advertised builds you want to
     *  fight), and {@code location} (sub-location string, e.g. "Arena",
     *  "Wildy", "FFA Portal", "Wilderness", "Clan Wars", or "" if the style
     *  has no sub-location). In mock mode this also schedules a 5-s
     *  auto-accept that fires {@link LobbyEventListener#onFightProposed}. */
    void sendInvite(LobbyMember opponent, Style style, BuildType build, String location);

    /** Cancels a pending outgoing invite to {@code opponent}. No-op if there
     *  is no outstanding invite for that opponent. */
    void cancelInvite(LobbyMember opponent);

    /** Accepts an incoming invite. Server transitions to the 30-s
     *  mutual-confirm phase and pushes {@link LobbyEventListener#onFightProposed}
     *  to both players. */
    void acceptInvite(IncomingInvite invite);

    /** Declines an incoming invite. No follow-up event is pushed to the
     *  recipient (the server removes the invite silently). */
    void declineInvite(IncomingInvite invite);

    /** Confirms the local user's side of the currently-active fight session.
     *  Server marks {@code confirmed_by[local_uuid]} and, if the peer has
     *  already confirmed, transitions to {@link LobbyEventListener#onMatchFound}.
     *  Otherwise the peer sees {@link LobbyEventListener#onFightConfirmedByPeer}. */
    void confirmFight();

    /** Adds {@code member} to the local user's block list. Subsequent
     *  invites between the two are dropped server-side; mutual hide policy
     *  applies (see architecture § Phase 2). */
    void block(LobbyMember member);

    /** Removes {@code member} from the local user's block list. Idempotent. */
    void unblock(LobbyMember member);

    /** {@code true} when the underlying transport (WebSocket in
     *  production, always-on in {@link DevLobbyFixture}) is in the
     *  open state. Drives the lobby panel's reconnect banner so the
     *  user gets visible feedback when the socket has dropped and
     *  the manager is in the slow-retry window. Default {@code true}
     *  keeps test fixtures + no-op services from showing the banner
     *  on construction. */
    default boolean isConnected() { return true; }

    /** Epoch ms when the underlying transport is scheduled to make
     *  its next reconnect attempt, or {@code 0} if no retry is
     *  pending (either because the socket is connected, or because
     *  the manager has no active intent — e.g. pre-login).
     *
     *  <p>Used by the panel's 1Hz banner ticker to render the
     *  "XX seconds remaining until next reconnect attempt" countdown.
     *  Default {@code 0} keeps test fixtures from feeding the panel a
     *  bogus countdown. */
    default long getNextReconnectAttemptEpochMs() { return 0L; }
}
