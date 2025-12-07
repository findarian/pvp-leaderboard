package com.pvp.leaderboard.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.ArrayList;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class WinRateChartPanel extends JPanel
{
    private List<Double> winRateHistory = new ArrayList<>();

    public WinRateChartPanel()
    {
        setPreferredSize(new Dimension(1024, 280));
    }

    public void setMatches(JsonArray matches)
    {
        this.winRateHistory = calculateWinRateHistory(matches);
        setPreferredSize(new Dimension(Math.max(240, winRateHistory.size() * 5), 200));
        revalidate();
        repaint();
    }

    private List<Double> calculateWinRateHistory(JsonArray matches)
    {
        List<Double> history = new ArrayList<>();
        if (matches == null || matches.size() == 0) return history;

        // Sort by time ascending
        List<JsonObject> sorted = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) sorted.add(matches.get(i).getAsJsonObject());
        sorted.sort((a, b) -> {
            double ta = a.has("when") ? a.get("when").getAsDouble() : 0;
            double tb = b.has("when") ? b.get("when").getAsDouble() : 0;
            return Double.compare(ta, tb);
        });

        int wins = 0;
        int total = 0;
        for (JsonObject m : sorted)
        {
            String result = m.has("result") ? m.get("result").getAsString().toLowerCase() : "";
            if ("win".equals(result)) wins++;
            if ("win".equals(result) || "loss".equals(result))
            {
                total++;
                if (total >= 10) // Only show after 10 games to avoid noise
                {
                    history.add((double) wins / total * 100.0);
                }
            }
        }
        return history;
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth() - 40;
        int height = getHeight() - 40;

        if (width <= 0 || height <= 0) return;

        // Draw axes
        g2.setColor(Color.LIGHT_GRAY);
        g2.drawLine(20, height + 20, width + 20, height + 20);
        g2.drawLine(20, 20, 20, height + 20);

        // Draw grid lines and Y-axis labels
        g2.setColor(Color.GRAY);
        for (int i = 0; i <= 10; i++)
        {
            int y = 20 + (i * height / 10);
            g2.drawLine(20, y, width + 20, y);
            g2.setColor(Color.WHITE);
            g2.drawString((100 - i * 10) + "%", 2, y + 5);
            g2.setColor(Color.GRAY);
        }

        // Draw X-axis labels and win rate line
        if (winRateHistory != null && winRateHistory.size() > 1)
        {
            // Draw X-axis labels (match numbers)
            g2.setColor(Color.WHITE);
            int maxTicks = Math.min(8, winRateHistory.size());
            for (int i = 0; i < maxTicks; i++)
            {
                int x = 20 + (i * width / (maxTicks - 1));
                int matchNum = winRateHistory.size() - (i * winRateHistory.size() / (maxTicks - 1));
                g2.drawString("#" + matchNum, x - 10, height + 35);
            }

            // Draw win rate line
            g2.setColor(new Color(255, 215, 0)); // Gold color
            g2.setStroke(new BasicStroke(2));
            for (int i = 0; i < winRateHistory.size() - 1; i++)
            {
                int x1 = 20 + (i * width / (winRateHistory.size() - 1));
                int y1 = height + 20 - (int)(winRateHistory.get(i) * height / 100);
                int x2 = 20 + ((i + 1) * width / (winRateHistory.size() - 1));
                int y2 = height + 20 - (int)(winRateHistory.get(i + 1) * height / 100);
                g2.drawLine(x1, y1, x2, y2);
            }
        }
        else
        {
            g2.setColor(Color.GRAY);
            g2.drawString("No match data available", width / 2 - 60, height / 2);
        }
    }
}
