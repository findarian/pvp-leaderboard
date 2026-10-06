package com.pvp.leaderboard.ui;

import lombok.*;
import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.queue.*;
import java.awt.*;
import javax.swing.*;

public class QueueGateSection extends JPanel
{
    /** The one matchmaking action (operator 2026-09-22: exactly this text). */
    static final String BUTTON_TEXT = "Queue for Matchmaking";
    static final String WAIT_TITLE = "Willing to wait";

    /** Every queue join goes out with this style and build. */
    public static final Style QUEUE_STYLE = Style.NH;
    public static final BuildType QUEUE_BUILD = BuildType.MAIN;

    /** The wait preference a fresh install queues with (5 min, AS-64). */
    static final int DEFAULT_WAIT_S = 300;

    private final LobbyPrefs prefs;
    private final JComboBox<String> waitCombo;
    private final QueueRangeSlider range;
    private final JButton queueBtn;
    /** Told after a <b>local</b> wait pick / rank-range pick so the owner
     *  can write that ONE key of the shared row (G-2). Sending only the
     *  key the user touched is what stops a wait change from overwriting a
     *  rank range set on Discord. Never fired while {@link #applyPrefs}
     *  is running. */
    @Setter private Runnable onWaitChanged;
    @Setter private Runnable onRangeChanged;
    /** Suppresses {@link #announce} while a server push is applied,
     *  so a Discord-side change is not immediately echoed back. */
    private boolean applyingPush;

    public QueueGateSection(LobbyPrefs prefs, float headerPt, Runnable onQueue)
    {
        this.prefs = prefs == null ? LobbyPrefs.inMemory() : prefs;
        setName("matchmaking-queue-section");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);
        setOpaque(false);
        // Bottom gap lives inside the block so hiding the block hides the gap too.
        setBorder(Ui.pad(0, 0, 12, 0));

        var waitTitle = new JLabel(WAIT_TITLE);
        waitTitle.setName("matchmaking-queue-wait-title");
        add(Ui.bold(waitTitle, headerPt));
        add(Ui.lgap(4));

        waitCombo = new JComboBox<>(waitLabels());
        waitCombo.setName("matchmaking-queue-wait");
        waitCombo.setSelectedIndex(QueueService.waitIndex(this.prefs.getWaitPrefS(DEFAULT_WAIT_S)));
        Ui.left(Ui.maxH(Ui.plain(waitCombo, headerPt), 40));
        waitCombo.setToolTipText("Maximum time in the queue — shared with the Discord queue");
        waitCombo.addActionListener(e ->
        {
            this.prefs.setWaitPrefS(waitPrefS());
            announce(onWaitChanged);
        });
        add(waitCombo);
        add(Ui.lgap(14));

        queueBtn = new JButton(BUTTON_TEXT);
        queueBtn.setName("matchmaking-queue-button");
        Ui.bold(queueBtn, 16f);
        queueBtn.setMargin(new Insets(10, 12, 10, 12));
        Ui.maxH(queueBtn, 44);
        queueBtn.setFocusPainted(false);
        queueBtn.setForeground(Color.WHITE);
        queueBtn.setBackground(Ui.ACCENT);
        queueBtn.setOpaque(true);
        queueBtn.setBorderPainted(false);
        queueBtn.addActionListener(e -> { if (onQueue != null) onQueue.run(); });
        add(queueBtn);

        // The owner places the rank-range row (above the gate's title).
        boolean rangeOn = this.prefs.getRangeOn();
        range = new QueueRangeSlider(rangeOn ? this.prefs.getMinRank(0) : 0,
            rangeOn ? this.prefs.getMaxRank(-1) : -1);
        range.setOnCommit(this::onRangeSet);
    }

    /** The picker's items, one per {@link QueueService#WAIT_CHOICES} entry. */
    static String[] waitLabels()
    {
        var out = new String[QueueService.WAIT_CHOICES.length];
        for (int i = 0; i < out.length; i++) out[i] = QueueText.waitLabel(QueueService.WAIT_CHOICES[i]);
        return out;
    }

    // ---------------------------------------------------------------- state

    /** The rank-range row this section keeps in step with the prefs. */
    public QueueRangeSlider rangeSlider()
    {
        return range;
    }

    /** The picked wait preference in seconds (one of the shared choices). */
    public int waitPrefS()
    {
        // The picker always holds one of the four choices.
        return QueueService.WAIT_CHOICES[waitCombo.getSelectedIndex()];
    }

    /** {@code true} while the slider is narrower than every rank. */
    public boolean rangeEnabled()
    {
        return range.narrowed();
    }

    public int rangeMinIdx()
    {
        return range.low();
    }

    public int rangeMaxIdx()
    {
        return range.high();
    }

    private void onRangeSet()
    {
        boolean on = range.narrowed();
        prefs.setRangeOn(on);
        if (on)
        {
            prefs.setMinRank(range.low());
            prefs.setMaxRank(range.high());
        }
        announce(onRangeChanged);
    }

    private void announce(Runnable listener)
    {
        if (applyingPush || listener == null) return;
        listener.run();
    }

    /** Applies a {@code queue/prefs} push (the shared row, possibly last
     *  written from Discord) onto the pickers and the persisted copy.
     *
     *  <p>Only the fields the push actually stated are touched — a
     *  {@link QueuePrefs} with no wait preference leaves the dropdown
     *  where the user put it. The outbound hook stays silent throughout,
     *  so applying a push never bounces back to the server. */
    public void applyPrefs(QueuePrefs incoming)
    {
        if (incoming == null) return;
        applyingPush = true;
        try
        {
            if (incoming.hasWaitPref())
            {
                waitCombo.setSelectedIndex(QueueService.waitIndex(incoming.waitPrefS));
                prefs.setWaitPrefS(waitPrefS());
            }
            if (incoming.rangeEnabled != null)
            {
                prefs.setRangeOn(incoming.rangeEnabled);
                if (incoming.rangeEnabled)
                {
                    prefs.setMinRank(incoming.minRankIdx);
                    prefs.setMaxRank(incoming.maxRankIdx);
                    range.setRange(incoming.minRankIdx, incoming.maxRankIdx);
                }
                else
                {
                    range.setRange(0, -1);
                }
            }
        }
        finally
        {
            applyingPush = false;
        }
    }
}
