package com.pvp.leaderboard.ui;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import javax.swing.table.*;
import java.util.List;
import static com.pvp.leaderboard.ui.Ui.*;
import static com.pvp.leaderboard.util.JsonLenient.*;
import static javax.swing.SwingUtilities.*;

public class PerfStats extends JPanel
{
    /** Opponent tiers, highest first: the rank breakdown's sort order. */
    private static final List<String> TIERS = Arrays.asList("3rd Age", "Dragon", "Rune", "Adamant", "Mithril", "Black", "Steel", "Iron", "Bronze");

    private final JLabel totalsLabel = new JLabel("K: - D: - Winrate -%");
    private final JPanel bucketPicker;

    private final DefaultTableModel rankModel = new DefaultTableModel(new String[]{"Rank", "Wins", "Deaths", "KD"}, 0);

    private String currentBucket = "overall";

    /** All-time wins/losses/ties per bucket from {@code /user} →
     *  {@code cumulative_stats}. Source of truth for the K/D summary
     *  row — matches the website's {@code updatePerformanceOverviewDisplay}. */
    private Map<String, int[]> bucketTotals;

    // Cumulative opponent rank stats per bucket: bucket -> (rank -> [wins, losses])
    private Map<String, Map<String, int[]>> oppRankStats;

    public PerfStats()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Performance Breakdown"));

        // Three rows of two: every label, "Tournament" included, shows in full.
        bucketPicker = bucketBar("performance-bucket-selector", null, 3, 2, BUCKETS, this::setBucket);
        styleBucketBar(bucketPicker, "overall");
        maxH(bucketPicker, Math.max(90, bucketPicker.getPreferredSize().height));
        add(bucketPicker);

        // Summary row
        var summaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        totalsLabel.setFont(smallFont());
        summaryRow.add(totalsLabel);
        add(summaryRow);

