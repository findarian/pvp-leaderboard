package com.pvp.leaderboard.tournament;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GearItem
{
    static final String[] WORN_SLOTS = {"head", "cape", "neck", "weapon", "body", "shield", "legs", "hands", "feet", "ring", "ammo"};

    public final int id;
    public final int qty;
    public final String name;
    public final String slot;
    public final boolean stackable;
    public final String variants;
    public final List<Integer> altIds;

    public GearItem(int id, int qty, String name, String slot, boolean stackable, String variants, Collection<Integer> altIds)
    {
        this.id = id;
        this.qty = qty;
        this.name = name;
        this.slot = slot;
        this.stackable = stackable;
        this.variants = variants;
        this.altIds = alternatives(id, altIds);
    }

    public static GearItem fromJson(JsonElement e)
    {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        Integer id = JsonLenient.optInteger(o, "id");
        Integer qty = JsonLenient.optInteger(o, "qty");
        if (id == null || id < 1 || qty == null || qty < 1) return null;
        String name = JsonLenient.optString(o, "name", "").trim();
        return new GearItem(id, qty, name.isEmpty() ? "Item " + id : name, wornSlot(JsonLenient.optString(o, "slot", null)),
            JsonLenient.optBool(o, "stackable", false), variantsMode(JsonLenient.optString(o, "variants", null)), altIdsOf(o));
    }

    public List<Integer> ids()
    {
        List<Integer> out = new ArrayList<>(1 + altIds.size());
        out.add(id);
        out.addAll(altIds);
        return out;
    }

    public boolean isWorn()
    {
        return slot != null;
    }

    static List<Integer> altIdsOf(JsonObject o)
    {
        List<Integer> out = new ArrayList<>();
        for (JsonElement a : JsonLenient.optArray(o, "alt_ids"))
        {
            Integer v = wholeNumber(a);
            if (v != null) out.add(v);
        }
        return out;
    }

    private static Integer wholeNumber(JsonElement e)
    {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try
        {
            double d = e.getAsDouble();
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
            return (int) d;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    static List<Integer> alternatives(int id, Collection<Integer> raw)
    {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        Set<Integer> out = new LinkedHashSet<>();
        for (Integer alt : raw)
        {
            if (alt != null && alt > 0 && alt != id) out.add(alt);
        }
        return out.isEmpty() ? Collections.<Integer>emptyList() : Collections.unmodifiableList(new ArrayList<>(out));
    }

    static String wornSlot(String s)
    {
        if (s == null) return null;
        for (String slot : WORN_SLOTS) if (slot.equals(s)) return slot;
        return null;
    }

    static String variantsMode(String s)
    {
        return GearSet.VARIANTS_ANY.equals(s) || GearSet.VARIANTS_EXACT.equals(s) ? s : null;
    }
}
