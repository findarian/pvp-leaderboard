package com.pvp.leaderboard.cache;

import lombok.*;
import com.google.gson.*;

/** A cached JSON payload and when it was fetched: a {@code /user} profile, a
 *  first {@code /matches} page, a CDN shard or a Top Players file. */
@Getter
@AllArgsConstructor
public class UserStats
{
    private final JsonObject stats;
    private final long timestamp;
}
