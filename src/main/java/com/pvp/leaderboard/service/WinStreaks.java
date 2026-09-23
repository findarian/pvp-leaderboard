package com.pvp.leaderboard.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class WinStreaks
{
	public static final WinStreaks EMPTY = new WinStreaks(Collections.emptyMap());

	private static final int CURRENT = 0;
	private static final int BEST = 1;
	private static final int NOT_SENT = -1;

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
			out.put(e.getKey(), new int[]{sent(bucket, "streak"), sent(bucket, "best_streak")});
		}
		return out.isEmpty() ? EMPTY : new WinStreaks(out);
	}

	private static int sent(JsonObject bucket, String key)
	{
		Integer v = JsonLenient.optInteger(bucket, key);
		return v == null || v < 0 ? NOT_SENT : v;
	}

	/** The current win streak in {@code bucketKey}; 0 when unknown. */
	public int current(String bucketKey)
	{
		return Math.max(0, raw(bucketKey, CURRENT));
	}

	/** The longest win streak in {@code bucketKey}; 0 when unknown. */
	public int best(String bucketKey)
	{
		return Math.max(0, raw(bucketKey, BEST));
	}

	public boolean hasStreak(String bucketKey)
	{
		return raw(bucketKey, CURRENT) != NOT_SENT;
	}

	public boolean hasBest(String bucketKey)
	{
		return raw(bucketKey, BEST) != NOT_SENT;
	}

	private int raw(String bucketKey, int index)
	{
		int[] v = bucketKey == null ? null : byBucket.get(bucketKey);
		return v == null ? NOT_SENT : v[index];
	}
}
