package com.pvp.leaderboard.lobby;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.service.PvPDataService;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Production {@link LobbyJoinGate} backed by
 * {@link PvPDataService#getUserProfile}. Wires the local player's
 * {@code cumulative_stats.<bucket>} to per-{@link Style} match counts +
 * exposes an hourly auto-refresh.
 *
 * <p><b>Lifecycle.</b>
 * <ol>
 *   <li>{@link #configure} is called once from {@code
 *   PvPLeaderboardPlugin.startUp()} to wire the {@link Supplier}s that
 *   resolve the local player's name + UUID at refresh time. Held as
 *   {@link Supplier}s rather than snapshot values because the player
 *   name is unavailable until {@code GameState.LOGGED_IN} fires (the
 *   plugin's {@code startUp()} can run before login).</li>
 *   <li>{@link #onLogin} fires from {@code onGameStateChanged
 *   LOGGED_IN}; kicks off an immediate refresh and starts the hourly
 *   auto-refresh timer.</li>
 *   <li>{@link #onLogout} fires from {@code LOGIN_SCREEN}; cancels the
 *   timer and clears the count map (returning to "unknown" state). The
 *   gate doesn't tear down listeners — the panel is registered for the
 *   lifetime of the dashboard.</li>
 * </ol>
 *
 * <p><b>Why no listener removal?</b> The only known caller is
 * {@code MatchmakingLobbyPanel}, which is a singleton-lifetime
 * collaborator; adding {@code removeListener} just invites lifecycle
 * bugs where the panel forgets to clean up. Mirror of the same decision
 * in {@code WebSocketManager.addConnectListener}.
 */
@Slf4j
@Singleton
public final class UserProfileLobbyJoinGate implements LobbyJoinGate
{
    private final PvPDataService pvpDataService;
    private final ScheduledExecutorService scheduler;

    /** Listeners notified on the EDT after every state mutation (refresh
     *  start, refresh complete, counts changed). {@link CopyOnWriteArrayList}
     *  so {@code addListener} during a fire callback doesn't trip
     *  ConcurrentModificationException. */
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Suppliers resolve at refresh time — see class javadoc for why
     *  these aren't snapshot values. {@code volatile} since
     *  {@link #configure} writes from the plugin's startup thread and
     *  {@link #refresh} reads from the scheduler thread / EDT. */
    private volatile Supplier<String> nameSupplier;
    private volatile Supplier<String> uuidSupplier;

    /** Guard against concurrent in-flight refreshes. CAS-incremented at
     *  {@link #refresh()} entry; cleared in the completion callback. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** Synchronised on {@code this} for read+write because the EnumMap
     *  isn't thread-safe and listeners may read it from the EDT while
     *  the refresh callback writes from an OkHttp dispatcher thread. */
    private final EnumMap<Style, Integer> counts = new EnumMap<>(Style.class);
    private volatile long lastRefreshEpochMs;

    /** Active auto-refresh handle; null when {@link #onLogout()} or no
     *  login has occurred. */
    private ScheduledFuture<?> autoRefresh;

    /** {@code true} between {@link #onLogin()} and {@link #onLogout()}.
     *  Independent of whether a refresh has completed — flips
     *  synchronously when the plugin reports the game-state change so
     *  the panel can swap its "Please log into the game" notice without
     *  waiting for the first network roundtrip. {@code volatile} since
     *  reads come from the EDT and writes from the plugin's game-event
     *  thread. */
    private volatile boolean loggedIn;

    @Inject
    public UserProfileLobbyJoinGate(PvPDataService pvpDataService,
                                    ScheduledExecutorService scheduler)
    {
        if (pvpDataService == null) throw new IllegalArgumentException("pvpDataService is required");
        if (scheduler == null) throw new IllegalArgumentException("scheduler is required");
        this.pvpDataService = pvpDataService;
        this.scheduler = scheduler;
    }

    /** Wire the identity suppliers — must be called once before the
     *  first {@link #onLogin()}. Idempotent on repeat calls (just
     *  overwrites). */
    public void configure(Supplier<String> nameSupplier,
                          Supplier<String> uuidSupplier)
    {
        this.nameSupplier = nameSupplier;
        this.uuidSupplier = uuidSupplier;
    }

    /** Called by the plugin on {@code GameState.LOGGED_IN}. Schedules the
     *  hourly auto-refresh + kicks off an immediate first refresh.
     *  Idempotent: a second call (e.g. on character-switch) replaces the
     *  existing schedule without stacking. */
    public synchronized void onLogin()
    {
        cancelAutoRefreshLocked();
        loggedIn = true;
        // Fast-retry schedule for the initial-fetch window: every
        // minute until the first refresh succeeds. The refresh()
        // success path replaces this with the {@link
        // #AUTO_REFRESH_INTERVAL_MS} hourly schedule. Used to be
        // initialDelay=1h period=1h — meaning a failed initial fetch
        // left the user waiting an hour for the next attempt. See
        // 2026-05-18 fix in PLUGIN_PROGRESS.md for the failure modes
        // this catches.
        autoRefresh = scheduler.scheduleAtFixedRate(
            this::autoTick,
            INITIAL_RETRY_INTERVAL_MS,
            INITIAL_RETRY_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
        // Initial fetch — without this the panel would have no counts to
        // show until the first fast-retry tick. refresh() fires
        // listeners on entry, which is what swaps the panel from
        // "Please log into the game" → the real gate UI.
        refresh();
    }

    /** Called by the plugin on {@code GameState.LOGIN_SCREEN}. Cancels
     *  the auto-refresh + clears cached counts so the next user logging
     *  in on the same client doesn't see the previous user's counts. */
    public synchronized void onLogout()
    {
        cancelAutoRefreshLocked();
        loggedIn = false;
        synchronized (counts) { counts.clear(); }
        lastRefreshEpochMs = 0L;
        fireListenersOnEdt();
    }

    private void cancelAutoRefreshLocked()
    {
        if (autoRefresh != null)
        {
            autoRefresh.cancel(false);
            autoRefresh = null;
        }
    }

    /** Auto-refresh tick handler. Skips if there's already a refresh in
     *  flight (manual refresh racing with the scheduler) to keep the
     *  network footprint to "at most one in-flight at a time". */
    private void autoTick()
    {
        if (refreshing.get()) return;
        refresh();
    }

    @Override
    public Map<Style, Integer> getMatchCounts()
    {
        synchronized (counts)
        {
            return Collections.unmodifiableMap(new EnumMap<>(counts));
        }
    }

    @Override
    public long getLastRefreshEpochMs() { return lastRefreshEpochMs; }

    @Override
    public boolean isRefreshing() { return refreshing.get(); }

    @Override
    public boolean isLoggedIn() { return loggedIn; }

    @Override
    public void refresh()
    {
        if (!refreshing.compareAndSet(false, true))
        {
            // Already in flight — debounce. The in-flight call will fire
            // listeners on its own completion path.
            return;
        }
        fireListenersOnEdt(); // refreshing=true

        Supplier<String> nameSup = this.nameSupplier;
        Supplier<String> uuidSup = this.uuidSupplier;
        String name = nameSup != null ? nameSup.get() : null;
        String uuid = uuidSup != null ? uuidSup.get() : null;
        if (name == null || name.trim().isEmpty())
        {
            // Pre-login refresh attempt — bail cleanly. We do NOT
            // promote the autoRefresh schedule to hourly here; the
            // fast-retry schedule from onLogin() will fire again in
            // INITIAL_RETRY_INTERVAL_MS so the count auto-populates
            // as soon as the local player name resolves. (Previously
            // refresh() unconditionally promoted to hourly at entry,
            // which meant a single failed initial attempt locked the
            // user out of auto-refresh for an hour.)
            refreshing.set(false);
            fireListenersOnEdt();
            return;
        }

        // forceRefresh=true bypasses PvPDataService's 30s local TTL —
        // we want the freshest read. Server-side CloudFront cache (60s)
        // still applies, which is fine; the user can't refresh faster
        // than that and get materially newer data anyway.
        pvpDataService.getUserProfile(name, uuid, true)
            .whenComplete((profile, ex) ->
            {
                try
                {
                    if (ex != null)
                    {
                        log.debug("LobbyJoinGate refresh failed for {}", name, ex);
                        // Don't clear counts — stale counts are better
                        // than empty ones (user already saw them; this
                        // would just flicker them to "Unknown" then
                        // back). Just don't update lastRefreshEpochMs
                        // and don't promote the schedule — the
                        // fast-retry tick will try again in 1 min.
                        return;
                    }
                    if (profile == null)
                    {
                        // Soft 404 from the API — player profile doesn't
                        // exist yet (e.g. brand-new account with zero
                        // matches). Treat as zero in every bucket.
                        applyAllZero();
                    }
                    else
                    {
                        applyProfile(profile);
                    }
                    lastRefreshEpochMs = System.currentTimeMillis();
                    // First-success / manual-refresh promote: switch to
                    // the hourly cadence and reset the clock so the
                    // next auto-tick is 1h from now (not 1h from the
                    // previous tick). Was at refresh() entry — that
                    // version unconditionally promoted even on failure
                    // paths, which broke the initial-fetch retry loop.
                    promoteToHourlyScheduleLocked();
                }
                catch (Exception e)
                {
                    log.debug("LobbyJoinGate refresh: callback threw", e);
                }
                finally
                {
                    refreshing.set(false);
                    fireListenersOnEdt();
                }
            });
    }

    /** Replace the active {@link #autoRefresh} with the long-period
     *  hourly schedule. Called only from the success path of
     *  {@link #refresh()} so first-success transitions us off the
     *  fast-retry cadence and manual refreshes reset the hourly clock
     *  in one place. No-op if logged out (cancelAutoRefreshLocked has
     *  already nulled out the schedule). */
    private synchronized void promoteToHourlyScheduleLocked()
    {
        if (!loggedIn) return;
        cancelAutoRefreshLocked();
        autoRefresh = scheduler.scheduleAtFixedRate(
            this::autoTick,
            AUTO_REFRESH_INTERVAL_MS,
            AUTO_REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
    }

    private void applyProfile(JsonObject profile)
    {
        if (profile == null || !profile.has("cumulative_stats") ||
            !profile.get("cumulative_stats").isJsonObject())
        {
            applyAllZero();
            return;
        }
        JsonObject cum = profile.getAsJsonObject("cumulative_stats");
        EnumMap<Style, Integer> next = new EnumMap<>(Style.class);
        for (Style s : Style.values())
        {
            String bucket = bucketFor(s);
            int total = 0;
            if (bucket != null && cum.has(bucket) && cum.get(bucket).isJsonObject())
            {
                JsonObject b = cum.getAsJsonObject(bucket);
                // Defensive ints — backend has been seen to return both
                // numeric primitives and Decimal-typed values depending
                // on the path through the Lambda; getAsInt() handles
                // both via Gson's coercion.
                int wins = optInt(b, "wins");
                int losses = optInt(b, "losses");
                int ties = optInt(b, "ties");
                total = wins + losses + ties;
                if (total < 0) total = 0;
            }
            next.put(s, total);
        }
        synchronized (counts)
        {
            counts.clear();
            counts.putAll(next);
        }
    }

    private void applyAllZero()
    {
        synchronized (counts)
        {
            counts.clear();
            for (Style s : Style.values()) counts.put(s, 0);
        }
    }

    private static String bucketFor(Style s)
    {
        // Lowercased style name → cumulative_stats bucket key. Locked
        // to a switch instead of {@code s.name().toLowerCase()} so
        // future enum renames (e.g. NH → "Nh") don't silently break the
        // wire mapping.
        switch (s)
        {
            case NH: return "nh";
            case VENG: return "veng";
            case MULTI: return "multi";
            case DMM: return "dmm";
            default: return null;
        }
    }

    private static int optInt(JsonObject o, String key)
    {
        try
        {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
        }
        catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e)
        {
            return 0;
        }
    }

    @Override
    public void addListener(Runnable listener)
    {
        if (listener != null) listeners.add(listener);
    }

    private void fireListenersOnEdt()
    {
        if (listeners.isEmpty()) return;
        if (SwingUtilities.isEventDispatchThread())
        {
            fireListeners();
        }
        else
        {
            SwingUtilities.invokeLater(this::fireListeners);
        }
    }

    private void fireListeners()
    {
        for (Runnable r : listeners)
        {
            try { r.run(); }
            catch (Exception e) { log.debug("LobbyJoinGate listener threw", e); }
        }
    }
}
