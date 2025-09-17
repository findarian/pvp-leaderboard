package com.pvp.leaderboard;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("PvPLeaderboard")
public interface PvPLeaderboardConfig extends Config
{
    
    // Removed: Show Rank Icons (icons shown when showRankAsText == false)

	@ConfigItem(
		keyName = "showOwnRank",
		name = "Show your own rank",
		description = "Display an icon next to your own name"
	)
	default boolean showOwnRank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRankAsText",
		name = "Show rank as text",
		description = "Use in-game font label (e.g., Bronze 2) instead of icon"
	)
	default boolean showRankAsText()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rankIconSize",
		name = "Rank Icon Size",
		description = "Pixel size of the rank icon next to names"
	)
	@Range(min = 10, max = 48)
	default int rankIconSize()
	{
		return 28;
	}

	@ConfigItem(
		keyName = "rankTextSize",
		name = "Rank Text Size",
		description = "Text size when showing rank as text"
	)
	@Range(min = 10, max = 48)
	default int rankTextSize()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "rankIconOffsetXSelf",
		name = "Rank Icon Offset X (Self)",
		description = "Horizontal X offset for your own rank icon (pixels)"
	)
	@Range(min = -100, max = 100)
	default int rankIconOffsetXSelf()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankIconOffsetXOthers",
		name = "Rank Icon Offset X (Others)",
		description = "Horizontal X offset for other players' rank icons (pixels)"
	)
	@Range(min = -100, max = 100)
	default int rankIconOffsetXOthers()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankIconOffsetY",
		name = "Rank Icon Offset Y",
		description = "Vertical offset applied to rank icon relative to name (pixels)"
	)
	@Range(min = -100, max = 100)
	default int rankIconOffsetY()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankBucket",
		name = "Rank Bucket",
		description = "Which rank bucket to display"
	)
	default RankBucket rankBucket()
	{
		return RankBucket.OVERALL;
	}





	@ConfigItem(
		keyName = "onlyFetchOnLogin",
		name = "Grab player info only on login",
		description = "When enabled, rank lookups are attempted only shortly after logging in"
	)
	default boolean onlyFetchOnLogin()
	{
		return false;
	}


    // Removed: Nearby Leaderboard mode

	@ConfigItem(
		keyName = "rankIconWhiteOutline",
		name = "Rank Icon White Outline",
		description = "Draw a white highlight around the rank icon (like item 'Use' outline)"
	)
	default boolean rankIconWhiteOutline()
	{
		return true;
	}

    // Removed: Show unranked players (no special unranked icon)

	// Rank bucket selector enum (public via being a member of the interface)
	enum RankBucket
	{
		OVERALL {
			@Override public String toString() { return "Overall"; }
		},
		NH {
			@Override public String toString() { return "NH"; }
		},
		VENG {
			@Override public String toString() { return "Veng"; }
		},
		MULTI {
			@Override public String toString() { return "Multi"; }
		},
		DMM {
			@Override public String toString() { return "DMM"; }
		}
	}

    // Removed: LeaderboardMode enum

}
