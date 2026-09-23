package com.pvp.leaderboard.game;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Singleton
public class RunePouchRunes
{
    private final Client client;
    private volatile Set<Integer> runeIds = Collections.emptySet();

    @Inject
    public RunePouchRunes(Client client)
    {
        this.client = client;
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        load();
    }

    private void load()
    {
        if (!runeIds.isEmpty()) return;
        try
        {
            EnumComposition runes = client.getEnum(GearWatcher.RUNEPOUCH_RUNE_ENUM);
            int[] ids = runes == null ? null : runes.getIntVals();
            if (ids == null) return;
            Set<Integer> read = new HashSet<>();
            for (int id : ids) if (id > 0) read.add(id);
            runeIds = Collections.unmodifiableSet(read);
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] rune pouch enum read failed", e);
        }
    }

    public boolean isRune(int itemId)
    {
        return runeIds.contains(itemId);
    }
}
