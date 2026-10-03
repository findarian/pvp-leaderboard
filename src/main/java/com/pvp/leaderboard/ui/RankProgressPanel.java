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
 * <p>The <b>Tournament Rating</b> row sits between Overall and NH and is
 * hidden until the player has a tournament rating (the bucket is absent
 * from {@code /user} until their first tournament game). Every row also
 * carries two <b>streak lines</b> under the bar ({@code "Current Winstreak 4"}
 * above {@code "Longest Streak 12"}, at the title's size in plain weight),
 * shown only while {@link #setStreaksShown} is on. The streak labels are
 * added after the bar so the rank label stays the block's second
 * {@link JLabel}. Each row is capped at its own height.
 */
public class RankProgressPanel extends JPanel
{
    private static final int SIDEBAR_SCROLLBAR_RESERVE_PX = 16;
    private static final int PROGRESS_BAR_WIDTH = 200;
    private static final int PROGRESS_BAR_HEIGHT = 16;

    /** Row order on screen. Index 1 (tournament) is hidden until rated. */
    static final String[] BUCKET_KEYS = {"overall", "tournament", "nh", "veng", "multi", "dmm"};
    private static final String[] BUCKET_TITLES = {"Overall Rating", "Tournament Rating", "NH Rating", "Veng Rating", "Multi Rating", "DMM Rating"};
    private static final int TOURNAMENT_IDX = 1;
    private static final Color STREAK_FG = new Color(0xcc, 0xcc, 0xcc);

    private final JPanel[] bucketPanels;
    private final Component[] bucketGaps;
    private final JProgressBar[] progressBars;
    private final JLabel[] bucketNameLabels;
    private final JLabel[] rankLabels;
    private final JLabel[] streakLabels;
    private final JLabel[] longestLabels;
    private boolean streaksShown;

    /** A row that never grows past its own height. */
    private static final class RowPanel extends JPanel
    {
        @Override
        public Dimension getMaximumSize()
        {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    public RankProgressPanel()
    {
        int n = BUCKET_KEYS.length;
        bucketPanels = new JPanel[n];
        bucketGaps = new Component[n];
        progressBars = new JProgressBar[n];
        bucketNameLabels = new JLabel[n];
        rankLabels = new JLabel[n];
        streakLabels = new JLabel[n];
        longestLabels = new JLabel[n];

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        initUI();
    }

    private void initUI()
    {
        for (int i = 0; i < BUCKET_KEYS.length; i++)
        {
            JPanel bucketPanel = new RowPanel();
            bucketPanel.setLayout(new BoxLayout(bucketPanel, BoxLayout.Y_AXIS));
            bucketPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, SIDEBAR_SCROLLBAR_RESERVE_PX));
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

            // The streak lines sit after the bar so the rank label stays the block's second JLabel.
            streakLabels[i] = streakLabel("rankStreak-" + BUCKET_KEYS[i], bucketNameLabels[i].getFont());
            bucketPanel.add(streakLabels[i]);
            longestLabels[i] = streakLabel("rankStreakLongest-" + BUCKET_KEYS[i], bucketNameLabels[i].getFont());
            bucketPanel.add(longestLabels[i]);

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

    private static JLabel streakLabel(String name, Font titleFont)
    {
        JLabel label = new JLabel(name.startsWith("rankStreakLongest-") ? longestStreakText(0) : currentStreakText(0, false));
        label.setFont(titleFont.deriveFont(Font.PLAIN, titleFont.getSize2D()));
        label.setForeground(STREAK_FG);
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setName(name);
        label.setVisible(false);
        return label;
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

    /** Whether the streak lines are shown under the rows. */
    public boolean isStreaksShown()
    {
        return streaksShown;
    }

    /** Shows or hides the streak lines under every row (Swing thread). */
    public void setStreaksShown(boolean shown)
    {
        streaksShown = shown;
        for (int i = 0; i < BUCKET_KEYS.length; i++)
        {
            streakLabels[i].setVisible(shown);
            longestLabels[i].setVisible(shown);
        }
        revalidate();
        repaint();
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
     * Full form: {@code streak} / {@code bestStreak} feed the streak lines
     * under the bar; pass {@code -1} for either to leave the lines as they
     * are (the rank-only refreshes do). A {@code "—"} rank on the tournament
     * row hides that row; anything else shows it.
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
                if (streak >= 0 && bestStreak >= 0)
                {
                    setStreakLines(idx, streak, false, bestStreak);
                }
                if (idx == TOURNAMENT_IDX)
                {
                    setTournamentRowVisible(rankLabel != null && !"—".equals(rankLabel) && !rankLabel.trim().isEmpty());
                }
            });
        }
    }

    public void updateStreak(String bucket, int streak, boolean plus, int bestStreak)
    {
        int idx = getBucketIndex(bucket);
        if (idx < 0) return;
        SwingUtilities.invokeLater(() -> setStreakLines(idx, streak, plus, bestStreak));
    }

    private void setStreakLines(int idx, int streak, boolean plus, int bestStreak)
    {
        streakLabels[idx].setText(currentStreakText(streak, plus));
        longestLabels[idx].setText(longestStreakText(bestStreak));
        streakLabels[idx].setVisible(streaksShown);
        longestLabels[idx].setVisible(streaksShown);
    }

    /** {@code "Current Winstreak 4"} ({@code "100+"} when the loaded history ran out before a loss). */
    static String currentStreakText(int streak, boolean plus)
    {
        return "Current Winstreak " + Math.max(0, streak) + (plus ? "+" : "");
    }

    /** {@code "Longest Streak 12"}. */
    static String longestStreakText(int bestStreak)
    {
        return "Longest Streak " + Math.max(0, bestStreak);
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
                streakLabels[i].setText(currentStreakText(0, false));
                streakLabels[i].setVisible(false);
                longestLabels[i].setText(longestStreakText(0));
                longestLabels[i].setVisible(false);
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
