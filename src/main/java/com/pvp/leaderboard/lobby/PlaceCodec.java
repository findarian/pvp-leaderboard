package com.pvp.leaderboard.lobby;

import java.util.*;

/**
 * Wire-protocol codec for the lobby's {@code location} field.
 *
 * <p>The {@link MatchmakingLobbyPanel}'s sub-location pickers
 * surface display strings — {@code "Arena"} / {@code "Wildy"} /
 * {@code "FFA Portal"} for NH and {@code "Wilderness"} /
 * {@code "Clan Wars"} for Multi — while the backend's
 * {@code ALLOWED_LOCATIONS_BY_STYLE} validator
 * (in {@code backend/core/lobby.py}) expects a tight canonical enum
 * {@code {arena, wildy, ffa, wilderness, clan_wars, none}} on the
 * wire. Without translation the picker would send {@code "FFA Portal"}
 * and the server would reject the invite with {@code INVALID_LOCATION}
 * before the row was even minted (operator report 2026-05-25 — exact
 * symptom on the screenshot of the {@code "That fight location isn't
 * supported. Pick a different one."} toast).
 *
 * <p>This codec is the single point of conversion at the wire
 * boundary in {@link WebSocketLobbyService}. The {@link
 * MatchmakingLobbyPanel}'s in-memory {@link OutgoingInvite} /
 * {@link IncomingInvite} / {@link FightSession} continue to carry
 * <strong>display</strong> strings so the existing UI render paths
 * ({@code inviteLabel}, {@code formatSetup},
 * {@code meetAtPlace}) read cleanly without churn.
 *
 * <p><strong>Symmetry with the backend.</strong> The backend has a
 * <em>lenient</em> validator layer (commit
 * {@code p2-lobby-location-display-aliases}, 2026-05-25) that also
 * accepts the plugin's display strings as aliases — kept as
 * defense-in-depth + back-compat for older plugin builds. With this
 * codec live, the wire is canonical end-to-end and the backend's
 * lenient layer is a no-op for new clients.
 *
 * <p><strong>Veng / DMM convention.</strong> Those styles have no
 * sub-location picker — the panel passes {@code null} or
 * {@code ""} when constructing invites. The backend's canonical
 * sentinel for "world-only, no sub-location" is the literal string
 * {@code "none"}. {@link #toCanonical} maps both
 * {@code (VENG, null)} and {@code (DMM, "")} (and every combination
 * thereof) to {@code "none"}; {@link #toDisplay} maps {@code "none"}
 * back to {@code ""} so the panel's render path falls through to
 * {@code inviteLabel}'s "PvP World" / "DMM World"
 * per-style default.
 */
public final class PlaceCodec
{

    /** canonical wire string → display string for the panel. */
    private static final Map<String, String> TO_DISPLAY;

    static
    {
        Map<String, String> c2d = new HashMap<>();
        c2d.put("arena",      "Arena");
        c2d.put("wildy",      "Wildy");
        c2d.put("ffa",        "FFA Portal");
        c2d.put("wilderness", "Wilderness");
        c2d.put("clan_wars",  "Clan Wars");
        // "none" is the world-only sentinel — render as empty so the
        // panel's existing inviteLabel(...) chooses the
        // "PvP World" / "DMM World" per-style default.
        c2d.put("none",       "");
        TO_DISPLAY = Collections.unmodifiableMap(c2d);
    }

    /**
     * Converts a server-canonical wire string back into the
     * picker-side display string the panel renders.
     *
     * <p>{@code null} / empty / unrecognised inputs pass through
     * (empty stays empty; unrecognised renders verbatim so it
     * surfaces in CloudWatch-via-debug-logs rather than vanishing).
     */
    public static String toDisplay(String canonOrNull)
    {
        if (canonOrNull == null)
        {
            return "";
        }
        String hit = TO_DISPLAY.get(canonOrNull);
        return hit != null ? hit : canonOrNull;
    }
}
