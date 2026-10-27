package com.pvp.leaderboard.perf;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import javax.inject.*;
import lombok.*;
import net.runelite.client.config.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/** The fights this computer tracks: each kept in the logged-in character's series of the store, with the ranks read
 *  right after it and what the post-fight lookup's row brings. */
@Singleton
public class FightLog
{
    /** How far apart, in seconds, a fight and what is read after it may end. */
    private static final long WINDOW = 10;

    private final ConfigManager configManager;
    private final FightStore store;
    private String profile;

    @Inject
    public FightLog(ConfigManager configManager, FightStore store)
    {
        this.configManager = configManager;
        this.store = store;
    }

    /** One fight: its end, opponent, result (win, loss, tie or partial), world (0 when not known) and each side's nine
     *  values; the name played as, the style (a bucket key), both sides' division in the new rating system after it,
     *  its rating change there and the server's marks (the stats' version, "s" when synced); "" for none. */
    @RequiredArgsConstructor
    public static final class Fight
    {
        final long endSec;
        final String opponent;
        final String result;
        final int world;
        final String[] mine;
        final String[] theirs;
        volatile String self = "", style = "", rank = "", oppRank = "", change = "", marks = "";
    }

    /** A fight tracked here: kept in the logged-in character's series; after logout, the last one's. */
    public void add(Fight f)
    {
        String p;
        try
        {
            p = configManager.getRSProfileKey();
        }
        catch (RuntimeException e)
        {
            p = null;
        }
        synchronized (this)
        {
            if (p != null) profile = p;
            p = profile;
        }
        try
        {
            if (p != null) store.add(p, f);
        }
        catch (RuntimeException e)
        {
        }
    }

    /** Keeps {@code rank}, read right after the fight against {@code opponent} that ended at {@code endSec} (epoch
     *  seconds), on that fight: as yours when {@code mine}, else as theirs. */
    public void rank(String opponent, long endSec, boolean mine, String rank)
    {
        Fight f = rank == null ? null : find(opponent, endSec, "");
        if (f == null) return;
        if (mine) f.rank = rank;
        else f.oppRank = rank;
        store.changed(f);
    }

    /** The post-fight lookup's row for a fight: its rating change, and the opponent's joined values and both sides'
     *  synced figures (never on a Partial), kept with the local fight it is. */
    public void row(JsonObject row)
    {
        Fight f = find(optString(row, "opponent_id", ""), optLong(row, "when", 0), optString(row, "participant_id", ""));
        if (f == null) return;
        String before = FightStore.line(f), change = optString(row, "v3_change", "");
        if (!change.isEmpty()) f.change = change;
        if (!"partial".equals(f.result) && FightMerge.join(f.mine, f.theirs, optObject(row, "perf_opp"),
            f.marks.contains("s") ? null : optObject(row, "sync"))) f.marks += "s";
        if (!before.equals(FightStore.line(f))) store.changed(f);
    }

    /** The local fight against {@code opponent}, played as {@code as} when given, that ended closest to {@code end}
     *  and within 10 s of it; null for none. */
    private Fight find(String opponent, long end, String as)
    {
        String key = NameUtils.canonicalKey(opponent), who = NameUtils.canonicalKey(as);
        Fight best = null;
        for (Fight f : store.local())
        {
            long apart = Math.abs(f.endSec - end);
            if (apart <= WINDOW && key.equals(NameUtils.canonicalKey(f.opponent))
                && (who.isEmpty() || f.self.isEmpty() || who.equals(NameUtils.canonicalKey(f.self)))
                && (best == null || apart < Math.abs(best.endSec - end))) best = f;
        }
        return best;
    }
}
