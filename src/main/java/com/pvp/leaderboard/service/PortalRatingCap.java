package com.pvp.leaderboard.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

public final class PortalRatingCap
{
    public static final String LABEL = "Rating capped in FFA portal";

    private static final String KEY = "capped_in_portal";

    private PortalRatingCap() {}

    public static boolean isCapped(JsonObject match)
    {
        JsonObject ratingChange = JsonLenient.optObject(match, "rating_change");
        if (ratingChange == null) return false;
        JsonElement flag = ratingChange.get(KEY);
        return flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean();
    }
}
