package com.pvp.leaderboard.perf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.zip.Deflater;
import net.runelite.api.kit.KitType;

/** One fight's log for the match body: the local player's own items, worn sets, levels, prayers and attacks, one
 *  record each, and each stretch of quiet client ticks as one N record; no text once a bound is broken. */
final class PerfLog
{
    static final int LIMIT = 65_536;
    private static final String TYPES = "IGSABH";
    private static final int[] CAPS = {256, 64, 4_000, 2_000};
    private static final KitType[] SLOTS = {KitType.HEAD, KitType.CAPE, KitType.AMULET, KitType.WEAPON, KitType.TORSO,
        KitType.SHIELD, KitType.LEGS, KitType.HANDS, KitType.BOOTS};

    private final List<Object> records = new ArrayList<>();
    private final Map<String, Integer> sets = new HashMap<>();
    private final Set<Integer> items = new HashSet<>();
    private final int[] counts = new int[CAPS.length], last = new int[TYPES.length()];
    private final IntFunction<int[]> stats;
    private int base = -1;
    private String state;
    private boolean broken, wrote;
    /** Client ticks since the last record in which no record came. */
    private int quiet;

    PerfLog(boolean lms, IntFunction<int[]> stats)
    {
        this.stats = stats;
        records.add("L1," + (lms ? 1 : 0));
    }

    /** {@code tick} counted from the first state's. */
    int tick(int tick)
    {
        return tick - base;
    }

    /** The local player at {@code tick}: its new items and worn set first, then a state record when any changed. */
    void state(int tick, PerfFight.Self s)
    {
        if (base < 0) base = tick;
        StringBuilder set = new StringBuilder();
        for (KitType k : SLOTS) set.append(s.gear[k.getIndex()]).append(',');
        String key = set.append(s.ring).append(',').append(s.ammo).toString();
        Integer n = sets.get(key);
        if (n == null)
        {
            for (KitType k : SLOTS) item(s.gear[k.getIndex()]);
            item(s.ring);
            item(s.ammo);
            if (PerfHit.longsword(s.gear[KitType.WEAPON.getIndex()])) item(Gear.VESTAS_LONGSWORD.id);
            n = sets.size();
            sets.put(key, n);
            put('G', 0, "G," + n + "," + key);
        }
        String now = s.lv[0] + "," + s.lv[1] + "," + s.lv[2] + "," + s.lv[3] + "," + s.lv[4] + "," + s.prayers + "," + n;
        if (now.equals(state)) return;
        state = now;
        put('S', tick(tick), "S," + tick(tick) + "," + now);
    }

    private void item(int id)
    {
        int[] b = id > 0 && !items.contains(id) ? PerfCalc.stats(stats, id) : null;
        if (b == null) return;
        items.add(id);
        StringBuilder r = new StringBuilder("I,").append(id);
        for (int x : b) r.append(',').append(x);
        put('I', 0, r);
    }

    /** A client tick ended (after its records): counted as quiet when no record came in it, from the first state on;
     *  a stretch past 60,000 ticks drops the log. */
    void endTick()
    {
        if (base < 0 || broken) return;
        if (wrote) wrote = false;
        else if (++quiet > 60_000)
        {
            broken = true;
            records.clear();
        }
    }

    /** One record of {@code type} at {@code tick} (0 for a record without one), after an N record for the quiet ticks
     *  before it; a broken bound drops the log. */
    void put(char type, int tick, Object record)
    {
        int t = TYPES.indexOf(type), c = Math.min(t, CAPS.length - 1);
        broken |= tick < 0 || tick > 60_000 || tick < last[t] || ++counts[c] > CAPS[c];
        last[t] = tick;
        if (broken) records.clear();
        else
        {
            if (quiet > 0) records.add("N," + quiet);
            records.add(record);
        }
        quiet = 0;
        wrote = true;
    }

    /** The records joined by ';', with an N record for the quiet ticks at the end; null once a bound was broken or
     *  past {@link #LIMIT} characters. */
    String text()
    {
        if (broken) return null;
        StringBuilder sb = new StringBuilder();
        for (Object r : records) sb.append(sb.length() == 0 ? "" : ";").append(r);
        if (quiet > 0) sb.append(";N,").append(quiet);
        return sb.length() > LIMIT ? null : sb.toString();
    }

    /** {@code text} as the match body carries it: standard Base64 (padded, one line) of the zlib stream (best
     *  compression) of its ASCII bytes. */
    static String pack(String text)
    {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try
        {
            d.setInput(text.getBytes(StandardCharsets.US_ASCII));
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, text.length() / 4));
            byte[] buf = new byte[4096];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
        finally
        {
            d.end();
        }
    }
}
