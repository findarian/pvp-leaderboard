package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.cache.WhitelistPlayerCache;
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
    private final WhitelistPlayerCache whitelistPlayerCache;

    // Displayed ranks cache (for self)
    private final ConcurrentHashMap<String, String> displayedRanks = new ConcurrentHashMap<>();
    private final Map<String, Long> displayedRanksTimestamp = new ConcurrentHashMap<>();
    
    // API-set ranks that should persist until shard cache refreshes with matching data
    private final ConcurrentHashMap<String, String> apiSetRanks = new ConcurrentHashMap<>();
    
    // Combat tracking for "hide rank out of combat" feature
    // Tracks when self was last in combat (milliseconds)
    private volatile long selfLastCombatMs = 0L;

    // Config change tracking
    private String lastBucketKey = null;
    private PvPLeaderboardConfig.RankDisplayMode lastDisplayMode = null;

    // Self-rank scheduling
    private volatile long selfRefreshRequestedAtMs = 0L;
    private volatile boolean selfRankAttempted = false;
    private volatile long nextSelfRankAllowedAtMs = 0L;

    // MMR change notification queue (for multi-kill scenarios)
    private final java.util.concurrent.ConcurrentLinkedQueue<MmrNotification> mmrNotificationQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile MmrNotification currentMmrNotification = null;
    private volatile long mmrNotificationStartMs = 0L;
    private volatile int lastNotificationTick = -1; // Track tick for 1-per-tick display
    
    // Legacy fields kept for backwards compatibility
    private volatile Double previousMmr = null;
    
    /**
     * Represents a single MMR change notification.
     */
    private static class MmrNotification {
        final double delta;
        final String bucketLabel;
        
        MmrNotification(double delta, String bucketLabel) {
            this.delta = delta;
            this.bucketLabel = bucketLabel;
        }
    }
    
    @Inject
    public RankOverlay(Client client, PvPLeaderboardConfig config, PvPDataService pvpDataService, 
                       WhitelistPlayerCache whitelistPlayerCache)
    {
        this.client = client;
        this.config = config;
        this.pvpDataService = pvpDataService;
        this.whitelistPlayerCache = whitelistPlayerCache;
        // Always use DYNAMIC for snap-to-player rendering
        // Use UNDER_WIDGETS layer (same as player indicators) so it appears above prayers
        // Use PRIORITY_LOW so it renders behind player indicator names
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.UNDER_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
    }

    // Fixed height constants for consistent rank positioning regardless of equipment
    // Using fixed values prevents "jumping" when players wear different helmets/hats
    private static final int HEAD_HEIGHT = 220;      // At head level (just above player name)
    private static final int ABOVE_HEAD_HEIGHT = 268; // Above head (higher than head position)

    /**
     * Get the height offset for rendering rank based on position setting.
     * Uses fixed height constants instead of player.getLogicalHeight() to ensure
     * consistent positioning regardless of what equipment the player is wearing.
     * 
     * @return The height offset to use with getCanvasTextLocation
     */
    private int getHeightOffsetForPosition()
    {
        PvPLeaderboardConfig.RankPosition pos = config.rankPosition();
        
        switch (pos)
        {
            case FEET:
                return 0; // At feet level
            case HEAD:
                return HEAD_HEIGHT; // At head level (just above player name)
            case ABOVE_HEAD:
            default:
                return ABOVE_HEAD_HEIGHT; // Above head (higher than head position)
        }
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
        // Note: Don't clear whitelist cache on world hop - it's fetched from API and valid for 1 hour
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
     * This rank will persist until the shard cache refreshes with matching data.
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
        // Track this as an API-set rank - persists until shard cache confirms with same rank
        apiSetRanks.put(key, rank);
        pvpDataService.clearShardNegativeCache(playerName);
        log.debug("[Overlay] setRankFromApi: key={} rank={} (will persist until shard confirms)", key, rank);
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
     * Show MMR delta notification with the actual delta from match history.
     * This is the preferred method as it uses the accurate server-calculated delta.
     * Notifications are queued for multi-kill scenarios.
     * 
     * @param mmrDelta The actual MMR change (positive for gain, negative for loss)
     * @param bucketLabel Optional bucket label to display (e.g., "Multi") when different from config bucket
     */
    public void showMmrDelta(double mmrDelta, String bucketLabel)
    {
        if (!config.showMmrChangeNotification())
        {
            return;
        }
        
        // Add to queue for sequential display
        MmrNotification notification = new MmrNotification(mmrDelta, bucketLabel);
        mmrNotificationQueue.offer(notification);
        log.debug("[Overlay] Queued MMR notification: delta={} bucket={} queueSize={}", 
            mmrDelta, bucketLabel, mmrNotificationQueue.size());
        
        // If no notification is currently showing, start this one immediately
        if (currentMmrNotification == null && mmrNotificationStartMs == 0L)
        {
            startNextNotification();
        }
    }
    
    /**
     * Start displaying the next notification from the queue.
     */
    private void startNextNotification()
    {
        MmrNotification next = mmrNotificationQueue.poll();
        if (next != null)
        {
            currentMmrNotification = next;
            mmrNotificationStartMs = System.currentTimeMillis();
            log.debug("[Overlay] Started MMR notification: delta={} bucket={} remaining={}", 
                next.delta, next.bucketLabel, mmrNotificationQueue.size());
        }
        else
        {
            currentMmrNotification = null;
            mmrNotificationStartMs = 0L;
        }
    }

    /**
     * Called after fight when new MMR is fetched.
     * Calculates delta and triggers notification.
     * @deprecated Use showMmrDelta instead which uses accurate server-calculated delta
     */
    @Deprecated
    public void onMmrUpdated(double newMmr, double oldMmr, String bucketLabel)
    {
        showMmrDelta(newMmr - oldMmr, bucketLabel);
    }

    /**
     * Called after fight when new MMR is fetched (legacy method without bucket label).
     * @deprecated Use showMmrDelta instead
     */
    @Deprecated
    public void onMmrUpdated(double newMmr, double oldMmr)
    {
        onMmrUpdated(newMmr, oldMmr, null);
    }

    /**
     * Get the stored previous MMR (for FightMonitor to check).
     */
    public Double getPreviousMmr()
    {
        return previousMmr;
    }

    /**
     * Add a player to the looked-up players cache with all their bucket ranks.
     * Called when "PvP lookup" is clicked on a player.
     * 
     * @param playerName The player's name
     * @param bucketRanks Map of bucket name to rank (e.g., "nh" -> "Dragon 2", "veng" -> "Gold 1")
     */
    public void addLookedUpPlayer(String playerName, Map<String, String> bucketRanks)
    {
        // No-op: looked-up player cache not implemented
        resetVisibilityTimer();
    }

    /**
     * Reset the visibility timer.
     * Called when combat occurs or when a player is looked up.
     * This keeps all ranks (self and looked-up players) visible for the configured duration.
     */
    public void resetVisibilityTimer()
    {
        selfLastCombatMs = System.currentTimeMillis();
        log.debug("[Overlay] Visibility timer reset");
    }

    /**
     * Refresh a looked-up player's rank for a specific bucket after a fight.
     * 
     * @param playerName The player's name
     * @param bucket The bucket to update (e.g., "nh", "veng")
     * @param newRank The new rank for that bucket
     */
    public void refreshLookedUpPlayer(String playerName, String bucket, String newRank)
    {
        // No-op: looked-up player cache not implemented
    }

    /**
     * Check if a player is in the looked-up cache (and not expired).
     */
    public boolean isPlayerLookedUp(String playerName)
    {
        return false; // Not implemented
    }

    /**
     * Get the cached rank for a looked-up player for the specified bucket.
     */
    public String getLookedUpRank(String playerName, String bucket)
    {
        return null; // Not implemented
    }

    /**
     * Update the visibility timer when in combat.
     * Called by FightMonitor when in combat.
     * This is universal - affects display of all ranks (self and looked-up players).
     */
    public void updateSelfCombatTime()
    {
        resetVisibilityTimer();
    }

    /**
     * Update the visibility timer when in combat with a specific player.
     * Called by FightMonitor when in combat with a specific player.
     * Note: Visibility timer is universal, so this just resets the timer.
     */
    public void updatePlayerCombatTime(String playerName)
    {
        resetVisibilityTimer();
    }

    /**
     * Check if ranks should be shown based on combat timeout.
     * This is universal - applies to both self rank and looked-up player ranks.
     * Returns true if ranks should be shown, false if they should be hidden.
     */
    private boolean shouldShowRanks()
    {
        if (!config.hideRankOutOfCombat())
        {
            return true; // Feature disabled, always show
        }
        long timeoutMs = config.hideRankAfterMinutes() * 60L * 1000L;
        long now = System.currentTimeMillis();
        return (now - selfLastCombatMs) <= timeoutMs;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (config == null || client == null)
        {
            return new Dimension(0, 0);
        }

        // Handle bucket/mode config changes
        String currentBucket = bucketKey(config.rankBucket());
        PvPLeaderboardConfig.RankDisplayMode currentMode = config.rankDisplayMode();

        if (lastBucketKey == null || !lastBucketKey.equals(currentBucket) || lastDisplayMode != currentMode)
        {
            displayedRanks.clear();
            displayedRanksTimestamp.clear();
            apiSetRanks.clear();  // Clear API overrides when config changes
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

        // Check if we should show ranks (combat timeout)
        boolean showRanks = shouldShowRanks();
        int heightOffset = getHeightOffsetForPosition();

        // Render self rank if enabled
        if (config.showOwnRank())
        {
            String nameKey = NameUtils.canonicalKey(localName);
            String cachedRank = displayedRanks.get(nameKey);

            Point nameLocation = localPlayer.getCanvasTextLocation(graphics, localName, heightOffset);
            if (nameLocation != null && cachedRank != null && showRanks)
            {
                FontMetrics fm = graphics.getFontMetrics();

                Point headLoc = localPlayer.getCanvasTextLocation(graphics, "", heightOffset);
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
        }

        // Render MMR change notification
        renderMmrChangeNotification(graphics, localPlayer);
        
        // Render whitelist player ranks (if enabled and has data)
        if (config.enableWhitelistRanks() && whitelistPlayerCache.size() > 0)
        {
            renderWhitelistPlayers(graphics, localPlayer, currentBucket, currentMode, heightOffset);
        }

        return new Dimension(0, 0);
    }
    
    /**
     * Render ranks for whitelisted players in the scene.
     * Data comes from cache (fetched separately by WhitelistService).
     * Uses current bucket setting, with fallback to overall if bucket not found.
     */
    private void renderWhitelistPlayers(Graphics2D graphics, Player localPlayer, String bucket,
                                        PvPLeaderboardConfig.RankDisplayMode mode, int heightOffset)
    {
        String localName = localPlayer.getName();
        
        for (Player player : client.getPlayers())
        {
            if (player == null || player == localPlayer) continue;
            
            String playerName = player.getName();
            if (playerName == null || playerName.equals(localName)) continue;
            
            // Get rank from cache for current bucket (falls back to overall)
            WhitelistPlayerCache.BucketRank rank = whitelistPlayerCache.getRank(playerName, bucket);
            if (rank == null) continue;
            
            // Format for display
            String displayRank;
            if (mode == PvPLeaderboardConfig.RankDisplayMode.RANK_NUMBER)
            {
                displayRank = rank.rank > 0 ? "Rank " + rank.rank : null;
            }
            else
            {
                displayRank = rank.getFormattedTier();
            }
            if (displayRank == null) continue;
            
            // Get player's screen position
            Point headLoc = player.getCanvasTextLocation(graphics, "", heightOffset);
            if (headLoc == null) continue;
            
            FontMetrics fm = graphics.getFontMetrics();
            int x = headLoc.getX() + config.rankOffsetX();
            int y = headLoc.getY() - fm.getAscent() - 2 + config.rankOffsetY();
            
            renderRankText(graphics, displayRank, x, y, Math.max(10, config.rankTextSize()));
        }
    }

    private void fetchRankForSelf(String selfName)
    {
        String bucket = bucketKey(config.rankBucket());
        PvPLeaderboardConfig.RankDisplayMode mode = config.rankDisplayMode();
        String key = NameUtils.canonicalKey(selfName);

        pvpDataService.getShardRankByName(selfName, bucket)
            .thenAccept(sr -> {
                if (sr == null)
                {
                    log.debug("[Overlay] No shard rank found for self: {}", selfName);
                    return;
                }

                String shardRank;
                if (mode == PvPLeaderboardConfig.RankDisplayMode.RANK_NUMBER)
                {
                    shardRank = sr.rank > 0 ? "Rank " + sr.rank : null;
                }
                else
                {
                    shardRank = sr.tier;
                }

                if (shardRank != null)
                {
                    long fetchCompletedAt = System.currentTimeMillis();
                    
                    // Check if there's an API-set rank that should persist
                    String apiRank = apiSetRanks.get(key);
                    if (apiRank != null)
                    {
                        if (apiRank.equals(shardRank))
                        {
                            // Shard cache has refreshed with matching data - clear the API override
                            apiSetRanks.remove(key);
                            displayedRanks.put(key, shardRank);
                            displayedRanksTimestamp.put(key, fetchCompletedAt);
                            log.debug("[Overlay] Shard cache refreshed for {}: {} (API override cleared)", selfName, shardRank);
                        }
                        else
                        {
                            // Shard has stale data - preserve the API-set rank
                            log.debug("[Overlay] Preserving API rank for {}: {} (shard has stale: {})", 
                                selfName, apiRank, shardRank);
                        }
                    }
                    else
                    {
                        // No API override - use shard data
                        displayedRanks.put(key, shardRank);
                        displayedRanksTimestamp.put(key, fetchCompletedAt);
                        log.debug("[Overlay] Self rank fetched: {} = {}", selfName, shardRank);
                    }
                    nextSelfRankAllowedAtMs = fetchCompletedAt + 60_000L;
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
        int currentTick = client.getTickCount();
        
        if (currentMmrNotification == null || mmrNotificationStartMs == 0L)
        {
            // Try to start next notification if queue has items (rate limit: 1 per tick)
            if (!mmrNotificationQueue.isEmpty() && currentTick != lastNotificationTick)
            {
                startNextNotification();
                lastNotificationTick = currentTick;
            }
            return;
        }
        if (!config.showMmrChangeNotification())
        {
            return;
        }

        // Check if current notification duration has expired
        long durationMs = config.mmrDuration() * 1000L;
        long elapsed = System.currentTimeMillis() - mmrNotificationStartMs;
        
        if (elapsed > durationMs)
        {
            // Current notification finished - start next (rate limit: 1 per tick)
            if (currentTick != lastNotificationTick)
            {
                startNextNotification();
                lastNotificationTick = currentTick;
            }
            else
            {
                // Already started one this tick, clear current and wait for next tick
                currentMmrNotification = null;
                mmrNotificationStartMs = 0L;
            }
            return;
        }

        // Get position above player
        Point headLoc = localPlayer.getCanvasTextLocation(g, "", localPlayer.getLogicalHeight() + 80);
        if (headLoc == null)
        {
            return;
        }

        double mmrDelta = currentMmrNotification.delta;
        String bucketLabel = currentMmrNotification.bucketLabel;

        // Calculate fade progress
        float progress = (float) elapsed / durationMs;
        int alpha = (int) (255 * (1.0f - progress));
        int floatOffset = (int) (progress * 30); // Float up 30 pixels

        // Format text: +1.3 MMR (NH) when bucket label present (auto-switched)
        // Otherwise: +1.3 MMR
        String sign = mmrDelta >= 0 ? "+" : "";
        String text;
        if (bucketLabel != null && !bucketLabel.isEmpty())
        {
            text = String.format("%s%.1f MMR (%s)", sign, mmrDelta, bucketLabel);
        }
        else
        {
            text = String.format("%s%.1f MMR", sign, mmrDelta);
        }

        // Determine color
        Color baseColor;
        if (config.colorblindMode())
        {
            baseColor = Color.WHITE;
        }
        else if (mmrDelta >= 0)
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
