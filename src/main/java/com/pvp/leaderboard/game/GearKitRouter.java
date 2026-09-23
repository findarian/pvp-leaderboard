package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.GearKit;
import com.pvp.leaderboard.tournament.GearKitSource;
import com.pvp.leaderboard.tournament.GearSet;

public final class GearKitRouter implements GearKitSource
{
    private final ArenaKitStore store;
    private final DuelKitReader reader;
    private final GearWatcher watcher;
    private final ArenaLocator locator;

    public GearKitRouter(ArenaKitStore store, DuelKitReader reader, GearWatcher watcher, ArenaLocator locator)
    {
        this.store = store;
        this.reader = reader;
        this.watcher = watcher;
        this.locator = locator;
    }

    @Override
    public GearKit kit(GearSet set, boolean arena)
    {
        if (set == null) return null;
        return arena ? store.get(set.build) : watcher.latest();
    }

    @Override
    public String buildInUse(boolean arena)
    {
        return arena ? reader.selectedBuild() : null;
    }

    @Override
    public boolean frozen(boolean arena)
    {
        return !arena && watcher.isFrozen();
    }

    @Override
    public boolean atArena()
    {
        return locator.isAtArena();
    }
}
