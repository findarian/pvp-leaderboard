package com.pvp.leaderboard.queue;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

/**
 * Immutable snapshot of the local user's matchmaking-queue state, parsed
 * from a {@code queue/state} (or {@code queue/timeout}) push — Plan 10
 * Part B / F.1 (2026-09-21). Mirrors the backend's
 * {@code matchmaking_queue.public_state}:
 *
 * <pre>
 * state            searching | idle | timeout
 * window           ±rating window (100/200/400/800) or null = anyone in the style
 * expanded         true once the search is open to everyone
 * rank_range       {min_rank_idx, max_rank_idx} or null
 * elapsed_s / wait_pref_s / remaining_s / next_expand_in_s (null when expanded)
 * matches_last_hour / matches_today   the pinned-message counters
 * reason           idle only: "expired" | "opponent_declined" (Part E, additive)
 * style / build / region
 * </pre>
 *
 * Missing keys degrade to neutral values so a partial push never throws
 * on the socket read thread. No UUIDs — the queue is keyed on
 * {@code player_id} server-side and the plugin only ever sees names.
 */
public final class QueueState
{
    public static final int UNKNOWN = -1;

    public final String state;
    /** {@code null} = the search is open to anyone in the style. */
    public final Integer window;
    public final boolean expanded;
    public final int rankMinIdx;
    public final int rankMaxIdx;
    public final int elapsedS;
    public final int waitPrefS;
    public final int remainingS;
    /** {@code null} once expanded (no next expansion). */
    public final Integer nextExpandInS;
    public final int matchesLastHour;
    public final int matchesToday;
    public final String reason;
    public final String style;
    public final String build;
    public final String region;

    public QueueState(String state, Integer window, boolean expanded, int rankMinIdx, int rankMaxIdx,
                      int elapsedS, int waitPrefS, int remainingS, Integer nextExpandInS,
                      int matchesLastHour, int matchesToday, String reason, String style, String build, String region)
    {
        this.state = state == null ? "idle" : state;
        this.window = window;
        this.expanded = expanded;
        this.rankMinIdx = rankMinIdx;
        this.rankMaxIdx = rankMaxIdx;
        this.elapsedS = elapsedS;
        this.waitPrefS = waitPrefS;
        this.remainingS = remainingS;
        this.nextExpandInS = nextExpandInS;
        this.matchesLastHour = matchesLastHour;
        this.matchesToday = matchesToday;
        this.reason = reason;
        this.style = style;
        this.build = build;
        this.region = region;
    }

    public static QueueState fromJson(JsonObject d)
    {
        if (d == null) d = new JsonObject();
        int rmin = UNKNOWN, rmax = UNKNOWN;
        JsonObject rr = JsonLenient.optObject(d, "rank_range");
        if (rr != null)
        {
            rmin = JsonLenient.optInt(rr, "min_rank_idx", UNKNOWN);
            rmax = JsonLenient.optInt(rr, "max_rank_idx", UNKNOWN);
        }
        return new QueueState(
            JsonLenient.optString(d, "state", "idle"),
            JsonLenient.optInteger(d, "window"),
            JsonLenient.optBool(d, "expanded", false),
            rmin, rmax,
            JsonLenient.optInt(d, "elapsed_s", 0),
            JsonLenient.optInt(d, "wait_pref_s", 0),
            JsonLenient.optInt(d, "remaining_s", 0),
            JsonLenient.optInteger(d, "next_expand_in_s"),
            JsonLenient.optInt(d, "matches_last_hour", 0),
            JsonLenient.optInt(d, "matches_today", 0),
            JsonLenient.optString(d, "reason", null),
            JsonLenient.optString(d, "style", null),
            JsonLenient.optString(d, "build", null),
            JsonLenient.optString(d, "region", null));
    }

    public boolean isSearching()
    {
        return "searching".equals(state);
    }

    public boolean hasRankRange()
    {
        return rankMinIdx != UNKNOWN && rankMaxIdx != UNKNOWN;
    }

    @Override
    public String toString()
    {
        return "QueueState{" + state + " window=" + window + " expanded=" + expanded + " elapsed=" + elapsedS + "/" + waitPrefS + "}";
    }
}
