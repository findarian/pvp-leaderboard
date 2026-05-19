package com.pvp.leaderboard.lobby;

/**
 * Terminal "Meet at" view payload pushed when both players have confirmed
 * the fight. Sent by the server via {@code lobby/match_found} per the
 * architecture's Phase-2 flow.
 *
 * <p>{@link #world} is the world number the players should world-hop to
 * (e.g. {@code "W370"} for NA Arena, {@code "TBD"} in mock mode until
 * {@code meet-at-worlds-tbd} is wired). {@link #meetingPlace} is the
 * in-game meeting spot per style+location (e.g. {@code "Arena"},
 * {@code "Ferox Enclave"}, {@code "Grand Exchange"}).
 *
 * <p>Created as part of {@code p1-plugin-mock-refactor}. Public final
 * fields match the {@code LobbyMember} idiom. The panel renders this in
 * its Meet-At view (the terminal state of the fight flow).
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
