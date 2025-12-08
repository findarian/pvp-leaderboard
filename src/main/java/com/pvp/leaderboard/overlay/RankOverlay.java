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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RankOverlay extends Overlay
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final PvPDataService pvpDataService;
    private final OkHttpClient okHttpClient;

    private final ConcurrentHashMap<String, String> displayedRanks = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> rankIconCache = new ConcurrentHashMap<>();
    private final Set<String> pendingIconFetches = Collections.synchronizedSet(new HashSet<>());
    private static final String S3_BASE_URL = "https://devsecopsautomated.com/";
    
    // Negative cache with TTL (1 hour)
    private final Map<String, Long> negativeCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_CACHE_TTL = 60L * 60L * 1000L;

    // Timestamp tracking for displayedRanks (1 hour TTL, same as shard refresh)
    private final Map<String, Long> displayedRanksTimestamp = new ConcurrentHashMap<>();
    private static final long DISPLAYED_RANK_TTL = 60L * 60L * 1000L; // 1 hour

    private String lastBucketKey = null;
    private PvPLeaderboardConfig.RankDisplayMode lastDisplayMode = null;
    
    private volatile long selfRefreshRequestedAtMs = 0L;
    private volatile boolean selfRankAttempted = false;
    private volatile long nextSelfRankAllowedAtMs = 0L;
    
    @Subscribe
    public void onPlayerRankEvent(PlayerRankEvent event)
    {
        if (event == null) {
            log.debug("[Overlay] onPlayerRankEvent: event is null");
            return;
        }
        if (event.getPlayerName() == null) {
            log.debug("[Overlay] onPlayerRankEvent: playerName is null");
            return;
        }
        if (event.getTier() == null) {
            log.debug("[Overlay] onPlayerRankEvent: tier is null for player={}", event.getPlayerName());
            return;
        }
        log.debug("[Overlay] onPlayerRankEvent received: player={} tier={} bucket={}", event.getPlayerName(), event.getTier(), event.getBucket());
        setRankFromApi(event.getPlayerName(), event.getTier());
    }

    private void debug(String format, Object... args)
    {
        // if (config.debugMode())
        // {
        //     log.debug("[RankOverlay] " + format, args);
        // }
    }

    public void scheduleSelfRankRefresh(long delayMs)
    {
        long now = System.currentTimeMillis();
        nextSelfRankAllowedAtMs = now + Math.max(0L, delayMs);
        selfRankAttempted = false;
        selfRefreshRequestedAtMs = nextSelfRankAllowedAtMs;
        // debug("scheduleSelfRankRefresh delayMs={} nextAllowedAtMs={}", delayMs, nextSelfRankAllowedAtMs);
    }

    public void resetLookupStateOnWorldHop()
    {
        try
        {
            selfRankAttempted = false;
            nextSelfRankAllowedAtMs = 0L;
            // debug("reset on world hop; kept {} displayed ranks", displayedRanks.size());
            // It does NOT clear displayedRanks currently (comment implies it keeps them)
        }
        catch (Exception ignore) {}
    }

    public String getCachedRankFor(String playerName)
    {
        return displayedRanks.get(NameUtils.canonicalKey(playerName));
    }

    public void forceLookupAndDisplay(String playerName)
    {
        if (playerName == null || playerName.trim().isEmpty()) return;
        if (clientThread != null)
        {
            clientThread.invokeLater(() -> {
                try
                {
                    String key = NameUtils.canonicalKey(playerName);
                    String bucket = bucketKey(config.rankBucket());
                    // debug("forced profile API fetch for player={} bucket={}", playerName, bucket);
                    
                    // Use profile API to bypass shard negative cache and get reliable data
                    pvpDataService.getTierFromProfile(playerName, bucket).thenAccept(tier -> {
                        if (tier != null && !tier.isEmpty())
                        {
                            displayedRanks.put(key, tier);
                            displayedRanksTimestamp.put(key, System.currentTimeMillis());
                            negativeCache.remove(key);
                            pvpDataService.clearShardNegativeCache(playerName);
                            // debug("Forced profile API returned rank for {}: {}", playerName, tier);
                        }
                        else
                        {
                            // debug("Forced profile API returned no rank for {}", playerName);
                        }
                    });
                }
                catch (Exception ignore) {}
            });
        }
    }

    public void setRankFromApi(String playerName, String rank)
    {
        if (playerName == null || playerName.trim().isEmpty()) {
            log.debug("[Overlay] setRankFromApi: playerName is null/empty");
            return;
        }
        if (rank == null || rank.trim().isEmpty()) {
            log.debug("[Overlay] setRankFromApi: rank is null/empty for player={}", playerName);
            return;
        }
        try
        {
            String key = NameUtils.canonicalKey(playerName);
            log.debug("[Overlay] setRankFromApi SUCCESS: player={} key={} rank={}", playerName, key, rank);
            displayedRanks.put(key, rank);
            displayedRanksTimestamp.put(key, System.currentTimeMillis());
            // Clear overlay negative cache so the rank shows immediately
            negativeCache.remove(key);
            // Also clear shard negative cache so future lookups aren't blocked
            pvpDataService.clearShardNegativeCache(playerName);
            log.debug("[Overlay] displayedRanks now contains {} entries, key={} has rank={}", displayedRanks.size(), key, displayedRanks.get(key));
        }
        catch (Exception e) {
            log.debug("[Overlay] setRankFromApi exception: {}", e.getMessage());
        }
    }

    public void holdApiOverride(String playerName, long millis)
    {
        // No-op now as service handles caching prioritization
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

    private final ClientThread clientThread;

    @Inject
    public RankOverlay(Client client, PvPLeaderboardConfig config, ClientThread clientThread, PvPDataService pvpDataService, OkHttpClient okHttpClient)
    {
        this.client = client;
        this.config = config;
        this.clientThread = clientThread;
        this.pvpDataService = pvpDataService;
        this.okHttpClient = okHttpClient;
        setPosition(OverlayPosition.DYNAMIC);
        // Draw over the 3D scene but under widgets/menus so menus cover the rank text
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_HIGHEST);
    }

    private CompletableFuture<String> fetchRankForUser(String name) {
        String bucket = bucketKey(config.rankBucket());
        PvPLeaderboardConfig.RankDisplayMode mode = config.rankDisplayMode();
        
        // Single path for ALL lookups - shard has all data
        return pvpDataService.getShardRankByName(name, bucket)
            .thenApply(sr -> {
                if (sr == null) return null;
                if (mode == PvPLeaderboardConfig.RankDisplayMode.RANK_NUMBER) {
                    return sr.rank > 0 ? "Rank " + sr.rank : null;
                }
                return sr.tier; // ICON and TEXT modes
            });
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (config == null || client == null) {
            return new Dimension(0, 0);
        }
        String currentBucket = bucketKey(config.rankBucket());
        PvPLeaderboardConfig.RankDisplayMode currentMode = config.rankDisplayMode();
        
        if (lastBucketKey == null || !lastBucketKey.equals(currentBucket) || lastDisplayMode != currentMode)
        {
            displayedRanks.clear();
            displayedRanksTimestamp.clear();
            negativeCache.clear();
            lastBucketKey = currentBucket;
            lastDisplayMode = currentMode;
            // debug("config changed to {}/{} -> clear transient state", currentBucket, currentMode);
            selfRankAttempted = false;
            nextSelfRankAllowedAtMs = 0L;
        }

        String localName = null; try { localName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null; } catch (Exception ignore) {}
        if (config.showOwnRank() && localName != null && selfRefreshRequestedAtMs > 0L)
        {
            String selfName = localName;
            long now = System.currentTimeMillis();
            if (now < selfRefreshRequestedAtMs)
            {
                // defer until request time
            }
            else if (!selfRankAttempted && now >= nextSelfRankAllowedAtMs)
            {
                selfRankAttempted = true;
                // debug("fetch self first name={} bucket={}", selfName, bucketKey(config.rankBucket()));
                selfRefreshRequestedAtMs = 0L;
                fetchRankForUser(selfName).thenAccept(rank -> {
                    if (rank != null)
                    {
                        String selfKey = NameUtils.canonicalKey(selfName);
                        displayedRanks.put(selfKey, rank);
                        displayedRanksTimestamp.put(selfKey, System.currentTimeMillis());
                        nextSelfRankAllowedAtMs = System.currentTimeMillis() + 60_000L;
                    }
                    else
                    {
                        nextSelfRankAllowedAtMs = System.currentTimeMillis() + 60_000L;
                    }
                });
            }
        }

        for (Player player : client.getTopLevelWorldView().players())
        {
            if (player == null || player.getName() == null)
            {
                continue;
            }
            if (player != client.getLocalPlayer() && !config.showOtherRanks())
            {
                continue;
            }
            if (player == client.getLocalPlayer() && !config.showOwnRank())
            {
                continue;
            }

            String playerName = player.getName();
            String nameKey = NameUtils.canonicalKey(playerName);
            String cachedRank = displayedRanks.get(nameKey);

            // Check if displayedRank has expired (1 hour TTL)
            // If expired, queue a background refresh but KEEP showing the stale rank
            boolean needsRefresh = false;
            if (cachedRank != null) {
                Long rankTime = displayedRanksTimestamp.get(nameKey);
                if (rankTime == null || System.currentTimeMillis() - rankTime >= DISPLAYED_RANK_TTL) {
                    needsRefresh = true;
                    // Don't clear - keep showing stale rank while refresh happens
                }
            }

            // Check negative cache
            boolean isNegative = false;
            Long negTime = negativeCache.get(nameKey);
            if (negTime != null) {
                if (System.currentTimeMillis() - negTime < NEGATIVE_CACHE_TTL) {
                    isNegative = true;
                } else {
                    negativeCache.remove(nameKey);
                }
            }

            // Queue lookup if: no cached rank, OR cached rank expired and needs refresh
            if ((cachedRank == null || needsRefresh) && !isNegative && !isRecentlyRequested(nameKey)) {
                 markRequested(nameKey);
                 // debug("Queueing auto-fetch for nearby player: {}", playerName);
                 queueLookup(playerName);
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
                        y = headLoc.getY() - iconSize - 2 + offsetY + config.rankIconOffsetYSelf();
                    }
                    else
                    {
                        x = nameLocation.getX() - iconSize / 2 + offsetX + config.rankIconOffsetXSelf();
                        y = nameLocation.getY() - fm.getAscent() - iconSize - 2 + offsetY + config.rankIconOffsetYSelf();
                    }
                }
                else
                {
                    int nameWidth = fm.stringWidth(playerName);
                    int centerX = nameLocation.getX() + Math.max(0, nameWidth) / 2 + offsetX + config.rankIconOffsetXOthers();
                    x = centerX - iconSize / 2;
                    y = nameLocation.getY() - fm.getAscent() - iconSize - 2 + offsetY + config.rankIconOffsetYOthers();
                }
                PvPLeaderboardConfig.RankDisplayMode mode = config.rankDisplayMode();
                if (mode == PvPLeaderboardConfig.RankDisplayMode.RANK_NUMBER)
                {
                    int centerX = x + iconSize / 2;
                    String text = cachedRank;
                    renderRankText(graphics, text, centerX, y, Math.max(10, config.rankTextSize()));
                }
                else if (mode == PvPLeaderboardConfig.RankDisplayMode.ICON)
                {
                    renderRankSpriteOrFallback(graphics, cachedRank, x, y, iconSize);
                }
                else 
                {
                    int centerX = x + iconSize / 2;
                    renderRankText(graphics, cachedRank, centerX, y, Math.max(10, config.rankTextSize()));
                }
            }
        }

        return new Dimension(0, 0);
    }

    private final Map<String, Long> requestTime = new ConcurrentHashMap<>();
    
    // Rate limiting state with concurrent lookup support
    private final java.util.Queue<String> pendingLookups = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile long lastLookupTimeMs = 0;
    private final java.util.concurrent.atomic.AtomicInteger activeLookupsCount = new java.util.concurrent.atomic.AtomicInteger(0);

    public void cleanupOldRequests()
    {
        long now = System.currentTimeMillis();
        // Increased TTL to 60s to reduce spam, but rely on negativeCache for long-term blocking
        requestTime.entrySet().removeIf(e -> (now - e.getValue()) > 60_000L);
    }

    public void processPendingLookups()
    {
        if (pendingLookups.isEmpty()) return;
        
        int maxConcurrent = getMaxConcurrentLookups();
        long now = System.currentTimeMillis();
        long delay = computeThrottleDelay();
        
        // Start as many lookups as we have slots for
        while (activeLookupsCount.get() < maxConcurrent && !pendingLookups.isEmpty()) {
            // Respect delay between starting new lookups
            if (now - lastLookupTimeMs < delay) {
                return;
            }
            
            String nextPlayer = pendingLookups.poll();
            if (nextPlayer == null) break;
            
            activeLookupsCount.incrementAndGet();
            lastLookupTimeMs = now;
            
            final String nameKey = NameUtils.canonicalKey(nextPlayer);
            
            try {
                fetchRankForUser(nextPlayer)
                    .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)  // Add timeout to prevent hanging
                    .whenComplete((r, ex) -> {
                        try {
                            if (ex != null) {
                                // Timeout or other exception - add to negative cache
                                // Clear stale rank if present
                                displayedRanks.remove(nameKey);
                                displayedRanksTimestamp.remove(nameKey);
                                negativeCache.put(nameKey, System.currentTimeMillis());
                            } else if (r != null) {
                                displayedRanks.put(nameKey, r);
                                displayedRanksTimestamp.put(nameKey, System.currentTimeMillis());
                                negativeCache.remove(nameKey);
                            } else {
                                // Player not found - clear stale rank if present
                                displayedRanks.remove(nameKey);
                                displayedRanksTimestamp.remove(nameKey);
                                negativeCache.put(nameKey, System.currentTimeMillis());
                            }
                        } finally {
                            activeLookupsCount.decrementAndGet();
                        }
                    });
            } catch (Exception e) {
                negativeCache.put(nameKey, System.currentTimeMillis());
                activeLookupsCount.decrementAndGet();
            }
        }
    }
    
    /**
     * Returns max concurrent lookups based on throttle level.
     * Level 8-10: 1 lookup (minimal network, max stability)
     * Level 5-7:  2 lookups
     * Level 0-4:  6→2 scaling (higher performance)
     */
    private int getMaxConcurrentLookups()
    {
        int level = Math.max(0, Math.min(10, config.lookupThrottleLevel()));
        if (level >= 8) return 1;       // 8-10: single lookup
        if (level >= 5) return 2;       // 5-7: 2 concurrent
        return Math.max(2, 6 - level);  // 0→6, 1→5, 2→4, 3→3, 4→2
    }
    
    /**
     * Delay between starting new lookups (ms).
     * Works in conjunction with getMaxConcurrentLookups() for total throughput control.
     */
    private long computeThrottleDelay()
    {
        int level = Math.max(0, Math.min(10, config.lookupThrottleLevel()));
        if (level == 0) return 50L;       // Fast: 50ms between starts
        if (level <= 4) return 100L * level;  // 100-400ms
        if (level <= 7) return 300L + (level - 4) * 150L;  // 450-750ms
        return 800L + (level - 7) * 400L; // 1200-2000ms for levels 8-10
    }

    private boolean isRecentlyRequested(String name) {
        Long t = requestTime.get(name);
        if (t == null) return false;
        // Increase loop check prevention to 60s. Real blocking is done by negativeCache (1hr)
        if (System.currentTimeMillis() - t > 60_000L) { 
            requestTime.remove(name);
            return false;
        }
        return true;
    }
    
    private void markRequested(String name) {
        requestTime.put(name, System.currentTimeMillis());
    }

    private void queueLookup(String playerName) {
        if (!pendingLookups.contains(playerName)) {
            pendingLookups.add(playerName);
        }
    }

    private void renderRankSpriteOrFallback(Graphics2D graphics, String rank, int x, int y, int size)
    {
        if (rank == null || rank.isEmpty())
        {
            BufferedImage fallback = resolveUnrankedIcon();
            if (fallback != null)
            {
                if (config.rankIconWhiteOutline())
                {
                    drawWhiteOutline(graphics, fallback, x, y, size);
                }
                graphics.drawImage(fallback, x, y, size, size, null);
            }
            return;
        }
        String[] parts = rank.split(" ");
        String rankName = parts.length > 0 ? parts[0] : rank;
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

        int centerX = x - textW / 2;
        int baseY = y + textH;

        // Check if 3rd Age - apply glow effect
        boolean isThirdAge = rankName.equals("3rd") || fullRank.startsWith("3rd");

        if (isThirdAge && !config.colorblindMode())
        {
            // Glowing white effect: multiple layers with decreasing alpha
            // Outer glow layers (largest spread, lowest alpha)
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
        // Return cached image if available
        BufferedImage cached = rankIconCache.get(rankName);
        if (cached != null)
        {
            return cached;
        }

        // Avoid fetching if we already failed or are fetching
        if (pendingIconFetches.contains(rankName))
        {
             return null;
        }

        // Calculate filename
        int division = 0;
        try { String[] partsFull = fullRank.split(" "); if (partsFull.length > 1) division = Integer.parseInt(partsFull[1]); } catch (Exception ignore) {}
        
        String filename = getS3IconFilename(rankName, division);
        if (filename == null) return null;

        // Trigger async fetch
        pendingIconFetches.add(rankName);
        fetchIconFromS3(rankName, filename);
        
        return null;
    }

    private String getS3IconFilename(String rankName, int division)
    {
        // Map rank + division to S3 filename.
        // Assuming file naming convention matches the weapons previously used:
        // Division 3 -> dagger
        // Division 2 -> scimitar
        // Division 1 -> 2h_sword
        // Rank "3rd Age" -> armadyl_godsword
        
        String weaponSuffix;
        switch (division)
        {
            case 3: weaponSuffix = "dagger"; break;
            case 2: weaponSuffix = "scimitar"; break;
            case 1: weaponSuffix = "2h_sword"; break;
            default: weaponSuffix = "scimitar"; break;
        }
        
        String normalizedRank = rankName.toLowerCase();
        
        if ("3rd age".equalsIgnoreCase(rankName) || rankName.startsWith("3rd "))
        {
            return "armadyl_godsword.png";
        }
        
        // e.g. bronze_dagger.png
        return normalizedRank + "_" + weaponSuffix + ".png";
    }

    private void fetchIconFromS3(String rankName, String filename)
    {
        String url = S3_BASE_URL + filename;
        Request request = new Request.Builder()
                .url(url)
                .build();
        
        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                // log.warn("Failed to fetch icon from S3: {}", url, e);
                // remove from pending so we can retry later if needed?
                // For now, leave in pending to avoid spamming errors
                // pendingIconFetches.remove(rankName);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null)
                {
                    response.close();
                    return;
                }
                try (InputStream is = body.byteStream())
                {
                    BufferedImage img = ImageIO.read(is);
                    if (img != null)
                    {
                        rankIconCache.put(rankName, img);
                    }
                }
                catch (Exception e)
                {
                    // log.debug("Error decoding image from S3: {}", url, e);
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    private BufferedImage getUnrankedIcon(int size)
    {
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
}
