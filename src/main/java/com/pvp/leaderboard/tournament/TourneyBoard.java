package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/**
 * A {@code tournament/standings} push (Plan 10 Part C / C.8): the live
 * leaderboard of one tournament plus the round clock. Pushed at once
 * after {@code tournament/subscribe} and on every score change while
 * subscribed; the same payload feeds the website (IoT) and Discord.
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

    private TourneyBoard(JsonObject o, String id)
    {
        tournamentId = id;
        status = optString(o, "status");
        round = optInt(o, "round", 0);
        rounds = optInt(o, "rounds", 0);
        etaS = optInt(o, "eta_s", -1);
        breakUntil = optLong(o, "break_until", 0L);
        extended = optInt(o, "extended", 0);
        rows = StandingsRow.fromArray(optArray(o, "standings"), StandingsRow.tierLabels(o));
        gearDeadline = Math.max(0L, optLong(o, "gear_check_until", 0L));
    }

    /** {@code null} without a {@code tournament_id}. */
    public static TourneyBoard fromJson(JsonObject o)
    {
        if (o == null) return null;
        String id = optString(o, "tournament_id");
        return id.isEmpty() ? null : new TourneyBoard(o, id);
    }

    public boolean isFinished()
    {
        return "finished".equals(status);
    }
}