        // Rank breakdown table
        add(createRanks());
    }

    private JPanel createRanks()
    {
        var table = new JTable(rankModel);
        table.setEnabled(false);
        for (int i = 0; i < 4; i++)
        {
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 90 : 45);
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        // The wrapper's size wins; the scroll pane fills it.
        var p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(table));
        p.setPreferredSize(new Dimension(0, 340));
        return p;
    }

    /**
     * Switch to a different bucket for display.
     */
    public void setBucket(String bucket)
    {
        currentBucket = bucket;
        updateTotals();
        updateRanks();
        invokeLater(() -> styleBucketBar(bucketPicker, bucket));
    }

    /**
     * Receive all-time wins/losses/ties from {@code /user} →
     * {@code cumulative_stats}. Drives the K/D summary label for the
     * selected bucket. Matches the website's performance overview.
     */
    public void setTotals(JsonObject totalStats)
    {
        bucketTotals = parseTotals(totalStats);
        updateTotals();
    }

    private static Map<String, int[]> parseTotals(JsonObject totalStats)
    {
        Map<String, int[]> out = new HashMap<>();
        if (totalStats == null) return out;
        for (String bucket : totalStats.keySet())
        {
            JsonObject b = optObject(totalStats, bucket);
            if (b != null)
            {
                out.put(bucket.toLowerCase(), new int[]{optInt(b, "wins", 0),
                    optInt(b, "losses", 0), optInt(b, "ties", 0)});
            }
        }
        return out;
    }

    /**
     * Update the K/D summary from {@link #bucketTotals} (the
     * selected bucket, else Overall), falling back to summing
     * {@link #oppRankStats} when cumulative data is absent
     * (legacy /user payloads).
     */
    private void updateTotals()
    {
        int kills = 0, deaths = 0, ties = 0;
        if (bucketTotals != null && !bucketTotals.isEmpty())
        {
            int[] row = bucketTotals.getOrDefault(currentBucket, bucketTotals.get("overall"));
            if (row != null)
            {
                kills = row[0];
                deaths = row[1];
                ties = row[2];
            }
        }
        else if (oppRankStats != null)
        {
            for (int[] v : oppRankStats.getOrDefault(currentBucket, Collections.emptyMap()).values())
            {
                kills += v[0];
                deaths += v[1];
            }
        }
        int total = kills + deaths + ties;
        String text = String.format("K: %d D: %d Winrate %.1f%%", kills, deaths, total > 0 ? (double) kills / total * 100 : 0);
        invokeLater(() -> totalsLabel.setText(text));
    }

    /**
     * Rank -> [wins, losses] from a JsonObject of rank -> {wins, losses}
     * entries; only ranks with a win or a loss are kept.
     */
    private static Map<String, int[]> parseRanks(JsonObject rankData)
    {
        Map<String, int[]> result = new HashMap<>();
        for (String key : rankData.keySet())
        {
            JsonObject rs = optObject(rankData, key);
            int wins = optInt(rs, "wins", 0);
            int losses = optInt(rs, "losses", 0);
            if (wins > 0 || losses > 0)
            {
                result.put(key, new int[]{wins, losses});
            }
        }
        return result;
    }

    /**
     * Receive opponent rank stats from the /user API response.
     * Called from Dashboard.loadStats() after getProfile.
     * Updates the rank breakdown table with all-time stats per opponent tier+division.
     *
     * Handles two formats:
     * - New format: nested by bucket {"overall": {...}, "nh": {...}, ...}
     * - Old format: flat {rank -> stats} (wrapped in "overall" for all buckets)
     */
    public void setOppRanks(JsonObject opponentStats)
    {
        oppRankStats = new HashMap<>();
        if (opponentStats != null)
        {
            if (optObject(opponentStats, "overall") != null)
            {
                for (String bucket : opponentStats.keySet())
                {
                    JsonObject bucketData = optObject(opponentStats, bucket);
                    if (bucketData != null) oppRankStats.put(bucket, parseRanks(bucketData));
                }
            }
            else
            {
                Map<String, int[]> overall = parseRanks(opponentStats);
                for (String b : ExtraStats.bucketKeys())
                {
                    oppRankStats.put(b, overall);
                }
            }
        }
        updateRanks();
    }

    /**
     * Update the rank breakdown table from cumulative opponent rank stats for current bucket.
     * Sorts by rank tier (highest first, unknown tiers last) then by division
     * (1, 2, 3); the division is read only to break a tie.
     */
    private void updateRanks()
    {
        invokeLater(() ->
        {
            rankModel.setRowCount(0);
            Map<String, int[]> stats = oppRankStats == null ? Collections.emptyMap()
                : oppRankStats.getOrDefault(currentBucket, Collections.emptyMap());
            List<String> keys = new ArrayList<>(stats.keySet());
            keys.sort(Comparator.comparingInt(PerfStats::tierIndex).thenComparingInt(PerfStats::division));
            for (String key : keys)
            {
                int[] s = stats.get(key);
                String kd = s[1] > 0 ? String.format("%.2f", (double) s[0] / s[1]) : String.valueOf(s[0]);
                rankModel.addRow(new Object[]{key, s[0], s[1], kd});
            }
        });
    }

    private static int tierIndex(String key)
    {
        String family = key.split(" ")[0];
        int i = TIERS.indexOf(family.equals("3rd") ? "3rd Age" : family);
        return i < 0 ? 999 : i;
    }

    private static int division(String key)
    {
        String[] parts = key.split(" ");
        return parts.length > 1 ? Integer.parseInt(parts[parts.length - 1]) : 0;
    }

    public void reset()
    {
        oppRankStats = null;
        bucketTotals = null;
        currentBucket = "overall";
        invokeLater(() ->
        {
            totalsLabel.setText("K: - D: - Winrate -%");
            rankModel.setRowCount(0);
            styleBucketBar(bucketPicker, "overall");
        });
    }
}
