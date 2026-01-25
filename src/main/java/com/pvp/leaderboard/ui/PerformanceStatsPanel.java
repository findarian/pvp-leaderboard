package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PerformanceStatsPanel extends JPanel
{
    private JLabel winPercentLabel;
    private JLabel cumulativeStatsLabel;
    private JPanel bucketSelectorPanel;
    
    private DefaultTableModel rankBreakdownModel;
    private JTable rankBreakdownTable;
    
    // Per-bucket cumulative stats: bucket -> [kills, deaths, ties]
    private Map<String, int[]> bucketStats;
    private String currentBucket = "overall";
    
    // Cumulative opponent rank stats per bucket: bucket -> (rank -> [wins, losses])
    private Map<String, Map<String, int[]>> opponentRankStatsByBucket;

    public PerformanceStatsPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Last 100 Game Performance"));
        
        initUI();
    }

    private void initUI()
    {
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) baseFont = new Font("SansSerif", Font.PLAIN, 12);
        Font small = baseFont.deriveFont(Font.PLAIN, Math.max(10f, baseFont.getSize2D() - 1f));
        
        // Bucket selector row
        bucketSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};
        for (String b : buckets) {
            JButton btn = new JButton(b);
            btn.setFont(small);
            btn.setMargin(new Insets(2, 6, 2, 6));
            final String bucketKey = b.toLowerCase();
            btn.addActionListener(e -> setBucket(bucketKey));
            // Highlight the default (Overall)
            if ("overall".equals(bucketKey)) {
                btn.setEnabled(false);
            }
            bucketSelectorPanel.add(btn);
        }
        add(bucketSelectorPanel);
        
        // Summary row with cumulative stats and win rate
        JPanel summaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        
        cumulativeStatsLabel = new JLabel("- Kills / - Deaths (- total)");
        cumulativeStatsLabel.setFont(small);
        summaryRow.add(cumulativeStatsLabel);
        
        summaryRow.add(Box.createHorizontalStrut(12));
        
        winPercentLabel = new JLabel("- % Winrate");
        winPercentLabel.setFont(small);
        summaryRow.add(winPercentLabel);
        
        add(summaryRow);

        // Rank breakdown table
        add(createRankBreakdownTable());
    }

    private JPanel createRankBreakdownTable()
    {
        String[] columns = {"Rank", "Wins", "Losses"};
        rankBreakdownModel = new DefaultTableModel(columns, 0);
        rankBreakdownTable = new JTable(rankBreakdownModel);
        rankBreakdownTable.setEnabled(false);
        
        // Set all columns to the same width
        int columnWidth = 60;
        for (int i = 0; i < rankBreakdownTable.getColumnCount(); i++) {
            rankBreakdownTable.getColumnModel().getColumn(i).setPreferredWidth(columnWidth);
            rankBreakdownTable.getColumnModel().getColumn(i).setMinWidth(columnWidth);
            rankBreakdownTable.getColumnModel().getColumn(i).setMaxWidth(columnWidth);
        }
        rankBreakdownTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        
        JScrollPane sp = new JScrollPane(rankBreakdownTable);
        sp.setPreferredSize(new Dimension(0, 150)); // Adjusted height
        
        // Wrap in panel to control sizing better if needed
        JPanel p = new JPanel(new BorderLayout());
        p.add(sp, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(0, 340)); // Match original preferred size
        return p;
    }

    /**
     * Receive all bucket stats from the /user API response.
     * Called from DashboardPanel.loadPlayerStats() after getUserProfile.
     */
    public void setCumulativeStats(JsonObject cumulativeStats)
    {
        bucketStats = new HashMap<>();
        for (String bucket : new String[]{"overall", "nh", "veng", "multi", "dmm"}) {
            if (cumulativeStats != null && cumulativeStats.has(bucket)) {
                JsonObject bs = cumulativeStats.getAsJsonObject(bucket);
                int kills = bs.has("wins") ? bs.get("wins").getAsInt() : 0;
                int deaths = bs.has("losses") ? bs.get("losses").getAsInt() : 0;
                int ties = bs.has("ties") ? bs.get("ties").getAsInt() : 0;
                bucketStats.put(bucket, new int[]{kills, deaths, ties});
            } else {
                bucketStats.put(bucket, new int[]{0, 0, 0});
            }
        }
        updateCumulativeDisplay();
    }
    
    /**
     * Switch to a different bucket for cumulative stats display.
     */
    public void setBucket(String bucket)
    {
        this.currentBucket = bucket;
        updateCumulativeDisplay();
        updateRankBreakdownDisplay();  // Also update opponent rank table for selected bucket
        // Update button states
        SwingUtilities.invokeLater(() -> {
            for (Component c : bucketSelectorPanel.getComponents()) {
                if (c instanceof JButton) {
                    JButton btn = (JButton) c;
                    boolean isSelected = btn.getText().equalsIgnoreCase(bucket);
                    btn.setEnabled(!isSelected);
                }
            }
        });
    }
    
    /**
     * Update the cumulative stats display based on selected bucket.
     */
    private void updateCumulativeDisplay()
    {
        int[] stats = bucketStats != null ? bucketStats.getOrDefault(currentBucket, new int[]{0, 0, 0}) : new int[]{0, 0, 0};
        int kills = stats[0];
        int deaths = stats[1];
        int ties = stats[2];
        int total = kills + deaths + ties;  // Include ties in denominator
        double winRate = total > 0 ? (double) kills / total * 100 : 0;
        
        SwingUtilities.invokeLater(() -> {
            cumulativeStatsLabel.setText(String.format("%d Kills / %d Deaths (%d total)", kills, deaths, total));
            winPercentLabel.setText(String.format("%.1f%% Winrate", winRate));
        });
    }

    /**
     * @deprecated Use setCumulativeStats() instead. Kept for backward compatibility.
     */
    @Deprecated
    public void updateStats(int wins, int losses, int ties)
    {
        // No longer used - cumulative stats come from API now
    }

    /**
     * Helper method to parse rank stats from a JsonObject containing rank -> {wins, losses} entries.
     */
    private Map<String, int[]> parseRankStats(JsonObject rankData)
    {
        Map<String, int[]> result = new HashMap<>();
        if (rankData == null) return result;
        for (String key : rankData.keySet()) {
            try {
                JsonObject rs = rankData.getAsJsonObject(key);
                int wins = rs.has("wins") ? rs.get("wins").getAsInt() : 0;
                int losses = rs.has("losses") ? rs.get("losses").getAsInt() : 0;
                if (wins > 0 || losses > 0) {
                    result.put(key, new int[]{wins, losses});
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Receive opponent rank stats from the /user API response.
     * Called from DashboardPanel.loadPlayerStats() after getUserProfile.
     * Updates the rank breakdown table with all-time stats per opponent tier+division.
     * 
     * Handles two formats:
     * - New format: nested by bucket {"overall": {...}, "nh": {...}, ...}
     * - Old format: flat {rank -> stats} (wrapped in "overall" for all buckets)
     */
    public void setOpponentRankStats(JsonObject opponentStats)
    {
        opponentRankStatsByBucket = new HashMap<>();
        
        if (opponentStats != null) {
            // Detect new format: has "overall" key with nested object
            if (opponentStats.has("overall") && 
                opponentStats.get("overall").isJsonObject()) {
                // New format: parse each bucket
                for (String bucket : opponentStats.keySet()) {
                    try {
                        JsonObject bucketData = opponentStats.getAsJsonObject(bucket);
                        opponentRankStatsByBucket.put(bucket, parseRankStats(bucketData));
                    } catch (Exception ignored) {}
                }
            } else {
                // Old format: wrap in "overall" and copy to all buckets
                Map<String, int[]> overall = parseRankStats(opponentStats);
                for (String b : new String[]{"overall", "nh", "veng", "multi", "dmm"}) {
                    opponentRankStatsByBucket.put(b, overall);
                }
            }
        }
        updateRankBreakdownDisplay();
    }
    
    /**
     * Update the rank breakdown table from cumulative opponent rank stats for current bucket.
     * Sorts by rank tier (highest first) then by division (1, 2, 3).
     */
    private void updateRankBreakdownDisplay()
    {
        SwingUtilities.invokeLater(() -> {
            if (rankBreakdownModel == null) return;
            rankBreakdownModel.setRowCount(0);
            
            // Get stats for current bucket, fallback to overall
            Map<String, int[]> currentStats = Collections.emptyMap();
            if (opponentRankStatsByBucket != null) {
                currentStats = opponentRankStatsByBucket.getOrDefault(
                    currentBucket,
                    opponentRankStatsByBucket.getOrDefault("overall", Collections.emptyMap())
                );
            }
            
            if (currentStats.isEmpty()) return;
            
            // Rank order for sorting (highest first)
            final String[] RANK_ORDER = {"3rd Age", "Dragon", "Rune", "Adamant", "Mithril", "Black", "Steel", "Iron", "Bronze"};
            java.util.Map<String, Integer> rankIndex = new HashMap<>();
            for (int i = 0; i < RANK_ORDER.length; i++) {
                rankIndex.put(RANK_ORDER[i], i);
            }
            
            // Sort keys by rank tier then division
            java.util.List<String> sortedKeys = new java.util.ArrayList<>(currentStats.keySet());
            sortedKeys.sort((a, b) -> {
                String[] partsA = a.split(" ");
                String[] partsB = b.split(" ");
                String rankA = partsA[0].equals("3rd") ? "3rd Age" : partsA[0];
                String rankB = partsB[0].equals("3rd") ? "3rd Age" : partsB[0];
                int idxA = rankIndex.getOrDefault(rankA, 999);
                int idxB = rankIndex.getOrDefault(rankB, 999);
                if (idxA != idxB) return idxA - idxB;
                // Same rank tier, sort by division (1, 2, 3)
                int divA = partsA.length > 1 ? Integer.parseInt(partsA[partsA.length - 1]) : 0;
                int divB = partsB.length > 1 ? Integer.parseInt(partsB[partsB.length - 1]) : 0;
                return divA - divB;
            });
            
            for (String key : sortedKeys) {
                int[] stats = currentStats.get(key);
                if (stats != null && (stats[0] > 0 || stats[1] > 0)) {
                    rankBreakdownModel.addRow(new Object[]{key, stats[0], stats[1]});
                }
            }
        });
    }

    /**
     * @deprecated Use setOpponentRankStats() instead. Kept for backward compatibility.
     * Update rank breakdown table from match history.
     */
    @Deprecated
    public void updateBreakdown(JsonArray matches)
    {
        // Only use if opponentRankStatsByBucket not set (legacy fallback)
        if (opponentRankStatsByBucket != null && !opponentRankStatsByBucket.isEmpty()) {
            return;
        }
        
        SwingUtilities.invokeLater(() -> {
            if (rankBreakdownModel == null || matches == null) return;
            rankBreakdownModel.setRowCount(0);
            
            Map<String, int[]> stats = new HashMap<>();
            for (int i = 0; i < matches.size(); i++)
            {
                JsonObject m = matches.get(i).getAsJsonObject();
                String result = m.has("result") ? m.get("result").getAsString().toLowerCase() : "";
                String oppRank = m.has("opponent_rank") ? m.get("opponent_rank").getAsString() : "Unranked";
                
                stats.putIfAbsent(oppRank, new int[]{0, 0});
                if ("win".equals(result)) stats.get(oppRank)[0]++;
                else if ("loss".equals(result)) stats.get(oppRank)[1]++;
            }
            
            for (Map.Entry<String, int[]> e : stats.entrySet())
            {
                rankBreakdownModel.addRow(new Object[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
            }
        });
    }

    public void reset()
    {
        bucketStats = null;
        opponentRankStatsByBucket = null;
        currentBucket = "overall";
        SwingUtilities.invokeLater(() -> {
            cumulativeStatsLabel.setText("- Kills / - Deaths (- total)");
            winPercentLabel.setText("- % Winrate");
            if (rankBreakdownModel != null) rankBreakdownModel.setRowCount(0);
            // Reset button states
            for (Component c : bucketSelectorPanel.getComponents()) {
                if (c instanceof JButton) {
                    JButton btn = (JButton) c;
                    btn.setEnabled(!"Overall".equalsIgnoreCase(btn.getText()));
                }
            }
        });
    }
}

