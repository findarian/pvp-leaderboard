package com.pvp.leaderboard.lobby;

import java.util.Collections;
import java.util.Map;

/**
 * Pre-lobby gate that tracks the local user's per-style match count and
 * enforces the {@link #THRESHOLD}-match minimum for queueing. Drives the
 * "you need N more kills/deaths in &lt;Style&gt; to queue" UX in
 * {@code MatchmakingLobbyPanel}'s pre-lobby gate.
 *
 * <p><b>"Match count" definition.</b> The count is
 * {@code wins + losses + ties} from the player's
 * {@code cumulative_stats.<bucket>} returned by
 * {@link com.pvp.leaderboard.service.PvPDataService#getUserProfile}. We
 * use the same data the Player Lookup tab already renders so there's a
 * single source of truth (and the user can sanity-check the number by
 * looking up their own profile).
 *
 * <p><b>Refresh cadence.</b> First refresh fires when the local player
 * logs in (driven by {@code PvPLeaderboardPlugin.onGameStateChanged
 * LOGGED_IN}). Auto-refresh repeats every {@link #AUTO_REFRESH_INTERVAL_MS}
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
 * @see com.pvp.leaderboard.lobby.UserProfileLobbyJoinGate production impl
 * @see com.pvp.leaderboard.lobby.NoOpLobbyJoinGate test impl + pre-login placeholder
 */
public interface LobbyJoinGate
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
    long AUTO_REFRESH_INTERVAL_MS = 60L * 60L * 1000L;

    /** Fast-retry cadence used between {@code onLogin()} and the first
     *  successful refresh. Guards against the failure mode where the
     *  initial fetch bails (name supplier not yet ready, API transient
     *  5xx, network blip) — without a fast retry the user would be
     *  stuck on "Loading your match count…" until the next manual
     *  Refresh click or the 1-hour auto-tick, whichever comes first.
     *  After the first success the production impl switches to
     *  {@link #AUTO_REFRESH_INTERVAL_MS}. */
    long INITIAL_RETRY_INTERVAL_MS = 60L * 1000L;

    /** Snapshot of last-known match counts per {@link Style}. Keys are
     *  only present for styles the gate has fetched a count for —
     *  callers must treat a missing key as "unknown" (render placeholder
     *  text, not 0). Empty map if the gate has never successfully
     *  refreshed (still in pre-login / pre-first-fetch state). */
    Map<Style, Integer> getMatchCounts();

    /** Snapshot of last-known rank index per {@link Style}, indexed
     *  into {@link com.pvp.leaderboard.util.RankUtils#THRESHOLDS}. Used
     *  by the matchmaking panel's self-profile preview row to render
     *  the same rank the player sees on the website + Player Lookup
     *  tab. Priority for the single-rank display is NH > Veng > Multi
     *  > DMM (matches {@link Style} declaration order).
     *
     *  <p>Keys are only present for styles the gate has both a rank
     *  string and a successful tier→index lookup for; callers must
     *  treat a missing key (or value {@code < 0}) as "rank unknown"
     *  and suppress the rank chip rather than rendering "Bronze 3"
     *  (the THRESHOLDS[0] default). Default implementation returns an
     *  empty map so test/no-op gates don't have to opt in. */
    default Map<Style, Integer> getRankIdxByStyle() { return Collections.emptyMap(); }

    /** Epoch ms of the last successful refresh; {@code 0} if never
     *  refreshed. UI uses this to render "Updated N min ago" so the user
     *  knows whether their count is stale. */
    long getLastRefreshEpochMs();

    /** {@code true} while a {@link #refresh()} is in-flight. UI uses
     *  this to debounce the Refresh button (so a frustrated user
     *  hammering it doesn't queue ten parallel fetches). */
    boolean isRefreshing();

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

    /** {@code true} when the local user is a server-recognised
     *  moderator (member of the {@code OSRS-LobbyMods} table on the
     *  backend). Authoritative for the {@code MatchmakingLobbyPanel}
     *  self-preview row's [MOD] chip — the lobby roster server-filters
     *  the viewer's own row out of every push, so this is the only
     *  client-side path that surfaces the local user's own mod status.
     *
     *  <p>Source of truth: parsed off the {@code /user} profile JSON's
     *  {@code is_mod} field on every successful refresh. Default
     *  implementation returns {@code false} so the no-op gate +
     *  test/placeholder gates don't have to opt in (and so a wiring
     *  bug doesn't accidentally promote a regular user). */
    default boolean isMod() { return false; }

    /** Triggers an immediate refresh. No-op if already refreshing.
     *  Listeners fire on entry (so the UI can show "Refreshing…") and
     *  again on completion. Resets the auto-refresh clock. */
    void refresh();

    /** Subscribe to refresh events. Listener fires on the EDT every
     *  time {@link #getMatchCounts()}, {@link #getLastRefreshEpochMs()},
     *  or {@link #isRefreshing()} could have changed. Listeners are not
     *  de-duplicated — adding the same {@code Runnable} twice will fire
     *  it twice. There is no {@code removeListener}: the panel that
     *  registers is a singleton-lifetime collaborator. */
    void addListener(Runnable listener);

    /** Helper: how many more matches the user needs in {@code style}
     *  before they can queue it. Returns 0 if already unlocked, or
     *  {@link #THRESHOLD} if the count is unknown / missing. */
    static int remaining(int count)
    {
        if (count >= THRESHOLD) return 0;
        if (count < 0) return THRESHOLD;
        return THRESHOLD - count;
    }

    /** Helper: is {@code count} sufficient to queue? Counts below 0
     *  (shouldn't happen but defend) treated as unknown → locked. */
    static boolean isUnlocked(int count)
    {
        return count >= THRESHOLD;
    }
}
