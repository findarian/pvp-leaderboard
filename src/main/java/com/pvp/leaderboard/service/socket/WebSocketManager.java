package com.pvp.leaderboard.service.socket;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.PvPLeaderboardConstants;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Single-connection socket lifecycle owner. Maintains exactly one
 * {@code okhttp3.WebSocket} to {@link PvPLeaderboardConstants#WEBSOCKET_URL}
 * keyed by the local user's OSRS client UUID.
 *
 * <p><b>Lifecycle contract</b> (enforced by
 * {@code PvPLeaderboardPlugin.onGameStateChanged}):
 * <ul>
 *   <li>{@link #connect(String)} on {@code GameState.LOGGED_IN}
 *   (post-10-tick player-ready delay).</li>
 *   <li>{@link #disconnect()} on {@code GameState.LOGIN_SCREEN}
 *   (close code 1001 GOING_AWAY).</li>
 *   <li>{@link #shutdown()} on plugin {@code shutDown()}.</li>
 * </ul>
 * Re-{@link #connect(String)} with the same UUID while already
 * connected is a no-op. With a different UUID it tears down the
 * current connection (close 1000) and connects fresh — but in practice
 * UUID doesn't change mid-session.
 *
 * <p><b>Reconnect policy</b>: exponential backoff
 * {@code 1s → 2s → 4s → 8s → 16s → 32s → 60s} (cap), reset to 1 s on
 * successful open. Triggered by abnormal close codes (anything other
 * than 1000 / 1001) AND by network-level {@code onFailure}.
 *
 * <p>HTTP 401 (server stealth-refuse) drops into a fixed
 * <b>1-minute slow-retry</b> instead of the fast 1s/2s/4s ladder.
 * Bounded enough that a server-side state change (unban, WAF policy
 * update) is picked up within a minute without requiring a RuneLite
 * restart; spaced enough that the per-IP hit rate stays well under
 * any auto-ban trigger. The panel surfaces a "Attempting to
 * reconnect" banner with the countdown so the user sees the retry
 * is in flight (see {@link #getNextReconnectAttemptEpochMs()}).
 *
 * <p><b>Keepalive</b>: RFC 6455 native ping every 8 min
 * ({@link OkHttpClient.Builder#pingInterval}). API Gateway's idle
 * timeout is 10 min; 8 min leaves a comfortable buffer. No
 * application-layer {@code system/ping} cmd exists per the protocol
 * doc's locked decision §0.1.
 *
 * <p><b>Send model</b>: fire-and-forget. {@link #send(String, JsonObject)}
 * routes through {@link SocketProtocol#encode} (allowlist guard) and
 * then {@code WebSocket.send(String)} — which itself buffers up to
 * 16 MB on the wire. If the socket is closed at send time the call
 * silently no-ops (returns {@code false}). The panel's contract is
 * "user action → fire-and-forget cmd; outcomes arrive asynchronously
 * via push events" so a dropped-while-offline send is acceptable —
 * the next reconnect re-issues {@code lobby/join} which the server
 * uses to re-broadcast roster + replay outstanding invites
 * ({@code WEBSOCKET_PROTOCOL.md §5.2}).
 */
@Slf4j
@Singleton
public final class WebSocketManager
{
    /** RFC 6455 close code for clean disconnect by either party. */
    public static final int CLOSE_NORMAL = 1000;
    /** RFC 6455 close code for "endpoint going away" (logout). */
    public static final int CLOSE_GOING_AWAY = 1001;

    /** Lowercase UUID-v4 format check — same regex the backend uses
     *  in {@code backend.core.validation.is_valid_uuid_format}. Pre-flight
     *  rejection prevents accidentally getting the IP WAF-banned by
     *  a malformed-UUID $connect attempt (architecture Phase 1
     *  validation step 2). */
    private static final Pattern UUID_V4_LOWERCASE = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private static final String USER_AGENT = "RuneLite/" + RuneLiteProperties.getVersion();

    private static final long PING_INTERVAL_MIN = 8L;

    /** Initial reconnect delay; doubles up to {@link #BACKOFF_MAX_MS}. */
    private static final long BACKOFF_INITIAL_MS = 1_000L;
    private static final long BACKOFF_MAX_MS = 60_000L;

    /** Fixed 1-minute retry interval used after an HTTP 401
     *  stealth-refuse. Separate from {@link #BACKOFF_INITIAL_MS} /
     *  {@link #BACKOFF_MAX_MS} so a normal-close reconnect doesn't
     *  promote into the slow lane and a 401 doesn't drop into the
     *  1-second cadence. See class javadoc on the reconnect policy
     *  for the rationale. */
    private static final long AUTH_REFUSED_RETRY_MS = 60_000L;

    private final OkHttpClient sharedHttpClient;
    private final SocketEventBus eventBus;
    private final ScheduledExecutorService scheduler;

    /** Lazily-built pinging client; reuses the shared client's connection
     *  pool / dispatcher via {@code newBuilder()} so we don't double up
     *  on threads / cache. */
    private OkHttpClient pingingClient;

    private final AtomicBoolean shutdownCalled = new AtomicBoolean(false);

    /** Active connection state — all access guarded by {@code this}
     *  monitor since {@link #connect} / {@link #disconnect} / WebSocket
     *  listener callbacks race. The OkHttp dispatcher uses its own
     *  thread pool for listener callbacks, so without the lock a
     *  {@code onClosed} → reconnect-schedule could race with a
     *  user-driven {@link #disconnect}. */
    private WebSocket activeSocket;
    private String activeUuid;
    /** Display-cased name of the in-game character the user is currently
     *  logged in as (e.g. {@code "Toyco"}). Sent to the server on
     *  $connect as a {@code &name=<urlencoded>} query parameter so the
     *  conn row + lobby member row are pinned to the active session
     *  rather than {@code sorted(player_names)[0]}. {@code null} when
     *  not yet known (called before {@code client.getLocalPlayer()}
     *  resolves). Backend treats null/missing as "fall back to alphabet
     *  default" and validates non-null names against the MMR row's
     *  {@code player_names} set (anti-spoof). */
    private String activeName;
    private long currentBackoffMs = BACKOFF_INITIAL_MS;
    private ScheduledFuture<?> pendingReconnect;
    /** Epoch ms when {@link #pendingReconnect} is scheduled to fire,
     *  or {@code 0} when no retry is queued. Exposed via
     *  {@link #getNextReconnectAttemptEpochMs()} so the lobby panel
     *  can render a countdown banner. Set on every
     *  {@link #scheduleReconnect()} / {@link #scheduleAuthRefusedRetry()}
     *  and cleared on {@link #reconnectTick()} entry / a successful
     *  {@link Listener#onOpen}. {@code volatile} since the panel's
     *  Swing-EDT ticker reads it from a different thread than the
     *  scheduler thread that writes it. */
    private volatile long nextReconnectEpochMs = 0L;

    /** Set to {@code true} when the manager has been asked to disconnect
     *  cleanly (logout / plugin shutdown). The listener's {@code onClosed}
     *  uses this flag to skip the reconnect schedule. */
    private volatile boolean intentionalDisconnect = false;

    /** Listeners called every time the socket transitions from
     *  not-connected to connected (i.e. {@code onOpen} fires). Used by
     *  {@link com.pvp.leaderboard.lobby.WebSocketLobbyService} to
     *  re-issue {@code lobby/join} on reconnect so the user's roster
     *  membership survives a transient drop. Invoked on the OkHttp
     *  dispatcher thread — listeners must marshal to their target thread
     *  themselves. {@link CopyOnWriteArrayList} so addConnectListener
     *  during an open-callback doesn't trip CME. */
    private final CopyOnWriteArrayList<Runnable> connectListeners = new CopyOnWriteArrayList<>();

    @Inject
    public WebSocketManager(OkHttpClient sharedHttpClient,
                            SocketEventBus eventBus,
                            ScheduledExecutorService scheduler)
    {
        this.sharedHttpClient = sharedHttpClient;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
    }

    /**
     * Opens (or resumes) the socket for {@code uuid}. Idempotent — a
     * second call with the same UUID while already connected is a no-op;
     * a call with a different UUID tears down the prior connection and
     * connects fresh.
     *
     * <p>Backwards-compat shim — delegates to {@link #connect(String,
     * String)} with no display-name. The server will fall back to its
     * alphabetical default name from the MMR row's player_names set.
     * New code should always pass the active local player's name so the
     * conn row matches the in-game session.
     *
     * @param uuid OSRS client UUID, lowercase v4. Invalid UUIDs are
     *             refused without a connect attempt to avoid the WAF
     *             auto-ban that would follow.
     */
    public synchronized void connect(String uuid)
    {
        connect(uuid, null);
    }

    /**
     * Opens (or resumes) the socket for {@code (uuid, displayName)}. Same
     * idempotency contract as {@link #connect(String)} but extends the
     * "same connection?" check to the display name as well: a call with
     * the same UUID but a different name (e.g. user switched characters
     * on the same client) tears down and reconnects so the server's
     * OSRS-Connections row matches the active in-game session.
     *
     * <p>The {@code displayName} is sent to the server as a
     * {@code &name=<urlencoded>} query parameter on the WebSocket URL.
     * The server validates it against the MMR row's {@code player_names}
     * set (anti-spoof — a modified plugin can't claim a name that isn't
     * linked to the trusted UUID) and uses it for the conn row's
     * {@code name} + canonical {@code player_id} attributes. Pass
     * {@code null} when the name isn't known yet (e.g. plugin re-toggle
     * before {@code client.getLocalPlayer()} has resolved); the server
     * falls back to {@code sorted(player_names)[0]}.
     *
     * @param uuid        OSRS client UUID, lowercase v4. Invalid UUIDs
     *                    are refused without a connect attempt to avoid
     *                    the WAF auto-ban that would follow.
     * @param displayName Active in-game display name (e.g. "Toyco"), or
     *                    {@code null} if not yet known.
     */
    public synchronized void connect(String uuid, String displayName)
    {
        if (shutdownCalled.get())
        {
            log.debug("WebSocketManager: connect after shutdown — ignoring");
            return;
        }
        if (uuid == null || !UUID_V4_LOWERCASE.matcher(uuid).matches())
        {
            log.warn("WebSocketManager: refusing connect with invalid UUID format (would trip WAF auto-ban)");
            return;
        }
        String normName = (displayName == null || displayName.trim().isEmpty())
            ? null : displayName.trim();
        if (activeSocket != null
            && uuid.equals(activeUuid)
            && java.util.Objects.equals(normName, activeName))
        {
            // Same (uuid, name) tuple already wired — no-op resume.
            return;
        }
        if (activeSocket != null)
        {
            // Switching UUID *or* display name: close existing cleanly
            // so the server can free its OSRS-Connections row before we
            // open a new one. The server also force-closes duplicates
            // server-side (force_close_duplicate keys on player_id, and
            // a name change with the same UUID could yield a different
            // player_id — explicit teardown avoids racing the
            // server-side eviction with a fresh open).
            intentionalDisconnect = true;
            String reason = uuid.equals(activeUuid) ? "name_change" : "uuid_change";
            try { activeSocket.close(CLOSE_NORMAL, reason); }
            catch (Exception ignored) { /* best-effort */ }
            activeSocket = null;
        }
        intentionalDisconnect = false;
        activeUuid = uuid;
        activeName = normName;
        cancelPendingReconnect();
        openSocketLocked();
    }

    /**
     * Closes the socket with {@link #CLOSE_GOING_AWAY}. Disables
     * reconnect until the next {@link #connect(String)} call. Idempotent.
     */
    public synchronized void disconnect()
    {
        intentionalDisconnect = true;
        cancelPendingReconnect();
        if (activeSocket != null)
        {
            try { activeSocket.close(CLOSE_GOING_AWAY, "client_logout"); }
            catch (Exception ignored) { /* best-effort */ }
            activeSocket = null;
        }
        activeUuid = null;
    }

    /**
     * Final teardown — called from the plugin's {@code shutDown()}.
     * Closes the socket and forbids any future reconnect attempts
     * (including scheduled retries).
     */
    public synchronized void shutdown()
    {
        if (!shutdownCalled.compareAndSet(false, true)) return;
        disconnect();
    }

    /**
     * Encodes + sends a cmd. Returns {@code true} if the frame was
     * handed off to OkHttp's send queue, {@code false} if the socket is
     * closed or the cmd isn't allowlisted.
     *
     * <p>NOTE: this is the only way to send anything. The encode helper
     * enforces the {@link SocketProtocol#ALLOWED_OUTGOING} guard.
     */
    public boolean send(String cmd, JsonObject data)
    {
        WebSocket snapshot;
        synchronized (this) { snapshot = activeSocket; }
        if (snapshot == null) return false;
        final String wire;
        try
        {
            wire = SocketProtocol.encode(cmd, data);
        }
        catch (IllegalArgumentException e)
        {
            log.warn("WebSocketManager: refusing to send disallowed cmd={}", cmd);
            return false;
        }
        return snapshot.send(wire);
    }

    /** True while {@link #activeSocket} != null. */
    public synchronized boolean isConnected()
    {
        return activeSocket != null;
    }

    /** Epoch ms at which the next reconnect attempt is scheduled to
     *  fire, or {@code 0} when no retry is pending (either because
     *  the socket is connected, or because no UUID has been set yet).
     *
     *  <p>Read on the Swing EDT by the lobby panel's 1Hz banner
     *  ticker; written on the scheduler/OkHttp threads. The field is
     *  {@code volatile} so the read sees the latest write without
     *  taking the manager's monitor (the ticker fires every second —
     *  contending on the monitor would serialize it behind in-flight
     *  sends). Stale-by-up-to-one-second is fine for a countdown
     *  display. */
    public long getNextReconnectAttemptEpochMs()
    {
        return nextReconnectEpochMs;
    }

    /**
     * Registers {@code l} to be called every time the socket completes
     * an {@code $connect} handshake ({@code onOpen}). Invoked on
     * OkHttp's dispatcher thread — listeners must marshal as needed.
     *
     * <p>No unregister API: the only known caller is the
     * {@code @Singleton} {@code WebSocketLobbyService}; exposing one
     * invites lifecycle bugs.
     */
    public void addConnectListener(Runnable l)
    {
        if (l != null) connectListeners.add(l);
    }

    // ---------------------------------------------------------------
    // Internal — connection wire-up
    // ---------------------------------------------------------------

    /** Lazily builds (and caches) the OkHttpClient used specifically for
     *  the WebSocket. Derived from the injected shared client so we
     *  keep its connection pool / SSL config but tack on the 8-min
     *  RFC 6455 ping interval. */
    private OkHttpClient pingingClient()
    {
        if (pingingClient == null)
        {
            pingingClient = sharedHttpClient.newBuilder()
                .pingInterval(PING_INTERVAL_MIN, TimeUnit.MINUTES)
                .build();
        }
        return pingingClient;
    }

    /** Must be called under {@code synchronized (this)}. */
    private void openSocketLocked()
    {
        if (activeUuid == null) return;
        StringBuilder url = new StringBuilder(PvPLeaderboardConstants.WEBSOCKET_URL)
            .append("?uuid=").append(activeUuid);
        if (activeName != null)
        {
            // RFC 3986 reserved chars are stripped by the in-game name
            // validator (RuneScape names are [A-Za-z0-9 _-]) so a single
            // urlencode pass is sufficient — no double-encode risk.
            url.append("&name=").append(java.net.URLEncoder.encode(
                activeName, java.nio.charset.StandardCharsets.UTF_8));
        }
        Request req = new Request.Builder()
            .url(url.toString())
            .header("User-Agent", USER_AGENT)
            .build();
        log.debug("WebSocketManager: connecting uuid={}... name={}",
            activeUuid.substring(0, 8), activeName == null ? "<none>" : activeName);
        activeSocket = pingingClient().newWebSocket(req, new Listener());
    }

    private synchronized void cancelPendingReconnect()
    {
        if (pendingReconnect != null)
        {
            pendingReconnect.cancel(false);
            pendingReconnect = null;
        }
        nextReconnectEpochMs = 0L;
    }

    /** Schedules a reconnect with the current backoff and doubles it
     *  (up to the cap) for the next failure. Resets to initial on
     *  successful open via {@link Listener#onOpen}. */
    private synchronized void scheduleReconnect()
    {
        if (shutdownCalled.get() || intentionalDisconnect || activeUuid == null) return;
        if (pendingReconnect != null && !pendingReconnect.isDone()) return;
        long delay = currentBackoffMs;
        currentBackoffMs = Math.min(currentBackoffMs * 2, BACKOFF_MAX_MS);
        log.debug("WebSocketManager: reconnect scheduled in {} ms", delay);
        nextReconnectEpochMs = System.currentTimeMillis() + delay;
        pendingReconnect = scheduler.schedule(this::reconnectTick, delay, TimeUnit.MILLISECONDS);
    }

    /** Slow-retry counterpart to {@link #scheduleReconnect()} for the
     *  HTTP 401 stealth-refuse path. Fixed 1-minute cadence so a
     *  server-side ban being lifted is picked up within a minute
     *  without compounding the WAF auto-ban. The panel surfaces a
     *  reconnect banner with a countdown driven by
     *  {@link #getNextReconnectAttemptEpochMs()}. */
    private synchronized void scheduleAuthRefusedRetry()
    {
        if (shutdownCalled.get() || intentionalDisconnect || activeUuid == null) return;
        if (pendingReconnect != null && !pendingReconnect.isDone()) return;
        long delay = AUTH_REFUSED_RETRY_MS;
        log.debug("WebSocketManager: 401 slow-retry scheduled in {} ms", delay);
        nextReconnectEpochMs = System.currentTimeMillis() + delay;
        pendingReconnect = scheduler.schedule(this::reconnectTick, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void reconnectTick()
    {
        pendingReconnect = null;
        nextReconnectEpochMs = 0L;
        if (shutdownCalled.get() || intentionalDisconnect || activeUuid == null) return;
        if (activeSocket != null) return;
        openSocketLocked();
    }

    /** WebSocket listener — runs on OkHttp's dispatcher thread. */
    private final class Listener extends WebSocketListener
    {
        @Override
        public void onOpen(WebSocket webSocket, Response response)
        {
            synchronized (WebSocketManager.this)
            {
                // Reset the fast-cadence backoff on a successful open
                // so the next normal-close blip starts at 1 s again.
                // The 401 retry cadence is fixed (1 min) so there's
                // nothing to reset there.
                currentBackoffMs = BACKOFF_INITIAL_MS;
                nextReconnectEpochMs = 0L;
            }
            log.debug("WebSocketManager: open");
            for (Runnable l : connectListeners)
            {
                try { l.run(); }
                catch (Exception e) { log.debug("WebSocketManager: connect listener threw", e); }
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            SocketCommand cmd = SocketProtocol.decode(text);
            if (cmd == null)
            {
                log.debug("WebSocketManager: dropping unparseable frame ({} bytes)",
                    text == null ? 0 : text.length());
                return;
            }
            eventBus.fire(cmd.cmd, cmd.data);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes)
        {
            // The server only sends text frames per the protocol doc;
            // a binary frame is a server bug. Drop silently.
            log.debug("WebSocketManager: dropping unexpected binary frame ({} bytes)", bytes.size());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason)
        {
            // Acknowledge server-initiated close cleanly.
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason)
        {
            boolean shouldReconnect;
            synchronized (WebSocketManager.this)
            {
                activeSocket = null;
                // 1000 (normal) / 1001 (going-away) are clean closes —
                // don't reconnect. Anything else is the server kicking
                // us off (1008 dup-connect, 1006 abnormal) — reconnect
                // with backoff.
                shouldReconnect = !intentionalDisconnect
                    && code != CLOSE_NORMAL
                    && code != CLOSE_GOING_AWAY;
            }
            log.debug("WebSocketManager: closed code={} reason={} reconnect={}",
                code, reason, shouldReconnect);
            if (shouldReconnect) scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response)
        {
            int status = response == null ? -1 : response.code();
            boolean intentional;
            synchronized (WebSocketManager.this)
            {
                activeSocket = null;
                intentional = intentionalDisconnect;
            }
            log.debug("WebSocketManager: failure status={} cause={} intentional={}",
                status, t == null ? "null" : t.getClass().getSimpleName(), intentional);
            if (response != null) response.close();
            if (intentional) return;
            // HTTP 401 = server stealth-refuse on $connect. Drop into
            // the fixed 1-minute slow-retry so server-side state
            // changes (unban, WAF policy update, the player's first
            // match landing) recover automatically without forcing
            // the user to restart RuneLite. Other failures use the
            // standard fast-cadence backoff. The panel renders a
            // visible countdown banner from
            // getNextReconnectAttemptEpochMs() so the user sees the
            // retry is in flight.
            if (status == 401)
            {
                scheduleAuthRefusedRetry();
            }
            else
            {
                scheduleReconnect();
            }
        }
    }
}
