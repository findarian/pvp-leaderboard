package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.game.ArenaWidgets;
import com.pvp.leaderboard.game.DuelKitReader;
import com.pvp.leaderboard.game.GearSearchHelper;
import com.pvp.leaderboard.tournament.GearDuelScreen;
import com.pvp.leaderboard.tournament.GearStatusReporter;
import com.pvp.leaderboard.tournament.TournamentSeries;
import com.pvp.leaderboard.tournament.TournamentSessionTracker;
import com.pvp.leaderboard.util.NameUtils;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ColorUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Singleton
public class ArenaGearOverlay extends Overlay
{
    static final int GREY_TEXT = 0x808080;
    static final Color GREY_COVER = new Color(80, 80, 80, 150);
    static final Color OUTLINE = new Color(0xFF, 0xD7, 0x00, 230);
    static final int OUTLINE_PX = 2;
    static final String UNREAD_TOOLTIP = "The plugin hasn't read your kit yet — open your kit tab (see the PvP Leaderboard panel)";
    static final String STALE_TOOLTIP = "Your kit changed since the plugin last read it — open your kit tab (see the PvP Leaderboard panel)";

    private final Client client;
    private final TooltipManager tooltips;
    private final DuelKitReader reader;
    private final GearSearchHelper search;
    private final TournamentSessionTracker sessions;
    private volatile Supplier<GearStatusReporter.View> view = () -> GearStatusReporter.View.EMPTY;

    private final Map<Widget, Integer> originalColors = new IdentityHashMap<>();
    private boolean greyed;

