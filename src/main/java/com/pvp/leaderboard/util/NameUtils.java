package com.pvp.leaderboard.util;

import java.util.*;
import java.util.regex.*;

public final class NameUtils
{
	/** Runs of blanks, compiled once. */
	private static final Pattern BLANKS = Pattern.compile("\\s+");

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
		// and shard lookups against canonical names.
		return BLANKS.matcher(name.replace('\u00A0', ' ').trim()).replaceAll(" ");
	}

	public static String canonicalKey(String name)
	{
		String display = normalizeDisplayName(name);
		// Treat names case-insensitively for lookups, caching and API queries
		return display.toLowerCase(Locale.ROOT);
	}
}
