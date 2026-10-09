package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.tournament.*;
import java.awt.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import java.util.List;
import static com.pvp.leaderboard.ui.Ui.*;

public class GearCard extends CapPanel
{
    public static final String NAME = "tournament-gear-card";
    static final String NEVER_READ = "Open your kit tab on the duel screen or the Supplies chest so the plugin can read your kit.";
    static final String STALE_HINT = "Your kit changed since the plugin last read it (a Load-outs copy?) — open your kit tab so it can re-read it.";
    static final String POUCH_HINT = "Open your kit's rune pouch once so the plugin can read its runes.";
    static final String AWAY_HINT = "Fights are PvP Arena Unranked Duels — head to the PvP Arena.";
    static final String STUB_BANNER = "Placeholder kit — the host's final kit may differ";
    static final String COPIED = "Copied — paste it into gear_sets_catalog.json";

    private static final float LINE_PT = TourneyPanel.BODY_PT;
    private static final float ITEM_PT = 14f;
    /** An item icon's width plus its gap to the text. */
    private static final int ICON_ROOM_PX = 40;
    static final Color RED = new Color(0xff6b6b);

    public interface Actions
    {
        void findItem(int itemId, List<Integer> altIds, String name);

        void copySetup(GearKit kit);

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
        maybeOpen(current);
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
            .append('|').append(v.atArena).append('|').append(v.verifiedOk()).append('|').append(v.stale()).append('|').append(v.kit == null);
        GearDiff d = v.diff;
        if (d != null)
        {
            sb.append('|').append(d.buildOk).append(d.spellbookOk).append(d.pouchUnknown).append(d.missing).append(d.extra);
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
        if (set.placeholder) add(label("gear-card-placeholder", STUB_BANNER, AMBER));
        countdown = label("gear-card-countdown", " ", AMBER, Font.BOLD, LINE_PT);
        add(countdown);
        if (v.event.arena && !v.atArena) add(label("gear-card-where", AWAY_HINT, MUTED));
        if (v.kit == null)
        {
            add(label("gear-card-hint", NEVER_READ, AMBER));
            for (GearItem item : set.items)
            {
                JLabel row = itemLabel("gear-card-need-" + item.id, item.qty + " × " + escape(item.name), "", INFO);
                icons.apply(row, item.id, item.qty, item.stackable);
                add(row);
            }
            finish();
            return;
        }
        if (v.stale()) add(label("gear-card-hint", STALE_HINT, AMBER));
        GearDiff d = v.diff;
        add(d.buildOk
            ? label("gear-card-build", "Build: " + set.buildLabel, GREEN)
            : label("gear-card-build", "Pick " + set.buildLabel + " on the duel screen's Stats tab", RED, Font.BOLD, LINE_PT));
        for (GearDiff.Row r : d.missing) add(missingRow(r));
        for (GearDiff.Row r : d.extra)
        {
            JLabel row = itemLabel("gear-card-extra-" + r.itemId, "remove: " + (r.need == 0 ? r.have : r.overBy()) + " × " + escape(r.name), "", AMBER);
            icons.apply(row, r.itemId, Math.max(1, r.need == 0 ? r.have : r.overBy()), r.stackable);
            add(row);
        }
        if (set.spellbook != null)
        {
            String book = set.bookLabel == null ? set.spellbook : set.bookLabel;
            add(d.spellbookOk
                ? label("gear-card-spellbook", "Spellbook: " + book, GREEN)
                : label("gear-card-spellbook", "Spellbook: " + book + " — switch it in the kit tab's drop-down", RED, Font.BOLD, LINE_PT));
        }
        if (d.pouchUnknown) add(label("gear-card-pouch", POUCH_HINT, AMBER));
        finish();
    }

    private JLabel missingRow(GearDiff.Row r)
    {
        JLabel row = itemLabel("gear-card-missing-" + r.itemId, escape(r.name), " <font color='#ff6b6b'>" + r.have + " / " + r.need + "</font>", INFO);
        icons.apply(row, r.itemId, r.need, r.stackable);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setToolTipText("Find it in the kit's supplies list (or the bank)");
        onClick(row, () -> actions.findItem(r.itemId, r.altIds, r.name));
        return row;
    }

    private void finish()
    {
        GearReporter.View v = current;
        var buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.setOpaque(false);
        left(buttons);
        if (v.kit != null)
        {
            JButton copy = TourneyPanel.tabButton("Copy my setup", "gear-card-copy", this::onCopy);
            copy.setToolTipText("Copy the kit the plugin read as a gear-set catalog entry");
            buttons.add(copy);
            buttons.add(vgap(4));
        }
        if (!v.event.arena && v.diff != null && !v.diff.missing.isEmpty())
        {
            buttons.add(TourneyPanel.tabButton("Show missing in bank", "gear-card-bank", () -> actions.showMissing(current.missingIds())));
        }
        if (buttons.getComponentCount() > 0)
        {
            add(vgap(4));
            add(buttons);
        }
        revalidate();
        repaint();
    }

    private void onCopy()
    {
        GearKit kit = current.kit;
        if (kit == null) return;
        actions.copySetup(kit);
        JLabel done = label("gear-card-copied", COPIED, GREEN);
        for (Component c : getComponents())
        {
            if ("gear-card-copied".equals(c.getName())) remove(c);
        }
        add(done);
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

    /** A plain line of text, wrapped to the card's width. */
    private static JLabel label(String name, String text, Color fg)
    {
        return label(name, text, fg, Font.PLAIN, LINE_PT);
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

    /** An item row: escaped text wrapped to the width left beside its item
     *  icon, then {@code suffix} markup (may be empty) on the last line. */
    private static JLabel itemLabel(String name, String escaped, String suffix, Color fg)
    {
        var l = new JLabel();
        l.setName(name);
        l.setForeground(fg);
        plain(l, ITEM_PT);
        l.setText("<html>" + TournamentInfoCard.wrapInner(l.getFont(), TournamentInfoCard.TEXT_WIDTH - ICON_ROOM_PX, escaped) + suffix + "</html>");
        return l;
    }
}
