package com.pvp.leaderboard.lobby;

import com.google.gson.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.inject.*;
import javax.swing.*;

/**
 * Production {@link JoinGate} backed by
 * {@link PvpApi#getProfile}. Wires the local player's
 * {@code cumulative_stats.<bucket>} to per-{@link Style} match counts +
 * exposes an hourly auto-refresh.
 *
 * <p><b>Lifecycle.</b>
 * <ol>
 *   <li>{@link #configure} is called once from {@code
 *   PvPLeaderboardPlugin.startUp()} to wire the {@link Supplier} that
 *   resolves the local player's name at refresh time. Held as a
 *   {@link Supplier} rather than a snapshot value because the player
 *   name is unavailable until {@code GameState.LOGGED_IN} fires (the
 *   plugin's {@code startUp()} can run before login).</li>
 *   <li>{@link #onLogin} fires from {@code onGameStateChanged
 *   LOGGED_IN}; kicks off an immediate refresh and starts the hourly
 *   auto-refresh timer.</li>
 *   <li>{@link #onLogout} fires from {@code LOGIN_SCREEN}; cancels the
 *   timer and clears the count map (returning to "unknown" state).
 *   Listeners stay registered until {@link #removeListener}.</li>
 * </ol>
 */
@Singleton
public final class ProfileGate implements JoinGate
{
    private final PvpApi pvpApi;
    private final ScheduledExecutorService scheduler;

