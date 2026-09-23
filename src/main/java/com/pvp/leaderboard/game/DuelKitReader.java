package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.GearDuelScreen;
import com.pvp.leaderboard.tournament.GearKit;
import com.pvp.leaderboard.tournament.GearSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

@Slf4j
@Singleton
public class DuelKitReader
{
    private final Client client;
    private final ArenaKitStore store;
    private final LongSupplier nowMs;

    private volatile GearDuelScreen screen;
    private volatile int suppliesPanel = -1;
    private volatile String suppliesSpellbook;
    private volatile BooleanSupplier active = () -> false;

    private final Map<Integer, ItemInfo> items = new HashMap<>();
    private String lastOwnBuild;
    private String sessionSelected;

    private static final class ItemInfo
    {
        final String name;
        final boolean stackable;
        final boolean noted;

        ItemInfo(String name, boolean stackable, boolean noted)
        {
            this.name = name;
            this.stackable = stackable;
            this.noted = noted;
        }
    }

    @Inject
    public DuelKitReader(Client client, ArenaKitStore store)
    {
        this(client, store, System::currentTimeMillis);
    }

    DuelKitReader(Client client, ArenaKitStore store, LongSupplier nowMs)
    {
        this.client = client;
        this.store = store;
        this.nowMs = nowMs;
    }

    public GearDuelScreen screen()
    {
        return screen;
    }

    public String selectedBuild()
    {
        GearDuelScreen s = screen;
        return s == null ? null : s.selectedBuild;
    }

    public int suppliesPanel()
    {
        return suppliesPanel;
    }

    public String suppliesSpellbook()
    {
        return suppliesSpellbook;
    }

    public void setActive(BooleanSupplier active)
    {
        this.active = active == null ? () -> false : active;
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        tick();
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed e)
    {
        if (e == null) return;
        if (e.getGroupId() == ArenaWidgets.DUEL_GROUP) screen = null;
        if (e.getGroupId() == ArenaWidgets.SUPPLIES_GROUP)
        {
            suppliesPanel = -1;
            suppliesSpellbook = null;
        }
    }

