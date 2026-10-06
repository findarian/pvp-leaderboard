package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

public final class LiveTourney
{
    public final String tournamentId;
    public final String name;
    public final String status;
    public final int round;
    public final int rounds;
    public final int etaS;
    public final long breakUntil;
    /** {@code null} on a bye or between rounds. */
    public final MatchSeries series;
    public final boolean bye;
    public final List<StandingsRow> standings;
    /** -1 when unknown. */
    public final int myRank;
    public final GearSet gearSet;
    public final int gearPrepSec;
    public final long gearDeadline;
    public final String location;

    private LiveTourney(JsonObject o, String id)
    {
        tournamentId = id;
        name = optString(o, "name", id);
        status = optString(o, "status", "running");
        round = optInt(o, "round", 0);
        rounds = optInt(o, "rounds", 0);
        etaS = optInt(o, "eta_s", -1);
        breakUntil = optLong(o, "break_until", 0L);
        series = MatchSeries.fromJson(optObject(o, "series"), id, optLong(o, "deadline_at", 0L));
        bye = optBool(o, "bye", false);
        standings = StandingsRow.fromArray(optArray(o, "standings"), StandingsRow.tierLabels(o));
        myRank = optInt(o, "my_rank", -1);
        gearSet = GearSet.fromJson(o.get("gear_set"));
        gearPrepSec = Math.max(0, optInt(o, "gear_prep_sec", 0));
        gearDeadline = Math.max(0L, optLong(o, "gear_check_until", 0L));
        location = Tourney.textOf(o, "location");
    }

    /** {@code null} for a null / non-object / id-less payload (= not in a running tournament). */
    public static LiveTourney fromJson(JsonObject o)
    {
        if (o == null) return null;
        String id = optString(o, "tournament_id");
        return id.isEmpty() ? null : new LiveTourney(o, id);
    }

    public boolean isRunning()
    {
        return "running".equals(status);
    }
}
