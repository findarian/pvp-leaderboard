package com.pvp.leaderboard.lobby;

/**
 * An invite the local user has sent but the opponent hasn't accepted yet.
 * Returned indirectly via {@link LobbyService#sendInvite} for the panel's
 * {@code [Invited M:SS]} chip state on the sender's row.
 *
 * <p>{@link #expiresAtEpochMs} is the absolute wall-clock instant the invite
 * lapses (10 min from server-stamped {@code createdAtEpochMs} matching the
 * server's {@code LOBBY_INVITE_TTL_SEC=600}). When it elapses, the panel
 * removes the chip and re-enables {@code [Fight]} on the opponent's row.
 *
 * <p>Pure immutable data carrier — timer ownership lives in the service
 * implementation, not on this type.
 */
public final class OutgoingInvite
{
    public final String inviteId;
    public final LobbyMember opponent;
    public final Style style;
    public final BuildType build;
    public final String location;
    public final long createdAtEpochMs;
    public final long expiresAtEpochMs;

    public OutgoingInvite(String inviteId, LobbyMember opponent, Style style, BuildType build,
                          String location, long createdAtEpochMs, long expiresAtEpochMs)
    {
        this.inviteId = inviteId;
        this.opponent = opponent;
        this.style = style;
        this.build = build;
        this.location = location;
        this.createdAtEpochMs = createdAtEpochMs;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }
}
