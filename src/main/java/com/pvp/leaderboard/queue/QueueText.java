package com.pvp.leaderboard.queue;

import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.LobbyErrorMessages;
import com.pvp.leaderboard.lobby.Style;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * User-facing text for the matchmaking queue (Plan 10 F.1, 2026-09-21):
 * the wait-time labels shared by the gate picker and the timeout banner,
 * the banners for the server's idle {@code reason}s (Part E), the
 * {@code error/queue} table, and the style / build labels the searching
 * card prints. Pure functions — {@code MatchmakingLobbyPanel} decides
 * <i>when</i> to show them. Wording is the operator's (mockup v4 / plan
 * § Part F); lobby codes defer to {@link LobbyErrorMessages} so one code
 * never has two translations.
 */
public final class QueueText
{
    public static final String EXPIRED = "Your match expired before both players confirmed.";
    public static final String OPPONENT_DECLINED = "Your opponent can't make it — the match was cancelled.";

    /** {@code error/queue} codes that are not lobby codes (WEBSOCKET_PROTOCOL.md § 7). */
    private static final Map<String, String> QUEUE_ERRORS;
    static
    {
        Map<String, String> m = new HashMap<>();
        m.put("QUEUE_ALREADY_IN", "You are already in the queue.");
        m.put("QUEUE_NOT_IN", "You are not in the queue.");
        // G-3: Discord's leading clause verbatim (discord_bot_handler._join_reply,
        // 409 in_open_session) with the plugin's own trailing clause — Discord's
        // "check your DMs (or the plugin)" points at a surface this user is not on.
        m.put("QUEUE_IN_OPEN_SESSION", "You already have a fight being set up — confirm it before queueing again.");
        // G-3: Discord's 429 sentence minus the emoji. Deliberately names no
        // cause — QUEUE_COOLDOWN covers the re-pair cooldown as well as a dodge
        // strike and the server sends no discriminator.
        m.put("QUEUE_COOLDOWN", "You're on a matchmaking cooldown. Try again later.");
        m.put("QUEUE_INVALID_PREF", "That queue preference is not valid. Pick a wait time from the list and try again.");
        QUEUE_ERRORS = Collections.unmodifiableMap(m);
    }

    private QueueText() {}

    /** {@code "30 s"} / {@code "5 min"} / {@code "10 min"} / {@code "30 min"};
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
            default: return null;
        }
    }

    /** The {@code queue/timeout} banner. */
    public static String forTimeout(int waitPrefS)
    {
        return "No opponent found within " + waitLabel(waitPrefS) + " — you left the queue.";
    }

    /** Lobby codes → {@link LobbyErrorMessages}; queue codes → the table
     *  above; anything newer → the server's message, then the generic
     *  fallback. Never the raw code. */
    public static String forError(String code, String message)
    {
        if (LobbyErrorMessages.isKnown(code)) return LobbyErrorMessages.forCode(code);
        String queue = code == null ? null : QUEUE_ERRORS.get(code);
        if (queue != null) return queue;
        if (message != null && !message.trim().isEmpty()) return message.trim();
        return LobbyErrorMessages.UNKNOWN_FALLBACK;
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
