package com.pvp.leaderboard.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory blocked-player registry for the Player Lookup tab's
 * per-row Block button. This is a UI-side block list scoped to the
 * lookup view; the matchmaking lobby maintains its own server-backed
 * block set via {@link com.pvp.leaderboard.lobby.LobbyService#block}.
 *
 * <p>Names are normalised (lowercase, trimmed, single-spaced) before
 * storage so display-time casing differences don't lead to duplicate
 * entries. The set lives in process memory only; closing RuneLite
 * drops it.
 *
 * <p>Listeners (via {@link #addListener(Consumer)}) are invoked on
 * whichever thread mutates the set — callers that need EDT delivery
 * should wrap with {@code SwingUtilities.invokeLater} themselves.
 */
public final class BlockedPlayersService
{
    private static final Set<String> BLOCKED =
        Collections.synchronizedSet(new LinkedHashSet<>());

    /** Listeners notified after every successful mutation. The blocked name
     *  (normalised) is passed so listeners don't need to re-snapshot. */
    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS =
        new CopyOnWriteArrayList<>();

    private BlockedPlayersService() {}

    public static boolean isBlocked(String name)
    {
        String n = normalize(name);
        return n != null && BLOCKED.contains(n);
    }

    /** Adds {@code name} to the block list. Returns true if it wasn't already
     *  blocked. Notifies listeners on success. */
    public static boolean block(String name)
    {
        String n = normalize(name);
        if (n == null) return false;
        boolean added = BLOCKED.add(n);
        if (added) fire(n);
        return added;
    }

    /** Removes {@code name} from the block list. Returns true if it was
     *  previously blocked. Notifies listeners on success. */
    public static boolean unblock(String name)
    {
        String n = normalize(name);
        if (n == null) return false;
        boolean removed = BLOCKED.remove(n);
        if (removed) fire(n);
        return removed;
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

    /** Snapshot of the current set. Safe to iterate without external locking. */
    public static Set<String> snapshot()
    {
        synchronized (BLOCKED) { return new LinkedHashSet<>(BLOCKED); }
    }

    public static void addListener(Consumer<String> l)
    {
        if (l != null) LISTENERS.add(l);
    }

    public static void removeListener(Consumer<String> l)
    {
        if (l != null) LISTENERS.remove(l);
    }

    private static void fire(String normalisedName)
    {
        for (Consumer<String> l : LISTENERS)
        {
            try { l.accept(normalisedName); } catch (RuntimeException ignore) {}
        }
    }

    private static String normalize(String n)
    {
        if (n == null) return null;
        String t = n.trim().replaceAll("\\s+", " ").toLowerCase();
        return t.isEmpty() ? null : t;
    }
}
