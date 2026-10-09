package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;

/**
 * Server-push callbacks for the Swiss tournaments (Plan 10 Part C / F.3),
 * registered with the {@link TourneySvc}. Every callback is delivered
 * on the Swing EDT (the {@code TourneySvc} marshals), so
 * panels may touch Swing directly and the session tracker / overlay
 * supplier read plain volatile fields. All methods are {@code default}
 * no-ops. Mirrors WEBSOCKET_PROTOCOL.md § 6.3 (server → client).
 */
public interface TournamentEventListener
{
    /** {@code tournament/list_response}. */
    default void onTournamentList(List<Tourney> tournaments) {}

    /** {@code tournament/registered} — the event + the caller's registration status. */
    default void onRegistered(Tourney tournament, String regStatus) {}

    /** {@code tournament/withdrawn}. */
    default void onWithdrawn(String tournamentId) {}

    /** {@code tournament/state} — the caller's registrations + the running
     *  tournament they are part of ({@code null} when none). */
    default void onTournamentState(List<Tourney> registrations, LiveTourney active) {}

    /** {@code tournament/standings} — after subscribe and on every change. */
    default void onStandings(TourneyBoard standings) {}

    /** {@code tournament/match_assigned} — a round opened with an opponent. */
    default void onMatchAssigned(MatchSeries series) {}

    /** {@code tournament/opponent_highlight} with every name the opponent is logged in with
     *  ({@code opponent_names}; the one name when the push carries no list). */
    default void onOpponentHighlight(String tournamentId, String opponentName, List<String> opponentNames) {}

    /** {@code tournament/opponent_highlight_clear}. */
    default void onOpponentHighlightClear(String tournamentId, String seriesId) {}

    /** {@code tournament/bye}. */
    default void onBye(String tournamentId, int round) {}

    default void onRoundEndCheck(String tournamentId, int round, String opponentName, long respondByS, String message) {}

    /** {@code tournament/removed} — dnf / dq / kicked / withdrawn / dropped_unpaid. */
    default void onRemoved(String tournamentId, String status, String reason, int round) {}

    /** {@code tournament/cancelled}. */
    default void onCancelled(String tournamentId, String reason) {}

    /** {@code tournament/finished} — winners {@code {top[], random[], seed}} + the final standings rows. */
    default void onFinished(String tournamentId, JsonObject winners, List<StandingsRow> standings) {}

    /** {@code tournament/problem_ack}. */
    default void onProblemAck() {}

    /** {@code error/tournament} — stable code + message; {@code cmd} echoes the rejected cmd. */
    default void onTournamentError(String code, String message, String cmd) {}

    /** Not a push: the socket (re)opened (set 7). The transport tells its
     *  listeners from the manager's connect hook, on the EDT, so a panel
     *  that asked for the list while the socket was down asks again. */
    default void onConnected() {}

    default void onGearCheck(String tournamentId, int round, long untilEpochS) {}

    default void onGearAck(String tournamentId, boolean ok) {}

    /** {@code tournament/no_show_recorded}: the no-show report counts at {@code effective_at}. */
    default void onNoShowRecorded(String tournamentId, String seriesId, long effectiveAtS) {}
}
