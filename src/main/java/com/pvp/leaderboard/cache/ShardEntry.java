package com.pvp.leaderboard.cache;

import com.google.gson.JsonObject;

public class ShardEntry
{
    private final JsonObject payload;
    private final long timestamp;

    public ShardEntry(JsonObject payload, long timestamp)
    {
        this.payload = payload;
        this.timestamp = timestamp;
    }

    public JsonObject getPayload()
    {
        return payload;
    }

    public long getTimestamp()
    {
        return timestamp;
    }
}

