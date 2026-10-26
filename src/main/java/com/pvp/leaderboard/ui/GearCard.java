package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.tournament.*;
import java.awt.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.*;
import java.util.List;
import static com.pvp.leaderboard.ui.Ui.*;

public class GearCard extends CapPanel
{
    public static final String NAME = "tournament-gear-card";

    private static final float LINE_PT = TourneyPanel.BODY_PT;
    static final Color RED = new Color(0xff6b6b);
    /** One item slot: a 36 x 32 icon inside a 2 px border. */
    private static final Dimension CELL = new Dimension(40, 36);
    private static final int GAP = 2;
    private static final int INV_W = 4 * CELL.width + 3 * GAP;
    /** The equipment shape, 3 wide and 5 high: each cell's worn slot, null for an empty corner. */
    private static final String[] DOLL = {null, "head", null, "cape", "neck", "ammo", "weapon", "body", "shield", null, "legs", null,
        "hands", "feet", "ring"};
    private static final Color SLOT_BG = new Color(0x3a3a3a);
    private static final Border PLAIN = BorderFactory.createLineBorder(new Color(0x4a4a4a), 2);
    private static final Border OUTLINE = BorderFactory.createLineBorder(RED, 2);
    /** The "Missing items" key: a small red box. */
    private static final Icon KEY = new Icon()
    {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            g.setColor(RED);
            g.drawRect(x, y + 1, 10, 10);
            g.drawRect(x + 1, y + 2, 8, 8);
        }

        @Override
        public int getIconWidth()
        {
            return 14;
        }

