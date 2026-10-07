package com.pvp.leaderboard.service.socket;

import com.google.gson.*;
import com.pvp.leaderboard.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.util.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import okhttp3.*;

/**
 * Single-connection socket lifecycle owner. Maintains exactly one
 * {@code okhttp3.WebSocket} to {@link PvpConsts#SOCKET_URL}
 * keyed by the local user's OSRS client UUID.
 *
 * <p><b>Lifecycle contract</b> (enforced by
 * {@code PvPLeaderboardPlugin.onGameStateChanged}):
 * <ul>
 *   <li>{@link #connect(String, String)} on {@code GameState.LOGGED_IN}
 *   (post-10-tick player-ready delay).</li>
 *   <li>{@link #disconnect()} on {@code GameState.LOGIN_SCREEN}
 *   (close code 1001 GOING_AWAY).</li>
 *   <li>{@link #shutdown()} on plugin {@code shutDown()}.</li>
 * </ul>
 * Re-{@link #connect(String, String)} with the same UUID and name while
 * already connected is a no-op. With a different UUID or name it tears down
 * the current connection (close 1000) and connects fresh.
 *
 * <p><b>Reconnect policy</b>: a jittered exponential backoff, see
 * {@link #retryDelayMs(int, double)}. The attempt count grows with
 * every connection that fails or closes before
 * {@link #STABLE_MS} and resets once one stays open that long.
 * Triggered by any close of the current socket that the plugin did not
 * request, whatever its code (the server's 2-hour close included), AND by
 * network-level {@code onFailure}. A logout or a plugin stop never
 * reconnects: {@link #disconnect()} forgets the UUID, and every retry needs
 * one; the closes the plugin requests itself (logout, name change, config
 * change) forget the socket first, so its callbacks are stale. A
 * {@link #connect} while a retry is scheduled leaves that retry in place,
 * and more than {@link #OPEN_BURST} opens within a minute wait for a
 * scheduled retry.
 *
 * <p>HTTP 401 retries after {@link #authDelayMs(double)}. The
 * panel surfaces a "Attempting to reconnect" banner with the countdown
 * (see {@link #getRetryAtMs()}).
 *
 * <p>{@code error/rate_limited} with {@code retry_at_epoch_ms} holds
 * outgoing frames until that time, at most
 * {@link #MAX_HOLD_MS}: the named {@code cmd}, or every frame
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
 * via push events" so a dropped-while-offline send is acceptable.
 *
 * <p><b>Threading</b>: all connection state is guarded by {@code this}
 * monitor since {@link #connect} / {@link #disconnect} / WebSocket listener
 * callbacks race. OkHttp calls the listener on its own threads (never from
 * inside {@code close()}), and every callback takes the monitor first.
 */
