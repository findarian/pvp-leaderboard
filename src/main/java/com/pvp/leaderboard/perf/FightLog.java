package com.pvp.leaderboard.perf;

import com.pvp.leaderboard.game.*;
import java.util.*;
import javax.inject.*;
import lombok.*;
import net.runelite.client.config.*;

/** All fights: the last fights this computer tracked, kept per character in RuneLite's profile config (new ones under
 *  the logged-in character) and shown for every character together, newest first, logged in or not; the ranks read
 *  right after each fight are kept under a key of their own. */
@Singleton
public class FightLog
{
    static final String KEY = "perfFights", RANKS = "perfRanks";
    static final int KEEP = 50;
    /** How far, in seconds, a rank's fight end may be from the kept fight's. */
    private static final long RANK_WINDOW = 10;

    private final ConfigManager configManager;
    private final LinkedList<Fight> fights = new LinkedList<>();
    private String profile;
    /** The loaded character's name, as RuneLite lists it; null when not known. */
    private String who;
    /** The ranks as last read or saved; written again only when they change. */
    private String savedRanks = "";
    @Setter private volatile Runnable listener;

    @Inject
    public FightLog(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    /** One fight: its end, opponent, result (win, loss, tie or partial), world, each side's nine values, and each
     *  player's division in the new rating system right after it ("Adamant 2"; null when none was read). */
    @AllArgsConstructor
    public static final class Fight
    {
        final long endSec;
        final String opponent;
        final String result;
        final int world;
        final String[] mine;
        final String[] theirs;
        final String myRank, theirRank;
        /** The name the fight was played as (its character's name in RuneLite); null when not known. */
        final String who;

        public Fight(long endSec, String opponent, String result, int world, String[] mine, String[] theirs)
        {
            this(endSec, opponent, result, world, mine, theirs, null, null, null);
        }

        Fight(long endSec, String opponent, String result, int world, String[] mine, String[] theirs, String myRank,
            String theirRank)
        {
            this(endSec, opponent, result, world, mine, theirs, myRank, theirRank, null);
        }
    }

    public void add(Fight f)
    {
        synchronized (this)
        {
            if (!load()) return;
            fights.addFirst(new Fight(f.endSec, f.opponent, f.result, f.world, f.mine, f.theirs, f.myRank, f.theirRank,
                f.who == null ? who : f.who));
            while (fights.size() > KEEP) fights.removeLast();
            save();
        }
        tell();
    }

    /** Keeps {@code rank}, read right after the fight against {@code opponent} that ended at {@code endSec} (epoch
     *  seconds), on that fight: as yours when {@code mine}, else as theirs. */
    public void rank(String opponent, long endSec, boolean mine, String rank)
    {
        synchronized (this)
        {
            if (opponent == null || rank == null || !load()) return;
            ListIterator<Fight> it = fights.listIterator();
            Fight f = null;
            while (f == null && it.hasNext())
            {
                Fight x = it.next();
                if (x.opponent.equals(opponent) && Math.abs(x.endSec - endSec) <= RANK_WINDOW) f = x;
            }
            if (f == null) return;
            it.set(new Fight(f.endSec, f.opponent, f.result, f.world, f.mine, f.theirs, mine ? rank : f.myRank,
                mine ? f.theirRank : rank, f.who));
            save();
        }
        tell();
    }

    /** The character whose fights are loaded (RuneLite's profile key); null before the first login. */
    public synchronized String character()
    {
        load();
        return profile;
    }

    /** Every character's fights, newest first: the loaded character's as kept, the others as saved. */
    public synchronized List<Fight> all()
    {
        load();
        List<Fight> all = new ArrayList<>(fights);
        for (RuneScapeProfile p : characters())
        {
            if (!p.getKey().equals(profile)) all.addAll(read(p.getKey(), p.getDisplayName()));
        }
        all.sort(Comparator.comparingLong((Fight f) -> f.endSec).reversed());
        return all;
    }

    /** RuneLite's characters on this computer; none when they cannot be read. */
    private List<RuneScapeProfile> characters()
    {
        try
        {
            List<RuneScapeProfile> list = configManager.getRSProfiles();
            return list == null ? Collections.emptyList() : list;
        }
        catch (RuntimeException e)
        {
            return Collections.emptyList();
        }
    }

    private void tell()
    {
        Runnable l = listener;
        if (l != null) l.run();
    }

    private void save()
    {
        StringBuilder sb = new StringBuilder(), ranks = new StringBuilder();
        for (Fight x : fights)
        {
            String opponent = x.opponent.replace('|', ' ');
            sb.append(x.endSec).append('|').append(opponent).append('|').append(x.result).append('|')
                .append(x.world).append('|').append(String.join(",", x.mine)).append('|')
                .append(String.join(",", x.theirs)).append('\n');
            if (x.myRank != null || x.theirRank != null)
            {
                ranks.append(x.endSec).append('|').append(opponent).append('|').append(clean(x.myRank)).append('|')
                    .append(clean(x.theirRank)).append('\n');
            }
        }
        write(KEY, sb.toString());
        String r = ranks.toString();
        if (!r.equals(savedRanks) && write(RANKS, r)) savedRanks = r;
    }

    private boolean write(String key, String value)
    {
        try
        {
            configManager.setConfiguration(FightMonitor.CONFIG_GROUP, profile, key, value);
            return true;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static String clean(String rank)
    {
        return rank == null ? "" : rank.replace('|', ' ').replace('\n', ' ');
    }

    private static String orNull(String s)
    {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Reads the character's list when the character changed; after logout the last character stays loaded. */
    private boolean load()
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
        if (p == null || p.equals(profile)) return profile != null;
        profile = p;
        who = null;
        for (RuneScapeProfile c : characters())
        {
            if (p.equals(c.getKey())) who = c.getDisplayName();
        }
        String r = configManager.getConfiguration(FightMonitor.CONFIG_GROUP, p, RANKS);
        savedRanks = r == null ? "" : r;
        fights.clear();
        fights.addAll(read(p, who));
        return true;
    }

    /** One character's saved fights with their ranks, as played by {@code name}. */
    private List<Fight> read(String key, String name)
    {
        String r = configManager.getConfiguration(FightMonitor.CONFIG_GROUP, key, RANKS);
        Map<String, String[]> ranks = new HashMap<>();
        for (String line : (r == null ? "" : r).split("\n"))
        {
            String[] k = line.split("\\|", -1);
            if (k.length == 4) ranks.put(k[0] + "|" + k[1], k);
        }
        List<Fight> out = new ArrayList<>();
        String saved = configManager.getConfiguration(FightMonitor.CONFIG_GROUP, key, KEY);
        for (String line : saved == null ? new String[0] : saved.split("\n"))
        {
            String[] f = line.split("\\|", -1);
            try
            {
                String[] mine = f[4].split(","), theirs = f[5].split(",");
                if (f.length == 6 && mine.length == 9 && theirs.length == 9)
                {
                    String[] k = ranks.getOrDefault(f[0] + "|" + f[1], new String[4]);
                    out.add(new Fight(Long.parseLong(f[0]), f[1], f[2], Integer.parseInt(f[3]), mine, theirs,
                        orNull(k[2]), orNull(k[3]), name));
                }
            }
            catch (RuntimeException e)
            {
            }
        }
        return out;
    }
}
