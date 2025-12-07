package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class PerformanceStatsPanel extends JPanel
{
    private JLabel winPercentLabel;
    private JLabel tiesLabel;
    private JLabel kdLabel;
    private JLabel killsLabel;
    private JLabel deathsLabel;
    
    private DefaultTableModel rankBreakdownModel;
    private JTable rankBreakdownTable;

    public PerformanceStatsPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Last 100 Game Performance"));
        
        initUI();
    }

    private void initUI()
    {
        JPanel summaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        winPercentLabel = new JLabel("- % Winrate");
        kdLabel = new JLabel("KD:");
        killsLabel = new JLabel("K:");
        deathsLabel = new JLabel("D:");
        tiesLabel = new JLabel("Ties:");
        
        Font baseFont = winPercentLabel.getFont();
        Font small = baseFont.deriveFont(Font.PLAIN, Math.max(10f, baseFont.getSize2D() - 1f));
        winPercentLabel.setFont(small);
        kdLabel.setFont(small);
        killsLabel.setFont(small);
        deathsLabel.setFont(small);
        tiesLabel.setFont(small);
        
        summaryRow.add(winPercentLabel);
        summaryRow.add(Box.createHorizontalStrut(6));
        summaryRow.add(kdLabel);
        summaryRow.add(Box.createHorizontalStrut(6));
        summaryRow.add(killsLabel);
        summaryRow.add(new JLabel(" | "));
        summaryRow.add(deathsLabel);
        summaryRow.add(Box.createHorizontalStrut(6));
        summaryRow.add(tiesLabel);
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
        JScrollPane sp = new JScrollPane(rankBreakdownTable);
        sp.setPreferredSize(new Dimension(0, 150)); // Adjusted height
        
        // Wrap in panel to control sizing better if needed
        JPanel p = new JPanel(new BorderLayout());
        p.add(sp, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(0, 340)); // Match original preferred size
        return p;
    }

    public void updateStats(int wins, int losses, int ties)
    {
        SwingUtilities.invokeLater(() -> {
            int total = wins + losses + ties;
            double winRate = total > 0 ? (double) wins / total * 100 : 0;
            
            winPercentLabel.setText(String.format("%.1f%% Winrate", winRate));
            tiesLabel.setText("Ties: " + ties);
            kdLabel.setText("KD: -");
            killsLabel.setText("K: -");
            deathsLabel.setText("D: -");
        });
    }

    public void updateBreakdown(JsonArray matches)
    {
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
        updateStats(0, 0, 0);
        SwingUtilities.invokeLater(() -> {
            if (rankBreakdownModel != null) rankBreakdownModel.setRowCount(0);
        });
    }
}

