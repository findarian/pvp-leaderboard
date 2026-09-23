package com.pvp.leaderboard.game;

import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.bank.BankSearch;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Slf4j
@Singleton
public class GearSearchHelper
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
    private final Consumer<Runnable> onClientThread;
    private final Runnable layoutBank;
    private final BooleanSupplier autoFilterBank;
    private final LongSupplier nowMs;
    private final IntUnaryOperator base;
    private final IntFunction<Collection<Integer>> variations;
    private volatile Supplier<Collection<Integer>> missing = Collections::emptyList;
    private volatile Highlight highlight;
    private volatile boolean bankFiltering;
    private volatile Set<Integer> bankFilterIds = Collections.emptySet();

    @Inject
    public GearSearchHelper(Client client, ClientThread clientThread, BankSearch bankSearch, PvPLeaderboardConfig config)
    {
        this(client, clientThread::invokeLater, bankSearch::layoutBank, config::gearAutoFilterBank, System::currentTimeMillis,
            ItemVariationMapping::map, ItemVariationMapping::getVariations);
    }

    GearSearchHelper(Client client, Consumer<Runnable> onClientThread, Runnable layoutBank, BooleanSupplier autoFilterBank, LongSupplier nowMs,
                     IntUnaryOperator base, IntFunction<Collection<Integer>> variations)
    {
        this.client = client;
        this.onClientThread = onClientThread;
        this.layoutBank = layoutBank;
        this.autoFilterBank = autoFilterBank;
        this.nowMs = nowMs;
        this.base = base;
        this.variations = variations;
    }

    public void setMissingSupplier(Supplier<Collection<Integer>> supplier)
    {
        this.missing = supplier == null ? Collections::emptyList : supplier;
    }

    public Highlight highlight()
    {
        Highlight h = highlight;
        return h == null || nowMs.getAsLong() >= h.untilMs ? null : h;
    }

    public boolean isBankFiltering()
    {
        return bankFiltering;
    }

    public void onItemClicked(int itemId, String name)
    {
        onItemClicked(itemId, Collections.<Integer>emptyList(), name);
    }

    public void onItemClicked(int itemId, List<Integer> altIds, String name)
    {
        if (itemId <= 0) return;
        List<Integer> alts = altIds == null ? Collections.<Integer>emptyList() : new ArrayList<>(altIds);
        onClientThread.accept(() -> clicked(itemId, alts, name));
    }

    public void showMissingInBank(Collection<Integer> itemIds)
    {
        if (itemIds == null || itemIds.isEmpty()) return;
        List<Integer> ids = new ArrayList<>(itemIds);
        onClientThread.accept(() -> filterBank(ids));
    }

    private void clicked(int itemId, List<Integer> altIds, String name)
    {
        Highlight h = new Highlight(itemId, altIds, name, nowMs.getAsLong() + HIGHLIGHT_MS);
        highlight = h;
        try
        {
            ArenaWidgets.KitPanel panel = visibleKitPanel();
            if (panel != null)
            {
                scrollTo(panel, h);
                return;
            }
            if (bankOpen()) filterBank(h.ids());
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] find-item failed", e);
        }
    }

    private ArenaWidgets.KitPanel visibleKitPanel()
    {
        if (visible(ArenaWidgets.DUEL_ROOT))
        {
            for (ArenaWidgets.KitPanel p : ArenaWidgets.DUEL_PANELS) if (visible(p.equipment)) return p;
        }
        if (visible(ArenaWidgets.SUPPLIES_ROOT))
        {
            for (ArenaWidgets.KitPanel p : ArenaWidgets.SUPPLIES_PANELS) if (visible(p.equipment)) return p;
        }
        return null;
    }

    private void scrollTo(ArenaWidgets.KitPanel panel, Highlight h)
    {
        Widget list = client.getWidget(panel.itemsList);
        if (list == null) return;
        for (Widget row : DuelKitReader.slotsOf(list))
        {
            if (!h.matches(row, base)) continue;
            int y = Math.max(0, row.getRelativeY() - SCROLL_MARGIN);
            list.setScrollY(y);
            client.runScript(ScriptID.UPDATE_SCROLLBAR, panel.itemsScrollbar, panel.itemsList, y);
            return;
        }
    }

    public static boolean rowMatches(Widget row, int itemId, String name, IntUnaryOperator base)
    {
        if (row == null) return false;
        int id = row.getItemId();
        if (id > 0 && id != ArenaWidgets.BLANK_ITEM_ID && base.applyAsInt(id) == base.applyAsInt(itemId)) return true;
        if (name == null || name.trim().isEmpty()) return false;
        String text = row.getText();
        if (text == null) return false;
        String t = text.replaceAll("<[^>]*>", "").replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
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
        bankFilterIds = expanded;
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
        if (!bankFiltering || e == null) return;
        String name = e.getEventName();
        if ("getSearchingTagTab".equals(name))
        {
            int[] stack = client.getIntStack();
            int size = client.getIntStackSize();
            if (stack != null && size >= 1) stack[size - 1] = 1;
        }
        else if ("bankSearchFilter".equals(name))
        {
            int[] stack = client.getIntStack();
            int size = client.getIntStackSize();
            if (stack == null || size < 2) return;
            stack[size - 2] = bankFilterIds.contains(stack[size - 1]) ? 1 : 0;
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e)
    {
        if (e == null || e.getGroupId() != ArenaWidgets.BANK_GROUP) return;
        boolean auto;
        try
        {
            auto = autoFilterBank.getAsBoolean();
        }
        catch (RuntimeException ex)
        {
            auto = false;
        }
        if (!auto) return;
        Collection<Integer> ids = missing.get();
        if (ids == null || ids.isEmpty()) return;
        List<Integer> copy = new ArrayList<>(ids);
        onClientThread.accept(() -> filterBank(copy));
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed e)
    {
        if (e == null || e.getGroupId() != ArenaWidgets.BANK_GROUP) return;
        bankFiltering = false;
        bankFilterIds = Collections.emptySet();
    }
}
