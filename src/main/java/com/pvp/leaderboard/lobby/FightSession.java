package com.pvp.leaderboard.lobby;

/**
 * A pending mutual-confirm fight session. Created when either side enters
 * the 30-s mutual-confirm phase (sender's invite was accepted, OR receiver
 * clicked Accept Fight). Pushed by the server via
 * {@code lobby/fight_proposed} to <b>both</b> sides; both must confirm via
 * {@code lobby/confirm} within {@link #confirmExpiresAtEpochMs} or the
 * session ends with both players returned to the lobby (no penalty).
 *
 * <p>This type is the immutable bundle of context for the session. The
 * panel tracks {@code iConfirmed} / {@code peerConfirmed} as <b>local</b>
 * UI state separate from this — those flags are derived from the server's
 * push events ({@link LobbyEventListener#onFightConfirmedByPeer},
 * {@link LobbyEventListener#onMatchFound}) and from the local user clicking
 * the confirm button. Timer ownership lives in the service implementation,
 * keeping this type a pure data carrier.
 */
public final class FightSession
{
    public final String fightSessionId;
    public final LobbyMember opponent;
    public final Style style;
    public final BuildType build;
    public final String location;
    public final long confirmExpiresAtEpochMs;

    public FightSession(String fightSessionId, LobbyMember opponent, Style style, BuildType build,
                        String location, long confirmExpiresAtEpochMs)
    {
        this.fightSessionId = fightSessionId;
        this.opponent = opponent;
        this.style = style;
        this.build = build;
        this.location = location;
        this.confirmExpiresAtEpochMs = confirmExpiresAtEpochMs;
    }
}
