package com.pvp.leaderboard.cache;

import com.pvp.leaderboard.util.NameUtils;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for looked-up players' ranks.
 * Stores all bucket ranks for each player and manages cache expiration.
 * Cache duration is 6 hours.
 */
@Slf4j
@Singleton
public class LookedUpPlayerCache
{
    // 6 hour cache duration
    private static final long CACHE_DURATION_MS = 6L * 60L * 60L * 1000L;

    // Cache storage: canonical player name -> entry
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    @Inject
    public LookedUpPlayerCache()
    {
    }

    /**
     * Represents a looked-up player's ranks for all buckets with timestamps.
     */
    public static class Entry
    {
        // Stores rank for each bucket: overall, nh, veng, multi, dmm
        private final ConcurrentHashMap<String, String> bucketRanks = new ConcurrentHashMap<>();
        private volatile long lookupTimestampMs;

        Entry(long timestampMs)
        {
            this.lookupTimestampMs = timestampMs;
        }

        public void setRankForBucket(String bucket, String rank)
        {
            if (bucket != null && rank != null && !rank.trim().isEmpty())
            {
                bucketRanks.put(bucket.toLowerCase(), rank);
            }
        }

        public String getRankForBucket(String bucket)
        {
            if (bucket == null) return bucketRanks.get("overall");
            return bucketRanks.get(bucket.toLowerCase());
        }

        public boolean isExpired(long now)
        {
            return (now - lookupTimestampMs) > CACHE_DURATION_MS;
        }

        public void refreshTimestamp()
        {
            this.lookupTimestampMs = System.currentTimeMillis();
        }

        public void refreshBucketRank(String bucket, String newRank)
        {
            if (bucket != null && newRank != null && !newRank.trim().isEmpty())
            {
                bucketRanks.put(bucket.toLowerCase(), newRank);
            }
            this.lookupTimestampMs = System.currentTimeMillis();
        }
    }

    /**
     * Add a player to the cache with all their bucket ranks.
     *
     * @param playerName  The player's name
     * @param bucketRanks Map of bucket name to rank (e.g., "nh" -> "Dragon 2")
     */
    public void addPlayer(String playerName, Map<String, String> bucketRanks)
    {
        if (playerName == null || playerName.trim().isEmpty() || bucketRanks == null || bucketRanks.isEmpty())
        {
            return;
        }
        String key = NameUtils.canonicalKey(playerName);
        Entry entry = cache.get(key);
        if (entry != null)
        {
            // Update existing entry with new ranks and refresh timestamp
            for (Map.Entry<String, String> e : bucketRanks.entrySet())
            {
                entry.setRankForBucket(e.getKey(), e.getValue());
            }
            entry.refreshTimestamp();
            log.debug("[LookupCache] Refreshed player: {} with {} buckets", key, bucketRanks.size());
        }
        else
        {
            // Add new entry
            entry = new Entry(System.currentTimeMillis());
            for (Map.Entry<String, String> e : bucketRanks.entrySet())
            {
                entry.setRankForBucket(e.getKey(), e.getValue());
            }
            cache.put(key, entry);
            log.debug("[LookupCache] Added player: {} with {} buckets", key, bucketRanks.size());
        }
    }

    /**
     * Refresh a player's rank for a specific bucket after a fight.
     * This resets the cache timer and updates the rank for that bucket.
     *
     * @param playerName The player's name
     * @param bucket     The bucket to update (e.g., "nh", "veng")
     * @param newRank    The new rank for that bucket
     */
    public void refreshPlayer(String playerName, String bucket, String newRank)
    {
        if (playerName == null || playerName.trim().isEmpty())
        {
            return;
        }
        String key = NameUtils.canonicalKey(playerName);
        Entry entry = cache.get(key);
        if (entry != null)
        {
            entry.refreshBucketRank(bucket, newRank);
            log.debug("[LookupCache] Refreshed player after fight: {} bucket={} rank={}", key, bucket, newRank);
        }
        // If player wasn't previously looked up, don't add them automatically
    }

    /**
     * Check if a player is in the cache (and not expired).
     */
    public boolean isPlayerCached(String playerName)
    {
        if (playerName == null)
        {
            return false;
        }
        String key = NameUtils.canonicalKey(playerName);
        Entry entry = cache.get(key);
        if (entry == null)
        {
            return false;
        }
        // Check if expired
        if (entry.isExpired(System.currentTimeMillis()))
        {
            cache.remove(key);
            return false;
        }
        return true;
    }

    /**
     * Get the cached rank for a player for the specified bucket.
     * Returns null if player is not in cache, is expired, or has no rank for that bucket.
     */
    public String getRank(String playerName, String bucket)
    {
        if (playerName == null)
        {
            return null;
        }
        String key = NameUtils.canonicalKey(playerName);
        Entry entry = cache.get(key);
        if (entry == null)
        {
            return null;
        }
        // Check if expired
        if (entry.isExpired(System.currentTimeMillis()))
        {
            cache.remove(key);
            return null;
        }
        return entry.getRankForBucket(bucket);
    }

    /**
     * Get the cache entry for a player.
     * Returns null if player is not in cache or is expired.
     */
    public Entry getEntry(String playerName)
    {
        if (playerName == null)
        {
            return null;
        }
        String key = NameUtils.canonicalKey(playerName);
        Entry entry = cache.get(key);
        if (entry == null)
        {
            return null;
        }
        // Check if expired
        if (entry.isExpired(System.currentTimeMillis()))
        {
            cache.remove(key);
            log.debug("[LookupCache] Expired entry for: {}", key);
            return null;
        }
        return entry;
    }

    /**
     * Check if the cache is empty.
     */
    public boolean isEmpty()
    {
        return cache.isEmpty();
    }

    /**
     * Get all cached player keys for iteration.
     * Used by the overlay to render ranks for visible players.
     */
    public Iterable<String> getCachedPlayerKeys()
    {
        return cache.keySet();
    }
}

