package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;

/**
 * Server-push callbacks for the Swiss tournaments,
 * registered with the {@link TourneySvc}. Every callback is delivered
 * on the Swing EDT (the {@code TourneySvc} marshals), so
 * panels may touch Swing directly and the session tracker / overlay
 * supplier read plain volatile fields. All methods are {@code default}
 * no-ops.
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

    /** {@code tournament/bye}; {@code noShow} when its {@code reason} is {@code no_show}: a win because the opponent did
     *  not show up. */
    default void onBye(String tournamentId, int round, boolean noShow) {}

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

    /** Not a push: the socket (re)opened. The transport tells its
     *  listeners from the manager's connect hook, on the EDT, so a panel
     *  that asked for the list while the socket was down asks again. */
    default void onConnected() {}

    default void onGearCheck(String tournamentId, int round, long untilEpochS) {}

    default void onGearAck(String tournamentId, boolean ok) {}

    /** {@code tournament/no_show_recorded}: the no-show report counts at {@code effective_at}. */
    default void onNoShowRecorded(String tournamentId, String seriesId, long effectiveAtS) {}

    /** {@code tournament/no_show_reported}: the opponent reported the player absent; it counts at {@code effective_at}. */
    default void onNoShowReported(String tournamentId, String seriesId, long effectiveAtS) {}

    /** {@code tournament/here_recorded}. */
    default void onHereRecorded(String tournamentId, String seriesId) {}

    /** {@code tournament/opponent_here}: the reported opponent says they are here. */
    default void onOpponentHere(String tournamentId, String seriesId) {}

    /** {@code tournament/reentered}: the player is back in the event from {@code round}. */
    default void onReentered(String tournamentId, int round) {}

    /** {@code tournament/result_changed}: the player's round {@code round} result is now {@code result} ({@code win},
     *  {@code draw} or {@code none}); {@code winnerName} names the winner of a win. */
    default void onResultChanged(String tournamentId, int round, String result, String winnerName) {}
}
