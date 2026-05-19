package com.pvp.leaderboard.lobby;

/**
 * An invite the local user has received. Pushed by the server via
 * {@code lobby/invite_received}; the panel pins one card per active invite
 * at the top of the lobby with Accept / Decline buttons + a TTL countdown.
 *
 * <p>{@link #expiresAtEpochMs} is the server-stamped wall-clock instant the
 * invite expires (10 min from {@code created_at} per
 * {@code LOBBY_INVITE_TTL_SEC=600} in the architecture). The panel computes
 * the visible countdown locally against this absolute deadline so client
 * clock drift doesn't shift the visible expiry.
 *
 * <p>Mirror of the now-removed panel-local invite-card data; the
 * {@link #build} field carries the sender's pick of which of <i>your</i>
 * advertised builds they want to fight (see {@code MatchmakingLobbyPanel}
 * docs around "Pick a Build" step for context).
 *
 * <p>Created as part of {@code p1-plugin-mock-refactor}. Public final
 * fields match the {@code LobbyMember} idiom (see that class for the
 * rationale).
 */
public final class IncomingInvite
{
    public final String inviteId;
    public final LobbyMember sender;
    public final Style style;
    public final BuildType build;
    public final String location;
    public final long expiresAtEpochMs;

    public IncomingInvite(String inviteId, LobbyMember sender, Style style, BuildType build,
                          String location, long expiresAtEpochMs)
    {
        this.inviteId = inviteId;
        this.sender = sender;
        this.style = style;
        this.build = build;
        this.location = location;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }
}
