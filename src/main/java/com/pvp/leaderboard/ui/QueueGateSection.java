package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.LobbyJoinGate;
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
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.Map;
import java.util.Set;

/**
 * The queue view's <b>Matchmaking queue</b> block (Plan 10 F.1, mockup v4
 * card 1, 2026-09-21; set 7, operator 2026-09-22): the green <b>Queue for
 * Matchmaking</b> button first, the hint that says what is missing while
 * it is disabled, then a small <b>Options</b> toggle — collapsed by default,
 * its label summarising the picks — over the wait-time picker (30 s / 5 /
 * 10 / 30 min, shared with Discord through the server-side prefs) and the
 * optional rank-range toggle (labelled with the lobby slider's bounds). It
 * persists its two picks through {@link LobbyPreferences} and owns the pure
 * "may queue" rule ({@link #eligible}: exactly one style, exactly one
 * build, that style unlocked); the owning {@code MatchmakingLobbyPanel}
 * supplies the picks, the slider bounds, the hint and the click handler,
 * and hides the whole block while the queue transport is inert.
 */
public class QueueGateSection extends JPanel
{
    public static final String NAME_SECTION = "matchmaking-queue-section";
    public static final String NAME_WAIT = "matchmaking-queue-wait";
    public static final String NAME_RANGE = "matchmaking-queue-range";
    public static final String NAME_BUTTON = "matchmaking-queue-button";
    public static final String NAME_HINT = "matchmaking-queue-hint";
    /** Set 7: the Options toggle and the panel it shows / hides. */
    public static final String NAME_OPTIONS = "matchmaking-queue-options";
    public static final String NAME_OPTIONS_PANEL = "matchmaking-queue-options-panel";

    /** The one matchmaking action (operator 2026-09-22: exactly this text). */
    static final String BUTTON_TEXT = "Queue for Matchmaking";
    /** The hint under the disabled button until the owner names a cause. */
    static final String DEFAULT_HINT = "Pick exactly one style and one build to queue.";

    /** The wait preference a fresh install queues with (5 min, AS-64). */
    static final int DEFAULT_WAIT_S = 300;
    private static final Color GREEN = new Color(0x2e, 0x7d, 0x32);
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    private static final Color MUTED = new Color(0x9a, 0x9a, 0x9a);

    private final LobbyPreferences prefs;
    private final JComboBox<String> waitCombo;
    private final JToggleButton rangeToggle;
    private final JButton queueBtn;
    private final JLabel hint;
    private final JButton optionsBtn;
    private final JPanel optionsPanel;
    /** Told after a <b>local</b> wait pick / rank-range pick so the owner
     *  can write that ONE key of the shared row (G-2). Sending only the
     *  key the user touched is what stops a wait change from overwriting a
     *  rank range set on Discord. Never fired while {@link #applyPrefs}
     *  is running. */
    private Runnable onWaitChanged;
    private Runnable onRangeChanged;
    /** Suppresses {@link #onPrefsChanged} while a server push is applied,
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

        JLabel header = new JLabel("Matchmaking queue");
        header.setFont(header.getFont().deriveFont(Font.BOLD, headerPt));
        header.setAlignmentX(LEFT_ALIGNMENT);
        add(header);
        add(strut(6));

        // Set 7: the button leads — it is the one matchmaking action.
        // Same treatment as the gate's "Go to lobby" CTA so the two read as siblings.
        queueBtn = new JButton(BUTTON_TEXT);
        queueBtn.setName(NAME_BUTTON);
        queueBtn.setFont(queueBtn.getFont().deriveFont(Font.BOLD, 16f));
        queueBtn.setMargin(new Insets(10, 12, 10, 12));
        queueBtn.setAlignmentX(LEFT_ALIGNMENT);
        queueBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        queueBtn.setFocusPainted(false);
        queueBtn.setForeground(Color.WHITE);
        queueBtn.setOpaque(true);
        queueBtn.setBorderPainted(false);
        queueBtn.addActionListener(e -> { if (onQueue != null) onQueue.run(); });
        add(queueBtn);
        add(strut(4));

        hint = new JLabel("<html>" + DEFAULT_HINT + "</html>");
        hint.setName(NAME_HINT);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        add(hint);
        add(strut(4));

        // Set 7: the two pickers collapse under "Options" so the queue view
        // stays short; the collapsed label still says what they hold.
        optionsBtn = new JButton();
        optionsBtn.setName(NAME_OPTIONS);
        optionsBtn.setFont(optionsBtn.getFont().deriveFont(Font.PLAIN, 12f));
        optionsBtn.setMargin(new Insets(2, 6, 2, 6));
        optionsBtn.setAlignmentX(LEFT_ALIGNMENT);
        optionsBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        optionsBtn.setHorizontalAlignment(SwingConstants.LEFT);
        optionsBtn.setFocusPainted(false);
        optionsBtn.setToolTipText("Wait time and rank range for the queue");
        add(optionsBtn);

        optionsPanel = new JPanel();
        optionsPanel.setName(NAME_OPTIONS_PANEL);
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setAlignmentX(LEFT_ALIGNMENT);
        optionsPanel.setOpaque(false);
        optionsPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        rangeToggle = new JToggleButton("Rank range");
        rangeToggle.setName(NAME_RANGE);
        rangeToggle.setSelected(this.prefs.getQueueRangeEnabled());
        rangeToggle.setFont(rangeToggle.getFont().deriveFont(Font.BOLD, 13f));
        rangeToggle.setMargin(new Insets(4, 10, 4, 10));
        rangeToggle.setAlignmentX(LEFT_ALIGNMENT);
        rangeToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        rangeToggle.setHorizontalAlignment(SwingConstants.LEFT);
        rangeToggle.setFocusPainted(false);
        rangeToggle.setToolTipText("Only match players inside your lobby rank range (the search still widens inside it)");
        rangeToggle.addActionListener(e ->
        {
            this.prefs.setQueueRangeEnabled(rangeToggle.isSelected());
            refreshOptionsLabel();
            announce(onRangeChanged);
        });
        optionsPanel.add(rangeToggle);
        optionsPanel.add(strut(4));

        JPanel waitRow = new JPanel();
        waitRow.setLayout(new BoxLayout(waitRow, BoxLayout.X_AXIS));
        waitRow.setAlignmentX(LEFT_ALIGNMENT);
        waitRow.setOpaque(false);
        waitRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        JLabel waitLabel = new JLabel("Willing to wait ");
        waitLabel.setFont(waitLabel.getFont().deriveFont(Font.BOLD, 13f));
        waitRow.add(waitLabel);
        waitCombo = new JComboBox<>(waitLabels());
        waitCombo.setName(NAME_WAIT);
        waitCombo.setSelectedIndex(indexOfWait(this.prefs.getQueueWaitPrefS(DEFAULT_WAIT_S)));
        waitCombo.setFont(waitCombo.getFont().deriveFont(Font.PLAIN, 13f));
        waitCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        waitCombo.setToolTipText("Maximum time in the queue — shared with the Discord queue");
        waitCombo.addActionListener(e ->
        {
            this.prefs.setQueueWaitPrefS(waitPrefS());
            refreshOptionsLabel();
            announce(onWaitChanged);
        });
        waitRow.add(waitCombo);
        optionsPanel.add(waitRow);
        optionsPanel.setVisible(false);
        add(optionsPanel);

        optionsBtn.addActionListener(e ->
        {
            optionsPanel.setVisible(!optionsPanel.isVisible());
            refreshOptionsLabel();
            revalidate();
            repaint();
        });
        refreshOptionsLabel();

        setQueueEnabled(false);
    }

    /** "Options ▾" while the pickers show; collapsed, the label carries the
     *  two picks ("Options ▸ 5 min · any rank") so hiding them hides nothing. */
    private void refreshOptionsLabel()
    {
        if (optionsPanel.isVisible())
        {
            optionsBtn.setText("Options ▾");
            return;
        }
        optionsBtn.setText("Options ▸  " + QueueText.waitLabel(waitPrefS()) + " · "
            + (rangeToggle.isSelected() ? "rank range on" : "any rank"));
    }

