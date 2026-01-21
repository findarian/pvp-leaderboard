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
     */
    public BucketRank getRank(String playerName, String bucket)
    {
        PlayerRanks ranks = getRanks(playerName);
        if (ranks == null) return null;
        return ranks.getBucket(bucket);
    }
    
    /**
     * Load whitelist data. Only call this on successful fetch.
     * Replaces all existing data.
     */
    public void loadWhitelist(Map<String, PlayerRanks> newData)
    {
        if (newData == null || newData.isEmpty())
        {
            return; // Don't clear existing data if nothing was fetched
        }
        
        playerRanks.clear();
        for (Map.Entry<String, PlayerRanks> entry : newData.entrySet())
        {
            playerRanks.put(canonicalize(entry.getKey()), entry.getValue());
        }
        
        log.debug("[WhitelistCache] Loaded {} players", playerRanks.size());
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