        @Override
        public int getIconHeight()
        {
            return 12;
        }
    };

    public interface Actions
    {
        void findItem(int itemId, List<Integer> altIds, String name);

        void showMissing(List<Integer> itemIds);

        void openPanel();
    }

    public interface Icons
    {
        void apply(JLabel label, int itemId, int qty, boolean stackable);
    }

    private final Icons icons;
    private final Actions actions;
    private final BooleanSupplier autoOpenEnabled;
    private String shape;
    private JLabel countdown;
    private JLabel okLine;
    private String lastAutoOpen;
    private GearReporter.View current = GearReporter.View.EMPTY;

    public GearCard(Icons icons, Actions actions, BooleanSupplier autoOpenEnabled)
    {
        this.icons = icons;
        this.actions = actions;
        this.autoOpenEnabled = autoOpenEnabled;
        setName(NAME);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        cardStyle(this);
        setVisible(false);
    }

    public void render(GearReporter.View v)
    {
        current = v == null ? GearReporter.View.EMPTY : v;
        if (current.event == null || current.inCombat || current.event.arena && !current.atArena)
        {
            setVisible(false);
            return;
        }
        maybeOpen(current);
        String nextShape = shapeOf(current);
        if (!nextShape.equals(shape))
        {
            shape = nextShape;
            rebuild(current);
        }
        refreshClock(current);
        setVisible(true);
    }

    private void maybeOpen(GearReporter.View v)
    {
        String key = autoOpenKey(v);
        if (key == null || key.equals(lastAutoOpen) || !GearKit.safe(autoOpenEnabled)) return;
        lastAutoOpen = key;
        actions.openPanel();
    }

    static String autoOpenKey(GearReporter.View v)
    {
        if (v == null || v.event == null || v.inCombat || v.verifiedOk()) return null;
        if (v.event.prepOpen(v.nowMs / 1000L)) return "prep|" + v.event.tournamentId + "|" + v.event.checkUntilS;
        if (v.event.running) return "start|" + v.event.tournamentId;
        return null;
    }

    private static String shapeOf(GearReporter.View v)
    {
        var sb = new StringBuilder(v.event.tournamentId).append('|').append(v.event.set.digest).append('|').append(v.event.arena)
            .append('|').append(v.verifiedOk()).append('|').append(v.kit == null);
        GearDiff d = v.diff;
        if (d != null)
        {
            sb.append('|').append(d.buildOk).append(d.spellbookOk).append(d.missing).append(d.extra);
        }
        return sb.toString();
    }

    private void rebuild(GearReporter.View v)
    {
        removeAll();
        countdown = null;
        okLine = null;
        GearSet set = v.event.set;
        if (v.verifiedOk())
        {
            okLine = label("gear-card-ok", "", GREEN, Font.BOLD, LINE_PT);
            add(okLine);
            revalidate();
            repaint();
            return;
        }
        add(label("gear-card-title", "Required kit: " + set.name, Color.WHITE, Font.BOLD, TourneyPanel.HEADER_PT));
        countdown = label("gear-card-countdown", " ", AMBER, Font.BOLD, LINE_PT);
        add(countdown);
        GearDiff d = v.diff;
        if (d != null && !d.buildOk) add(label("gear-card-build", "Pick " + set.buildLabel + " on the duel screen's Stats tab", RED));
        if (d != null && set.spellbook != null && !d.spellbookOk)
        {
            String book = set.bookLabel == null ? set.spellbook : set.bookLabel;
            add(label("gear-card-spellbook", "Spellbook: " + book + " — switch it in the kit tab's drop-down", RED));
        }
        add(vgap(4));
        add(worn(set, d));
        add(vgap(GAP));
        add(inventory(set, d));
        if (d != null && !d.missing.isEmpty())
        {
            add(vgap(4));
            JLabel key = label("gear-card-missing", "Missing items", RED);
            key.setIcon(KEY);
            add(key);
        }
        if (d != null && !d.extra.isEmpty())
        {
            add(vgap(4));
            add(label("gear-card-extras", "Extra items to remove", RED));
            add(extras(d.extra));
        }
        finish();
    }

    /** The worn items in the equipment shape, centred over the inventory. */
    private JComponent worn(GearSet set, GearDiff d)
    {
        JPanel grid = grid("gear-card-worn", 5, 3);
        for (String slot : DOLL)
        {
            GearItem item = null;
            for (GearItem i : set.items) if (slot != null && slot.equals(i.slot)) item = i;
            grid.add(slot == null ? new JLabel() : slot("gear-card-worn-" + slot, item, 0, 1, d));
        }
        var wrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrap.setOpaque(false);
        wrap.add(grid);
        Dimension size = new Dimension(INV_W, grid.getPreferredSize().height);
        wrap.setPreferredSize(size);
        wrap.setMaximumSize(size);
        return left(wrap);
    }

    private JComponent inventory(GearSet set, GearDiff d)
    {
        GearItem[] at = new GearItem[GearItem.INVENTORY_SLOTS];
        int[] k = new int[at.length];
        int[] n = new int[at.length];
        for (Map.Entry<GearItem, List<Integer>> e : slots(set).entrySet())
        {
            List<Integer> s = e.getValue();
            for (int i = 0; i < s.size(); i++)
            {
                at[s.get(i)] = e.getKey();
                k[s.get(i)] = i;
                n[s.get(i)] = s.size();
            }
        }
        JPanel grid = grid("gear-card-inv", 7, 4);
        for (int x = 0; x < at.length; x++) grid.add(slot("gear-card-inv-" + x, at[x], k[x], n[x], d));
        return fixed(grid);
    }

    /** Each carried row's inventory slots: the set's own when every carried row has them and none is shared, else the
     *  set's order, one slot per unit and one per stack. */
    private static Map<GearItem, List<Integer>> slots(GearSet set)
    {
        Set<Integer> used = new HashSet<>();
        boolean own = true;
        for (GearItem i : set.items)
        {
            if (i.isWorn()) continue;
            if (i.inv.isEmpty()) own = false;
            for (int x : i.inv) own &= used.add(x);
        }
        Map<GearItem, List<Integer>> out = new LinkedHashMap<>();
        int next = 0;
        for (GearItem i : set.items)
        {
            if (i.isWorn()) continue;
            List<Integer> s = own ? i.inv : new ArrayList<>();
            for (int u = i.stackable ? 1 : i.qty; !own && u > 0 && next < GearItem.INVENTORY_SLOTS; u--) s.add(next++);
            out.put(i, s);
        }
        return out;
    }

    /** One slot of the kit: {@code item}'s icon (a stack with its count), its name and have / need on hover, and a red
     *  outline when it is one of the row's last {@code missing} of {@code n} icons; a click on it finds the item. */
    private JLabel slot(String name, GearItem item, int k, int n, GearDiff d)
    {
        JLabel l = box(name);
        if (item == null) return l;
        icons.apply(l, item.id, item.stackable ? item.qty : 1, item.stackable);
        int have = item.qty;
        int need = item.qty;
        if (d != null)
        {
            for (GearDiff.Row r : d.missing) if (r.itemId == item.id) have = r.have;
            for (GearDiff.Row r : d.extra) if (r.need > 0 && r.itemId == item.id) have = r.have;
        }
        l.setToolTipText(d == null ? item.name : item.name + " " + have + " / " + need);
        int missing = need - have;
        if (missing > 0 && (n == 1 || k >= n - missing))
        {
            l.setBorder(OUTLINE);
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            onClick(l, () -> actions.findItem(item.id, item.altIds, item.name));
        }
        return l;
    }

    /** The extras, outlined, each showing how many to remove. */
    private JComponent extras(List<GearDiff.Row> rows)
    {
        JPanel grid = grid("gear-card-extra", 0, 4);
        for (GearDiff.Row r : rows)
        {
            int n = r.need == 0 ? r.have : r.overBy();
            JLabel l = box("gear-card-extra-" + r.itemId);
            l.setBorder(OUTLINE);
            icons.apply(l, r.itemId, n, r.stackable || n > 1);
            l.setToolTipText(r.name + ": remove " + n);
            grid.add(l);
        }
        return fixed(grid);
    }

    private static JLabel box(String name)
    {
        var l = new JLabel();
        l.setName(name);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setPreferredSize(CELL);
        l.setOpaque(true);
        l.setBackground(SLOT_BG);
        l.setBorder(PLAIN);
        return l;
    }

    private static JPanel grid(String name, int rows, int cols)
    {
        var p = new JPanel(new GridLayout(rows, cols, GAP, GAP));
        p.setName(name);
        p.setOpaque(false);
        return p;
    }

    /** {@code c} at its preferred size, aligned left. */
    private static JComponent fixed(JComponent c)
    {
        c.setMaximumSize(c.getPreferredSize());
        return left(c);
    }

    private void finish()
    {
        GearReporter.View v = current;
        if (!v.event.arena && v.diff != null && !v.diff.missing.isEmpty())
        {
            var buttons = new JPanel();
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
            buttons.setOpaque(false);
            left(buttons);
            buttons.add(TourneyPanel.tabButton("Show missing in bank", "gear-card-bank", () -> actions.showMissing(current.missingIds())));
            add(vgap(4));
            add(buttons);
        }
        revalidate();
        repaint();
    }

    private void refreshClock(GearReporter.View v)
    {
        GearTracker.GearEvent e = v.event;
        long nowS = v.nowMs / 1000L;
        boolean prep = e.prepOpen(nowS);
        String left = prep ? mmss(e.checkUntilS - nowS) : null;
        if (countdown != null)
        {
            String text = prep ? TournamentInfoCard.wrapEscaped(countdown.getFont(), TournamentInfoCard.TEXT_WIDTH,
                (e.checkRound > 0 ? "Round " + e.checkRound : "The round") + " starts in " + left + " — your kit must match") : " ";
            if (!text.equals(countdown.getText())) countdown.setText(text);
            countdown.setVisible(prep);
        }
        if (okLine != null)
        {
            String clock = prep ? " · " + (e.checkRound > 0 ? "round " + e.checkRound : "the round") + " starts in " + left : "";
            String text = TournamentInfoCard.wrapEscaped(okLine.getFont(), TournamentInfoCard.TEXT_WIDTH, "Kit matches " + escape(e.set.name) + clock);
            if (!text.equals(okLine.getText())) okLine.setText(text);
        }
    }

    /** A bold line of text in {@code fg}, wrapped to the card's width. */
    private static JLabel label(String name, String text, Color fg)
    {
        return label(name, text, fg, Font.BOLD, LINE_PT);
    }

    /** A line of text, wrapped to the card's width. */
    private static JLabel label(String name, String text, Color fg, int style, float pt)
    {
        var l = new JLabel();
        l.setName(name);
        l.setForeground(fg);
        wrap(font(l, style, pt), text);
        return l;
    }
}
