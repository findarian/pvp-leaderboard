package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.eventbus.*;
import net.runelite.api.gameval.InterfaceID;
import static com.pvp.leaderboard.game.ArenaWidgets.*;

@Singleton
public class KitReader
{
    private final Client client;
    private final KitStore store;
    LongSupplier nowMs = System::currentTimeMillis;

    private volatile DuelScreen screen;
    private volatile int suppliesPanel = -1;
    private volatile String suppliesBook;
    private volatile BooleanSupplier active = () -> false;

    private final Map<Integer, ItemComposition> items = new HashMap<>();
    private String lastOwnBuild;
    private String sessionSelected;

    @Inject
    public KitReader(Client client, KitStore store)
    {
        this.client = client;
        this.store = store;
    }

    public DuelScreen screen()
    {
        return screen;
    }

    public String selectedBuild()
    {
        DuelScreen s = screen;
        return s == null ? null : s.selectedBuild;
    }

    public int suppliesPanel()
    {
        return suppliesPanel;
    }

    public String suppliesBook()
    {
        return suppliesBook;
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
        if (e.getGroupId() == DUEL_GROUP) screen = null;
        if (e.getGroupId() == InterfaceID.PVP_ARENA_STAGINGAREA_SUPPLIES)
        {
            suppliesPanel = -1;
            suppliesBook = null;
        }
    }

    void tick()
    {
        if (!GearKit.safe(active))
        {
            screen = null;
            suppliesPanel = -1;
            suppliesBook = null;
            return;
        }
        long now = nowMs.getAsLong();
        try
        {
            observeSigs();
        }
        catch (RuntimeException e)
        {
        }
        try
        {
            readSupplies(now);
        }
        catch (RuntimeException e)
        {
            suppliesPanel = -1;
        }
        try
        {
            readDuel(now);
        }
        catch (RuntimeException e)
        {
        }
        try
        {
            readPicker();
        }
        catch (RuntimeException e)
        {
        }
    }

    private void observeSigs()
    {
        for (int b = 0; b < GearSet.BUILDS.size(); b++)
        {
            if (!loadoutSynced(client, b)) continue;
            store.observe(GearSet.BUILDS.get(b), loadoutHash(client, b), client.getVarpValue(POUCH_VARP + b));
        }
    }

    static boolean loadoutSynced(Client client, int buildIndex)
    {
        for (int varbit : LOADOUT_VARBITS[buildIndex])
        {
            if (client.getVarbitValue(varbit) != 0) return true;
        }
        return false;
    }

    private void readSupplies(long now)
    {
        if (!visible(client.getWidget(SUPPLIES_ROOT)))
        {
            suppliesPanel = -1;
            suppliesBook = null;
            return;
        }
        int panel = visiblePanel(SUPPLIES_PANELS);
        suppliesPanel = panel;
        if (panel < 0)
        {
            suppliesBook = null;
            return;
        }
        GearKit kit = readPanel(SUPPLIES_PANELS[panel], GearKit.SOURCE_SUPPLIES, panel, now);
        suppliesBook = kit.spellbook;
        store.put(kit);
        lastOwnBuild = kit.build;
    }

    private void readDuel(long now)
    {
        if (!visible(client.getWidget(DUEL_ROOT)))
        {
            screen = null;
            return;
        }
        DuelScreen before = screen;
        if (before == null) sessionSelected = null;
        String opponent = findText(client.getWidget(InterfaceID.PvpArenaUnrankedduel.TITLE), 3, KitReader::opponentFromTitle);
        if (opponent == null && before != null) opponent = before.opponentName;
        int panel = visiblePanel(DUEL_PANELS);
        String selected = panel >= 0 ? GearSet.BUILDS.get(panel) : findText(client.getWidget(InterfaceID.PvpArenaUnrankedduel.TAB_MYEQUIPMENT), 3, GearSet::buildFromText);
        if (selected == null) selected = sessionSelected;
        else sessionSelected = selected;
        GearKit own = null;
        if (panel >= 0)
        {
            own = readPanel(DUEL_PANELS[panel], GearKit.SOURCE_DUEL, panel, now);
            store.put(own);
            lastOwnBuild = own.build;
        }
        screen = new DuelScreen(opponent, selected, panel, own);
    }

