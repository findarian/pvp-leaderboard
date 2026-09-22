package com.pvp.leaderboard.game;

import com.pvp.leaderboard.util.NameUtils;

/**
 * Tracks which character the plugin has already run its once-per-session
 * login init for, so the init chain runs on a genuine login rather than
 * on every event that happens to arm it.
 *
 * <p><b>Why.</b> The delayed-init block in
 * {@code PvPLeaderboardPlugin.onGameTick} is armed by
 * {@code GameState.LOGGED_IN} and also by {@code HOPPING}/{@code LOADING}.
 * {@code LOADING} fires on every map region load, so ordinary movement
 * re-ran the whole chain — including {@code lobbyJoinGate.onLogin()},
 * which forces a cache-bypassing {@code /user} fetch, and a heartbeat
 * schedule restart. None of that can produce a different answer within
 * a session, and the resulting API traffic contributed to a client
 * being blocked by the API's WAF.
 *
 * <p>Only the per-session work is gated on this. Genuinely per-hop work
 * (the overlay's self-rank refresh, which {@code resetLookupStateOnWorldHop}
 * has just invalidated) stays outside it.
 *
 * <p><b>Session boundary</b> is a real logout: {@link #onLogout()} is
 * driven from the {@code LOGIN_SCREEN} branch, alongside the existing
 * {@code lobbyJoinGate.onLogout()} / {@code whitelistService.onLogout()}
 * teardown. A world hop never passes through {@code LOGIN_SCREEN}, which
 * is exactly why it should not re-init.
 *
 * <p><b>Threading.</b> Confined to the client thread (both callers are
 * game-event handlers), so plain field access is sufficient.
 */
public final class SessionInitTracker
{
    /** Canonical name of the character the full init has run for, or
     *  {@code null} when no init has happened since the last logout.
     *  Canonical (see {@link NameUtils#canonicalKey}) so a cosmetic
     *  difference — casing, or the non-breaking space the client uses
     *  in some nameplate contexts — doesn't read as a new character. */
    private String initializedPlayer;

    /**
     * Reports whether the per-session init should run for
     * {@code playerName}, recording it as initialised when so.
     *
     * @param playerName the resolved local player name; {@code null} or
     *                   blank when the client hasn't populated it yet
     * @return {@code true} on the first init for a character, and again
     *         after {@link #onLogout()} or a character switch;
     *         {@code false} when this character is already initialised
     */
    public boolean shouldRunFullInit(String playerName)
    {
        if (playerName == null || playerName.trim().isEmpty())
        {
            // Name hasn't resolved yet. Report "don't init" without
            // recording anything — recording here would mark the
            // session initialised and suppress the real init that
            // follows once the name arrives.
            return false;
        }
        String canonical = NameUtils.canonicalKey(playerName);
        if (canonical.equals(initializedPlayer))
        {
            return false;
        }
        initializedPlayer = canonical;
        return true;
    }

    /** Ends the session so the next login re-inits. */
    public void onLogout()
    {
        initializedPlayer = null;
    }
}
