package com.pvp.leaderboard.lobby;

import java.util.*;

/**
 * The seam between {@code MatchmakingLobbyPanel} and the lobby transport.
 * The production implementation is {@code WebSocketLobbyService}, which
 * dispatches {@code lobby/*} commands over the plugin's WebSocket
 * connection. {@link NoOpLobby} is the inert fallback used when no
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

    /** Confirms the local user's side of the currently-active fight session.
     *  If the peer has already confirmed, the server transitions to
     *  {@link LobbyEventListener#onMatchFound}; otherwise the peer sees
     *  {@link LobbyEventListener#onFightConfirmedByPeer}. */
    void confirmFight();

    /** Same as {@link #block(LobbyMember)} for callers that hold only
     *  a player id (e.g. the Player-Lookup-tab Block button on
     *  {@code Dashboard}, which has no full {@link LobbyMember}).
     *  The wire encoding is identical: {@code lobby/block} with
     *  {@code blocked_player_id=<id>}. Null/empty/blank ids are
     *  silently dropped — defence-in-depth against a UI bug shipping
     *  a malformed frame to the server (which auto-bans on
     *  malformed input). Default impl is a no-op so the
     *  {@link NoOpLobby} test stub doesn't have to override. */
    default void blockById(String playerId) { /* no-op default */ }

    /** Same as {@link #unblock(LobbyMember)} for callers that hold
     *  only a player id. Wire encoding is identical to
     *  {@link #unblock(LobbyMember)}. Idempotent; null/empty/blank
     *  ids are silently dropped. */
    default void unblockById(String playerId) { /* no-op default */ }

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
    default long getRetryAtMs() { return 0L; }
}
