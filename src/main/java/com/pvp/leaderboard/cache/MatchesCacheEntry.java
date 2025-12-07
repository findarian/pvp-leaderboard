package com.pvp.leaderboard.cache;

import com.google.gson.JsonObject;

public class MatchesCacheEntry
{
    private final JsonObject response;
    private final long timestamp;

    public MatchesCacheEntry(JsonObject response, long timestamp)
    {
        this.response = response;
        this.timestamp = timestamp;
    }

    public JsonObject getResponse()
    {
        return response;
    }

    public long getTimestamp()
    {
        return timestamp;
    }
}

