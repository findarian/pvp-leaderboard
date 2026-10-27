package com.pvp.leaderboard.perf;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.ui.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;
import net.runelite.client.config.*;

/** The Performance tab: every stored fight, merged and filtered in memory, as cards 50 at a time (Individual fights)
 *  or as the Overall stats box; and the sync. A new fight adds one card; a filter change rebuilds once, after a short
 *  wait. The filter controls show and hide behind one button; that choice is kept in RuneLite's settings. */
public class FightsPanel extends JPanel
{
    static final String INDIVIDUAL = "individual fights", EST = "100% est.", MMR = "MMR Change";
    static final int PAGE = 50;
    private static final String SYNCED = "  Synced";
    /** The settings key holding whether the filter controls are shown; absent means hidden. */
    private static final String GROUP = FightMonitor.CONFIG_GROUP, FILTERS = "perfFilters";
    // SYNC: private static final String CAPTION = "Allowed once per day, don't click unless there are missing matches locally";
    static final String[] LABELS = {"Off-pray", "Expected damage", "Damage", "Magic luck", "Offensive prayer",
        "HP healed", "Hits on robes", "KO chances", "Ghost barrages"};
    static final String[] SHORT = {"OP", "eD", "D", "M", "P", "HP", "rH", "KO", "GB"};
    /** Per row: 1 when the higher value is better, -1 when the lower is, 0 when the row is not compared. */
    private static final int[] BETTER = {0, 1, 1, 1, 0, 0, -1, 1, 0};
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT);
    static final Color WIN_BG = new Color(0x2b3a30), LOSS_BG = new Color(0x3c2b2b), WORSE = new Color(0xff6b6b);
    /** The width a card's lines fit in: the side panel's 225 px less its padding and the card's. */
    static final int WIDTH = 190;
    /** The least space between a value and its row's label. */
    private static final int GAP = 3;
    /** The side panel's width with its scrollbar, where a filter's label and control share a row only if both fit. */
    private static final int PANEL = 205;

    private final FightStore store;
    private final ConfigManager config;
    // SYNC: private final Grab grab;
    private final JPanel bar, view = new JPanel(new BorderLayout()), cards = Ui.column(), list = Ui.column(), box = Ui.column(),
        filters = Ui.column();
    private final JButton more = new JButton("Show 50 more"), toggle = new JButton("Show filters");
    // SYNC: private final JButton grabButton = new JButton("Sync from PvP Leaderboard");
    // SYNC: private final JLabel caption = text("", Font.PLAIN, Ui.MUTED), grabLine = text("", Font.PLAIN, null);
    private final JComboBox<String> names = new JComboBox<>(), styles = new JComboBox<>(), rank = new JComboBox<>(),
        recent = new JComboBox<>(FightFilter.RECENT), show = new JComboBox<>(FightFilter.SHOW);
    private final JCheckBox partials = new JCheckBox("Include partial fights", true);
    private final FightFilter filter = new FightFilter();
    Timer debounce;
    private List<FightLog.Fight> merged = new ArrayList<>(), shown = new ArrayList<>();
    private OverallStats totals = new OverallStats();
    /** Each shown fight as the totals counted it: its result, change and both sides' values at the time. */
    private final Map<FightLog.Fight, FightLog.Fight> counted = new IdentityHashMap<>();
    /** The Played as names, by canonical name, A-Z. */
    private final TreeMap<String, String> known = new TreeMap<>();
    private boolean quiet;
    private int built, limit = PAGE;

    // SYNC: public FightsPanel(FightStore store, Grab grab, ConfigManager config)
    public FightsPanel(FightStore store, ConfigManager config)
    {
        super(new BorderLayout(0, 6));
        this.store = store;
        this.config = config;
        // SYNC: this.grab = grab;
        bar = Ui.bucketBar("perf-views", "perf-view", 1, 2, new String[]{"Individual fights", "Overall stats"}, this::show);
        JPanel top = Ui.column();
        top.add(Ui.left(bar));
        toggle.setName("perf-filters");
        toggle.setFont(Ui.smallFont());
        toggle.setMargin(new Insets(2, 2, 2, 2));
        toggle.setFocusPainted(false);
        toggle.addActionListener(e -> toggled());
        top.add(Ui.left(Ui.pin(toggle)));
        // SYNC: grabButton.setName("perf-grab");
        // SYNC: grabButton.setMargin(new Insets(2, 2, 2, 2));
        // SYNC: grabButton.addActionListener(e -> grab.click());
        // SYNC: caption.setName("perf-grab-caption");
        // SYNC: Ui.wrap(caption, CAPTION);
        // SYNC: grabLine.setName("perf-grab-line");
        // SYNC: top.add(Ui.left(Ui.pin(grabButton)));
        // SYNC: top.add(Ui.left(caption));
        // SYNC: top.add(Ui.left(grabLine));
        names.setName("perf-as");
        styles.setName("perf-style");
        styles.addItem("All styles");
        for (StreakBucket b : StreakBucket.values()) styles.addItem(b.label);
        rank.setName("perf-rank");
        rank.addItem("Any");
        for (int i = 0; i < 7; i++) rank.addItem(FightFilter.TIERS[i]);
        show.setName("perf-show");
        partials.setName("perf-partials");
        recent.setName("perf-recent");
        recent.setEditable(true);
        for (ItemSelectable c : new ItemSelectable[]{names, styles, rank, show, partials}) c.addItemListener(e -> filtered());
        recent.addActionListener(e -> recent());
        filters.add(field("Played as", names));
        filters.add(field("Style", styles));
        filters.add(field("Minimum opponent rank", rank));
        filters.add(field("Show", show));
        filters.add(field("Most recent fights", recent));
        top.add(Ui.left(filters));
        top.add(Ui.left(Ui.pin(partials)));
        filters("true".equals(config.getConfiguration(GROUP, FILTERS)));
        add(top, BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);
        list.setName("perf-fights");
        box.setName("perf-overall");
        Ui.cardStyle(box);
        more.setName("perf-more");
        more.addActionListener(e -> page(limit += PAGE));
        cards.add(Ui.left(list));
        cards.add(Ui.left(more));
        store.setListener(f -> SwingUtilities.invokeLater(() -> changed(f)));
        // SYNC: grab.setListener(() -> SwingUtilities.invokeLater(this::grabLine));
        // SYNC: addHierarchyListener(e -> {
            // SYNC: if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) grab.opened();
        // SYNC: });
        show(INDIVIDUAL);
        refresh();
    }

    /** The button: the filter controls the other way, the choice kept in RuneLite's settings. */
    private void toggled()
    {
        boolean shown = !filters.isVisible();
        config.setConfiguration(GROUP, FILTERS, String.valueOf(shown));
        filters(shown);
    }

    /** The filter controls shown or hidden, the button reading the other way; the filters apply either way. */
    private void filters(boolean shown)
    {
        filters.setVisible(shown);
        toggle.setText(shown ? "Hide filters" : "Show filters");
        revalidate();
        repaint();
    }

    /** A filter's label and its control: on one row when both fit at their preferred widths, the control taking the
     *  rest; else the label on its own line above the control. */
    private static JComponent field(String label, JComponent control)
    {
        JPanel p = new JPanel(new Field())
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.add(text(label, Font.PLAIN, null));
        p.add(control);
        return Ui.left(p);
    }

    /** Everything again from the store. */
    public void refresh()
    {
        merged = FightMerge.merge(store.local(), store.grabbed());
        names();
        apply();
        // SYNC: grabLine();
    }

    /** A store change: a new fight adds one card and its name if new; a fight's own change counts, draws, adds or
     *  removes that fight alone; anything else rebuilds. */
    private void changed(FightLog.Fight f)
    {
        int at = f == null ? -1 : index(merged, f);
        if (f == null || at < 0 && store.grabbed().stream().anyMatch(g -> FightMerge.same(f, g)))
        {
            refresh();
            return;
        }
        if (at >= 0)
        {
            updated(f);
            return;
        }
        merged.add(0, f);
        name(f);
        if (!filter.keeps(f)) return;
        if (shown.isEmpty()) list.removeAll();
        shown.add(0, f);
        count(f);
        list.add(card(f), 0);
        built++;
        if (filter.recent > 0 && shown.size() > filter.recent) uncount(shown.remove(shown.size() - 1));
        while (built > Math.min(limit, shown.size())) list.remove(--built);
        more.setVisible(shown.size() > built);
        box();
        list.revalidate();
        list.repaint();
    }

    /** A stored fight changed: shown and still kept, counted again and its card redrawn; no longer kept, it leaves;
     *  newly kept, it joins; else nothing. */
    private void updated(FightLog.Fight f)
    {
        int i = index(shown, f);
        boolean keep = filter.keeps(f);
        if (i >= 0 && keep)
        {
            uncount(f);
            count(f);
            if (i < built)
            {
                list.remove(i);
                list.add(card(f), i);
            }
        }
        else if (i >= 0) leave(i);
        else if (keep) join(f);
        else return;
        more.setVisible(shown.size() > built);
        box();
        list.revalidate();
        list.repaint();
    }

    /** {@code f} goes in at its place by end, unless Most recent N keeps only newer fights; a list then over N lets its
     *  oldest go. */
    private void join(FightLog.Fight f)
    {
        int p = place(f);
        if (filter.recent > 0 && p >= filter.recent) return;
        if (shown.isEmpty()) list.removeAll();
        shown.add(p, f);
        count(f);
        if (p <= built && p < limit)
        {
            list.add(card(f), p);
            built++;
        }
        if (filter.recent > 0 && shown.size() > filter.recent)
        {
            int last = shown.size() - 1;
            uncount(shown.remove(last));
            if (last < built) list.remove(--built);
        }
        while (built > Math.min(limit, shown.size())) list.remove(--built);
    }

    /** The shown fight at {@code i} goes; with Most recent N the next kept fight comes in at the end. */
    private void leave(int i)
    {
        uncount(shown.remove(i));
        if (i < built)
        {
            list.remove(i);
            built--;
        }
        FightLog.Fight next = filter.recent > 0 ? nextKept() : null;
        if (next != null)
        {
            shown.add(next);
            count(next);
        }
        if (shown.isEmpty()) note();
        while (built < Math.min(limit, shown.size())) list.add(card(shown.get(built++)));
    }

    /** {@code f}'s index among the shown fights: after each shown fight that comes before it in the merged list. */
    private int place(FightLog.Fight f)
    {
        Set<FightLog.Fight> in = Collections.newSetFromMap(new IdentityHashMap<>());
        in.addAll(shown);
        int p = 0;
        for (FightLog.Fight g : merged)
        {
            if (g == f) break;
            if (in.contains(g)) p++;
        }
        return p;
    }

    /** The first kept fight after the last shown one in the merged list; null for none. */
    private FightLog.Fight nextKept()
    {
        for (int k = shown.isEmpty() ? 0 : index(merged, shown.get(shown.size() - 1)) + 1; k < merged.size(); k++)
        {
            if (filter.keeps(merged.get(k))) return merged.get(k);
        }
        return null;
    }

    /** {@code f} into the totals as it is now, remembered as counted. */
    private void count(FightLog.Fight f)
    {
        FightLog.Fight c = new FightLog.Fight(f.endSec, f.opponent, f.result, f.world, f.mine.clone(), f.theirs.clone());
        c.change = f.change;
        counted.put(f, c);
        totals.add(c, 1);
    }

    /** {@code f} out of the totals, as it was counted. */
    private void uncount(FightLog.Fight f)
    {
        FightLog.Fight c = counted.remove(f);
        if (c != null) totals.add(c, -1);
    }

    private static int index(List<FightLog.Fight> fights, FightLog.Fight f)
    {
        for (int i = 0; i < fights.size(); i++) if (fights.get(i) == f) return i;
        return -1;
    }

    /** The Played as names: every name in the store, A-Z. */
    private void names()
    {
        known.clear();
        for (FightLog.Fight f : merged) if (!f.self.isEmpty()) known.putIfAbsent(NameUtils.canonicalKey(f.self), f.self);
        Object chosen = names.getSelectedItem();
        quiet = true;
        names.removeAllItems();
        names.addItem("All names");
        known.values().forEach(names::addItem);
        names.setSelectedItem(known.containsValue(chosen) ? chosen : "All names");
        quiet = false;
    }

    /** A new fight's name into Played as at its place, when it is not there yet; the choice stays. */
    private void name(FightLog.Fight f)
    {
        String key = f.self.isEmpty() ? null : NameUtils.canonicalKey(f.self);
        if (key == null || known.containsKey(key)) return;
        known.put(key, f.self);
        quiet = true;
        names.insertItemAt(f.self, known.headMap(key).size() + 1);
        quiet = false;
    }

    /** A filter changed: one rebuild after a short wait. */
    private void filtered()
    {
        if (quiet) return;
        if (debounce == null)
        {
            debounce = new Timer(0, e -> apply());
            debounce.setRepeats(false);
        }
        debounce.setInitialDelay(45 + merged.size() * 3 / 100);
        debounce.restart();
    }

    /** Most recent fights: a choice or a typed entry; one refused shows the last valid choice again. */
    private void recent()
    {
        if (quiet) return;
        if (filter.recent(String.valueOf(recent.getSelectedItem()))) filtered();
        quiet = true;
        recent.setSelectedItem(filter.recentText());
        quiet = false;
    }

    /** The filters on the merged list: both views from scratch. */
    private void apply()
    {
        filter.self = names.getSelectedIndex() > 0 ? (String) names.getSelectedItem() : null;
        filter.style = styles.getSelectedIndex() > 0 ? StreakBucket.values()[styles.getSelectedIndex() - 1].bucketKey : null;
        filter.min = rank.getSelectedIndex() - 1;
        filter.result = FightFilter.RESULTS[show.getSelectedIndex()];
        filter.partials = partials.isSelected();
        shown = filter.apply(merged);
        totals = new OverallStats();
        counted.clear();
        for (FightLog.Fight f : shown) count(f);
        list.removeAll();
        built = 0;
        if (shown.isEmpty()) note();
        page(limit = PAGE);
        box();
    }

    /** The list's note in place of every card: nothing stored, or nothing the filters keep. */
    private void note()
    {
        list.removeAll();
        built = 0;
        list.add(Ui.label("perf-note", merged.isEmpty() ? "No fights tracked yet" : "No fights match the filters",
            Font.ITALIC, Ui.smallFont().getSize2D(), Ui.MUTED));
    }

    /** The cards up to {@code to}, after those built. */
    private void page(int to)
    {
        while (built < Math.min(to, shown.size())) list.add(card(shown.get(built++)));
        more.setVisible(shown.size() > built);
        list.revalidate();
        list.repaint();
    }

    private void show(String key)
    {
        Ui.styleBucketBar(bar, key);
        view.removeAll();
        view.add(INDIVIDUAL.equals(key) ? cards : box, BorderLayout.NORTH);
        view.revalidate();
        view.repaint();
    }

    /** The Overall stats box from the running totals: its heading, the name played as and "Opponents" once, the wins,
     *  losses and ties, your net MMR Change, then the nine rows as on a card, in the card's colours. */
    private void box()
    {
        box.removeAll();
        box.add(vs(text("Overall stats", Font.BOLD, null), null,
            text(count(totals.fights) + (totals.fights == 1 ? " fight" : " fights"), Font.PLAIN, Ui.MUTED)));
        box.add(vs(text(filter.self == null ? "All names" : filter.self, Font.BOLD, null), null, text("Opponents", Font.BOLD, null)));
        box.add(vs(text("Wins: " + count(totals.wins), Font.PLAIN, null), text("Losses: " + count(totals.losses), Font.PLAIN, null),
            text("Ties: " + count(totals.ties), Font.PLAIN, null)));
        String net = totals.mmr();
        box.add(vs(text(net, Font.PLAIN, gain(net)), text(MMR, Font.PLAIN, Ui.MUTED), text("-", Font.PLAIN, null)));
        String[] mine = totals.mine(), theirs = totals.theirs(), a = totals.compared(0), b = totals.compared(1);
        for (int i = 0; i < 9; i++)
        {
            Color[] tone = tones(i, a[i], b[i]);
            JLabel left = text(mine[i], Font.PLAIN, tone[0]), right = text(theirs[i], Font.PLAIN, tone[1]);
            box.add(vs(left, rowLabel(i, left, right), right));
        }
        box.revalidate();
        box.repaint();
    }

    private static String count(int n)
    {
        return String.format(Locale.US, "%,d", n);
    }

    // SYNC: private void grabLine()
    // SYNC: {
        // SYNC: String t = Objects.toString(grab.text(), "");
        // SYNC: grabButton.setEnabled(grab.ready());
        // SYNC: Ui.wrap(grabLine, t);
        // SYNC: grabLine.setVisible(!t.isEmpty());
    // SYNC: }

    private static JComponent card(FightLog.Fight f)
    {
        StreakBucket b = StreakBucket.forKey(f.style);
        return card(f.result, when(f.endSec) + (f.marks.contains("s") ? SYNCED : ""), f.world > 0 ? "W" + f.world : null,
            b == null ? null : b.label, or(f.self), or(f.rank), f.opponent, or(f.oppRank), f.mine, f.theirs, mmr(f.change));
    }

    private static String or(String s)
    {
        return s == null || s.isEmpty() ? null : s;
    }

    /** A rating change as shown: signed to one decimal ("+7.9", "-9.7"), "0.0" for none, "-" when not known. */
    static String mmr(String change)
    {
        try
        {
            double v = Math.round(Double.parseDouble(change) * 10) / 10.0;
            return v == 0 ? "0.0" : String.format(Locale.ROOT, "%+.1f", v);
        }
        catch (RuntimeException e)
        {
            return "-";
        }
    }

    /** A rating change's colour: green for a gain, red for a loss, none for "0.0" or "-". */
    static Color gain(String mmr)
    {
        return mmr.startsWith("+") ? Ui.GREEN : mmr.length() > 1 && mmr.startsWith("-") ? WORSE : null;
    }

    /** A match row's rating change in the new system as {@link #change(double)} shows it; "-" for none. */
    public static String change(JsonObject m)
    {
        return change(JsonLenient.optDouble(m, "v3_change", Double.NaN));
    }

    /** A rating change as the popup and the match history show it: "+7.93", "-9.71", "0.00"; "-" for none (NaN). */
    public static String change(double v)
    {
        if (Double.isNaN(v)) return "-";
        double r = Math.round(v * 100) / 100.0;
        return r == 0 ? "0.00" : String.format(Locale.ROOT, "%+.2f", r);
    }

    static String when(long sec)
    {
        return WHEN.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()));
    }

    /** One fight: its date, world (when known) and result on the top line, each player's rank above their name in
     *  the rank's colour (yours left, theirs right; a name not known is left blank) with the style between them, the
     *  nine rows, then your rating change ({@code mmr}, no row for null); tinted for a win or a loss. */
    static JComponent card(String result, String date, String world, String style, String myName, String myRank,
        String theirName, String theirRank, String[] mine, String[] theirs, String mmr)
    {
        JPanel c = Ui.column();
        Ui.cardStyle(c);
        c.setName("perf-card");
        String res = result.toLowerCase(Locale.ROOT);
        c.setBackground("win".equals(res) ? WIN_BG : "loss".equals(res) ? LOSS_BG : Ui.CARD_BG);
        String r = res.isEmpty() ? "" : Character.toUpperCase(res.charAt(0)) + res.substring(1);
        c.add(vs(text(date, Font.PLAIN, Ui.MUTED), world == null ? null : text(world, Font.PLAIN, Ui.MUTED),
            text(r, Font.BOLD, "win".equals(res) ? Ui.GREEN : "loss".equals(res) ? WORSE : Ui.MUTED)));
        if (myRank != null || theirRank != null || style != null)
        {
            c.add(vs(text(myRank, Font.PLAIN, tint(myRank)), style == null ? null : text(style, Font.PLAIN, null),
                text(theirRank, Font.PLAIN, tint(theirRank))));
        }
        JLabel them = text(theirName, Font.BOLD, tint(theirRank));
        them.setName("perf-opponent");
        c.add(vs(text(myName, Font.BOLD, tint(myRank)), null, them));
        for (int i = 0; i < 9; i++)
        {
            Color[] tone = tones(i, mine[i], theirs[i]);
            JLabel left = text(shown(i, mine[i]), Font.PLAIN, tone[0]),
                right = text(shownTheirs(i, theirs[i]), Font.PLAIN, tone[1]);
            c.add(vs(left, rowLabel(i, left, right), right));
        }
        if (mmr != null) c.add(vs(text(mmr, Font.PLAIN, gain(mmr)), text(MMR, Font.PLAIN, Ui.MUTED), text("-", Font.PLAIN, null)));
        return Ui.pin(c);
    }

    /** Row {@code i}'s name between {@code left} and {@code right}: in full when the three fit one line, else short
     *  with the full name on hover. */
    private static JLabel rowLabel(int i, JLabel left, JLabel right)
    {
        JLabel full = text(LABELS[i], Font.PLAIN, Ui.MUTED);
        int w = left.getPreferredSize().width + full.getPreferredSize().width + right.getPreferredSize().width;
        if (w + 2 * GAP <= WIDTH) return full;
        JLabel brief = text(SHORT[i], Font.PLAIN, Ui.MUTED);
        brief.setToolTipText(LABELS[i]);
        return brief;
    }

    private static JLabel text(String s, int style, Color fg)
    {
        return Ui.label(null, s == null ? "" : s, style, Ui.smallFont().getSize2D(), fg);
    }

    private static Color tint(String rank)
    {
        return rank == null ? null : RankUtils.getRankColor(rank);
    }

    /** {@code left}, {@code mid} and {@code right} on one line when they fit the card; else {@code mid} above the other
     *  two, which share a line when they fit and take one each when not. */
    private static JComponent vs(JLabel left, JLabel mid, JLabel right)
    {
        int l = left.getPreferredSize().width, r = right.getPreferredSize().width;
        int m = mid == null ? 0 : mid.getPreferredSize().width;
        if (l + m + r + 2 * GAP <= WIDTH) return line(left, mid, right);
        JPanel lines = Ui.column();
        lines.setOpaque(false);
        if (mid != null) lines.add(line(null, mid, null));
        if (l + r + GAP <= WIDTH)
        {
            lines.add(line(left, null, right));
        }
        else
        {
            lines.add(line(left, null, null));
            lines.add(line(null, null, right));
        }
        return Ui.left(lines);
    }

    private static JPanel line(JLabel left, JLabel mid, JLabel right)
    {
        JPanel p = new JPanel(new Line());
        p.setOpaque(false);
        p.add(left == null ? new JLabel() : left);
        p.add(mid == null ? new JLabel() : mid);
        if (right != null) right.setHorizontalAlignment(SwingConstants.RIGHT);
        p.add(right == null ? new JLabel() : right);
        return Ui.left(p);
    }

    /** One line of three labels: the first at the left edge, the last at the right edge, the middle one centred or
     *  moved just enough to clear a long value beside it. */
    private static final class Line implements LayoutManager
    {
        @Override
        public void addLayoutComponent(String name, Component c)
        {
        }

        @Override
        public void removeLayoutComponent(Component c)
        {
        }

        @Override
        public Dimension preferredLayoutSize(Container p)
        {
            int w = 2 * GAP, h = 0;
            for (Component c : p.getComponents())
            {
                Dimension d = c.getPreferredSize();
                w += d.width;
                h = Math.max(h, d.height);
            }
            return new Dimension(w, h);
        }

        @Override
        public Dimension minimumLayoutSize(Container p)
        {
            return preferredLayoutSize(p);
        }

        @Override
        public void layoutContainer(Container p)
        {
            int w = p.getWidth(), h = p.getHeight();
            Component left = p.getComponent(0), mid = p.getComponent(1), right = p.getComponent(2);
            int l = left.getPreferredSize().width, m = mid.getPreferredSize().width, r = right.getPreferredSize().width;
            left.setBounds(0, 0, l, h);
            right.setBounds(w - r, 0, r, h);
            mid.setBounds(Math.max(l + GAP, Math.min((w - m) / 2, w - r - GAP - m)), 0, m, h);
        }
    }

    /** A filter's row: the label at its own width and the control taking the rest, or, when the two do not fit side
     *  by side, the label above the control. */
    private static final class Field implements LayoutManager
    {
        @Override
        public void addLayoutComponent(String name, Component c)
        {
        }

        @Override
        public void removeLayoutComponent(Component c)
        {
        }

        /** Decided at the panel's narrowest width, never the row's current one, so a layout pass cannot flip it. */
        private static boolean stacked(Container p)
        {
            return p.getComponent(0).getPreferredSize().width + 6 + p.getComponent(1).getPreferredSize().width > PANEL;
        }

        @Override
        public Dimension preferredLayoutSize(Container p)
        {
            Dimension l = p.getComponent(0).getPreferredSize(), c = p.getComponent(1).getPreferredSize();
            return stacked(p) ? new Dimension(Math.min(PANEL, Math.max(l.width, c.width)), l.height + 2 + c.height)
                : new Dimension(l.width + 6 + c.width, Math.max(l.height, c.height));
        }

        @Override
        public Dimension minimumLayoutSize(Container p)
        {
            return preferredLayoutSize(p);
        }

        @Override
        public void layoutContainer(Container p)
        {
            Component label = p.getComponent(0), control = p.getComponent(1);
            Dimension l = label.getPreferredSize(), c = control.getPreferredSize();
            int w = p.getWidth(), h = Math.max(l.height, c.height);
            if (stacked(p))
            {
                label.setBounds(0, 0, l.width, l.height);
                control.setBounds(0, l.height + 2, w, c.height);
            }
            else
            {
                label.setBounds(0, (h - l.height) / 2, l.width, l.height);
                control.setBounds(l.width + 6, (h - c.height) / 2, w - l.width - 6, c.height);
            }
        }
    }

    /** Row {@code i}'s two value colours: green for the better side, red for the worse; none when the row is not
     *  compared, the two are equal or either cannot be read. */
    static Color[] tones(int i, String mine, String theirs)
    {
        double a = score(i, mine), b = score(i, theirs);
        if (BETTER[i] == 0 || Double.isNaN(a) || Double.isNaN(b) || a == b) return new Color[2];
        return (a > b) == (BETTER[i] > 0) ? new Color[]{Ui.GREEN, WORSE} : new Color[]{WORSE, Ui.GREEN};
    }

    /** Row {@code i}'s value as compared: expected damage and damage as numbers, magic hits as the luck % shown,
     *  hits on robes as a share, KO chances as their total %; NaN when it cannot be read. */
    static double score(int i, String raw)
    {
        String[] p = raw == null ? new String[0] : raw.split("/");
        try
        {
            if ((i == 1 || i == 2) && p.length == 1) return Double.parseDouble(p[0]);
            if (i == 3 && p.length == 3 && Double.parseDouble(p[2]) > 0)
            {
                return Math.round(Integer.parseInt(p[0]) / Double.parseDouble(p[2]) * 100);
            }
            if (i == 6 && p.length == 2 && Double.parseDouble(p[1]) > 0)
            {
                return Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
            }
            if (i == 7 && p.length == 2) return Double.parseDouble(p[1]);
        }
        catch (NumberFormatException e)
        {
            return Double.NaN;
        }
        return Double.NaN;
    }

    /** The opponent's stored value as shown: as {@link #shown}, the offensive prayer the client cannot see as the
     *  estimate. */
    static String shownTheirs(int i, String raw)
    {
        return i == 4 && "-".equals(raw) ? EST : shown(i, raw);
    }

    /** A stored value as shown: magic hits with their luck, KO chances and ghost barrages with their total. */
    static String shown(int i, String raw)
    {
        String[] p = raw.split("/");
        try
        {
            if (i == 3 && p.length == 3)
            {
                double expected = Double.parseDouble(p[2]);
                return p[0] + "/" + p[1] + (expected > 0 ? " (" + Math.round(Integer.parseInt(p[0]) / expected * 100) + "%)" : "");
            }
            if (i == 7 && p.length == 2) return p[0] + " (" + p[1] + "%)";
            if (i == 8 && p.length == 2) return p[0] + " (" + p[1] + ")";
        }
        catch (NumberFormatException e)
        {
            return raw;
        }
        return raw;
    }
}
