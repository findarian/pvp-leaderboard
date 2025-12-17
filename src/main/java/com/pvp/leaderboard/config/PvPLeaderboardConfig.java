package com.pvp.leaderboard.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("PvPLeaderboard")
public interface PvPLeaderboardConfig extends Config
{
	// ==================== Sections ====================

	@ConfigSection(
		name = "Overlay Settings",
		description = "Settings for the rank overlay display",
		position = 0
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Visual Settings",
		description = "Settings for overlay appearance",
		position = 1
	)
	String visualSection = "visual";

	@ConfigSection(
		name = "Notifications",
		description = "Settings for notifications",
		position = 2
	)
	String notificationSection = "notifications";

	@ConfigSection(
		name = "Other",
		description = "Other settings",
		position = 3
	)
	String otherSection = "other";

	// ==================== Enums ====================

	enum RankDisplayMode
	{
		TEXT
		{
			@Override
			public String toString()
			{
				return "Text";
			}
		},
		RANK_NUMBER
		{
			@Override
			public String toString()
			{
				return "Rank #";
			}
		}
	}

	enum RankBucket
	{
		OVERALL
		{
			@Override
			public String toString()
			{
				return "Overall";
			}
		},
		NH
		{
			@Override
			public String toString()
			{
				return "NH";
			}
		},
		VENG
		{
			@Override
			public String toString()
			{
				return "Veng";
			}
		},
		MULTI
		{
			@Override
			public String toString()
			{
				return "Multi";
			}
		},
		DMM
		{
			@Override
			public String toString()
			{
				return "DMM";
			}
		}
	}

	// ==================== Overlay Settings ====================

	@ConfigItem(
		keyName = "showOwnRank",
		name = "Show your own rank",
		description = "Display your rank above your character",
		section = overlaySection,
		position = 0
	)
	default boolean showOwnRank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rankDisplayMode",
		name = "Display rank as",
		description = "How to display ranks above players",
		section = overlaySection,
		position = 1
	)
	default RankDisplayMode rankDisplayMode()
	{
		return RankDisplayMode.TEXT;
	}

	@ConfigItem(
		keyName = "rankBucket",
		name = "Rank Bucket",
		description = "Which rank bucket to display",
		section = overlaySection,
		position = 2
	)
	default RankBucket rankBucket()
	{
		return RankBucket.OVERALL;
	}

	// ==================== Visual Settings ====================

	@ConfigItem(
		keyName = "rankTextSize",
		name = "Rank Text Size",
		description = "Text size for rank display",
		section = visualSection,
		position = 0
	)
	@Range(min = 10, max = 48)
	default int rankTextSize()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "rankOffsetX",
		name = "Rank Offset X",
		description = "Horizontal offset for rank display (pixels)",
		section = visualSection,
		position = 1
	)
	@Range(min = -100, max = 100)
	default int rankOffsetX()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankOffsetY",
		name = "Rank Offset Y",
		description = "Vertical offset for rank display (pixels)",
		section = visualSection,
		position = 2
	)
	@Range(min = -100, max = 100)
	default int rankOffsetY()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "colorblindMode",
		name = "Colorblind Mode",
		description = "Makes all rank text white for better visibility",
		section = visualSection,
		position = 3
	)
	default boolean colorblindMode()
	{
		return false;
	}

	// ==================== Notifications ====================

	@ConfigItem(
		keyName = "showMmrChangeNotification",
		name = "Show MMR Change",
		description = "Display MMR gained/lost after fights (XP drop style)",
		section = notificationSection,
		position = 0
	)
	default boolean showMmrChangeNotification()
	{
		return true;
	}

	@ConfigItem(
		keyName = "mmrOffsetX",
		name = "MMR Offset X",
		description = "Horizontal offset for MMR notification (0 = XP drop position)",
		section = notificationSection,
		position = 1
	)
	@Range(min = -500, max = 500)
	default int mmrOffsetX()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "mmrOffsetY",
		name = "MMR Offset Y",
		description = "Vertical offset for MMR notification (0 = XP drop position)",
		section = notificationSection,
		position = 2
	)
	@Range(min = -500, max = 500)
	default int mmrOffsetY()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "mmrDuration",
		name = "MMR Duration",
		description = "How long the MMR notification stays on screen (seconds)",
		section = notificationSection,
		position = 3
	)
	@Range(min = 1, max = 10)
	default int mmrDuration()
	{
		return 3;
	}

	// ==================== Other Settings ====================

	@ConfigItem(
		keyName = "enablePvpLookupMenu",
		name = "Enable 'pvp lookup' right-click",
		description = "Adds the 'pvp lookup' option to player right-click menu",
		section = otherSection,
		position = 0
	)
	default boolean enablePvpLookupMenu()
	{
		return true;
	}
}
