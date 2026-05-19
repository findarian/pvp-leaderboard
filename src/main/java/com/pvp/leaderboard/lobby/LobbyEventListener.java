package com.pvp.leaderboard.lobby;

import java.util.List;
import java.util.Set;

/**
 * Server-push callbacks the panel registers with a {@link LobbyService} via
 * {@link LobbyService#setListener}. Every callback is invoked on Swing's
 * event-dispatch thread (EDT) — implementations of {@link LobbyService}
 * MUST marshal via {@code SwingUtilities.invokeLater} so listeners can
 * directly mutate Swing components.
 *
 * <p>Mirrors the architecture's server-to-client command set
 * ({@code lobby/roster}, {@code lobby/invite_received}, etc. — see
 * {@code docs/SOCKET_LOBBY_ARCHITECTURE.md} → Phase 2 → "Server-only
 * outbound cmds"). Naming uses {@code onSomething} convention rather than
 * mirroring the wire command names verbatim because some events have no
 * 1-to-1 wire equivalent in this release (e.g.
 * {@link #onFightConfirmedByPeer} maps to {@code lobby/fight_confirmed_by_peer}
 * but is named for what the panel does with it).
 *
 * <p>Created as part of {@code p1-plugin-mock-refactor}. Default no-op
 * methods let implementations override only the events they care about
 * (e.g. unit tests that only assert one callback fires).
 */
public interface LobbyEventListener
{
    /** Full roster snapshot pushed after a {@code lobby/join} or whenever
     *  membership changes server-side. The panel coalesces visible roster
     *  updates to one render per 60 s — so this can fire as often as the
     *  server pleases without flickering the UI under the user's cursor.
     *  Replaces members verbatim (no merge / diff). */
    default void onRosterSnapshot(List<LobbyMember> roster) {}

    /** Periodic presence count broadcast (architecture: {@code presence/count}).
     *  Distinct from {@link #onRosterSnapshot} which is the full member list;
     *  this is just a cheap "X players online" header update. */
    default void onPresenceCount(int totalOnline) {}

    /** Someone in the lobby sent the local user an invite. Panel pins an
     *  invite card at the top of the roster with Accept / Decline buttons
     *  and a TTL countdown derived from {@link IncomingInvite#getExpiresAtEpochMs}. */
    default void onIncomingInvite(IncomingInvite invite) {}

    /** A previously-pushed {@link IncomingInvite} was cancelled by the
     *  sender. Panel removes the matching card. */
    default void onIncomingInviteCancelled(String inviteId) {}

    /** An invite — either one the local user sent and the opponent
     *  accepted, OR one the local user just accepted — has transitioned to
     *  the 30-s mutual-confirm phase. Panel switches to its ConfirmFight
     *  view. Both players see this event simultaneously. */
    default void onFightProposed(FightSession session) {}

    /** The peer has confirmed the active fight session, but the local user
     *  hasn't yet. Panel flips the ConfirmFight subheader from
     *  "Waiting on other player" to "Confirmed by other player" (or shows
     *  "Confirmed by other player" if the local user is the slow one). */
    default void onFightConfirmedByPeer(String fightSessionId) {}

    /** Both players have confirmed; here's the meeting world and place.
     *  Panel transitions to its terminal Meet-At view. */
    default void onMatchFound(MatchInfo match) {}

    /** The 30-s confirm window elapsed without both players confirming.
     *  Panel returns the user to the lobby with no penalty (matches the
     *  mock-UI "Find a new match" exit path). */
    default void onFightSessionExpired(String fightSessionId) {}

    /** An operation failed with a stable error code from the architecture's
     *  error envelope (e.g. {@code INVITE_EXPIRED}, {@code RANK_OUT_OF_RANGE},
     *  {@code BLOCKED}). Panel renders human-readable text via the
     *  {@code xc-plugin-localized-errors} table — never the raw code. */
    default void onError(String code, String message) {}

    // ---------------------------------------------------------------
    // Cross-device-sync pushes added 2026-05-18 per
    // BACKEND_HANDOFF_TO_PLUGIN.md §3.3. Only onBlockListSnapshot is
    // functionally required (drives the gray-out feature); the other
    // three are cross-device sync hooks the panel can ignore for now.
    // ---------------------------------------------------------------

    /** Server's ACK of a {@code lobby/join} cmd. Useful for surfacing
     *  "joined as observer" feedback or starting a session-scoped clock;
     *  the panel currently doesn't render anything off it but the hook
     *  exists for future telemetry. */
    default void onJoinedEcho() {}

    /** Authoritative snapshot of UUIDs the local user has blocked.
     *  Pushed once on join + after every block/unblock the server applies.
     *  Panel uses this to gray out matching rows in the roster. */
    default void onBlockListSnapshot(Set<String> blockedUuids) {}

    /** Cross-device delta: the local user (on another device or this
     *  one) just blocked {@code uuid}. Panel adds to its block set and
     *  re-renders the row. Mostly redundant with {@link #onBlockListSnapshot}
     *  since the server also re-pushes the full snapshot, but the delta
     *  arrives first so the UI feels snappier. */
    default void onBlockAdded(String uuid) {}

    /** Cross-device delta inverse of {@link #onBlockAdded}. Panel removes
     *  {@code uuid} from its block set and re-renders the row. */
    default void onBlockRemoved(String uuid) {}
}
