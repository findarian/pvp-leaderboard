package com.pvp.leaderboard.queue;

import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.Style;

/**
 * The seam between the matchmaking panel and the queue transport (Plan
 * 10 Part B / F.1). Fire-and-forget: outcomes arrive through the
 * registered {@link QueueEventListener}. The production implementation is
 * {@link WebSocketQueueService}; {@link NoOpQueueService} is the inert
 * fallback (the gate then hides the queue button).
 */
public interface QueueService
{
    /** Wait-time choices (seconds) shared with Discord — AS-64. */
    int[] WAIT_PREF_CHOICES = {30, 300, 600, 1800};

    void setListener(QueueEventListener listener);

    void start();

    /** {@code queue/join}: one style, one build, the user's region, an
     *  optional rank range (pass {@link QueueState#UNKNOWN} for both to
     *  omit) and the wait preference in seconds. */
    void join(String region, Style style, BuildType build, int minRankIdx, int maxRankIdx, int waitPrefS);

    /** {@code queue/leave}. */
    void leave();

    /** {@code queue/expand_range}: search everyone in the style at once. */
    void expandRange();

    /** {@code queue/status}: re-sync (panel opened / reconnect). */
    void requestStatus();

    // ---- shared preferences (parity gap G-2) -------------------------
    // The wait time and the rank range live in ONE server-side row shared
    // with the Discord queue, so the plugin writes it on a local pick and
    // re-reads it when the socket comes up. Each is one job; the defaults
    // are no-ops so the inert service needs no change.

    /** {@code queue/set_prefs} with an <b>empty</b> {@code prefs} object:
     *  merges nothing server-side and answers {@code queue/prefs} with the
     *  stored row — the read-through that brings a Discord-side change to
     *  the plugin's pickers. */
    default void requestPrefs() { }

    /** {@code queue/set_prefs} with only {@code wait_pref_s}, so the shared
     *  rank range is left untouched. */
    default void sendWaitPref(int waitPrefS) { }

    /** {@code queue/set_prefs} with only {@code rank_range}; pass
     *  {@link QueueState#UNKNOWN} for both bounds to clear it. */
    default void sendRankRange(int minRankIdx, int maxRankIdx) { }

    /** {@code true} when this is a real transport (the gate shows the
     *  queue controls); the no-op service answers {@code false}. */
    default boolean isAvailable() { return true; }
}
