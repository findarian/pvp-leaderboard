package com.pvp.leaderboard.queue;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/**
 * Immutable snapshot of the local user's matchmaking-queue state, parsed
 * from a {@code queue/state} (or {@code queue/timeout}) push — Plan 10
 * Part B / F.1 (2026-09-21). The part of the backend's
 * {@code matchmaking_queue.public_state} the plugin shows:
 *
 * <pre>
 * state            searching | idle | timeout
 * window           ±rating window (100/200/400/800) or null = anyone in the style
 * expanded         true once the search is open to everyone
 * elapsed_s / wait_pref_s
 * reason           idle only: "expired" | "opponent_declined" (Part E, additive)
 * fight_session_id idle with a reason: the fight session the server ended
 * </pre>
 *
 * The server also sends the rank range, the remaining and next-expansion
 * times, the match counters, style, build and region; nothing reads them.
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
    public final int elapsedS;
    public final int waitPrefS;
    public final String reason;
    /** The fight session an idle state's {@code reason} is about; {@code null} when the push names none. */
    public final String fightId;

    public QueueState(String state, Integer window, boolean expanded, int elapsedS, int waitPrefS, String reason,
                      String fightId)
    {
        this.state = state == null ? "idle" : state;
        this.window = window;
        this.expanded = expanded;
        this.elapsedS = elapsedS;
        this.waitPrefS = waitPrefS;
        this.reason = reason;
        this.fightId = fightId == null || fightId.isEmpty() ? null : fightId;
    }

    public static QueueState fromJson(JsonObject d)
    {
        if (d == null) d = new JsonObject();
        return new QueueState(
            optString(d, "state", "idle"),
            optInteger(d, "window"),
            optBool(d, "expanded", false),
            optInt(d, "elapsed_s", 0),
            optInt(d, "wait_pref_s", 0),
            optString(d, "reason", null),
            optString(d, "fight_session_id", null));
    }

    public boolean isSearching()
    {
        return "searching".equals(state);
    }

    /** {@code true} for an idle state whose {@code reason} says the server ended a match before both
     *  players confirmed: {@code "expired"} or {@code "opponent_declined"}. */
    public boolean endsMatch()
    {
        return "idle".equals(state) && ("expired".equals(reason) || "opponent_declined".equals(reason));
    }

    @Override
    public String toString()
    {
        return "QueueState{" + state + " window=" + window + " expanded=" + expanded + " elapsed=" + elapsedS + "/" + waitPrefS + "}";
    }
}