    @Inject
    public ArenaGearOverlay(Client client, TooltipManager tooltips, DuelKitReader reader, GearSearchHelper search, TournamentSessionTracker sessions)
    {
        this.client = client;
        this.tooltips = tooltips;
        this.reader = reader;
        this.search = search;
        this.sessions = sessions;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    public void setViewSupplier(Supplier<GearStatusReporter.View> supplier)
    {
        this.view = supplier == null ? () -> GearStatusReporter.View.EMPTY : supplier;
    }

    boolean isGreyed()
    {
        return greyed;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        try
        {
            renderInner(g);
        }
        catch (RuntimeException ignored)
        {
        }
        return null;
    }

    private void renderInner(Graphics2D g)
    {
        GearStatusReporter.View v = view.get();
        boolean gearEvent = v != null && v.event != null && v.event.arena;
        if (!gearEvent)
        {
            if (greyed || !originalColors.isEmpty()) applyGrey(false);
            greyed = false;
            return;
        }
        GearDuelScreen s = reader.screen();
        boolean againstOpponent = s != null && isTournamentOpponent(v, s.opponentName);
        boolean grey = againstOpponent && !v.verifiedOk();
        applyGrey(grey);
        greyed = grey;
        if (grey)
        {
            drawCovers(g);
            maybeTooltip(v);
        }
        if (v.event.set.spellbook != null)
        {
            String want = v.event.set.spellbook;
            if (againstOpponent && s.ownPanel >= 0 && s.ownKit != null && !want.equals(s.ownKit.spellbook))
            {
                outline(g, client.getWidget(ArenaWidgets.DUEL_PANELS[s.ownPanel].spellbookMenu));
            }
            int chest = reader.suppliesPanel();
            if (chest >= 0 && ArenaWidgets.BUILD_ORDER[chest].equals(v.event.set.build) && !want.equals(reader.suppliesSpellbook()))
            {
                outline(g, client.getWidget(ArenaWidgets.SUPPLIES_PANELS[chest].spellbookMenu));
            }
        }
        GearSearchHelper.Highlight h = search.highlight();
        if (h != null)
        {
            ArenaWidgets.KitPanel panel = s != null && s.ownPanel >= 0 ? ArenaWidgets.DUEL_PANELS[s.ownPanel]
                : reader.suppliesPanel() >= 0 ? ArenaWidgets.SUPPLIES_PANELS[reader.suppliesPanel()] : null;
            if (panel != null) outlineRows(g, panel, h);
        }
    }

    private boolean isTournamentOpponent(GearStatusReporter.View v, String name)
    {
        TournamentSeries series = sessions.getActiveSeries();
        if (series == null || !series.hasNamedOpponent() || name == null) return false;
        if (series.tournamentId != null && !series.tournamentId.isEmpty() && !series.tournamentId.equals(v.event.tournamentId)) return false;
        String want = NameUtils.canonicalKey(series.opponentName);
        return !want.isEmpty() && want.equals(NameUtils.canonicalKey(name));
    }

    private void applyGrey(boolean grey)
    {
        for (int id : ArenaWidgets.DUEL_CONFIRMS)
        {
            Widget w = client.getWidget(id);
            if (w != null) colourTexts(w, grey, 2);
        }
    }

    private void colourTexts(Widget w, boolean grey, int depth)
    {
        if (w == null) return;
        String text = w.getText();
        if (text != null && !text.trim().isEmpty())
        {
            if (grey)
            {
                if (!originalColors.containsKey(w)) originalColors.put(w, w.getTextColor());
                if (w.getTextColor() != GREY_TEXT) w.setTextColor(GREY_TEXT);
            }
            else
            {
                Integer original = originalColors.remove(w);
                if (original != null) w.setTextColor(original);
            }
        }
        if (depth <= 0) return;
        for (Widget[] kids : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
        {
            if (kids == null) continue;
            for (Widget k : kids) colourTexts(k, grey, depth - 1);
        }
    }

    private void drawCovers(Graphics2D g)
    {
        g.setColor(GREY_COVER);
        for (int id : ArenaWidgets.DUEL_CONFIRMS)
        {
            Widget w = client.getWidget(id);
            if (w == null || w.isHidden()) continue;
            Rectangle b = w.getBounds();
            if (b != null) g.fill(b);
        }
    }

    private void maybeTooltip(GearStatusReporter.View v)
    {
        Point mouse = client.getMouseCanvasPosition();
        if (mouse == null) return;
        for (int id : ArenaWidgets.DUEL_CONFIRMS)
        {
            Widget w = client.getWidget(id);
            if (w == null || w.isHidden()) continue;
            Rectangle b = w.getBounds();
            if (b != null && b.contains(mouse.getX(), mouse.getY()))
            {
                tooltips.add(new Tooltip(tooltipFor(v)));
                return;
            }
        }
    }

    static String tooltipFor(GearStatusReporter.View v)
    {
        if (v.kit == null || v.diff == null) return UNREAD_TOOLTIP;
        if (v.stale()) return STALE_TOOLTIP;
        return "Your kit doesn't match the tournament set (" + v.diff.summary() + ") — see the PvP Leaderboard panel";
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded e)
    {
        if (!greyed || e == null) return;
        MenuEntry entry = e.getMenuEntry();
        if (entry == null || !isConfirm(entry.getParam1())) return;
        String option = entry.getOption();
        if (option == null || !"confirm".equals(option.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT))) return;
        entry.setOption(ColorUtil.wrapWithColorTag("Confirm", Color.GRAY));
    }

    private static boolean isConfirm(int componentId)
    {
        for (int id : ArenaWidgets.DUEL_CONFIRMS) if (id == componentId) return true;
        return false;
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed e)
    {
        if (e != null && e.getGroupId() == ArenaWidgets.DUEL_GROUP)
        {
            originalColors.clear();
            greyed = false;
        }
    }

    private void outlineRows(Graphics2D g, ArenaWidgets.KitPanel panel, GearSearchHelper.Highlight h)
    {
        Widget list = client.getWidget(panel.itemsList);
        if (list == null || list.isHidden()) return;
        Rectangle visibleArea = list.getBounds();
        for (Widget row : DuelKitReader.slotsOf(list))
        {
            if (!h.matches(row, ItemVariationMapping::map)) continue;
            Rectangle b = row.getBounds();
            if (b != null && (visibleArea == null || visibleArea.intersects(b))) outline(g, b);
        }
    }

    private void outline(Graphics2D g, Widget w)
    {
        if (w == null || w.isHidden()) return;
        outline(g, w.getBounds());
    }

    private static void outline(Graphics2D g, Rectangle r)
    {
        if (r == null) return;
        g.setColor(OUTLINE);
        g.fillRect(r.x, r.y, r.width, OUTLINE_PX);
        g.fillRect(r.x, r.y + r.height - OUTLINE_PX, r.width, OUTLINE_PX);
        g.fillRect(r.x, r.y, OUTLINE_PX, r.height);
        g.fillRect(r.x + r.width - OUTLINE_PX, r.y, OUTLINE_PX, r.height);
    }
}
