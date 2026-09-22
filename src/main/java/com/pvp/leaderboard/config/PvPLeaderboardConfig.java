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
		name = "Display Ranks",
		description = "Settings for displaying ranks to and from other players",
		position = 3
	)
	String whitelistSection = "whitelist";

	@ConfigSection(
		name = "Other",
		description = "Other settings",
		position = 4
	)
	String otherSection = "other";

	// ==================== Enums ====================

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
		},
		/** Plan 10: the Swiss-tournament rating bucket (2026-09-21). */
		TOURNAMENT
		{
			@Override
			public String toString()
			{
				return "Tournament";
			}
		}
	}

	enum RankPosition
	{
		FEET
		{
			@Override
			public String toString()
			{
				return "Feet";
			}
		},
		HEAD
		{
			@Override
			public String toString()
			{
				return "Head";
			}
		},
		ABOVE_HEAD
		{
			@Override
			public String toString()
			{
				return "Above Head";
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

	@ConfigItem(
		keyName = "autoSwitchBucket",
		name = "Auto-switch Leaderboard",
		description = "Automatically switch leaderboard to match your last fight style (overrides manual selection)",
		section = overlaySection,
		position = 3
	)
	default boolean autoSwitchBucket()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoSwitchTournamentBucket",
		name = "Auto-switch to Tournament",
		description = "While you are in a running tournament the side panel and overlay use the Tournament bucket; it switches back at the next fight like every bucket (Plan 10)",
		section = overlaySection,
		position = 4
	)
	default boolean autoSwitchTournamentBucket()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideRankOutOfCombat",
		name = "Hide rank when out of combat",
		description = "When enabled, your rank disappears after not being in combat",
		section = overlaySection,
		position = 4
	)
	default boolean hideRankOutOfCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideRankAfterMinutes",
		name = "Hide rank after (minutes)",
		description = "Minutes out of combat before your rank disappears (1-360)",
		section = overlaySection,
		position = 5
	)
	@Range(min = 1, max = 360)
	default int hideRankAfterMinutes()
	{
		return 15;
	}

	// ==================== Visual Settings ====================

	@ConfigItem(
		keyName = "rankPosition",
		name = "Rank Position",
		description = "Where to display the rank relative to your character",
		section = visualSection,
		position = 0
	)
	default RankPosition rankPosition()
	{
		return RankPosition.ABOVE_HEAD;
	}

	@ConfigItem(
		keyName = "rankTextSize",
		name = "Rank Text Size",
		description = "Text size for rank display",
		section = visualSection,
		position = 1
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
		position = 2
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
		position = 3
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
		position = 4
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

	@ConfigItem(
		keyName = "enableLobbyInviteNotification",
		name = "Lobby invite popup",
		description = "Show an OSRS-style in-game popup when another player invites you to fight in the matchmaking lobby",
		section = notificationSection,
		position = 4
	)
	default boolean enableLobbyInviteNotification()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lobbyInviteNotificationDurationSeconds",
		name = "Lobby invite popup duration",
		description = "How long the lobby invite popup stays on screen (seconds). Includes fade in/out.",
		section = notificationSection,
		position = 5
	)
	@Range(min = 1, max = 30)
	default int lobbyInviteNotificationDurationSeconds()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "enableMatchFoundNotification",
		name = "Match found popup",
		description = "Show an OSRS-style in-game popup when a matchmaking fight is locked in (the other player accepted your invite, or you accepted theirs).",
		section = notificationSection,
		position = 6
	)
	default boolean enableMatchFoundNotification()
	{
		return true;
	}

	@ConfigItem(
		keyName = "suppressNotificationsInCombat",
		name = "Hide popups while in combat",
		description = "Defer lobby invite + match found in-game popups while you're in a PvP fight, then show the deferred popup the moment the plugin closes out the match. On by default. The popup's visible-window timer is paused for the duration of the fight (so a popup arriving mid-combat doesn't expire before you see it). Release triggers: (a) right after a match is submitted on a kill or death, or (b) when combat ends naturally and the FightEntry is GC'd (~10-30s after the last damage hitsplat). Multi-combat: stays deferred until you're out of combat with ALL opponents, not just the first one.",
		section = notificationSection,
		position = 7
	)
	default boolean suppressNotificationsInCombat()
	{
		return true;
	}

	// ==================== Other Settings ====================

	@ConfigItem(
		keyName = "enablePvpLookupMenu",
		name = "Enable 'PvP lookup' right-click",
		description = "Adds the 'PvP lookup' option to player right-click menu",
		section = otherSection,
		position = 0
	)
	default boolean enablePvpLookupMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableQuickMatch",
		name = "Matchmaking queue",
		description = "Show 'Queue for matchmaking' on the Matchmaking gate: one style, optional rank range, a wait time shared with Discord (Plan 10)",
		section = otherSection,
		position = 1
	)
	default boolean enableQuickMatch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableTournaments",
		name = "Tournaments tab",
		description = "Show the Tournaments sub-tab (register, live standings, round clock). Only the tab is gated: the opponent outline and the Tournament bucket auto-switch stay on regardless (Plan 10)",
		section = otherSection,
		position = 2
	)
	default boolean enableTournaments()
	{
		return true;
	}

	// ==================== Whitelist Settings ====================

	@ConfigItem(
		keyName = "showRankToOthers",
		name = "Show your rank to others",
		description = "When enabled, your username is shared so others can see your rank above your head",
		section = whitelistSection,
		position = 0
	)
	default boolean showRankToOthers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableWhitelistRanks",
		name = "Display other players ranks",
		description = "Show ranks above other players who have opted in",
		section = whitelistSection,
		position = 1
	)
	default boolean enableWhitelistRanks()
	{
		return true;
	}

	// ==================== Kill Streak Box (BOARD row 33) ====================
	// The section is declared here with its items so the whole feature is ONE
	// hunk at the end of the file, apart from the Plan 10 step-7 hunks above
	// (staging map in docs/PLUGIN_PROGRESS.md).

	@ConfigSection(
		name = "Kill Streak Box",
		description = "A movable box with your current kill streak in the style you are fighting in (off by default)",
		position = 5
	)
	String killStreakSection = "killStreak";

	@ConfigItem(
		keyName = "showKillStreakBox",
		name = "Show kill streak box",
		description = "Show a movable box reading '<style> Current Kill Streak: N' (Alt+drag to move it). Off by default.",
		section = killStreakSection,
		position = 0
	)
	default boolean showKillStreakBox()
	{
		return false;
	}

	@ConfigItem(
		keyName = "killStreakBoxAutoSwitch",
		name = "Auto-switch style",
		description = "Follow the style of your last fight (NH, Veng, Multi or DMM) and Event while you are in a running tournament. Off: always show the style picked below.",
		section = killStreakSection,
		position = 1
	)
	default boolean killStreakBoxAutoSwitch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "killStreakBoxBucket",
		name = "Style",
		description = "The style the box shows while auto-switch is off (Event = tournaments)",
		section = killStreakSection,
		position = 2
	)
	default StreakBucket killStreakBoxBucket()
	{
		return StreakBucket.NH;
	}

	@ConfigItem(
		keyName = "killStreakBoxShowLongest",
		name = "Show longest streak",
		description = "Add a second line with your longest kill streak in that style",
		section = killStreakSection,
		position = 3
	)
	default boolean killStreakBoxShowLongest()
	{
		return false;
	}
}
