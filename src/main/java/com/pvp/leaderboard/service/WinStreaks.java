package com.pvp.leaderboard.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The per-bucket win streaks of one /user profile (BOARD row 33, 2026-09-21):
 * {@code buckets.<key>.streak} (current) and {@code buckets.<key>.best_streak}
 * (longest), read with the same lenient helper and the same keys the Player
 * Lookup streak line uses ({@code DashboardPanel.applyBucketStatsFromUser}),
 * so the kill-streak box and the side panel can never disagree on a number.
 * A bucket that is absent, JSON null or not an object (the {@code tournament}
 * bucket of a player who never entered one) reads 0 / 0; nothing is ever
 * negative. Immutable; parsed once per cached profile, never per frame.
 */
public final class WinStreaks
{
	public static final WinStreaks EMPTY = new WinStreaks(Collections.emptyMap());

	/** bucket key → {current, best}. */
	private final Map<String, int[]> byBucket;

	private WinStreaks(Map<String, int[]> byBucket)
	{
		this.byBucket = byBucket;
	}

	public static WinStreaks fromProfile(JsonObject profile)
	{
		JsonObject buckets = JsonLenient.optObject(profile, "buckets");
		if (buckets == null) return EMPTY;
		Map<String, int[]> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : buckets.entrySet())
		{
			JsonObject bucket = JsonLenient.optObject(buckets, e.getKey());
			if (bucket == null) continue;
			out.put(e.getKey(), new int[]{
				Math.max(0, JsonLenient.optInt(bucket, "streak", 0)),
				Math.max(0, JsonLenient.optInt(bucket, "best_streak", 0))});
		}
		return out.isEmpty() ? EMPTY : new WinStreaks(out);
	}

	/** The current win streak in {@code bucketKey}; 0 when unknown. */
	public int current(String bucketKey)
	{
		int[] v = bucketKey == null ? null : byBucket.get(bucketKey);
		return v == null ? 0 : v[0];
	}

	/** The longest win streak in {@code bucketKey}; 0 when unknown. */
	public int best(String bucketKey)
	{
		int[] v = bucketKey == null ? null : byBucket.get(bucketKey);
		return v == null ? 0 : v[1];
	}
}
