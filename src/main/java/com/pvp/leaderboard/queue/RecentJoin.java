package com.pvp.leaderboard.queue;

import lombok.*;

/** One recent queue join: the player's name and the join time in epoch seconds. */
@Value
public class RecentJoin
{
    String name;
    long at;
}
