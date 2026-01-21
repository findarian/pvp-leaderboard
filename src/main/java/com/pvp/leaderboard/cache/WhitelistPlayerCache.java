package com.pvp.leaderboard.cache;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple cache for whitelist player ranks.
 * Data is fetched from external URL and cached for 1 hour.
 * Cache is only updated on successful fetch (preserves stale data on failure).
 * 
 * Stores per-bucket rank data (overall, nh, veng, multi, dmm).
 */
@Slf4j
@Singleton
public class WhitelistPlayerCache
{
    /**
     * Rank data for a single bucket.
     */
    public static class BucketRank
    {
        public final String tier;   // e.g. "Adamant2"
        public final int rank;      // e.g. 2
        public final double mmr;    // e.g. 1335.74
        
        public BucketRank(String tier, int rank, double mmr)
        {
            this.tier = tier;
            this.rank = rank;
            this.mmr = mmr;
        }
        
        /**
         * Get formatted tier for display (e.g. "Adamant 2" from "Adamant2")
         */
        public String getFormattedTier()
        {
            if (tier == null) return null;
            // Split tier like "Adamant2" into "Adamant 2"
            return tier.replaceAll("(\\D)(\\d)", "$1 $2");
        }
    }
    
    /**
     * All rank data for a whitelisted player (all buckets).
     */
    public static class PlayerRanks
    {
        public final Map<String, BucketRank> buckets; // bucket name -> rank data
        
        public PlayerRanks(Map<String, BucketRank> buckets)
        {
            this.buckets = buckets;
        }
        
        /**
         * Get rank for a specific bucket.
         */
        public BucketRank getBucket(String bucket)
        {
            if (bucket == null) return null;
            return buckets.get(bucket.toLowerCase());
        }
        
        /**
         * Get rank for bucket with fallback to overall.
         */
        public BucketRank getBucketOrOverall(String bucket)
        {
            BucketRank rank = getBucket(bucket);
            if (rank != null) return rank;
            return buckets.get("overall");
        }
    }
    
    // Player name (canonical/lowercase) -> PlayerRanks (all buckets)
    private final Map<String, PlayerRanks> playerRanks = new ConcurrentHashMap<>();
    
    // Lookup cache: tracks when each player was last looked up to prevent spam
    // Key: canonical player name, Value: timestamp of last lookup
    private final Map<String, Long> lookupCache = new ConcurrentHashMap<>();
    private static final long LOOKUP_CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    
    @Inject
    public WhitelistPlayerCache()
    {
    }
    
    /**
     * Check if a player is on the whitelist.
     */
    public boolean isWhitelisted(String playerName)
    {
        if (playerName == null) return false;
        return playerRanks.containsKey(canonicalize(playerName));
    }
    
    /**
     * Get all rank data for a whitelisted player.
     */
    public PlayerRanks getRanks(String playerName)
    {
        if (playerName == null) return null;
        return playerRanks.get(canonicalize(playerName));
    }
    
    /**
     * Get rank for a specific bucket. No fallback - returns null if bucket not found.
     * Uses a 10-minute lookup cache to prevent spam lookups every frame.
     */
    public BucketRank getRank(String playerName, String bucket)
    {
        String key = canonicalize(playerName);
        long now = System.currentTimeMillis();
        
        // Check if we've looked up this player recently
        Long lastLookup = lookupCache.get(key);
        boolean isNewLookup = (lastLookup == null || now - lastLookup >= LOOKUP_CACHE_TTL_MS);
        
        PlayerRanks ranks = getRanks(playerName);
        
        if (ranks == null)
        {
            if (isNewLookup)
            {
                lookupCache.put(key, now);
                log.debug("[WhitelistCache] Lookup: {} - not on whitelist (cached for 10min)", playerName);
            }
            return null;
        }
        
        BucketRank bucketRank = ranks.getBucket(bucket);
        
        if (isNewLookup)
        {
            lookupCache.put(key, now);
            if (bucketRank != null)
            {
                log.debug("[WhitelistCache] Lookup: {} [{}] - found: {} (rank #{})", 
                    playerName, bucket, bucketRank.tier, bucketRank.rank);
            }
            else
            {
                log.debug("[WhitelistCache] Lookup: {} [{}] - no rank for this bucket", playerName, bucket);
            }
        }
        
        return bucketRank;
    }
    
    /**
     * Clear the lookup cache. Called when whitelist data is refreshed.
     */
    public void clearLookupCache()
    {
        lookupCache.clear();
        log.debug("[WhitelistCache] Lookup cache cleared");
    }
    
    /**
     * Load whitelist data. Only call this on successful fetch.
     * Replaces all existing data and clears the lookup cache.
     */
    public void loadWhitelist(Map<String, PlayerRanks> newData)
    {
        if (newData == null || newData.isEmpty())
        {
            log.debug("[WhitelistCache] Load called with empty data - preserving existing cache ({} players)", playerRanks.size());
            return; // Don't clear existing data if nothing was fetched
        }
        
        int oldSize = playerRanks.size();
        playerRanks.clear();
        lookupCache.clear(); // Clear lookup cache so players get re-looked up with new data
        
        for (Map.Entry<String, PlayerRanks> entry : newData.entrySet())
        {
            playerRanks.put(canonicalize(entry.getKey()), entry.getValue());
        }
        
        log.debug("[WhitelistCache] Cache updated: {} -> {} players (lookup cache cleared)", oldSize, playerRanks.size());
    }
    
    /**
     * Get count of cached players.
     */
    public int size()
    {
        return playerRanks.size();
    }
    
    private String canonicalize(String name)
    {
        return name.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
