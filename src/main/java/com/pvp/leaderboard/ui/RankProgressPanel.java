package com.pvp.leaderboard.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class RankProgressPanel extends JPanel
{
    private static final int SIDEBAR_SCROLLBAR_RESERVE_PX = 16;
    private static final int PROGRESS_BAR_WIDTH = 200;
    private static final int PROGRESS_BAR_HEIGHT = 16;

    private static final Map<String, Color> RANK_COLORS = new HashMap<>();
    static
    {
        RANK_COLORS.put("Bronze", new Color(0xB8, 0x73, 0x33));
        RANK_COLORS.put("Iron", new Color(0xC0, 0xC0, 0xC0));
        RANK_COLORS.put("Steel", new Color(0x9A, 0xA2, 0xA6));
        RANK_COLORS.put("Black", new Color(0x6A, 0x6A, 0x6A));
        RANK_COLORS.put("Mithril", new Color(0x3B, 0xA7, 0xD6));
        RANK_COLORS.put("Adamant", new Color(0x1A, 0x8B, 0x6F));
        RANK_COLORS.put("Rune", new Color(0x4E, 0x9F, 0xE3));
        RANK_COLORS.put("Dragon", new Color(0xE5, 0x39, 0x35));
        RANK_COLORS.put("3rd Age", new Color(0xE5, 0xC1, 0x00));
    }

    private static Color getRankColor(String rankName)
    {
        if (rankName == null) return Color.GRAY;
        String base = rankName.split(" ")[0];
        if ("3rd".equals(base)) return RANK_COLORS.getOrDefault("3rd Age", Color.GRAY);
        return RANK_COLORS.getOrDefault(base, Color.GRAY);
    }

    private final JProgressBar[] progressBars;
    private final JLabel[] bucketNameLabels;
    private final JLabel[] rankLabels;
    private final String[] buckets = {"Overall Rating", "NH Rating", "Veng Rating", "Multi Rating", "DMM Rating"};

    public RankProgressPanel()
    {
        progressBars = new JProgressBar[5];
        bucketNameLabels = new JLabel[5];
        rankLabels = new JLabel[5];

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        initUI();
    }

    private void initUI()
    {
        for (int i = 0; i < buckets.length; i++)
        {
            JPanel bucketPanel = new JPanel();
            bucketPanel.setLayout(new BoxLayout(bucketPanel, BoxLayout.Y_AXIS));
            bucketPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, SIDEBAR_SCROLLBAR_RESERVE_PX));
            bucketPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            bucketPanel.setAlignmentX(LEFT_ALIGNMENT);
            
            bucketNameLabels[i] = new JLabel(buckets[i]);
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
            
            add(bucketPanel);
            if (i < buckets.length - 1) add(Box.createVerticalStrut(14));
        }
    }

    public void updateBucket(String bucket, String rankLabel, int division, double pct, int rankNumber)
    {
        int idx = getBucketIndex(bucket);
        if (idx >= 0 && idx < progressBars.length)
        {
            SwingUtilities.invokeLater(() -> {
                Color rankColor = getRankColor(rankLabel);
                
                if (rankLabels[idx] != null)
                {
                    String displayRank = rankLabel + (division > 0 ? " " + division : "");
                    if (rankNumber > 0)
                    {
                        displayRank += " #" + rankNumber;
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
            });
        }
    }
    
    public void reset()
    {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < buckets.length; i++)
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
            }
        });
    }

    private int getBucketIndex(String bucket)
    {
        if (bucket == null) return -1;
        String b = bucket.toLowerCase();
        if ("overall".equals(b)) return 0;
        if ("nh".equals(b)) return 1;
        if ("veng".equals(b)) return 2;
        if ("multi".equals(b)) return 3;
        if ("dmm".equals(b)) return 4;
        return -1;
    }
}

