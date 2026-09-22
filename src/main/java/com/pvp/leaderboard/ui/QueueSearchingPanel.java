package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.queue.QueueState;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.function.IntFunction;

/**
 * The "Searching…" card of the matchmaking queue (Plan 10 F.1, mockup v4
 * card 2): elapsed / limit, style · build, the rank range when one is set,
 * the current ±rating window with the next expansion, the matches-found
 * counters, and the <b>Expand matchmaking range</b> / <b>Leave queue</b>
 * buttons. Pure rendering of a {@link QueueState}; the owning
 * {@link MatchmakingLobbyPanel} sends the cmds.
 */
public class QueueSearchingPanel extends JPanel
{
    public static final String NAME_TITLE = "queue-title";
    public static final String NAME_CLOCK = "queue-clock";
    public static final String NAME_STYLE = "queue-style";
    public static final String NAME_RANGE = "queue-range";
    public static final String NAME_WINDOW = "queue-window";
    public static final String NAME_COUNTERS = "queue-counters";
    public static final String NAME_EXPAND = "queue-expand";
    public static final String NAME_LEAVE = "queue-leave";

    /** The window a fresh waiter searches in — ±100 for the first 30 s
     *  ({@code matchmaking_queue.window_for_elapsed}); anything wider means
     *  the schedule has started doubling (G-13). */
    static final int OPENING_WINDOW = 100;

    private final JLabel title = new JLabel("Searching…");
    private final JLabel clock = new JLabel(" ");
    private final JLabel style = new JLabel(" ");
    private final JLabel range = new JLabel(" ");
    private final JLabel window = new JLabel(" ");
    private final JLabel counters = new JLabel(" ");
    private final JButton expand = new JButton("Expand matchmaking range");
    private final JButton leave = new JButton("Leave queue");
    private final IntFunction<String> rankLabel;
    private QueueState last;

    public QueueSearchingPanel(IntFunction<String> rankLabel, Runnable onExpand, Runnable onLeave)
    {
        this.rankLabel = rankLabel == null ? Integer::toString : rankLabel;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(18, 8, 18, 8));
        title.setName(NAME_TITLE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        add(left(title));
        add(Box.createVerticalStrut(6));
        clock.setName(NAME_CLOCK);
        clock.setFont(clock.getFont().deriveFont(Font.BOLD, 16f));
        add(left(clock));
        add(Box.createVerticalStrut(10));
        style.setName(NAME_STYLE);
        add(left(style));
        range.setName(NAME_RANGE);
        add(left(range));
        window.setName(NAME_WINDOW);
        add(left(window));
        counters.setName(NAME_COUNTERS);
        counters.setForeground(new Color(0x9a, 0x9a, 0x9a));
        add(left(counters));
        add(Box.createVerticalStrut(12));
        expand.setName(NAME_EXPAND);
        expand.setFont(expand.getFont().deriveFont(Font.BOLD, 14f));
        expand.setMargin(new Insets(8, 12, 8, 12));
        expand.setBackground(new Color(0x2e, 0x7d, 0x32));
        expand.setForeground(Color.WHITE);
        expand.setOpaque(true);
        expand.setBorderPainted(false);
        expand.setFocusPainted(false);
        expand.addActionListener(e -> { if (onExpand != null) onExpand.run(); });
        add(left(expand));
        JLabel hint = new JLabel("<html>Opens the search to everyone in your style; can be changed any time.</html>");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(new Color(0x9a, 0x9a, 0x9a));
        add(left(hint));
        add(Box.createVerticalStrut(10));
        leave.setName(NAME_LEAVE);
        leave.setFont(leave.getFont().deriveFont(Font.BOLD, 14f));
        leave.setMargin(new Insets(8, 12, 8, 12));
        leave.setBackground(new Color(0x5a, 0x2a, 0x2a));
        leave.setForeground(new Color(0xff, 0xb3, 0xb3));
        leave.setOpaque(true);
        leave.setBorderPainted(false);
        leave.setFocusPainted(false);
        leave.addActionListener(e -> { if (onLeave != null) onLeave.run(); });
        add(left(leave));
    }

    private static <T extends java.awt.Component> T left(T c)
    {
        if (c instanceof javax.swing.JComponent)
        {
            ((javax.swing.JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
            ((javax.swing.JComponent) c).setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 4));
        }
        return c;
    }

    /** Re-render from a fresh {@code queue/state}. */
    public void render(QueueState s, String styleLabel, String buildLabel)
    {
        last = s;
        boolean anyone = s.expanded || s.window == null;
        // G-13: the operator's phrase is for the moment the ±100 window
        // STARTS DOUBLING (mockup card 2 note), not only for the Expand
        // button. A rank-range search widens inside the range, so it keeps
        // the plain title; pressing Expand drops the range, so it wins.
        boolean widening = s.expanded
            || (!s.hasRankRange() && (s.window == null || s.window > OPENING_WINDOW));
        title.setText(widening ? "Expanding matchmaking range…" : "Searching…");
        clock.setText(mmss(s.elapsedS) + " / " + mmss(s.waitPrefS));
        style.setText("Style " + styleLabel + " · " + buildLabel);
        if (s.hasRankRange())
        {
            range.setText("Looking in " + rankLabel.apply(s.rankMinIdx) + " – " + rankLabel.apply(s.rankMaxIdx));
            range.setVisible(true);
        }
        else
        {
            range.setText(" ");
            range.setVisible(false);
        }
        if (anyone)
        {
            window.setText("Searching anyone in " + styleLabel);
        }
        else
        {
            String next = s.nextExpandInS == null ? "" : " (next " + Math.min(s.window * 2, 800) + " in " + s.nextExpandInS + " s)";
            window.setText("Within " + s.window + " rating" + next);
        }
        counters.setText("Matches found " + s.matchesLastHour + " last hour · " + s.matchesToday + " today");
        expand.setVisible(!anyone);
        revalidate();
        repaint();
    }

    /** Local 1 Hz tick between server pushes: only the elapsed clock moves. */
    public void tick()
    {
        if (last == null) return;
        last = new QueueState(last.state, last.window, last.expanded, last.rankMinIdx, last.rankMaxIdx, last.elapsedS + 1, last.waitPrefS,
            Math.max(0, last.remainingS - 1), last.nextExpandInS == null ? null : Math.max(0, last.nextExpandInS - 1), last.matchesLastHour,
            last.matchesToday, last.reason, last.style, last.build, last.region);
        clock.setText(mmss(last.elapsedS) + " / " + mmss(last.waitPrefS));
    }

    static String mmss(int seconds)
    {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }
}
