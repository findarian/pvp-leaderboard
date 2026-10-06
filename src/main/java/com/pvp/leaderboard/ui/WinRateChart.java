package com.pvp.leaderboard.ui;

import com.google.gson.*;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import java.util.List;
import static com.pvp.leaderboard.util.JsonLenient.*;

/** The Advanced Stats win-rate line. Its size is the dashboard's (200 px
 *  high, the column's width), whatever the number of matches. */
public class WinRateChart extends JPanel
{
    private List<Double> winRates = new ArrayList<>();

    public void setMatches(JsonArray matches)
    {
        winRates = winRatesOf(matches);
        repaint();
    }

    private List<Double> winRatesOf(JsonArray matches)
    {
        List<Double> history = new ArrayList<>();
        if (matches == null || matches.size() == 0) return history;

        // Sort by time ascending
        List<JsonObject> sorted = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) sorted.add(matches.get(i).getAsJsonObject());
        sorted.sort(Comparator.comparingDouble(m -> optDouble(m, "when", 0)));

        int wins = 0;
        int total = 0;
        for (JsonObject m : sorted)
        {
            String result = optString(m, "result").toLowerCase();
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
        var g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth() - 40;
        int height = getHeight() - 40;

        if (width <= 0 || height <= 0) return;

        // Draw axes
        g2.setColor(Color.LIGHT_GRAY);
        g2.drawLine(20, height + 20, width + 20, height + 20);
        g2.drawLine(20, 20, 20, height + 20);

        // Draw grid lines and Y-axis labels
        for (int i = 0; i <= 10; i++)
        {
            int y = 20 + (i * height / 10);
            g2.setColor(Color.GRAY);
            g2.drawLine(20, y, width + 20, y);
            g2.setColor(Color.WHITE);
            g2.drawString((100 - i * 10) + "%", 2, y + 5);
        }

        // Draw X-axis labels and win rate line
        if (winRates.size() > 1)
        {
            // Draw win rate line
            g2.setColor(new Color(0xffd700)); // Gold color
            g2.setStroke(new BasicStroke(2));
            for (int i = 0; i < winRates.size() - 1; i++)
            {
                int x1 = 20 + (i * width / (winRates.size() - 1));
                int y1 = height + 20 - (int)(winRates.get(i) * height / 100);
                int x2 = 20 + ((i + 1) * width / (winRates.size() - 1));
                int y2 = height + 20 - (int)(winRates.get(i + 1) * height / 100);
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
