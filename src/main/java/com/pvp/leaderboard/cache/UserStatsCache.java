package com.pvp.leaderboard.cache;

import com.google.gson.JsonObject;

public class UserStatsCache
{
    private final JsonObject stats;
    private final long timestamp;

    public UserStatsCache(JsonObject stats, long timestamp)
    {
        this.stats = stats;
        this.timestamp = timestamp;
    }

    public JsonObject getStats()
    {
        return stats;
    }

    public long getTimestamp()
    {
        return timestamp;
    }
}

