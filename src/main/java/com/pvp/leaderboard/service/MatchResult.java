package com.pvp.leaderboard.service;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@ToString
public class MatchResult
{
    private final String playerId;
    private final String opponentId;
    private final String result; // "win", "loss", "tie"
    private final int world;
    private final long fightStartTs;
    private final long fightEndTs;
    private final String fightStartSpellbook;
    private final String fightEndSpellbook;
    private final boolean wasInMulti;
    private final String idToken;
    private final long damageToOpponent;
    private final String clientUniqueId;
}
