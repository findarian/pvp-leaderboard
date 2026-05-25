package com.pvp.leaderboard.lobby;

import java.util.Set;

/**
 * The seam between {@code MatchmakingLobbyPanel} and the lobby transport.
 * The production implementation is {@code WebSocketLobbyService}, which
 * dispatches {@code lobby/*} commands over the plugin's WebSocket
 * connection. {@link NoOpLobbyService} is the inert fallback used when no
 * service is injected.
 *
 * <p>All methods are <b>fire-and-forget</b> from the panel's perspective —
 * outcomes (success, errors, opponent reactions) arrive asynchronously via
 * the registered {@link LobbyEventListener}. The panel never reads state
 * directly off this object; it only sends commands and renders events.
 * This mirrors the "server is source of truth; client only renders" rule
 * that the matchmaking protocol is built on.
 *
 * <p>Implementations must marshal all listener callbacks onto the EDT
 * (Swing's event-dispatch thread); the production service does this via
 * {@code SwingUtilities.invokeLater}.
 */
public interface LobbyService
{
    /** Registers the single listener the service pushes events to. Replaces
     *  any previously-set listener. Pass {@code null} to clear. Must be set
     *  <b>before</b> calling {@link #start()} or {@link #joinLobby} — events
     *  fired before a listener is registered are dropped silently. */
    void setListener(LobbyEventListener listener);

    /** Begins background work (socket connect, scheduled retries, etc.).
     *  Idempotent — calling twice is a no-op. The panel calls this once
     *  on construction. */
    void start();

    /** Stops all background work and releases resources. Idempotent. The
     *  panel calls this when the user leaves the matchmaking sub-tab. */
    void stop();

    /** Asks the server to add the local user to the lobby or update their
     *  advertised preferences. Calling again with different preferences is
     *  treated as an update — the server is responsible for idempotency.
     *
     *  <p>{@code sortBucket} drives the per-row {@code rank_idx} +
     *  {@code peak_rank_idx} the server emits on every {@code lobby/roster}
     *  push for this viewer. Pass the canonical bucket key for the style
     *  the user has prioritised in their picks ({@code "nh" / "veng" /
     *  "multi" / "dmm"} or {@code "overall"} as a catch-all when no style
     *  is selected). The slider matchmaking gate filters on the
     *  {@code rank_idx} the server returns for this bucket, so it must
     *  reflect the user's primary advertised style or the slider gates
     *  the wrong rank.
     *
     *  <p>When the user toggles styles inside the lobby, callers should
     *  re-invoke this method with an updated {@code sortBucket} so the
     *  next roster push carries fresh per-bucket ranks. */
    void joinLobby(String region, Set<Style> styles, Set<BuildType> builds,
                   int minDisplayRankIdx, int maxDisplayRankIdx, String sortBucket);

    /** Asks the server to remove the local user from the lobby. Outstanding
     *  outgoing invites and active fight sessions are cancelled server-side.
     *
     *  <p>Equivalent to {@link #leaveLobby(boolean) leaveLobby(false)} — a
     *  permanent leave that clears the cached join args so a subsequent
     *  reconnect does not silently re-join the user. */
    default void leaveLobby() { leaveLobby(false); }

    /** Asks the server to remove the local user from the lobby.
     *
     *  <p>When {@code preserveReplayState} is {@code true}, the service
     *  keeps the last {@code joinLobby(...)} args around so that the
     *  next socket {@code onOpen} re-issues them automatically. This is
     *  the path the plugin uses for transient {@code LOGIN_SCREEN}
     *  disconnects: the server-side row must be removed (so peers stop
     *  seeing a dead invite target) but the user is going to log back
     *  in moments later and should be auto-re-joined.
     *
     *  <p>When {@code preserveReplayState} is {@code false}, the cached
     *  args are cleared and the user remains out of the lobby across
     *  reconnects until something explicitly calls {@link #joinLobby}
     *  again. This is the path for "Reset Options" and plugin
     *  shutdown. */
    void leaveLobby(boolean preserveReplayState);

    /** Sends an invite to {@code opponent} with the picked {@code style},
     *  {@code build} (which of the opponent's advertised builds you want to
     *  fight), and {@code location} (sub-location string, e.g. "Arena",
     *  "Wildy", "FFA Portal", "Wilderness", "Clan Wars", or "" if the style
     *  has no sub-location). */
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
     *  If the peer has already confirmed, the server transitions to
     *  {@link LobbyEventListener#onMatchFound}; otherwise the peer sees
     *  {@link LobbyEventListener#onFightConfirmedByPeer}. */
    void confirmFight();

    /** Adds {@code member} to the local user's block list. Subsequent
     *  invites between the two are dropped server-side; mutual hide policy
     *  applies. */
    void block(LobbyMember member);

    /** Removes {@code member} from the local user's block list. Idempotent. */
    void unblock(LobbyMember member);

    /** {@code true} when the underlying transport (WebSocket in
     *  production) is in the open state. Drives the lobby panel's
     *  reconnect banner so the user gets visible feedback when the
     *  socket has dropped and the manager is in the slow-retry
     *  window. Default {@code true} keeps the no-op service from
     *  showing the banner on construction. */
    default boolean isConnected() { return true; }

    /** Epoch ms when the underlying transport is scheduled to make
     *  its next reconnect attempt, or {@code 0} if no retry is
     *  pending (either because the socket is connected, or because
     *  the manager has no active intent — e.g. pre-login).
     *
     *  <p>Used by the panel's 1Hz banner ticker to render the
     *  "XX seconds remaining until next reconnect attempt" countdown.
     *  Default {@code 0} keeps the no-op service from feeding the
     *  panel a bogus countdown. */
    default long getNextReconnectAttemptEpochMs() { return 0L; }

    /** Hint that the user has just opened a Player Lookup for
     *  {@code playerName} (or otherwise triggered a fresh {@code /user}
     *  profile pull). Implementations should clear any negative shard
     *  caches keyed on this player and re-run lobby rank enrichment
     *  so a "Waiting" chip caused by a transient shard miss flips to
     *  the resolved rank without waiting for the next periodic
     *  retry. No-op for transports without a rank cache. */
    default void refreshRankForPlayer(String playerName) { /* no-op */ }
}
