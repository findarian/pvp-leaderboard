package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.queue.*;
import java.awt.*;
import javax.swing.*;

/**
 * The "Searching…" card of the matchmaking queue: the title and the
 * elapsed / limit clock, then the <b>Expand matchmaking range</b> and
 * <b>Leave queue</b> buttons. Pure rendering of a {@link QueueState}; the
 * owning {@link MatchmakingLobbyPanel} sends the cmds.
 */
public class QueueSearchingPanel extends JPanel
{
    private final JLabel title = new JLabel("Searching…");
    private final JLabel clock = new JLabel(" ");
    private final JButton expand = new JButton("Expand matchmaking range");
    private final JButton leave = new JButton("Leave queue");
    private QueueState last;
    /** Seconds in the queue: the last push's, plus one per local tick. */
    private int elapsed;

    public QueueSearchingPanel(Runnable onExpand, Runnable onLeave)
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(Ui.pad(18, 8, 18, 8));
        title.setName("queue-title");
        add(row(Ui.bold(title, 18f)));
        add(Ui.vgap(6));
        clock.setName("queue-clock");
        add(row(Ui.bold(clock, 16f)));
        add(Ui.vgap(12));
        add(row(button(expand, "queue-expand", Ui.ACCENT, Color.WHITE, onExpand)));
        add(Ui.vgap(10));
        add(row(button(leave, "queue-leave", Ui.RED, Ui.RED_FG, onLeave)));
    }

    /** A bold 14 pt flat button in {@code bg} / {@code fg} that runs {@code action} (when there is one). */
    private static JButton button(JButton b, String name, Color bg, Color fg, Runnable action)
    {
        b.setName(name);
        Ui.bold(b, 14f);
        b.setMargin(new Insets(8, 12, 8, 12));
        b.setBackground(bg);
        b.setForeground(fg);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.addActionListener(e -> { if (action != null) action.run(); });
        return b;
    }

    /** {@code c} (a label or a button: aligned left already) as wide as the card and 4 px taller than it wants. */
    private static <T extends JComponent> T row(T c)
    {
        return Ui.maxH(c, c.getPreferredSize().height + 4);
    }

    /** Re-render from a fresh {@code queue/state}. */
    public void render(QueueState s)
    {
        last = s;
        elapsed = s.elapsedS;
        clock.setText(Ui.mmss(elapsed) + " / " + Ui.mmss(s.waitPrefS));
        expand.setVisible(!(s.expanded || s.window == null));
        revalidate();
        repaint();
    }

    /** Local 1 Hz tick between server pushes: only the elapsed clock moves. */
    public void tick()
    {
        if (last == null) return;
        clock.setText(Ui.mmss(++elapsed) + " / " + Ui.mmss(last.waitPrefS));
    }
}
