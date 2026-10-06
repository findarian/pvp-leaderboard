package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import lombok.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/** One item: a row of a gear set, or an item read from a kit ({@code noted} only there; {@code variants} and
 *  {@code altIds} only on set rows). */
@EqualsAndHashCode
public final class GearItem
{
    public final int id;
    public final int qty;
    public final String name;
    public final String slot;
    public final boolean stackable;
    public final boolean noted;
    public final String variants;
    public final List<Integer> altIds;

    public GearItem(int id, int qty, String name, String slot, boolean stackable, boolean noted, String variants, Collection<Integer> altIds)
    {
        this.id = id;
        this.qty = qty;
        this.name = name;
        this.slot = slot;
        this.stackable = stackable;
        this.noted = noted;
        this.variants = variants;
        this.altIds = alternatives(id, altIds);
    }

    public static GearItem fromJson(JsonElement e)
    {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        Integer id = optInteger(o, "id");
        Integer qty = optInteger(o, "qty");
        if (id == null || id < 1 || qty == null || qty < 1) return null;
        String name = optString(o, "name").trim();
        return new GearItem(id, qty, name.isEmpty() ? "Item " + id : name, wornSlot(optString(o, "slot", null)),
            optBool(o, "stackable", false), false, variantsMode(optString(o, "variants", null)), altIdsOf(o));
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
        for (JsonElement a : optArray(o, "alt_ids"))
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
        for (String slot : ArenaWidgets.SLOT_NAMES) if (slot.equals(s)) return slot;
        return null;
    }

    static String variantsMode(String s)
    {
        return GearSet.VARIANTS_ANY.equals(s) || GearSet.VARIANTS_EXACT.equals(s) ? s : null;
    }
}