    /** {@code true} while the wait / range pickers are expanded. */
    public boolean optionsShown()
    {
        return optionsPanel.isVisible();
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

    /** Relabels the toggle with the lobby slider's current bounds. */
    public void setRangeLabels(String minLabel, String maxLabel)
    {
        rangeToggle.setText("Rank range: " + minLabel + " – " + maxLabel);
    }

    /** Green when the picks allow a queue, grey (with the hint) otherwise. */
    public void setQueueEnabled(boolean enabled)
    {
        queueBtn.setEnabled(enabled);
        queueBtn.setBackground(enabled ? GREEN : GREY);
        hint.setVisible(!enabled);
    }

    /** The reason shown under a disabled button — the owner names what is
     *  missing (one style, one build, a locked style); {@code null} puts
     *  the generic sentence back. */
    public void setHint(String text)
    {
        hint.setText("<html>" + (text == null || text.trim().isEmpty() ? DEFAULT_HINT : text) + "</html>");
    }

    public boolean isQueueEnabled()
    {
        return queueBtn.isEnabled();
    }

    /** The picked wait preference in seconds (one of the shared choices). */
    public int waitPrefS()
    {
        int i = waitCombo.getSelectedIndex();
        if (i < 0 || i >= QueueService.WAIT_PREF_CHOICES.length) return DEFAULT_WAIT_S;
        return QueueService.WAIT_PREF_CHOICES[i];
    }

    public boolean rangeEnabled()
    {
        return rangeToggle.isSelected();
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
                rangeToggle.setSelected(incoming.rangeEnabled);
                prefs.setQueueRangeEnabled(incoming.rangeEnabled);
                if (incoming.rangeEnabled)
                {
                    prefs.setQueueMinRankIdx(incoming.minRankIdx);
                    prefs.setQueueMaxRankIdx(incoming.maxRankIdx);
                }
            }
            refreshOptionsLabel();
        }
        finally
        {
            applyingPush = false;
        }
    }

    /** Back to the defaults (5 min, range off) — Reset Options wipes the
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
            rangeToggle.setSelected(false);
            prefs.setQueueWaitPrefS(DEFAULT_WAIT_S);
            prefs.setQueueRangeEnabled(false);
            refreshOptionsLabel();
        }
        finally
        {
            applyingPush = false;
        }
    }

    // ---------------------------------------------------------------- the rule

    /** Exactly one style, exactly one build, and that style past the
     *  anti-smurf threshold (a missing count = unknown = locked). */
    public static boolean eligible(Set<Style> styles, Set<BuildType> builds, Map<Style, Integer> counts)
    {
        Style style = soleStyle(styles);
        if (style == null || soleBuild(builds) == null || counts == null) return false;
        Integer count = counts.get(style);
        return count != null && LobbyJoinGate.isUnlocked(count);
    }

    /** The one selected style, or {@code null} unless exactly one is picked. */
    public static Style soleStyle(Set<Style> styles)
    {
        return styles != null && styles.size() == 1 ? styles.iterator().next() : null;
    }

    /** The one selected build, or {@code null} unless exactly one is picked. */
    public static BuildType soleBuild(Set<BuildType> builds)
    {
        return builds != null && builds.size() == 1 ? builds.iterator().next() : null;
    }
}
