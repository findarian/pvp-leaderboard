package com.pvp.leaderboard.config;

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
	public static StreakBucket fromRankBucket(PvPLeaderboardConfig.RankBucket bucket)
	{
		if (bucket == null) return null;
		switch (bucket)
		{
			case NH: return NH;
			case VENG: return VENG;
			case MULTI: return MULTI;
			case DMM: return DMM;
			case TOURNAMENT: return EVENT;
			default: return null;
		}
	}

	@Override
	public String toString()
	{
		return label;
	}
}
