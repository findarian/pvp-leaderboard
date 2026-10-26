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
    private final JLabel totalsLabel = new JLabel("K: - D: - Winrate -%");
    private final JPanel bucketPicker;

    private final DefaultTableModel rankModel = new DefaultTableModel(new String[]{"Rank", "Wins", "Deaths", "KD"}, 0);

    private String currentBucket = "overall";

    /** All-time wins/losses/ties per bucket from {@code /user} →
     *  {@code cumulative_stats}. Source of truth for the K/D summary
     *  row — matches the website's Performance Overview. */
    private Map<String, int[]> bucketTotals;

    // Opponent divisions per bucket: bucket -> (division -> [kills, deaths])
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
     * The rank breakdown from a /user profile: each bucket's v3 card {@code opp} rows
     * ({@code division}, {@code kills}, {@code deaths}); a row without a division, or with neither a kill nor a
     * death, is skipped.
     */
    public void setOppRanks(JsonObject profile)
    {
        oppRankStats = new HashMap<>();
        for (String label : BUCKETS)
        {
            String bucket = label.toLowerCase(Locale.ROOT);
            Map<String, int[]> rows = new HashMap<>();
            for (JsonElement e : optArray(RankUtils.card(profile, bucket), "opp"))
            {
                JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : null;
                String division = RankUtils.division(o);
                int kills = optInt(o, "kills", 0);
                int deaths = optInt(o, "deaths", 0);
                if (division != null && (kills > 0 || deaths > 0)) rows.put(division, new int[]{kills, deaths});
            }
            oppRankStats.put(bucket, rows);
        }
        updateRanks();
    }

    /** The rank breakdown table for the current bucket, highest division first (unknown ones last). */
    private void updateRanks()
    {
        invokeLater(() ->
        {
            rankModel.setRowCount(0);
            Map<String, int[]> stats = oppRankStats == null ? Collections.emptyMap()
                : oppRankStats.getOrDefault(currentBucket, Collections.emptyMap());
            List<String> keys = new ArrayList<>(stats.keySet());
            List<String> order = Arrays.asList(RankUtils.SLOTS);
            keys.sort(Comparator.comparingInt(k -> -order.indexOf(k)));
            for (String key : keys)
            {
                int[] s = stats.get(key);
                String kd = s[1] > 0 ? String.format("%.2f", (double) s[0] / s[1]) : String.valueOf(s[0]);
                rankModel.addRow(new Object[]{key, s[0], s[1], kd});
            }
        });
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
