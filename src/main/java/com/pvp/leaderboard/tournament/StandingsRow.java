package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

public final class StandingsRow
{
    public final int rank;
    public final String displayName;
    public final double points;
    public final int wins, losses, draws, byes;
    public final String status;
    /** The tier's label from the payload's {@code tier_labels} ("Adamant 3"), {@code null} when none applies. */
    public final String rankLabel;
    /** The prize in whole gp ({@code prize_gp}), -1 when the row has none. */
    public final long prizeGp;
    /** The prize came from the random draw: {@code prize_random} is a JSON {@code true}. */
    public final boolean prizeRandom;
    /** {@code round_done} is {@code true}: the player has a result in the current round. */
    public final boolean roundDone;

    private StandingsRow(JsonObject o, int fallbackRank, List<String> tierLabels)
    {
        String acct = optString(o, "acct_sha");
        int tier = optInt(o, "tier", -1);
        String label = tier >= 0 && tier < tierLabels.size() ? tierLabels.get(tier) : null;
        rank = optInt(o, "rank", fallbackRank);
        displayName = optString(o, "display_name", acct.length() >= 8 ? acct.substring(0, 8) : acct);
        points = optDouble(o, "points", 0.0);
        wins = Math.max(0, optInt(o, "wins", 0));
        losses = Math.max(0, optInt(o, "losses", 0));
        draws = Math.max(0, optInt(o, "draws", 0));
        byes = Math.max(0, optInt(o, "byes", 0));
        status = optString(o, "status", "active");
        rankLabel = label == null || label.trim().isEmpty() ? null : label.trim();
        prizeGp = optWhole(o, "prize_gp");
        prizeRandom = new JsonPrimitive(true).equals(o.get("prize_random"));
        roundDone = optBool(o, "round_done", false);
    }

    /** The payload's {@code tier_labels} in order, a non-string entry as {@code null}; empty when absent. */
    public static List<String> tierLabels(JsonObject payload)
    {
        List<String> out = new ArrayList<>();
        for (JsonElement e : optArray(payload, "tier_labels"))
        {
            out.add(str(e));
        }
        return out;
    }

    /** Rows in wire order with their labels from the payload's {@code tier_labels}; non-object entries are skipped. Never null. */
    public static List<StandingsRow> fromArray(JsonArray arr, List<String> tierLabels)
    {
        List<StandingsRow> out = new ArrayList<>(arr.size());
        int i = 0;
        for (JsonElement e : arr)
        {
            i++;
            if (e == null || !e.isJsonObject()) continue;
            out.add(new StandingsRow(e.getAsJsonObject(), i, tierLabels));
        }
        return out;
    }

    /** Any status but {@code active}. */
    public boolean removed()
    {
        return !"active".equals(status);
    }
}
