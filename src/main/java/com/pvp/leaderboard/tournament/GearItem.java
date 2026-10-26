package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import lombok.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/** One item: a row of a gear set, or an item read from a kit ({@code noted} only there; {@code variants},
 *  {@code altIds} and {@code inv} only on set rows). */
@EqualsAndHashCode
public final class GearItem
{
    public static final int INVENTORY_SLOTS = 28;

    public final int id;
    public final int qty;
    public final String name;
    public final String slot;
    public final boolean stackable;
    public final boolean noted;
    public final String variants;
    public final List<Integer> altIds;
    /** The inventory slots (0-27) a carried set row takes in the set's picture, sorted; empty when it has none. */
    public final List<Integer> inv;

    public GearItem(int id, int qty, String name, String slot, boolean stackable, boolean noted, String variants, Collection<Integer> altIds)
    {
        this(id, qty, name, slot, stackable, noted, variants, altIds, Collections.<Integer>emptyList());
    }

    public GearItem(int id, int qty, String name, String slot, boolean stackable, boolean noted, String variants, Collection<Integer> altIds,
                    List<Integer> inv)
    {
        this.id = id;
        this.qty = qty;
        this.name = name;
        this.slot = slot;
        this.stackable = stackable;
        this.noted = noted;
        this.variants = variants;
        this.altIds = alternatives(id, altIds);
        this.inv = inv;
    }

    public static GearItem fromJson(JsonElement e)
    {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        Integer id = optInteger(o, "id");
        Integer qty = optInteger(o, "qty");
        if (id == null || id < 1 || qty == null || qty < 1) return null;
        String name = optString(o, "name").trim();
        String slot = wornSlot(optString(o, "slot", null));
        boolean stackable = optBool(o, "stackable", false);
        return new GearItem(id, qty, name.isEmpty() ? "Item " + id : name, slot, stackable, false,
            variantsMode(optString(o, "variants", null)), altIdsOf(o), slot != null ? Collections.<Integer>emptyList() : invOf(o, stackable ? 1 : qty));
    }

    /** {@code inv}: exactly {@code need} distinct whole slots 0-27, sorted; empty for anything else. */
    static List<Integer> invOf(JsonObject o, int need)
    {
        JsonElement e = o.get("inv");
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != need) return Collections.emptyList();
        Set<Integer> out = new TreeSet<>();
        for (JsonElement a : e.getAsJsonArray())
        {
            Integer v = wholeNumber(a);
            if (v == null || v < 0 || v >= INVENTORY_SLOTS || !out.add(v)) return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
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
