package com.pvp.leaderboard.perf;

import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;
import lombok.*;
import net.runelite.client.config.*;

/** Every stored fight, in RuneLite's settings: one series per character (its RS profile's perfStore.n keys) and the
 *  grabbed series (the global perfGrab.n keys), in chunks of 200 lines, each series at most 10,000 fights. Read once and
 *  kept in memory; a change writes only its chunks, on the executor. A line that does not read is kept as it is. */
@Singleton
public class FightStore
{
    static final int CHUNK = 200, KEEP = 10_000;
    /** Set 45's list (newest first, 50 at most) and its ranks: read at each start, never written. */
    private static final String OLD = "perfFights", OLD_RANKS = "perfRanks";
    private static final int OLD_KEEP = 50;
    private static final String GROUP = FightMonitor.CONFIG_GROUP, STORE = "perfStore.", GRAB = "perfGrab.";

    private final ConfigManager config;
    private final ScheduledExecutorService executor;
    /** Each series by its RS profile key (the grabbed: null): its chunks by number, each a fight or an unread line. */
    private final Map<String, TreeMap<Integer, List<Object>>> series = new HashMap<>();
    private volatile boolean loaded;
    /** Raised by each reload: a write queued before it is dropped. */
    private int generation;
    @Setter private volatile Consumer<FightLog.Fight> listener;

    @Inject
    public FightStore(ConfigManager config, ScheduledExecutorService executor)
    {
        this.config = config;
        this.executor = executor;
    }

    /** Reads every series once: each RS profile's, with its old list merged in, and the grabbed. */
    public void load()
    {
        synchronized (this)
        {
            if (loaded) return;
            read();
        }
        tell(null);
    }

    /** RuneLite's settings profile changed: every series forgotten now and read again on the executor. */
    public void reload()
    {
        synchronized (this)
        {
            generation++;
            loaded = false;
            series.clear();
        }
        executor.execute(this::load);
    }

    private void read()
    {
        int bad = read(null);
        List<RuneScapeProfile> profiles = config.getRSProfiles();
        for (RuneScapeProfile p : profiles) bad += read(p.getKey());
        loaded = true;
        for (RuneScapeProfile p : profiles) imported(p.getKey(), p.getDisplayName());
    }

    private int read(String profile)
    {
        TreeMap<Integer, List<Object>> s = new TreeMap<>();
        int bad = 0;
        for (String k : profile == null ? config.getConfigurationKeys(GROUP + "." + GRAB)
            : config.getRSProfileConfigurationKeys(GROUP, profile, STORE))
        {
            String key = profile == null ? k.substring(GROUP.length() + 1) : k;
            int n;
            try
            {
                n = Integer.parseInt(key.substring(key.lastIndexOf('.') + 1));
            }
            catch (NumberFormatException e)
            {
                continue;
            }
            String v = profile == null ? config.getConfiguration(GROUP, key) : config.getConfiguration(GROUP, profile, key);
            List<Object> c = new ArrayList<>();
            for (String line : v == null ? new String[0] : v.split("\n"))
            {
                FightLog.Fight f = parse(line);
                if (f == null && !line.isEmpty()) bad++;
                if (!line.isEmpty()) c.add(f == null ? line : f);
            }
            if (!c.isEmpty()) s.put(n, c);
        }
        series.put(profile, s);
        return bad;
    }

