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

}
