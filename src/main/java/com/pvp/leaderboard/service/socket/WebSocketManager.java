package com.pvp.leaderboard.service.socket;

import com.google.gson.Gson;
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
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
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
 * <p><b>Reconnect policy</b>: a jittered exponential backoff, see
 * {@link #reconnectDelayMs(int, double)}. The attempt count grows with
 * every connection that fails or closes before
 * {@link #STABLE_CONNECTION_MS} and resets once one stays open that long.
 * Triggered by any close the plugin did not request, whatever its code
 * (the server's 2-hour close included), AND by network-level
 * {@code onFailure}; a logout or a plugin stop never reconnects. A
 * {@link #connect} while a
 * retry is scheduled leaves that retry in place, and more than
 * {@link #OPEN_BURST} opens within {@link #OPEN_BURST_WINDOW_MS} wait
 * for a scheduled retry.
 *
 * <p>HTTP 401 retries after {@link #authRefusedDelayMs(double)}. The
 * panel surfaces a "Attempting to reconnect" banner with the countdown
 * (see {@link #getNextReconnectAttemptEpochMs()}).
 *
 * <p>{@code error/rate_limited} with {@code retry_at_epoch_ms} holds
 * outgoing frames until that time, at most
 * {@link #RATE_LIMIT_MAX_HOLD_MS}: the named {@code cmd}, or every frame
 * when none is named.
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

    static final long BACKOFF_MIN_MS = 1_000L;
    static final long BACKOFF_MAX_MS = 60_000L;
    static final long STABLE_CONNECTION_MS = 30_000L;
    static final long AUTH_REFUSED_RETRY_MIN_MS = 30_000L;
    static final long AUTH_REFUSED_RETRY_MAX_MS = 90_000L;
    static final long RESYNC_MAX_DELAY_MS = 5_000L;
    static final int OPEN_BURST = 5;
    static final long OPEN_BURST_WINDOW_MS = 60_000L;
    static final long RATE_LIMIT_MAX_HOLD_MS = 60_000L;

    private final OkHttpClient sharedHttpClient;
    private final SocketEventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;
    private final com.pvp.leaderboard.config.PvPLeaderboardConfig config;
    private final LongSupplier clock;
    private final DoubleSupplier random;

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
    /** Consecutive scheduled reconnects since a socket last stayed open
     *  {@link #STABLE_CONNECTION_MS}. */
    private int retryAttempt;
    /** Clock reading at the current socket's open; {@code 0} when none is open. */
    private long openedAtMs;
    /** Clock readings of the recent opens, oldest first. */
    private final ArrayDeque<Long> recentOpensMs = new ArrayDeque<>();
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

    private final CopyOnWriteArrayList<Runnable> resyncListeners = new CopyOnWriteArrayList<>();

    /** Clock reading until which every outgoing frame is held; {@code 0} when none is. */
    private volatile long holdAllUntilMs;
    /** Per-cmd hold, clock reading until which that cmd is held. */
    private final Map<String, Long> holdCmdUntilMs = new ConcurrentHashMap<>();

    @Inject
    public WebSocketManager(OkHttpClient sharedHttpClient,
                            SocketEventBus eventBus,
                            ScheduledExecutorService scheduler,
                            Gson gson,
                            com.pvp.leaderboard.config.PvPLeaderboardConfig config)
    {
        this(sharedHttpClient, eventBus, scheduler, gson, config,
            System::currentTimeMillis, () -> ThreadLocalRandom.current().nextDouble());
    }

    WebSocketManager(OkHttpClient sharedHttpClient,
                     SocketEventBus eventBus,
                     ScheduledExecutorService scheduler,
                     Gson gson,
                     com.pvp.leaderboard.config.PvPLeaderboardConfig config,
                     LongSupplier clock,
                     DoubleSupplier random)
    {
        this.sharedHttpClient = sharedHttpClient;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.gson = gson;
        this.config = config;
        this.clock = clock;
        this.random = random;
        if (eventBus != null) eventBus.register("error/rate_limited", this::onRateLimited);
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
            // Plugin re-toggle path: RuneLite preserves the parent
            // injector across off/on toggles, so this {@code @Singleton}
            // survives shutDown() with {@code shutdownCalled=true}. A
            // fresh connect() after that re-toggle should succeed, not
            // sit silently refusing forever (root cause of the "I
            // turned the plugin off and on and now no one's in the
            // lobby" QA report). Treat the connect as a soft restart:
            // clear the latch + the intentional-disconnect flag so the
            // reconnect ladder + listener callbacks behave like a
            // fresh-start manager. The shutdown() that flipped the
            // flag also closed the socket + cancelled any pending
            // reconnect, so we're starting from a clean state.
            log.debug("WebSocketManager: connect after shutdown - clearing latch for plugin restart");
            shutdownCalled.set(false);
            intentionalDisconnect = false;
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
            noteSocketEndedLocked();
        }
        intentionalDisconnect = false;
        activeUuid = uuid;
        activeName = normName;
        if (pendingReconnect != null && !pendingReconnect.isDone())
        {
            return;
        }
        cancelPendingReconnect();
        openNowOrDeferLocked();
    }

    /**
     * Tears down and re-opens the socket with the SAME {@code (uuid, name)}
     * so $connect-captured query flags (currently {@code show_rank}) are
     * re-sent. Used when the "Show your rank to others" config toggles — the
     * opt-out is snapshotted server-side at $connect (like {@code is_mod}), so
     * a live reconnect is the way to apply it promptly instead of waiting for
     * the next natural reconnect. No-op when not currently connected (the next
     * {@link #connect} will pick up the flag anyway).
     */
    public synchronized void reconnectForConfigChange()
    {
        if (activeUuid == null || activeSocket == null)
        {
            return;
        }
        intentionalDisconnect = true;
        try { activeSocket.close(CLOSE_NORMAL, "config_change"); }
        catch (Exception ignored) { /* best-effort */ }
        activeSocket = null;
        noteSocketEndedLocked();
        intentionalDisconnect = false;
        cancelPendingReconnect();
        openNowOrDeferLocked();
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
            noteSocketEndedLocked();
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
     * closed, the cmd isn't allowlisted or the cmd is held.
     *
     * <p>NOTE: this is the only way to send anything. The encode helper
     * enforces the {@link SocketProtocol#ALLOWED_OUTGOING} guard.
     */
    public boolean send(String cmd, JsonObject data)
    {
        if (isHeld(cmd, clock.getAsLong()))
        {
            log.debug("WebSocketManager: -> {} held", cmd);
            return false;
        }
        WebSocket snapshot;
        synchronized (this) { snapshot = activeSocket; }
        if (snapshot == null)
        {
            // The socket can be null transiently between disconnect
            // and the next reconnect. Surface this at DEBUG so a
            // dropped frame (e.g. lobby/join racing an open) is
            // diagnosable from logs without spamming WARN noise
            // every time the user types during a backoff.
            log.debug("WebSocketManager: -> {} dropped (no active socket)", cmd);
            return false;
        }
        final String wire;
        try
        {
            wire = SocketProtocol.encode(gson, cmd, data);
        }
        catch (IllegalArgumentException e)
        {
            log.warn("WebSocketManager: refusing to send disallowed cmd={}", cmd);
            return false;
        }
        // Outbound trace — the payload preview costs a JSON-string
        // concat, so it sits at TRACE alongside the inbound one; DEBUG
        // keeps the command name. data may be null on no-arg cmds
        // (e.g. lobby/leave), tolerate that. Truncate the JSON preview
        // so a hypothetical huge payload can't blow up the log.
        if (log.isTraceEnabled())
        {
            log.trace("WebSocketManager: -> {} {}", cmd, previewJson(data));
        }
        else
        {
            log.debug("WebSocketManager: -> {}", cmd);
        }
        return snapshot.send(wire);
    }

    /** Truncates a JSON payload to a log-safe preview. Used by the
     *  outbound + inbound debug traces — keeps the log readable when
     *  the server pushes a 50-row {@code lobby/roster} while still
     *  surfacing enough of the payload to read field names + first
     *  few values. */
    private static String previewJson(JsonObject data)
    {
        if (data == null) return "{}";
        String s = data.toString();
        return s.length() <= 800 ? s : s.substring(0, 800) + "...<+" + (s.length() - 800) + " chars>";
    }

    /** True while {@link #activeSocket} != null. */
    public synchronized boolean isConnected()
    {
        return activeSocket != null;
    }

    /** The display name the socket was last connected with; {@code null}
     *  before a connect with a name. */
    public synchronized String getActiveName()
    {
        return activeName;
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

    /**
     * Registers {@code l} to run once per opened socket, a random
     * 0–{@link #RESYNC_MAX_DELAY_MS} after the open, while that socket is
     * still the open one. Every re-sync listener runs in the same task.
     */
    public void addResyncListener(Runnable l)
    {
        if (l != null) resyncListeners.add(l);
    }

    /** Retry delay for the {@code attempt}-th consecutive reconnect:
     *  between {@link #BACKOFF_MIN_MS} and {@code min(BACKOFF_MAX_MS, 2^attempt s)}. */
    static long reconnectDelayMs(int attempt, double r)
    {
        int n = Math.max(1, attempt);
        long cap = n >= 6 ? BACKOFF_MAX_MS : Math.min(BACKOFF_MAX_MS, 1_000L << n);
        return between(BACKOFF_MIN_MS, cap, r);
    }

    static long authRefusedDelayMs(double r)
    {
        return between(AUTH_REFUSED_RETRY_MIN_MS, AUTH_REFUSED_RETRY_MAX_MS, r);
    }

    static long resyncDelayMs(double r)
    {
        return between(0L, RESYNC_MAX_DELAY_MS, r);
    }

    private static long between(long lo, long hi, double r)
    {
        if (hi <= lo) return lo;
        double c = Double.isNaN(r) ? 0.0 : Math.max(0.0, Math.min(1.0, r));
        return lo + Math.round(c * (hi - lo));
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
        // Rank-overlay opt-out (p15-membership-feed): when the user turns
        // "Show your rank to others" OFF we append show_rank=0 so the backend
        // marks this profile opted-out in OSRS-PluginUsers (drops it from the
        // membership feed). Default (opted-in) omits the param — the server
        // treats absence as opted-in. Captured at $connect like is_mod, so a
        // mid-session toggle applies on reconnect (see reconnectForConfigChange).
        if (!config.showRankToOthers())
        {
            url.append("&show_rank=0");
        }
        url.append("&v=").append(PvPLeaderboardConstants.PLUGIN_VERSION);
        Request req = new Request.Builder()
            .url(url.toString())
            .header("User-Agent", USER_AGENT)
            .build();
        log.debug("WebSocketManager: connecting uuid={}... name={} v={}",
            activeUuid.substring(0, 8), activeName == null ? "<none>" : activeName, PvPLeaderboardConstants.PLUGIN_VERSION);
        recentOpensMs.addLast(clock.getAsLong());
        while (recentOpensMs.size() > OPEN_BURST) recentOpensMs.removeFirst();
        activeSocket = pingingClient().newWebSocket(req, new Listener());
    }

    /** Opens now, or, after {@link #OPEN_BURST} opens within
     *  {@link #OPEN_BURST_WINDOW_MS}, schedules the open as a retry.
     *  Must be called under {@code synchronized (this)}. */
    private void openNowOrDeferLocked()
    {
        long now = clock.getAsLong();
        while (!recentOpensMs.isEmpty() && now - recentOpensMs.peekFirst() >= OPEN_BURST_WINDOW_MS)
        {
            recentOpensMs.removeFirst();
        }
        if (recentOpensMs.size() >= OPEN_BURST)
        {
            log.debug("WebSocketManager: open deferred ({} opens within {} ms)", recentOpensMs.size(), OPEN_BURST_WINDOW_MS);
            scheduleReconnect();
            return;
        }
        openSocketLocked();
    }

    /** Bookkeeping for the current socket going away. Must be called
     *  under {@code synchronized (this)}. */
    private void noteSocketEndedLocked()
    {
        if (openedAtMs > 0L && clock.getAsLong() - openedAtMs >= STABLE_CONNECTION_MS)
        {
            retryAttempt = 0;
        }
        openedAtMs = 0L;
    }

    private boolean isHeld(String cmd, long now)
    {
        if (now < holdAllUntilMs) return true;
        if (cmd == null) return false;
        Long until = holdCmdUntilMs.get(cmd);
        if (until == null) return false;
        if (now < until) return true;
        holdCmdUntilMs.remove(cmd, until);
        return false;
    }

    private void onRateLimited(JsonObject data)
    {
        if (data == null) return;
        long now = clock.getAsLong();
        long retryAt = com.pvp.leaderboard.util.JsonLenient.optLong(data, "retry_at_epoch_ms", 0L);
        if (retryAt <= now) return;
        long until = Math.min(retryAt, now + RATE_LIMIT_MAX_HOLD_MS);
        String cmd = com.pvp.leaderboard.util.JsonLenient.optString(data, "cmd", "");
        if (cmd.isEmpty())
        {
            holdAllUntilMs = Math.max(holdAllUntilMs, until);
        }
        else
        {
            holdCmdUntilMs.merge(cmd, until, Math::max);
        }
        log.debug("WebSocketManager: holding {} for {} ms", cmd.isEmpty() ? "every frame" : cmd, until - now);
    }

    private void scheduleResync(WebSocket opened)
    {
        if (resyncListeners.isEmpty()) return;
        long delay = resyncDelayMs(random.getAsDouble());
        scheduler.schedule(() -> runResync(opened), delay, TimeUnit.MILLISECONDS);
    }

    private void runResync(WebSocket opened)
    {
        synchronized (this)
        {
            if (activeSocket != opened) return;
        }
        for (Runnable l : resyncListeners)
        {
            try { l.run(); }
            catch (Exception e) { log.debug("WebSocketManager: resync listener threw", e); }
        }
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

    /** Schedules the next reconnect after {@link #reconnectDelayMs(int, double)}
     *  for the next attempt. */
    private synchronized void scheduleReconnect()
    {
        if (shutdownCalled.get() || intentionalDisconnect || activeUuid == null) return;
        if (pendingReconnect != null && !pendingReconnect.isDone()) return;
        retryAttempt++;
        long delay = reconnectDelayMs(retryAttempt, random.getAsDouble());
        log.debug("WebSocketManager: reconnect {} scheduled in {} ms", retryAttempt, delay);
        nextReconnectEpochMs = System.currentTimeMillis() + delay;
        pendingReconnect = scheduler.schedule(this::reconnectTick, delay, TimeUnit.MILLISECONDS);
    }

    /** Schedules the retry after an HTTP 401, after
     *  {@link #authRefusedDelayMs(double)}. The panel surfaces a
     *  reconnect banner with a countdown driven by
     *  {@link #getNextReconnectAttemptEpochMs()}. */
    private synchronized void scheduleAuthRefusedRetry()
    {
        if (shutdownCalled.get() || intentionalDisconnect || activeUuid == null) return;
        if (pendingReconnect != null && !pendingReconnect.isDone()) return;
        long delay = authRefusedDelayMs(random.getAsDouble());
        log.debug("WebSocketManager: 401 retry scheduled in {} ms", delay);
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
            boolean isCurrent;
            synchronized (WebSocketManager.this)
            {
                isCurrent = (activeSocket == webSocket);
                if (isCurrent) openedAtMs = clock.getAsLong();
                nextReconnectEpochMs = 0L;
            }
            log.debug("WebSocketManager: open");
            for (Runnable l : connectListeners)
            {
                try { l.run(); }
                catch (Exception e) { log.debug("WebSocketManager: connect listener threw", e); }
            }
            if (isCurrent) scheduleResync(webSocket);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            SocketCommand cmd = SocketProtocol.decode(gson, text);
            if (cmd == null)
            {
                log.debug("WebSocketManager: dropping unparseable frame ({} bytes)",
                    text == null ? 0 : text.length());
                return;
            }
            // Inbound trace — same pattern as the outbound trace in
            // {@link WebSocketManager#send}. The payload preview is a
            // kilobyte-scale string built on this thread, which is the
            // socket's read loop, so it sits at TRACE; DEBUG keeps the
            // command name, which is what tells you the frame arrived
            // at all. A busy lobby/roster is several KB per push every
            // few seconds.
            if (log.isTraceEnabled())
            {
                log.trace("WebSocketManager: <- {} {}", cmd.cmd, previewJson(cmd.data));
            }
            else
            {
                log.debug("WebSocketManager: <- {}", cmd.cmd);
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
            boolean isCurrent;
            synchronized (WebSocketManager.this)
            {
                // Ref-equality guard: this callback might be a delayed
                // onClosed from a *previous* socket that we already
                // tore down + replaced (e.g. the {@code name_change}
                // reconnect path in {@link #connect}). If we blindly
                // {@code activeSocket = null} we'll clobber the new,
                // freshly-opened socket — which has no other write
                // site, so the manager will believe the socket is
                // closed for the rest of its lifetime even though
                // OkHttp considers it open. {@link #send} then drops
                // every outbound frame as "no active socket" and the
                // panel's {@code lobby/join} never reaches the wire
                // (root cause of the "we can't see each other"
                // empty-roster bug surfaced in the QA logs).
                isCurrent = (activeSocket == webSocket);
                if (isCurrent)
                {
                    activeSocket = null;
                    noteSocketEndedLocked();
                }
                // Any close the plugin did not request reconnects with
                // backoff, whatever its code (the server's 2-hour close
                // is 1001). A logout or a plugin stop sets
                // intentionalDisconnect first and never reconnects.
                // Reconnect ONLY when the closed socket was the current
                // one; a stale callback from a defunct socket must not
                // schedule a spurious reconnect over the live one.
                shouldReconnect = isCurrent && !intentionalDisconnect;
            }
            log.debug("WebSocketManager: closed code={} reason={} reconnect={} stale={}",
                code, reason, shouldReconnect, !isCurrent);
            if (shouldReconnect) scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response)
        {
            int status = response == null ? -1 : response.code();
            boolean intentional;
            boolean isCurrent;
            synchronized (WebSocketManager.this)
            {
                // Same ref-equality guard as onClosed — a failure
                // notification can race a successful replacement
                // socket (e.g. when the old socket's TCP RST arrives
                // milliseconds after we swap in a name-change-reopened
                // one).
                isCurrent = (activeSocket == webSocket);
                if (isCurrent)
                {
                    activeSocket = null;
                    noteSocketEndedLocked();
                }
                intentional = intentionalDisconnect;
            }
            log.debug("WebSocketManager: failure status={} cause={} intentional={} stale={}",
                status, t == null ? "null" : t.getClass().getSimpleName(), intentional, !isCurrent);
            if (response != null) response.close();
            if (intentional) return;
            // Stale-callback guard — a failure from a defunct socket
            // (e.g. the previous name=<none> socket whose TCP RST
            // arrives after we already swapped in the name=Toyco
            // replacement) must not trigger a reconnect over the
            // live socket. Treat as a no-op; the current socket has
            // its own onOpen/onClosed lifecycle.
            if (!isCurrent) return;
            // HTTP 401 on $connect: scheduleAuthRefusedRetry; other
            // failures: scheduleReconnect. The panel renders a
            // visible countdown banner from
            // getNextReconnectAttemptEpochMs().
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
