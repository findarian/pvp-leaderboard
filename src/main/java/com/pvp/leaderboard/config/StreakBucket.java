package com.pvp.leaderboard.config;
import java.util.*;

/**
 * The five styles the kill-streak box can show (BOARD row 33, 2026-09-21) —
 * the ONE place for the box's on-screen label and the {@code /user}
 * {@code buckets.<key>} it reads. Overall has no streak box (the operator's
 * list is NH / Veng / Multi / DMM / Event); {@link #EVENT} is the Swiss
 * tournament bucket under the name players see for it.
 *
 * <p>{@link #toString()} returns the label so RuneLite's config combo shows
 * "Veng" / "Event" rather than the constant name — the same trick as
 * {@link PvPLeaderboardConfig.RankBucket}.
 */
public enum StreakBucket
{
	NH("NH", "nh"),
	VENG("Veng", "veng"),
	MULTI("Multi", "multi"),
	DMM("DMM", "dmm"),
	/** The tournament rating bucket ({@code buckets.tournament}; JSON null
	 *  on /user for players who never entered one). */
	EVENT("Event", "tournament");

	/** The word before "Current Kill Streak" in the box. */
	public final String label;
	/** The key under {@code buckets} in the /user profile. */
	public final String bucketKey;

	StreakBucket(String label, String bucketKey)
	{
		this.label = label;
		this.bucketKey = bucketKey;
	}

	/** The box's style for a leaderboard bucket: Tournament reads "Event";
	 *  Overall (and {@code null}) has no streak box, so {@code null}. */
	public static StreakBucket forRank(PvPLeaderboardConfig.RankBucket bucket)
	{
		return bucket == null ? null : forKey(bucket.name());
	}

	public static StreakBucket forKey(String key)
	{
		if (key == null) return null;
		String k = key.trim().toLowerCase(Locale.ROOT);
		for (StreakBucket b : values())
		{
			if (b.bucketKey.equals(k)) return b;
		}
		return null;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
