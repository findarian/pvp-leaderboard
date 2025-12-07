package com.pvp.leaderboard;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;

public class PvPLeaderboardPluginTest
{

    public static void main(String[] args) throws Exception
	{
		loadPlugins(PvPLeaderboardPlugin.class);
		RuneLite.main(args);
	}

    @SafeVarargs
    private static void loadPlugins(Class<? extends Plugin>... plugins)
    {
        ExternalPluginManager.loadBuiltin(plugins);
    }
}

