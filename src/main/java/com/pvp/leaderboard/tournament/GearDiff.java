package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;

public final class GearDiff
{
    public static final int DIFF_MAX = 12;

    public static final class Row
    {
        public final int itemId;
        public final String name;
        public final int need;
        public final int have;
        public final boolean stackable;
        public final boolean noted;
        public final List<Integer> altIds;

        public Row(int itemId, String name, int need, int have, boolean stackable, boolean noted, List<Integer> altIds)
        {
            this.itemId = itemId;
            this.name = name;
            this.need = need;
            this.have = have;
            this.stackable = stackable;
            this.noted = noted;
            this.altIds = altIds == null ? Collections.<Integer>emptyList() : altIds;
        }

        public boolean isMissing()
        {
            return have < need;
        }

        public boolean isExtra()
        {
            return have > need;
        }

        public int shortBy()
        {
            return Math.max(0, need - have);
        }

        public int overBy()
        {
            return Math.max(0, have - need);
        }

        @Override
        public String toString()
        {
            return itemId + " " + have + "/" + need;
        }
    }

    public final String setBuild;
    public final String kitBuild;
    public final boolean buildOk;
    public final String setSpellbook;
    public final String kitSpellbook;
    public final boolean spellbookOk;
    public final List<Row> missing;
    public final List<Row> extra;
    public final boolean pouchUnknown;
    public final boolean ok;

    public GearDiff(String setBuild, String kitBuild, boolean buildOk, String setSpellbook, String kitSpellbook, boolean spellbookOk,
                    List<Row> missing, List<Row> extra, boolean pouchUnknown)
    {
        this.setBuild = setBuild;
        this.kitBuild = kitBuild;
        this.buildOk = buildOk;
        this.setSpellbook = setSpellbook;
        this.kitSpellbook = kitSpellbook;
        this.spellbookOk = spellbookOk;
        this.missing = missing;
        this.extra = extra;
        this.pouchUnknown = pouchUnknown;
        ok = buildOk && spellbookOk && missing.isEmpty() && extra.isEmpty();
    }

    public JsonArray wireDiff()
    {
        var out = new JsonArray();
        for (List<Row> rows : Arrays.asList(missing, extra))
        {
            for (Row r : rows)
            {
                if (out.size() >= DIFF_MAX) return out;
                var t = new JsonArray();
                t.add(r.itemId);
                t.add(r.need);
                t.add(r.have);
                out.add(t);
            }
        }
        return out;
    }

    public String summary()
    {
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) parts.add(missing.size() + " missing");
        if (!extra.isEmpty()) parts.add(extra.size() + " extra");
        if (!buildOk) parts.add("wrong build");
        if (!spellbookOk) parts.add("wrong spellbook");
        return String.join(", ", parts);
    }
}
