package com.pvp.leaderboard.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.cache.WhitelistPlayerCache;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service to fetch whitelist data and send heartbeats.
 * 
 * Whitelist Behavior:
 * - Fetches once per hour on success
 * - On failure, retries every 15 seconds up to 5 times
 * - After 5 failures, waits 1 hour before trying again
 * - If nothing is returned, existing cache is preserved (not cleared)
 * 
 * Heartbeat Behavior:
 * - Sends POST every 15 minutes (including on login)
 * - Only sends when username is available
 * 
 * Authentication (both endpoints):
 * - X-Client-Unique-Id header (UUID)
 * - User-Agent header
 */
@Slf4j
@Singleton
public class WhitelistService
{
    private static final String WHITELIST_URL = "https://devsecopsautomated.com/whitelist.json";
    private static final String HEARTBEAT_URL = "https://l5xya0wf0d.execute-api.us-east-1.amazonaws.com/prod/heartbeat";
    private static final String USER_AGENT = "RuneLite/" + RuneLiteProperties.getVersion();
    
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 15_000L; // 15 seconds
    private static final long WHITELIST_REFRESH_MS = (9L * 60L + 30L) * 1000L; // 9 minutes 30 seconds
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L; // 5 minutes
    
    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final PvPLeaderboardConfig config;
    private final WhitelistPlayerCache cache;
    private final ScheduledExecutorService scheduler;
    private final ClientIdentityService clientIdentityService;
    
    // Whitelist state
    private final AtomicBoolean fetchInProgress = new AtomicBoolean(false);
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private volatile long lastSuccessfulFetchMs = 0L;
    private volatile long nextFetchAllowedMs = 0L;
    
    // Heartbeat state
    private volatile long lastHeartbeatMs = 0L;
    private volatile String currentUsername = null;
    
    @Inject
    public WhitelistService(OkHttpClient okHttpClient, Gson gson, PvPLeaderboardConfig config,
                           WhitelistPlayerCache cache, ScheduledExecutorService scheduler,
                           ClientIdentityService clientIdentityService)
    {
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.config = config;
        this.cache = cache;
        this.scheduler = scheduler;
        this.clientIdentityService = clientIdentityService;
    }
    
    /**
     * Called when player logs in. Starts heartbeat and fetch cycles.
     * 
     * @param username The player's username (required for heartbeat)
     */
    public void onLogin(String username)
    {
        if (username == null || username.trim().isEmpty())
        {
            log.debug("[Whitelist] No username provided, skipping login tasks");
            return;
        }
        
        this.currentUsername = username.trim();
        log.debug("[Heartbeat] Player logged in: {} - starting heartbeat cycle", currentUsername);
        
        // Send immediate heartbeat on login
        log.debug("[Heartbeat] Sending initial heartbeat on login");
        sendHeartbeat();
        
        // Schedule recurring heartbeat every 5 minutes
        scheduleHeartbeat();
        
        // Fetch whitelist if enabled and needed
        if (config.enableWhitelistRanks())
        {
            long now = System.currentTimeMillis();
            if (lastSuccessfulFetchMs == 0L || now - lastSuccessfulFetchMs >= WHITELIST_REFRESH_MS)
            {
                if (now >= nextFetchAllowedMs)
                {
                    startFetch();
                }
            }
        }
    }
    
    /**
     * Called when player logs out.
     */
    public void onLogout()
    {
        log.debug("[Heartbeat] Player logged out - stopping heartbeat cycle");
        currentUsername = null;
    }
    
