package com.pvp.leaderboard.service;

import com.google.gson.*;

public final class PortalCap
{
    public static final String LABEL = "Rating capped in FFA portal";

    /** True when a match row's {@code v3_capped} is the JSON boolean true. */
    public static boolean isCapped(JsonObject match)
    {
        JsonElement flag = match == null ? null : match.get("v3_capped");
        return flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean();
    }
}
