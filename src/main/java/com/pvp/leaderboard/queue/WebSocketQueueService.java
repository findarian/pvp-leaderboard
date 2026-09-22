package com.pvp.leaderboard.queue;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.Style;
import com.pvp.leaderboard.service.socket.SocketEventBus;
import com.pvp.leaderboard.service.socket.WebSocketManager;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * {@link QueueService} over the plugin's WebSocket (Plan 10 Part B / F.1,
 * 2026-09-21). Encodes the five {@code queue/*} cmds (WEBSOCKET_PROTOCOL.md
 * § 6.2b) and turns the four server pushes + {@code error/queue} into
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
@Slf4j
@Singleton
public class WebSocketQueueService implements QueueService
{
    private final WebSocketManager socket;
    private final SocketEventBus bus;
    private volatile QueueEventListener listener;
    private volatile boolean started = false;

    @Inject
    public WebSocketQueueService(WebSocketManager socket, SocketEventBus bus)
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
        bus.register("queue/matched", this::handleMatched);
        bus.register("queue/timeout", this::handleTimeout);
        bus.register("queue/prefs", this::handlePrefs);
        bus.register("error/queue", this::handleError);
        // ONE connect listener: re-sync the queue row and re-read the
        // shared prefs row (G-2) in the same hook.
        socket.addConnectListener(this::onSocketConnected);
    }

    /** Socket (re-)open: ask for the live queue row and the shared prefs. */
    private void onSocketConnected()
    {
        requestStatus();
        requestPrefs();
    }

    @Override
    public void join(String region, Style style, BuildType build, int minRankIdx, int maxRankIdx, int waitPrefS)
    {
        if (style == null || build == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("region", region == null ? "" : region);
        d.addProperty("style", style.name().toLowerCase());
        d.addProperty("build", build.name().toLowerCase());
        if (minRankIdx != QueueState.UNKNOWN && maxRankIdx != QueueState.UNKNOWN)
        {
            JsonObject rr = new JsonObject();
            rr.addProperty("min_rank_idx", Math.min(minRankIdx, maxRankIdx));
            rr.addProperty("max_rank_idx", Math.max(minRankIdx, maxRankIdx));
            d.add("rank_range", rr);
        }
        d.addProperty("wait_pref_s", normaliseWait(waitPrefS));
        socket.send("queue/join", d);
    }

    /** Snaps any value onto the shared choice list (AS-64). */
    static int normaliseWait(int waitPrefS)
    {
        int best = WAIT_PREF_CHOICES[1];
        long bestDelta = Long.MAX_VALUE;
        for (int c : WAIT_PREF_CHOICES)
        {
            long delta = Math.abs((long) c - waitPrefS);
            if (delta < bestDelta)
            {
                bestDelta = delta;
                best = c;
            }
        }
        return best;
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
        JsonObject prefs = new JsonObject();
        prefs.addProperty("wait_pref_s", normaliseWait(waitPrefS));
        sendPrefs(prefs);
    }

    @Override
    public void sendRankRange(int minRankIdx, int maxRankIdx)
    {
        JsonObject prefs = new JsonObject();
        if (minRankIdx == QueueState.UNKNOWN || maxRankIdx == QueueState.UNKNOWN)
        {
            // An explicit null clears the shared range; an absent key would
            // leave whatever Discord last wrote in place.
            prefs.add("rank_range", com.google.gson.JsonNull.INSTANCE);
        }
        else
        {
            JsonObject rr = new JsonObject();
            rr.addProperty("min_rank_idx", Math.min(minRankIdx, maxRankIdx));
            rr.addProperty("max_rank_idx", Math.max(minRankIdx, maxRankIdx));
            prefs.add("rank_range", rr);
        }
        sendPrefs(prefs);
    }

    /** One {@code queue/set_prefs} frame carrying exactly {@code prefs};
     *  the server merges the named keys and leaves the rest alone. */
    private void sendPrefs(JsonObject prefs)
    {
        JsonObject d = new JsonObject();
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
        JsonObject prefs = com.pvp.leaderboard.util.JsonLenient.optObject(d, "prefs");
        if (prefs != null) onEdt(l -> l.onQueuePrefs(prefs));
    }

    private void handleMatched(JsonObject d)
    {
        QueueMatch match = QueueMatch.fromJson(d);
        if (match == null)
        {
            log.debug("queue/matched without fight_session_id dropped");
            return;
        }
        onEdt(l -> l.onQueueMatched(match));
    }

    private void handleTimeout(JsonObject d)
    {
        QueueState state = QueueState.fromJson(d);
        onEdt(l -> l.onQueueTimeout(state));
    }

    private void handlePrefs(JsonObject d)
    {
        JsonObject prefs = d != null && d.has("prefs") && d.get("prefs").isJsonObject() ? d.getAsJsonObject("prefs") : new JsonObject();
        onEdt(l -> l.onQueuePrefs(prefs));
    }

    private void handleError(JsonObject d)
    {
        String code = com.pvp.leaderboard.util.JsonLenient.optString(d, "code", "UNKNOWN");
        String message = com.pvp.leaderboard.util.JsonLenient.optString(d, "message", "");
        onEdt(l -> l.onQueueError(code, message));
    }

    private void onEdt(java.util.function.Consumer<QueueEventListener> fn)
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
                log.debug("QueueEventListener threw", e);
            }
        });
    }
}
