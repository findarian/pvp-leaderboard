package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.util.RankUtils;
import javax.swing.*;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.*;

/**
 * The Player Lookup rating rows: one block per bucket with the bucket
 * name, the rank line ({@code "Adamant 3 #731 Top 0.07%"}) and the
 * progress-to-next-rank bar.
 *
 * <p>Plan 10 (2026-09-21): the <b>Tournament Rating</b> row sits above
 * Overall and is hidden until the player has a tournament rating (the
 * bucket is absent from {@code /user} until their first recognised
 * tournament game, AS-97). Every row also carries a <b>streak line</b>
 * ({@code "Current Winstreak 4 · Longest Streak 12"}, BOARD row 33),
 * hidden while both values are unknown / zero. The streak label is added
 * after the bar so the rank label stays the block's second {@link JLabel}
 * (what the existing tests read).
 */
public class RankProgressPanel extends JPanel
{
    private static final int SIDEBAR_SCROLLBAR_RESERVE_PX = 16;
    private static final int PROGRESS_BAR_WIDTH = 200;
    private static final int PROGRESS_BAR_HEIGHT = 16;

    /** Row order on screen. Index 0 (tournament) is hidden until rated. */
    static final String[] BUCKET_KEYS = {"tournament", "overall", "nh", "veng", "multi", "dmm"};
    private static final String[] BUCKET_TITLES = {"Tournament Rating", "Overall Rating", "NH Rating", "Veng Rating", "Multi Rating", "DMM Rating"};
    private static final int TOURNAMENT_IDX = 0;

    private final JPanel[] bucketPanels;
    private final Component[] bucketGaps;
    private final JProgressBar[] progressBars;
    private final JLabel[] bucketNameLabels;
    private final JLabel[] rankLabels;
    private final JLabel[] streakLabels;

    public RankProgressPanel()
    {
        int n = BUCKET_KEYS.length;
        bucketPanels = new JPanel[n];
        bucketGaps = new Component[n];
        progressBars = new JProgressBar[n];
        bucketNameLabels = new JLabel[n];
        rankLabels = new JLabel[n];
        streakLabels = new JLabel[n];

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        initUI();
    }

    private void initUI()
    {
        for (int i = 0; i < BUCKET_KEYS.length; i++)
        {
            JPanel bucketPanel = new JPanel();
            bucketPanel.setLayout(new BoxLayout(bucketPanel, BoxLayout.Y_AXIS));
            bucketPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, SIDEBAR_SCROLLBAR_RESERVE_PX));
            bucketPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
            bucketPanel.setAlignmentX(LEFT_ALIGNMENT);
            bucketPanel.setName("rankBucket-" + BUCKET_KEYS[i]);

            bucketNameLabels[i] = new JLabel(BUCKET_TITLES[i]);
            bucketNameLabels[i].setFont(bucketNameLabels[i].getFont().deriveFont(Font.BOLD));
            bucketNameLabels[i].setAlignmentX(LEFT_ALIGNMENT);
            bucketPanel.add(bucketNameLabels[i]);

            rankLabels[i] = new JLabel(" ");
            rankLabels[i].setFont(rankLabels[i].getFont().deriveFont(Font.BOLD));
            rankLabels[i].setAlignmentX(LEFT_ALIGNMENT);
            bucketPanel.add(rankLabels[i]);

            progressBars[i] = new JProgressBar(0, 100);
            progressBars[i].setValue(0);
            progressBars[i].setStringPainted(true);
            progressBars[i].setString("0%");
            progressBars[i].setPreferredSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setMinimumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setMaximumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            progressBars[i].setAlignmentX(LEFT_ALIGNMENT);
            progressBars[i].setUI(new BasicProgressBarUI()
            {
                @Override
                protected Color getSelectionForeground() { return Color.WHITE; }
                @Override
                protected Color getSelectionBackground() { return Color.WHITE; }
            });
            bucketPanel.add(progressBars[i]);

            // Streak line (BOARD row 33) — after the bar so the rank label
            // stays the block's second JLabel.
            streakLabels[i] = new JLabel(" ");
            streakLabels[i].setFont(streakLabels[i].getFont().deriveFont(Font.PLAIN, 11f));
            streakLabels[i].setForeground(new Color(0xcc, 0xcc, 0xcc));
            streakLabels[i].setAlignmentX(LEFT_ALIGNMENT);
            streakLabels[i].setName("rankStreak-" + BUCKET_KEYS[i]);
            streakLabels[i].setVisible(false);
            bucketPanel.add(streakLabels[i]);

