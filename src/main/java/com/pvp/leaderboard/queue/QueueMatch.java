package com.pvp.leaderboard.queue;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

/**
 * The {@code queue/matched} push (Plan 10 Part B): the pair is found and
 * the lobby's own {@code lobby/fight_proposed} follows with the same
 * {@code fight_session_id}, so the existing Confirm / Fight-ready views
 * take over. This object only feeds the "Match found!" transition and
 * the popup caption. Names only — no UUIDs on the socket path.
 */
public final class QueueMatch
{
    public final String fightSessionId;
    public final String opponentName;
    public final String opponentPlayerId;
    public final String opponentRegion;
    public final String opponentBuild;
    /** Opponent's rating in the queued style, or {@code NaN} when absent. */
    public final double opponentMmrMu;
    public final String style;
    public final String build;
    public final String location;
    public final long expiresAtEpochMs;

    public QueueMatch(String fightSessionId, String opponentName, String opponentPlayerId, String opponentRegion,
                      String opponentBuild, double opponentMmrMu, String style, String build, String location, long expiresAtEpochMs)
    {
        this.fightSessionId = fightSessionId;
        this.opponentName = opponentName;
        this.opponentPlayerId = opponentPlayerId;
        this.opponentRegion = opponentRegion;
        this.opponentBuild = opponentBuild;
        this.opponentMmrMu = opponentMmrMu;
        this.style = style;
        this.build = build;
        this.location = location;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }

    /** {@code null} when the push carries no {@code fight_session_id}. */
    public static QueueMatch fromJson(JsonObject d)
    {
        if (d == null) return null;
        String sid = JsonLenient.optString(d, "fight_session_id", "");
        if (sid.isEmpty()) return null;
        return new QueueMatch(
            sid,
            JsonLenient.optString(d, "opponent_name", JsonLenient.optString(d, "opponent_player_id", "your opponent")),
            JsonLenient.optString(d, "opponent_player_id", null),
            JsonLenient.optString(d, "opponent_region", null),
            JsonLenient.optString(d, "opponent_build", null),
            JsonLenient.optDouble(d, "opponent_mmr_mu", Double.NaN),
            JsonLenient.optString(d, "style", null),
            JsonLenient.optString(d, "build", null),
            JsonLenient.optString(d, "location", null),
            JsonLenient.optLong(d, "expires_at_epoch_ms", 0L));
    }
}
