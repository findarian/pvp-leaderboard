package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * Server-push callbacks for the Swiss tournaments (Plan 10 Part C / F.3),
 * registered with a {@link TournamentService}. Every callback is delivered
 * on the Swing EDT (the {@code WebSocketTournamentService} marshals), so
 * panels may touch Swing directly and the session tracker / overlay
 * supplier read plain volatile fields. All methods are {@code default}
 * no-ops. Mirrors WEBSOCKET_PROTOCOL.md § 6.3 (server → client).
 */
public interface TournamentEventListener
{
    /** {@code tournament/list_response}. */
    default void onTournamentList(List<TournamentSummary> tournaments, long nowEpochS) {}

    /** {@code tournament/registered} — the event + the caller's registration status. */
    default void onRegistered(TournamentSummary tournament, String registrationStatus) {}

    /** {@code tournament/withdrawn}. */
    default void onWithdrawn(String tournamentId, String status) {}

    /** {@code tournament/state} — the caller's registrations + the running
     *  tournament they are part of ({@code null} when none). */
    default void onTournamentState(List<TournamentSummary> registrations, TournamentActive active) {}

    /** {@code tournament/standings} — after subscribe and on every change. */
    default void onStandings(TournamentStandings standings) {}

    /** {@code tournament/match_assigned} — a round opened with an opponent. */
    default void onMatchAssigned(TournamentSeries series) {}

    /** {@code tournament/opponent_highlight} — outline this player until {@code untilEpochS}. */
    default void onOpponentHighlight(String tournamentId, String opponentName, String opponentAcctSha, long untilEpochS) {}

    /** {@code tournament/opponent_highlight_clear}. */
    default void onOpponentHighlightClear(String tournamentId, String seriesId) {}

    /** {@code tournament/bye}. */
    default void onBye(String tournamentId, int round) {}

    /** {@code tournament/round_end_check} — reply within 30 s or be DNF'd (C.7). */
    default void onRoundEndCheck(String tournamentId, int round, String seriesId, String opponentName, long respondByEpochS) {}

    /** {@code tournament/removed} — dnf / dq / kicked / withdrawn / dropped_unpaid. */
    default void onRemoved(String tournamentId, String status, String reason, int round) {}

    /** {@code tournament/cancelled}. */
    default void onCancelled(String tournamentId, String reason) {}

    /** {@code tournament/finished} — winners {@code {top[], random[], seed}} + the top 10. */
    default void onFinished(String tournamentId, JsonObject winners, List<StandingsRow> standings) {}

    /** {@code tournament/problem_ack}. */
    default void onProblemAck(String tournamentId) {}

    /** {@code error/tournament} — stable code + message; {@code cmd} echoes the rejected cmd. */
    default void onTournamentError(String code, String message, String cmd) {}

    /** Not a push: the socket (re)opened (set 7). The transport tells its
     *  listeners from the manager's connect hook, on the EDT, so a panel
     *  that asked for the list while the socket was down asks again. */
    default void onSocketConnected() {}
}