    /** The character's set 45 list merged in, by fight, at each start: a fight not stored yet (the same end and the
     *  same opponent) is added as played by {@code name}, with its ranks; a stored one without ranks takes them. */
    private void imported(String profile, String name)
    {
        String old = config.getConfiguration(GROUP, profile, OLD), r = config.getConfiguration(GROUP, profile, OLD_RANKS);
        if (old == null) return;
        Map<String, String[]> ranks = new HashMap<>();
        for (String line : r == null ? new String[0] : r.split("\n"))
        {
            String[] k = line.split("\\|", -1);
            if (k.length == 4) ranks.put(k[0] + "|" + k[1], k);
        }
        Map<String, FightLog.Fight> have = new HashMap<>();
        for (List<Object> c : series.get(profile).values())
        {
            for (Object o : c) if (o instanceof FightLog.Fight) have.put(key((FightLog.Fight) o), (FightLog.Fight) o);
        }
        List<FightLog.Fight> added = new ArrayList<>();
        String[] lines = old.split("\n");
        for (int i = 0; i < Math.min(lines.length, OLD_KEEP); i++)
        {
            String[] p = lines[i].split("\\|", -1);
            FightLog.Fight f = p.length < 6 || p.length > 7 ? null : parse(String.join("|", "1", p[0], clean(name), p[1],
                p[2], p[3], "", "", "", "", p[4], p[5], p.length == 7 && "1".equals(p[6]) ? "s" : ""));
            if (f == null) continue;
            String[] k = ranks.getOrDefault(p[0] + "|" + p[1], new String[]{"", "", "", ""});
            FightLog.Fight stored = have.putIfAbsent(key(f), f);
            FightLog.Fight to = stored == null ? f : stored;
            boolean ranked = to.rank.isEmpty() && to.oppRank.isEmpty() && !(k[2] + k[3]).isEmpty();
            if (ranked)
            {
                to.rank = k[2];
                to.oppRank = k[3];
            }
            if (stored == null) added.add(f);
            else if (ranked) saved(stored);
        }
        added.sort(Comparator.comparingLong(f -> f.endSec));
        merge(profile, added);
    }

    private static String key(FightLog.Fight f)
    {
        return f.endSec + "|" + NameUtils.canonicalKey(f.opponent);
    }

    /** {@code oldestFirst} into {@code profile}'s series by end time: after the newest, as a new fight goes; among the
     *  stored ones, the series written again, unless the 10,000 would drop every one of them at once. */
    private void merge(String profile, List<FightLog.Fight> oldestFirst)
    {
        if (oldestFirst.isEmpty()) return;
        List<Object> all = new ArrayList<>();
        series.get(profile).values().forEach(all::addAll);
        long newest = Long.MIN_VALUE;
        for (Object o : all) if (o instanceof FightLog.Fight) newest = Math.max(newest, ((FightLog.Fight) o).endSec);
        if (oldestFirst.get(0).endSec >= newest)
        {
            Set<Integer> touched = new TreeSet<>();
            for (FightLog.Fight f : oldestFirst) append(profile, f, touched);
            for (int n : touched) save(profile, n);
            return;
        }
        for (FightLog.Fight f : oldestFirst)
        {
            int at = all.size();
            while (at > 0 && !(all.get(at - 1) instanceof FightLog.Fight && ((FightLog.Fight) all.get(at - 1)).endSec <= f.endSec)) at--;
            all.add(at, f);
        }
        List<Object> kept = all.subList(Math.max(0, all.size() - KEEP), all.size());
        if (oldestFirst.stream().anyMatch(kept::contains)) replace(profile, new ArrayList<>(kept));
    }

    /** A fight tracked here, at the end of {@code profile}'s series; past 10,000 the series' oldest goes. */
    public void add(String profile, FightLog.Fight f)
    {
        boolean fresh;
        synchronized (this)
        {
            fresh = !loaded;
            if (fresh) read();
            Set<Integer> touched = new TreeSet<>();
            append(profile, f, touched);
            for (int n : touched) save(profile, n);
        }
        tell(fresh ? null : f);
    }

    /** {@code f} at the end of {@code profile}'s series, its oldest leaving past 10,000; the chunks changed go into
     *  {@code touched}. */
    private void append(String profile, FightLog.Fight f, Set<Integer> touched)
    {
        TreeMap<Integer, List<Object>> s = series.computeIfAbsent(profile, k -> new TreeMap<>());
        Map.Entry<Integer, List<Object>> last = s.lastEntry();
        int n = last == null ? 0 : last.getValue().size() < CHUNK ? last.getKey() : last.getKey() + 1;
        s.computeIfAbsent(n, k -> new ArrayList<>()).add(f);
        touched.add(n);
        while (s.values().stream().mapToInt(List::size).sum() > KEEP)
        {
            Map.Entry<Integer, List<Object>> first = s.firstEntry();
            first.getValue().remove(0);
            if (first.getValue().isEmpty()) s.remove(first.getKey());
            touched.add(first.getKey());
        }
    }

    // SYNC: /** The grabbed series replaced by {@code oldestFirst} (its newest 10,000). */
    // SYNC: public void replaceGrab(List<FightLog.Fight> oldestFirst)
    // SYNC: {
        // SYNC: synchronized (this)
        // SYNC: {
            // SYNC: replace(null, oldestFirst);
        // SYNC: }
        // SYNC: tell(null);
    // SYNC: }

