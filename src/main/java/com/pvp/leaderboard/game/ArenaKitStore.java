package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.GearKit;
import com.pvp.leaderboard.tournament.GearSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Singleton
public class ArenaKitStore
{
    static final String CONFIG_GROUP = "PvPLeaderboard";
    static final String KEY_PREFIX = "gearKit.";

    private final ConfigManager configManager;

    private String loadedProfile;
    private final Map<String, GearKit> kits = new HashMap<>();
    private final Map<String, List<GearKit.Item>> pendingPouch = new HashMap<>();
    private final Map<String, Long> pendingPouchSig = new HashMap<>();
    private String latestBuild;
    private final Map<String, Long> observedLoadout = new HashMap<>();
    private final Map<String, Long> observedPouch = new HashMap<>();

    @Inject
    public ArenaKitStore(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    public synchronized GearKit get(String build)
    {
        if (!valid(build) || !ensureLoaded()) return null;
        return view(build, kits.get(build));
    }

    public synchronized GearKit latest()
    {
        if (!ensureLoaded() || latestBuild == null) return null;
        return view(latestBuild, kits.get(latestBuild));
    }

    public synchronized void put(GearKit kit)
    {
        if (kit == null || !valid(kit.build) || !ensureLoaded()) return;
        GearKit prev = kits.get(kit.build);
        GearKit next = kit;
        if (!next.pouchKnown)
        {
            List<GearKit.Item> pending = pendingPouch.remove(kit.build);
            Long pendingSig = pendingPouchSig.remove(kit.build);
            if (pending != null)
            {
                next = next.withPouch(pending, true, pendingSig == null ? 0L : pendingSig);
            }
            else if (prev != null && prev.pouchKnown && pouchStillValid(kit.build, prev.pouchSig))
            {
                next = next.withPouch(prev.pouch, true, prev.pouchSig);
            }
        }
        kits.put(kit.build, next);
        latestBuild = kit.build;
        if (prev == null || !prev.sameContent(next)) persist(kit.build, next);
    }

    public synchronized void putPouch(String build, List<GearKit.Item> runes, long pouchVarp)
    {
        if (!valid(build) || !ensureLoaded()) return;
        List<GearKit.Item> copy = runes == null ? Collections.<GearKit.Item>emptyList() : new ArrayList<>(runes);
        GearKit prev = kits.get(build);
        if (prev == null)
        {
            pendingPouch.put(build, copy);
            pendingPouchSig.put(build, pouchVarp);
            return;
        }
        GearKit next = prev.withPouch(copy, true, pouchVarp);
        kits.put(build, next);
        if (!prev.sameContent(next)) persist(build, next);
    }

    public synchronized void observe(String build, long loadoutSig, long pouchVarp)
    {
        if (!valid(build)) return;
        observedLoadout.put(build, loadoutSig);
        observedPouch.put(build, pouchVarp);
    }

    public synchronized void clearSession()
    {
        observedLoadout.clear();
        observedPouch.clear();
    }

    private GearKit view(String build, GearKit kit)
    {
        if (kit == null) return null;
        Long loadout = observedLoadout.get(build);
        GearKit out = kit;
        if (loadout != null && loadout != kit.loadoutSig) out = out.asStale();
        if (out.pouchKnown && !pouchStillValid(build, out.pouchSig))
        {
            out = out.withPouch(Collections.<GearKit.Item>emptyList(), false, out.pouchSig);
        }
        return out;
    }

    private boolean pouchStillValid(String build, long pouchSig)
    {
        Long pouch = observedPouch.get(build);
        return pouch == null || pouch == pouchSig;
    }

    private static boolean valid(String build)
    {
        return build != null && GearSet.BUILDS.contains(build);
    }

    private boolean ensureLoaded()
    {
        String profile;
        try
        {
            profile = configManager.getRSProfileKey();
        }
        catch (RuntimeException e)
        {
            return false;
        }
        if (profile == null) return false;
        if (!profile.equals(loadedProfile))
        {
            loadedProfile = profile;
            kits.clear();
            pendingPouch.clear();
            pendingPouchSig.clear();
            latestBuild = null;
            long newest = Long.MIN_VALUE;
            for (String build : GearSet.BUILDS)
            {
                GearKit saved;
                try
                {
                    saved = GearKit.fromJson(configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_PREFIX + build));
                }
                catch (RuntimeException e)
                {
                    saved = null;
                }
                if (saved == null || !build.equals(saved.build)) continue;
                kits.put(build, saved);
                if (saved.readAtMs > newest)
                {
                    newest = saved.readAtMs;
                    latestBuild = build;
                }
            }
        }
        return true;
    }

    private void persist(String build, GearKit kit)
    {
        try
        {
            configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PREFIX + build, kit.toJson());
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] could not save the {} kit: {}", build, e.getMessage());
        }
    }
}
