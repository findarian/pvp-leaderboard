package com.pvp.leaderboard.perf;

import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.function.*;

/** The filters both views share, in this order: Played as, Style, Minimum opponent rank, Show, Include partial fights,
 *  then Most recent fights. */
final class FightFilter
{
    /** The v3 tiers, lowest first; the dropdown offers all but 3rd Age. */
    static final String[] TIERS = {"Bronze", "Iron", "Steel", "Mithril", "Adamant", "Rune", "Dragon", "3rd Age"};
    /** Most recent fights' choices: All, the time windows, then the counts. */
    static final String[] RECENT = {"All", "Last day", "Last week", "Last month", "Last year", "Last 10 fights",
        "Last 100 fights", "Last 1,000 fights", "Last 10,000 fights"};
    private static final int[] DAYS = {0, 1, 7, 30, 365};
    /** Show's choices, and the result each keeps (null: every fight). */
    static final String[] SHOW = {"All fights", "Kills", "Deaths", "Double deaths"};
    static final String[] RESULTS = {null, "win", "loss", "tie"};

    /** The lowest tier kept (-1: any) and the newest fights kept (0: all); the time window, in seconds (0: all time);
     *  the name played as, the style (a bucket key) and the result shown, null for all. */
    int min = -1, recent;
    long window;
    String self, style, result;
    boolean partials = true;
    LongSupplier now = () -> System.currentTimeMillis() / 1000;

    /** The fights kept from {@code newestFirst}, newest first. */
    List<FightLog.Fight> apply(List<FightLog.Fight> newestFirst)
    {
        List<FightLog.Fight> out = new ArrayList<>();
        for (FightLog.Fight f : newestFirst)
        {
            if (recent > 0 && out.size() >= recent) break;
            if (keeps(f)) out.add(f);
        }
        return out;
    }

    /** Every filter but the count. */
    boolean keeps(FightLog.Fight f)
    {
        return (self == null || NameUtils.canonicalKey(self).equals(NameUtils.canonicalKey(f.self)))
            && (style == null || style.equalsIgnoreCase(f.style))
            && (min < 0 || tier(f.oppRank) >= min)
            && (result == null || result.equals(f.result))
            && (partials || List.of("win", "loss", "tie").contains(f.result))
            && (window <= 0 || f.endSec >= now.getAsLong() - window);
    }

    /** The tier of a v3 division label, by {@link #TIERS}; -1 for none. */
    static int tier(String rank)
    {
        for (int i = 0; rank != null && i < TIERS.length; i++)
        {
            if (rank.equals(TIERS[i]) || rank.startsWith(TIERS[i] + " ")) return i;
        }
        return -1;
    }

    /** Most recent fights' choice, or a typed whole number from 1 to 10,000 ("Last N fights" too): true when taken;
     *  anything else changes nothing. */
    boolean recent(String text)
    {
        String t = text == null ? "" : text.trim();
        for (int i = 0; i < RECENT.length; i++)
        {
            if (!RECENT[i].equalsIgnoreCase(t)) continue;
            window = i < DAYS.length ? DAYS[i] * 86_400L : 0;
            recent = i < DAYS.length ? 0 : (int) Math.pow(10, i - DAYS.length + 1);
            return true;
        }
        String n = t.replaceFirst("(?i)^last\\s+", "").replaceFirst("(?i)\\s+fights?$", "");
        int v = n.matches("[0-9]{1,5}|[0-9]{1,2},[0-9]{3}") ? Integer.parseInt(n.replace(",", "")) : 0;
        if (v < 1 || v > 10_000) return false;
        recent = v;
        window = 0;
        return true;
    }

    /** Most recent fights as shown: the choice, or "Last N fights". */
    String recentText()
    {
        for (int i = 1; i < DAYS.length; i++) if (window == DAYS[i] * 86_400L) return RECENT[i];
        return recent == 0 ? RECENT[0] : String.format(Locale.US, "Last %,d fight%s", recent, recent == 1 ? "" : "s");
    }
}
