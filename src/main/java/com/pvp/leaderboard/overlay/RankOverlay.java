package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.cache.MembershipCache;
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
@SuppressWarnings("deprecation")
public class RankOverlay extends Overlay
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final PvPDataService pvpDataService;
    private final WhitelistPlayerCache whitelistPlayerCache;
    /** Opt-in membership set from the snapshot/delta feed (names only). The
     *  overlay gates rendering on this; rank itself comes from the name-keyed
     *  shards (see {@link #fetchSceneShardRankIfNeeded}). Replaces the
     *  whitelist.json membership blob (see PLAN_PRESENCE_FRESHNESS.md). */
    private final MembershipCache membershipCache;

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

    // Self-rank scheduling
    private volatile long selfRefreshRequestedAtMs = 0L;
    private volatile boolean selfRankAttempted = false;
    private volatile long nextSelfRankAllowedAtMs = 0L;

    /** Scene shard lookup state for whitelisted players visible in the
     *  world who don't yet have a rank label above their head. Aligns
     *  retry interval with the backend's DynamoDB-stream shard writer
     *  (~30 s propagation) so a newly-active opt-in player stops
     *  sitting blank for minutes while {@code whitelist.json} waits
     *  for its 9:30 refresh cycle. Uses
     *  {@link PvPDataService#getShardRankByName(String, String, boolean)}
     *  with {@code bypassCache=true} on each attempt so we don't serve
     *  a stale 60-min positive-cache entry from a prior passive read.
     *  Bounded by whitelist membership + in-scene visibility + this
     *  per-player backoff — not a whole-world shard poll. */
    private static final long SCENE_SHARD_RETRY_MS = 30_000L;
    private final ConcurrentHashMap<String, Long> sceneShardLastAttemptMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> sceneShardInFlight = new ConcurrentHashMap<>();

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
                       WhitelistPlayerCache whitelistPlayerCache, MembershipCache membershipCache)
    {
        this.client = client;
        this.config = config;
        this.pvpDataService = pvpDataService;
        this.whitelistPlayerCache = whitelistPlayerCache;
        this.membershipCache = membershipCache;
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
        // Defensive: a corrupted/missing persisted setting can return
        // null here. {@code switch (null)} would NPE every render frame
        // (~60 fps) and feed RuneLite's OverlayRenderer a stack-stripped
        // exception via HotSpot's OmitStackTraceInFastThrow. Fall back
        // to the same default the switch's default arm uses so behaviour
        // is identical to a normal {@code ABOVE_HEAD} setting.
        if (pos == null)
        {
            return ABOVE_HEAD_HEIGHT;
        }
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

    /** Tracks the last time we logged a render-loop throwable, in
     *  millis since epoch. Used to rate-limit the swallow-warn so a
     *  pathological 60-fps NPE storm produces at most one WARN per
     *  minute instead of ~3,600 per minute. */
    private volatile long lastRenderErrorLogMs = 0L;

    /** Minimum interval between {@code "render threw — swallowing"}
     *  WARN entries when the inner body is failing every frame. One
     *  minute is enough to keep the user's log readable while still
     *  giving prompt feedback if a new failure appears mid-session. */
    private static final long RENDER_ERROR_LOG_INTERVAL_MS = 60_000L;

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Catch-all guard: the rank overlay paints every frame at
        // ~60 FPS and reaches into client/Player APIs that can
        // transiently null out around login / world-hop / scene
        // transitions. Without this wrapper, a single null deref
        // would feed RuneLite's OverlayRenderer a same-bytecode-
        // location throw every frame; HotSpot then strips the
        // stack via OmitStackTraceInFastThrow and the user sees
        // 60 untraceable WARN lines/sec. Logging once with a real
        // stack here + swallowing downstream restores the diagnostic
        // signal. See {@code RankOverlayRenderSafetyTest} for the
        // contract.
        try
        {
            return renderInner(graphics);
        }
        catch (Throwable t)
        {
            // Rate-limit the WARN line so a pathological 60-fps NPE
            // cannot flood the log. The synthetic Throwable allocated
            // at the catch site always carries a real stack — even
            // when HotSpot has stack-stripped {@code t} via
            // {@code -XX:+OmitStackTraceInFastThrow} after thousands
            // of throws from the same bytecode location — so the user
            // can still locate the call path through {@code render()}.
            long now = System.currentTimeMillis();
            if (now - lastRenderErrorLogMs >= RENDER_ERROR_LOG_INTERVAL_MS)
            {
                lastRenderErrorLogMs = now;
                log.warn(
                    "[RankOverlay] render threw {} (msg={}) — swallowing. " +
                        "Diagnostic stack (HotSpot may have stripped the original):",
                    t.getClass().getName(), t.getMessage(),
                    new Throwable("RankOverlay render diagnostic capture"));
            }
            return new Dimension(0, 0);
        }
    }

    private Dimension renderInner(Graphics2D graphics)
    {
        if (config == null || client == null)
        {
            return new Dimension(0, 0);
        }

        // Handle bucket config changes
        String currentBucket = bucketKey(config.rankBucket());

        if (lastBucketKey == null || !lastBucketKey.equals(currentBucket))
        {
            displayedRanks.clear();
            displayedRanksTimestamp.clear();
            apiSetRanks.clear();
            whitelistPlayerCache.clearLookupCache();
            sceneShardLastAttemptMs.clear();
            sceneShardInFlight.clear();
            lastBucketKey = currentBucket;
            selfRankAttempted = false;
            nextSelfRankAllowedAtMs = 0L;
            log.debug("[Overlay] Bucket changed to {} - caches cleared", currentBucket);
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

        // Render self rank if enabled.
        // Order of guards matters: localName + cachedRank + showRanks
        // are checked BEFORE invoking RuneLite's getCanvasTextLocation.
        // Two reasons:
        //   1. localName == null happens transiently during world hops /
        //      login. Passing null into getCanvasTextLocation can NPE
        //      deep inside RuneLite (FontMetrics.stringWidth(null)),
        //      which surfaces as a stack-stripped NPE in the wrapper.
        //   2. When cachedRank == null or showRanks == false the result
        //      would be discarded anyway, so skipping the call is also
        //      a per-frame perf win.
        if (config.showOwnRank() && localName != null && showRanks)
        {
            String nameKey = NameUtils.canonicalKey(localName);
            String cachedRank = displayedRanks.get(nameKey);

            if (cachedRank != null)
            {
                Point nameLocation = localPlayer.getCanvasTextLocation(graphics, localName, heightOffset);
                if (nameLocation != null)
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
        }

        // Render MMR change notification
        renderMmrChangeNotification(graphics, localPlayer);
        
        // Render opted-in player ranks (if enabled and the membership feed
        // has loaded). Membership now comes from the snapshot/delta feed
        // (MembershipCache); rank is resolved per name via shards below.
        if (config.enableWhitelistRanks() && membershipCache.size() > 0)
        {
            renderWhitelistPlayers(graphics, localPlayer, currentBucket, heightOffset);
        }

        return new Dimension(0, 0);
    }
    
    /**
     * Render ranks for whitelisted players in the scene.
     * Only shows ranks for players on the whitelist (opt-in system).
     * Uses whichever data was fetched most recently:
     *   - API-fetched rank from a fight (stored in displayedRanks with timestamp)
     *   - Whitelist cache (refreshed every ~9.5 minutes)
     * Uses current bucket setting, with fallback to overall if bucket not found.
     */
    private void renderWhitelistPlayers(Graphics2D graphics, Player localPlayer, String bucket,
                                        int heightOffset)
    {
        String localName = localPlayer.getName();
        long whitelistRefreshMs = whitelistPlayerCache.getLastRefreshMs();

        // Defensive: client.getPlayers() can return null transiently
        // during scene loads / world hops. The enhanced-for below would
        // NPE on the implicit .iterator() call and feed RuneLite's
        // OverlayRenderer a stack-stripped NPE every frame at 60 fps
        // until the scene settles. Treat null as "no players visible".
        java.util.List<Player> scenePlayers = client.getPlayers();
        if (scenePlayers == null)
        {
            return;
        }

        for (Player player : scenePlayers)
        {
            if (player == null || player == localPlayer) continue;
            
            String playerName = player.getName();
            if (playerName == null || playerName.equals(localName)) continue;
            
            // Only show ranks for opted-in players (membership feed).
            if (!membershipCache.isMember(playerName)) continue;
            
            String displayRank = null;
            String nameKey = NameUtils.canonicalKey(playerName);
            
            // Get API-fetched rank and its timestamp
            String apiRank = displayedRanks.get(nameKey);
            Long apiTimestamp = displayedRanksTimestamp.get(nameKey);
            
            // Get whitelist cache rank
            WhitelistPlayerCache.BucketRank whitelistRank = whitelistPlayerCache.getRank(playerName, bucket);
            String formattedWhitelistRank = whitelistRank != null ? whitelistRank.getFormattedTier() : null;
            
            // Use whichever was fetched most recently
            if (apiRank != null && apiTimestamp != null)
            {
                if (whitelistRefreshMs > apiTimestamp && formattedWhitelistRank != null)
                {
                    // Whitelist was refreshed after API fetch - use whitelist (newer)
                    displayRank = formattedWhitelistRank;
                }
                else
                {
                    // API fetch is more recent - use API data
                    displayRank = apiRank;
                }
            }
            else if (formattedWhitelistRank != null)
            {
                // No API data - use whitelist cache
                displayRank = formattedWhitelistRank;
            }

            // Whitelisted + visible but still no label — the
            // whitelist.json row may be missing this bucket, the
            // player may have just opted in (CDN whitelist lags
            // heartbeats), or shards may have fresher data than
            // the last whitelist pull. Kick an async shard read
            // (bypass positive cache) instead of waiting up to
            // 9:30 for the next whitelist.json refresh.
            if (displayRank == null)
            {
                fetchSceneShardRankIfNeeded(playerName, bucket, nameKey);
                continue;
            }
            
            // Get player's screen position
            Point headLoc = player.getCanvasTextLocation(graphics, "", heightOffset);
            if (headLoc == null) continue;
            
            FontMetrics fm = graphics.getFontMetrics();
            int x = headLoc.getX() + config.rankOffsetX();
            int y = headLoc.getY() - fm.getAscent() - 2 + config.rankOffsetY();
            
            renderRankText(graphics, displayRank, x, y, Math.max(10, config.rankTextSize()));
        }
    }

    /**
     * Async shard fetch for a whitelisted, in-scene player who still
     * has no rank label. Uses {@code bypassCache=true} so each retry
     * picks up the DynamoDB-stream writer's ~30 s shard updates
     * instead of a stale passive-cache entry. Throttled to one
     * in-flight attempt per player and {@link #SCENE_SHARD_RETRY_MS}
     * between attempts so render() doesn't hammer the CDN.
     */
    private void fetchSceneShardRankIfNeeded(String playerName, String bucket, String nameKey)
    {
        long now = System.currentTimeMillis();
        if (!shouldAttemptSceneShardLookup(
            sceneShardLastAttemptMs.get(nameKey), now, SCENE_SHARD_RETRY_MS,
            sceneShardInFlight.containsKey(nameKey)))
        {
            return;
        }
        if (sceneShardInFlight.putIfAbsent(nameKey, Boolean.TRUE) != null)
        {
            return;
        }
        sceneShardLastAttemptMs.put(nameKey, now);
        log.debug("[Overlay] Scene shard lookup (bypass) for whitelisted player {} bucket={}",
            playerName, bucket);

        pvpDataService.getShardRankByName(playerName, bucket, true)
            .whenComplete((sr, ex) ->
            {
                sceneShardInFlight.remove(nameKey);
                if (ex != null)
                {
                    log.debug("[Overlay] Scene shard lookup failed for {}: {}",
                        playerName, ex.getMessage());
                    return;
                }
                if (sr == null || sr.tier == null || sr.tier.trim().isEmpty())
                {
                    log.debug("[Overlay] Scene shard lookup miss for {} bucket={} (retry in {}s)",
                        playerName, bucket, SCENE_SHARD_RETRY_MS / 1000L);
                    return;
                }
                long fetchedAt = System.currentTimeMillis();
                displayedRanks.put(nameKey, sr.tier);
                displayedRanksTimestamp.put(nameKey, fetchedAt);
                log.debug("[Overlay] Scene shard rank for {} bucket={}: {}",
                    playerName, bucket, sr.tier);
            });
    }

    /** Package-private for unit tests — decides whether a new scene
     *  shard attempt is allowed given backoff + in-flight state. */
    static boolean shouldAttemptSceneShardLookup(long lastAttemptMs, long nowMs,
                                                 long retryIntervalMs, boolean inFlight)
    {
        if (inFlight) return false;
        if (lastAttemptMs <= 0L) return true;
        return nowMs - lastAttemptMs >= retryIntervalMs;
    }

    private void fetchRankForSelf(String selfName)
    {
        String bucket = bucketKey(config.rankBucket());
        String key = NameUtils.canonicalKey(selfName);

        pvpDataService.getTierFromProfile(selfName, bucket)
            .thenAccept(apiTier -> {
                long fetchCompletedAt = System.currentTimeMillis();
                
                if (apiTier != null)
                {
                    displayedRanks.put(key, apiTier);
                    displayedRanksTimestamp.put(key, fetchCompletedAt);
                    apiSetRanks.put(key, apiTier);
                    log.debug("[Overlay] Self rank fetched from API: {} = {}", selfName, apiTier);
                    nextSelfRankAllowedAtMs = fetchCompletedAt + 60_000L;
                }
                else
                {
                    log.debug("[Overlay] API returned null for self {}, falling back to shard", selfName);
                    fetchRankForSelfFromShard(selfName, bucket, key);
                }
            })
            .exceptionally(ex -> {
                log.debug("[Overlay] API error fetching self rank: {}, falling back to shard", ex.getMessage());
                fetchRankForSelfFromShard(selfName, bucket, key);
                return null;
            });
    }
    
    /**
     * Fallback method to fetch self rank from shard when API fails.
     */
    private void fetchRankForSelfFromShard(String selfName, String bucket, String key)
    {
        pvpDataService.getShardRankByName(selfName, bucket)
            .thenAccept(sr -> {
                if (sr == null)
                {
                    log.debug("[Overlay] No shard rank found for self: {}", selfName);
                    return;
                }

                String shardRank = sr.tier;

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
                        log.debug("[Overlay] Self rank fetched from shard (fallback): {} = {}", selfName, shardRank);
                    }
                    nextSelfRankAllowedAtMs = fetchCompletedAt + 60_000L;
                }
            })
            .exceptionally(ex -> {
                log.debug("[Overlay] Error fetching self rank from shard: {}", ex.getMessage());
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
