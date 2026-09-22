package com.pvp.leaderboard.queue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

/**
 * The shared matchmaking preferences carried by a {@code queue/prefs}
 * push (WEBSOCKET_PROTOCOL.md § 6.2b) — the plugin's inbound half of
 * parity gap <b>G-2</b>. The same {@code OSRS-MatchmakingPrefs} row backs
 * the plugin's gate pickers and the Discord modal, so a wait time or rank
 * range set on either surface has to land on the other.
 *
 * <p><b>Tri-state on purpose.</b> The wire distinguishes three things and
 * so does this object: a key the server did not send states nothing and
 * must not overwrite a local pick; an explicit {@code null}
 * {@code rank_range} is the server saying "no range"; an object turns the
 * range on. {@link #waitPrefS} is {@link QueueState#UNKNOWN} when unstated
 * and {@link #rangeEnabled} is {@code null} when unstated.
 *
 * <p><b>Nothing here is required.</b> Plugin releases take days and cannot
 * be reverted (CLAUDE.md § 10), so the backend must keep serving the
 * previous client: every field is optional, wrong types degrade to
 * "unstated" and unknown fields are ignored rather than rejected. The
 * server's own keys that the plugin has no picker for ({@code region},
 * {@code style}, {@code build}, {@code location},
 * {@code auto_switch_bucket}, {@code discord_user_id}) are deliberately
 * not read here — the gate owns those locally.
 */
public final class QueuePrefs
{
    /** The shared wait preference in seconds, snapped onto
     *  {@link QueueService#WAIT_PREF_CHOICES}, or {@link QueueState#UNKNOWN}
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
        if (prefs == null) return new QueuePrefs(QueueState.UNKNOWN, null, QueueState.UNKNOWN, QueueState.UNKNOWN);

        Integer wait = JsonLenient.optInteger(prefs, "wait_pref_s");
        int waitPrefS = wait == null ? QueueState.UNKNOWN : WebSocketQueueService.normaliseWait(wait);

        Boolean rangeEnabled = null;
        int min = QueueState.UNKNOWN;
        int max = QueueState.UNKNOWN;
        JsonElement rrRaw = prefs.get("rank_range");
        if (rrRaw != null && rrRaw.isJsonNull())
        {
            // The server explicitly cleared the shared range.
            rangeEnabled = Boolean.FALSE;
        }
        else if (rrRaw != null && rrRaw.isJsonObject())
        {
            JsonObject rr = rrRaw.getAsJsonObject();
            int lo = JsonLenient.optInt(rr, "min_rank_idx", QueueState.UNKNOWN);
            int hi = JsonLenient.optInt(rr, "max_rank_idx", QueueState.UNKNOWN);
            if (lo == QueueState.UNKNOWN || hi == QueueState.UNKNOWN)
            {
                // A half-written range cannot drive a two-ended slider; treat
                // it as "no range" rather than guessing the missing bound.
                rangeEnabled = Boolean.FALSE;
            }
            else
            {
                rangeEnabled = Boolean.TRUE;
                min = Math.min(lo, hi);
                max = Math.max(lo, hi);
            }
        }
        // Any other type (array, string, number) states nothing — a future
        // server shape must never clear a user's local pick.
        return new QueuePrefs(waitPrefS, rangeEnabled, min, max);
    }

    @Override
    public String toString()
    {
        return "QueuePrefs{wait=" + waitPrefS + " range=" + rangeEnabled + " " + minRankIdx + "–" + maxRankIdx + "}";
    }
}
