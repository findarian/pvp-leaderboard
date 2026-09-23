package com.pvp.leaderboard.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class WinLossPeak
{
	public static final WinLossPeak EMPTY = new WinLossPeak(Collections.emptyMap(), Collections.emptyMap());

	private final Map<String, int[]> records;
	private final Map<String, String> peaks;

	private WinLossPeak(Map<String, int[]> records, Map<String, String> peaks)
	{
		this.records = records;
		this.peaks = peaks;
	}

	public static WinLossPeak fromProfile(JsonObject profile)
	{
		if (profile == null) return EMPTY;
		Map<String, int[]> records = new HashMap<>();
		JsonObject cum = JsonLenient.optObject(profile, "cumulative_stats");
		if (cum != null)
		{
			for (Map.Entry<String, JsonElement> e : cum.entrySet())
			{
				JsonObject b = JsonLenient.optObject(cum, e.getKey());
				if (b == null) continue;
				records.put(e.getKey(), new int[]{
					Math.max(0, JsonLenient.optInt(b, "wins", 0)),
					Math.max(0, JsonLenient.optInt(b, "losses", 0))});
			}
		}
		Map<String, String> peaks = new HashMap<>();
		JsonObject peakMap = JsonLenient.optObject(profile, "peaks");
		if (peakMap != null)
		{
			for (Map.Entry<String, JsonElement> e : peakMap.entrySet())
			{
				String text = peakText(JsonLenient.optObject(peakMap, e.getKey()));
				if (text != null) peaks.put(e.getKey(), text);
			}
		}
		return records.isEmpty() && peaks.isEmpty() ? EMPTY : new WinLossPeak(records, peaks);
	}

	private static String peakText(JsonObject peak)
	{
		String rank = JsonLenient.optString(peak, "rank", "").trim();
		if (rank.isEmpty()) return null;
		int division = JsonLenient.optInt(peak, "division", 0);
		return division > 0 ? rank + " " + division : rank;
	}

	public int wins(String bucketKey)
	{
		int[] v = bucketKey == null ? null : records.get(bucketKey);
		return v == null ? 0 : v[0];
	}

	public int losses(String bucketKey)
	{
		int[] v = bucketKey == null ? null : records.get(bucketKey);
		return v == null ? 0 : v[1];
	}

	public String peak(String bucketKey)
	{
		return bucketKey == null ? null : peaks.get(bucketKey);
	}
}