    /** Listeners notified on the EDT after every state mutation (refresh
     *  start, refresh complete, counts changed). {@link CopyOnWriteArrayList}
     *  so {@code addListener} during a fire callback doesn't trip
     *  ConcurrentModificationException. */
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Resolves at refresh time — see class javadoc for why this isn't a
     *  snapshot value. {@code volatile} since {@link #configure} writes from
     *  the plugin's startup thread and {@link #refresh} reads from the
     *  scheduler thread / EDT. */
    private volatile Supplier<String> nameSupplier;

    /** Guard against concurrent in-flight refreshes. CAS-incremented at
     *  {@link #refresh()} entry; cleared in the completion callback. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** Synchronised on {@code this} for read+write because the EnumMap
     *  isn't thread-safe and listeners may read it from the EDT while
     *  the refresh callback writes from an OkHttp dispatcher thread. */
    private final EnumMap<Style, Integer> counts = new EnumMap<>(Style.class);

    /** Consecutive soft-404 refreshes ({@code /user} returned "player
     *  not found"). Drives the geometric backoff in
     *  {@link #softBackOff()}; reset by a successful refresh
     *  and by {@link #onLogin()}.
     *
     *  <p>A soft 404 is a stable condition — an account the backend
     *  has never seen stays unknown until it plays a match — but the
     *  refresh path deliberately doesn't treat it as a success, so
     *  without this counter the gate sat on the 60 s fast-retry for
     *  the whole session. Each of those retries passes
     *  {@code forceRefresh=true}, which bypasses the profile cache,
     *  so it was an uncacheable API call every minute per client. */
    private final AtomicInteger softMisses = new AtomicInteger();

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
    public ProfileGate(PvpApi pvpApi,
                                    ScheduledExecutorService scheduler)
    {
        if (pvpApi == null) throw new IllegalArgumentException("pvpDataService is required");
        if (scheduler == null) throw new IllegalArgumentException("scheduler is required");
        this.pvpApi = pvpApi;
        this.scheduler = scheduler;
    }

    /** Wire the player-name supplier — must be called once before the
     *  first {@link #onLogin()}. Idempotent on repeat calls (just
     *  overwrites). */
    public void configure(Supplier<String> nameSupplier)
    {
        this.nameSupplier = nameSupplier;
    }

    /** Called by the plugin on {@code GameState.LOGGED_IN}. Schedules the
     *  hourly auto-refresh + kicks off an immediate first refresh.
     *  Idempotent: a second call (e.g. on character-switch) replaces the
     *  existing schedule without stacking. */
    public synchronized void onLogin()
    {
        cancelAutoRefreshLocked();
        loggedIn = true;
        // Fresh session — a new character may well be one the backend
        // knows, so don't inherit the previous account's backoff.
        softMisses.set(0);
        // Fast-retry schedule for the initial-fetch window: every
        // minute until the first refresh succeeds. The refresh()
        // success path replaces this with the {@link
        // #RECHECK_MS} hourly schedule. Without the
        // fast-retry, a failed initial fetch would leave the user
        // waiting an hour for the next attempt (and the gate stuck
        // on "Please log into the game" the whole time).
        autoRefresh = scheduler.scheduleAtFixedRate(
            this::autoTick,
            RETRY_MIN_MS,
            RETRY_MIN_MS,
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
        synchronized (counts)
        {
            counts.clear();
        }
        // Drop the mod bit — without this, a non-mod logging in on the
        // same client right after a moderator would inherit the [MOD]
        // chip on their self-preview until the first /user fetch
        // completed.
        fireOnEdt();
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
        fireOnEdt(); // refreshing=true

        Supplier<String> nameSup = nameSupplier;
        String name = nameSup != null ? nameSup.get() : null;
        if (name == null || name.trim().isEmpty())
        {
            // Pre-login refresh attempt — bail cleanly. We do NOT
            // promote the autoRefresh schedule to hourly here; the
            // fast-retry schedule from onLogin() will fire again in
            // RETRY_MIN_MS so the count auto-populates
            // as soon as the local player name resolves. (Previously
            // refresh() unconditionally promoted to hourly at entry,
            // which meant a single failed initial attempt locked the
            // user out of auto-refresh for an hour.)
            refreshing.set(false);
            fireOnEdt();
            return;
        }

        // forceRefresh=true bypasses PvpApi's 30s local TTL —
        // we want the freshest read. Server-side CloudFront cache (60s)
        // still applies, which is fine; the user can't refresh faster
        // than that and get materially newer data anyway.
        pvpApi.getProfile(name, true)
            .whenComplete((profile, ex) ->
            {
                try
                {
                    if (ex != null)
                    {
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
                        // matches). Surface zeros in the UI but do NOT
                        // treat this as a successful refresh: promoting
                        // to the hourly schedule here left new players
                        // stuck below THRESHOLD until they restarted
                        // RuneLite or clicked Refresh count manually.
                        applyAllZero();
                        softBackOff();
                        return;
                    }
                    applyProfile(profile);
                    softMisses.set(0);
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
                }
                finally
                {
                    refreshing.set(false);
                    fireOnEdt();
                }
            });
    }

    /** Widens the retry interval after repeated soft-404s, doubling
     *  each time up to the {@link #RECHECK_MS} cap.
     *
     *  <p>The first miss is left alone on the fast retry: that's the
     *  case this schedule exists for (a brand-new account whose first
     *  match is about to land), and it's worth one prompt re-check.
     *  It's the indefinite repetition that had to stop — the interval
     *  now reaches the hourly cap after ~6 misses, so an account the
     *  backend simply doesn't know costs the same as any other idle
     *  client instead of an uncacheable request every minute.
     *
     *  <p>No-op when logged out: {@link #cancelAutoRefreshLocked()}
     *  has already torn the schedule down and re-arming here would
     *  leak a task onto RuneLite's client-wide executor. */
    private synchronized void softBackOff()
    {
        int misses = softMisses.incrementAndGet();
        if (misses < 2) return;
        if (!loggedIn) return;
        long interval = RETRY_MIN_MS;
        for (int i = 1; i < misses && interval < RECHECK_MS; i++)
        {
            interval *= 2;
        }
        if (interval > RECHECK_MS) interval = RECHECK_MS;
        cancelAutoRefreshLocked();
        autoRefresh = scheduler.scheduleAtFixedRate(
            this::autoTick, interval, interval, TimeUnit.MILLISECONDS);
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
            RECHECK_MS,
            RECHECK_MS,
            TimeUnit.MILLISECONDS);
    }

    private void applyProfile(JsonObject profile)
    {
        if (profile == null)
        {
            applyAllZero();
            return;
        }
        // Parse is_mod before any cumulative_stats early-return so a
        // mod account still surfaces the [MOD] chip on the self-preview
        // row even when match counts are absent (brand-new account,
        // partial profile payload, etc.). Strict-boolean only — mirrors
        // WebSocketLobbyService.parseMember's wire contract.
        JsonObject cum = JsonLenient.optObject(profile, "cumulative_stats");
        if (cum == null)
        {
            zeroCounts();
            return;
        }
        // Per-bucket rank lives in a separate top-level object on the
        // {@code /user} endpoint — NOT inside {@code cumulative_stats}.
        // See {@code PvpApi.extractTierFromUserResponse}: the
        // per-bucket shape is {@code {"rank": "Adamant", "division":
        // 2, "mmr": ...}} where {@code rank} is the family name and
        // {@code division} is 1–3 (absent / non-1–3 for the
        // single-division "3rd Age" tier). Earlier draft of this
        // method read {@code cumulative_stats.<bucket>.tier} which
        // doesn't exist on this endpoint (only the S3 shard payload
        // has a {@code tier} field) — meaning {@link
        // #rankIdxByStyle} would stay empty and the self-preview
        // row's name rendered in plain white. Fixed by reading from
        // {@code buckets.<bucket>} instead.
        EnumMap<Style, Integer> nextCounts = new EnumMap<>(Style.class);
        for (Style s : Style.values())
        {
            JsonObject b = JsonLenient.optObject(cum, bucketFor(s));
            int total = 0;
            if (b != null)
            {
                // Defensive ints — backend has been seen to return both
                // numeric primitives and Decimal-typed values depending
                // on the path through the Lambda; getAsInt() handles
                // both via Gson's coercion.
                int wins = JsonLenient.optInt(b, "wins", 0);
                int losses = JsonLenient.optInt(b, "losses", 0);
                int ties = JsonLenient.optInt(b, "ties", 0);
                total = wins + losses + ties;
                if (total < 0) total = 0;
            }
            nextCounts.put(s, total);
        }
        synchronized (counts)
        {
            counts.clear();
            counts.putAll(nextCounts);
        }
    }

    private void applyAllZero()
    {
        zeroCounts();
    }

    /** Clears match counts + rank indices without touching {@link #isMod}.
     *  Used when {@code cumulative_stats} is absent but {@code is_mod}
     *  was still parsed from the top-level profile object. */
    private void zeroCounts()
    {
        synchronized (counts)
        {
            counts.clear();
            for (Style s : Style.values()) counts.put(s, 0);
            // Soft-404 / brand-new player: leave rankIdxByStyle empty.
            // The self-preview row falls back to "rank unknown" and
            // suppresses the rank chip rather than showing a default
            // Bronze 3.
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

    @Override
    public void addListener(Runnable listener)
    {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void removeListener(Runnable listener)
    {
        if (listener != null) listeners.remove(listener);
    }

    private void fireOnEdt()
    {
        if (listeners.isEmpty()) return;
        if (SwingUtilities.isEventDispatchThread())
        {
            fireAll();
        }
        else
        {
            SwingUtilities.invokeLater(this::fireAll);
        }
    }

    private void fireAll()
    {
        for (Runnable r : listeners)
        {
            try { r.run(); }
            catch (Exception e) { }
        }
    }
}
