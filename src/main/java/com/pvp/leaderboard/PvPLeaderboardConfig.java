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
		keyName = "showOtherRanks",
		name = "Show other player ranks",
		description = "Display ranks above other players"
	)
	default boolean showOtherRanks()
	{
		return true;
	}

    enum RankDisplayMode { TEXT { public String toString(){return "Text";} }, RANK_NUMBER { public String toString(){return "Rank";} }, ICON { public String toString(){return "Icon";} } }

	@ConfigItem(
		keyName = "rankDisplayMode",
		name = "Display rank as",
		description = "How to display ranks above players"
	)
	default RankDisplayMode rankDisplayMode()
	{
		return RankDisplayMode.TEXT;
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
		name = "Offset X (Self)",
		description = "Horizontal X offset for your own rank display (pixels)"
	)
	@Range(min = -100, max = 100)
	default int rankIconOffsetXSelf()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankIconOffsetXOthers",
		name = "Offset X (Others)",
		description = "Horizontal X offset for other players' rank display (pixels)"
	)
	@Range(min = -100, max = 100)
	default int rankIconOffsetXOthers()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankIconOffsetYSelf",
		name = "Offset Y (Self)",
		description = "Vertical offset for your own rank display (pixels)"
	)
	@Range(min = -100, max = 100)
	default int rankIconOffsetYSelf()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankIconOffsetYOthers",
		name = "Offset Y (Others)",
		description = "Vertical offset for other players' rank display (pixels)"
	)
	@Range(min = -100, max = 100)
	default int rankIconOffsetYOthers()
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

    // removed: onlyFetchOnLogin

	@ConfigItem(
		keyName = "rankIconWhiteOutline",
		name = "Rank Icon White Outline",
		description = "Draw a white highlight around the rank icon (like item 'Use' outline)"
	)
	default boolean rankIconWhiteOutline()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTopNearbyOverlay",
		name = "Ranked players nearby (Top)",
		description = "Show a floating box with you and the top 10 nearby ranked players"
	)
	default boolean showTopNearbyOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBottomNearbyOverlay",
		name = "Ranked players nearby (Bottom)",
		description = "Show a floating box with the bottom 10 nearby ranked players"
	)
	default boolean showBottomNearbyOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "topNearbyCount",
		name = "Top nearby count",
		description = "Max entries in Top nearby overlay (1-10)"
	)
	@Range(min = 1, max = 10)
	default int topNearbyCount()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "bottomNearbyCount",
		name = "Bottom nearby count",
		description = "Max entries in Bottom nearby overlay (1-10)"
	)
	@Range(min = 1, max = 10)
	default int bottomNearbyCount()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "lookupThrottleLevel",
		name = "Throttling (Reduce lag, higher = slower player lookup)",
		description = "Limits how fast lookups are to reduce lag (0 = off)"
	)
	@Range(min = 0, max = 10)
	default int lookupThrottleLevel()
	{
		return 6; // ~560ms per fetch, ~4 concurrent
	}

    // removed: muteUiErrors

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