    private void readPicker()
    {
        if (!visible(client.getWidget(InterfaceID.PvpArenaRunepouch.UNIVERSE))) return;
        String build = lastOwnBuild;
        int b = GearSet.BUILDS.indexOf(build);
        if (b < 0) return;
        List<GearItem> runes = new ArrayList<>();
        for (Widget child : slotsOf(client.getWidget(InterfaceID.PvpArenaRunepouch.RUNES)))
        {
            int[] it = firstItem(child, 2);
            if (it != null) runes.add(item(it[0], it[1], null));
        }
        store.putPouch(build, runes, client.getVarpValue(POUCH_VARP + b));
    }

    private GearKit readPanel(ArenaWidgets.KitPanel p, String source, int buildIndex, long now)
    {
        String spellbook = spellbookOf(p);
        if (spellbook == null)
        {
            int v = client.getVarbitValue(LOADOUT_VARBITS[buildIndex][39]);
            spellbook = v > 0 ? GearSet.bookOfVarbit(v) : null;
        }
        return new GearKit(source, GearSet.BUILDS.get(buildIndex), spellbook, wornOf(p), carriedOf(p), null, false, now,
            loadoutHash(client, buildIndex), 0L, false);
    }

    private List<GearItem> wornOf(ArenaWidgets.KitPanel p)
    {
        List<GearItem> worn = new ArrayList<>();
        for (int k = 0; k < p.slots.length; k++)
        {
            int[] it = firstItem(client.getWidget(p.slots[k]), 3);
            if (it != null) worn.add(item(it[0], it[1], SLOT_NAMES[k]));
        }
        return worn;
    }

    private List<GearItem> carriedOf(ArenaWidgets.KitPanel p)
    {
        List<GearItem> carried = new ArrayList<>();
        for (Widget slot : slotsOf(client.getWidget(p.inventory)))
        {
            int[] it = firstItem(slot, 2);
            if (it != null) carried.add(item(it[0], it[1], null));
        }
        return carried;
    }

    private String spellbookOf(ArenaWidgets.KitPanel p)
    {
        for (int id : new int[]{p.bookMenu, p.bookDisplay, p.bookBox})
        {
            String s = findText(client.getWidget(id), 3, GearSet::bookOfText);
            if (s != null) return s;
        }
        return null;
    }

    private GearItem item(int id, int qty, String slot)
    {
        return item(items.computeIfAbsent(id, client::getItemDefinition), id, qty, slot, true);
    }

    /** A kit item from its definition; {@code plain} strips tags from the name. */
    static GearItem item(ItemComposition c, int id, int qty, String slot, boolean plain)
    {
        String name = c == null || c.getName() == null ? "Item " + id : plain ? c.getName().replaceAll("<[^>]*>", "") : c.getName();
        return new GearItem(id, qty, name, slot, c != null && c.isStackable(), c != null && c.getNote() != -1, null, null);
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

    static long loadoutHash(Client client, int buildIndex)
    {
        long h = 0xcbf29ce484222325L;
        for (int varbit : LOADOUT_VARBITS[buildIndex])
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

    /** {@code w}'s dynamic, static and nested children, in that order. */
    public static Widget[][] kids(Widget w)
    {
        return new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()};
    }

    static int[] firstItem(Widget w, int depth)
    {
        if (w == null || w.isHidden()) return null;
        int id = w.getItemId();
        if (id > 0 && id != BLANK_ID) return new int[]{id, Math.max(1, w.getItemQuantity())};
        if (depth <= 0) return null;
        for (Widget[] kids : kids(w))
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
        for (Widget[] kids : kids(container))
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
        for (Widget[] kids : kids(w))
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
