package com.pvp.leaderboard.ui;

import lombok.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import javax.swing.plaf.basic.*;
import static com.pvp.leaderboard.ui.Ui.*;

/**
 * The Player Lookup rating rows: one block per bucket with the bucket
 * name, the rank line ({@code "Adamant 3 #731 Top 0.07%"}) and the
 * progress-to-next-rank bar.
 *
 * <p>The <b>Tournament Rating</b> row (index 1) sits between Overall and NH and is
 * hidden until the player has a tournament rating (the bucket is absent
 * from {@code /user} until their first tournament game). Every row also
 * carries two <b>streak lines</b> under the bar ({@code "Current Winstreak 4"}
 * above {@code "Longest Streak 12"}, at the title's size in plain weight),
 * shown only while {@link #setStreaksShown} is on. The streak labels are
 * added after the bar so the rank label stays the block's second
 * {@link JLabel}. Each row is capped at its own height, and keeps 16 px
 * clear on the right for the sidebar's scroll bar.
 */
public class RankProgress extends JPanel
{
    /** Row order on screen. Index 1 (tournament) is hidden until rated. */
    static final String[] BUCKET_KEYS = {"overall", "tournament", "nh", "veng", "multi", "dmm"};
    private static final String[] BUCKET_TITLES = {"Overall Rating", "Tournament Rating", "NH Rating", "Veng Rating", "Multi Rating", "DMM Rating"};
    private static final Color STREAK_FG = new Color(0xcccccc);

    private final JProgressBar[] progressBars = new JProgressBar[6];
    private final JLabel[] rankLabels = new JLabel[6];
    private final JLabel[] streakLabels = new JLabel[6];
    private final JLabel[] longestLabels = new JLabel[6];
    /** The Tournament row and the gap under it. */
    private JPanel tournamentRow;
    private Component tournamentGap;
    @Getter private boolean streaksShown;

    public RankProgress()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        var barSize = new Dimension(200, 16);
        for (int i = 0; i < BUCKET_KEYS.length; i++)
        {
            JPanel bucketPanel = new CapPanel();
            bucketPanel.setLayout(new BoxLayout(bucketPanel, BoxLayout.Y_AXIS));
            bucketPanel.setBorder(pad(0, 0, 0, 16));
            left(bucketPanel);
            bucketPanel.setName("rankBucket-" + BUCKET_KEYS[i]);

            var title = new JLabel(BUCKET_TITLES[i]);
            Font titleFont = title.getFont().deriveFont(Font.BOLD);
            title.setFont(titleFont);
            bucketPanel.add(title);

            rankLabels[i] = new JLabel(" ");
            rankLabels[i].setFont(rankLabels[i].getFont().deriveFont(Font.BOLD));
            bucketPanel.add(rankLabels[i]);

            var bar = new JProgressBar(0, 100);
            bar.setStringPainted(true);
            bar.setString("0%");
            bar.setPreferredSize(barSize);
            bar.setMinimumSize(barSize);
            bar.setMaximumSize(barSize);
            bar.setBorder(pad(0, 0, 0, 0));
            left(bar);
            bar.setUI(new BasicProgressBarUI()
            {
                @Override
                protected Color getSelectionForeground() { return Color.WHITE; }
                @Override
                protected Color getSelectionBackground() { return Color.WHITE; }
            });
            progressBars[i] = bar;
            bucketPanel.add(bar);

            // The streak lines sit after the bar so the rank label stays the block's second JLabel.
            streakLabels[i] = streakLabel("rankStreak-" + BUCKET_KEYS[i], titleFont);
            bucketPanel.add(streakLabels[i]);
            longestLabels[i] = streakLabel("rankStreakLongest-" + BUCKET_KEYS[i], titleFont);
            bucketPanel.add(longestLabels[i]);

            add(bucketPanel);
            Component gap = i < BUCKET_KEYS.length - 1 ? add(vgap(14)) : null;
            if (i == 1)
            {
                tournamentRow = bucketPanel;
                tournamentGap = gap;
            }
        }
        setTournamentRowVisible(false);
    }

    private static JLabel streakLabel(String name, Font titleFont)
    {
        var label = new JLabel(name.startsWith("rankStreakLongest-") ? bestText(0) : streakText(0, false));
        label.setFont(titleFont.deriveFont(Font.PLAIN));
        label.setForeground(STREAK_FG);
        label.setName(name);
        label.setVisible(false);
        return label;
    }

    private void setTournamentRowVisible(boolean visible)
    {
        tournamentRow.setVisible(visible);
        tournamentGap.setVisible(visible);
        revalidate();
        repaint();
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
        if (idx < 0) return;
        SwingUtilities.invokeLater(() -> {
            Color rankColor = RankUtils.getRankColor(rankLabel);

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

            progressBars[idx].setValue((int) pct);
            progressBars[idx].setString(Math.round(pct) + "%");
            progressBars[idx].setForeground(rankColor);

            if (streak >= 0 && bestStreak >= 0)
            {
                setStreaks(idx, streak, false, bestStreak);
            }
            if (idx == 1)
            {
                setTournamentRowVisible(rankLabel != null && !"—".equals(rankLabel) && !rankLabel.trim().isEmpty());
            }
        });
    }

    public void updateStreak(String bucket, int streak, boolean plus, int bestStreak)
    {
        int idx = getBucketIndex(bucket);
        if (idx < 0) return;
        SwingUtilities.invokeLater(() -> setStreaks(idx, streak, plus, bestStreak));
    }

    private void setStreaks(int idx, int streak, boolean plus, int bestStreak)
    {
        streakLabels[idx].setText(streakText(streak, plus));
        longestLabels[idx].setText(bestText(bestStreak));
        streakLabels[idx].setVisible(streaksShown);
        longestLabels[idx].setVisible(streaksShown);
    }

    /** {@code "Current Winstreak 4"} ({@code "100+"} when the loaded history ran out before a loss). */
    static String streakText(int streak, boolean plus)
    {
        return "Current Winstreak " + Math.max(0, streak) + (plus ? "+" : "");
    }

    /** {@code "Longest Streak 12"}. */
    static String bestText(int bestStreak)
    {
        return "Longest Streak " + Math.max(0, bestStreak);
    }

    public void reset()
    {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < BUCKET_KEYS.length; i++)
            {
                rankLabels[i].setText(" ");
                rankLabels[i].setForeground(UIManager.getColor("Label.foreground"));
                progressBars[i].setValue(0);
                progressBars[i].setString("0%");
                progressBars[i].setForeground(UIManager.getColor("ProgressBar.foreground"));
                streakLabels[i].setText(streakText(0, false));
                streakLabels[i].setVisible(false);
                longestLabels[i].setText(bestText(0));
                longestLabels[i].setVisible(false);
            }
            setTournamentRowVisible(false);
        });
    }

    private static int getBucketIndex(String bucket)
    {
        return Arrays.asList(BUCKET_KEYS).indexOf(bucket == null ? null : bucket.toLowerCase());
    }
}
