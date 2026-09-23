package com.pvp.leaderboard.game;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntSupplier;

@Singleton
public class ArenaLocator
{
    public static final Set<Integer> ARENA_REGIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(13362, 13363)));

    private final IntSupplier regionId;
    private final IntSupplier arenaWorldVarbit;
    private volatile boolean atArena;

    @Inject
    public ArenaLocator(Client client)
    {
        this(() -> regionOf(client), () -> client.getVarbitValue(ArenaWidgets.ARENA_WORLD_VARBIT));
    }

    ArenaLocator(IntSupplier regionId, IntSupplier arenaWorldVarbit)
    {
        this.regionId = regionId;
        this.arenaWorldVarbit = arenaWorldVarbit;
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        tick();
    }

    void tick()
    {
        try
        {
            atArena = decide(regionId.getAsInt(), arenaWorldVarbit.getAsInt());
        }
        catch (RuntimeException e)
        {
            atArena = false;
        }
    }

    public boolean isAtArena()
    {
        return atArena;
    }

    static boolean decide(int region, int arenaWorldVarbit)
    {
        return arenaWorldVarbit == 1 || ARENA_REGIONS.contains(region);
    }

    private static int regionOf(Client client)
    {
        Player p = client.getLocalPlayer();
        if (p == null) return -1;
        LocalPoint lp = p.getLocalLocation();
        if (lp == null) return -1;
        WorldPoint wp = WorldPoint.fromLocalInstance(client, lp);
        return wp == null ? -1 : wp.getRegionID();
    }
}
