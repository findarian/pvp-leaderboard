package com.pvp.leaderboard.util;

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
		// RuneLite nameplates (Player.getName(), menu targets, chat) separate
		// name words with a NON-BREAKING SPACE (\u00A0). Java's \s does NOT
		// match it (ASCII-only without UNICODE_CHARACTER_CLASS) and trim()
		// does not strip it, so it must be unified to a regular space FIRST —
		// otherwise every multi-word in-scene name fails membership checks
		// and shard lookups against backend-canonical names ("run piggy").
		return name.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
	}

	public static String canonicalKey(String name)
	{
		String display = normalizeDisplayName(name);
		// Treat names case-insensitively for lookups, caching and API queries
		return display.toLowerCase(Locale.ROOT);
	}
}
