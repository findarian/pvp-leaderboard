package com.pvp.leaderboard.game;

import lombok.Value;

/**
 * Event fired when a player's rank is updated from the API.
 * Used to communicate rank updates to the overlay system.
 */
@Value
public class PlayerRankEvent
{
    String playerName;
    String bucket;
    String tier;
}

