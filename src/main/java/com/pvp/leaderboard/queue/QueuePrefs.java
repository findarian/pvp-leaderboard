package com.pvp.leaderboard.queue;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;

/**
 * The shared matchmaking preferences carried by a {@code queue/prefs}
 * push. The plugin's gate pickers and the Discord modal share them, so a
 * wait time or rank range set on either surface lands on the other.
 *
 * <p><b>Tri-state.</b> The wire distinguishes three things and so does
 * this object: a key the push did not send states nothing and must not
 * overwrite a local pick; an explicit {@code null} {@code rank_range}
 * means "no range"; an object turns the range on. {@link #waitPrefS} is
 * {@link QueueState#UNKNOWN} when unstated and {@link #rangeEnabled} is
 * {@code null} when unstated.
 *
 * <p>Every field is optional: wrong types degrade to "unstated" and
 * unknown fields are ignored. Keys the plugin has no picker for
 * ({@code region}, {@code style}, {@code build}, {@code location},
 * {@code auto_switch_bucket}, {@code discord_user_id}) are not read
 * here — the gate owns those locally.
 */
public final class QueuePrefs
{
    /** The shared wait preference in seconds, snapped onto
     *  {@link QueueService#WAIT_CHOICES}, or {@link QueueState#UNKNOWN}
     *  when the push stated none. */
    public final int waitPrefS;

    /** {@code TRUE} = the server carries a range, {@code FALSE} = the server
     *  cleared it, {@code null} = the push stated nothing. */
    public final Boolean rangeEnabled;

    /** Ordered bounds, both {@link QueueState#UNKNOWN} unless
     *  {@link #rangeEnabled} is {@code TRUE}. */
    public final int minRankIdx;
    public final int maxRankIdx;

    public QueuePrefs(int waitPrefS, Boolean rangeEnabled, int minRankIdx, int maxRankIdx)
    {
        this.waitPrefS = waitPrefS;
        this.rangeEnabled = rangeEnabled;
        this.minRankIdx = minRankIdx;
        this.maxRankIdx = maxRankIdx;
    }

    /** {@code true} when the push named a wait preference. */
    public boolean hasWaitPref()
    {
        return waitPrefS != QueueState.UNKNOWN;
    }

    /** Parses the {@code prefs} object of a {@code queue/prefs} push (or of
     *  an additive {@code prefs} on {@code queue/state}). Never throws;
     *  {@code null} / empty states nothing. */
    public static QueuePrefs fromJson(JsonObject prefs)
    {
        Integer wait = JsonLenient.optInteger(prefs, "wait_pref_s");
        Boolean rangeEnabled = null;
        int min = QueueState.UNKNOWN;
        int max = QueueState.UNKNOWN;
        JsonElement rrRaw = prefs == null ? null : prefs.get("rank_range");
        if (rrRaw != null && rrRaw.isJsonNull())
        {
            // The server explicitly cleared the shared range.
            rangeEnabled = false;
        }
        else if (rrRaw != null && rrRaw.isJsonObject())
        {
            JsonObject rr = rrRaw.getAsJsonObject();
            int lo = JsonLenient.optInt(rr, "min_rank_idx", QueueState.UNKNOWN);
            int hi = JsonLenient.optInt(rr, "max_rank_idx", QueueState.UNKNOWN);
            // A half-written range cannot drive a two-ended slider; treat
            // it as "no range" rather than guessing the missing bound.
            rangeEnabled = lo != QueueState.UNKNOWN && hi != QueueState.UNKNOWN;
            if (rangeEnabled)
            {
                min = Math.min(lo, hi);
                max = Math.max(lo, hi);
            }
        }
        // Any other type (array, string, number) states nothing — a future
        // server shape must never clear a user's local pick.
        return new QueuePrefs(wait == null ? QueueState.UNKNOWN : WebSocketQueueService.snapWait(wait), rangeEnabled, min, max);
    }

    @Override
    public String toString()
    {
        return "QueuePrefs{wait=" + waitPrefS + " range=" + rangeEnabled + " " + minRankIdx + "–" + maxRankIdx + "}";
    }
}
