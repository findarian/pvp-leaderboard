package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.client.eventbus.*;
import net.runelite.api.gameval.InventoryID;
import static net.runelite.api.gameval.VarbitID.*;

@Singleton
public class GearWatcher
{
    static final long FREEZE_MS = 5_000L;
    static final int POUCH_ENUM = EnumID.RUNEPOUCH_RUNE;
    private static final int[] POUCH_TYPES = {RUNE_POUCH_TYPE_1, RUNE_POUCH_TYPE_2, RUNE_POUCH_TYPE_3,
        RUNE_POUCH_TYPE_4, RUNE_POUCH_TYPE_5, RUNE_POUCH_TYPE_6};
    private static final int[] POUCH_COUNTS = {RUNE_POUCH_QUANTITY_1, RUNE_POUCH_QUANTITY_2, RUNE_POUCH_QUANTITY_3,
        RUNE_POUCH_QUANTITY_4, RUNE_POUCH_QUANTITY_5, RUNE_POUCH_QUANTITY_6};

    private final Client client;
    BooleanSupplier inCombat;
    LongSupplier nowMs = System::currentTimeMillis;
    private volatile BooleanSupplier active = () -> false;
    private volatile GearKit latest;
    private volatile long combatEndMs;
    private volatile Set<Integer> runeIds = Collections.emptySet();

    private boolean dirty = true;
    private boolean wasInCombat;
    private final Map<Integer, ItemComposition> compositions = new HashMap<>();

    @Inject
    public GearWatcher(Client client, FightMonitor fightMonitor)
    {
        this.client = client;
        inCombat = fightMonitor::isInCombat;
    }

    public void setActive(BooleanSupplier active)
    {
        this.active = active == null ? () -> false : active;
    }

    public GearKit latest()
    {
        return latest;
    }

    public boolean isFrozen()
    {
        if (GearKit.safe(inCombat)) return true;
        long ended = combatEndMs;
        return ended > 0 && nowMs.getAsLong() - ended < FREEZE_MS;
    }

    /** Whether {@code itemId} is one of the game's rune pouch runes; false for every id until the first tick read them. */
    public boolean isRune(int itemId)
    {
        return runeIds.contains(itemId);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged e)
    {
        if (e.getContainerId() == InventoryID.INV || e.getContainerId() == InventoryID.WORN) dirty = true;
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged e)
    {
        int varbit = e.getVarbitId();
        if (varbit == SPELLBOOK || contains(POUCH_TYPES, varbit) || contains(POUCH_COUNTS, varbit)) dirty = true;
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        tick();
    }

    void tick()
    {
        loadRunes();
        long now = nowMs.getAsLong();
        boolean fighting = GearKit.safe(inCombat);
        if (fighting)
        {
            wasInCombat = true;
            combatEndMs = 0L;
        }
        else if (wasInCombat)
        {
            wasInCombat = false;
            combatEndMs = now;
        }
        if (!GearKit.safe(active)) return;
        long ended = combatEndMs;
        if (fighting || (ended > 0 && now - ended < FREEZE_MS)) return;
        if (!dirty && latest != null) return;
        try
        {
            latest = read(now);
            dirty = false;
        }
        catch (RuntimeException e)
        {
        }
    }

    private void loadRunes()
    {
        if (!runeIds.isEmpty()) return;
        try
        {
            EnumComposition runes = client.getEnum(POUCH_ENUM);
            int[] ids = runes == null ? null : runes.getIntVals();
            if (ids == null) return;
            Set<Integer> read = new HashSet<>();
            for (int id : ids) if (id > 0) read.add(id);
            runeIds = Collections.unmodifiableSet(read);
        }
        catch (RuntimeException e)
        {
        }
    }

    private GearKit read(long now)
    {
        List<GearItem> worn = new ArrayList<>();
        Item[] wornItems = items(client.getItemContainer(InventoryID.WORN));
        for (int idx = 0; idx < wornItems.length; idx++)
        {
            Item i = wornItems[idx];
            if (i == null || i.getId() <= 0 || i.getQuantity() <= 0) continue;
            worn.add(item(i.getId(), i.getQuantity(), slotName(idx)));
        }
        List<GearItem> carried = new ArrayList<>();
        for (Item i : items(client.getItemContainer(InventoryID.INV)))
        {
            if (i == null || i.getId() <= 0 || i.getQuantity() <= 0) continue;
            carried.add(item(i.getId(), i.getQuantity(), null));
        }
        var k = new GearKit(GearKit.SOURCE_CONTAINERS, null, GearSet.bookOfVarbit(client.getVarbitValue(SPELLBOOK)),
            worn, carried, null, false, now, 0L, 0L, false);
        return k.withPouch(k.hasRunePouch() ? pouchRunes() : null, true, 0L);
    }

    private List<GearItem> pouchRunes()
    {
        List<GearItem> runes = new ArrayList<>();
        EnumComposition runeEnum = client.getEnum(POUCH_ENUM);
        if (runeEnum == null) return runes;
        for (int n = 0; n < POUCH_TYPES.length; n++)
        {
            int type = client.getVarbitValue(POUCH_TYPES[n]);
            int qty = client.getVarbitValue(POUCH_COUNTS[n]);
            if (type <= 0 || qty <= 0) continue;
            int runeId = runeEnum.getIntValue(type);
            if (runeId > 0) runes.add(item(runeId, qty, null));
        }
        return runes;
    }

    private GearItem item(int id, int qty, String slot)
    {
        return KitReader.item(compositions.computeIfAbsent(id, client::getItemDefinition), id, qty, slot, false);
    }

    static String slotName(int idx)
    {
        for (int k = 0; k < ArenaWidgets.SLOT_INDEX.length; k++)
        {
            if (ArenaWidgets.SLOT_INDEX[k] == idx) return ArenaWidgets.SLOT_NAMES[k];
        }
        return null;
    }

    private static Item[] items(ItemContainer c)
    {
        if (c == null) return new Item[0];
        Item[] items = c.getItems();
        return items == null ? new Item[0] : items;
    }

    public static boolean contains(int[] ids, int id)
    {
        for (int i : ids) if (i == id) return true;
        return false;
    }
}
