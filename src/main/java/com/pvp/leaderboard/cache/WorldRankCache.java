package com.pvp.leaderboard.cache;

public class WorldRankCache
{
    private final int worldRank;
    private final long timestamp;

    public WorldRankCache(int worldRank, long timestamp)
    {
        this.worldRank = worldRank;
        this.timestamp = timestamp;
    }

    public int getWorldRank()
    {
        return worldRank;
    }

    public long getTimestamp()
    {
        return timestamp;
    }
}

