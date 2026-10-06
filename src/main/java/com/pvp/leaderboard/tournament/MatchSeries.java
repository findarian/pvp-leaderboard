package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/**
 * The local player's series in the current round (Plan 10 Part C):
 * {@code tournament/match_assigned} and {@code tournament/state.active.series}
 * both parse into this. The opponent is identified by name / acct_sha /
 * player_id only.
 */
public final class MatchSeries
{
    public static final String UNKNOWN_OPPONENT = "your opponent";

    public final String tournamentId;
    public final String seriesId;
    public final int round;
    /** open | decided | dnf | void | bye. */
    public final String status;
    public final String opponentName;
    public final String world;
    public final String meetingPlace;
    /** Round deadline (epoch seconds), 0 when unknown. */
    public final long deadlineAt;
    public final String category;
    public final String style;
    /** Every name the opponent is logged in with ({@code opponent_names}); empty when the push carries none. */
    public final List<String> opponentNames;
    /** The opponent's region ({@code opponent_region}, "na-e"); {@code null} when the push carries none. */
    public final String opponentRegion;

    private MatchSeries(JsonObject o, String sid, String tid, long deadline)
    {
        tournamentId = optString(o, "tournament_id", tid);
        seriesId = sid;
        round = optInt(o, "round", 0);
        status = optString(o, "status", "open");
        opponentName = optString(o, "opponent_name", UNKNOWN_OPPONENT);
        world = optString(o, "world", null);
        meetingPlace = optString(o, "meeting_place", null);
        deadlineAt = optLong(o, "deadline_at", deadline);
        category = optString(o, "category", null);
        style = optString(o, "style", null);
        opponentNames = namesOf(o, "opponent_names");
        opponentRegion = Tourney.textOf(o, "opponent_region");
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
        for (JsonElement e : optArray(o, key))
        {
            String s = str(e);
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
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
    public static MatchSeries fromJson(JsonObject o, String tournamentId, long deadlineAt)
    {
        if (o == null) return null;
        String sid = optString(o, "series_id");
        return sid.isEmpty() ? null : new MatchSeries(o, sid, tournamentId, deadlineAt);
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
        return !opponentName.trim().isEmpty() && !UNKNOWN_OPPONENT.equals(opponentName);
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
