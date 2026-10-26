package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.*;
import java.util.*;
import java.util.function.*;
import lombok.*;

@RequiredArgsConstructor
public final class KitRouter implements KitSource
{
    /** The PvP Arena worlds. */
    public static final Set<Integer> ARENA_WORLDS = Set.of(578, 558, 570);

    private final KitStore store;
    private final KitReader reader;
    private final GearWatcher watcher;
    private final IntSupplier world;

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
        return ARENA_WORLDS.contains(world.getAsInt());
    }
}
