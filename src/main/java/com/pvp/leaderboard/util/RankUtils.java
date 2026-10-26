package com.pvp.leaderboard.util;

import com.google.gson.*;
import java.awt.*;
import java.util.*;

public class RankUtils
{
    private static final Map<String, Color> RANK_COLORS = new HashMap<>();

    /** The wire's 25 rank slots (queue range, event limits), lowest first: Bronze 3 ... Dragon 1, then 3rd Age. */
    public static final String[] SLOTS = new String[25];
    /** The rating's 21 divisions, lowest first: the slots without Black and 3rd Age. */
    public static final String[] DIVISIONS = new String[21];

    static
    {
        RANK_COLORS.put("Bronze", new Color(0xb87333));
        RANK_COLORS.put("Iron", Color.LIGHT_GRAY);
        RANK_COLORS.put("Steel", new Color(154, 162, 166));
        RANK_COLORS.put("Black", Color.GRAY);
        RANK_COLORS.put("Mithril", new Color(98, 104, 199));
        RANK_COLORS.put("Adamant", new Color(26, 139, 111));
        RANK_COLORS.put("Rune", new Color(78, 159, 227));
        RANK_COLORS.put("Dragon", new Color(0xe53935));
        RANK_COLORS.put("3rd", Color.WHITE);
        String[] families = {"Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune", "Dragon"};
        for (int i = 0; i < 24; i++) SLOTS[i] = families[i / 3] + " " + (3 - i % 3);
        SLOTS[24] = "3rd Age";
        for (int p = 0; p < 21; p++) DIVISIONS[p] = SLOTS[slotOf(p)];
    }

    /** The slot of division {@code p}: 0-8 as they are, 9-20 as 12-23. */
    public static int slotOf(int p)
    {
        return p < 9 ? p : p + 3;
    }

    /** A /user profile's v3 card of {@code bucket} ({@code buckets.<bucket>.v3}); null when absent or not an object. */
    public static JsonObject card(JsonObject profile, String bucket)
    {
        return JsonLenient.optObject(JsonLenient.optObject(JsonLenient.optObject(profile, "buckets"), bucket), "v3");
    }

    /** The {@code division} text of a v3 object (a card, a peak, a shard or board entry); null when absent, blank or not text. */
    public static String division(JsonObject o)
    {
        String d = o == null ? null : JsonLenient.str(o.get("division"));
        return d == null || d.trim().isEmpty() ? null : d;
    }

    /** The colour of a rank label's first word (the rank family); grey when unknown. */
    public static Color getRankColor(String rankName)
    {
        return RANK_COLORS.getOrDefault(rankName == null ? null : rankName.split(" ")[0], new Color(0x666666));
    }

    /** The slot of a rank label ("Rune 3", "3rd Age"); -1 for any other text. */
    public static int indexOfTier(String label)
    {
        return Arrays.asList(SLOTS).indexOf(label);
    }

    /**
     * "Top X%" for {@code aboveCount} players at or above a rank out of {@code total}: a whole number from 10%, one
     * decimal from 1%, two below ("Top <0.01%" under that). "No one yet" for an empty rank or an unknown total; the
     * ranks tab sizes every row to its widest, so the label stays short.
     */
    public static String formatTopPercent(long aboveCount, long total)
    {
        if (total <= 0L || aboveCount <= 0L)
        {
            return "No one yet";
        }
        double pct = (double) aboveCount / (double) total * 100.0;
        if (pct >= 10.0)
        {
            return "Top " + String.format(Locale.US, "%.0f%%", pct);
        }
        if (pct >= 1.0)
        {
            return "Top " + String.format(Locale.US, "%.1f%%", pct);
        }
        String twoDp = String.format(Locale.US, "%.2f", pct);
        return "0.00".equals(twoDp) ? "Top <0.01%" : "Top " + twoDp + "%";
    }
}
