package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.RankUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MatchHistoryPanel extends JPanel
{
    private JTable matchHistoryTable;
    private DefaultTableModel tableModel;

    public MatchHistoryPanel()
    {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Match History"));
        
        createTable();
    }

    private void createTable()
    {
        String[] columns = {"Res", "Opponent", "Type", "Match", "Change", "Time"};
        tableModel = new DefaultTableModel(columns, 0);
        matchHistoryTable = new JTable(tableModel);
        matchHistoryTable.setFillsViewportHeight(true);
        matchHistoryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        try {
            matchHistoryTable.getColumnModel().getColumn(0).setPreferredWidth(40);
            matchHistoryTable.getColumnModel().getColumn(1).setPreferredWidth(140);
            matchHistoryTable.getColumnModel().getColumn(2).setPreferredWidth(60);
            matchHistoryTable.getColumnModel().getColumn(3).setPreferredWidth(220);
            matchHistoryTable.getColumnModel().getColumn(4).setPreferredWidth(180);
            matchHistoryTable.getColumnModel().getColumn(5).setPreferredWidth(120);
        } catch (Exception ignore) {}

        JScrollPane scrollPane = new JScrollPane(matchHistoryTable);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(0, 300));
        add(scrollPane, BorderLayout.CENTER);
    }

    public void setMatches(JsonArray matches)
    {
        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0);
            if (matches == null) return;

            for (int i = 0; i < matches.size(); i++)
            {
                JsonObject match = matches.get(i).getAsJsonObject();
                String result = match.has("result") ? match.get("result").getAsString() : "";
                String opponent = match.has("opponent_id") ? match.get("opponent_id").getAsString() : "";
                String matchType = match.has("bucket") ? match.get("bucket").getAsString().toUpperCase() : "Unknown";
                String playerRank = computeRank(match, "player_");
                String opponentRank = computeRank(match, "opponent_");
                String matchDisplay = playerRank + " vs " + opponentRank;
                String change = computeRatingChangePlain(match);
                String time = match.has("when") ? formatTime(match.get("when").getAsLong()) : "";
                
                tableModel.addRow(new Object[]{result, opponent, matchType, matchDisplay, change, time});
            }
        });
    }
    
    public void clear()
    {
        SwingUtilities.invokeLater(() -> tableModel.setRowCount(0));
    }

    private String computeRank(JsonObject match, String prefix)
    {
        if (match.has(prefix + "rank"))
        {
            String rank = match.get(prefix + "rank").getAsString();
            int division = match.has(prefix + "division") ? match.get(prefix + "division").getAsInt() : 0;
            return rank + (division > 0 ? " " + division : "");
        }
        return "Unknown";
    }

    private String computeRatingChangePlain(JsonObject match)
    {
        if (match.has("rating_change"))
        {
            JsonObject ratingChange = match.getAsJsonObject("rating_change");
            
            // Prefer actual MMR delta from backend when present
            if (ratingChange.has("mmr_delta"))
            {
                double mmrDelta = ratingChange.get("mmr_delta").getAsDouble();
                String mmrText = String.format("%+.2f MMR", mmrDelta);
                return mmrText; // Simplified for plain text view, or add more details if space allows
            }
            
            // Fallback to progress calculation logic if needed, or simplified
            // Reuse logic from DashboardPanel if we want exact same output:
            
            double progressChange = ratingChange.has("progress_change") ? ratingChange.get("progress_change").getAsDouble() : 0;
            
            // Re-implement simplified delta calculation or just show percentage
            // The original logic was quite complex to handle wrapping.
            // Let's copy the essential part or reuse RankUtils if possible.
            // RankUtils doesn't have "computeRatingChangeText".
            
            
            String fromRank = ratingChange.has("from_rank") ? ratingChange.get("from_rank").getAsString() : "";
            String toRank = ratingChange.has("to_rank") ? ratingChange.get("to_rank").getAsString() : "";
            int fromDiv = ratingChange.has("from_division") ? ratingChange.get("from_division").getAsInt() : 0;
            int toDiv = ratingChange.has("to_division") ? ratingChange.get("to_division").getAsInt() : 0;
            
            double playerMmr = match.has("player_mmr") ? match.get("player_mmr").getAsDouble() : 0;
            double afterProg = RankUtils.calculateProgressFromMMR(playerMmr);
            double beforeProg = afterProg - progressChange;
            
            String fromKey = fromRank + "|" + fromDiv;
            String toKey = toRank + "|" + toDiv;
            if (!fromKey.equals(toKey) && Math.abs(progressChange) > 0)
            {
                String result = match.has("result") ? match.get("result").getAsString().toLowerCase() : "";
                int signShouldBe = "win".equals(result) ? 1 : ("loss".equals(result) ? -1 : (int)Math.signum(progressChange));
                if (Math.signum(progressChange) != signShouldBe)
                {
                    beforeProg = afterProg + (100 - Math.abs(progressChange)) * signShouldBe;
                }
            }
            
            beforeProg = Math.max(0, Math.min(100, beforeProg));
            
            int fromIdx = RankUtils.getRankIndex(fromRank, fromDiv);
            int toIdx = RankUtils.getRankIndex(toRank, toDiv);
            double rawDelta = (afterProg - beforeProg) + (toIdx - fromIdx) * 100;
            
            String result = match.has("result") ? match.get("result").getAsString().toLowerCase() : "";
            if ("tie".equals(result)) return "0% change";
            return String.format("%+.2f%% change", rawDelta);
        }
        return "-";
    }

    private String formatTime(long timestamp)
    {
        return new SimpleDateFormat("MM/dd/yyyy, HH:mm:ss").format(new Date(timestamp * 1000));
    }
}

