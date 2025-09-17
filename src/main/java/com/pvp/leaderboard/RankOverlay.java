package com.pvp.leaderboard;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RankOverlay extends Overlay
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final RankCacheService rankCache;
    private final ItemManager itemManager;

    private final ConcurrentHashMap<String, String> displayedRanks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> fetchInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> attemptedLookup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> loggedFetch = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> rankIconCache = new ConcurrentHashMap<>();
    private String lastBucketKey = null;

    public String getCachedRankFor(String playerName)
    {
        return displayedRanks.get(playerName);
    }

    public java.awt.image.BufferedImage resolveRankIcon(String fullRank)
    {
        if (fullRank == null || fullRank.isEmpty())
        {
            return null;
        }
        String[] parts = fullRank.split(" ");
        String rankName = parts.length > 0 ? parts[0] : fullRank;
        return getRankIcon(fullRank, rankName);
    }

    public java.awt.image.BufferedImage resolveUnrankedIcon()
    {
        try
        {
            return getUnrankedIcon(Math.max(10, config.rankIconSize()));
        }
        catch (Exception ignore)
        {
            return null;
        }
    }

    private int offsetX = 0;
    private int offsetY = 0;
    private boolean dragging = false;
    private java.awt.Point dragStart = null;

    @Inject
    public RankOverlay(Client client, PvPLeaderboardConfig config, RankCacheService rankCache, ItemManager itemManager)
    {
        this.client = client;
        this.config = config;
        this.rankCache = rankCache;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Show text if enabled, otherwise show icons

        String currentBucket = bucketKey(config.rankBucket());
        if (lastBucketKey == null || !lastBucketKey.equals(currentBucket))
        {
            displayedRanks.clear();
            fetchInFlight.clear();
            loggedFetch.clear();
            attemptedLookup.clear();
            lastBucketKey = currentBucket;
        }

        for (Player player : client.getPlayers())
        {
            if ((!config.showOwnRank() && player == client.getLocalPlayer()) || player.getName() == null)
            {
                continue;
            }

            String playerName = player.getName();
            String cachedRank = displayedRanks.get(playerName);

            if (cachedRank == null)
            {
                if (attemptedLookup.putIfAbsent(playerName, Boolean.TRUE) == null)
                {
                    if (fetchInFlight.putIfAbsent(playerName, Boolean.TRUE) == null)
                    {
                        rankCache.getPlayerRank(playerName, config.rankBucket()).thenAccept(rank -> {
                            if (rank != null)
                            {
                                displayedRanks.put(playerName, rank);
                                if (loggedFetch.putIfAbsent(playerName, Boolean.TRUE) == null)
                                {
                                    log.info("Fetched rank for {}: {}", playerName, rank);
                                }
                            }
                            else
                            {
                                if (loggedFetch.putIfAbsent(playerName, Boolean.TRUE) == null)
                                {
                                    log.info("No rank found for {} (missing in JSON)", playerName);
                                }
                            }
                            fetchInFlight.remove(playerName);
                        });
                    }
                }
                continue;
            }

            Point nameLocation = player.getCanvasTextLocation(graphics, playerName, player.getLogicalHeight() + 40);
            if (nameLocation != null)
            {
                int iconSize = Math.max(10, config.rankIconSize());
                FontMetrics fm = graphics.getFontMetrics();
                int x;
                int y;
                if (player == client.getLocalPlayer())
                {
                    Point headLoc = player.getCanvasTextLocation(graphics, "", player.getLogicalHeight() + 40);
                    if (headLoc != null)
                    {
                        x = headLoc.getX() - iconSize / 2 + offsetX + config.rankIconOffsetXSelf();
                        y = headLoc.getY() - iconSize - 2 + offsetY + config.rankIconOffsetY();
                    }
                    else
                    {
                        x = nameLocation.getX() - iconSize / 2 + offsetX + config.rankIconOffsetXSelf();
                        y = nameLocation.getY() - fm.getAscent() - iconSize - 2 + offsetY + config.rankIconOffsetY();
                    }
                }
                else
                {
                    int centerX;
                    if (nameLocation != null)
                    {
                        int nameWidth = fm.stringWidth(playerName);
                        centerX = nameLocation.getX() + Math.max(0, nameWidth) / 2 + offsetX + config.rankIconOffsetXOthers();
                        x = centerX - iconSize / 2;
                        y = nameLocation.getY() - fm.getAscent() - iconSize - 2 + offsetY + config.rankIconOffsetY();
                    }
                    else
                    {
                        Point headLoc = player.getCanvasTextLocation(graphics, "", player.getLogicalHeight() + 40);
                        if (headLoc != null)
                        {
                            x = headLoc.getX() - iconSize / 2 + offsetX + config.rankIconOffsetXOthers();
                            y = headLoc.getY() - iconSize - 2 + offsetY + config.rankIconOffsetY();
                        }
                        else
                        {
                            x = nameLocation.getX() - iconSize / 2 + offsetX + config.rankIconOffsetXOthers();
                            y = nameLocation.getY() - fm.getAscent() - iconSize - 2 + offsetY + config.rankIconOffsetY();
                        }
                    }
                }
                if (config.showRankAsText())
                {
                    int centerX = x + iconSize / 2;
                    renderRankText(graphics, cachedRank, centerX, y, Math.max(10, config.rankTextSize()));
                }
                else
                {
                    renderRankSpriteOrFallback(graphics, cachedRank, x, y, iconSize);
                }
            }
        }

        return null;
    }

    public Dimension onMousePressed(MouseEvent mouseEvent)
    {
        if (mouseEvent.getButton() == MouseEvent.BUTTON1)
        {
            dragging = true;
            dragStart = mouseEvent.getPoint();
        }
        return null;
    }

    public Dimension onMouseReleased(MouseEvent mouseEvent)
    {
        dragging = false;
        dragStart = null;
        return null;
    }

    public Dimension onMouseDragged(MouseEvent mouseEvent)
    {
        if (dragging && dragStart != null)
        {
            java.awt.Point current = mouseEvent.getPoint();
            offsetX += current.x - dragStart.x;
            offsetY += current.y - dragStart.y;
            dragStart = current;
        }
        return null;
    }

    private void renderRankSpriteOrFallback(Graphics2D graphics, String rank, int x, int y, int size)
    {
        String[] parts = rank.split(" ");
        String rankName = parts[0];
        BufferedImage icon = getRankIcon(rank, rankName);
        if (icon != null)
        {
            if (config.rankIconWhiteOutline())
            {
                drawWhiteOutline(graphics, icon, x, y, size);
            }
            graphics.drawImage(icon, x, y, size, size, null);
            return;
        }
        // Unranked icon disabled (feature removed); show nothing and fall back to colored circle text abbrev
        Color rankColor = getRankColor(rankName);
        graphics.setColor(rankColor);
        graphics.fillOval(x, y, size, size);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, size - 4)));
        FontMetrics fm = graphics.getFontMetrics();
        String displayText = getRankAbbreviation(rankName);
        int textWidth = fm.stringWidth(displayText);
        int textX = x + (size - textWidth) / 2;
        int textY = y + (size + fm.getAscent() - fm.getDescent()) / 2 - 1;
        graphics.drawString(displayText, textX, textY);
        return;
    }

    private void renderRankText(Graphics2D g, String fullRank, int x, int y, int size)
    {
        if (fullRank == null || fullRank.trim().isEmpty()) return;
        String[] parts = fullRank.split(" ");
        String rankName = parts[0];
        String division = parts.length > 1 ? parts[1] : "";
        String text = division.isEmpty() ? rankName : (rankName + " " + division);

        g.setFont(graphicsFontFromSize(Math.max(10, config.rankTextSize())));
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int textH = fm.getAscent();

        g.setColor(new Color(0, 0, 0, 180));
        for (int dy = -1; dy <= 1; dy++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                if (dx == 0 && dy == 0) continue;
                g.drawString(text, x - textW / 2 + dx, y + textH + dy);
            }
        }

        g.setColor(getRankColor(rankName));
        g.drawString(text, x - textW / 2, y + textH);
    }

    private Font graphicsFontFromSize(int size)
    {
        return new Font(Font.DIALOG, Font.BOLD, size);
    }

    private void drawWhiteOutline(Graphics2D g, BufferedImage img, int x, int y, int size)
    {
        Image scaled = img.getScaledInstance(size, size, Image.SCALE_SMOOTH);
        BufferedImage mask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        mg.drawImage(scaled, 0, 0, null);
        mg.dispose();

        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(Color.WHITE);
        for (int dy = -1; dy <= 1; dy++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                if (dx == 0 && dy == 0) continue;
                for (int py = 0; py < size; py++)
                {
                    for (int px = 0; px < size; px++)
                    {
                        int a = (mask.getRGB(px, py) >>> 24) & 0xFF;
                        if (a > 0)
                        {
                            g.fillRect(x + px + dx, y + py + dy, 1, 1);
                        }
                    }
                }
            }
        }
    }

    private BufferedImage getRankIcon(String fullRank, String rankName)
    {
        BufferedImage cached = rankIconCache.get(rankName);
        if (cached != null)
        {
            return cached;
        }
        Integer itemId = null;
        int division = 0;
        try { String[] partsFull = fullRank.split(" "); if (partsFull.length > 1) division = Integer.parseInt(partsFull[1]); } catch (Exception ignore) {}

        switch (rankName)
        {
            case "Bronze": itemId = divisionToWeapon(ItemID.BRONZE_DAGGER, ItemID.BRONZE_SCIMITAR, ItemID.BRONZE_2H_SWORD, division); break;
            case "Iron": itemId = divisionToWeapon(ItemID.IRON_DAGGER, ItemID.IRON_SCIMITAR, ItemID.IRON_2H_SWORD, division); break;
            case "Steel": itemId = divisionToWeapon(ItemID.STEEL_DAGGER, ItemID.STEEL_SCIMITAR, ItemID.STEEL_2H_SWORD, division); break;
            case "Black": itemId = divisionToWeapon(ItemID.BLACK_DAGGER, ItemID.BLACK_SCIMITAR, ItemID.BLACK_2H_SWORD, division); break;
            case "Mithril": itemId = divisionToWeapon(ItemID.MITHRIL_DAGGER, ItemID.MITHRIL_SCIMITAR, ItemID.MITHRIL_2H_SWORD, division); break;
            case "Adamant": itemId = divisionToWeapon(ItemID.ADAMANT_DAGGER, ItemID.ADAMANT_SCIMITAR, ItemID.ADAMANT_2H_SWORD, division); break;
            case "Rune": itemId = divisionToWeapon(ItemID.RUNE_DAGGER, ItemID.RUNE_SCIMITAR, ItemID.RUNE_2H_SWORD, division); break;
            case "Dragon": itemId = divisionToWeapon(ItemID.DRAGON_DAGGER, ItemID.DRAGON_SCIMITAR, ItemID.DRAGON_2H_SWORD, division); break;
            default: break;
        }
        if (itemId == null && ("3rd Age".equalsIgnoreCase(fullRank) || fullRank.startsWith("3rd ")))
        {
            itemId = ItemID.ARMADYL_GODSWORD;
        }
        if (itemId == null || itemManager == null)
        {
            return null;
        }
        BufferedImage img = itemManager.getImage(itemId);
        if (img != null)
        {
            rankIconCache.put(rankName, img);
        }
        return img;
    }

    private BufferedImage getUnrankedIcon(int size)
    {
        if (itemManager == null) return null;
        try
        {
            return itemManager.getImage(ItemID.HEALER_ICON);
        }
        catch (Exception ignore) {}
        return null;
    }

    private static String bucketKey(PvPLeaderboardConfig.RankBucket bucket)
    {
        if (bucket == null) return "overall";
        switch (bucket)
        {
            case NH: return "nh";
            case VENG: return "veng";
            case MULTI: return "multi";
            case DMM: return "dmm";
            case OVERALL:
            default: return "overall";
        }
    }

    private static Integer divisionToWeapon(int daggerId, int scimId, int twoHId, int division)
    {
        switch (division)
        {
            case 3: return daggerId;
            case 2: return scimId;
            case 1: return twoHId;
            default: return scimId;
        }
    }

    private Color getRankColor(String rank)
    {
        switch (rank)
        {
            case "Bronze": return new Color(184, 115, 51);
            case "Iron": return new Color(192, 192, 192);
            case "Steel": return new Color(154, 162, 166);
            case "Black": return new Color(46, 46, 46);
            case "Mithril": return new Color(59, 167, 214);
            case "Adamant": return new Color(26, 139, 111);
            case "Rune": return new Color(78, 159, 227);
            case "Dragon": return new Color(229, 57, 53);
            case "3rd Age": return new Color(229, 193, 0);
            default: return Color.GRAY;
        }
    }

    private String getRankAbbreviation(String rank)
    {
        switch (rank)
        {
            case "Bronze": return "Br";
            case "Iron": return "Ir";
            case "Steel": return "St";
            case "Black": return "Bl";
            case "Mithril": return "Mi";
            case "Adamant": return "Ad";
            case "Rune": return "Ru";
            case "Dragon": return "Dr";
            case "3rd Age": return "3A";
            default: return "?";
        }
    }
}