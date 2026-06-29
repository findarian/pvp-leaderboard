package com.pvp.leaderboard.lobby;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Localized message lookup for {@code error/lobby} envelope codes. The
 * server sends a debug {@code message} field too but it's English-only
 * and subject to change without notice — the plugin must never display
 * the raw {@code message} directly, only the localized one from here.
 *
 * <p>Unknown codes fall back to a generic "Something went wrong" so a
 * server that introduces a new error code without a plugin release
 * doesn't surface a raw enum to the user.
 */
public final class LobbyErrorMessages
{
    /** Generic fallback for codes the plugin's version of the map
     *  doesn't recognise. Intentionally vague — we don't know what
     *  happened, only that something failed. */
    public static final String UNKNOWN_FALLBACK = "Something went wrong. Try again in a moment.";

    /** Stable code → user-facing string map. Keep in sync with the
     *  server-side error enum every time a new code is added. */
    private static final Map<String, String> MESSAGES;
    static
    {
        Map<String, String> m = new HashMap<>();
        // ---- Join validation ----
        m.put("UNKNOWN_REGION",
            "That region isn't supported. Pick one from the dropdown and try again.");
        m.put("INVALID_STYLE",
            "One of your selected styles isn't supported. Reset options and try again.");
        m.put("INVALID_BUILD",
            "Your build pick isn't supported. Reset options and try again.");
        m.put("INVALID_LOCATION",
            "That fight location isn't supported. Pick a different one.");
        m.put("INVALID_DISPLAY_RANK",
            "Your rank range is invalid. Adjust the rank slider and try again.");
        m.put("SMURF_GUARD",
            "You need 20 kills or deaths in this style before you can queue for it. "
            + "Play casual PvP to build up your match count.");

        // ---- Invite / accept / confirm ----
        m.put("PEER_NOT_IN_LOBBY",
            "That player just left the lobby. Pick someone else.");
        m.put("RANK_OUT_OF_RANGE",
            "That player is outside your rank range. Widen your rank slider or pick someone else.");
        m.put("BLOCKED",
            "You can't fight this player — one of you has blocked the other.");
        m.put("DUPLICATE_INVITE",
            "You already have an outstanding invite to that player. Wait for the timer to expire "
            + "or cancel it from the lobby.");
        m.put("INVITE_EXPIRED",
            "That invite expired before you could accept it.");
        m.put("MATCHMAKING_SUSPENDED",
            "That player is suspended from matchmaking right now.");
        m.put("FIGHT_SESSION_EXPIRED",
            "The 30-second confirm window expired. Send a new invite to retry.");
        m.put("SELF_INVITE",
            "You can't fight your own account.");

        // ---- Protocol / transport ----
        m.put("INVALID_MESSAGE",
            "The plugin sent a message the server didn't understand. This is a plugin bug — please report it.");
        m.put("UNKNOWN_COMMAND",
            "The plugin sent a command the server doesn't support. The server may be older than your plugin "
            + "build — try again later.");

        MESSAGES = Collections.unmodifiableMap(m);
    }

    /** Look up the localized message for {@code code}, or
     *  {@link #UNKNOWN_FALLBACK} if the code is unrecognised / null /
     *  empty. Never returns {@code null}. */
    public static String forCode(String code)
    {
        if (code == null || code.isEmpty()) return UNKNOWN_FALLBACK;
        String msg = MESSAGES.get(code);
        return msg != null ? msg : UNKNOWN_FALLBACK;
    }

    /** Returns {@code true} if {@code code} is one the plugin knows about
     *  — useful for tests that want to assert wire-side codes have
     *  matching client-side translations (i.e. that we don't ship a
     *  build whose error UX is half-localized). */
    public static boolean isKnown(String code)
    {
        return code != null && MESSAGES.containsKey(code);
    }

    private LobbyErrorMessages() { /* static-only */ }
}
