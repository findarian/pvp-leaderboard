package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.List;

/**
 * {@code tournament/state.active} (Plan 10 Part C / F.3): the running
 * tournament the local player is part of — round clock, their series (or
 * a bye), the top of the standings and their own place.
 */
public final class TournamentActive
{
    public final String tournamentId;
    public final String name;
    public final String status;
    public final int round;
    public final int rounds;
    public final long deadlineAt;
    public final int etaS;
    public final long breakUntil;
    /** {@code null} on a bye or between rounds. */
    public final TournamentSeries series;
    public final boolean bye;
    public final List<StandingsRow> standings;
    /** -1 when unknown. */
    public final int myRank;
    public final int myPoints;

    public TournamentActive(String tournamentId, String name, String status, int round, int rounds, long deadlineAt, int etaS,
                            long breakUntil, TournamentSeries series, boolean bye, List<StandingsRow> standings, int myRank, int myPoints)
    {
        this.tournamentId = tournamentId;
        this.name = name;
        this.status = status;
        this.round = round;
        this.rounds = rounds;
        this.deadlineAt = deadlineAt;
        this.etaS = etaS;
        this.breakUntil = breakUntil;
        this.series = series;
        this.bye = bye;
        this.standings = standings;
        this.myRank = myRank;
        this.myPoints = myPoints;
    }

    /** {@code null} for a null / non-object / id-less payload (= not in a running tournament). */
    public static TournamentActive fromJson(JsonObject o)
    {
        if (o == null) return null;
        String id = JsonLenient.optString(o, "tournament_id", "");
        if (id.isEmpty()) return null;
        long deadline = JsonLenient.optLong(o, "deadline_at", 0L);
        Integer eta = JsonLenient.optInteger(o, "eta_s");
        Integer myRank = JsonLenient.optInteger(o, "my_rank");
        Integer myPoints = JsonLenient.optInteger(o, "my_points");
        return new TournamentActive(
            id,
            JsonLenient.optString(o, "name", id),
            JsonLenient.optString(o, "status", "running"),
            JsonLenient.optInt(o, "round", 0),
            JsonLenient.optInt(o, "rounds", 0),
            deadline,
            eta == null ? -1 : eta,
            JsonLenient.optLong(o, "break_until", 0L),
            TournamentSeries.fromJson(JsonLenient.optObject(o, "series"), id, deadline),
            JsonLenient.optBool(o, "bye", false),
            StandingsRow.fromArray(JsonLenient.optArray(o, "standings")),
            myRank == null ? -1 : myRank,
            myPoints == null ? -1 : myPoints);
    }

    public boolean isRunning()
    {
        return "running".equals(status);
    }
}
