package com.pvp.leaderboard.util;

import com.google.gson.*;
import com.pvp.leaderboard.service.*;
import java.awt.*;
import java.util.*;
import static java.lang.Math.*;

public class RankUtils
{
    private static final Map<String, Color> RANK_COLORS = new HashMap<>();

    public static final String[][] THRESHOLDS = {
        {"Bronze", "3", "0"}, {"Bronze", "2", "170"}, {"Bronze", "1", "240"},
        {"Iron", "3", "310"}, {"Iron", "2", "380"}, {"Iron", "1", "450"},
        {"Steel", "3", "520"}, {"Steel", "2", "590"}, {"Steel", "1", "660"},
        {"Black", "3", "730"}, {"Black", "2", "800"}, {"Black", "1", "870"},
        {"Mithril", "3", "940"}, {"Mithril", "2", "1010"}, {"Mithril", "1", "1080"},
        {"Adamant", "3", "1150"}, {"Adamant", "2", "1250"}, {"Adamant", "1", "1350"},
        {"Rune", "3", "1450"}, {"Rune", "2", "1550"}, {"Rune", "1", "1650"},
        {"Dragon", "3", "1750"}, {"Dragon", "2", "1850"}, {"Dragon", "1", "1950"},
        {"3rd Age", "0", "2100"}
    };

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
    }

    /** The colour of a rank label's first word (the rank family); grey when unknown. */
    public static Color getRankColor(String rankName)
    {
        return RANK_COLORS.getOrDefault(rankName == null ? null : rankName.split(" ")[0], new Color(0x666666));
    }

    public static String formatTier(String raw)
    {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.equalsIgnoreCase("3rdAge")) return "3rd Age";
        return s.replaceAll("([A-Za-z]+)(\\d+)$", "$1 $2");
    }

    /** Maps a backend tier string (e.g. {@code "Adamant2"}, {@code "3rdAge"})
     *  to its index in {@link #THRESHOLDS}, or {@code -1} if the tier
     *  doesn't match any known threshold. Used by the lobby gate to
     *  surface the local player's rank in their own self-profile
     *  preview row.
     *
     *  <p>Tolerates both compact ({@code "Adamant2"}) and spaced
     *  ({@code "Adamant 2"}) forms by normalising via
     *  {@link #formatTier(String)} first. Comparison is
     *  case-insensitive on the rank-family name and exact on the
     *  division number — a malformed input returns {@code -1} rather
     *  than guessing. 3rd Age (division "0" in THRESHOLDS) also matches
     *  in its single-token form. */
    public static int indexOfTier(String raw)
    {
        String f = raw == null ? null : formatTier(raw);
        for (int i = 0; f != null && i < THRESHOLDS.length; i++)
        {
            String[] t = THRESHOLDS[i];
            if (f.equalsIgnoreCase(t[0] + " " + t[1]) || "0".equals(t[1]) && f.equalsIgnoreCase(t[0]))
            {
                return i;
            }
        }
        return -1;
    }

    /** True for no object, and for the default initialization (Bronze 3 at 0 MMR) - not a real rank. */
    public static boolean isUnranked(JsonObject obj)
    {
        if (obj == null) return true;
        double mmr = obj.has("mmr") && !obj.get("mmr").isJsonNull() ? obj.get("mmr").getAsDouble() : 0.0;
        String rank = obj.has("rank") && !obj.get("rank").isJsonNull() ? obj.get("rank").getAsString() : "";
        int div = obj.has("division") && !obj.get("division").isJsonNull() ? obj.get("division").getAsInt() : 0;
        return abs(mmr) < 0.001 && "Bronze".equalsIgnoreCase(rank) && div == 3;
    }

    /** The THRESHOLDS index of {@code mmr}: the highest threshold at or
     *  below it; Bronze 3 (0) below zero. */
    private static int tierIdx(double mmr)
    {
        int k = 0;
        while (k < 24 && mmr >= Double.parseDouble(THRESHOLDS[k + 1][2]))
        {
            k++;
        }
        return k;
    }

    /** How far {@code mmr} is from tier {@code k} (below 3rd Age, index 24) to
     *  the next, as a fraction of the span: 0 at the tier, 1 at the next. */
    private static double frac(double mmr, int k)
    {
        double c = Double.parseDouble(THRESHOLDS[k][2]);
        return (mmr - c) / (Double.parseDouble(THRESHOLDS[k + 1][2]) - c);
    }

    public static RankInfo toRankInfo(double mmrVal)
    {
        // Do not arbitrarily treat 0 as null; 0 is a valid MMR (Bronze 3).
        // The caller is responsible for determining if data is missing (e.g. JSON missing "mmr" field).
        int k = tierIdx(mmrVal);
        return new RankInfo(THRESHOLDS[k][0], Integer.parseInt(THRESHOLDS[k][1]),
            k == 24 ? 100 : max(0, min(100, frac(mmrVal, k) * 100)));
    }

    // Encode MMR to rank-scale value (the THRESHOLDS index + progress to the next)
    // Returns 0.0 to 24.0
    public static double tierValueOf(double mmr)
    {
        int k = tierIdx(mmr);
        return k == 24 ? k : k + max(0.0, min(1.0, frac(mmr, k)));
    }

    /** Sort key of a "Family Division" label: the family's order (Bronze 0 to
     *  3rd Age 8, -1 unknown) x 10, plus 4 - division. */
    public static int getRankOrder(String rank)
    {
        String[] parts = rank.split(" ");
        int division = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int order = -1;
        for (int i = 0; i < 9; i++)
        {
            if (THRESHOLDS[i * 3][0].equals(parts[0]))
            {
                order = i;
            }
        }
        return order * 10 + (4 - division);
    }

    /**
     * Total population recorded in a rank histogram artifact
     * ({@code rank_hist/<bucket>.json}, produced by the infra-side
     * {@code backend/core/rank_histogram.py}). Returns {@code 0} for a
     * {@code null}, empty, or malformed histogram (all of which throw
     * below) so callers can render "No one yet" without special-casing
     * failures.
     */
    public static long histTotal(JsonObject hist)
    {
        try
        {
            return max(0L, hist.get("total").getAsLong());
        }
        catch (RuntimeException e)
        {
            return 0L;
        }
    }

    /**
     * Number of players whose MMR is at or above {@code mmrThreshold},
     * read from the cumulative histogram's {@code bins} array. Each bin is
     * {@code [floor, count, count_above]} sorted ascending by floor, where
     * {@code count_above} is the population of all strictly-higher bins.
     *
     * <p>Because every rank cutoff in {@link #THRESHOLDS} is a multiple of
     * the 10-MMR bin width, a threshold always lands on a bin boundary —
     * so the first bin whose floor is {@code >= mmrThreshold} gives an
     * exact at-or-above count of {@code count + count_above}. Malformed
     * rows and missing/empty histograms degrade to {@code 0}.
     */
    public static long totalAbove(JsonObject hist, double mmrThreshold)
    {
        for (JsonElement el : JsonLenient.optArray(hist, "bins"))
        {
            try
            {
                JsonArray bin = el.getAsJsonArray();
                if (bin.get(0).getAsDouble() >= mmrThreshold)
                {
                    return max(0L, bin.get(1).getAsLong() + bin.get(2).getAsLong());
                }
            }
            catch (RuntimeException e)
            {
                // Skip a corrupt row (not an array, short, or a bad value)
                // rather than failing the whole lookup.
            }
        }
        return 0L;
    }

    /**
     * Human-readable "Top X%" label for a tier, given how many players are
     * at or above that tier and the total population. Returns
     * {@code "No one yet"} when the tier is empty (or the population is
     * unknown), matching the "What are the ranks" design.
     *
     * <p>The empty-tier label is kept short on purpose: it's the widest
     * string in that view, and the view sizes all 25 rows to fit its
     * widest row, so every extra character shrinks every rank name on
     * screen. The previous "No one currently here" cost ~5pt across the
     * whole list.
     *
     * <p>Precision scales with the magnitude so the exclusive top tiers stay
     * legible: whole numbers at/above 10%, one decimal in {@code [1%, 10%)},
     * two decimals below 1% (e.g. {@code "Top 0.01%"}), and {@code "Top
     * <0.01%"} for a share too small to show at two decimals.
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
        return formatTopPercentPrecise(aboveCount, total);
    }

    /**
     * Fixed two-decimal "Top X.XX%" used by the Player Lookup ratings, where
     * the user wants a consistent precision per rating (unlike
     * {@link #formatTopPercent}'s magnitude-scaled precision used by the
     * rank-tier explainer). Returns {@code null} when the share can't be
     * determined (no/zero total, or the player isn't counted) so the caller
     * can simply omit the suffix rather than print a misleading value. A
     * non-zero share that rounds below 0.01% renders {@code "Top <0.01%"}.
     *
     * @param aboveCount players at or above the player's MMR (inclusive)
     * @param total          bucket population from the histogram
     */
    public static String formatTopPercentPrecise(long aboveCount, long total)
    {
        if (total <= 0L || aboveCount <= 0L)
        {
            return null;
        }
        double pct = (double) aboveCount / (double) total * 100.0;
        if (pct > 100.0)
        {
            pct = 100.0;
        }
        String twoDp = String.format(Locale.US, "%.2f", pct);
        if ("0.00".equals(twoDp))
        {
            return "Top <0.01%";
        }
        return "Top " + twoDp + "%";
    }
}
