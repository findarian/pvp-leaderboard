package com.pvp.leaderboard.tournament;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GearKit
{
    public static final String SOURCE_DUEL_KIT = "duel_kit";
    public static final String SOURCE_SUPPLIES = "supplies";
    public static final String SOURCE_CONTAINERS = "containers";
    public static final String SOURCE_SAVED = "saved_kit";
    public static final Set<Integer> RUNE_POUCH_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(12791, 24416, 27281, 27509)));

    public static final class Item
    {
        public final int id;
        public final int qty;
        public final String name;
        public final String slot;
        public final boolean stackable;
        public final boolean noted;

        public Item(int id, int qty, String name, String slot, boolean stackable, boolean noted)
        {
            this.id = id;
            this.qty = qty;
            this.name = name;
            this.slot = slot;
            this.stackable = stackable;
            this.noted = noted;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (!(o instanceof Item)) return false;
            Item i = (Item) o;
            return id == i.id && qty == i.qty && stackable == i.stackable && noted == i.noted
                && Objects.equals(name, i.name) && Objects.equals(slot, i.slot);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, qty, name, slot, stackable, noted);
        }

        @Override
        public String toString()
        {
            return qty + "x" + id + (slot == null ? "" : "@" + slot) + (noted ? "(noted)" : "");
        }
    }

    public final String source;
    public final String build;
    public final String spellbook;
    public final List<Item> worn;
    public final List<Item> carried;
    public final List<Item> pouch;
    public final boolean pouchKnown;
    public final long readAtMs;
    public final long loadoutSig;
    public final long pouchSig;
    public final boolean stale;

    private GearKit(Builder b)
    {
        this.source = b.source;
        this.build = b.build;
        this.spellbook = b.spellbook;
        this.worn = immutable(b.worn);
        this.carried = immutable(b.carried);
        this.pouch = immutable(b.pouch);
        this.pouchKnown = b.pouchKnown;
        this.readAtMs = b.readAtMs;
        this.loadoutSig = b.loadoutSig;
        this.pouchSig = b.pouchSig;
        this.stale = b.stale;
    }

    private static List<Item> immutable(List<Item> items)
    {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        List<Item> out = new ArrayList<>(items.size());
        for (Item i : items) if (i != null) out.add(i);
        return Collections.unmodifiableList(out);
    }

    public static Builder builder(String source)
    {
        return new Builder(source);
    }

    public Builder toBuilder()
    {
        return new Builder(source).forBuild(build).spellbook(spellbook).worn(worn).carried(carried).pouch(pouch, pouchKnown)
            .readAt(readAtMs).signatures(loadoutSig, pouchSig).stale(stale);
    }

    public boolean hasRunePouch()
    {
        for (Item i : carried) if (RUNE_POUCH_IDS.contains(i.id)) return true;
        for (Item i : worn) if (RUNE_POUCH_IDS.contains(i.id)) return true;
        return false;
    }

    public GearKit asStale()
    {
        return toBuilder().stale(true).build();
    }

    public GearKit withSource(String newSource)
    {
        return toBuilder().source(newSource).build();
    }

    public GearKit withPouch(List<Item> runes, boolean known, long newPouchSig)
    {
        return toBuilder().pouch(runes, known).signatures(loadoutSig, newPouchSig).build();
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
        JsonObject o = new JsonObject();
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
        return builder(SOURCE_SAVED)
            .forBuild(JsonLenient.optString(o, "build", null))
            .spellbook(JsonLenient.optString(o, "spellbook", null))
            .worn(itemsFrom(JsonLenient.optArray(o, "worn")))
            .carried(itemsFrom(JsonLenient.optArray(o, "carried")))
            .pouch(itemsFrom(JsonLenient.optArray(o, "pouch")), JsonLenient.optBool(o, "pouch_known", false))
            .readAt(JsonLenient.optLong(o, "read_at", 0L))
            .signatures(longOf(o, "loadout_sig"), longOf(o, "pouch_sig"))
            .build();
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

    private static JsonArray itemsJson(List<Item> items)
    {
        JsonArray a = new JsonArray();
        for (Item i : items)
        {
            JsonObject o = new JsonObject();
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

    private static List<Item> itemsFrom(JsonArray a)
    {
        List<Item> out = new ArrayList<>();
        for (JsonElement e : a)
        {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Integer id = JsonLenient.optInteger(o, "id");
            Integer qty = JsonLenient.optInteger(o, "qty");
            if (id == null || id < 1 || qty == null || qty < 1) continue;
            out.add(new Item(id, qty, JsonLenient.optString(o, "name", "Item " + id), JsonLenient.optString(o, "slot", null),
                JsonLenient.optBool(o, "stackable", false), JsonLenient.optBool(o, "noted", false)));
        }
        return out;
    }

    @Override
    public String toString()
    {
        return "GearKit{" + source + " " + build + " " + spellbook + " worn=" + worn.size() + " carried=" + carried.size()
            + " pouch=" + (pouchKnown ? pouch.size() : "?") + (stale ? " stale" : "") + "}";
    }

    public static final class Builder
    {
        private String source;
        private String build;
        private String spellbook;
        private List<Item> worn = Collections.emptyList();
        private List<Item> carried = Collections.emptyList();
        private List<Item> pouch = Collections.emptyList();
        private boolean pouchKnown;
        private long readAtMs;
        private long loadoutSig;
        private long pouchSig;
        private boolean stale;

        private Builder(String source)
        {
            source(source);
        }

        public Builder source(String s)
        {
            this.source = s == null ? SOURCE_CONTAINERS : s;
            return this;
        }

        public Builder forBuild(String b)
        {
            this.build = b;
            return this;
        }

        public Builder spellbook(String s)
        {
            this.spellbook = s;
            return this;
        }

        public Builder worn(List<Item> items)
        {
            this.worn = items;
            return this;
        }

        public Builder carried(List<Item> items)
        {
            this.carried = items;
            return this;
        }

        public Builder pouch(List<Item> runes, boolean known)
        {
            this.pouch = runes;
            this.pouchKnown = known;
            return this;
        }

        public Builder readAt(long ms)
        {
            this.readAtMs = ms;
            return this;
        }

        public Builder signatures(long loadout, long pouchVarp)
        {
            this.loadoutSig = loadout;
            this.pouchSig = pouchVarp;
            return this;
        }

        public Builder stale(boolean s)
        {
            this.stale = s;
            return this;
        }

        public GearKit build()
        {
            return new GearKit(this);
        }
    }
}
