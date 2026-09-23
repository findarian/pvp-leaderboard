package com.pvp.leaderboard.tournament;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GearDiff
{
    public static final int WIRE_DIFF_MAX = 12;

    public static final class Row
    {
        public final int itemId;
        public final String name;
        public final int need;
        public final int have;
        public final boolean stackable;
        public final boolean noted;
        public final List<Integer> altIds;

        public Row(int itemId, String name, int need, int have, boolean stackable, boolean noted)
        {
            this(itemId, name, need, have, stackable, noted, Collections.<Integer>emptyList());
        }

        public Row(int itemId, String name, int need, int have, boolean stackable, boolean noted, List<Integer> altIds)
        {
            this.itemId = itemId;
            this.name = name;
            this.need = need;
            this.have = have;
            this.stackable = stackable;
            this.noted = noted;
            this.altIds = altIds == null || altIds.isEmpty() ? Collections.<Integer>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(altIds));
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
        this.missing = missing == null ? Collections.<Row>emptyList() : Collections.unmodifiableList(new ArrayList<>(missing));
        this.extra = extra == null ? Collections.<Row>emptyList() : Collections.unmodifiableList(new ArrayList<>(extra));
        this.pouchUnknown = pouchUnknown;
        this.ok = buildOk && spellbookOk && this.missing.isEmpty() && this.extra.isEmpty();
    }

    public int missingCount()
    {
        return missing.size();
    }

    public int extraCount()
    {
        return extra.size();
    }

    public JsonArray wireDiff()
    {
        JsonArray out = new JsonArray();
        for (List<Row> rows : java.util.Arrays.asList(missing, extra))
        {
            for (Row r : rows)
            {
                if (out.size() >= WIRE_DIFF_MAX) return out;
                JsonArray t = new JsonArray();
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

    @Override
    public String toString()
    {
        return "GearDiff{" + (ok ? "ok" : summary()) + "}";
    }
}
