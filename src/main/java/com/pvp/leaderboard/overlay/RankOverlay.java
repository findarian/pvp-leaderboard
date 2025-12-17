package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.game.PlayerRankEvent;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.util.NameUtils;
import com.pvp.leaderboard.util.RankUtils;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RankOverlay extends Overlay
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final PvPDataService pvpDataService;

    // Displayed ranks cache
    private final ConcurrentHashMap<String, String> displayedRanks = new ConcurrentHashMap<>();
    private final Map<String, Long> displayedRanksTimestamp = new ConcurrentHashMap<>();

    // Config change tracking
    private String lastBucketKey = null;
    private PvPLeaderboardConfig.RankDisplayMode lastDisplayMode = null;

    // Self-rank scheduling
    private volatile long selfRefreshRequestedAtMs = 0L;
    private volatile boolean selfRankAttempted = false;
    private volatile long nextSelfRankAllowedAtMs = 0L;

    // MMR change notification
    private volatile Double previousMmr = null;
    private volatile Double currentMmrDelta = null;
    private volatile long mmrNotificationStartMs = 0L;

    @Inject
    public RankOverlay(Client client, PvPLeaderboardConfig config, PvPDataService pvpDataService)
    {
        this.client = client;
        this.config = config;
        this.pvpDataService = pvpDataService;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_HIGHEST);
    }

    @Subscribe
    public void onPlayerRankEvent(PlayerRankEvent event)
    {
        if (event == null || event.getPlayerName() == null || event.getTier() == null)
        {
            return;
        }
        log.debug("[Overlay] onPlayerRankEvent: player={} tier={} bucket={}",
            event.getPlayerName(), event.getTier(), event.getBucket());
        setRankFromApi(event.getPlayerName(), event.getTier());
    }

    /**
     * Schedule a self-rank refresh after the given delay.
     */
    public void scheduleSelfRankRefresh(long delayMs)
    {
        long now = System.currentTimeMillis();
        nextSelfRankAllowedAtMs = now + Math.max(0L, delayMs);
        selfRankAttempted = false;
        selfRefreshRequestedAtMs = nextSelfRankAllowedAtMs;
    }

    /**
     * Reset lookup state on world hop.
     */
    public void resetLookupStateOnWorldHop()
    {
        selfRankAttempted = false;
        nextSelfRankAllowedAtMs = 0L;
    }

    /**
     * Get cached rank for a player.
     */
    public String getCachedRankFor(String playerName)
    {
        return displayedRanks.get(NameUtils.canonicalKey(playerName));
    }

    /**
     * Set rank from API response.
     */
    public void setRankFromApi(String playerName, String rank)
    {
        if (playerName == null || playerName.trim().isEmpty() || rank == null || rank.trim().isEmpty())
        {
            return;
        }
        String key = NameUtils.canonicalKey(playerName);
        displayedRanks.put(key, rank);
        displayedRanksTimestamp.put(key, System.currentTimeMillis());
        pvpDataService.clearShardNegativeCache(playerName);
        log.debug("[Overlay] setRankFromApi: key={} rank={}", key, rank);
    }

    /**
     * Store the previous MMR value before a fight for delta calculation.
     */
    public void storePreviousMmr(double mmr)
    {
        this.previousMmr = mmr;
        log.debug("[Overlay] Stored previous MMR: {}", mmr);
    }

    /**
     * Called after fight when new MMR is fetched.
     * Calculates delta and triggers notification.
     */
    public void onMmrUpdated(double newMmr, double oldMmr)
    {
        if (!config.showMmrChangeNotification())
        {
            return;
        }
        this.currentMmrDelta = newMmr - oldMmr;
        this.mmrNotificationStartMs = System.currentTimeMillis();
        this.previousMmr = null;
        log.debug("[Overlay] MMR updated: old={} new={} delta={}", oldMmr, newMmr, currentMmrDelta);
    }

    /**
     * Get the stored previous MMR (for FightMonitor to check).
     */
    public Double getPreviousMmr()
    {
        return previousMmr;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (config == null || client == null)
        {
            return new Dimension(0, 0);
        }

        // Handle config changes
        String currentBucket = bucketKey(config.rankBucket());
        PvPLeaderboardConfig.RankDisplayMode currentMode = config.rankDisplayMode();

        if (lastBucketKey == null || !lastBucketKey.equals(currentBucket) || lastDisplayMode != currentMode)
        {
            displayedRanks.clear();
            displayedRanksTimestamp.clear();
            lastBucketKey = currentBucket;
            lastDisplayMode = currentMode;
            selfRankAttempted = false;
            nextSelfRankAllowedAtMs = 0L;
        }

        // Get local player
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            return new Dimension(0, 0);
        }

        String localName = localPlayer.getName();

        // Handle self-rank refresh scheduling
        if (config.showOwnRank() && localName != null && selfRefreshRequestedAtMs > 0L)
        {
            long now = System.currentTimeMillis();
            if (now >= selfRefreshRequestedAtMs && !selfRankAttempted && now >= nextSelfRankAllowedAtMs)
            {
                selfRankAttempted = true;
                selfRefreshRequestedAtMs = 0L;
                fetchRankForSelf(localName);
            }
        }

        // Only render self in Phase 1
        if (!config.showOwnRank())
        {
            // Still render MMR notification even if rank is hidden
            renderMmrChangeNotification(graphics, localPlayer);
            return new Dimension(0, 0);
        }

        String nameKey = NameUtils.canonicalKey(localName);
        String cachedRank = displayedRanks.get(nameKey);

        // Render rank above local player
        Point nameLocation = localPlayer.getCanvasTextLocation(graphics, localName, localPlayer.getLogicalHeight() + 40);
        if (nameLocation != null && cachedRank != null)
        {
            FontMetrics fm = graphics.getFontMetrics();

            Point headLoc = localPlayer.getCanvasTextLocation(graphics, "", localPlayer.getLogicalHeight() + 40);
            int x, y;
            if (headLoc != null)
            {
                x = headLoc.getX() + config.rankOffsetX();
                y = headLoc.getY() - fm.getAscent() - 2 + config.rankOffsetY();
            }
            else
            {
                x = nameLocation.getX() + config.rankOffsetX();
                y = nameLocation.getY() - fm.getAscent() - 2 + config.rankOffsetY();
            }

            renderRankText(graphics, cachedRank, x, y, Math.max(10, config.rankTextSize()));
        }

        // Render MMR change notification
        renderMmrChangeNotification(graphics, localPlayer);

        return new Dimension(0, 0);
    }

    private void fetchRankForSelf(String selfName)
    {
        String bucket = bucketKey(config.rankBucket());
        PvPLeaderboardConfig.RankDisplayMode mode = config.rankDisplayMode();

        pvpDataService.getShardRankByName(selfName, bucket)
            .thenAccept(sr -> {
                if (sr == null)
                {
                    log.debug("[Overlay] No shard rank found for self: {}", selfName);
                    return;
                }

                String rank;
                if (mode == PvPLeaderboardConfig.RankDisplayMode.RANK_NUMBER)
                {
                    rank = sr.rank > 0 ? "Rank " + sr.rank : null;
                }
                else
                {
                    rank = sr.tier;
                }

                if (rank != null)
                {
                    String key = NameUtils.canonicalKey(selfName);
                    displayedRanks.put(key, rank);
                    displayedRanksTimestamp.put(key, System.currentTimeMillis());
                    nextSelfRankAllowedAtMs = System.currentTimeMillis() + 60_000L;
                    log.debug("[Overlay] Self rank fetched: {} = {}", selfName, rank);
                }
            })
            .exceptionally(ex -> {
                log.debug("[Overlay] Error fetching self rank: {}", ex.getMessage());
                nextSelfRankAllowedAtMs = System.currentTimeMillis() + 60_000L;
                return null;
            });
    }

    private void renderRankText(Graphics2D g, String fullRank, int x, int y, int size)
    {
        if (fullRank == null || fullRank.trim().isEmpty())
        {
            return;
        }

        String[] parts = fullRank.split(" ");
        String rankName = parts[0];
        String division = parts.length > 1 ? parts[1] : "";
        String text = division.isEmpty() ? rankName : (rankName + " " + division);

        g.setFont(new Font(Font.DIALOG, Font.BOLD, size));
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int textH = fm.getAscent();
        int centerX = x - textW / 2;
        int baseY = y + textH;

        // Check for 3rd Age - special glow effect
        boolean isThirdAge = rankName.equals("3rd") || fullRank.startsWith("3rd");

        if (isThirdAge && !config.colorblindMode())
        {
            // Glowing white effect: multiple layers with decreasing alpha
            int[][] glowLayers = {
                {3, 25},   // offset 3px, alpha 25
                {2, 50},   // offset 2px, alpha 50
                {1, 100},  // offset 1px, alpha 100
            };

            for (int[] layer : glowLayers)
            {
                int offset = layer[0];
                int alpha = layer[1];
                g.setColor(new Color(255, 255, 255, alpha));
                for (int dy = -offset; dy <= offset; dy++)
                {
                    for (int dx = -offset; dx <= offset; dx++)
                    {
                        if (dx == 0 && dy == 0) continue;
                        g.drawString(text, centerX + dx, baseY + dy);
                    }
                }
            }

            // Core white text
            g.setColor(Color.WHITE);
            g.drawString(text, centerX, baseY);
        }
        else
        {
            // Standard rendering: black outline + colored text
            g.setColor(new Color(0, 0, 0, 180));
            for (int dy = -1; dy <= 1; dy++)
            {
                for (int dx = -1; dx <= 1; dx++)
                {
                    if (dx == 0 && dy == 0) continue;
                    g.drawString(text, centerX + dx, baseY + dy);
                }
            }

            // Determine text color
            if (config.colorblindMode() || fullRank.startsWith("Rank "))
            {
                g.setColor(Color.WHITE);
            }
            else
            {
                g.setColor(RankUtils.getRankColor(rankName));
            }
            g.drawString(text, centerX, baseY);
        }
    }

    private void renderMmrChangeNotification(Graphics2D g, Player localPlayer)
    {
        if (currentMmrDelta == null || mmrNotificationStartMs == 0L)
        {
            return;
        }
        if (!config.showMmrChangeNotification())
        {
            return;
        }

        long durationMs = config.mmrDuration() * 1000L;
        long elapsed = System.currentTimeMillis() - mmrNotificationStartMs;
        if (elapsed > durationMs)
        {
            // Clear notification after duration
            currentMmrDelta = null;
            mmrNotificationStartMs = 0L;
            return;
        }

        // Get position above player
        Point headLoc = localPlayer.getCanvasTextLocation(g, "", localPlayer.getLogicalHeight() + 80);
        if (headLoc == null)
        {
            return;
        }

        // Calculate fade alpha (255 -> 0 over duration)
        float progress = (float) elapsed / durationMs;
        int alpha = (int) (255 * (1.0f - progress));

        // Format text: +100.9 MMR or -3.2 MMR
        String sign = currentMmrDelta >= 0 ? "+" : "";
        String text = String.format("%s%.1f MMR", sign, currentMmrDelta);

        // Determine color
        Color baseColor;
        if (config.colorblindMode())
        {
            baseColor = Color.WHITE;
        }
        else if (currentMmrDelta >= 0)
        {
            baseColor = new Color(0, 200, 0); // Green for gain
        }
        else
        {
            baseColor = new Color(229, 57, 53); // Red for loss
        }
        Color color = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha);

        // Render with black outline
        g.setFont(new Font(Font.DIALOG, Font.BOLD, Math.max(12, config.rankTextSize() + 2)));
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int centerX = headLoc.getX() - textW / 2 + config.mmrOffsetX();

        // Float upward as it fades
        int floatOffset = (int) (progress * 30); // Float up 30 pixels
        int drawY = headLoc.getY() - floatOffset + config.mmrOffsetY();

        // Black outline with alpha
        Color outlineColor = new Color(0, 0, 0, (int) (180 * (1.0f - progress)));
        g.setColor(outlineColor);
        for (int dy = -1; dy <= 1; dy++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                if (dx == 0 && dy == 0) continue;
                g.drawString(text, centerX + dx, drawY + dy);
            }
        }

        // Main colored text
        g.setColor(color);
        g.drawString(text, centerX, drawY);
    }

    private static String bucketKey(PvPLeaderboardConfig.RankBucket bucket)
    {
        if (bucket == null)
        {
            return "overall";
        }
        switch (bucket)
        {
            case NH:
                return "nh";
            case VENG:
                return "veng";
            case MULTI:
                return "multi";
            case DMM:
                return "dmm";
            case OVERALL:
            default:
                return "overall";
        }
    }
}