    void tick()
    {
        boolean on;
        try
        {
            on = active.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            on = false;
        }
        if (!on)
        {
            screen = null;
            suppliesPanel = -1;
            suppliesSpellbook = null;
            return;
        }
        long now = nowMs.getAsLong();
        try
        {
            observeSignatures();
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] signature read failed", e);
        }
        try
        {
            readSupplies(now);
        }
        catch (RuntimeException e)
        {
            suppliesPanel = -1;
            log.debug("[Gear] supplies read failed", e);
        }
        try
        {
            readDuel(now);
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] duel screen read failed", e);
        }
        try
        {
            readPouchPicker();
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] rune pouch read failed", e);
        }
    }

    private void observeSignatures()
    {
        for (int b = 0; b < ArenaWidgets.BUILD_ORDER.length; b++)
        {
            if (!loadoutSynced(client, b)) continue;
            store.observe(ArenaWidgets.BUILD_ORDER[b], loadoutSignature(client, b), client.getVarpValue(ArenaWidgets.LOADOUT_POUCH_VARPS[b]));
        }
    }

    static boolean loadoutSynced(Client client, int buildIndex)
    {
        for (int varbit : ArenaWidgets.LOADOUT_VARBITS[buildIndex])
        {
            if (client.getVarbitValue(varbit) != 0) return true;
        }
        return false;
    }

    private void readSupplies(long now)
    {
        if (!visible(client.getWidget(ArenaWidgets.SUPPLIES_ROOT)))
        {
            suppliesPanel = -1;
            suppliesSpellbook = null;
            return;
        }
        int panel = visiblePanel(ArenaWidgets.SUPPLIES_PANELS);
        suppliesPanel = panel;
        if (panel < 0)
        {
            suppliesSpellbook = null;
            return;
        }
        GearKit kit = readPanel(ArenaWidgets.SUPPLIES_PANELS[panel], GearKit.SOURCE_SUPPLIES, panel, now);
        suppliesSpellbook = kit.spellbook;
        store.put(kit);
        lastOwnBuild = kit.build;
    }

    private void readDuel(long now)
    {
        if (!visible(client.getWidget(ArenaWidgets.DUEL_ROOT)))
        {
            screen = null;
            return;
        }
        GearDuelScreen before = screen;
        if (before == null) sessionSelected = null;
        String opponent = findText(client.getWidget(ArenaWidgets.DUEL_TITLE), 3, DuelKitReader::opponentFromTitle);
        if (opponent == null && before != null) opponent = before.opponentName;
        int panel = visiblePanel(ArenaWidgets.DUEL_PANELS);
        String selected = panel >= 0 ? ArenaWidgets.BUILD_ORDER[panel] : findText(client.getWidget(ArenaWidgets.DUEL_TAB_MY), 3, GearSet::buildFromText);
        if (selected == null) selected = sessionSelected;
        else sessionSelected = selected;
        GearKit own = null;
        if (panel >= 0)
        {
            own = readPanel(ArenaWidgets.DUEL_PANELS[panel], GearKit.SOURCE_DUEL_KIT, panel, now);
            store.put(own);
            lastOwnBuild = own.build;
        }
        screen = new GearDuelScreen(opponent, selected, panel, own);
    }

    private void readPouchPicker()
    {
        if (!visible(client.getWidget(ArenaWidgets.RUNEPOUCH_ROOT))) return;
        String build = lastOwnBuild;
        int b = ArenaWidgets.buildIndex(build);
        if (b < 0) return;
        List<GearKit.Item> runes = new ArrayList<>();
        for (Widget child : slotsOf(client.getWidget(ArenaWidgets.RUNEPOUCH_RUNES)))
        {
            int[] it = firstItem(child, 2);
            if (it != null) runes.add(item(it[0], it[1], null));
        }
        store.putPouch(build, runes, client.getVarpValue(ArenaWidgets.LOADOUT_POUCH_VARPS[b]));
    }

    private GearKit readPanel(ArenaWidgets.KitPanel p, String source, int buildIndex, long now)
    {
        String spellbook = spellbookOf(p);
        if (spellbook == null)
        {
            int v = client.getVarbitValue(ArenaWidgets.LOADOUT_SPELLBOOK_VARBITS[buildIndex]);
            spellbook = v > 0 ? GearSet.spellbookFromVarbit(v) : null;
        }
        return GearKit.builder(source)
            .forBuild(ArenaWidgets.BUILD_ORDER[buildIndex])
            .spellbook(spellbook)
            .worn(wornOf(p))
            .carried(carriedOf(p))
            .readAt(now)
            .signatures(loadoutSignature(client, buildIndex), 0L)
            .build();
    }

    private List<GearKit.Item> wornOf(ArenaWidgets.KitPanel p)
    {
        List<GearKit.Item> worn = new ArrayList<>();
        for (int k = 0; k < p.slots.length; k++)
        {
            int[] it = firstItem(client.getWidget(p.slots[k]), 3);
            if (it != null) worn.add(item(it[0], it[1], ArenaWidgets.WORN_SLOT_NAMES[k]));
        }
        return worn;
    }

    private List<GearKit.Item> carriedOf(ArenaWidgets.KitPanel p)
    {
        List<GearKit.Item> carried = new ArrayList<>();
        for (Widget slot : slotsOf(client.getWidget(p.inventory)))
        {
            int[] it = firstItem(slot, 2);
            if (it != null) carried.add(item(it[0], it[1], null));
        }
        return carried;
    }

    private String spellbookOf(ArenaWidgets.KitPanel p)
    {
        for (int id : new int[]{p.spellbookMenu, p.spellbookDisplay, p.spellbookContainer})
        {
            String s = findText(client.getWidget(id), 3, GearSet::spellbookFromText);
            if (s != null) return s;
        }
        return null;
    }

    private GearKit.Item item(int id, int qty, String slot)
    {
        ItemInfo info = items.get(id);
        if (info == null)
        {
            ItemComposition c = client.getItemDefinition(id);
            String name = c == null || c.getName() == null ? "Item " + id : c.getName().replaceAll("<[^>]*>", "");
            info = new ItemInfo(name, c != null && c.isStackable(), c != null && c.getNote() != -1);
            items.put(id, info);
        }
        return new GearKit.Item(id, qty, info.name, slot, info.stackable, info.noted);
    }

    private int visiblePanel(ArenaWidgets.KitPanel[] panels)
    {
        for (int i = 0; i < panels.length; i++)
        {
            if (visible(client.getWidget(panels[i].equipment))) return i;
        }
        return -1;
    }

    static String opponentFromTitle(String title)
    {
        if (title == null) return null;
        String t = title.replaceAll("<[^>]*>", "").trim();
        String prefix = "unranked duel:";
        if (!t.toLowerCase(Locale.ROOT).startsWith(prefix)) return null;
        String name = t.substring(prefix.length()).trim();
        return name.isEmpty() ? null : name;
    }

    static long loadoutSignature(Client client, int buildIndex)
    {
        long h = 0xcbf29ce484222325L;
        for (int varbit : ArenaWidgets.LOADOUT_VARBITS[buildIndex])
        {
            h ^= client.getVarbitValue(varbit);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static boolean visible(Widget w)
    {
        return w != null && !w.isHidden();
    }

    static int[] firstItem(Widget w, int depth)
    {
        if (w == null || w.isHidden()) return null;
        int id = w.getItemId();
        if (id > 0 && id != ArenaWidgets.BLANK_ITEM_ID) return new int[]{id, Math.max(1, w.getItemQuantity())};
        if (depth <= 0) return null;
        for (Widget[] kids : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
        {
            if (kids == null) continue;
            for (Widget k : kids)
            {
                int[] hit = firstItem(k, depth - 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    public static Widget[] slotsOf(Widget container)
    {
        if (container == null) return new Widget[0];
        for (Widget[] kids : new Widget[][]{container.getDynamicChildren(), container.getStaticChildren(), container.getNestedChildren()})
        {
            if (kids != null && kids.length > 0) return kids;
        }
        return new Widget[0];
    }

    static String findText(Widget w, int depth, Function<String, String> parse)
    {
        if (w == null) return null;
        String own = w.getText();
        if (own != null && !own.trim().isEmpty())
        {
            String parsed = parse.apply(own);
            if (parsed != null) return parsed;
        }
        if (depth <= 0) return null;
        for (Widget[] kids : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
        {
            if (kids == null) continue;
            for (Widget k : kids)
            {
                String hit = findText(k, depth - 1, parse);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
