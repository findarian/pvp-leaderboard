package com.pvp.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class RankCacheService
{
    private static final long CACHE_EXPIRY_MS = 60 * 60 * 1000; // 1 hour
    private static final String S3_BASE = "https://devsecopsautomated.com";
    
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CachedRank>> bucketCaches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> bucketLastRefreshMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> bucketLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> playerRetryAfterMs = new ConcurrentHashMap<>(); // key: bucket|player
    
    public CompletableFuture<String> getPlayerRank(String playerName, Object bucket)
    {
        return CompletableFuture.supplyAsync(() -> {
            String bucketKey = safeBucket(bucket);
            String canonPlayer = safePlayer(playerName);

            ConcurrentHashMap<String, CachedRank> bucketCache = bucketCaches.computeIfAbsent(bucketKey, k -> new ConcurrentHashMap<>());
            CachedRank cached = bucketCache.get(canonPlayer);
            
            if (cached != null && !cached.isExpired())
            {
                return cached.rank;
            }
            
            try
            {
                String playerKey = bucketKey + "|" + canonPlayer;
                long now = System.currentTimeMillis();

                Long retryAfter = playerRetryAfterMs.get(playerKey);
                if (retryAfter != null && now < retryAfter)
                {
                    return null; // Suppress retries for this player
                }

                // Throttle bucket refresh to at most once per minute when missing
                Long last = bucketLastRefreshMs.getOrDefault(bucketKey, 0L);
                if (now - last < 60_000)
                {
                    return null; // Recently refreshed and still missing
                }

                Object lock = bucketLocks.computeIfAbsent(bucketKey, k -> new Object());
                synchronized (lock)
                {
                    // Double-check throttling after acquiring lock
                    long checkLast = bucketLastRefreshMs.getOrDefault(bucketKey, 0L);
                    if (now - checkLast < 60_000)
                    {
                        return null;
                    }

                    // Perform refresh
                    try
                    {
                        refreshBucketCache(bucketKey, bucketCache);
                    }
                    catch (Exception e)
                    {
                        // On failure, set throttle timers to prevent spam
                        bucketLastRefreshMs.put(bucketKey, now);
                        playerRetryAfterMs.put(playerKey, now + 60_000);
                        throw e;
                    }
                    finally
                    {
                        bucketLastRefreshMs.put(bucketKey, now);
                    }
                }

                CachedRank after = bucketCache.get(canonPlayer);
                if (after == null)
                {
                    playerRetryAfterMs.put(playerKey, System.currentTimeMillis() + 60_000);
                    return null;
                }
                return after.rank;
            }
            catch (Exception e)
            {
                log.error("Failed to fetch rank for player {} in bucket {}", playerName, bucket, e);
                return null;
            }
        });
    }

    public void forceRefreshBucket(Object bucket)
    {
        String bucketKey = safeBucket(bucket);
        ConcurrentHashMap<String, CachedRank> bucketCache = bucketCaches.computeIfAbsent(bucketKey, k -> new ConcurrentHashMap<>());
        Object lock = bucketLocks.computeIfAbsent(bucketKey, k -> new Object());
        synchronized (lock)
        {
            try
            {
                refreshBucketCache(bucketKey, bucketCache);
                bucketLastRefreshMs.put(bucketKey, System.currentTimeMillis());
            }
            catch (Exception e)
            {
                log.error("Failed to force refresh bucket {}", bucketKey, e);
            }
        }
    }
    
    private void refreshBucketCache(String bucket, ConcurrentHashMap<String, CachedRank> bucketCache) throws Exception
    {
        String urlStr = urlForBucket(bucket);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null)
        {
            response.append(line);
        }
        reader.close();
        
        JsonObject data = JsonParser.parseString(response.toString()).getAsJsonObject();
        if (data == null || !data.has("players") || data.get("players").isJsonNull())
        {
            return; // Nothing to cache
        }
        JsonArray players = data.getAsJsonArray("players");

        long now = System.currentTimeMillis();
        bucketCache.clear();
        for (int i = 0; i < players.size(); i++)
        {
            try
            {
                JsonObject player = players.get(i).getAsJsonObject();
                if (player == null) continue;

                java.util.List<String> allNames = new java.util.ArrayList<>();
                if (player.has("player_name") && !player.get("player_name").isJsonNull())
                {
                    allNames.add(player.get("player_name").getAsString());
                }
                if (player.has("player_names") && player.get("player_names").isJsonArray())
                {
                    JsonArray names = player.getAsJsonArray("player_names");
                    for (int ni = 0; ni < names.size(); ni++)
                    {
                        if (!names.get(ni).isJsonNull())
                        {
                            allNames.add(names.get(ni).getAsString());
                        }
                    }
                }
                if (allNames.isEmpty())
                {
                    continue;
                }

                Double mmr = null;
                if (player.has("mmr") && !player.get("mmr").isJsonNull())
                {
                    mmr = player.get("mmr").getAsNumber().doubleValue();
                }
                else if (player.has("player_mmr") && !player.get("player_mmr").isJsonNull())
                {
                    mmr = player.get("player_mmr").getAsNumber().doubleValue();
                }
                if (mmr == null)
                {
                    continue;
                }

                String rank = calculateRankFromMMR(mmr);
                for (String nm : allNames)
                {
                    if (nm != null && !nm.isEmpty())
                    {
                        bucketCache.put(nm.trim().toLowerCase(), new CachedRank(rank, now));
                    }
                }
            }
            catch (Exception ignore)
            {
                // Skip malformed entries
            }
        }
    }

    private static String safeBucket(Object bucket)
    {
        if (bucket == null) return "overall";
        String b;
        if (bucket instanceof PvPLeaderboardConfig.RankBucket)
        {
            switch ((PvPLeaderboardConfig.RankBucket) bucket)
            {
                case NH: return "nh";
                case VENG: return "veng";
                case MULTI: return "multi";
                case DMM: return "dmm";
                case OVERALL:
                default: return "overall";
            }
        }
        b = String.valueOf(bucket).trim().toLowerCase();
        switch (b)
        {
            case "overall":
            case "nh":
            case "veng":
            case "multi":
            case "dmm":
                return b;
            default:
                return "overall";
        }
    }

    private static String safePlayer(String name)
    {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static String urlForBucket(String bucket)
    {
        switch (safeBucket(bucket))
        {
            case "nh":
                return S3_BASE + "/leaderboard_nh.json";
            case "veng":
                return S3_BASE + "/leaderboard_veng.json";
            case "multi":
                return S3_BASE + "/leaderboard_multi.json";
            case "dmm":
                return S3_BASE + "/leaderboard_dmm.json";
            case "overall":
            default:
                return S3_BASE + "/leaderboard.json";
        }
    }
    
    private String calculateRankFromMMR(double mmr)
    {
        String[][] thresholds = {
            {"Bronze", "3", "0"}, {"Bronze", "2", "170"}, {"Bronze", "1", "240"},
            {"Iron", "3", "310"}, {"Iron", "2", "380"}, {"Iron", "1", "450"},
            {"Steel", "3", "520"}, {"Steel", "2", "590"}, {"Steel", "1", "660"},
            {"Black", "3", "730"}, {"Black", "2", "800"}, {"Black", "1", "870"},
            {"Mithril", "3", "940"}, {"Mithril", "2", "1010"}, {"Mithril", "1", "1080"},
            {"Adamant", "3", "1150"}, {"Adamant", "2", "1250"}, {"Adamant", "1", "1350"},
            {"Rune", "3", "1450"}, {"Rune", "2", "1550"}, {"Rune", "1", "1650"},
            {"Dragon", "3", "1750"}, {"Dragon", "2", "1850"}, {"Dragon", "1", "1950"},
            {"3rd Age", "0", "2100"}
        };
        
        String[] current = thresholds[0];
        for (String[] threshold : thresholds)
        {
            if (mmr >= Double.parseDouble(threshold[2]))
            {
                current = threshold;
            }
            else
            {
                break;
            }
        }
        
        String rank = current[0];
        int division = Integer.parseInt(current[1]);
        return rank + (division > 0 ? " " + division : "");
    }
    
    private static class CachedRank
    {
        final String rank;
        final long timestamp;
        
        CachedRank(String rank, long timestamp)
        {
            this.rank = rank;
            this.timestamp = timestamp;
        }
        
        boolean isExpired()
        {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS;
        }
    }
}