package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.function.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

public final class GearKit
{
    public static final String SOURCE_DUEL = "duel_kit";
    public static final String SOURCE_SUPPLIES = "supplies";
    public static final String SOURCE_CONTAINERS = "containers";
    public static final String SOURCE_SAVED = "saved_kit";
    public static final Set<Integer> POUCH_IDS = Set.of(12791, 24416, 27281, 27509);

    public final String source;
    public final String build;
    public final String spellbook;
    public final List<GearItem> worn;
    public final List<GearItem> carried;
    public final List<GearItem> pouch;
    public final boolean pouchKnown;
    public final long readAtMs;
    public final long loadoutSig;
    public final long pouchSig;
    public final boolean stale;

    /** A null {@code source} reads as {@link #SOURCE_CONTAINERS}; the lists are copied without their null entries. */
    public GearKit(String source, String build, String spellbook, List<GearItem> worn, List<GearItem> carried, List<GearItem> pouch,
                   boolean pouchKnown, long readAtMs, long loadoutSig, long pouchSig, boolean stale)
    {
        this.source = source == null ? SOURCE_CONTAINERS : source;
        this.build = build;
        this.spellbook = spellbook;
        this.worn = immutable(worn);
        this.carried = immutable(carried);
        this.pouch = immutable(pouch);
        this.pouchKnown = pouchKnown;
        this.readAtMs = readAtMs;
        this.loadoutSig = loadoutSig;
        this.pouchSig = pouchSig;
        this.stale = stale;
    }

    private static List<GearItem> immutable(List<GearItem> items)
    {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        List<GearItem> out = new ArrayList<>(items.size());
        for (GearItem i : items) if (i != null) out.add(i);
        return Collections.unmodifiableList(out);
    }

    /** {@code s}'s answer; false when it throws. */
    public static boolean safe(BooleanSupplier s)
    {
        try
        {
            return s.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    public boolean hasRunePouch()
    {
        for (GearItem i : carried) if (POUCH_IDS.contains(i.id)) return true;
        for (GearItem i : worn) if (POUCH_IDS.contains(i.id)) return true;
        return false;
    }

    public GearKit asStale()
    {
        return new GearKit(source, build, spellbook, worn, carried, pouch, pouchKnown, readAtMs, loadoutSig, pouchSig, true);
    }

    public GearKit withSource(String newSource)
    {
        return new GearKit(newSource, build, spellbook, worn, carried, pouch, pouchKnown, readAtMs, loadoutSig, pouchSig, stale);
    }

    public GearKit withPouch(List<GearItem> runes, boolean known, long newPouchSig)
    {
        return new GearKit(source, build, spellbook, worn, carried, runes, known, readAtMs, loadoutSig, newPouchSig, stale);
    }

    public boolean sameContent(GearKit o)
    {
        return o != null && Objects.equals(build, o.build) && Objects.equals(spellbook, o.spellbook) && worn.equals(o.worn)
            && carried.equals(o.carried) && pouch.equals(o.pouch) && pouchKnown == o.pouchKnown && loadoutSig == o.loadoutSig
            && pouchSig == o.pouchSig;
    }

    public boolean isEmpty()
    {
        return worn.isEmpty() && carried.isEmpty() && pouch.isEmpty();
    }

    public String toJson()
    {
        var o = new JsonObject();
        o.addProperty("build", build);
        o.addProperty("spellbook", spellbook);
        o.add("worn", itemsJson(worn));
        o.add("carried", itemsJson(carried));
        o.add("pouch", itemsJson(pouch));
        o.addProperty("pouch_known", pouchKnown);
        o.addProperty("read_at", readAtMs);
        o.addProperty("loadout_sig", loadoutSig);
        o.addProperty("pouch_sig", pouchSig);
        return o.toString();
    }

    public static GearKit fromJson(String text)
    {
        if (text == null || text.trim().isEmpty()) return null;
        JsonElement e;
        try
        {
            e = new JsonParser().parse(text);
        }
        catch (RuntimeException ex)
        {
            return null;
        }
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        return new GearKit(SOURCE_SAVED, optString(o, "build", null), optString(o, "spellbook", null),
            itemsFrom(optArray(o, "worn")), itemsFrom(optArray(o, "carried")), itemsFrom(optArray(o, "pouch")),
            optBool(o, "pouch_known", false), optLong(o, "read_at", 0L), longOf(o, "loadout_sig"), longOf(o, "pouch_sig"), false);
    }

    private static long longOf(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return 0L;
        try
        {
            return e.getAsLong();
        }
        catch (RuntimeException ex)
        {
            return 0L;
        }
    }

    private static JsonArray itemsJson(List<GearItem> items)
    {
        var a = new JsonArray();
        for (GearItem i : items)
        {
            var o = new JsonObject();
            o.addProperty("id", i.id);
            o.addProperty("qty", i.qty);
            o.addProperty("name", i.name);
            if (i.slot != null) o.addProperty("slot", i.slot);
            if (i.stackable) o.addProperty("stackable", true);
            if (i.noted) o.addProperty("noted", true);
            a.add(o);
        }
        return a;
    }

    private static List<GearItem> itemsFrom(JsonArray a)
    {
        List<GearItem> out = new ArrayList<>();
        for (JsonElement e : a)
        {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Integer id = optInteger(o, "id");
            Integer qty = optInteger(o, "qty");
            if (id == null || id < 1 || qty == null || qty < 1) continue;
            out.add(new GearItem(id, qty, optString(o, "name", "Item " + id), optString(o, "slot", null),
                optBool(o, "stackable", false), optBool(o, "noted", false), null, null));
        }
        return out;
    }
}
