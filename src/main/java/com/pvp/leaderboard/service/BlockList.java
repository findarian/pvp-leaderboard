package com.pvp.leaderboard.service;

import java.util.*;

/**
 * In-memory blocked-player registry for the Player Lookup tab's
 * per-row Block button. This is a UI-side block list scoped to the
 * lookup view, kept in step with the server-backed block set (the
 * {@code lobby/block} pushes and snapshot).
 *
 * <p>Names are normalised (lowercase, trimmed, single-spaced) before
 * storage so display-time casing differences don't lead to duplicate
 * entries. The set lives in process memory only; closing RuneLite
 * drops it.
 */
public final class BlockList
{
    private static final Set<String> BLOCKED =
        Collections.synchronizedSet(new LinkedHashSet<>());

    public static boolean isBlocked(String name)
    {
        // A blank name normalises to null, which is never stored
        return BLOCKED.contains(normalize(name));
    }

    /** Adds {@code name} to the block list. */
    public static void block(String name)
    {
        String n = normalize(name);
        if (n != null) BLOCKED.add(n);
    }

    /** Removes {@code name} from the block list. */
    public static void unblock(String name)
    {
        BLOCKED.remove(normalize(name));
    }

    /** Convenience flip — block if currently unblocked, unblock otherwise.
     *  Returns the new state ({@code true} = now blocked). */
    public static boolean toggle(String name)
    {
        if (isBlocked(name))
        {
            unblock(name);
            return false;
        }
        block(name);
        return true;
    }

    /** Replaces the entire set with {@code names} (each normalised).
     *  Used by the {@code MatchmakingLobbyPanel.onBlockListSnapshot}
     *  mirror so a reconnect or cross-device rehydrate keeps the UI-side
     *  block registry in lockstep with the canonical server state.
     *  Null input is treated as "empty set" — the registry is cleared. */
    public static void replaceAll(Set<String> names)
    {
        Set<String> normalised = new LinkedHashSet<>();
        if (names != null)
        {
            for (String n : names)
            {
                String norm = normalize(n);
                if (norm != null) normalised.add(norm);
            }
        }
        synchronized (BLOCKED)
        {
            BLOCKED.clear();
            BLOCKED.addAll(normalised);
        }
    }

    private static String normalize(String n)
    {
        if (n == null) return null;
        String t = n.trim().replaceAll("\\s+", " ").toLowerCase();
        return t.isEmpty() ? null : t;
    }
}