    /**
     * Schedule the next heartbeat in 5 minutes.
     */
    private void scheduleHeartbeat()
    {
        log.debug("[Heartbeat] Scheduling next heartbeat in {} minutes", HEARTBEAT_INTERVAL_MS / 60000);
        scheduler.schedule(() -> {
            if (currentUsername != null)
            {
                log.debug("[Heartbeat] Scheduled heartbeat triggered for user: {}", currentUsername);
                sendHeartbeat();
                scheduleHeartbeat(); // Schedule next one
            }
            else
            {
                log.debug("[Heartbeat] Scheduled heartbeat skipped - no user logged in");
            }
        }, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Send a heartbeat to the server.
     * Only sends if "Show your rank to others" is enabled.
     */
    private void sendHeartbeat()
    {
        if (!config.showRankToOthers())
        {
            log.debug("[Heartbeat] Skipped - 'Show your rank to others' is disabled");
            return;
        }
        
        String username = currentUsername;
        if (username == null || username.isEmpty())
        {
            log.debug("[Heartbeat] No username available, skipping heartbeat");
            return;
        }
        
        String clientUuid = clientIdentityService.getClientUniqueId();
        if (clientUuid == null || clientUuid.isEmpty())
        {
            log.debug("[Heartbeat] No client UUID available, skipping heartbeat");
            return;
        }
        
        // Build JSON body with username
        String jsonBody = "{\"username\":\"" + username.replace("\"", "\\\"") + "\"}";
        
        log.debug("[Heartbeat] Sending heartbeat - URL: {} | UUID: {} | User-Agent: {} | Body: {}",
            HEARTBEAT_URL, clientUuid, USER_AGENT, jsonBody);
        
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json"),
            jsonBody
        );
        
        Request request = new Request.Builder()
            .url(HEARTBEAT_URL)
            .header("X-Client-Unique-Id", clientUuid)
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build();
        
        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[Heartbeat] Network failure - Error: {} | Type: {}", e.getMessage(), e.getClass().getSimpleName());
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    String responseBody = res.body() != null ? res.body().string() : "(empty)";
                    
                    if (res.isSuccessful())
                    {
                        lastHeartbeatMs = System.currentTimeMillis();
                        log.debug("[Heartbeat] Success - HTTP {} | User: {} | Response: {}", 
                            res.code(), username, responseBody);
                    }
                    else
                    {
                        log.debug("[Heartbeat] HTTP error - Code: {} | Message: {} | Response: {}", 
                            res.code(), res.message(), responseBody);
                    }
                }
            }
        });
    }
    
    /**
     * Start a fetch attempt. Handles retry logic internally.
     */
    private void startFetch()
    {
        if (!fetchInProgress.compareAndSet(false, true))
        {
            return; // Already fetching
        }
        
        retryCount.set(0);
        doFetch();
    }
    
    /**
     * Execute the actual HTTP fetch.
     */
    private void doFetch()
    {
        String clientUuid = clientIdentityService.getClientUniqueId();
        if (clientUuid == null || clientUuid.isEmpty())
        {
            log.debug("[Whitelist] No client UUID available, skipping fetch");
            handleFailure();
            return;
        }
        
        log.debug("[Whitelist] Fetching from URL (attempt {})", retryCount.get() + 1);
        
        Request request = new Request.Builder()
            .url(WHITELIST_URL)
            .header("X-Client-Unique-Id", clientUuid)
            .header("User-Agent", USER_AGENT)
            .get()
            .build();
        
        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[Whitelist] Fetch failed: {}", e.getMessage());
                handleFailure();
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        log.debug("[Whitelist] HTTP error: {}", res.code());
                        handleFailure();
                        return;
                    }
                    
                    ResponseBody body = res.body();
                    if (body == null)
                    {
                        log.debug("[Whitelist] Empty response body");
                        handleFailure();
                        return;
                    }
                    
                    String bodyString = body.string();
                    Map<String, WhitelistPlayerCache.PlayerRanks> parsed = parseResponse(bodyString);
                    
                    if (parsed != null && !parsed.isEmpty())
                    {
                        cache.loadWhitelist(parsed);
                        handleSuccess();
                    }
                    else
                    {
                        // Nothing found - don't update cache, treat as failure for retry purposes
                        log.debug("[Whitelist] No data in response, preserving existing cache");
                        handleFailure();
                    }
                }
                catch (Exception e)
                {
                    log.debug("[Whitelist] Parse error: {}", e.getMessage());
                    handleFailure();
                }
            }
        });
    }
    
    /**
     * Handle successful fetch.
     */
    private void handleSuccess()
    {
        long now = System.currentTimeMillis();
        lastSuccessfulFetchMs = now;
        nextFetchAllowedMs = now + WHITELIST_REFRESH_MS;
        fetchInProgress.set(false);
        retryCount.set(0);
        
        log.debug("[Whitelist] Fetch successful, {} players loaded. Next fetch in 9:30.", cache.size());
        
        // Schedule next fetch
        scheduler.schedule(this::startFetch, WHITELIST_REFRESH_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Handle failed fetch - retry or schedule next attempt.
     */
    private void handleFailure()
    {
        int attempts = retryCount.incrementAndGet();
        
        if (attempts < MAX_RETRIES)
        {
            // Retry in 15 seconds
            log.debug("[Whitelist] Retry {} of {} in 15 seconds", attempts, MAX_RETRIES);
            scheduler.schedule(this::doFetch, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        else
        {
            // Max retries reached, wait 1 hour
            long now = System.currentTimeMillis();
            nextFetchAllowedMs = now + WHITELIST_REFRESH_MS;
            fetchInProgress.set(false);
            retryCount.set(0);
            
            log.debug("[Whitelist] Max retries reached. Next attempt in 9:30.");
            scheduler.schedule(this::startFetch, WHITELIST_REFRESH_MS, TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * Parse the JSON response into PlayerRanks objects.
     * 
     * Expected format:
     * {
     *   "players": [
     *     {
     *       "name": "Toyco",
     *       "overall": {"tier": "Adamant2", "rank": 2, "mmr": 1335.74},
     *       "nh": {"tier": "Adamant1", "rank": 5, "mmr": 1430.66},
     *       "veng": {"tier": "Adamant3", "rank": 1, "mmr": 1247.32},
     *       "multi": {"tier": "Rune3", "rank": 1, "mmr": 1487.13},
     *       "dmm": {"tier": "Mithril3", "rank": 14, "mmr": 1003.3}
     *     }
     *   ]
     * }
     */
    private Map<String, WhitelistPlayerCache.PlayerRanks> parseResponse(String json)
    {
        Map<String, WhitelistPlayerCache.PlayerRanks> result = new HashMap<>();
        
        try
        {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            
            if (root.has("players") && root.get("players").isJsonArray())
            {
                for (var element : root.getAsJsonArray("players"))
                {
                    if (!element.isJsonObject()) continue;
                    JsonObject player = element.getAsJsonObject();
                    parsePlayer(player, result);
                }
            }
        }
        catch (Exception e)
        {
            log.debug("[Whitelist] JSON parse error: {}", e.getMessage());
        }
        
        return result;
    }
    
    private static final String[] BUCKETS = {"overall", "nh", "veng", "multi", "dmm"};
    
    /**
     * Parse a single player object with per-bucket rank data.
     */
    private void parsePlayer(JsonObject player, Map<String, WhitelistPlayerCache.PlayerRanks> result)
    {
        String name = getStringField(player, "name");
        if (name == null || name.isEmpty()) return;
        
        Map<String, WhitelistPlayerCache.BucketRank> buckets = new HashMap<>();
        
        for (String bucket : BUCKETS)
        {
            if (player.has(bucket) && player.get(bucket).isJsonObject())
            {
                JsonObject bucketData = player.getAsJsonObject(bucket);
                String tier = getStringField(bucketData, "tier");
                int rank = getIntField(bucketData, "rank");
                double mmr = getDoubleField(bucketData, "mmr");
                
                if (tier != null || rank > 0)
                {
                    buckets.put(bucket, new WhitelistPlayerCache.BucketRank(tier, rank, mmr));
                }
            }
        }
        
        if (!buckets.isEmpty())
        {
            result.put(name, new WhitelistPlayerCache.PlayerRanks(buckets));
        }
    }
    
    private String getStringField(JsonObject obj, String field)
    {
        if (obj.has(field) && !obj.get(field).isJsonNull())
        {
            return obj.get(field).getAsString();
        }
        return null;
    }
    
    private int getIntField(JsonObject obj, String field)
    {
        if (obj.has(field) && !obj.get(field).isJsonNull())
        {
            return obj.get(field).getAsInt();
        }
        return 0;
    }
    
    private double getDoubleField(JsonObject obj, String field)
    {
        if (obj.has(field) && !obj.get(field).isJsonNull())
        {
            return obj.get(field).getAsDouble();
        }
        return 0.0;
    }
}

