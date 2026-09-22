package com.pvp.leaderboard.tournament;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One line of a tournament's standings (Plan 10 Part C). */
public final class StandingsRow
{
    public final int rank;
    public final String acctSha;
    public final String displayName;
    public final int points;
    public final int wins;
    public final int losses;
    public final int byes;
    /** active | dnf | dq | kicked | withdrawn. */
    public final String status;
    public final int removedRound;

    public StandingsRow(int rank, String acctSha, String displayName, int points, int wins, int losses, int byes, String status, int removedRound)
    {
        this.rank = rank;
        this.acctSha = acctSha;
        this.displayName = displayName;
        this.points = points;
        this.wins = wins;
        this.losses = losses;
        this.byes = byes;
        this.status = status;
        this.removedRound = removedRound;
    }

    public static StandingsRow fromJson(JsonObject o, int fallbackRank)
    {
        if (o == null) return null;
        String acct = JsonLenient.optString(o, "acct_sha", "");
        String name = JsonLenient.optString(o, "display_name", acct.length() >= 8 ? acct.substring(0, 8) : acct);
        return new StandingsRow(
            JsonLenient.optInt(o, "rank", fallbackRank),
            acct,
            name,
            JsonLenient.optInt(o, "points", 0),
            JsonLenient.optInt(o, "wins", 0),
            JsonLenient.optInt(o, "losses", 0),
            JsonLenient.optInt(o, "byes", 0),
            JsonLenient.optString(o, "status", "active"),
            JsonLenient.optInt(o, "removed_round", 0));
    }

    /** Rows in wire order; non-object entries are skipped. Never null. */
    public static List<StandingsRow> fromArray(JsonArray arr)
    {
        if (arr == null) return Collections.emptyList();
        List<StandingsRow> out = new ArrayList<>(arr.size());
        int i = 0;
        for (JsonElement e : arr)
        {
            i++;
            if (e == null || !e.isJsonObject()) continue;
            StandingsRow row = fromJson(e.getAsJsonObject(), i);
            if (row != null) out.add(row);
        }
        return out;
    }

    public boolean isActive()
    {
        return "active".equals(status);
    }

    /** {@code "DNF"} / {@code "DQ"} / {@code "kicked"} / {@code "withdrew"} / {@code ""} for active rows. */
    public String removedLabel()
    {
        switch (status == null ? "" : status)
        {
            case "dnf": return "DNF";
            case "dq": return "DQ";
            case "kicked": return "kicked";
            case "withdrawn": return "withdrew";
            case "dropped_unpaid": return "dropped";
            default: return "";
        }
    }
}
