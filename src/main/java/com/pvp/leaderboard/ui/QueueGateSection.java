package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.LobbyPreferences;
import com.pvp.leaderboard.lobby.Style;
import com.pvp.leaderboard.queue.QueuePrefs;
import com.pvp.leaderboard.queue.QueueService;
import com.pvp.leaderboard.queue.QueueText;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

public class QueueGateSection extends JPanel
{
    public static final String NAME_SECTION = "matchmaking-queue-section";
    public static final String NAME_WAIT_TITLE = "matchmaking-queue-wait-title";
    public static final String NAME_WAIT = "matchmaking-queue-wait";
    public static final String NAME_RANGE = QueueRangeSlider.NAME;
    public static final String NAME_BUTTON = "matchmaking-queue-button";

    /** The one matchmaking action (operator 2026-09-22: exactly this text). */
    static final String BUTTON_TEXT = "Queue for Matchmaking";
    static final String WAIT_TITLE = "Willing to wait";

    /** Every queue join goes out with this style and build. */
    public static final Style QUEUE_STYLE = Style.NH;
    public static final BuildType QUEUE_BUILD = BuildType.MAIN;

    /** The wait preference a fresh install queues with (5 min, AS-64). */
    static final int DEFAULT_WAIT_S = 300;
    private static final Color GREEN = new Color(0x2e, 0x7d, 0x32);

    private final LobbyPreferences prefs;
    private final JComboBox<String> waitCombo;
    private final QueueRangeSlider range;
    private final JButton queueBtn;
    /** Told after a <b>local</b> wait pick / rank-range pick so the owner
     *  can write that ONE key of the shared row (G-2). Sending only the
     *  key the user touched is what stops a wait change from overwriting a
     *  rank range set on Discord. Never fired while {@link #applyPrefs}
     *  is running. */
    private Runnable onWaitChanged;
    private Runnable onRangeChanged;
    /** Suppresses {@link #announce} while a server push is applied,
     *  so a Discord-side change is not immediately echoed back. */
    private boolean applyingPush;

    public QueueGateSection(LobbyPreferences prefs, float headerPt, Runnable onQueue)
    {
        this.prefs = prefs == null ? LobbyPreferences.inMemory() : prefs;
        setName(NAME_SECTION);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);
        setOpaque(false);
        // Bottom gap lives inside the block so hiding the block hides the gap too.
        setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        JLabel waitTitle = new JLabel(WAIT_TITLE);
        waitTitle.setName(NAME_WAIT_TITLE);
        waitTitle.setFont(waitTitle.getFont().deriveFont(Font.BOLD, headerPt));
        waitTitle.setAlignmentX(LEFT_ALIGNMENT);
        add(waitTitle);
        add(strut(4));

        waitCombo = new JComboBox<>(waitLabels());
        waitCombo.setName(NAME_WAIT);
        waitCombo.setSelectedIndex(indexOfWait(this.prefs.getQueueWaitPrefS(DEFAULT_WAIT_S)));
        waitCombo.setFont(waitCombo.getFont().deriveFont(Font.PLAIN, headerPt));
        waitCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        waitCombo.setAlignmentX(LEFT_ALIGNMENT);
        waitCombo.setToolTipText("Maximum time in the queue — shared with the Discord queue");
        waitCombo.addActionListener(e ->
        {
            this.prefs.setQueueWaitPrefS(waitPrefS());
            announce(onWaitChanged);
        });
        add(waitCombo);
        add(strut(14));

        queueBtn = new JButton(BUTTON_TEXT);
        queueBtn.setName(NAME_BUTTON);
        queueBtn.setFont(queueBtn.getFont().deriveFont(Font.BOLD, 16f));
        queueBtn.setMargin(new Insets(10, 12, 10, 12));
        queueBtn.setAlignmentX(LEFT_ALIGNMENT);
        queueBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        queueBtn.setFocusPainted(false);
        queueBtn.setForeground(Color.WHITE);
        queueBtn.setBackground(GREEN);
        queueBtn.setOpaque(true);
        queueBtn.setBorderPainted(false);
        queueBtn.addActionListener(e -> { if (onQueue != null) onQueue.run(); });
        add(queueBtn);

        // The owner places the rank-range row (above the gate's title).
        boolean rangeOn = this.prefs.getQueueRangeEnabled();
        range = new QueueRangeSlider(rangeOn ? this.prefs.getQueueMinRankIdx(0) : 0,
            rangeOn ? this.prefs.getQueueMaxRankIdx(-1) : -1);
        range.setOnCommit(this::onRangeCommitted);
    }

    private static JComponent strut(int height)
    {
        JComponent f = (JComponent) Box.createVerticalStrut(height);
        f.setAlignmentX(LEFT_ALIGNMENT);
        return f;
    }

    /** The picker's items, one per {@link QueueService#WAIT_PREF_CHOICES} entry. */
    static String[] waitLabels()
    {
        String[] out = new String[QueueService.WAIT_PREF_CHOICES.length];
        for (int i = 0; i < out.length; i++) out[i] = QueueText.waitLabel(QueueService.WAIT_PREF_CHOICES[i]);
        return out;
    }

    /** The picker index nearest to {@code waitPrefS}. */
    static int indexOfWait(int waitPrefS)
    {
        int best = 0;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < QueueService.WAIT_PREF_CHOICES.length; i++)
        {
            long delta = Math.abs((long) QueueService.WAIT_PREF_CHOICES[i] - waitPrefS);
            if (delta < bestDelta)
            {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
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
        int i = waitCombo.getSelectedIndex();
        if (i < 0 || i >= QueueService.WAIT_PREF_CHOICES.length) return DEFAULT_WAIT_S;
        return QueueService.WAIT_PREF_CHOICES[i];
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

    private void onRangeCommitted()
    {
        boolean on = range.narrowed();
        prefs.setQueueRangeEnabled(on);
        if (on)
        {
            prefs.setQueueMinRankIdx(range.low());
            prefs.setQueueMaxRankIdx(range.high());
        }
        announce(onRangeChanged);
    }

    // ------------------------------------------------- shared prefs (G-2)

    /** Registers the callback fired after a local <b>wait-time</b> pick. */
    public void setOnWaitChanged(Runnable listener)
    {
        this.onWaitChanged = listener;
    }

    /** Registers the callback fired after a local <b>rank-range</b> pick. */
    public void setOnRangeChanged(Runnable listener)
    {
        this.onRangeChanged = listener;
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
                waitCombo.setSelectedIndex(indexOfWait(incoming.waitPrefS));
                prefs.setQueueWaitPrefS(waitPrefS());
            }
            if (incoming.rangeEnabled != null)
            {
                prefs.setQueueRangeEnabled(incoming.rangeEnabled);
                if (incoming.rangeEnabled)
                {
                    prefs.setQueueMinRankIdx(incoming.minRankIdx);
                    prefs.setQueueMaxRankIdx(incoming.maxRankIdx);
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

    /** Back to the defaults (5 min, every rank) — Reset Options wipes the
     *  persisted prefs, so the widgets and the store are re-aligned here. */
    public void reset()
    {
        // Local only: Reset Options must not silently overwrite a wait time
        // the player set on Discord (the shared row is rewritten on their
        // next pick or their next queue/join).
        applyingPush = true;
        try
        {
            waitCombo.setSelectedIndex(indexOfWait(DEFAULT_WAIT_S));
            range.setRange(0, -1);
            prefs.setQueueWaitPrefS(DEFAULT_WAIT_S);
            prefs.setQueueRangeEnabled(false);
        }
        finally
        {
            applyingPush = false;
        }
    }
}
