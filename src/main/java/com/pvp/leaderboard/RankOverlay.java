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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@SuppressWarnings("deprecation")
public class RankOverlay extends Overlay
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final PvPLeaderboardPlugin plugin;
    private final ItemManager itemManager;

    private final ConcurrentHashMap<String, String> displayedRanks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> fetchInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> attemptedLookup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> attemptedAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> loggedFetch = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> rankIconCache = new ConcurrentHashMap<>();
    // When present, prefer API-derived rank and ignore shard results until cleared
    private final ConcurrentHashMap<String, Long> apiOverrideUntilMs = new ConcurrentHashMap<>();
    private String lastBucketKey = null;
    private long lastShardNotReadyLogMs = 0L;
    
    private long lastScheduleMs = 0L;
    private volatile boolean selfRankAttempted = false;
    private volatile long nextSelfRankAllowedAtMs = 0L;
    // private long lastSelfLogMs = 0L; // debug disabled
    private static final long RANK_CACHE_TTL_MS = 60L * 60L * 1000L;
    private static final long NAME_RETRY_BACKOFF_MS = 60L * 60L * 1000L;
    private final Map<String, CacheEntry> nameRankCache = Collections.synchronizedMap(
        new LinkedHashMap<String, CacheEntry>(512, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest)
            {
                return size() > 4096;
            }
        }
    );
    private static class CacheEntry {
        final String rank;
        final long ts;
        CacheEntry(String r, long t) {
            this.rank = r;
            this.ts = t;
        }
    }

    private String cacheKeyFor(String name)
    {
        String normalized = (name == null ? "" : name.trim().replaceAll("\\s+"," "));
        return bucketKey(config.rankBucket()) + "|" + normalized;
    }
    private static long computeThrottleDelayMs(int level)
    {
        if (level <= 0) return 10L;          // level 0 → 10ms
        if (level >= 10) return 2000L;       // level 10 → 2000ms
        if (level <= 5)
        {
            // Linear from 0:10ms to 5:200ms → +38ms per level
            return 10L + (long) (38L * level);
        }
        // Linear from 5:200ms to 10:2000ms → +360ms per level
        return 200L + (long) ((level - 5L) * 360L);
    }
    public void scheduleSelfRankRefresh(long delayMs)
    {
        long now = System.currentTimeMillis();
        nextSelfRankAllowedAtMs = now + Math.max(0L, delayMs);
        selfRankAttempted = false;
        // try { log.debug("[Overlay] scheduleSelfRankRefresh delayMs={} nextAllowedAtMs={}", delayMs, nextSelfRankAllowedAtMs); } catch (Exception ignore) {}
        // Do not clear the currently displayed self rank; keep it while the refresh happens
        // to avoid visual flicker during combat or after submissions.
        try
        {
            if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            {
                String self = client.getLocalPlayer().getName();
                try { nameRankCache.remove(cacheKeyFor(self)); } catch (Exception ignore) {}
            }
        }
        catch (Exception ignore) {}
    }

    public void resetLookupStateOnWorldHop()
    {
        try
        {
            // Preserve self rank across scene changes to avoid flicker
            String selfName = null; String selfRank = null;
            try {
                selfName = (client != null && client.getLocalPlayer() != null) ? client.getLocalPlayer().getName() : null;
                if (selfName != null) selfRank = displayedRanks.get(selfName);
            } catch (Exception ignore) {}
            displayedRanks.clear();
            if (selfName != null && selfRank != null) {
                displayedRanks.put(selfName, selfRank);
            }
            fetchInFlight.clear();
            attemptedLookup.clear();
            attemptedAtMs.clear();
            loggedFetch.clear();
            lastScheduleMs = 0L;
            selfRankAttempted = false;
            nextSelfRankAllowedAtMs = 0L;
            // try { log.debug("[Overlay] reset on world hop; preservedSelf={} hasRank={}", selfName, (selfRank != null)); } catch (Exception ignore) {}
        }
        catch (Exception ignore) {}
    }

    public String getCachedRankFor(String playerName)
    {
        return displayedRanks.get(playerName);
    }

    public void forceLookupAndDisplay(String playerName)
    {
        if (playerName == null || playerName.trim().isEmpty()) return;
        try
        {
            attemptedLookup.remove(playerName);
            displayedRanks.remove(playerName);
            if (fetchInFlight.putIfAbsent(playerName, Boolean.TRUE) == null)
            {
                log.info("[Overlay] forced fetch for player={} bucket={}", playerName, bucketKey(config.rankBucket()));
                final String ln; try { ln = client != null && client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null; } catch (Exception ex) { /* fallback */ return; }
                final boolean isSelf = (ln != null && ln.equals(playerName));
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return plugin != null ? plugin.resolvePlayerRankNoClient(playerName, bucketKey(config.rankBucket()), isSelf) : null;
                    } catch (Exception e) { return null; }
                }, scheduler).thenAccept(rank -> {
                    try
                    {
                        if (rank != null)
                        {
                            displayedRanks.put(playerName, rank);
                            if (loggedFetch.putIfAbsent(playerName, Boolean.TRUE) == null)
                            {
                                log.debug("Fetched rank for {}: {}", playerName, rank);
                            }
                        }
                        else if (loggedFetch.putIfAbsent(playerName, Boolean.TRUE) == null)
                        {
                            log.info("No rank found for {} (no retry)", playerName);
                        }
                    }
                    finally
                    {
                        fetchInFlight.remove(playerName);
                    }
                });
            }
        }
        catch (Exception ignore) {}
    }

    public void setRankFromApi(String playerName, String rank)
    {
        if (playerName == null || playerName.trim().isEmpty()) return;
        if (rank == null || rank.trim().isEmpty()) return;
        try
        {
            displayedRanks.put(playerName, rank);
            try { nameRankCache.put(cacheKeyFor(playerName), new CacheEntry(rank, System.currentTimeMillis())); } catch (Exception ignore) {}
            // Mark override: self = indefinite, others = 15s window
            long until;
            try {
                String self = (client != null && client.getLocalPlayer() != null) ? client.getLocalPlayer().getName() : null;
                boolean isSelf = (self != null && self.equals(playerName));
                until = isSelf ? Long.MAX_VALUE : (System.currentTimeMillis() + 15_000L);
            } catch (Exception e) {
                until = System.currentTimeMillis() + 15_000L;
            }
            try { apiOverrideUntilMs.put(playerName, until); } catch (Exception ignore) {}
        }
        catch (Exception ignore) {}
    }

    public void holdApiOverride(String playerName, long millis)
    {
        if (playerName == null || playerName.trim().isEmpty()) return;
        long until = System.currentTimeMillis() + Math.max(0L, millis);
        try { apiOverrideUntilMs.put(playerName, until); } catch (Exception ignore) {}
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

    private final ScheduledExecutorService scheduler;

    @Inject
    public RankOverlay(Client client, PvPLeaderboardConfig config, ItemManager itemManager, PvPLeaderboardPlugin plugin, ScheduledExecutorService scheduler)
    {
        this.client = client;
        this.config = config;
        this.itemManager = itemManager;
        this.plugin = plugin;
        this.scheduler = scheduler;
        setPosition(OverlayPosition.DYNAMIC);
        // Draw over the 3D scene but under widgets/menus so menus cover the rank text
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // lookupThrottleLevel is honored below in per-tick scheduling and in-flight limits
        // Gate on shard readiness instead of fixed delay
        if (plugin != null && !plugin.isShardReady())
        {
            long now = System.currentTimeMillis();
            if (now - lastShardNotReadyLogMs >= 1000L)
            {
                log.info("[Overlay] shard not ready yet; skipping render");
                lastShardNotReadyLogMs = now;
            }
            return new Dimension(0, 0);
        }
        // Show text if enabled, otherwise show icons

        if (config == null || client == null) {
            return new Dimension(0, 0);
        }
        String currentBucket = bucketKey(config.rankBucket());
        if (lastBucketKey == null || !lastBucketKey.equals(currentBucket))
        {
            // Do not clear nameRankCache (1h persistence across buckets); just reset transient state
            // On bucket switch, clear all so a bucket with no rank hides self rank
            displayedRanks.clear();
            fetchInFlight.clear();
            loggedFetch.clear();
            attemptedLookup.clear();
            attemptedAtMs.clear();
            lastBucketKey = currentBucket;
            // Allow self lookup again immediately on bucket change and clear cached overlay values for self
            selfRankAttempted = false;
            nextSelfRankAllowedAtMs = 0L;
            // Keep nameRankCache (1h TTL per bucket); do not purge here
        }

        // Always prioritize fetching the local player's rank first, but only once until explicitly refreshed
        String localName = null; try { localName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null; } catch (Exception ignore) {}
        if (config.showOwnRank() && localName != null)
        {
            String selfName = localName;
            long now = System.currentTimeMillis();
            if (!selfRankAttempted && now >= nextSelfRankAllowedAtMs)
            {
                if (fetchInFlight.putIfAbsent(selfName, Boolean.TRUE) == null)
                {
                    selfRankAttempted = true;
                    log.debug("[Overlay] fetch self first name={} bucket={}", selfName, bucketKey(config.rankBucket()));
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return plugin != null ? plugin.resolvePlayerRankNoClient(selfName, bucketKey(config.rankBucket()), true) : null;
                        } catch (Exception e) { return null; }
                    }, scheduler).thenAccept(rank -> {
                        try
                        {
                            if (rank != null)
                            {
                                Long until = apiOverrideUntilMs.get(selfName);
                                boolean overrideActive = until != null && System.currentTimeMillis() < until;
                                if (!overrideActive)
                                {
                                    displayedRanks.put(selfName, rank);
                                    try { nameRankCache.put(cacheKeyFor(selfName), new CacheEntry(rank, System.currentTimeMillis())); } catch (Exception ignore) {}
                                    if (loggedFetch.putIfAbsent(selfName, Boolean.TRUE) == null)
                                    {
                                        log.debug("Fetched rank for {}: {}", selfName, rank);
                                    }
                                }
                                // If override is active, keep API-derived value; do not clear the override here
                            }
                            else if (loggedFetch.putIfAbsent(selfName, Boolean.TRUE) == null)
                            {
                                log.info("No rank found for {} (no retry)", selfName);
                            }
                        }
                        finally
                        {
                            fetchInFlight.remove(selfName);
                        }
                    });
                }
            }
            else
            {
                // debug disabled: self fetch gated logs
                // long nowMs = System.currentTimeMillis();
                // if (nowMs - lastSelfLogMs >= 1000L) {
                //     try { log.debug("[Overlay] self fetch gated attempted={} waitMs={}", selfRankAttempted, Math.max(0L, nextSelfRankAllowedAtMs - nowMs)); } catch (Exception ignore) {}
                //     lastSelfLogMs = nowMs;
                // }
            }
        }
        else
        {
            // debug disabled: local name null logs
            // long nowMs = System.currentTimeMillis();
            // if (nowMs - lastSelfLogMs >= 1000L) {
            //     try { log.debug("[Overlay] local player name null; skipping self schedule"); } catch (Exception ignore) {}
            //     lastSelfLogMs = nowMs;
            // }
        }

        java.util.HashSet<String> present = new java.util.HashSet<>();
        // Apply per-tick backpressure: schedule at most a few fetches per game tick
        int currentTick = 0; try { currentTick = client.getTickCount(); } catch (Exception ignore) {}
        if (currentTick != lastFetchTick) { lastFetchTick = currentTick; scheduledThisTick = 0; }
        int level = config != null ? Math.max(0, Math.min(10, config.lookupThrottleLevel())) : 0;
        int maxPerTick = Math.max(1, 2 + (10 - level) / 4); // lower level → more per tick
        for (Player player : client.getPlayers())
        {
            if (player == null || player.getName() == null)
            {
                continue;
            }
            if (player != client.getLocalPlayer() && !config.showOtherRanks())
            {
                continue; // skip others when disabled
            }
            if (player == client.getLocalPlayer() && !config.showOwnRank())
            {
                continue; // skip self when disabled
            }

            String playerName = player.getName();
            present.add(playerName);
            String cachedRank = displayedRanks.get(playerName);
            // Serve from 1-hour cache if available
            try
            {
                String ck = cacheKeyFor(playerName);
                CacheEntry ce = nameRankCache.get(ck);
                if (ce != null && (System.currentTimeMillis() - ce.ts) < RANK_CACHE_TTL_MS)
                {
                    displayedRanks.put(playerName, ce.rank);
                    cachedRank = ce.rank;
                }
            }
            catch (Exception ignore) {}

            // If API override is active, use cached rank and skip fetch
            Long apiUntil = apiOverrideUntilMs.get(playerName);
            boolean apiActive = apiUntil != null && System.currentTimeMillis() < apiUntil;
            if (cachedRank == null && !apiActive)
            {
                Long firstAttempt = attemptedAtMs.get(playerName);
                long now = System.currentTimeMillis();
                if (firstAttempt != null && now - firstAttempt < NAME_RETRY_BACKOFF_MS) {
                    continue; // skip re-attempts for 60s
                }
                // Skip scheduling if name is within 1h cache window of a previous miss
                // This mirrors DashboardPanel's negative cache but avoids any network
                if (firstAttempt != null && now - firstAttempt < NAME_RETRY_BACKOFF_MS)
                {
                    continue;
                }
                // Limit concurrent lookups based on throttle level
                int maxConcurrent;
                if (level <= 0) maxConcurrent = 10;
                else if (level >= 10) maxConcurrent = 1;
                else if (level <= 5) maxConcurrent = 10 - (level - 1); // 1->10, 5->6
                else maxConcurrent = Math.max(1, 11 - level); // 6->5, 9->2
                if (fetchInFlight.size() >= maxConcurrent)
                {
                    continue;
                }
                // Respect per-tick schedule limit and bounded queue
                if (scheduledThisTick >= maxPerTick) {
                    continue;
                }
                // Using shared scheduler; rely on per-tick and in-flight limits for backpressure
                // Also space out scheduling to avoid bursts
                long perScheduleDelayMs = computeThrottleDelayMs(level);
                long nowMsFetch = System.currentTimeMillis();
                if (perScheduleDelayMs > 0 && nowMsFetch - lastScheduleMs < perScheduleDelayMs)
                {
                    continue;
                }
                lastScheduleMs = nowMsFetch;

                if (fetchInFlight.putIfAbsent(playerName, Boolean.TRUE) == null)
                {
                    attemptedLookup.put(playerName, Boolean.TRUE);
                    attemptedAtMs.put(playerName, now);
                    // Only log once per name per bucket to avoid spam in crowded areas
                    if (loggedFetch.putIfAbsent(playerName, Boolean.TRUE) == null)
                    {
                        log.debug("[Overlay] fetch once for player={} bucket={}", playerName, bucketKey(config.rankBucket()));
                    }
                    scheduledThisTick++;
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return plugin != null ? plugin.resolvePlayerRankNoClient(playerName, bucketKey(config.rankBucket()), false) : null;
                        } catch (Exception e) { return null; }
                    }, scheduler).thenAccept(rank -> {
                        try
                        {
                            if (rank != null)
                            {
                                // If API override exists, keep it and clear override only when shard confirms presence
                                Long until = apiOverrideUntilMs.get(playerName);
                                boolean overrideActive = until != null && System.currentTimeMillis() < until;
                                if (!overrideActive)
                                {
                                    displayedRanks.put(playerName, rank);
                                    try { nameRankCache.put(cacheKeyFor(playerName), new CacheEntry(rank, System.currentTimeMillis())); } catch (Exception ignore) {}
                                }
                                // If override is active, keep API-derived value; do not clear the override early
                                try { nameRankCache.put(cacheKeyFor(playerName), new CacheEntry(rank, System.currentTimeMillis())); } catch (Exception ignore) {}
                                if (loggedFetch.putIfAbsent(playerName, Boolean.TRUE) == null)
                                {
                                    log.debug("Fetched rank for {}: {}", playerName, rank);
                                }
                            }
                            else if (loggedFetch.putIfAbsent(playerName, Boolean.TRUE) == null)
                            {
                                log.info("No rank found for {} (no retry)", playerName);
                            }
                        }
                        finally
                        {
                            fetchInFlight.remove(playerName);
                        }
                    });
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
                    // Force display as Rank N
                    String text = cachedRank;
                    try
                    {
                        if (text != null && !text.startsWith("Rank"))
                        {
                            // Attempt to derive world rank index from plugin using name+bucket
                            int idx = plugin != null ? plugin.getWorldRankIndex(playerName, bucketKey(config.rankBucket())) : -1;
                            if (idx > 0) text = "Rank " + idx;
                        }
                    }
                    catch (Exception ignore) {}
                    renderRankText(graphics, text, centerX, y, Math.max(10, config.rankTextSize()));
                }
                else if (mode == PvPLeaderboardConfig.RankDisplayMode.ICON)
                {
                    renderRankSpriteOrFallback(graphics, cachedRank, x, y, iconSize);
                }
                else // TEXT
                {
                    int centerX = x + iconSize / 2;
                    renderRankText(graphics, cachedRank, centerX, y, Math.max(10, config.rankTextSize()));
                }
            }
        }

        // prune attempt state for players that left
        try {
            for (String name : attemptedLookup.keySet())
            {
                if (!present.contains(name))
                {
                    attemptedLookup.remove(name);
                    attemptedAtMs.remove(name);
                }
            }
        } catch (Exception ignore) {}

        return new Dimension(0, 0);
    }

    // Low-priority bounded executor for overlay lookups to avoid stuttering the client.
    // This is intentionally separate from the shared scheduler because:
    // - Work is latency/tick sensitive and uses a bounded queue with discard policy to avoid frame hitches.
    // - Thread count is dynamically tuned per throttle level and may be 1-3.
    // - Tasks are short-lived network lookups and image resolves; isolating protects client thread.
    // Using shared injected scheduler; no custom executor

    // Per-tick scheduling counters
    private int lastFetchTick = -1;
    private int scheduledThisTick = 0;

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
        // No sprite found; skip rendering to avoid extra overdraw
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

        if (fullRank.startsWith("Rank "))
        {
            g.setColor(Color.WHITE);
        }
        else
        {
            g.setColor(getRankColor(rankName));
        }
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

    // Removed unused abbreviation helper
}