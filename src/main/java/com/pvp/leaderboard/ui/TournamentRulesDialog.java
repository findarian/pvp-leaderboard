package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.tournament.TournamentRules;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * The in-panel Rules dialog (plugin set 6, 2026-09-22): a card the
 * Tournaments panel shows in place of the list / active view when an
 * event's {@code tournament/list_response} entry carries a
 * {@link TournamentRules} block. Sections render in wire order as a bold
 * heading + the lines as wrapped <b>plain text</b> (a {@link JTextArea},
 * so nothing in the rules is ever interpreted as HTML), in a scroll pane;
 * <b>Open on the site</b> opens the event's {@code rules_url} in the
 * browser and <b>Back</b> returns to the card the player came from. With
 * no block (an older backend, or junk) the panel never builds this — it
 * opens {@code rules_url} directly, as before.
 */
final class TournamentRulesDialog extends JPanel
{
    static final String NAME = "tournament-rules-dialog";
    private static final Color MUTED = new Color(0x9a, 0x9a, 0x9a);
    private static final Color TEXT = new Color(0xdd, 0xdd, 0xdd);

    TournamentRulesDialog(String eventName, TournamentRules rules, Runnable onBack, Runnable onOpenSite)
    {
        setLayout(new BorderLayout());
        setName(NAME);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("<html>" + TournamentsPanel.escape(eventName == null ? "" : eventName) + " · Rules</html>");
        title.setName("tournament-rules-title");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        top.add(title);
        JLabel caption = new JLabel("Version " + rules.version + " · " + rules.sections.size() + (rules.sections.size() == 1 ? " section" : " sections"));
        caption.setFont(caption.getFont().deriveFont(Font.PLAIN, 11f));
        caption.setForeground(MUTED);
        caption.setAlignmentX(LEFT_ALIGNMENT);
        top.add(caption);
        top.add(Box.createVerticalStrut(4));
        add(top, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        int i = 0;
        for (TournamentRules.Section s : rules.sections)
        {
            if (!s.heading.isEmpty())
            {
                JLabel heading = new JLabel(s.heading);
                heading.setName("tournament-rules-heading-" + i);
                heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
                heading.setAlignmentX(LEFT_ALIGNMENT);
                heading.setBorder(BorderFactory.createEmptyBorder(i == 0 ? 0 : 8, 0, 2, 0));
                body.add(heading);
            }
            if (!s.lines.isEmpty())
            {
                JTextArea lines = new JTextArea(String.join("\n", s.lines));
                lines.setName("tournament-rules-lines-" + i);
                lines.setEditable(false);
                lines.setFocusable(false);
                lines.setLineWrap(true);
                lines.setWrapStyleWord(true);
                lines.setOpaque(false);
                lines.setForeground(TEXT);
                lines.setFont(lines.getFont().deriveFont(Font.PLAIN, 12f));
                lines.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
                lines.setAlignmentX(LEFT_ALIGNMENT);
                body.add(lines);
            }
            i++;
        }
        body.add(Box.createVerticalGlue());
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.X_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JButton back = TournamentsPanel.smallButton("Back");
        back.setName("tournament-rules-back");
        back.addActionListener(e -> onBack.run());
        footer.add(back);
        footer.add(Box.createHorizontalStrut(4));
        JButton site = TournamentsPanel.smallButton("Open on the site");
        site.setName("tournament-rules-open-site");
        site.addActionListener(e -> onOpenSite.run());
        footer.add(site);
        footer.add(Box.createHorizontalGlue());
        add(footer, BorderLayout.SOUTH);
    }
}
