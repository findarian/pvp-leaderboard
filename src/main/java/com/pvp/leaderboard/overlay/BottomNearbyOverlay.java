package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.PvPLeaderboardPlugin;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("deprecation")
public class BottomNearbyOverlay extends Overlay
{
    private final Client client;
    private final PvPLeaderboardPlugin plugin;
    private final PvPLeaderboardConfig config;
    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public BottomNearbyOverlay(Client client, PvPLeaderboardPlugin plugin, PvPLeaderboardConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setMovable(true);
        setSnappable(true);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showBottomNearbyOverlay())
        {
            return null;
        }

        panelComponent.getChildren().clear();

        int fontSize = Math.max(9, config.nearbyOverlayFontSize());
        Font base = graphics.getFont().deriveFont(Font.BOLD, (float) fontSize);
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("Worst Players Nearby")
            .color(Color.YELLOW)
            .build());
        // Include self; no need to track separately

        List<PlayerEntry> entries = new ArrayList<>();
        for (Player p : client.getPlayers())
        {
            if (p == null) continue;
            String pname = p.getName();
            if (pname == null) continue;
            // Include the local player as well
            String rank = plugin.getDisplayedRankFor(pname);
            if (rank == null) continue;
            entries.add(new PlayerEntry(pname, rank, rankOrder(rank)));
        }

        // Bottom N
        List<PlayerEntry> sortedBottom = new ArrayList<>(entries);
        sortedBottom.sort(Comparator.comparingInt((PlayerEntry e) -> e.order).thenComparing(e -> e.name));
        int shown = 0;
        int bottomLimit = Math.max(1, Math.min(10, config.bottomNearbyCount()));
        for (PlayerEntry e : sortedBottom)
        {
            LineComponent line = LineComponent.builder()
                .left(e.name + ":")
                .right(e.rank)
                .leftColor(Color.WHITE)
                .rightColor(getRankColor(e.rank))
                .build();
            line.setLeftFont(base);
            line.setRightFont(base);
            panelComponent.getChildren().add(line);
            shown++;
            if (shown >= bottomLimit) break;
        }

        int width = Math.max(140, config.nearbyOverlayWidth());
        panelComponent.setPreferredSize(new Dimension(width, 0));
        return panelComponent.render(graphics);
    }

    private static class PlayerEntry
    {
        final String name;
        final String rank;
        final int order;
        PlayerEntry(String name, String rank, int order)
        {
            this.name = name;
            this.rank = rank;
            this.order = order;
        }
    }

    private int rankOrder(String fullRank)
    {
        if (fullRank == null || fullRank.isEmpty()) return -1;
        String[] parts = fullRank.split(" ");
        String base = parts.length > 0 ? parts[0] : fullRank;
        int division = 0;
        try { division = parts.length > 1 ? Integer.parseInt(parts[1]) : 0; } catch (Exception ignore) {}

        int tier;
        switch (base)
        {
            case "Bronze": tier = 0; break;
            case "Iron": tier = 1; break;
            case "Steel": tier = 2; break;
            case "Black": tier = 3; break;
            case "Mithril": tier = 4; break;
            case "Adamant": tier = 5; break;
            case "Rune": tier = 6; break;
            case "Dragon": tier = 7; break;
            case "3rd":
            case "3rd Age": tier = 8; break;
            default: tier = -1; break;
        }
        return tier * 10 + (4 - division);
    }

    private Color getRankColor(String fullRank)
    {
        String base = fullRank == null ? null : fullRank.split(" ")[0];
        if (base == null) return Color.WHITE;
        String baseLower = base.toLowerCase();
        switch (baseLower)
        {
            case "bronze": return new Color(184, 115, 51);
            case "iron": return new Color(192, 192, 192);
            case "steel": return new Color(154, 162, 166);
            case "black": return new Color(46, 46, 46);
            case "mithril": return new Color(59, 167, 214);
            case "adamant": return new Color(26, 139, 111);
            case "rune": return new Color(78, 159, 227);
            case "dragon": return new Color(229, 57, 53);
            case "3rd":
            case "3rd age": return new Color(229, 193, 0);
            default: return Color.WHITE;
        }
    }
}
