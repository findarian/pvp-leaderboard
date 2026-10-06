package com.pvp.leaderboard.queue;

import com.google.gson.*;
import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.service.socket.*;
import com.pvp.leaderboard.util.*;
import java.util.function.*;
import javax.inject.*;
import javax.swing.*;

/**
 * {@link QueueService} over the plugin's WebSocket (Plan 10 Part B / F.1,
 * 2026-09-21). Encodes the five {@code queue/*} cmds (WEBSOCKET_PROTOCOL.md
 * § 6.2b) and turns the server pushes + {@code error/queue} into
 * EDT-delivered {@link QueueEventListener} callbacks.
 *
 * <p>Reconnect: after a socket re-open the server's row may or may not
 * still exist (queue rows expire with the wait preference), so the
 * service re-asks with {@code queue/status} instead of replaying the
 * join — the panel re-renders from the answer.
 *
 * <p>No UUIDs: {@code queue/join} carries region / style / build / range
 * / wait only; the server keys the row on the trusted connection.
 */
@Singleton
public class WebSocketQueueService implements QueueService
{
    private final SocketMgr socket;
    private final SocketBus bus;
    private volatile QueueEventListener listener;
    private volatile boolean started = false;

    @Inject
    public WebSocketQueueService(SocketMgr socket, SocketBus bus)
    {
        this.socket = socket;
        this.bus = bus;
    }

    @Override
    public void setListener(QueueEventListener listener)
    {
        this.listener = listener;
    }

    @Override
    public synchronized void start()
    {
        if (started) return;
        started = true;
        bus.register("queue/state", this::handleState);
        bus.register("queue/timeout", this::handleTimeout);
        bus.register("queue/prefs", this::handlePrefs);
        bus.register("error/queue", this::handleError);
        // ONE re-sync listener: on a socket (re-)open, ask for the live queue
        // row and re-read the shared prefs row (G-2) in the same hook.
        socket.addResyncListener(() ->
        {
            requestStatus();
            requestPrefs();
        });
    }

    @Override
    public void join(String region, Style style, BuildType build, int minRankIdx, int maxRankIdx, int waitPrefS)
    {
        if (style == null || build == null) return;
        var d = new JsonObject();
        d.addProperty("region", region == null ? "" : region);
        d.addProperty("style", style.name().toLowerCase());
        d.addProperty("build", build.name().toLowerCase());
        if (minRankIdx != QueueState.UNKNOWN && maxRankIdx != QueueState.UNKNOWN)
        {
            d.add("rank_range", range(minRankIdx, maxRankIdx));
        }
        d.addProperty("wait_pref_s", snapWait(waitPrefS));
        socket.send("queue/join", d);
    }

    /** The {@code rank_range} object: the two bounds, lowest first. */
    private static JsonObject range(int a, int b)
    {
        var rr = new JsonObject();
        rr.addProperty("min_rank_idx", Math.min(a, b));
        rr.addProperty("max_rank_idx", Math.max(a, b));
        return rr;
    }

    /** Snaps any value onto the shared choice list (AS-64). */
    static int snapWait(int waitPrefS)
    {
        return WAIT_CHOICES[QueueService.waitIndex(waitPrefS)];
    }

    @Override
    public void leave()
    {
        socket.send("queue/leave", new JsonObject());
    }

    @Override
    public void expandRange()
    {
        socket.send("queue/expand_range", new JsonObject());
    }

    @Override
    public void requestStatus()
    {
        socket.send("queue/status", new JsonObject());
    }

    // ---- shared preferences (G-2) ----

    @Override
    public void requestPrefs()
    {
        // An EMPTY prefs object: matchmaking_queue.save_prefs validates it
        // to {}, merges nothing, and answers queue/prefs with the stored
        // row. A read without a backend change — and without the risk of
        // pushing a stale local value over one set from Discord.
        sendPrefs(new JsonObject());
    }

    @Override
    public void sendWaitPref(int waitPrefS)
    {
        var prefs = new JsonObject();
        prefs.addProperty("wait_pref_s", snapWait(waitPrefS));
        sendPrefs(prefs);
    }

    @Override
    public void sendRange(int minRankIdx, int maxRankIdx)
    {
        var prefs = new JsonObject();
        // An explicit null clears the shared range; an absent key would
        // leave whatever Discord last wrote in place.
        prefs.add("rank_range", minRankIdx == QueueState.UNKNOWN || maxRankIdx == QueueState.UNKNOWN
            ? JsonNull.INSTANCE : range(minRankIdx, maxRankIdx));
        sendPrefs(prefs);
    }

    /** One {@code queue/set_prefs} frame carrying exactly {@code prefs};
     *  the server merges the named keys and leaves the rest alone. */
    private void sendPrefs(JsonObject prefs)
    {
        var d = new JsonObject();
        d.add("prefs", prefs);
        socket.send("queue/set_prefs", d);
    }

    // ---- inbound ----
    private void handleState(JsonObject d)
    {
        QueueState state = QueueState.fromJson(d);
        onEdt(l -> l.onQueueState(state));
        // Forward compatibility with the G-2 backend half: when
        // matchmaking_queue.status_snapshot starts carrying an additive
        // `prefs` object, this client already applies it — no second
        // plugin release needed (CLAUDE.md § 10).
        JsonObject prefs = JsonLenient.optObject(d, "prefs");
        if (prefs != null) onEdt(l -> l.onQueuePrefs(prefs));
    }

    private void handleTimeout(JsonObject d)
    {
        QueueState state = QueueState.fromJson(d);
        onEdt(l -> l.onQueueTimeout(state));
    }

    private void handlePrefs(JsonObject d)
    {
        JsonObject sent = JsonLenient.optObject(d, "prefs");
        JsonObject prefs = sent == null ? new JsonObject() : sent;
        onEdt(l -> l.onQueuePrefs(prefs));
    }

    private void handleError(JsonObject d)
    {
        String code = JsonLenient.optString(d, "code", "UNKNOWN");
        String message = JsonLenient.optString(d, "message");
        onEdt(l -> l.onQueueError(code, message));
    }

    private void onEdt(Consumer<QueueEventListener> fn)
    {
        SwingUtilities.invokeLater(() ->
        {
            QueueEventListener l = listener;
            if (l == null) return;
            try
            {
                fn.accept(l);
            }
            catch (Exception e)
            {
            }
        });
    }
}
