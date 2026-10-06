package com.pvp.leaderboard.service;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;

public final class PortalCap
{
    public static final String LABEL = "Rating capped in FFA portal";

    private static final String KEY = "capped_in_portal";

    public static boolean isCapped(JsonObject match)
    {
        JsonObject ratingChange = JsonLenient.optObject(match, "rating_change");
        if (ratingChange == null) return false;
        JsonElement flag = ratingChange.get(KEY);
        return flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean();
    }
}
