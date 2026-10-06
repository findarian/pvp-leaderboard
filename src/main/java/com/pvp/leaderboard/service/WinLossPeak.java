package com.pvp.leaderboard.service;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

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
		JsonObject cum = optObject(profile, "cumulative_stats");
		if (cum != null)
		{
			for (Map.Entry<String, JsonElement> e : cum.entrySet())
			{
				JsonObject b = optObject(cum, e.getKey());
				if (b == null) continue;
				records.put(e.getKey(), new int[]{
					Math.max(0, optInt(b, "wins", 0)),
					Math.max(0, optInt(b, "losses", 0))});
			}
		}
		Map<String, String> peaks = new HashMap<>();
		JsonObject peakMap = optObject(profile, "peaks");
		if (peakMap != null)
		{
			for (Map.Entry<String, JsonElement> e : peakMap.entrySet())
			{
				String text = peakText(optObject(peakMap, e.getKey()));
				if (text != null) peaks.put(e.getKey(), text);
			}
		}
		return records.isEmpty() && peaks.isEmpty() ? EMPTY : new WinLossPeak(records, peaks);
	}

	private static String peakText(JsonObject peak)
	{
		String rank = optString(peak, "rank").trim();
		if (rank.isEmpty()) return null;
		int division = optInt(peak, "division", 0);
		return division > 0 ? rank + " " + division : rank;
	}

	public int wins(String bucketKey)
	{
		return count(bucketKey, 0);
	}

	public int losses(String bucketKey)
	{
		return count(bucketKey, 1);
	}

	/** Both maps are a HashMap or an empty map: either answers a null key with null. */
	public String peak(String bucketKey)
	{
		return peaks.get(bucketKey);
	}

	private int count(String bucketKey, int i)
	{
		int[] v = records.get(bucketKey);
		return v == null ? 0 : v[i];
	}
}
