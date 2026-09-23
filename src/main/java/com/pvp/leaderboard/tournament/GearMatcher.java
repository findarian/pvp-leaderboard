package com.pvp.leaderboard.tournament;

import net.runelite.client.game.ItemVariationMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

public final class GearMatcher
{
    private final IntUnaryOperator variations;
    private final IntPredicate isRune;

    public GearMatcher(IntPredicate isRune)
    {
        this(ItemVariationMapping::map, isRune);
    }

    public GearMatcher(IntUnaryOperator variations, IntPredicate isRune)
    {
        this.variations = variations == null ? IntUnaryOperator.identity() : variations;
        this.isRune = isRune == null ? id -> false : isRune;
    }

    private static final class Units
    {
        final String name;
        final boolean stackable;
        long qty;

        Units(String name, boolean stackable)
        {
            this.name = name;
            this.stackable = stackable;
        }
    }

    private static final class Group
    {
        final boolean exact;
        final Set<Integer> keys;
        final GearItem first;
        long need;
        long have;

        Group(boolean exact, Set<Integer> keys, GearItem first)
        {
            this.exact = exact;
            this.keys = keys;
            this.first = first;
        }
    }

    public GearDiff match(GearSet set, GearKit kit, String buildInUse)
    {
        if (set == null || kit == null) return null;
        String kitBuild = buildInUse != null ? buildInUse : kit.build;
        boolean buildOk = kitBuild == null || set.build.equals(kitBuild);
        boolean spellbookOk = set.spellbook == null || set.spellbook.equals(kit.spellbook);

        boolean pouchChecked = listsRunes(set);
        Map<Integer, Units> units = new LinkedHashMap<>();
        Map<Integer, Units> noted = new LinkedHashMap<>();
        collect(kit.worn, units, noted);
        collect(kit.carried, units, noted);
        if (pouchChecked && kit.pouchKnown) collect(kit.pouch, units, noted);

        Map<String, Group> groups = new LinkedHashMap<>();
        for (GearItem item : set.items)
        {
            boolean exact = GearSet.VARIANTS_EXACT.equals(set.variantsFor(item));
            Set<Integer> keys = new TreeSet<>();
            for (int id : item.ids()) keys.add(exact ? id : variations.applyAsInt(id));
            Group g = groups.computeIfAbsent((exact ? "x" : "a") + keys, k -> new Group(exact, keys, item));
            g.need += item.qty;
        }

        Map<Integer, Long> remaining = new LinkedHashMap<>();
        for (Map.Entry<Integer, Units> e : units.entrySet()) remaining.put(e.getKey(), e.getValue().qty);
        for (Group g : groups.values())
        {
            if (g.exact) take(g, remaining);
        }
        for (Group g : groups.values())
        {
            if (!g.exact) take(g, remaining);
        }

        List<GearDiff.Row> standalone = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : remaining.entrySet())
        {
            long left = e.getValue();
            if (left <= 0) continue;
            Group owner = ownerOf(groups.values(), e.getKey());
            if (owner != null)
            {
                owner.have += left;
                continue;
            }
            Units u = units.get(e.getKey());
            standalone.add(new GearDiff.Row(e.getKey(), u.name, 0, clamp(left), u.stackable, false));
        }

        List<GearDiff.Row> missing = new ArrayList<>();
        List<GearDiff.Row> extra = new ArrayList<>();
        for (Group g : groups.values())
        {
            GearDiff.Row row = new GearDiff.Row(g.first.id, g.first.name, clamp(g.need), clamp(g.have), g.first.stackable, false,
                g.first.altIds);
            if (row.isMissing()) missing.add(row);
            else if (row.isExtra()) extra.add(row);
        }
        extra.addAll(standalone);
        for (Map.Entry<Integer, Units> e : noted.entrySet())
        {
            extra.add(new GearDiff.Row(e.getKey(), e.getValue().name, 0, clamp(e.getValue().qty), e.getValue().stackable, true));
        }
        boolean pouchUnknown = pouchChecked && kit.hasRunePouch() && !kit.pouchKnown;
        return new GearDiff(set.build, kitBuild, buildOk, set.spellbook, kit.spellbook, spellbookOk, missing, extra, pouchUnknown);
    }

    private boolean listsRunes(GearSet set)
    {
        for (GearItem item : set.items)
        {
            for (int id : item.ids())
            {
                if (isRune.test(id) || isRune.test(variations.applyAsInt(id))) return true;
            }
        }
        return false;
    }

    private void take(Group g, Map<Integer, Long> remaining)
    {
        for (Map.Entry<Integer, Long> e : remaining.entrySet())
        {
            if (g.have >= g.need) return;
            if (e.getValue() <= 0 || !holds(g, e.getKey())) continue;
            long taken = Math.min(e.getValue(), g.need - g.have);
            g.have += taken;
            e.setValue(e.getValue() - taken);
        }
    }

    private Group ownerOf(Collection<Group> groups, int id)
    {
        for (Group g : groups)
        {
            if (g.exact && holds(g, id)) return g;
        }
        for (Group g : groups)
        {
            if (!g.exact && holds(g, id)) return g;
        }
        return null;
    }

    private boolean holds(Group g, int kitId)
    {
        return g.keys.contains(g.exact ? kitId : variations.applyAsInt(kitId));
    }

    private static void collect(List<GearKit.Item> items, Map<Integer, Units> units, Map<Integer, Units> noted)
    {
        for (GearKit.Item i : items)
        {
            if (i == null || i.id <= 0 || i.qty <= 0) continue;
            Map<Integer, Units> into = i.noted ? noted : units;
            into.computeIfAbsent(i.id, k -> new Units(i.name, i.stackable)).qty += i.qty;
        }
    }

    private static int clamp(long v)
    {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }
}
