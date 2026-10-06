package com.pvp.leaderboard.ui;

import com.google.gson.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.util.*;
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
                    computeRank(match, "player_") + " vs " + computeRank(match, "opponent_"),
                    computeRatingChangePlain(match),
                    match.has("when") ? new SimpleDateFormat("MM/dd/yyyy HH:mm").format(new Date(match.get("when").getAsLong() * 1000)) : ""});
            }
        });
    }

    private static String computeRank(JsonObject match, String prefix)
    {
        String rank = optString(match, prefix + "rank", null);
        if (rank == null) return "Unknown";
        int division = optInt(match, prefix + "division", 0);
        return rank + (division > 0 ? " " + division : "");
    }

    /** The capped label, else the backend's MMR delta, else "-". The backend's
     *  one {@code rating_change} builder ({@code backend/core/match_rating_change.py})
     *  always writes {@code mmr_delta} and omits the object otherwise. */
    private static String computeRatingChangePlain(JsonObject match)
    {
        if (PortalCap.isCapped(match))
        {
            return PortalCap.LABEL;
        }
        double delta = optDouble(optObject(match, "rating_change"), "mmr_delta", Double.NaN);
        return Double.isNaN(delta) ? "-" : String.format("%+.2f MMR", delta);
    }
}
