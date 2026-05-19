package com.pvp.leaderboard.lobby;

/**
 * Terminal "Meet at" view payload pushed when both players have confirmed
 * the fight. Sent by the server via {@code lobby/match_found}.
 *
 * <p>{@link #world} is the world number the players should world-hop to
 * (e.g. {@code "W370"} for NA Arena), resolved server-side per the
 * style + location + both players' regions. The client renders this
 * verbatim — no client-side world-picking — so the world tables can be
 * updated server-side without a plugin release. {@link #meetingPlace}
 * is the in-game meeting spot per style+location (e.g. {@code "Arena"},
 * {@code "Ferox Enclave"}, {@code "Grand Exchange"}), also resolved
 * server-side.
 *
 * <p>Pure immutable data carrier. The panel renders this in its Meet-At
 * view (the terminal state of the fight flow).
 */
public final class MatchInfo
{
    public final String fightSessionId;
    public final LobbyMember opponent;
    public final Style style;
    public final BuildType build;
    public final String location;
    public final String world;
    public final String meetingPlace;

    public MatchInfo(String fightSessionId, LobbyMember opponent, Style style, BuildType build,
                     String location, String world, String meetingPlace)
    {
        this.fightSessionId = fightSessionId;
        this.opponent = opponent;
        this.style = style;
        this.build = build;
        this.location = location;
        this.world = world;
        this.meetingPlace = meetingPlace;
    }
}
