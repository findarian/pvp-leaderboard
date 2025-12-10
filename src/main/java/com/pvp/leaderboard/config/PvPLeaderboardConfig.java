package com.pvp.leaderboard.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("PvPLeaderboard")
public interface PvPLeaderboardConfig extends Config
{

	@ConfigItem(
		keyName = "enablePvpLookupMenu",
		name = "Enable 'pvp lookup' right-click",
		description = "Adds the 'pvp lookup' option to player right-click menu"
	)
	default boolean enablePvpLookupMenu()
	{
		return false; 
	}

	@ConfigItem(
		keyName = "rankBucket",
		name = "Rank Bucket",
		description = "Which rank bucket to display in the panel"
	)
	default RankBucket rankBucket()
	{
		return RankBucket.OVERALL;
	}

	@ConfigItem(
		keyName = "debugMode",
		name = "Debug Mode",
		description = "Enable detailed logging for PvP lookups"
	)
	default boolean debugMode()
	{
		return false;
	}

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
}
