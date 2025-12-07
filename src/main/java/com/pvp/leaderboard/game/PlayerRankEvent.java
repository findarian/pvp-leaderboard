package com.pvp.leaderboard.game;

import lombok.Value;

@Value
public class PlayerRankEvent
{
    String playerName;
    String bucket;
    String tier;
}

