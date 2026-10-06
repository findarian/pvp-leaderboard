package com.pvp.leaderboard.queue;

import com.pvp.leaderboard.lobby.*;
import java.util.*;

/**
 * User-facing text for the matchmaking queue (Plan 10 F.1, 2026-09-21):
 * the wait-time labels shared by the gate picker and the timeout banner,
 * the banners for the server's idle {@code reason}s (Part E), the
 * {@code error/queue} table, and the style / build labels the searching
 * card prints. Pure functions — {@code MatchmakingLobbyPanel} decides
 * <i>when</i> to show them. Wording is the operator's (mockup v4 / plan
 * § Part F); lobby codes defer to {@link LobbyErrors} so one code
 * never has two translations, except {@code MATCHMAKING_SUSPENDED} ({@link #BANNED}).
 */
public final class QueueText
{
    /** The same sentence as the lobby's {@code FIGHT_SESSION_EXPIRED}. */
    public static final String EXPIRED = LobbyErrors.MATCH_EXPIRED;
    public static final String OPPONENT_DECLINED = "Your opponent can't make it — the match was cancelled.";
    public static final String LEFT_ON_DISCORD = "You left the queue on Discord.";
    /** A queue join refused with {@code MATCHMAKING_SUSPENDED}, and a tournament
     *  registration refused with {@code TOURNAMENT_BANNED}. */
    public static final String BANNED = "Your account is banned from matchmaking and tournaments. DM Toyco if this is a mistake.";

    /** {@code error/queue} codes and their sentences; read before the lobby table. */
    private static final Map<String, String> QUEUE_ERRORS = Map.of(
        "MATCHMAKING_SUSPENDED", BANNED,
        "QUEUE_ALREADY_IN", "You are already in the queue.",
        "QUEUE_NOT_IN", "You are not in the queue.",
        "QUEUE_IN_OPEN_SESSION", "You already have a match to confirm.",
        "QUEUE_COOLDOWN", "You're on a matchmaking cooldown.",
        "QUEUE_INVALID_PREF", "That queue preference is not valid. Pick a wait time from the list and try again.",
        "QUEUE_STYLE_UNAVAILABLE", "The queue is NH only.");

    /** {@code "30 s"} / {@code "5 min"} / {@code "10 min"} / {@code "15 min"};
     *  any other value prints the same way ({@code "1 min 30 s"}). */
    public static String waitLabel(int waitPrefS)
    {
        int s = Math.max(0, waitPrefS);
        if (s < 60) return s + " s";
        if (s % 60 == 0) return (s / 60) + " min";
        return (s / 60) + " min " + (s % 60) + " s";
    }

    /** The banner for an idle {@code queue/state}'s {@code reason}, or
     *  {@code null} when there is nothing to tell the user. */
    public static String forIdleReason(String reason)
    {
        if (reason == null) return null;
        switch (reason)
        {
            case "expired": return EXPIRED;
            case "opponent_declined": return OPPONENT_DECLINED;
            case "left_on_discord": return LEFT_ON_DISCORD;
            default: return null;
        }
    }

    /** The {@code queue/timeout} banner. */
    public static String forTimeout(int waitPrefS)
    {
        return "No opponent found within " + waitLabel(waitPrefS) + " — you left the queue.";
    }

    /** Queue codes and {@code MATCHMAKING_SUSPENDED} → the table above; other
     *  lobby codes → {@link LobbyErrors}; anything newer → the server's
     *  message, then the generic fallback. Never the raw code. */
    public static String forError(String code, String message)
    {
        String queue = code == null ? null : QUEUE_ERRORS.get(code);
        if (queue != null) return queue;
        if (LobbyErrors.isKnown(code)) return LobbyErrors.forCode(code);
        if (message != null && !message.trim().isEmpty()) return message.trim();
        return LobbyErrors.UNKNOWN_FALLBACK;
    }

    /** {@code "NH"} for the wire's {@code "nh"}; the fallback's label when
     *  the wire value is absent / unknown; {@code "?"} without either. */
    public static String styleLabel(String wireStyle, Style fallback)
    {
        Style s = parseStyle(wireStyle);
        if (s == null) s = fallback;
        return s == null ? "?" : s.label;
    }

    /** {@code "Main"} for the wire's {@code "main"}, same fallbacks as {@link #styleLabel}. */
    public static String buildLabel(String wireBuild, BuildType fallback)
    {
        BuildType b = parseBuild(wireBuild);
        if (b == null) b = fallback;
        return b == null ? "?" : b.label;
    }

    /** The wire style ({@code nh} / {@code veng} / {@code multi} / {@code dmm}, any case), or {@code null}. */
    public static Style parseStyle(String wire)
    {
        if (wire == null || wire.trim().isEmpty()) return null;
        try
        {
            return Style.valueOf(wire.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /** The wire build ({@code main} / {@code zerker} / {@code pure}, any case), or {@code null}. */
    public static BuildType parseBuild(String wire)
    {
        if (wire == null || wire.trim().isEmpty()) return null;
        try
        {
            return BuildType.valueOf(wire.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }
}
