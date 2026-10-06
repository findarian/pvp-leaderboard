package com.pvp.leaderboard.service;

import com.google.gson.*;
import com.pvp.leaderboard.cache.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.client.config.*;

@Singleton
public class WinStreakTracker
{
	static final String CONFIG_GROUP = "PvPLeaderboard";
	static final String KEY_PREFIX = "winStreak.";
	static final long GRACE_MS = 30_000L;
	static final int GRACE_TRIES = 2;

	private static final String WIN = "win";
	private static final String LOSS = "loss";

	private final ConfigManager configManager;
	private final LongSupplier nowMs;

	private String loadedProfile;
	private final Map<StreakBucket, Streak> streaks = new EnumMap<>(StreakBucket.class);
	private boolean known;
	private long profileTs = Long.MIN_VALUE;

	private static final class Streak
	{
		int current;
		boolean plus;
		int serverBest;
		int localBest;
		int graceSeen;
		long graceStartMs;
	}

	@Inject
	public WinStreakTracker(ConfigManager configManager)
	{
		this(configManager, System::currentTimeMillis);
	}

	public WinStreakTracker(ConfigManager configManager, LongSupplier nowMs)
	{
		this.configManager = configManager;
		this.nowMs = nowMs;
	}

	public synchronized void seedHistory(JsonArray newestFirst)
	{
		if (newestFirst == null || !ensureLoaded()) return;
		known = true;
		Map<StreakBucket, int[]> tally = new EnumMap<>(StreakBucket.class);
		for (JsonElement e : newestFirst)
		{
			if (!e.isJsonObject()) continue;
			JsonObject row = e.getAsJsonObject();
			StreakBucket bucket = StreakBucket.forKey(JsonLenient.optString(row, "bucket", null));
			if (bucket == null) continue;
			int[] t = tally.computeIfAbsent(bucket, k -> new int[2]);
			if (t[1] == 1) continue;
			String result = normalise(JsonLenient.optString(row, "result", null));
			if (WIN.equals(result)) t[0]++;
			else if (LOSS.equals(result)) t[1] = 1;
		}
		for (Map.Entry<StreakBucket, int[]> en : tally.entrySet())
		{
			int wins = en.getValue()[0];
			boolean exact = en.getValue()[1] == 1;
			Streak s = streaks.get(en.getKey());
			if (exact)
			{
				set(en.getKey(), s, wins, false);
				s.graceSeen = 0;
			}
			else if (wins > s.current)
			{
				set(en.getKey(), s, wins, true);
				s.graceSeen = 0;
			}
		}
	}

	public synchronized void onFight(String bucketKey, String result)
	{
		StreakBucket bucket = StreakBucket.forKey(bucketKey);
		if (bucket == null || !ensureLoaded()) return;
		Streak s = streaks.get(bucket);
		String r = normalise(result);
		if (WIN.equals(r))
		{
			set(bucket, s, s.current == Integer.MAX_VALUE ? s.current : s.current + 1, s.plus);
		}
		else if (LOSS.equals(r))
		{
			set(bucket, s, 0, false);
		}
	}

	public synchronized void reconcile(String bucketKey, int serverStreak, int serverBest)
	{
		StreakBucket bucket = StreakBucket.forKey(bucketKey);
		if (bucket == null || !ensureLoaded()) return;
		known = true;
		Streak s = streaks.get(bucket);
		if (serverBest >= 0) s.serverBest = serverBest;
		if (serverStreak < 0) return;
		if (serverStreak >= s.current)
		{
			set(bucket, s, serverStreak, false);
			s.graceSeen = 0;
			return;
		}
		long now = nowMs.getAsLong();
		if (s.graceSeen == 0) s.graceStartMs = now;
		s.graceSeen++;
		if (s.graceSeen >= GRACE_TRIES && now - s.graceStartMs >= GRACE_MS)
		{
			set(bucket, s, serverStreak, false);
			s.localBest = serverStreak;
			s.graceSeen = 0;
		}
	}

	public synchronized void observeProfile(UserStats cached)
	{
		if (cached == null || !ensureLoaded()) return;
		if (cached.getTimestamp() == profileTs) return;
		profileTs = cached.getTimestamp();
		known = true;
		JsonObject buckets = JsonLenient.optObject(cached.getStats(), "buckets");
		for (StreakBucket b : StreakBucket.values())
		{
			JsonObject bucket = JsonLenient.optObject(buckets, b.bucketKey);
			reconcile(b.bucketKey, sent(bucket, "streak"), sent(bucket, "best_streak"));
		}
	}

	/** {@code buckets.<key>.<field>} as the server sent it, or -1 when it sent
	 *  none: absent, not a number, or negative (a streak is never below zero). */
	private static int sent(JsonObject bucket, String field)
	{
		Integer v = JsonLenient.optInteger(bucket, field);
		return v == null || v < 0 ? -1 : v;
	}

	public synchronized void clear()
	{
		loadedProfile = null;
		streaks.clear();
		known = false;
		profileTs = Long.MIN_VALUE;
	}

	public synchronized boolean isKnown()
	{
		return ensureLoaded() && known;
	}

	public synchronized int current(String bucketKey)
	{
		Streak s = find(bucketKey);
		return s == null ? 0 : s.current;
	}

	public synchronized boolean plus(String bucketKey)
	{
		Streak s = find(bucketKey);
		return s != null && s.plus;
	}

	public synchronized int longest(String bucketKey)
	{
		Streak s = find(bucketKey);
		return s == null ? 0 : Math.max(s.serverBest, Math.max(s.localBest, s.current));
	}

	public synchronized String text(String bucketKey)
	{
		Streak s = find(bucketKey);
		return s == null ? "0" : encode(s.current, s.plus);
	}

	private Streak find(String bucketKey)
	{
		StreakBucket bucket = StreakBucket.forKey(bucketKey);
		return bucket == null || !ensureLoaded() ? null : streaks.get(bucket);
	}

	private boolean ensureLoaded()
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null) return false;
		if (!profile.equals(loadedProfile))
		{
			loadedProfile = profile;
			known = false;
			profileTs = Long.MIN_VALUE;
			streaks.clear();
			for (StreakBucket b : StreakBucket.values())
			{
				Streak s = new Streak();
				int[] saved = decode(configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_PREFIX + b.bucketKey));
				s.current = saved[0];
				s.plus = saved[1] == 1;
				s.localBest = s.current;
				streaks.put(b, s);
			}
		}
		return true;
	}

	private void set(StreakBucket bucket, Streak s, int value, boolean plus)
	{
		int v = Math.max(0, value);
		boolean p = plus && v > 0;
		String before = encode(s.current, s.plus);
		s.current = v;
		s.plus = p;
		s.localBest = Math.max(s.localBest, v);
		String after = encode(v, p);
		if (after.equals(before)) return;
		try
		{
			configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PREFIX + bucket.bucketKey, after);
		}
		catch (RuntimeException e)
		{
		}
	}

	static String encode(int current, boolean plus)
	{
		return plus ? current + "+" : String.valueOf(current);
	}

	static int[] decode(String saved)
	{
		if (saved == null) return new int[]{0, 0};
		String t = saved.trim();
		boolean plus = t.endsWith("+");
		if (plus) t = t.substring(0, t.length() - 1);
		try
		{
			int v = Integer.parseInt(t);
			return v < 0 ? new int[]{0, 0} : new int[]{v, plus && v > 0 ? 1 : 0};
		}
		catch (NumberFormatException e)
		{
			return new int[]{0, 0};
		}
	}

	private static String normalise(String result)
	{
		return result == null ? null : result.trim().toLowerCase(Locale.ROOT);
	}
}
