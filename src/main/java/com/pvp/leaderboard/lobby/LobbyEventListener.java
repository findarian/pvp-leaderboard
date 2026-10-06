package com.pvp.leaderboard.lobby;

import java.util.*;

/**
 * Server-push callbacks the panel registers with a {@link LobbyService} via
 * {@link LobbyService#setListener}. Every callback is invoked on Swing's
 * event-dispatch thread (EDT) — implementations of {@link LobbyService}
 * MUST marshal via {@code SwingUtilities.invokeLater} so listeners can
 * directly mutate Swing components.
 *
 * <p>Mirrors the server-to-client lobby command set ({@code lobby/roster},
 * {@code lobby/invite_received}, etc.). Method names follow the
 * {@code onSomething} convention rather than mirroring the wire command
 * names verbatim, because some events have no 1-to-1 wire equivalent
 * (e.g. {@link #onFightConfirmedByPeer} maps to
 * {@code lobby/fight_confirmed_by_peer} but is named for what the panel
 * does with it).
 *
 * <p>All methods are {@code default} no-ops so implementations override
 * only the events they care about (e.g. unit tests that only assert one
 * callback fires).
 */
public interface LobbyEventListener
{

    /** An invite — either one the local user sent and the opponent
     *  accepted, OR one the local user just accepted — has transitioned to
     *  the mutual-confirm phase. Panel switches to its ConfirmFight
     *  view. Both players see this event simultaneously. */
    default void onFightProposed(FightSession session) {}

    /** The peer has confirmed the active fight session, but the local user
     *  hasn't yet. Panel flips the ConfirmFight subheader from
     *  "Waiting on other player" to "Confirmed by other player" (or shows
     *  "Confirmed by other player" if the local user is the slow one). */
    default void onFightConfirmedByPeer(String fightId) {}

    /** Both players have confirmed; here's the meeting world and place.
     *  Panel transitions to its terminal Meet-At view. */
    default void onMatchFound(MatchInfo match) {}

    /** The confirm window elapsed without both players confirming.
     *  Panel returns the user to the lobby with no penalty. */
    default void onFightSessionExpired(String fightId) {}

    /** An operation failed with a stable error code from the server's
     *  error envelope (e.g. {@code INVITE_EXPIRED}, {@code RANK_OUT_OF_RANGE},
     *  {@code BLOCKED}). Panel renders human-readable text via
     *  {@link LobbyErrors} — never the raw code. */
    default void onError(String code, String message) {}

    /** Authoritative snapshot of canonical player IDs (lowercased display
     *  names) the local user has blocked. Pushed once on join and after
     *  every block/unblock the server applies. Panel uses this to gray
     *  out matching rows in the roster. */
    default void onBlockListSnapshot(Set<String> blockedIds) {}

    /** Cross-device delta: the local user (on another device or this one)
     *  just blocked {@code playerId}. Panel adds to its block set and
     *  re-renders the row. Mostly redundant with {@link #onBlockListSnapshot}
     *  since the server also re-pushes the full snapshot, but the delta
     *  arrives first so the UI feels snappier. */
    default void onBlockAdded(String playerId) {}

    /** Cross-device delta inverse of {@link #onBlockAdded}. Panel removes
     *  {@code playerId} from its block set and re-renders the row. */
    default void onBlockRemoved(String playerId) {}
}
