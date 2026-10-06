package com.pvp.leaderboard.game;

import com.pvp.leaderboard.tournament.*;
import java.util.*;
import javax.inject.*;
import net.runelite.client.config.*;

@Singleton
public class KitStore
{
    static final String KEY_PREFIX = "gearKit.";

    private final ConfigManager configManager;

    private String loadedProfile;
    private final Map<String, GearKit> kits = new HashMap<>();
    private final Map<String, List<GearItem>> pendingPouch = new HashMap<>();
    private final Map<String, Long> pouchSigs = new HashMap<>();
    private String latestBuild;
    private final Map<String, long[]> observed = new HashMap<>();

    @Inject
    public KitStore(ConfigManager configManager)
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
            List<GearItem> pending = pendingPouch.remove(kit.build);
            Long pendingSig = pouchSigs.remove(kit.build);
            if (pending != null)
            {
                next = next.withPouch(pending, true, pendingSig == null ? 0L : pendingSig);
            }
            else if (prev != null && prev.pouchKnown && pouchValid(kit.build, prev.pouchSig))
            {
                next = next.withPouch(prev.pouch, true, prev.pouchSig);
            }
        }
        kits.put(kit.build, next);
        latestBuild = kit.build;
        if (prev == null || !prev.sameContent(next)) persist(kit.build, next);
    }

    public synchronized void putPouch(String build, List<GearItem> runes, long pouchVarp)
    {
        if (!valid(build) || !ensureLoaded()) return;
        List<GearItem> copy = runes == null ? Collections.<GearItem>emptyList() : new ArrayList<>(runes);
        GearKit prev = kits.get(build);
        if (prev == null)
        {
            pendingPouch.put(build, copy);
            pouchSigs.put(build, pouchVarp);
            return;
        }
        GearKit next = prev.withPouch(copy, true, pouchVarp);
        kits.put(build, next);
        if (!prev.sameContent(next)) persist(build, next);
    }

    public synchronized void observe(String build, long loadoutSig, long pouchVarp)
    {
        if (!valid(build)) return;
        observed.put(build, new long[]{loadoutSig, pouchVarp});
    }

    public synchronized void clearSession()
    {
        observed.clear();
    }

    private GearKit view(String build, GearKit kit)
    {
        if (kit == null) return null;
        long[] o = observed.get(build);
        GearKit out = kit;
        if (o != null && o[0] != kit.loadoutSig) out = out.asStale();
        if (out.pouchKnown && !pouchValid(build, out.pouchSig))
        {
            out = out.withPouch(Collections.<GearItem>emptyList(), false, out.pouchSig);
        }
        return out;
    }

    private boolean pouchValid(String build, long pouchSig)
    {
        long[] o = observed.get(build);
        return o == null || o[1] == pouchSig;
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
            pouchSigs.clear();
            latestBuild = null;
            long newest = Long.MIN_VALUE;
            for (String build : GearSet.BUILDS)
            {
                GearKit saved;
                try
                {
                    saved = GearKit.fromJson(configManager.getRSProfileConfiguration(FightMonitor.CONFIG_GROUP, KEY_PREFIX + build));
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
            configManager.setRSProfileConfiguration(FightMonitor.CONFIG_GROUP, KEY_PREFIX + build, kit.toJson());
        }
        catch (RuntimeException e)
        {
        }
    }
}
