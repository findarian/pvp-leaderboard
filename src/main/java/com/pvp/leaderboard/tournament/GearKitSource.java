package com.pvp.leaderboard.tournament;

public interface GearKitSource
{
    GearKit kit(GearSet set, boolean arena);

    String buildInUse(boolean arena);

    boolean frozen(boolean arena);

    boolean atArena();
}
