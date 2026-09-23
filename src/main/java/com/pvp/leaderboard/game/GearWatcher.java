package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.GearKit;
import com.pvp.leaderboard.tournament.GearSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

@Slf4j
@Singleton
public class GearWatcher
{
    static final long FREEZE_AFTER_COMBAT_MS = 5_000L;
    static final int RUNEPOUCH_RUNE_ENUM = EnumID.RUNEPOUCH_RUNE;
    private static final int[] POUCH_TYPES = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
        VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
    private static final int[] POUCH_QUANTITIES = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
        VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6};

    private final Client client;
    private final BooleanSupplier inCombat;
    private final LongSupplier nowMs;
    private volatile BooleanSupplier active = () -> false;
    private volatile GearKit latest;
    private volatile long combatEndedAtMs;

    private boolean dirty = true;
    private boolean wasInCombat;
    private final Map<Integer, ItemComposition> compositions = new HashMap<>();

    @Inject
    public GearWatcher(Client client, FightMonitor fightMonitor)
    {
        this(client, fightMonitor::isInCombat, System::currentTimeMillis);
    }

    GearWatcher(Client client, BooleanSupplier inCombat, LongSupplier nowMs)
    {
        this.client = client;
        this.inCombat = inCombat;
        this.nowMs = nowMs;
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
        if (safe(inCombat)) return true;
        long ended = combatEndedAtMs;
        return ended > 0 && nowMs.getAsLong() - ended < FREEZE_AFTER_COMBAT_MS;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged e)
    {
        if (e != null && (e.getContainerId() == InventoryID.INV || e.getContainerId() == InventoryID.WORN)) dirty = true;
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged e)
    {
        if (e == null) return;
        int varbit = e.getVarbitId();
        if (varbit == VarbitID.SPELLBOOK || contains(POUCH_TYPES, varbit) || contains(POUCH_QUANTITIES, varbit)) dirty = true;
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        tick();
    }

    void tick()
    {
        long now = nowMs.getAsLong();
        boolean fighting = safe(inCombat);
        if (fighting)
        {
            wasInCombat = true;
            combatEndedAtMs = 0L;
        }
        else if (wasInCombat)
        {
            wasInCombat = false;
            combatEndedAtMs = now;
        }
        if (!safe(active)) return;
        long ended = combatEndedAtMs;
        if (fighting || (ended > 0 && now - ended < FREEZE_AFTER_COMBAT_MS)) return;
        if (!dirty && latest != null) return;
        try
        {
            latest = read(now);
            dirty = false;
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] container read failed", e);
        }
    }

    private GearKit read(long now)
    {
        List<GearKit.Item> worn = new ArrayList<>();
        Item[] wornItems = items(client.getItemContainer(InventoryID.WORN));
        for (int idx = 0; idx < wornItems.length; idx++)
        {
            Item i = wornItems[idx];
            if (i == null || i.getId() <= 0 || i.getQuantity() <= 0) continue;
            worn.add(item(i.getId(), i.getQuantity(), slotName(idx)));
        }
        List<GearKit.Item> carried = new ArrayList<>();
        for (Item i : items(client.getItemContainer(InventoryID.INV)))
        {
            if (i == null || i.getId() <= 0 || i.getQuantity() <= 0) continue;
            carried.add(item(i.getId(), i.getQuantity(), null));
        }
        GearKit.Builder b = GearKit.builder(GearKit.SOURCE_CONTAINERS)
            .spellbook(GearSet.spellbookFromText(FightMonitor.getSpellbookName(client.getVarbitValue(VarbitID.SPELLBOOK))))
            .worn(worn)
            .carried(carried)
            .readAt(now);
        GearKit noPouch = b.build();
        return b.pouch(noPouch.hasRunePouch() ? pouchRunes() : new ArrayList<>(), true).build();
    }

    private List<GearKit.Item> pouchRunes()
    {
        List<GearKit.Item> runes = new ArrayList<>();
        EnumComposition runeEnum = client.getEnum(RUNEPOUCH_RUNE_ENUM);
        if (runeEnum == null) return runes;
        for (int n = 0; n < POUCH_TYPES.length; n++)
        {
            int type = client.getVarbitValue(POUCH_TYPES[n]);
            int qty = client.getVarbitValue(POUCH_QUANTITIES[n]);
            if (type <= 0 || qty <= 0) continue;
            int runeId = runeEnum.getIntValue(type);
            if (runeId > 0) runes.add(item(runeId, qty, null));
        }
        return runes;
    }

    private GearKit.Item item(int id, int qty, String slot)
    {
        ItemComposition c = compositions.computeIfAbsent(id, client::getItemDefinition);
        String name = c == null || c.getName() == null ? "Item " + id : c.getName();
        return new GearKit.Item(id, qty, name, slot, c != null && c.isStackable(), c != null && c.getNote() != -1);
    }

    static String slotName(int idx)
    {
        for (int k = 0; k < ArenaWidgets.WORN_SLOT_INDEX.length; k++)
        {
            if (ArenaWidgets.WORN_SLOT_INDEX[k] == idx) return ArenaWidgets.WORN_SLOT_NAMES[k];
        }
        return null;
    }

    private static Item[] items(ItemContainer c)
    {
        if (c == null) return new Item[0];
        Item[] items = c.getItems();
        return items == null ? new Item[0] : items;
    }

    private static boolean contains(int[] ids, int id)
    {
        for (int i : ids) if (i == id) return true;
        return false;
    }

    private static boolean safe(BooleanSupplier s)
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
}
