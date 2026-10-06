package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.tournament.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.game.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.tooltip.*;
import net.runelite.client.util.*;
import net.runelite.api.Point;
import static com.pvp.leaderboard.game.ArenaWidgets.*;

@Singleton
public class ArenaGearOverlay extends Overlay
{
    static final int GREY_TEXT = 0x808080;
    static final Color GREY_COVER = new Color(0x96505050, true);
    static final Color OUTLINE = new Color(0xE6FFD700, true);
    static final int OUTLINE_PX = 2;
    static final String UNREAD_TOOLTIP = "The plugin hasn't read your kit yet — open your kit tab (see the PvP Leaderboard panel)";
    static final String STALE_TOOLTIP = "Your kit changed since the plugin last read it — open your kit tab (see the PvP Leaderboard panel)";

    private final Client client;
    private final TooltipManager tooltips;
    private final KitReader reader;
    private final GearSearch search;
    private final OppTracker sessions;
    private volatile Supplier<GearReporter.View> view = () -> GearReporter.View.EMPTY;

    private final Map<Widget, Integer> originalColors = new IdentityHashMap<>();
    private boolean greyed;

    @Inject
    public ArenaGearOverlay(Client client, TooltipManager tooltips, KitReader reader, GearSearch search, OppTracker sessions)
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

    public void setView(Supplier<GearReporter.View> supplier)
    {
        view = supplier == null ? () -> GearReporter.View.EMPTY : supplier;
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
        GearReporter.View v = view.get();
        boolean gearEvent = v != null && v.event != null && v.event.arena;
        if (!gearEvent)
        {
            if (greyed || !originalColors.isEmpty()) applyGrey(false);
            greyed = false;
            return;
        }
        DuelScreen s = reader.screen();
        boolean againstOpponent = s != null && isTourneyOpp(v, s.opponentName);
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
                outline(g, spellbookBox(DUEL_PANELS[s.ownPanel]));
            }
            int chest = reader.suppliesPanel();
            if (chest >= 0 && GearSet.BUILDS.get(chest).equals(v.event.set.build) && !want.equals(reader.suppliesBook()))
            {
                outline(g, spellbookBox(SUPPLIES_PANELS[chest]));
            }
        }
        GearSearch.Highlight h = search.highlight();
        if (h != null)
        {
            ArenaWidgets.KitPanel panel = s != null && s.ownPanel >= 0 ? DUEL_PANELS[s.ownPanel]
                : reader.suppliesPanel() >= 0 ? SUPPLIES_PANELS[reader.suppliesPanel()] : null;
            if (panel != null) outlineRows(g, panel, h);
        }
    }

    private boolean isTourneyOpp(GearReporter.View v, String name)
    {
        MatchSeries series = sessions.getActiveSeries();
        if (series == null || !series.hasNamedOpponent() || name == null) return false;
        if (series.tournamentId != null && !series.tournamentId.isEmpty() && !series.tournamentId.equals(v.event.tournamentId)) return false;
        String want = NameUtils.canonicalKey(series.opponentName);
        return !want.isEmpty() && want.equals(NameUtils.canonicalKey(name));
    }

    private void applyGrey(boolean grey)
    {
        for (int id : DUEL_CONFIRMS)
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
        for (Widget[] kids : KitReader.kids(w))
        {
            if (kids == null) continue;
            for (Widget k : kids) colourTexts(k, grey, depth - 1);
        }
    }

    private void drawCovers(Graphics2D g)
    {
        g.setColor(GREY_COVER);
        for (int id : DUEL_CONFIRMS)
        {
            Widget w = client.getWidget(id);
            if (w == null || w.isHidden()) continue;
            Rectangle b = w.getBounds();
            if (b != null) g.fill(b);
        }
    }

    private void maybeTooltip(GearReporter.View v)
    {
        Point mouse = client.getMouseCanvasPosition();
        if (mouse == null) return;
        for (int id : DUEL_CONFIRMS)
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

    static String tooltipFor(GearReporter.View v)
    {
        if (v.kit == null || v.diff == null) return UNREAD_TOOLTIP;
        if (v.stale()) return STALE_TOOLTIP;
        return "Your kit doesn't match the tournament set (" + v.diff.summary() + ") — see the PvP Leaderboard panel";
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded e)
    {
        if (!greyed) return;
        MenuEntry entry = e.getMenuEntry();
        if (entry == null || !GearWatcher.contains(DUEL_CONFIRMS, entry.getParam1())) return;
        String option = entry.getOption();
        if (option == null || !"confirm".equals(option.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT))) return;
        entry.setOption(ColorUtil.wrapWithColorTag("Confirm", Color.GRAY));
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed e)
    {
        if (e.getGroupId() == DUEL_GROUP)
        {
            originalColors.clear();
            greyed = false;
        }
    }

    /** The bounds of the panel's spellbook drop-down as shown: the first visible of its display, container and menu. */
    private Rectangle spellbookBox(ArenaWidgets.KitPanel panel)
    {
        for (int id : new int[]{panel.bookDisplay, panel.bookBox, panel.bookMenu})
        {
            Widget w = client.getWidget(id);
            if (w == null || w.isHidden()) continue;
            Rectangle b = w.getBounds();
            if (b != null && !b.isEmpty()) return b;
        }
        return null;
    }

    private void outlineRows(Graphics2D g, ArenaWidgets.KitPanel panel, GearSearch.Highlight h)
    {
        Widget list = client.getWidget(panel.itemsList);
        if (list == null || list.isHidden()) return;
        Rectangle visibleArea = list.getBounds();
        for (Widget row : KitReader.slotsOf(list))
        {
            if (!h.matches(row, ItemVariationMapping::map)) continue;
            Rectangle b = row.getBounds();
            if (b != null && (visibleArea == null || visibleArea.intersects(b))) outline(g, b);
        }
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
