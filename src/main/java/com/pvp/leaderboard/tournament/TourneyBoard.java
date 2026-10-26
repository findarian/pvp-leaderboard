package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/**
 * A {@code tournament/standings} push, or a
 * {@code tournament/standings_delta} applied to it: the live leaderboard
 * of one tournament plus the round clock.
 */
public final class TourneyBoard
{
    public final String tournamentId;
    public final String status;
    public final int round;
    public final int rounds;
    /** Seconds left in the round, -1 when no round is open. */
    public final int etaS;
    /** Epoch seconds, 0 when not on a break. */
    public final long breakUntil;
    public final int extended;
    public final List<StandingsRow> rows;
    public final long gearDeadline;

    private TourneyBoard(JsonObject o, String id, List<StandingsRow> rows)
    {
        tournamentId = id;
        status = optString(o, "status");
        round = optInt(o, "round", 0);
        rounds = optInt(o, "rounds", 0);
        etaS = optInt(o, "eta_s", -1);
        breakUntil = optLong(o, "break_until", 0L);
        extended = optInt(o, "extended", 0);
        this.rows = rows;
        gearDeadline = Math.max(0L, optLong(o, "gear_check_until", 0L));
    }

    /** {@code null} without a {@code tournament_id}. */
    public static TourneyBoard fromJson(JsonObject o)
    {
        if (o == null) return null;
        String id = optString(o, "tournament_id");
        return id.isEmpty() ? null : new TourneyBoard(o, id, StandingsRow.fromArray(optArray(o, "standings"), StandingsRow.tierLabels(o)));
    }

    /** {@code o}'s id, status, round and clock with {@code rows}. */
    static TourneyBoard of(JsonObject o, List<StandingsRow> rows)
    {
        return new TourneyBoard(o, optString(o, "tournament_id"), rows);
    }

    public boolean isFinished()
    {
        return "finished".equals(status);
    }
}
