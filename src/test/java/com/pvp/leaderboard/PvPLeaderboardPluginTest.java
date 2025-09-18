package com.pvp.leaderboard;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PvPLeaderboardPluginTest
{
    @SuppressWarnings({"unchecked", "varargs"})
    public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PvPLeaderboardPlugin.class);
		RuneLite.main(args);
	}
}

