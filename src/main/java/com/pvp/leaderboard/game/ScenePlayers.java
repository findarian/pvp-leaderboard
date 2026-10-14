package com.pvp.leaderboard.game;

import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.*;

/** The players in the scene, kept from spawn and despawn events. Used on the client thread only. */
@Singleton
public class ScenePlayers
{
    private final Set<Player> players = new LinkedHashSet<>();

    /** Adds the player only while their world view still holds them at their index. */
    @Subscribe
    public void onPlayerSpawned(PlayerSpawned e)
    {
        Player p = e.getPlayer();
        if (p == null) return;
        WorldView wv = p.getWorldView();
        if (wv == null || wv.players().byIndex(p.getId()) != p)
        {
            return;
        }
        add(p);
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned e)
    {
        players.remove(e.getPlayer());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        GameState s = e.getGameState();
        if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING) clear();
    }

    /** Replaces the scene with the players in it now, for a start while logged in. */
    public void seed(Client client)
    {
        clear();
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        for (Player p : wv.players()) add(p);
    }

    /** The live set, in arrival order; read it on the client thread. */
    public Collection<Player> all()
    {
        return players;
    }

    public void clear()
    {
        players.clear();
    }

    void add(Player p)
    {
        if (p != null) players.add(p);
    }
}
