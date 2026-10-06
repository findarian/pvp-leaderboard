package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

public final class Tourney
{
    public final String tournamentId;
    public final String name;
    /** registration | running | finished | cancelled. */
    public final String status;
    public final String format;
    /** nh / veng / multi / dmm. */
    public final String category;
    /** main / zerker / pure. */
    public final String style;
    public final int maxPlayers;
    public final long regClosesAt;
    public final long startsAt;
    public final int rounds;
    public final int currentRound;
    public final int regCount;
    /** registered | withdrawn | dnf | dq | kicked | dropped_unpaid, or {@code null} when not registered. */
    public final String myStatus;
    public final String rulesUrl;
    public final long buyInGp;
    public final String prizeMode;
    public final int prizeTopX;
    public final int prizeRandomY;
    public final long prizePoolGp;
    public final boolean pluginRequired;
    public final boolean lateJoins;
    public final int minGames;
    /** The host ({@code creator_name}); {@code null} when the row does not carry it. */
    public final String creatorName;
    /** {@code rank_limits} in bucket order (the order Discord prints them); empty when the event has none. */
    public final List<RankLimit> rankLimits;
    /** The optional {@code rules} block; {@code null} = absent or junk, so the plugin uses {@link #rulesUrl}. */
    public final TourneyRules rules;
    public final GearSet gearSet;
    public final int gearPrepSec;
    public final String location;
    public final boolean roundsAuto;
    /** Where a registered player meets when round 1 opens ({@code meeting.world}, "W578"); {@code null} when the entry has none. */
    public final String meetingWorld;
    /** {@code meeting.place}; {@code null} when the entry has none. */
    public final String meetingPlace;
    /** {@code ended_at} of a finished event (the history route), 0 when absent. */
    public final long endedAt;

    /** One bucket's entry of {@code rank_limits}: rank indices (0 = Bronze 3 … 24 = 3rd Age), {@code -1} = that bound is not set. */
    public static final class RankLimit
    {
        public final String bucket;
        public final int minIdx;
        public final int maxIdx;

        public RankLimit(String bucket, int minIdx, int maxIdx)
        {
            this.bucket = bucket;
            this.minIdx = minIdx;
            this.maxIdx = maxIdx;
        }
    }

    private Tourney(JsonObject o, String id)
    {
        tournamentId = id;
        name = optString(o, "name", id);
        status = optString(o, "status");
        format = optString(o, "format", "swiss");
        category = optString(o, "category");
        style = optString(o, "style", "main");
        maxPlayers = optInt(o, "max_players", 0);
        regClosesAt = optLong(o, "registration_closes_at", 0L);
        startsAt = optLong(o, "starts_at", 0L);
        rounds = optInt(o, "rounds", 0);
        currentRound = optInt(o, "current_round", 0);
        regCount = optInt(o, "registered_count", 0);
        myStatus = optString(o, "my_status", null);
        rulesUrl = optString(o, "rules_url", null);
        buyInGp = optLong(o, "buy_in_gp", 0L);
        prizeMode = optString(o, "prize_mode", "top_x");
        prizeTopX = optInt(o, "prize_top_x", 1);
        prizeRandomY = optInt(o, "prize_random_y", 0);
        prizePoolGp = optLong(o, "prize_pool_gp", 0L);
        pluginRequired = optBool(o, "plugin_required", true);
        lateJoins = optBool(o, "mid_event_joins", false);
        minGames = optInt(o, "min_games", 0);
        creatorName = optString(o, "creator_name", null);
        rankLimits = limitsFrom(o);
        rules = TourneyRules.fromJson(o.get("rules"));
        gearSet = GearSet.fromJson(o.get("gear_set"));
        gearPrepSec = Math.max(0, optInt(o, "gear_prep_sec", 0));
        location = textOf(o, "location");
        roundsAuto = optBool(o, "rounds_auto", false);
        meetingWorld = meetingWorld(o);
        meetingPlace = meetingPlace(o);
        endedAt = optLong(o, "ended_at", 0L);
    }

