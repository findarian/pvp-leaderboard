package com.pvp.leaderboard.game;

import lombok.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.tournament.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.game.*;
import net.runelite.client.plugins.bank.*;
import net.runelite.api.gameval.InterfaceID;
import static com.pvp.leaderboard.game.ArenaWidgets.*;

@Singleton
public class GearSearch
{
    public static final long HIGHLIGHT_MS = 15_000L;
    static final int SCROLL_MARGIN = 8;

    public static final class Highlight
    {
        public final int itemId;
        public final List<Integer> altIds;
        public final String name;
        public final long untilMs;

        public Highlight(int itemId, String name, long untilMs)
        {
            this(itemId, Collections.<Integer>emptyList(), name, untilMs);
        }

        public Highlight(int itemId, List<Integer> altIds, String name, long untilMs)
        {
            this.itemId = itemId;
            this.altIds = positive(altIds);
            this.name = name;
            this.untilMs = untilMs;
        }

        public boolean matches(Widget row, IntUnaryOperator base)
        {
            if (rowMatches(row, itemId, name, base)) return true;
            for (int alt : altIds)
            {
                if (rowMatches(row, alt, null, base)) return true;
            }
            return false;
        }

        List<Integer> ids()
        {
            List<Integer> out = new ArrayList<>(1 + altIds.size());
            out.add(itemId);
            out.addAll(altIds);
            return out;
        }

        private static List<Integer> positive(List<Integer> ids)
        {
            if (ids == null || ids.isEmpty()) return Collections.emptyList();
            List<Integer> out = new ArrayList<>();
            for (Integer id : ids)
            {
                if (id != null && id > 0) out.add(id);
            }
            return out.isEmpty() ? Collections.<Integer>emptyList() : Collections.unmodifiableList(out);
        }
    }

    private final Client client;
    Consumer<Runnable> onClientThread;
    Runnable layoutBank;
    BooleanSupplier autoFilterBank;
    LongSupplier nowMs = System::currentTimeMillis;
    IntUnaryOperator base = ItemVariationMapping::map;
    IntFunction<Collection<Integer>> variations = ItemVariationMapping::getVariations;
    private volatile Supplier<Collection<Integer>> missing = Collections::emptyList;
    private volatile Highlight highlight;
    @Getter private volatile boolean bankFiltering;
    private volatile Set<Integer> filterIds = Collections.emptySet();

    @Inject
    public GearSearch(Client client, ClientThread clientThread, BankSearch bankSearch, PvPLeaderboardConfig config)
    {
        this.client = client;
        onClientThread = clientThread::invokeLater;
        layoutBank = bankSearch::layoutBank;
        autoFilterBank = config::gearAutoFilterBank;
    }

    public void setMissing(Supplier<Collection<Integer>> supplier)
    {
        missing = supplier == null ? Collections::emptyList : supplier;
    }

    public Highlight highlight()
    {
        Highlight h = highlight;
        return h == null || nowMs.getAsLong() >= h.untilMs ? null : h;
    }

    public void onItemClicked(int itemId, String name)
    {
        onItemClicked(itemId, Collections.<Integer>emptyList(), name);
    }

    public void onItemClicked(int itemId, List<Integer> altIds, String name)
    {
        if (itemId <= 0) return;
        onClientThread.accept(() -> clicked(itemId, altIds, name));
    }

    public void showMissing(Collection<Integer> itemIds)
    {
        if (itemIds == null || itemIds.isEmpty()) return;
        List<Integer> ids = new ArrayList<>(itemIds);
        onClientThread.accept(() -> filterBank(ids));
    }

    private void clicked(int itemId, List<Integer> altIds, String name)
    {
        var h = new Highlight(itemId, altIds, name, nowMs.getAsLong() + HIGHLIGHT_MS);
        highlight = h;
        try
        {
            ArenaWidgets.KitPanel panel = shownPanel();
            if (panel != null)
            {
                scrollTo(panel, h);
                return;
            }
            if (bankOpen()) filterBank(h.ids());
        }
        catch (RuntimeException e)
        {
        }
    }

    private ArenaWidgets.KitPanel shownPanel()
    {
        if (visible(DUEL_ROOT))
        {
            for (ArenaWidgets.KitPanel p : DUEL_PANELS) if (visible(p.equipment)) return p;
        }
        if (visible(SUPPLIES_ROOT))
        {
            for (ArenaWidgets.KitPanel p : SUPPLIES_PANELS) if (visible(p.equipment)) return p;
        }
        return null;
    }

    private void scrollTo(ArenaWidgets.KitPanel panel, Highlight h)
    {
        Widget list = client.getWidget(panel.itemsList);
        if (list == null) return;
        for (Widget row : KitReader.slotsOf(list))
        {
            if (!h.matches(row, base)) continue;
            int y = Math.max(0, row.getRelativeY() - SCROLL_MARGIN);
            list.setScrollY(y);
            client.runScript(ScriptID.UPDATE_SCROLLBAR, panel.itemsScroll, panel.itemsList, y);
            return;
        }
    }

    public static boolean rowMatches(Widget row, int itemId, String name, IntUnaryOperator base)
    {
        if (row == null) return false;
        int id = row.getItemId();
        if (id > 0 && id != BLANK_ID && base.applyAsInt(id) == base.applyAsInt(itemId)) return true;
        if (name == null || name.trim().isEmpty()) return false;
        String text = row.getText();
        if (text == null) return false;
        String t = GearSet.plain(text);
        String n = name.trim().toLowerCase(Locale.ROOT);
        return t.equals(n) || t.startsWith(n + " ");
    }

    private boolean bankOpen()
    {
        return visible(InterfaceID.Bankmain.ITEMS);
    }

    private void filterBank(List<Integer> ids)
    {
        Set<Integer> expanded = new HashSet<>();
        for (Integer id : ids)
        {
            if (id == null || id <= 0) continue;
            expanded.add(id);
            Collection<Integer> v = variations.apply(id);
            if (v != null) expanded.addAll(v);
        }
        if (expanded.isEmpty()) return;
        filterIds = expanded;
        bankFiltering = true;
        layoutBank.run();
    }

    private boolean visible(int componentId)
    {
        Widget w = client.getWidget(componentId);
        return w != null && !w.isHidden();
    }

    @Subscribe(priority = -1)
    public void onScriptCallbackEvent(ScriptCallbackEvent e)
    {
        if (!bankFiltering) return;
        String name = e.getEventName();
        int[] stack = client.getIntStack();
        int size = client.getIntStackSize();
        if (stack == null) return;
        if ("getSearchingTagTab".equals(name))
        {
            if (size >= 1) stack[size - 1] = 1;
        }
        else if ("bankSearchFilter".equals(name) && size >= 2)
        {
            stack[size - 2] = filterIds.contains(stack[size - 1]) ? 1 : 0;
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e)
    {
        if (e.getGroupId() != BANK_GROUP || !GearKit.safe(autoFilterBank)) return;
        Collection<Integer> ids = missing.get();
        if (ids == null || ids.isEmpty()) return;
        List<Integer> copy = new ArrayList<>(ids);
        onClientThread.accept(() -> filterBank(copy));
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed e)
    {
        if (e.getGroupId() != BANK_GROUP) return;
        bankFiltering = false;
        filterIds = Collections.emptySet();
    }
}
