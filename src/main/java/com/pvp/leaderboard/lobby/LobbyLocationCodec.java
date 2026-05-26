package com.pvp.leaderboard.lobby;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
 * ({@code inviteLocationLabel}, {@code formatStyleBuildPlace},
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
 * {@code inviteLocationLabel}'s "PvP World" / "DMM World"
 * per-style default.
 */
public final class LobbyLocationCodec
{
    /** (style, lowercased-display) → canonical wire string. */
    private static final Map<String, String> DISPLAY_TO_CANONICAL;

    /** canonical wire string → display string for the panel. */
    private static final Map<String, String> CANONICAL_TO_DISPLAY;

    static
    {
        Map<String, String> d2c = new HashMap<>();
        // NH picker — the strings come from MatchmakingLobbyPanel.NH_LOCATIONS.
        d2c.put(key(Style.NH, "arena"),       "arena");
        d2c.put(key(Style.NH, "wildy"),       "wildy");
        d2c.put(key(Style.NH, "ffa portal"),  "ffa");
        d2c.put(key(Style.NH, "ffa"),         "ffa");
        // Multi picker — from MatchmakingLobbyPanel.MULTI_LOCATIONS.
        d2c.put(key(Style.MULTI, "wilderness"),  "wilderness");
        d2c.put(key(Style.MULTI, "clan wars"),   "clan_wars");
        d2c.put(key(Style.MULTI, "clan_wars"),   "clan_wars");
        // Veng / DMM — null/empty/literal "none" all collapse to canonical "none".
        d2c.put(key(Style.VENG, ""),     "none");
        d2c.put(key(Style.VENG, "none"), "none");
        d2c.put(key(Style.DMM,  ""),     "none");
        d2c.put(key(Style.DMM,  "none"), "none");
        DISPLAY_TO_CANONICAL = Collections.unmodifiableMap(d2c);

        Map<String, String> c2d = new HashMap<>();
        c2d.put("arena",      "Arena");
        c2d.put("wildy",      "Wildy");
        c2d.put("ffa",        "FFA Portal");
        c2d.put("wilderness", "Wilderness");
        c2d.put("clan_wars",  "Clan Wars");
        // "none" is the world-only sentinel — render as empty so the
        // panel's existing inviteLocationLabel(...) chooses the
        // "PvP World" / "DMM World" per-style default.
        c2d.put("none",       "");
        CANONICAL_TO_DISPLAY = Collections.unmodifiableMap(c2d);
    }

    private LobbyLocationCodec()
    {
        // Utility class.
    }

    /**
     * Converts a picker-side display string into the canonical wire
     * string the server's {@code ALLOWED_LOCATIONS_BY_STYLE} expects.
     *
     * <p>Unknown strings are lowercased and returned verbatim — the
     * server then issues {@code INVALID_LOCATION} which surfaces as
     * the {@code LobbyErrorMessages.INVALID_LOCATION} toast in the
     * panel. This is intentional: the codec is whitelist-only on the
     * canonicalisation side, NOT on the rejection side.
     *
     * <p>{@code style == null} is a programming error (caller bug);
     * we return {@code ""} rather than NPE so the wire-side framing
     * still produces a JSON payload the server can reject cleanly.
     */
    public static String toCanonical(Style style, String displayOrNull)
    {
        if (style == null)
        {
            return "";
        }
        String d = displayOrNull == null ? "" : displayOrNull.trim().toLowerCase();
        String hit = DISPLAY_TO_CANONICAL.get(key(style, d));
        return hit != null ? hit : d;
    }

    /**
     * Converts a server-canonical wire string back into the
     * picker-side display string the panel renders.
     *
     * <p>{@code null} / empty / unrecognised inputs pass through
     * (empty stays empty; unrecognised renders verbatim so it
     * surfaces in CloudWatch-via-debug-logs rather than vanishing).
     */
    public static String toDisplay(String canonicalOrNull)
    {
        if (canonicalOrNull == null)
        {
            return "";
        }
        String hit = CANONICAL_TO_DISPLAY.get(canonicalOrNull);
        return hit != null ? hit : canonicalOrNull;
    }

    private static String key(Style style, String lowercasedDisplay)
    {
        return style.name() + "|" + lowercasedDisplay;
    }
}
