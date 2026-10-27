package com.pvp.leaderboard.ui;

import com.google.gson.*;
import com.pvp.leaderboard.perf.FightsPanel;
import com.pvp.leaderboard.service.*;
import java.awt.*;
import java.text.*;
import java.util.*;
import javax.swing.*;
import javax.swing.table.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/** The "Popout Match History" table; its dialog has a fixed size and is never packed. */
public class HistoryPanel extends JPanel
{
    private final DefaultTableModel tableModel = new DefaultTableModel(new String[]{"Res", "Opponent", "Type", "Match", "Change", "Time"}, 0);

    public HistoryPanel()
    {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Match History"));

        var table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {40, 140, 60, 220, 180, 160};
        for (int i = 0; i < widths.length; i++)
        {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void setMatches(JsonArray matches)
    {
        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0);
            if (matches == null) return;

            for (JsonElement e : matches)
            {
                JsonObject match = e.getAsJsonObject();
                tableModel.addRow(new Object[]{
                    optString(match, "result"),
                    optString(match, "opponent_id"),
                    match.has("bucket") ? match.get("bucket").getAsString().toUpperCase() : "Unknown",
                    division(match, "v3_to") + " vs " + division(match, "v3_opponent_to"),
                    computeRatingChangePlain(match),
                    match.has("when") ? new SimpleDateFormat("MM/dd/yyyy HH:mm").format(new Date(match.get("when").getAsLong() * 1000)) : ""});
            }
        });
    }

    /** A match row's division text at {@code key}; "-" for none. */
    private static String division(JsonObject match, String key)
    {
        String d = str(match.get(key));
        return d == null || d.trim().isEmpty() ? "-" : d;
    }

    /** The capped label, else the row's {@code v3_change} ("+7.93 MMR", "0.00 MMR"), else "-". */
    private static String computeRatingChangePlain(JsonObject match)
    {
        if (PortalCap.isCapped(match))
        {
            return PortalCap.LABEL;
        }
        String change = FightsPanel.change(match);
        return "-".equals(change) ? change : change + " MMR";
    }
}
