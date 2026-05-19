package com.pvp.leaderboard.service.socket;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Cmd-keyed listener registry for server-pushed socket events.
 * Owns the dispatch fanout from {@link WebSocketManager}'s
 * single read-loop thread to whatever subscriber wants the payload.
 *
 * <p>Subscribers are {@code Consumer<JsonObject>} so they receive the
 * payload {@code data} object directly (the outer {@code cmd} envelope
 * is already routed by name to the right consumer list). Subscribers
 * are called <b>synchronously on the socket's read-loop thread</b> —
 * they MUST be cheap or marshal to another thread themselves. The
 * existing pattern in the plugin uses {@code SwingUtilities.invokeLater}
 * to bounce to the EDT for any UI mutation; the lobby panel's
 * {@link com.pvp.leaderboard.lobby.LobbyService}/
 * {@link com.pvp.leaderboard.lobby.LobbyEventListener} contract requires
 * EDT delivery — the {@code WebSocketLobbyService} adapter does that
 * marshalling so individual UI consumers don't have to.
 *
 * <p>Registrations are append-only for the life of the registered
 * service (typically the {@code @Singleton} {@code WebSocketLobbyService}).
 * No unregister API yet — singleton services don't need one and
 * exposing one invites lifecycle bugs where stale callbacks live past
 * their owner.
 *
 * <p>{@link CopyOnWriteArrayList} per-cmd lets fire iteration not
 * block registration and vice versa, so a subscriber registering a
 * new listener from inside its callback (rare but legal) doesn't
 * trip {@link java.util.ConcurrentModificationException}.
 */
@Slf4j
@Singleton
public final class SocketEventBus
{
    /** Cmd → list of subscribers. Initialised lazily on first register. */
    private final Map<String, CopyOnWriteArrayList<Consumer<JsonObject>>> listeners = new HashMap<>();

    /** Backing set kept in sync with {@link #listeners} keys; used for
     *  quick "is anyone listening for this cmd" checks in
     *  {@link WebSocketManager}'s dispatch (avoids the HashMap entry
     *  hit when no one cares about a server push). */
    private final Set<String> subscribedCmds = new HashSet<>();

    /**
     * Subscribes {@code handler} to be invoked every time a server frame
     * with the given {@code cmd} arrives. Multiple subscribers per cmd
     * are allowed and called in registration order; the typical pattern
     * is one subscriber per cmd (the {@code WebSocketLobbyService}).
     */
    public synchronized void register(String cmd, Consumer<JsonObject> handler)
    {
        if (cmd == null || cmd.isEmpty() || handler == null) return;
        listeners.computeIfAbsent(cmd, k -> new CopyOnWriteArrayList<>()).add(handler);
        subscribedCmds.add(cmd);
    }

    /**
     * Fires {@code data} to every registered subscriber of {@code cmd}.
     * No-op if there are no subscribers. Exceptions thrown by any
     * subscriber are caught + logged so a single buggy listener can't
     * starve the rest.
     */
    public void fire(String cmd, JsonObject data)
    {
        if (cmd == null) return;
        CopyOnWriteArrayList<Consumer<JsonObject>> subs;
        synchronized (this)
        {
            subs = listeners.get(cmd);
        }
        if (subs == null || subs.isEmpty()) return;
        for (Consumer<JsonObject> s : subs)
        {
            try
            {
                s.accept(data);
            }
            catch (Exception e)
            {
                // Best-effort isolation: don't let a UI bug in one
                // listener take down the dispatch loop.
                log.debug("SocketEventBus subscriber threw for cmd={}", cmd, e);
            }
        }
    }

    /** Test/debug accessor: true if {@link #register} has been called for
     *  the given cmd at least once. Used by {@link WebSocketManager}
     *  diagnostics. */
    public synchronized boolean hasSubscribers(String cmd)
    {
        return cmd != null && subscribedCmds.contains(cmd);
    }
}