            bucketPanels[i] = bucketPanel;
            add(bucketPanel);
            if (i < BUCKET_KEYS.length - 1)
            {
                bucketGaps[i] = Box.createVerticalStrut(14);
                add(bucketGaps[i]);
            }
        }
        setTournamentRowVisible(false);
    }

    private void setTournamentRowVisible(boolean visible)
    {
        bucketPanels[TOURNAMENT_IDX].setVisible(visible);
        if (bucketGaps[TOURNAMENT_IDX] != null) bucketGaps[TOURNAMENT_IDX].setVisible(visible);
        revalidate();
        repaint();
    }

    /** {@code true} while the Tournament row is shown (the player has a tournament rating). */
    public boolean isTournamentRowVisible()
    {
        return bucketPanels[TOURNAMENT_IDX].isVisible();
    }

    public void updateBucket(String bucket, String rankLabel, int division, double pct, int rankNumber)
    {
        updateBucket(bucket, rankLabel, division, pct, rankNumber, null);
    }

    /**
     * @param topPercent the player's "Top X.XX%" for this bucket (from the
     *                   rank histogram), or {@code null} to omit the suffix.
     *                   Renders {@code "Adamant 3 #731 Top X.XX%"}.
     */
    public void updateBucket(String bucket, String rankLabel, int division, double pct, int rankNumber, String topPercent)
    {
        updateBucket(bucket, rankLabel, division, pct, rankNumber, topPercent, -1, -1);
    }

    /**
     * Full form (Plan 10 / BOARD row 33): {@code streak} / {@code bestStreak}
     * feed the streak line under the bar; pass {@code -1} for either to
     * leave the line as it is (the rank-only refreshes do), {@code 0} for
     * both to hide it. A {@code "—"} rank on the tournament row hides that
     * row; anything else shows it.
     */
    public void updateBucket(String bucket, String rankLabel, int division, double pct, int rankNumber, String topPercent,
                             int streak, int bestStreak)
    {
        int idx = getBucketIndex(bucket);
        if (idx >= 0 && idx < progressBars.length)
        {
            SwingUtilities.invokeLater(() -> {
                Color rankColor = RankUtils.getRankColor(rankLabel);

                if (rankLabels[idx] != null)
                {
                    String displayRank = rankLabel + (division > 0 ? " " + division : "");
                    if (rankNumber > 0)
                    {
                        displayRank += " #" + rankNumber;
                    }
                    if (topPercent != null && !topPercent.isEmpty())
                    {
                        displayRank += " " + topPercent;
                    }
                    rankLabels[idx].setText(displayRank);
                    rankLabels[idx].setForeground(rankColor);
                }
                if (progressBars[idx] != null)
                {
                    progressBars[idx].setValue((int) pct);
                    progressBars[idx].setString(Math.round(pct) + "%");
                    progressBars[idx].setForeground(rankColor);
                }
                if (streak >= 0 && bestStreak >= 0 && streakLabels[idx] != null)
                {
                    boolean show = streak > 0 || bestStreak > 0;
                    streakLabels[idx].setText(show ? streakText(streak, bestStreak) : " ");
                    streakLabels[idx].setVisible(show);
                }
                if (idx == TOURNAMENT_IDX)
                {
                    setTournamentRowVisible(rankLabel != null && !"—".equals(rankLabel) && !rankLabel.trim().isEmpty());
                }
            });
        }
    }

    /** {@code "Current Winstreak 4 · Longest Streak 12"} (BOARD row 33 wording, mockup v4). */
    static String streakText(int streak, int bestStreak)
    {
        return "Current Winstreak " + Math.max(0, streak) + " · Longest Streak " + Math.max(0, bestStreak);
    }

    public void reset()
    {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < BUCKET_KEYS.length; i++)
            {
                if (rankLabels[i] != null)
                {
                    rankLabels[i].setText(" ");
                    rankLabels[i].setForeground(UIManager.getColor("Label.foreground"));
                }
                if (progressBars[i] != null)
                {
                    progressBars[i].setValue(0);
                    progressBars[i].setString("0%");
                    progressBars[i].setForeground(UIManager.getColor("ProgressBar.foreground"));
                }
                if (streakLabels[i] != null)
                {
                    streakLabels[i].setText(" ");
                    streakLabels[i].setVisible(false);
                }
            }
            setTournamentRowVisible(false);
        });
    }

    private int getBucketIndex(String bucket)
    {
        if (bucket == null) return -1;
        String b = bucket.toLowerCase();
        for (int i = 0; i < BUCKET_KEYS.length; i++)
        {
            if (BUCKET_KEYS[i].equals(b)) return i;
        }
        return -1;
    }
}
