package com.pvp.leaderboard.tournament;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The local player's series in the current round (Plan 10 Part C):
 * {@code tournament/match_assigned} and {@code tournament/state.active.series}
 * both parse into this. The opponent is identified by name / acct_sha /
 * player_id only.
 */
public final class TournamentSeries
{
    public static final String UNKNOWN_OPPONENT = "your opponent";

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
    /** Every name the opponent is logged in with ({@code opponent_names}); empty when the push carries none. */
    public final List<String> opponentNames;
    /** The opponent's region ({@code opponent_region}, "na-e"); {@code null} when the push carries none. */
    public final String opponentRegion;

    public TournamentSeries(String tournamentId, String seriesId, int round, int bestOf, String status, String opponentName,
                            String opponentAcctSha, String opponentPlayerId, String world, String meetingPlace, int gamesRecognised,
                            String winnerAcctSha, long deadlineAt, String category, String style)
    {
        this(tournamentId, seriesId, round, bestOf, status, opponentName, opponentAcctSha, opponentPlayerId, world, meetingPlace, gamesRecognised,
            winnerAcctSha, deadlineAt, category, style, null, null);
    }

    public TournamentSeries(String tournamentId, String seriesId, int round, int bestOf, String status, String opponentName,
                            String opponentAcctSha, String opponentPlayerId, String world, String meetingPlace, int gamesRecognised,
                            String winnerAcctSha, long deadlineAt, String category, String style, List<String> opponentNames, String opponentRegion)
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
        this.opponentNames = opponentNames == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<>(opponentNames));
        this.opponentRegion = opponentRegion;
    }

    /** The names the outline matches: {@link #opponentNames}, else the one {@link #opponentName} when named. */
    public List<String> outlineNames()
    {
        if (!opponentNames.isEmpty()) return opponentNames;
        return hasNamedOpponent() ? Collections.singletonList(opponentName) : Collections.<String>emptyList();
    }

    /** The string entries of {@code key}'s array, trimmed, blanks and non-strings skipped; empty when it is not an array. */
    public static List<String> namesOf(JsonObject o, String key)
    {
        List<String> out = new ArrayList<>();
        for (JsonElement e : JsonLenient.optArray(o, key))
        {
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            String s = e.getAsString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** {@code "W370"} for {@code 370} / {@code "370"} / {@code " w370 "}; {@code null} for nothing. */
    public static String worldLabelOf(String world)
    {
        if (world == null) return null;
        String w = world.trim();
        if (w.isEmpty()) return null;
        return w.toUpperCase().startsWith("W") ? "W" + w.substring(1) : "W" + w;
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
            JsonLenient.optString(o, "opponent_name", UNKNOWN_OPPONENT),
            JsonLenient.optString(o, "opponent_acct_sha", null),
            JsonLenient.optString(o, "opponent_player_id", null),
            JsonLenient.optString(o, "world", null),
            JsonLenient.optString(o, "meeting_place", null),
            JsonLenient.optInt(o, "games_recognised", 0),
            JsonLenient.optString(o, "winner_acct_sha", null),
            JsonLenient.optLong(o, "deadline_at", deadlineAt),
            JsonLenient.optString(o, "category", null),
            JsonLenient.optString(o, "style", null),
            namesOf(o, "opponent_names"),
            TournamentSummary.textOf(o, "opponent_region"));
    }

    public boolean isOpen()
    {
        return "open".equals(status);
    }

    /** {@code "W370"} for {@code 370} / {@code "370"} / {@code "W370"}; {@code "?"} when unknown. */
    public String worldLabel()
    {
        String label = worldLabelOf(world);
        return label == null ? "?" : label;
    }

    public boolean hasNamedOpponent()
    {
        return opponentName != null && !opponentName.trim().isEmpty() && !UNKNOWN_OPPONENT.equals(opponentName);
    }

    public int worldNumber()
    {
        if (world == null) return 0;
        String w = world.trim();
        if (w.startsWith("W") || w.startsWith("w")) w = w.substring(1).trim();
        try
        {
            int n = Integer.parseInt(w);
            return n > 0 ? n : 0;
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
