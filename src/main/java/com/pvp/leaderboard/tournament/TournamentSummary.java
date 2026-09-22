package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One tournament as the server lists it (Plan 10 Part C / F.3,
 * 2026-09-21): the public event fields of {@code tournament/list_response}
 * plus the caller's own {@code my_status}; the trimmed rows of
 * {@code tournament/state.registrations} parse into the same type with
 * the fields the server omits left at their neutral values. acct_sha /
 * display names only — no UUIDs on the socket path.
 *
 * <p>Set 6 (2026-09-22, the info card): {@code creator_name} (the host),
 * {@code rank_limits} (per-bucket rank indices) and the optional
 * {@code rules} block ({@link TournamentRules}) are read too — all
 * additive, each neutral when the row does not carry it.
 */
public final class TournamentSummary
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
    public final long registrationClosesAt;
    public final long startsAt;
    public final int rounds;
    public final int currentRound;
    public final int registeredCount;
    /** registered | withdrawn | dnf | dq | kicked | dropped_unpaid, or {@code null} when not registered. */
    public final String myStatus;
    public final String rulesUrl;
    public final long buyInGp;
    public final String prizeMode;
    public final int prizeTopX;
    public final int prizeRandomY;
    public final long prizePoolGp;
    public final String description;
    public final boolean pluginRequired;
    public final boolean midEventJoins;
    public final int minGames;
    public final int roundLengthSec;
    /** The host ({@code creator_name}); {@code null} when the row does not carry it. */
    public final String creatorName;
    /** {@code rank_limits} in bucket order (the order Discord prints them); empty when the event has none. */
    public final List<RankLimit> rankLimits;
    /** The optional {@code rules} block; {@code null} = absent or junk, so the plugin uses {@link #rulesUrl}. */
    public final TournamentRules rules;

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

    public TournamentSummary(String tournamentId, String name, String status, String format, String category, String style,
                             int maxPlayers, long registrationClosesAt, long startsAt, int rounds, int currentRound, int registeredCount,
                             String myStatus, String rulesUrl, long buyInGp, String prizeMode, int prizeTopX, int prizeRandomY,
                             long prizePoolGp, String description, boolean pluginRequired, boolean midEventJoins, int minGames, int roundLengthSec)
    {
        this(tournamentId, name, status, format, category, style, maxPlayers, registrationClosesAt, startsAt, rounds, currentRound, registeredCount,
            myStatus, rulesUrl, buyInGp, prizeMode, prizeTopX, prizeRandomY, prizePoolGp, description, pluginRequired, midEventJoins, minGames, roundLengthSec,
            null, Collections.<RankLimit>emptyList(), null);
    }

    public TournamentSummary(String tournamentId, String name, String status, String format, String category, String style,
                             int maxPlayers, long registrationClosesAt, long startsAt, int rounds, int currentRound, int registeredCount,
                             String myStatus, String rulesUrl, long buyInGp, String prizeMode, int prizeTopX, int prizeRandomY,
                             long prizePoolGp, String description, boolean pluginRequired, boolean midEventJoins, int minGames, int roundLengthSec,
                             String creatorName, List<RankLimit> rankLimits, TournamentRules rules)
    {
        this.tournamentId = tournamentId;
        this.name = name;
        this.status = status;
        this.format = format;
        this.category = category;
        this.style = style;
        this.maxPlayers = maxPlayers;
        this.registrationClosesAt = registrationClosesAt;
        this.startsAt = startsAt;
        this.rounds = rounds;
        this.currentRound = currentRound;
        this.registeredCount = registeredCount;
        this.myStatus = myStatus;
        this.rulesUrl = rulesUrl;
        this.buyInGp = buyInGp;
        this.prizeMode = prizeMode;
        this.prizeTopX = prizeTopX;
        this.prizeRandomY = prizeRandomY;
        this.prizePoolGp = prizePoolGp;
        this.description = description;
        this.pluginRequired = pluginRequired;
        this.midEventJoins = midEventJoins;
        this.minGames = minGames;
        this.roundLengthSec = roundLengthSec;
        this.creatorName = creatorName;
        this.rankLimits = rankLimits == null ? Collections.<RankLimit>emptyList() : Collections.unmodifiableList(new ArrayList<>(rankLimits));
        this.rules = rules;
    }

    /** {@code null} when the object has no {@code tournament_id}. */
    public static TournamentSummary fromJson(JsonObject o)
    {
        if (o == null) return null;
        String id = JsonLenient.optString(o, "tournament_id", "");
        if (id.isEmpty()) return null;
        return new TournamentSummary(
            id,
            JsonLenient.optString(o, "name", id),
            JsonLenient.optString(o, "status", ""),
            JsonLenient.optString(o, "format", "swiss"),
            JsonLenient.optString(o, "category", ""),
            JsonLenient.optString(o, "style", "main"),
            JsonLenient.optInt(o, "max_players", 0),
            JsonLenient.optLong(o, "registration_closes_at", 0L),
            JsonLenient.optLong(o, "starts_at", 0L),
            JsonLenient.optInt(o, "rounds", 0),
            JsonLenient.optInt(o, "current_round", 0),
            JsonLenient.optInt(o, "registered_count", 0),
            JsonLenient.optString(o, "my_status", null),
            JsonLenient.optString(o, "rules_url", null),
            JsonLenient.optLong(o, "buy_in_gp", 0L),
            JsonLenient.optString(o, "prize_mode", "top_x"),
            JsonLenient.optInt(o, "prize_top_x", 1),
            JsonLenient.optInt(o, "prize_random_y", 0),
            JsonLenient.optLong(o, "prize_pool_gp", 0L),
            JsonLenient.optString(o, "description", ""),
            JsonLenient.optBool(o, "plugin_required", true),
            JsonLenient.optBool(o, "mid_event_joins", false),
            JsonLenient.optInt(o, "min_games", 0),
            JsonLenient.optInt(o, "round_length_sec", 0),
            JsonLenient.optString(o, "creator_name", null),
            rankLimitsFrom(o),
            TournamentRules.fromJson(o.get("rules")));
    }

    /** {@code rank_limits: {<bucket>: {min_idx?, max_idx?}}} → one
     *  {@link RankLimit} per bucket that carries at least one numeric bound,
     *  in bucket order (what {@code discord_tournament_messages._rank_limit_parts}
     *  prints). Junk — not an object, a non-object bucket, a non-numeric
     *  bound — is skipped, never thrown on. */
    static List<RankLimit> rankLimitsFrom(JsonObject o)
    {
        JsonObject limits = JsonLenient.optObject(o, "rank_limits");
        if (limits == null) return Collections.emptyList();
        List<String> buckets = new ArrayList<>(limits.keySet());
        Collections.sort(buckets);
        List<RankLimit> out = new ArrayList<>();
        for (String bucket : buckets)
        {
            JsonObject entry = JsonLenient.optObject(limits, bucket);
            if (entry == null) continue;
            Integer lo = JsonLenient.optInteger(entry, "min_idx");
            Integer hi = JsonLenient.optInteger(entry, "max_idx");
            if (lo == null && hi == null) continue;
            out.add(new RankLimit(bucket.trim().toLowerCase(Locale.ROOT), lo == null ? -1 : lo, hi == null ? -1 : hi));
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isOpenForRegistration()
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
        return style == null || style.isEmpty() ? "Main" : Character.toUpperCase(style.charAt(0)) + style.substring(1);
    }

    /** The bucket label the Discord messages use ({@code STYLE_LABELS}): nh → NH, dmm → DMM, else capitalised; {@code "?"} for none. */
    public static String categoryLabel(String category)
    {
        String cat = category == null ? "" : category;
        return "nh".equals(cat) ? "NH" : "dmm".equals(cat) ? "DMM" : cat.isEmpty() ? "?" : Character.toUpperCase(cat.charAt(0)) + cat.substring(1);
    }

    @Override
    public String toString()
    {
        return "TournamentSummary{" + tournamentId + " " + name + " " + status + " my=" + myStatus + "}";
    }
}
