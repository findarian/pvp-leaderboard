package com.pvp.leaderboard.tournament;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.pvp.leaderboard.util.JsonLenient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StandingsRow
{
    public final int rank;
    public final String acctSha;
    public final String displayName;
    public final double points;
    public final int wins;
    public final int losses;
    public final int draws;
    public final int byes;
    public final String status;
    public final int removedRound;
    /** The player's tournament tier ({@code tier}, 0..24), -1 when the row carries none. */
    public final int tier;
    /** The tier's label from the payload's {@code tier_labels} ("Adamant 3"), {@code null} when none applies. */
    public final String rankLabel;

    public StandingsRow(int rank, String acctSha, String displayName, double points, int wins, int losses, int byes, String status, int removedRound)
    {
        this(rank, acctSha, displayName, points, wins, losses, 0, byes, status, removedRound);
    }

    public StandingsRow(int rank, String acctSha, String displayName, double points, int wins, int losses, int draws, int byes, String status, int removedRound)
    {
        this(rank, acctSha, displayName, points, wins, losses, draws, byes, status, removedRound, -1, null);
    }

    public StandingsRow(int rank, String acctSha, String displayName, double points, int wins, int losses, int draws, int byes, String status, int removedRound,
                        int tier, String rankLabel)
    {
        this.rank = rank;
        this.acctSha = acctSha;
        this.displayName = displayName;
        this.points = points;
        this.wins = wins;
        this.losses = losses;
        this.draws = Math.max(0, draws);
        this.byes = byes;
        this.status = status;
        this.removedRound = removedRound;
        this.tier = tier < 0 ? -1 : tier;
        this.rankLabel = rankLabel == null || rankLabel.trim().isEmpty() ? null : rankLabel.trim();
    }

    public static StandingsRow fromJson(JsonObject o, int fallbackRank)
    {
        return fromJson(o, fallbackRank, Collections.<String>emptyList());
    }

    /** {@code tierLabels} is the payload's {@code tier_labels}; the row's {@code tier} indexes it for its label. */
    public static StandingsRow fromJson(JsonObject o, int fallbackRank, List<String> tierLabels)
    {
        if (o == null) return null;
        String acct = JsonLenient.optString(o, "acct_sha", "");
        String name = JsonLenient.optString(o, "display_name", acct.length() >= 8 ? acct.substring(0, 8) : acct);
        Double points = pointsOrNull(o.get("points"));
        int tier = tierOf(o.get("tier"));
        return new StandingsRow(
            JsonLenient.optInt(o, "rank", fallbackRank),
            acct,
            name,
            points == null ? 0.0 : points,
            JsonLenient.optInt(o, "wins", 0),
            JsonLenient.optInt(o, "losses", 0),
            JsonLenient.optInt(o, "draws", 0),
            JsonLenient.optInt(o, "byes", 0),
            JsonLenient.optString(o, "status", "active"),
            JsonLenient.optInt(o, "removed_round", 0),
            tier,
            tierLabels != null && tier >= 0 && tier < tierLabels.size() ? tierLabels.get(tier) : null);
    }

    /** The payload's {@code tier_labels} in order, a non-string entry as {@code null}; empty when absent. */
    public static List<String> tierLabels(JsonObject payload)
    {
        List<String> out = new ArrayList<>();
        for (JsonElement e : JsonLenient.optArray(payload, "tier_labels"))
        {
            out.add(e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null);
        }
        return out;
    }

    private static boolean isNumber(JsonElement e)
    {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    /** A whole, non-negative number; -1 for anything else. */
    private static int tierOf(JsonElement e)
    {
        if (!isNumber(e)) return -1;
        double v = e.getAsDouble();
        return Double.isNaN(v) || Double.isInfinite(v) || v < 0 ? -1 : (int) v;
    }

    static Double pointsOrNull(JsonElement e)
    {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return null;
        double value;
        try
        {
            value = p.isNumber() ? p.getAsDouble() : new BigDecimal(p.getAsString().trim()).doubleValue();
        }
        catch (RuntimeException ex)
        {
            return null;
        }
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }

    /** Rows in wire order; non-object entries are skipped. Never null. */
    public static List<StandingsRow> fromArray(JsonArray arr)
    {
        return fromArray(arr, Collections.<String>emptyList());
    }

    /** Rows in wire order with their labels from the payload's {@code tier_labels}; non-object entries are skipped. Never null. */
    public static List<StandingsRow> fromArray(JsonArray arr, List<String> tierLabels)
    {
        if (arr == null) return Collections.emptyList();
        List<StandingsRow> out = new ArrayList<>(arr.size());
        int i = 0;
        for (JsonElement e : arr)
        {
            i++;
            if (e == null || !e.isJsonObject()) continue;
            StandingsRow row = fromJson(e.getAsJsonObject(), i, tierLabels);
            if (row != null) out.add(row);
        }
        return out;
    }

    public boolean isActive()
    {
        return "active".equals(status);
    }

    public String removedLabel()
    {
        switch (status == null ? "" : status)
        {
            case "dnf": return "DNF";
            case "dq": return "DQ";
            case "kicked": return "kicked";
            case "withdrawn": return "withdrew";
            case "dropped_unpaid": return "dropped";
            case "dropped_gear": return "kit";
            default: return "";
        }
    }
}
