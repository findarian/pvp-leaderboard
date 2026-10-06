package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.*;
import lombok.*;

@RequiredArgsConstructor
public final class KitRouter implements KitSource
{
    private final KitStore store;
    private final KitReader reader;
    private final GearWatcher watcher;
    private final ArenaLocator locator;

    @Override
    public GearKit kit(GearSet set, boolean arena)
    {
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
