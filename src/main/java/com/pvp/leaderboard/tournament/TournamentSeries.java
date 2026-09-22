package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

/**
 * The local player's series in the current round (Plan 10 Part C):
 * {@code tournament/match_assigned} and {@code tournament/state.active.series}
 * both parse into this. The opponent is identified by name / acct_sha /
 * player_id only.
 */
public final class TournamentSeries
{
    public final String tournamentId;
    public final String seriesId;
    public final int round;
    public final int bestOf;
    /** open | decided | dnf | void | bye. */
    public final String status;
    public final String opponentName;
    public final String opponentAcctSha;
    public final String opponentPlayerId;
    public final String world;
    public final String meetingPlace;
    public final int gamesRecognised;
    public final String winnerAcctSha;
    /** Round deadline (epoch seconds), 0 when unknown. */
    public final long deadlineAt;
    public final String category;
    public final String style;

    public TournamentSeries(String tournamentId, String seriesId, int round, int bestOf, String status, String opponentName,
                            String opponentAcctSha, String opponentPlayerId, String world, String meetingPlace, int gamesRecognised,
                            String winnerAcctSha, long deadlineAt, String category, String style)
    {
        this.tournamentId = tournamentId;
        this.seriesId = seriesId;
        this.round = round;
        this.bestOf = bestOf;
        this.status = status;
        this.opponentName = opponentName;
        this.opponentAcctSha = opponentAcctSha;
        this.opponentPlayerId = opponentPlayerId;
        this.world = world;
        this.meetingPlace = meetingPlace;
        this.gamesRecognised = gamesRecognised;
        this.winnerAcctSha = winnerAcctSha;
        this.deadlineAt = deadlineAt;
        this.category = category;
        this.style = style;
    }

    /** {@code null} without a {@code series_id}. {@code tournamentId} /
     *  {@code deadlineAt} come from the object itself when present
     *  ({@code match_assigned}) or from the enclosing state otherwise. */
    public static TournamentSeries fromJson(JsonObject o, String tournamentId, long deadlineAt)
    {
        if (o == null) return null;
        String sid = JsonLenient.optString(o, "series_id", "");
        if (sid.isEmpty()) return null;
        return new TournamentSeries(
            JsonLenient.optString(o, "tournament_id", tournamentId),
            sid,
            JsonLenient.optInt(o, "round", 0),
            JsonLenient.optInt(o, "best_of", 1),
            JsonLenient.optString(o, "status", "open"),
            JsonLenient.optString(o, "opponent_name", "your opponent"),
            JsonLenient.optString(o, "opponent_acct_sha", null),
            JsonLenient.optString(o, "opponent_player_id", null),
            JsonLenient.optString(o, "world", null),
            JsonLenient.optString(o, "meeting_place", null),
            JsonLenient.optInt(o, "games_recognised", 0),
            JsonLenient.optString(o, "winner_acct_sha", null),
            JsonLenient.optLong(o, "deadline_at", deadlineAt),
            JsonLenient.optString(o, "category", null),
            JsonLenient.optString(o, "style", null));
    }

    public boolean isOpen()
    {
        return "open".equals(status);
    }

    /** {@code "W370"} for {@code 370} / {@code "370"} / {@code "W370"}; {@code "?"} when unknown. */
    public String worldLabel()
    {
        if (world == null || world.trim().isEmpty()) return "?";
        String w = world.trim();
        return w.toUpperCase().startsWith("W") ? w : "W" + w;
    }
}
