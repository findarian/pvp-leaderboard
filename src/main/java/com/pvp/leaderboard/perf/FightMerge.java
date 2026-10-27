package com.pvp.leaderboard.perf;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/** The views' list: the local fights and the grabbed rows as one, newest first. A row is the closest local fight
 *  against the same opponent, played as the same name, that ended within 10 s of it; that fight keeps its values and
 *  takes the row's ranks, change, style and name, its synced figures and the opponent's joined ones. */
final class FightMerge
{
    private FightMerge()
    {
    }

    static List<FightLog.Fight> merge(List<FightLog.Fight> local, List<FightLog.Fight> grabbed)
    {
        Map<String, List<FightLog.Fight>> byOpponent = new HashMap<>();
        for (FightLog.Fight l : local) byOpponent.computeIfAbsent(NameUtils.canonicalKey(l.opponent), k -> new ArrayList<>()).add(l);
        Map<FightLog.Fight, FightLog.Fight> joined = new IdentityHashMap<>();
        List<FightLog.Fight> out = new ArrayList<>();
        for (FightLog.Fight g : grabbed)
        {
            FightLog.Fight best = null;
            for (FightLog.Fight l : byOpponent.getOrDefault(NameUtils.canonicalKey(g.opponent), Collections.emptyList()))
            {
                if (!joined.containsKey(l) && same(l, g)
                    && (best == null || Math.abs(l.endSec - g.endSec) < Math.abs(best.endSec - g.endSec))) best = l;
            }
            if (best == null) out.add(g);
            else joined.put(best, join(best, g));
        }
        for (FightLog.Fight l : local) out.add(joined.getOrDefault(l, l));
        out.sort((a, b) -> Long.compare(b.endSec, a.endSec));
        return out;
    }

    /** Whether the grabbed row {@code g} is the local fight {@code l}. */
    static boolean same(FightLog.Fight l, FightLog.Fight g)
    {
        return Math.abs(l.endSec - g.endSec) <= 10 && NameUtils.canonicalKey(l.opponent).equals(NameUtils.canonicalKey(g.opponent))
            && (l.self.isEmpty() || NameUtils.canonicalKey(l.self).equals(NameUtils.canonicalKey(g.self)));
    }

    private static FightLog.Fight join(FightLog.Fight l, FightLog.Fight g)
    {
        FightLog.Fight m = new FightLog.Fight(l.endSec, l.opponent, l.result, l.world, l.mine.clone(), l.theirs.clone());
        boolean synced = g.marks.contains("s");
        m.self = or(l.self, g.self);
        m.style = or(g.style, l.style);
        m.rank = or(g.rank, l.rank);
        m.oppRank = or(g.oppRank, l.oppRank);
        m.change = or(g.change, l.change);
        m.marks = synced ? g.marks : l.marks;
        if (synced)
        {
            sync(m.mine, g.mine);
            sync(m.theirs, g.theirs);
        }
        for (int i = 4; i < 6; i++) if (number(g.theirs[i])) m.theirs[i] = g.theirs[i];
        return m;
    }

    /** The server's values on a fight's sides: the opponent's Offensive prayer and HP healed from {@code opp}
     *  (perf_opp), and both sides' synced figures from {@code sync} when all of them are numbers; true when synced. */
    static boolean join(String[] mine, String[] theirs, JsonObject opp, JsonObject sync)
    {
        for (int i = 4; i < 6; i++)
        {
            String v = optString(opp, i == 4 ? "pr" : "hp", null);
            if (number(v)) theirs[i] = v;
        }
        String[] you = side(optObject(sync, "you")), them = side(optObject(sync, "them"));
        if (you == null || them == null) return false;
        sync(mine, you);
        sync(theirs, them);
        return true;
    }

    /** Synced figures as a side's nine places (expected damage, magic luck's expected part, KO chances, the ghost
     *  barrages' expected part); null unless all four are numbers. */
    private static String[] side(JsonObject o)
    {
        String[] v = new String[9];
        String[] keys = {"ed", "mh", "ko", "gb"};
        int[] at = {1, 3, 7, 8};
        for (int i = 0; i < 4; i++)
        {
            v[at[i]] = optString(o, keys[i], null);
            if (!number(v[at[i]])) return null;
        }
        return v;
    }

    /** A side's synced figures from {@code v}: expected damage, magic luck's expected part, KO chances and the ghost
     *  barrages' expected part. */
    private static void sync(String[] side, String[] v)
    {
        side[1] = v[1];
        side[3] = part(side[3], v[3]);
        side[7] = v[7];
        side[8] = part(side[8], v[8]);
    }

    /** {@code raw} with its last part replaced by {@code v}'s last part; that part alone when {@code raw} has one. */
    private static String part(String raw, String v)
    {
        String last = v.substring(v.lastIndexOf('/') + 1);
        int i = raw.lastIndexOf('/');
        return i < 0 ? last : raw.substring(0, i + 1) + last;
    }

    static boolean number(String v)
    {
        return v != null && v.matches("[0-9./]{1,16}");
    }

    private static String or(String a, String b)
    {
        return a.isEmpty() ? b : a;
    }
}
