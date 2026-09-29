package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.tournament.GearDiff;
import com.pvp.leaderboard.tournament.GearEventTracker;
import com.pvp.leaderboard.tournament.GearItem;
import com.pvp.leaderboard.tournament.GearKit;
import com.pvp.leaderboard.tournament.GearSet;
import com.pvp.leaderboard.tournament.GearStatusReporter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class TournamentGearCard extends JPanel
{
    public static final String NAME = "tournament-gear-card";
    static final String NEVER_READ_HINT = "Open your kit tab on the duel screen or the Supplies chest so the plugin can read your kit.";
    static final String STALE_HINT = "Your kit changed since the plugin last read it (a Load-outs copy?) — open your kit tab so it can re-read it.";
    static final String POUCH_HINT = "Open your kit's rune pouch once so the plugin can read its runes.";
    static final String AWAY_HINT = "Fights are PvP Arena Unranked Duels — head to the PvP Arena.";
    static final String PLACEHOLDER_BANNER = "Placeholder kit — the host's final kit may differ";
    static final String COPIED = "Copied — paste it into gear_sets_catalog.json";

    private static final float LINE_PT = TournamentsPanel.BODY_PT;
    private static final float ITEM_PT = 14f;
    /** An item icon's width plus its gap to the text. */
    private static final int ICON_ROOM_PX = 40;
    private static final Color BG = new Color(0x2b, 0x2b, 0x2b);
    private static final Color DIVIDER = new Color(0x40, 0x40, 0x40);
    private static final Color GREEN = new Color(0x3e, 0xcf, 0x8e);
    private static final Color RED = new Color(0xff, 0x6b, 0x6b);
    private static final Color AMBER = new Color(0xff, 0xb3, 0x47);
    private static final Color MUTED = new Color(0x9a, 0x9a, 0x9a);
    private static final Color INFO = new Color(0xdd, 0xdd, 0xdd);

    public interface Actions
    {
        void findItem(int itemId, List<Integer> altIds, String name);

        void copySetup(GearKit kit);

        void showMissingInBank(List<Integer> itemIds);

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
    private GearStatusReporter.View current = GearStatusReporter.View.EMPTY;

    public TournamentGearCard(Icons icons, Actions actions, BooleanSupplier autoOpenEnabled)
    {
        this.icons = icons == null ? (l, id, q, s) -> { } : icons;
        this.actions = actions;
        this.autoOpenEnabled = autoOpenEnabled == null ? () -> false : autoOpenEnabled;
        setName(NAME);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BG);
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(new MatteBorder(0, 0, 1, 0, DIVIDER), BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        setAlignmentX(LEFT_ALIGNMENT);
        setVisible(false);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    public void render(GearStatusReporter.View v)
    {
        current = v == null ? GearStatusReporter.View.EMPTY : v;
        maybeAutoOpen(current);
        if (current.event == null || current.inCombat)
        {
            setVisible(false);
            return;
        }
        String nextShape = shapeOf(current);
        if (!nextShape.equals(shape))
        {
            shape = nextShape;
            rebuild(current);
        }
        refreshClock(current);
        setVisible(true);
    }

    private void maybeAutoOpen(GearStatusReporter.View v)
    {
        String key = autoOpenKey(v);
        if (key == null || key.equals(lastAutoOpen)) return;
        boolean enabled;
        try
        {
            enabled = autoOpenEnabled.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            enabled = false;
        }
        if (!enabled) return;
        lastAutoOpen = key;
        if (actions != null) actions.openPanel();
    }

    static String autoOpenKey(GearStatusReporter.View v)
    {
        if (v == null || v.event == null || v.inCombat || v.verifiedOk()) return null;
        if (v.event.prepOpen(v.nowMs / 1000L)) return "prep|" + v.event.tournamentId + "|" + v.event.checkUntilEpochS;
        if (v.event.running) return "start|" + v.event.tournamentId;
        return null;
    }

    private static String shapeOf(GearStatusReporter.View v)
    {
        StringBuilder sb = new StringBuilder(v.event.tournamentId).append('|').append(v.event.set.digest).append('|').append(v.event.arena)
            .append('|').append(v.atArena).append('|').append(v.verifiedOk()).append('|').append(v.stale()).append('|').append(v.kit == null);
        GearDiff d = v.diff;
        if (d != null)
        {
            sb.append('|').append(d.buildOk).append(d.spellbookOk).append(d.pouchUnknown).append(d.missing).append(d.extra);
        }
        return sb.toString();
    }

    private void rebuild(GearStatusReporter.View v)
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
        add(label("gear-card-title", "Required kit: " + escape(set.name), Color.WHITE, Font.BOLD, TournamentsPanel.HEADER_PT));
        if (set.placeholder) add(label("gear-card-placeholder", PLACEHOLDER_BANNER, AMBER, Font.PLAIN, LINE_PT));
        countdown = label("gear-card-countdown", " ", AMBER, Font.BOLD, LINE_PT);
        add(countdown);
        if (v.event.arena && !v.atArena) add(label("gear-card-where", AWAY_HINT, MUTED, Font.PLAIN, LINE_PT));
        if (v.kit == null)
        {
            add(label("gear-card-hint", NEVER_READ_HINT, AMBER, Font.PLAIN, LINE_PT));
            for (GearItem item : set.items)
            {
                JLabel row = itemLabel("gear-card-need-" + item.id, item.qty + " × " + escape(item.name), "", INFO);
                icons.apply(row, item.id, item.qty, item.stackable);
                add(row);
            }
            finish();
            return;
        }
        if (v.stale()) add(label("gear-card-hint", STALE_HINT, AMBER, Font.PLAIN, LINE_PT));
        GearDiff d = v.diff;
        add(d.buildOk
            ? label("gear-card-build", "Build: " + escape(set.buildLabel) + " ✓", GREEN, Font.PLAIN, LINE_PT)
            : label("gear-card-build", "Pick " + escape(set.buildLabel) + " on the duel screen's Stats tab", RED, Font.BOLD, LINE_PT));
        for (GearDiff.Row r : d.missing) add(missingRow(r));
        for (GearDiff.Row r : d.extra)
        {
            JLabel row = itemLabel("gear-card-extra-" + r.itemId, "remove: " + (r.need == 0 ? r.have : r.overBy()) + " × " + escape(r.name), "", AMBER);
            icons.apply(row, r.itemId, Math.max(1, r.need == 0 ? r.have : r.overBy()), r.stackable);
            add(row);
        }
        if (set.spellbook != null)
        {
            String book = set.spellbookLabel == null ? set.spellbook : set.spellbookLabel;
            add(d.spellbookOk
                ? label("gear-card-spellbook", "Spellbook: " + escape(book) + " ✓", GREEN, Font.PLAIN, LINE_PT)
                : label("gear-card-spellbook", "Spellbook: " + escape(book) + " — switch it in the kit tab's drop-down", RED, Font.BOLD, LINE_PT));
        }
        if (d.pouchUnknown) add(label("gear-card-pouch", POUCH_HINT, AMBER, Font.PLAIN, LINE_PT));
        finish();
    }

    private JLabel missingRow(GearDiff.Row r)
    {
        JLabel row = itemLabel("gear-card-missing-" + r.itemId, escape(r.name), " <font color='#ff6b6b'>" + r.have + " / " + r.need + "</font>", INFO);
        icons.apply(row, r.itemId, r.need, r.stackable);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setToolTipText("Find it in the kit's supplies list (or the bank)");
        final int id = r.itemId;
        final List<Integer> altIds = r.altIds;
        final String name = r.name;
        row.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (actions != null) actions.findItem(id, altIds, name);
            }
        });
        return row;
    }

    private void finish()
    {
        GearStatusReporter.View v = current;
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        if (v.kit != null)
        {
            JButton copy = TournamentsPanel.tabButton("Copy my setup");
            copy.setName("gear-card-copy");
            copy.setToolTipText("Copy the kit the plugin read as a gear-set catalog entry");
            copy.addActionListener(e -> onCopy());
            buttons.add(copy);
            buttons.add(Box.createVerticalStrut(4));
        }
        if (!v.event.arena && v.diff != null && !v.diff.missing.isEmpty())
        {
            JButton bank = TournamentsPanel.tabButton("Show missing in bank");
            bank.setName("gear-card-bank");
            bank.addActionListener(e -> { if (actions != null) actions.showMissingInBank(current.missingIds()); });
            buttons.add(bank);
        }
        if (buttons.getComponentCount() > 0)
        {
            add(Box.createVerticalStrut(4));
            add(buttons);
        }
        revalidate();
        repaint();
    }

    private void onCopy()
    {
        GearKit kit = current.kit;
        if (kit == null || actions == null) return;
        actions.copySetup(kit);
        JLabel done = label("gear-card-copied", COPIED, GREEN, Font.PLAIN, LINE_PT);
        for (java.awt.Component c : getComponents())
        {
            if ("gear-card-copied".equals(c.getName())) remove(c);
        }
        add(done);
        revalidate();
        repaint();
    }

    private void refreshClock(GearStatusReporter.View v)
    {
        GearEventTracker.GearEvent e = v.event;
        long nowS = v.nowMs / 1000L;
        boolean prep = e.prepOpen(nowS);
        String left = prep ? TournamentsPanel.mmss(e.checkUntilEpochS - nowS) : null;
        if (countdown != null)
        {
            String text = prep ? TournamentInfoCard.wrapEscaped(countdown.getFont(), TournamentInfoCard.TEXT_WIDTH_PX,
                (e.checkRound > 0 ? "Round " + e.checkRound : "The round") + " starts in " + left + " — your kit must match") : " ";
            if (!text.equals(countdown.getText())) countdown.setText(text);
            countdown.setVisible(prep);
        }
        if (okLine != null)
        {
            String clock = prep ? " · " + (e.checkRound > 0 ? "round " + e.checkRound : "the round") + " starts in " + left : "";
            String text = TournamentInfoCard.wrapEscaped(okLine.getFont(), TournamentInfoCard.TEXT_WIDTH_PX, "Kit ✓ matches " + escape(e.set.name) + clock);
            if (!text.equals(okLine.getText())) okLine.setText(text);
        }
    }

    /** A line of escaped text, wrapped to the card's width. */
    private static JLabel label(String name, String html, Color fg, int style, float pt)
    {
        JLabel l = new JLabel();
        l.setName(name);
        l.setForeground(fg);
        l.setFont(l.getFont().deriveFont(style, pt));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setText(TournamentInfoCard.wrapEscaped(l.getFont(), TournamentInfoCard.TEXT_WIDTH_PX, html));
        return l;
    }

    /** An item row: escaped text wrapped to the width left beside its item
     *  icon, then {@code suffix} markup (may be empty) on the last line. */
    private static JLabel itemLabel(String name, String escaped, String suffix, Color fg)
    {
        JLabel l = new JLabel();
        l.setName(name);
        l.setForeground(fg);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, ITEM_PT));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setText("<html>" + TournamentInfoCard.wrapInner(l.getFont(), TournamentInfoCard.TEXT_WIDTH_PX - ICON_ROOM_PX, escaped) + suffix + "</html>");
        return l;
    }

    private static String escape(String s)
    {
        return TournamentsPanel.escape(s);
    }
}
