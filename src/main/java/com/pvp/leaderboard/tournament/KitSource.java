package com.pvp.leaderboard.tournament;

public interface KitSource
{
    GearKit kit(GearSet set, boolean arena);

    String buildInUse(boolean arena);

    boolean frozen(boolean arena);

    boolean atArena();
}
