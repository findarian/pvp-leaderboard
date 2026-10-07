package com.pvp.leaderboard.config;

import net.runelite.client.config.*;

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

	/** {@link #toString()} is the label RuneLite's settings drop-down shows
	 *  (where it differs from the constant name). */
	enum RankBucket
	{
		OVERALL("Overall"),
		NH("NH"),
		VENG("Veng"),
		MULTI("Multi"),
		DMM("DMM"),
		/** Plan 10: the Swiss-tournament rating bucket (2026-09-21). */
		TOURNAMENT("Tournament");

		private final String label;

		RankBucket(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}

		/** The bucket's key in the API: its lower-case name; {@code "overall"} for none. */
		public static String key(RankBucket b)
		{
			return b == null ? "overall" : b.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** RuneLite's settings drop-down title-cases the names: "Feet", "Head",
	 *  "Above Head". */
	enum RankPosition
	{
		FEET,
		HEAD,
		ABOVE_HEAD
	}

	// ==================== Overlay Settings ====================

	@ConfigItem(
		keyName = "showOwnRank",
		name = "Show your own rank",
		description = "Display your rank above your character",
		section = overlaySection
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
		description = "While you are in a running tournament the side panel and overlay use the Tournament bucket; it switches back at the next fight like every bucket",
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
		section = visualSection
	)
	default RankPosition rankPosition()
	{
		return RankPosition.ABOVE_HEAD;
	}

	@ConfigItem(
		keyName = "rankTextSize",
		name = "Rank Text Size",
		description = "Text size for rank display",
		section = visualSection
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
		section = notificationSection
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
		section = otherSection
	)
	default boolean enablePvpLookupMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableQuickMatch",
		name = "Matchmaking queue",
		description = "Show the matchmaking queue on the Matchmaking tab: NH, an optional rank range and a wait time shared with Discord",
		section = otherSection
	)
	default boolean enableQuickMatch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableTournaments",
		name = "Tournaments tab",
		description = "Show the Tournaments tab (register, live standings, round clock). With it off, the opponent outline and Auto-switch to Tournament still work",
		section = otherSection
	)
	default boolean enableTournaments()
	{
		return true;
	}

	@ConfigSection(
		name = "Tournament Gear",
		description = "The required-kit check of tournaments that set one (PvP Arena Unranked Duels)",
		position = 6
	)
	String tournamentGearSection = "tournamentGear";

	@ConfigItem(
		keyName = "gearAutoOpenPanel",
		name = "Open the panel for the kit check",
		description = "When a tournament's kit check starts (and when the event starts) while your kit does not match, open this plugin's panel on the Tournaments tab - once per check",
		section = tournamentGearSection
	)
	default boolean gearAutoOpenPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gearAutoFilterBank",
		name = "Filter the bank to missing kit items",
		description = "During a tournament kit check outside the PvP Arena, filter the bank to the items your kit is missing when you open it",
		section = tournamentGearSection,
		position = 1
	)
	default boolean gearAutoFilterBank()
	{
		return true;
	}

	// ==================== Whitelist Settings ====================

	@ConfigItem(
		keyName = "showRankToOthers",
		name = "Show your rank to others",
		description = "When enabled, your username is shared so others can see your rank above your head",
		section = whitelistSection
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
		description = "A movable counter of your current kill streak in the style you are fighting in",
		position = 5
	)
	String killStreakSection = "killStreak";

	@ConfigItem(
		keyName = "showKillStreakBox",
		name = "Always show kill streak box",
		description = "Show the movable counter '<style> Current Kill Streak: N' everywhere (Alt+drag to move it). "
			+ "Off: it only appears inside the FFA portal, while the option below is on. "
			+ "'N+' means your streak is longer than the loaded match history.",
		section = killStreakSection
	)
	default boolean showKillStreakBox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "killStreakBoxInFfaPortal",
		name = "Show in the FFA portal",
		description = "Show the box while you are inside the Clan Wars FFA portal, even with 'Always show' off",
		section = killStreakSection,
		position = 1
	)
	default boolean killStreakBoxInFfaPortal()
	{
		return true;
	}

	@ConfigItem(
		keyName = "killStreakBoxAutoSwitch",
		name = "Auto-switch style",
		description = "Follow the style of your last fight (NH, Veng, Multi or DMM) and Event while you are in a running tournament. Off: always show the style picked below.",
		section = killStreakSection,
		position = 2
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
		position = 3
	)
	default StreakBucket killStreakBoxBucket()
	{
		return StreakBucket.NH;
	}

	@ConfigItem(
		keyName = "killStreakBoxShowLongest",
		name = "Show longest streak",
		description = "Add a line with your longest kill streak in that style",
		section = killStreakSection,
		position = 4
	)
	default boolean killStreakBoxShowLongest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "killStreakBoxShowWinLoss",
		name = "Show win / loss",
		description = "Add a line with your wins and losses in that style",
		section = killStreakSection,
		position = 5
	)
	default boolean killStreakBoxShowWinLoss()
	{
		return false;
	}

	@ConfigItem(
		keyName = "killStreakBoxShowPeak",
		name = "Show peak rank in box",
		description = "Adds a Peak line to the kill streak box.",
		section = killStreakSection,
		position = 6
	)
	default boolean killStreakBoxShowPeak()
	{
		return false;
	}
}
