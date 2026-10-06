package com.pvp.leaderboard.game;

import lombok.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.client.eventbus.*;

@Singleton
public class ArenaLocator
{
    public static final Set<Integer> ARENA_REGIONS = Set.of(13362, 13363);

    IntSupplier regionId;
    IntSupplier worldVarbit;
    @Getter private volatile boolean atArena;

    @Inject
    public ArenaLocator(Client client)
    {
        regionId = () -> regionOf(client);
        worldVarbit = () -> client.getVarbitValue(VarbitID.THIS_IS_A_PVP_ARENA_WORLD);
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
            atArena = decide(regionId.getAsInt(), worldVarbit.getAsInt());
        }
        catch (RuntimeException e)
        {
            atArena = false;
        }
    }

    static boolean decide(int region, int worldVarbit)
    {
        return worldVarbit == 1 || ARENA_REGIONS.contains(region);
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
