package com.pvp.leaderboard.lobby;

import java.util.*;

/**
 * Pre-lobby gate that tracks the local user's per-style match count and
 * enforces the {@link #THRESHOLD}-match minimum for queueing. Drives the
 * "you need N more kills/deaths in &lt;Style&gt; to queue" UX in
 * {@code MatchmakingLobbyPanel}'s pre-lobby gate.
 *
 * <p><b>"Match count" definition.</b> The count is
 * {@code wins + losses + ties} from the player's
 * {@code cumulative_stats.<bucket>} returned by
 * {@link com.pvp.leaderboard.service.PvpApi#getProfile}. We
 * use the same data the Player Lookup tab already renders so there's a
 * single source of truth (and the user can sanity-check the number by
 * looking up their own profile).
 *
 * <p><b>Refresh cadence.</b> First refresh fires when the local player
 * logs in (driven by {@code PvPLeaderboardPlugin.onGameStateChanged
 * LOGGED_IN}). Auto-refresh repeats every {@link #RECHECK_MS}
 * (1 hour). Users can also force a manual refresh via {@link #refresh()};
 * a manual refresh resets the auto-refresh clock to avoid double-fires.
 *
 * <p><b>Trust boundary.</b> This gate is UX-only — it gives the user
 * immediate feedback without having to attempt a {@code lobby/join} the
 * server would reject. The server enforces the same rule at
 * {@code lobby/join} time; a modified plugin that bypasses this gate
 * still trips the server-side check and receives an
 * {@code error/lobby SMURF_GUARD} response.
 *
 * <p><b>Threading.</b> Listeners fire on the EDT; getters are safe to
 * call from any thread but their return values are point-in-time.
 *
 * @see com.pvp.leaderboard.lobby.ProfileGate production impl
 * @see com.pvp.leaderboard.lobby.NoOpGate test impl + pre-login placeholder
 */
public interface JoinGate
{
    /** Per-style minimum {@code wins + losses + ties} needed to queue.
     *  Mirror this exactly on the backend's {@code SMURF_GUARD} check —
     *  any drift between client and server would surface as the user
     *  passing the local gate but getting {@code SMURF_GUARD} back from
     *  {@code lobby/join} (or vice versa, which is worse — UI says
     *  "locked" but the server would have let them in). */
    int THRESHOLD = 20;

    /** Auto-refresh cadence — once per hour. Manual {@link #refresh()}
     *  resets this clock so a user who refreshes right before the hourly
     *  tick doesn't see a redundant fetch a moment later. */
    long RECHECK_MS = 60L * 60L * 1000L;

    /** Fast-retry cadence used between {@code onLogin()} and the first
     *  successful refresh. Guards against the failure mode where the
     *  initial fetch bails (name supplier not yet ready, API transient
     *  5xx, network blip) — without a fast retry the user would be
     *  stuck on "Loading your match count…" until the next manual
     *  Refresh click or the 1-hour auto-tick, whichever comes first.
     *  After the first success the production impl switches to
     *  {@link #RECHECK_MS}. */
    long RETRY_MIN_MS = 60L * 1000L;

    /** Snapshot of last-known match counts per {@link Style}. Keys are
     *  only present for styles the gate has fetched a count for —
     *  callers must treat a missing key as "unknown" (render placeholder
     *  text, not 0). Empty map if the gate has never successfully
     *  refreshed (still in pre-login / pre-first-fetch state). */
    Map<Style, Integer> getMatchCounts();

    /** {@code true} between {@code onLogin()} and {@code onLogout()} on
     *  the production impl — i.e. while the local OSRS player is in
     *  {@code GameState.LOGGED_IN} and the gate has a player-name to
     *  query against. UI uses this to swap the entire pre-lobby gate
     *  for a "Please log into the game" notice when {@code false} so
     *  the user isn't confronted with style toggles that look broken
     *  / locked for reasons they can't fix from outside the game.
     *
     *  <p>Note this is the <b>game</b> login state, not the website's
     *  Cognito session. A user authenticated to the website but parked
     *  on the OSRS login screen still reports {@code false} here. */
    boolean isLoggedIn();

    /** Triggers an immediate refresh. No-op if already refreshing.
     *  Listeners fire on entry (so the UI can show "Refreshing…") and
     *  again on completion. Resets the auto-refresh clock. */
    void refresh();

    /** Subscribe to refresh events. Listener fires on the EDT every
     *  time {@link #getMatchCounts()}, {@link #getLastRefreshEpochMs()},
     *  or {@link #isRefreshing()} could have changed. Listeners are not
     *  de-duplicated — adding the same {@code Runnable} twice will fire
     *  it twice. {@link #removeListener} unregisters one. */
    void addListener(Runnable listener);

    /** Unregisters one registration of {@code listener}; unknown or
     *  {@code null} is a no-op. */
    default void removeListener(Runnable listener) { }
}
