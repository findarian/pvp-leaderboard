package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.List;

/**
 * A {@code tournament/standings} push (Plan 10 Part C / C.8): the live
 * leaderboard of one tournament plus the round clock. Pushed at once
 * after {@code tournament/subscribe} and on every score change while
 * subscribed; the same payload feeds the website (IoT) and Discord.
 */
public final class TournamentStandings
{
    public final String tournamentId;
    public final String name;
    public final String status;
    public final int round;
    public final int rounds;
    /** Epoch seconds, 0 when no round is open. */
    public final long deadlineAt;
    /** Seconds left in the round, -1 when no round is open. */
    public final int etaS;
    /** Epoch seconds, 0 when not on a break. */
    public final long breakUntil;
    public final int extended;
    public final List<StandingsRow> rows;
    public final long updatedAt;
    /** The prize draw once finished ({@code {top[], random[], seed}}), else {@code null}. */
    public final JsonObject winners;

    public TournamentStandings(String tournamentId, String name, String status, int round, int rounds, long deadlineAt, int etaS,
                               long breakUntil, int extended, List<StandingsRow> rows, long updatedAt, JsonObject winners)
    {
        this.tournamentId = tournamentId;
        this.name = name;
        this.status = status;
        this.round = round;
        this.rounds = rounds;
        this.deadlineAt = deadlineAt;
        this.etaS = etaS;
        this.breakUntil = breakUntil;
        this.extended = extended;
        this.rows = rows;
        this.updatedAt = updatedAt;
        this.winners = winners;
    }

    /** {@code null} without a {@code tournament_id}. */
    public static TournamentStandings fromJson(JsonObject o)
    {
        if (o == null) return null;
        String id = JsonLenient.optString(o, "tournament_id", "");
        if (id.isEmpty()) return null;
        Integer eta = JsonLenient.optInteger(o, "eta_s");
        return new TournamentStandings(
            id,
            JsonLenient.optString(o, "name", id),
            JsonLenient.optString(o, "status", ""),
            JsonLenient.optInt(o, "round", 0),
            JsonLenient.optInt(o, "rounds", 0),
            JsonLenient.optLong(o, "deadline_at", 0L),
            eta == null ? -1 : eta,
            JsonLenient.optLong(o, "break_until", 0L),
            JsonLenient.optInt(o, "extended", 0),
            StandingsRow.fromArray(JsonLenient.optArray(o, "standings")),
            JsonLenient.optLong(o, "updated_at", 0L),
            JsonLenient.optObject(o, "winners"));
    }

    public boolean isFinished()
    {
        return "finished".equals(status);
    }

    /** Local-clock ETA: the server's {@code deadline_at} minus now, floored at 0; -1 without a deadline. */
    public int secondsLeft(long nowEpochS)
    {
        if (deadlineAt <= 0) return -1;
        return (int) Math.max(0L, deadlineAt - nowEpochS);
    }
}
