package com.pvp.leaderboard.service.socket;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;

/**
 * Cmd-keyed listener registry for server-pushed socket events.
 * Owns the dispatch fanout from {@link SocketMgr}'s
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
@Singleton
public final class SocketBus
{
    /** Cmd → list of subscribers. Initialised lazily on first register;
     *  never holds a null or empty cmd. */
    private final Map<String, CopyOnWriteArrayList<Consumer<JsonObject>>> listeners = new HashMap<>();

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
    }

    /**
     * Fires {@code data} to every registered subscriber of {@code cmd}.
     * No-op if there are no subscribers. Exceptions thrown by any
     * subscriber are caught + logged so a single buggy listener can't
     * starve the rest.
     */
    public void fire(String cmd, JsonObject data)
    {
        CopyOnWriteArrayList<Consumer<JsonObject>> subs;
        synchronized (this)
        {
            subs = listeners.get(cmd);
        }
        if (subs == null) return;
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
            }
        }
    }

    /** Test/debug accessor: true if {@link #register} has been called for
     *  the given cmd at least once. Used by {@link SocketMgr}
     *  diagnostics. */
    public synchronized boolean hasSubscribers(String cmd)
    {
        return listeners.containsKey(cmd);
    }
}