    /** {@code meeting.world} of an object, as "W578"; {@code null} without one. */
    static String meetingWorld(JsonObject o)
    {
        JsonObject meeting = optObject(o, "meeting");
        return meeting == null ? null : MatchSeries.worldLabelOf(optString(meeting, "world", null));
    }

    /** {@code meeting.place} of an object; {@code null} without one. */
    static String meetingPlace(JsonObject o)
    {
        JsonObject meeting = optObject(o, "meeting");
        return meeting == null ? null : textOf(meeting, "place");
    }

    /** {@code null} when the object has no {@code tournament_id}. */
    public static Tourney fromJson(JsonObject o)
    {
        if (o == null) return null;
        String id = optString(o, "tournament_id");
        return id.isEmpty() ? null : new Tourney(o, id);
    }

    static String textOf(JsonObject o, String key)
    {
        String s = str(o == null ? null : o.get(key));
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** {@code rank_limits: {<bucket>: {min_idx?, max_idx?}}} → one
     *  {@link RankLimit} per bucket that carries at least one numeric bound,
     *  in bucket order (what {@code discord_tournament_messages._rank_limit_parts}
     *  prints). Junk — not an object, a non-object bucket, a non-numeric
     *  bound — is skipped, never thrown on. */
    static List<RankLimit> limitsFrom(JsonObject o)
    {
        JsonObject limits = optObject(o, "rank_limits");
        if (limits == null) return Collections.emptyList();
        List<String> buckets = new ArrayList<>(limits.keySet());
        Collections.sort(buckets);
        List<RankLimit> out = new ArrayList<>();
        for (String bucket : buckets)
        {
            JsonObject entry = optObject(limits, bucket);
            if (entry == null) continue;
            Integer lo = optInteger(entry, "min_idx");
            Integer hi = optInteger(entry, "max_idx");
            if (lo == null && hi == null) continue;
            out.add(new RankLimit(bucket.trim().toLowerCase(Locale.ROOT), lo == null ? -1 : lo, hi == null ? -1 : hi));
        }
        return Collections.unmodifiableList(out);
    }

    /** The events of a list in its order; non-objects and entries without an id are skipped. */
    public static List<Tourney> listOf(JsonArray arr)
    {
        List<Tourney> out = new ArrayList<>();
        for (JsonElement e : arr)
        {
            if (e == null || !e.isJsonObject()) continue;
            Tourney t = fromJson(e.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    public boolean isRegOpen()
    {
        return "registration".equals(status);
    }

    public boolean isRunning()
    {
        return "running".equals(status);
    }

    public boolean amRegistered()
    {
        return "registered".equals(myStatus);
    }

    /** {@code "NH Main"} — the category + build the way the lobby chips spell them. */
    public String styleLabel()
    {
        return categoryLabel() + " " + buildLabel();
    }

    /** {@code "NH"} / {@code "Veng"} / {@code "Multi"} / {@code "DMM"}, {@code "?"} when unknown. */
    public String categoryLabel()
    {
        return categoryLabel(category);
    }

    /** {@code "Main"} / {@code "Zerker"} / {@code "Pure"} ({@code "Main"} when unset). */
    public String buildLabel()
    {
        return style.isEmpty() ? "Main" : Character.toUpperCase(style.charAt(0)) + style.substring(1);
    }

    /** The bucket label the Discord messages use ({@code STYLE_LABELS}): nh → NH, dmm → DMM, else capitalised; {@code "?"} for none. */
    public static String categoryLabel(String category)
    {
        return "nh".equals(category) ? "NH" : "dmm".equals(category) ? "DMM" : category.isEmpty() ? "?" : Character.toUpperCase(category.charAt(0)) + category.substring(1);
    }

    @Override
    public String toString()
    {
        return "TournamentSummary{" + tournamentId + " " + name + " " + status + " my=" + myStatus + "}";
    }
}
