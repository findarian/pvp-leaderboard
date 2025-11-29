package com.pvp.leaderboard;

import java.util.Locale;

public final class NameUtils
{
	private NameUtils() {}

	public static String normalizeDisplayName(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.trim().replaceAll("\\s+", " ");
	}

	public static String canonicalKey(String name)
	{
		String display = normalizeDisplayName(name);
		// Treat names case-insensitively for lookups, caching and API queries
		return display.toLowerCase(Locale.ROOT);
	}
}

