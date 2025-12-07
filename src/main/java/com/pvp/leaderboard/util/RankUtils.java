package com.pvp.leaderboard.util;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.service.RankInfo;
import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

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
        RANK_COLORS.put("Bronze", new Color(184, 115, 51));
        RANK_COLORS.put("Iron", new Color(192, 192, 192));
        RANK_COLORS.put("Steel", new Color(154, 162, 166));
        RANK_COLORS.put("Black", Color.GRAY);
        RANK_COLORS.put("Mithril", new Color(59, 167, 214));
        RANK_COLORS.put("Adamant", new Color(26, 139, 111));
        RANK_COLORS.put("Rune", new Color(78, 159, 227));
        RANK_COLORS.put("Dragon", new Color(229, 57, 53));
        RANK_COLORS.put("3rd", new Color(229, 193, 0));
    }

    public static Color getRankColor(String rankName)
    {
        if (rankName == null) return new Color(102, 102, 102);
        String baseName = rankName.split(" ")[0];
        return RANK_COLORS.getOrDefault(baseName, new Color(102, 102, 102));
    }

    public static Color getRankTextColor(String rankName, boolean colorBlindMode)
    {
        if (colorBlindMode) return Color.WHITE;
        return getRankColor(rankName);
    }

    public static String formatTierLabel(String raw)
    {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.equalsIgnoreCase("3rdAge")) return "3rd Age";
        return s.replaceAll("([A-Za-z]+)(\\d+)$", "$1 $2");
    }

    public static boolean isUnrankedOrDefault(JsonObject obj)
    {
        if (obj == null) return true;
        // Check for 0 MMR
        double mmr = 0.0;
        if (obj.has("mmr") && !obj.get("mmr").isJsonNull())
        {
            mmr = obj.get("mmr").getAsDouble();
        }

        // Check for Bronze 3 rank
        String rank = "";
        int div = 0;
        if (obj.has("rank") && !obj.get("rank").isJsonNull())
        {
            rank = obj.get("rank").getAsString();
        }
        if (obj.has("division") && !obj.get("division").isJsonNull())
        {
            div = obj.get("division").getAsInt();
        }

        // If it looks like default initialization (Bronze 3, 0 MMR), treat as unranked
        if (Math.abs(mmr) < 0.001 && "Bronze".equalsIgnoreCase(rank) && div == 3)
        {
            return true;
        }

        return false;
    }

    public static RankInfo rankLabelAndProgressFromMMR(double mmrVal)
    {
        // Do not arbitrarily treat 0 as null; 0 is a valid MMR (Bronze 3).
        // The caller is responsible for determining if data is missing (e.g. JSON missing "mmr" field).

        String[][] thresholds = THRESHOLDS;

        double v = mmrVal;
        String[] curr = thresholds[0];
        for (String[] t : thresholds)
        {
            if (v >= Double.parseDouble(t[2])) curr = t;
            else break;
        }

        int idx = -1;
        for (int i = 0; i < thresholds.length; i++)
        {
            if (thresholds[i][0].equals(curr[0]) && thresholds[i][1].equals(curr[1]) && thresholds[i][2].equals(curr[2]))
            {
                idx = i;
                break;
            }
        }

        String[] next = idx >= 0 && idx < thresholds.length - 1 ? thresholds[idx + 1] : curr;
        double pct = curr[0].equals("3rd Age") ? 100 :
            Math.max(0, Math.min(100, ((v - Double.parseDouble(curr[2])) / Math.max(1, Double.parseDouble(next[2]) - Double.parseDouble(curr[2]))) * 100));

        return new RankInfo(curr[0], Integer.parseInt(curr[1]), pct);
    }

    public static double calculateTierValue(double mmr)
    {
        // 0 MMR is valid (bottom of Bronze 3). Do not short-circuit.
        String[][] thresholds = THRESHOLDS;

        String[] current = thresholds[0];
        for (String[] threshold : thresholds)
        {
            if (mmr >= Double.parseDouble(threshold[2]))
            {
                current = threshold;
            }
            else
            {
                break;
            }
        }

        // Convert to percentage for graph display
        String[] tiers = {"Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune", "Dragon", "3rd Age"};
        for (int i = 0; i < tiers.length; i++)
        {
            if (tiers[i].equals(current[0]))
            {
                return (i * 100.0 / tiers.length) + (Integer.parseInt(current[1]) * 10.0 / tiers.length);
            }
        }
        return 0;
    }

    // Encode MMR to rank-scale value (baseIndex*3 + divisionOffset + progress)
    // Returns 0.0 to 24.0+
    public static double calculateContinuousTierValue(double mmr) {
        final String[] ORDER = {"Bronze","Iron","Steel","Black","Mithril","Adamant","Rune","Dragon","3rd Age"};
        double v = mmr;
        String[] curr = THRESHOLDS[0];
        for (String[] t : THRESHOLDS) { if (v >= Double.parseDouble(t[2])) curr = t; else break; }
        
        int idxCurr = -1;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (Arrays.equals(THRESHOLDS[i], curr)) { idxCurr = i; break; }
        }
        
        String[] next = THRESHOLDS[Math.min(idxCurr + 1, THRESHOLDS.length - 1)];
        int baseIdx = 0;
        for (int i = 0; i < ORDER.length; i++) { if (ORDER[i].equals(curr[0])) { baseIdx = i; break; } }
        int div = "3rd Age".equals(curr[0]) ? 0 : Integer.parseInt(curr[1]);
        int divOffset = "3rd Age".equals(curr[0]) ? 0 : (3 - div);
        double tierBase = baseIdx * 3 + divOffset;
        if ("3rd Age".equals(curr[0])) return tierBase;
        double span = Math.max(1.0, Double.parseDouble(next[2]) - Double.parseDouble(curr[2]));
        double prog = Math.max(0.0, Math.min(1.0, (v - Double.parseDouble(curr[2])) / span));
        return tierBase + prog;
    }

    public static double calculateProgressFromMMR(double mmr)
    {
        String[][] thresholds = THRESHOLDS;

        String[] current = thresholds[0];
        for (String[] threshold : thresholds)
        {
            if (mmr >= Double.parseDouble(threshold[2]))
            {
                current = threshold;
            }
            else
            {
                break;
            }
        }

        if ("3rd Age".equals(current[0]))
        {
            return 100.0;
        }

        int currentIndex = -1;
        for (int i = 0; i < thresholds.length; i++)
        {
            if (thresholds[i][0].equals(current[0]) && thresholds[i][1].equals(current[1]))
            {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex >= 0 && currentIndex < thresholds.length - 1)
        {
            double currentThreshold = Double.parseDouble(current[2]);
            double nextThreshold = Double.parseDouble(thresholds[currentIndex + 1][2]);
            double span = nextThreshold - currentThreshold;
            return Math.max(0, Math.min(100, ((mmr - currentThreshold) / span) * 100));
        }

        return 0.0;
    }

    public static int getRankIndex(String rank, int division)
    {
        String[][] thresholds = THRESHOLDS;

        for (int i = 0; i < thresholds.length; i++)
        {
            if (thresholds[i][0].equals(rank) && Integer.parseInt(thresholds[i][1]) == division)
            {
                return i;
            }
        }
        return 0;
    }

    public static int getRankOrder(String rank)
    {
        String[] parts = rank.split(" ");
        String baseName = parts[0];
        int division = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

        int baseOrder;
        switch (baseName) {
            case "Bronze":
                baseOrder = 0;
                break;
            case "Iron":
                baseOrder = 1;
                break;
            case "Steel":
                baseOrder = 2;
                break;
            case "Black":
                baseOrder = 3;
                break;
            case "Mithril":
                baseOrder = 4;
                break;
            case "Adamant":
                baseOrder = 5;
                break;
            case "Rune":
                baseOrder = 6;
                break;
            case "Dragon":
                baseOrder = 7;
                break;
            case "3rd Age":
                baseOrder = 8;
                break;
            default:
                baseOrder = -1;
                break;
        }

        return baseOrder * 10 + (4 - division);
    }
}