    /** {@code f} changed: its chunk written again. */
    public void changed(FightLog.Fight f)
    {
        if (saved(f)) tell(f);
    }

    private synchronized boolean saved(FightLog.Fight f)
    {
        for (Map.Entry<String, TreeMap<Integer, List<Object>>> s : series.entrySet())
        {
            for (Map.Entry<Integer, List<Object>> c : s.getValue().descendingMap().entrySet())
            {
                if (c.getValue().contains(f))
                {
                    save(s.getKey(), c.getKey());
                    return true;
                }
            }
        }
        return false;
    }

    /** Every character's fights; none before the start read. */
    public List<FightLog.Fight> local()
    {
        return fights(false);
    }

    public List<FightLog.Fight> grabbed()
    {
        return fights(true);
    }

    private List<FightLog.Fight> fights(boolean grab)
    {
        List<FightLog.Fight> out = new ArrayList<>();
        if (!loaded) return out;
        synchronized (this)
        {
            for (Map.Entry<String, TreeMap<Integer, List<Object>>> s : series.entrySet())
            {
                if ((s.getKey() == null) != grab) continue;
                for (List<Object> c : s.getValue().values())
                {
                    for (Object o : c) if (o instanceof FightLog.Fight) out.add((FightLog.Fight) o);
                }
            }
        }
        return out;
    }

    private void replace(String profile, List<?> entries)
    {
        TreeMap<Integer, List<Object>> s = series.computeIfAbsent(profile, k -> new TreeMap<>());
        Set<Integer> touched = new TreeSet<>(s.keySet());
        s.clear();
        int from = Math.max(0, entries.size() - KEEP);
        for (int i = from; i < entries.size(); i++) s.computeIfAbsent((i - from) / CHUNK, k -> new ArrayList<>()).add(entries.get(i));
        touched.addAll(s.keySet());
        for (int n : touched) save(profile, n);
    }

    private void save(String profile, int n)
    {
        int g = generation;
        executor.execute(() -> write(profile, n, g));
    }

    /** Chunk {@code n} of {@code profile}'s series as it is now: written, or removed when it is gone. */
    private synchronized void write(String profile, int n, int g)
    {
        if (!loaded || g != generation) return;
        List<Object> c = series.getOrDefault(profile, new TreeMap<>()).get(n);
        String key = (profile == null ? GRAB : STORE) + n;
        StringBuilder b = new StringBuilder();
        for (Object o : c == null ? List.of() : c) b.append(o instanceof FightLog.Fight ? line((FightLog.Fight) o) : o).append('\n');
        try
        {
            if (c == null && profile == null) config.unsetConfiguration(GROUP, key);
            else if (c == null) config.unsetConfiguration(GROUP, profile, key);
            else if (profile == null) config.setConfiguration(GROUP, key, b.toString());
            else config.setConfiguration(GROUP, profile, key, b.toString());
        }
        catch (RuntimeException e)
        {
        }
    }

    private void tell(FightLog.Fight f)
    {
        Consumer<FightLog.Fight> l = listener;
        if (l != null) l.accept(f);
    }

    /** A fight as one line, version 1: end|name played as|opponent|result|world|style|rank|opponent's rank|change|nine
     *  values|the opponent's nine|marks. */
    static String line(FightLog.Fight f)
    {
        return String.join("|", "1", String.valueOf(f.endSec), clean(f.self), clean(f.opponent), clean(f.result),
            String.valueOf(f.world), clean(f.style), clean(f.rank), clean(f.oppRank), clean(f.change),
            clean(String.join(",", f.mine)), clean(String.join(",", f.theirs)), clean(f.marks));
    }

    /** A version 1 line as a fight; null when it does not read. */
    static FightLog.Fight parse(String line)
    {
        String[] p = line.split("\\|", -1);
        String[] mine = p.length == 13 ? p[10].split(",", -1) : null, theirs = mine == null ? null : p[11].split(",", -1);
        if (mine == null || !"1".equals(p[0]) || mine.length != 9 || theirs.length != 9) return null;
        try
        {
            FightLog.Fight f = new FightLog.Fight(Long.parseLong(p[1]), p[3], p[4], Integer.parseInt(p[5]), mine, theirs);
            f.self = p[2];
            f.style = p[6];
            f.rank = p[7];
            f.oppRank = p[8];
            f.change = p[9];
            f.marks = p[12];
            return f;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String clean(String s)
    {
        return s == null ? "" : s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
