package com.pvp.leaderboard.ui;

import javax.swing.*;
import java.awt.*;

public class RankProgressPanel extends JPanel
{
    private static final int SIDEBAR_SCROLLBAR_RESERVE_PX = 16;
    private static final int PROGRESS_BAR_WIDTH = 200;
    private static final int PROGRESS_BAR_HEIGHT = 16;

    private final JProgressBar[] progressBars;
    private final JLabel[] progressLabels;
    private final String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};

    public RankProgressPanel()
    {
        progressBars = new JProgressBar[5];
        progressLabels = new JLabel[5];

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Season 0"));

        initUI();
    }

    private void initUI()
    {
        for (int i = 0; i < buckets.length; i++)
        {
            JPanel bucketPanel = new JPanel(new BorderLayout());
            bucketPanel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, SIDEBAR_SCROLLBAR_RESERVE_PX));
            bucketPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            
            progressLabels[i] = new JLabel(buckets[i] + " - — (0.0%) ");
            progressLabels[i].setFont(progressLabels[i].getFont().deriveFont(Font.BOLD));
            bucketPanel.add(progressLabels[i], BorderLayout.NORTH);
            
            progressBars[i] = new JProgressBar(0, 100);
            progressBars[i].setValue(0);
            progressBars[i].setStringPainted(true);
            progressBars[i].setString("0%");
            progressBars[i].setPreferredSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setMinimumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setMaximumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            
            JPanel barRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            barRow.setOpaque(false);
            barRow.add(progressBars[i]);
            bucketPanel.add(barRow, BorderLayout.CENTER);
            
            add(bucketPanel);
            if (i < buckets.length - 1) add(Box.createVerticalStrut(8));
        }
    }

    public void updateBucket(String bucket, String rankLabel, int division, double pct, int rankNumber)
    {
        int idx = getBucketIndex(bucket);
        if (idx >= 0 && idx < progressBars.length)
        {
            SwingUtilities.invokeLater(() -> {
                if (progressLabels[idx] != null)
                {
                    String displayRank = rankLabel + (division > 0 ? " " + division : "");
                    String text = buckets[idx] + " - " + displayRank + " (" + Math.round(pct) + "%)";
                    if (rankNumber > 0)
                    {
                        text += " #" + rankNumber;
                    }
                    progressLabels[idx].setText(text);
                }
                if (progressBars[idx] != null)
                {
                    progressBars[idx].setValue((int) pct);
                    progressBars[idx].setString(Math.round(pct) + "%");
                }
            });
        }
    }
    
    public void reset()
    {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < progressLabels.length; i++)
            {
                if (progressLabels[i] != null)
                {
                    progressLabels[i].setText(buckets[i] + " - — (0.0%)");
                }
                if (progressBars[i] != null)
                {
                    progressBars[i].setValue(0);
                    progressBars[i].setString("0%");
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