@Slf4j
@Singleton
public final class SocketMgr
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
    private static final Pattern UUID_V4 = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    static final long STABLE_MS = 30_000L;
    static final long RESYNC_MS = 5_000L;
    static final int OPEN_BURST = 5;
    static final long MAX_HOLD_MS = 60_000L;

    private final OkHttpClient sharedHttp;
    private final SocketBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;
    private final PvPLeaderboardConfig config;
    private final LongSupplier clock;
    private final DoubleSupplier random;

    /** Lazily-built pinging client; reuses the shared client's connection
     *  pool / dispatcher via {@code newBuilder()} so we don't double up
     *  on threads / cache. */
    private OkHttpClient pingingClient;

    /** Active connection state — all access guarded by {@code this}
     *  monitor. The OkHttp dispatcher uses its own thread pool for
     *  listener callbacks, so without the lock a {@code onClosed} →
     *  reconnect-schedule could race with a user-driven
     *  {@link #disconnect}. A socket is only ever open with a UUID. */
    private WebSocket activeSocket;
    /** The UUID to (re)connect with; {@code null} before the first connect
     *  and after {@link #disconnect()} / {@link #shutdown()}, which is what
     *  stops every scheduled retry. */
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
     *  {@link #STABLE_MS}. */
    private int retryAttempt;
    /** Clock reading at the current socket's open; {@code 0} when none is open. */
    private long openedAtMs;
    /** Clock readings of the recent opens, oldest first. */
    private final ArrayDeque<Long> openTimes = new ArrayDeque<>();
    private ScheduledFuture<?> retryJob;
    private ScheduledFuture<?> resyncJob;
    /** Epoch ms when {@link #retryJob} is scheduled to fire,
     *  or {@code 0} when no retry is queued. Exposed via
     *  {@link #getRetryAtMs()} so the lobby panel
     *  can render a countdown banner. Set on every
     *  {@link #scheduleRetry(boolean)} and cleared on
     *  {@link #retryTick()} entry / a successful
     *  {@link Listener#onOpen}. {@code volatile} since the panel's
     *  Swing-EDT ticker reads it from a different thread than the
     *  scheduler thread that writes it. */
    private volatile long retryAtMs = 0L;

    /** Listeners called every time the socket transitions from
     *  not-connected to connected (i.e. {@code onOpen} fires). Invoked on
     *  the OkHttp dispatcher thread — listeners must marshal to their
     *  target thread themselves. {@link CopyOnWriteArrayList} so
     *  addConnectListener during an open-callback doesn't trip CME. */
    private final CopyOnWriteArrayList<Runnable> connectHooks = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Runnable> resyncHooks = new CopyOnWriteArrayList<>();

    /** Clock reading until which every outgoing frame is held; {@code 0} when none is. */
    private volatile long holdAllUntil;
    /** Per-cmd hold, clock reading until which that cmd is held. */
    private final Map<String, Long> holdCmdUntil = new ConcurrentHashMap<>();

    @Inject
    public SocketMgr(OkHttpClient sharedHttp,
                            SocketBus eventBus,
                            ScheduledExecutorService scheduler,
                            Gson gson,
                            PvPLeaderboardConfig config)
    {
        this(sharedHttp, eventBus, scheduler, gson, config,
            System::currentTimeMillis, () -> ThreadLocalRandom.current().nextDouble());
    }

    SocketMgr(OkHttpClient sharedHttp,
                     SocketBus eventBus,
                     ScheduledExecutorService scheduler,
                     Gson gson,
                     PvPLeaderboardConfig config,
                     LongSupplier clock,
                     DoubleSupplier random)
    {
        this.sharedHttp = sharedHttp;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.gson = gson;
        this.config = config;
        this.clock = clock;
        this.random = random;
        if (eventBus != null) eventBus.register("error/rate_limited", this::onLimited);
    }

    /**
     * Opens (or resumes) the socket for {@code (uuid, displayName)}. A
     * second call with the same UUID and name while already connected is a
     * no-op; a call with the same UUID but a different name (e.g. user
     * switched characters on the same client) or a different UUID tears down
     * and reconnects so the server's OSRS-Connections row matches the active
     * in-game session. After a {@link #shutdown()} (RuneLite keeps this
     * {@code @Singleton} across plugin off / on) it simply opens again.
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
        if (uuid == null || !UUID_V4.matcher(uuid).matches())
        {
            log.warn("WebSocketManager: refusing connect with invalid UUID format (would trip WAF auto-ban)");
            return;
        }
        String normName = (displayName == null || displayName.trim().isEmpty())
            ? null : displayName.trim();
        if (activeSocket != null)
        {
            // Same (uuid, name) tuple already wired — no-op resume.
            if (uuid.equals(activeUuid) && Objects.equals(normName, activeName)) return;
            // Switching UUID *or* display name: close existing cleanly
            // so the server can free its OSRS-Connections row before we
            // open a new one. The server also force-closes duplicates
            // server-side (force_close_duplicate keys on player_id, and
            // a name change with the same UUID could yield a different
            // player_id — explicit teardown avoids racing the
            // server-side eviction with a fresh open).
            closeLocked(CLOSE_NORMAL, uuid.equals(activeUuid) ? "name_change" : "uuid_change");
        }
        activeUuid = uuid;
        activeName = normName;
        if (retryPending()) return;
        cancelRetry();
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
    public synchronized void reconnectNow()
    {
        if (activeSocket == null) return;
        closeLocked(CLOSE_NORMAL, "config_change");
        cancelRetry();
        openNowOrDeferLocked();
    }

    /**
     * Closes the socket with {@link #CLOSE_GOING_AWAY}. Disables
     * reconnect until the next {@link #connect(String, String)} call. Idempotent.
     */
    public synchronized void disconnect()
    {
        cancelRetry();
        if (resyncJob != null)
        {
            resyncJob.cancel(false);
            resyncJob = null;
        }
        if (activeSocket != null) closeLocked(CLOSE_GOING_AWAY, "client_logout");
        activeUuid = null;
    }

    /**
     * Final teardown — called from the plugin's {@code shutDown()}. The
     * same as {@link #disconnect()}: closes the socket and cancels any
     * scheduled retry; nothing reconnects until the next connect.
     */
    public void shutdown()
    {
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
            return false;
        }
        WebSocket snapshot;
        synchronized (this) { snapshot = activeSocket; }
        if (snapshot == null)
        {
            // The socket can be null transiently between disconnect
            // and the next reconnect. Surface this at DEBUG so a
            // dropped frame (e.g. a confirm racing an open) is
            // diagnosable from logs without spamming WARN noise
            // every time the user types during a backoff.
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
        return snapshot.send(wire);
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
    public long getRetryAtMs()
    {
        return retryAtMs;
    }

    /**
     * Registers {@code l} to be called every time the socket completes
     * an {@code $connect} handshake ({@code onOpen}). Invoked on
     * OkHttp's dispatcher thread — listeners must marshal as needed.
     *
     * <p>No unregister API: the callers are {@code @Singleton} services;
     * exposing one invites lifecycle bugs.
     */
    public void addConnectListener(Runnable l)
    {
        if (l != null) connectHooks.add(l);
    }

    /**
     * Registers {@code l} to run once per opened socket, a random
     * 0–{@link #RESYNC_MS} after the open, while that socket is
     * still the open one. Every re-sync listener runs in the same task.
     */
    public void addResyncListener(Runnable l)
    {
        if (l != null) resyncHooks.add(l);
    }

    /** Retry delay for the {@code attempt}-th consecutive reconnect:
     *  between 1 s and {@code min(60 s, 2^attempt s)} (attempts below 1
     *  count as 1). */
    static long retryDelayMs(int attempt, double r)
    {
        return between(1_000L, Math.min(60_000L, 1_000L << Math.min(Math.max(1, attempt), 6)), r);
    }

    /** Retry delay after an HTTP 401 on $connect: 30 to 90 s. */
    static long authDelayMs(double r)
    {
        return between(30_000L, 90_000L, r);
    }

    static long resyncMs(double r)
    {
        return between(0L, RESYNC_MS, r);
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
            pingingClient = sharedHttp.newBuilder()
                .pingInterval(8, TimeUnit.MINUTES)
                .build();
        }
        return pingingClient;
    }

    /** Must be called under {@code synchronized (this)}. */
    private void openLocked()
    {
        if (activeUuid == null) return;
        // The name: RFC 3986 reserved chars are stripped by the in-game name
        // validator (RuneScape names are [A-Za-z0-9 _-]) so a single
        // urlencode pass is sufficient — no double-encode risk.
        // show_rank (p15-membership-feed): when the user turns "Show your
        // rank to others" OFF we append show_rank=0 so the backend marks this
        // profile opted-out in OSRS-PluginUsers (drops it from the membership
        // feed). Default (opted-in) omits the param — the server treats
        // absence as opted-in. Captured at $connect like is_mod, so a
        // mid-session toggle applies on reconnect (see reconnectNow).
        String url = PvpConsts.SOCKET_URL + "?uuid=" + activeUuid
            + (activeName == null ? "" : "&name=" + URLEncoder.encode(activeName, StandardCharsets.UTF_8))
            + (config.showRankToOthers() ? "" : "&show_rank=0")
            + "&v=" + PvpConsts.VERSION;
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", PvpConsts.USER_AGENT)
            .build();
        openTimes.addLast(clock.getAsLong());
        while (openTimes.size() > OPEN_BURST) openTimes.removeFirst();
        activeSocket = pingingClient().newWebSocket(req, new Listener());
    }

    /** Opens now, or, after {@link #OPEN_BURST} opens within a minute,
     *  schedules the open as a retry. Must be called under
     *  {@code synchronized (this)}. */
    private void openNowOrDeferLocked()
    {
        long now = clock.getAsLong();
        while (!openTimes.isEmpty() && now - openTimes.peekFirst() >= 60_000L)
        {
            openTimes.removeFirst();
        }
        if (openTimes.size() >= OPEN_BURST)
        {
            scheduleRetry(false);
            return;
        }
        openLocked();
    }

    /** Asks the current socket to close and forgets it (its callbacks are
     *  stale from here on). Must be called under {@code synchronized (this)}
     *  with a socket open. */
    private void closeLocked(int code, String reason)
    {
        try { activeSocket.close(code, reason); }
        catch (Exception ignored) { /* best-effort */ }
        endLocked(activeSocket);
    }

    /** Bookkeeping for {@code ws} going away: when it is the current socket,
     *  forgets it and resets the backoff if it stayed open
     *  {@link #STABLE_MS}. Returns whether it was current — a
     *  callback from a socket the plugin already replaced or closed is stale.
     *  Must be called under {@code synchronized (this)}. */
    private boolean endLocked(WebSocket ws)
    {
        if (activeSocket != ws) return false;
        activeSocket = null;
        if (openedAtMs > 0L && clock.getAsLong() - openedAtMs >= STABLE_MS)
        {
            retryAttempt = 0;
        }
        openedAtMs = 0L;
        return true;
    }

    /** Runs every listener, each isolated from the others' exceptions. */
    private void runAll(List<Runnable> listeners)
    {
        for (Runnable l : listeners)
        {
            try { l.run(); }
            catch (Exception e)
            {
            }
        }
    }

    private boolean isHeld(String cmd, long now)
    {
        if (now < holdAllUntil) return true;
        if (cmd == null) return false;
        Long until = holdCmdUntil.get(cmd);
        if (until == null) return false;
        if (now < until) return true;
        holdCmdUntil.remove(cmd, until);
        return false;
    }

    private void onLimited(JsonObject data)
    {
        if (data == null) return;
        long now = clock.getAsLong();
        long retryAt = JsonLenient.optLong(data, "retry_at_epoch_ms", 0L);
        if (retryAt <= now) return;
        long until = Math.min(retryAt, now + MAX_HOLD_MS);
        String cmd = JsonLenient.optString(data, "cmd");
        if (cmd.isEmpty())
        {
            holdAllUntil = Math.max(holdAllUntil, until);
        }
        else
        {
            holdCmdUntil.merge(cmd, until, Math::max);
        }
    }

    private void scheduleResync(WebSocket opened)
    {
        if (resyncHooks.isEmpty()) return;
        long delay = resyncMs(random.getAsDouble());
        synchronized (this)
        {
            resyncJob = scheduler.schedule(() -> runResync(opened), delay, TimeUnit.MILLISECONDS);
        }
    }

    private void runResync(WebSocket opened)
    {
        synchronized (this)
        {
            if (activeSocket != opened) return;
        }
        runAll(resyncHooks);
    }

    private synchronized void cancelRetry()
    {
        if (retryJob != null)
        {
            retryJob.cancel(false);
            retryJob = null;
        }
        retryAtMs = 0L;
    }

    /** A scheduled retry that has not run yet. Must be called under
     *  {@code synchronized (this)}. */
    private boolean retryPending()
    {
        return retryJob != null && !retryJob.isDone();
    }

    /** Schedules the next open: after an HTTP 401, after
     *  {@link #authDelayMs(double)}; otherwise after
     *  {@link #retryDelayMs(int, double)} for the next attempt. Nothing
     *  without a UUID or while a retry is already scheduled. The panel
     *  surfaces a reconnect banner with a countdown driven by
     *  {@link #getRetryAtMs()}. */
    private synchronized void scheduleRetry(boolean authRefused)
    {
        if (activeUuid == null || retryPending()) return;
        long delay = authRefused ? authDelayMs(random.getAsDouble()) : retryDelayMs(++retryAttempt, random.getAsDouble());
        retryAtMs = System.currentTimeMillis() + delay;
        retryJob = scheduler.schedule(this::retryTick, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void retryTick()
    {
        retryJob = null;
        retryAtMs = 0L;
        if (activeUuid == null || activeSocket != null) return;
        openLocked();
    }

    /** WebSocket listener — runs on OkHttp's dispatcher thread. */
    private final class Listener extends WebSocketListener
    {
        @Override
        public void onOpen(WebSocket webSocket, Response response)
        {
            boolean isCurrent;
            synchronized (SocketMgr.this)
            {
                isCurrent = (activeSocket == webSocket);
                if (isCurrent) openedAtMs = clock.getAsLong();
                retryAtMs = 0L;
            }
            runAll(connectHooks);
            if (isCurrent) scheduleResync(webSocket);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            SocketCommand cmd = SocketProtocol.decode(gson, text);
            if (cmd == null)
            {
                return;
            }
            eventBus.fire(cmd.cmd, cmd.data);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason)
        {
            // Acknowledge server-initiated close cleanly.
            webSocket.close(code, reason);
        }

        /** Any close of the current socket reconnects with backoff, whatever
         *  its code (the server's 2-hour close is 1001). A callback from a
         *  socket the plugin already replaced (the {@code name_change}
         *  reconnect in {@link #connect}) or closed (logout, plugin stop) is
         *  stale: it must neither clobber the live socket — {@link #send} would
         *  then drop every frame as "no active socket" — nor schedule a
         *  spurious reconnect. */
        @Override
        public void onClosed(WebSocket webSocket, int code, String reason)
        {
            boolean isCurrent;
            synchronized (SocketMgr.this) { isCurrent = endLocked(webSocket); }
            if (isCurrent) scheduleRetry(false);
        }

        /** Same stale-callback guard as {@link #onClosed} (e.g. the previous
         *  socket's TCP RST arriving after the name-change replacement). An
         *  HTTP 401 on $connect retries after the auth-refused delay, any
         *  other failure on the backoff. */
        @Override
        public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response)
        {
            int status = response == null ? -1 : response.code();
            boolean isCurrent;
            synchronized (SocketMgr.this) { isCurrent = endLocked(webSocket); }
            if (response != null) response.close();
            if (isCurrent) scheduleRetry(status == 401);
        }
    }
}
