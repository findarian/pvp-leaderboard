package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.util.RankUtils;

import javax.swing.*;
import java.awt.*;

public class RankTierPanel extends JPanel
{
    public RankTierPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        buildTierList();
    }

    private void buildTierList()
    {
        String[][] thresholds = RankUtils.THRESHOLDS;

        for (int i = thresholds.length - 1; i >= 0; i--)
        {
            String rankName = thresholds[i][0];
            int division = Integer.parseInt(thresholds[i][1]);

            String displayName = "3rd Age".equals(rankName)
                ? "3rd Age"
                : rankName + " " + division;

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setName("tierRow");
            row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            row.setAlignmentX(LEFT_ALIGNMENT);

            JLabel nameLabel = new JLabel(displayName);
            nameLabel.setName("tierName");
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
            nameLabel.setForeground("3rd Age".equals(rankName) ? Color.WHITE : RankUtils.getRankColor(rankName));
            row.add(nameLabel, BorderLayout.WEST);

            add(row);
        }
    }
}
