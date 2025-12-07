package com.pvp.leaderboard.game;

import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.config.PvPLeaderboardConfig.RankBucket;
import com.pvp.leaderboard.service.ClientIdentityService;
import com.pvp.leaderboard.service.CognitoAuthService;
import com.pvp.leaderboard.service.MatchResult;
import com.pvp.leaderboard.service.MatchResultService;
import com.pvp.leaderboard.service.PvPDataService;
import net.runelite.client.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.HitsplatID;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;

@Slf4j
@Singleton
@SuppressWarnings("deprecation")
public class FightMonitor
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final ScheduledExecutorService scheduler;
    private final MatchResultService matchResultService;
    private final PvPDataService pvpDataService;
    private final CognitoAuthService cognitoAuthService;
    private final ClientIdentityService clientIdentityService;
    private final EventBus eventBus;

    // Dependencies injected via init
    // private DashboardPanel dashboardPanel; 
    // private RankOverlay rankOverlay; 

    // --- Fight State ---
    private boolean inFight = false;
    private boolean wasInMulti = false;
    private int fightStartSpellbook = -1;
    private String opponent = null;
    private volatile String lastExactOpponentName = null;
    private long fightStartTime = 0;

    // Tracks multiple simultaneous fights (per-opponent)
    private final ConcurrentHashMap<String, FightEntry> activeFights = new ConcurrentHashMap<>();
    
    // Damage accounting
    private final ConcurrentHashMap<String, Long> damageFromOpponent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> damageToOpponent = new ConcurrentHashMap<>();
    
    // Tick counters
    private int suppressFightStartTicks = 0;
    private final ConcurrentHashMap<String, Integer> perOpponentSuppressUntilTicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> shardPresence = new ConcurrentHashMap<>();
    
    private volatile int lastCombatActivityTick = 0;
    private static final int OUT_OF_COMBAT_TICKS = 16;
    private int gcTicksCounter = 0;

    // Finalization state
    // (removed unused fields)

    @Inject
    public FightMonitor(
        Client client,
        PvPLeaderboardConfig config,
        ScheduledExecutorService scheduler,
        MatchResultService matchResultService,
        PvPDataService pvpDataService,
        CognitoAuthService cognitoAuthService,
        ClientIdentityService clientIdentityService,
        EventBus eventBus)
    {
        this.client = client;
        this.config = config;
        this.scheduler = scheduler;
        this.matchResultService = matchResultService;
        this.pvpDataService = pvpDataService;
        this.cognitoAuthService = cognitoAuthService;
        this.clientIdentityService = clientIdentityService;
        this.eventBus = eventBus;
    }

    public void resetFightState()
    {
        inFight = false;
        wasInMulti = false;
        fightStartSpellbook = -1;
        opponent = null;
        fightStartTime = 0;
        suppressFightStartTicks = 2;
        activeFights.clear();
        damageFromOpponent.clear();
        damageToOpponent.clear();
        lastCombatActivityTick = 0;
        try { log.debug("[Fight] state reset; suppressTicks={}", suppressFightStartTicks); } catch (Exception ignore) {}
    }

    public void handleGameTick(GameTick tick)
    {
        try
        {
            // Handle fight suppression ticks
            if (suppressFightStartTicks > 0)
            {
                suppressFightStartTicks--;
            }

            // Handle per-opponent suppression ticks
            if (!perOpponentSuppressUntilTicks.isEmpty())
            {
                java.util.List<String> toRemove = new java.util.ArrayList<>();
                perOpponentSuppressUntilTicks.forEach((name, ticks) -> {
                    if (ticks <= 1) {
                        toRemove.add(name);
                    } else {
                        perOpponentSuppressUntilTicks.put(name, ticks - 1);
                    }
                });
                toRemove.forEach(perOpponentSuppressUntilTicks::remove);
            }

            // Handle GC (every 33 ticks approx 20s)
            gcTicksCounter++;
            if (gcTicksCounter >= 33)
            {
                gcTicksCounter = 0;
                long now = System.currentTimeMillis();
                activeFights.entrySet().removeIf(e -> {
                    FightEntry fe = e.getValue();
                    if (fe == null) return true;
                    if (!fe.finalized && now - fe.lastActivityMs > 10_000L) {
                        log.debug("[Fight] expire inactive vs={} lastActivity={}msAgo", e.getKey(), now - fe.lastActivityMs);
                        return true;
                    }
                    return false;
                });
                inFight = !activeFights.isEmpty();
            }

            // Combat window idle check
            int tickNow = client.getTickCount();
            if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS && (!damageFromOpponent.isEmpty() || !damageToOpponent.isEmpty()))
            {
                damageFromOpponent.clear();
                damageToOpponent.clear();
                log.debug("[Fight] window cleared on tick={} (idle>{} ticks)", tickNow, OUT_OF_COMBAT_TICKS);
            }
        }
        catch (Exception e)
        {
            log.error("Uncaught exception in FightMonitor.onGameTick", e);
        }
    }

    public void handleHitsplatApplied(HitsplatApplied event)
    {
        try
        {
            if (!(event.getActor() instanceof Player)) return;
            if (client == null || config == null) return;

            Player player = (Player) event.getActor();
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer == null) return;

            String opponentName = null;
            boolean startNow = false;
            int amt = 0;
            boolean startFromThisHit = false;
            boolean isMine = false;
            int hitsplatType = -1;

            net.runelite.api.Hitsplat hs = event.getHitsplat();
            if (hs != null)
            {
                isMine = hs.isMine();
                hitsplatType = hs.getHitsplatType();
                int a = hs.getAmount();
                if (hitsplatType == HitsplatID.DAMAGE_ME || hitsplatType == HitsplatID.DAMAGE_OTHER)
                {
                    amt = a;
                    startFromThisHit = (a > 0);
                }
                else if (hitsplatType == HitsplatID.POISON || hitsplatType == HitsplatID.VENOM)
                {
                    amt = a;
                    startFromThisHit = false;
                }
            }

            if (player == localPlayer)
            {
                // Inbound damage
                opponentName = resolveInboundAttacker(localPlayer);
                if (opponentName != null)
                {
                    lastExactOpponentName = opponentName;
                    if (amt > 0)
                    {
                        damageFromOpponent.merge(opponentName, (long) amt, (a, b) -> a + b);
                    }
                    if (startFromThisHit) startNow = true;
                }
            }
            else
            {
                // Outbound damage
                boolean weAreAttacking = isMine;
                if (!weAreAttacking && (hitsplatType == HitsplatID.POISON || hitsplatType == HitsplatID.VENOM)) {
                    weAreAttacking = (localPlayer.getInteracting() == player);
                }

                if (weAreAttacking)
                {
                    opponentName = player.getName();
                    if (amt > 0)
                    {
                        damageToOpponent.merge(opponentName, (long) amt, (a, b) -> a + b);
                    }
                    if (startFromThisHit) startNow = true;
                }
            }

            boolean validOpp = (opponentName != null && isPlayerOpponent(opponentName));
            if (startNow && opponentName != null && validOpp)
            {
                int tickNow = client.getTickCount();
                if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS)
                {
                    activeFights.clear();
                    damageFromOpponent.clear();
                    log.debug("[Fight] combat window reset after {} ticks idle", tickNow - lastCombatActivityTick);
                }
                lastCombatActivityTick = tickNow;

                if (opponentName.equals(localPlayer.getName())) return; // Ignore self
                if (suppressFightStartTicks > 0) return;
                if (perOpponentSuppressUntilTicks.containsKey(opponentName)) return;

                touchFight(opponentName);
                inFight = true;
                if (!inFight) startFight(opponentName);
                if (client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1) wasInMulti = true;
            }
        }
        catch (Exception e)
        {
            log.debug("Uncaught exception in FightMonitor.onHitsplatApplied", e);
        }
    }

    public void handleActorDeath(ActorDeath event)
    {
        try
        {
            if (!(event.getActor() instanceof Player)) return;
            Player player = (Player) event.getActor();
            Player localPlayer = client.getLocalPlayer();

            if (player == localPlayer)
            {
                // Self death
                String killer = findActualKiller(localPlayer);
                if (killer != null) opponent = killer;

                // Pick killer from damage attribution
                if (killer == null) killer = findKillerByDamage();
                if (killer == null) killer = opponent;
                if (killer == null) killer = mostRecentActiveOpponent();

                if (killer != null) endFightFor(killer, "loss");
                else {
                    activeFights.clear(); inFight = false;
                }
            }
            else
            {
                // Other death
                String name = player.getName();
                if (name != null)
                {
                    boolean isEngagedWithLocal = (player.getInteracting() == localPlayer) || (localPlayer.getInteracting() == player);
                    if (activeFights.containsKey(name) || isEngagedWithLocal)
                    {
                        endFightFor(name, "win");
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.debug("Uncaught exception in FightMonitor.onActorDeath", e);
        }
    }

    private void startFight(String opponentName)
    {
        inFight = true;
        opponent = opponentName;
        wasInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        fightStartSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
        fightStartTime = System.currentTimeMillis() / 1000;
        log.debug("[Fight] startFight opp='{}' world={} startSpell={} multi={}", opponentName, client.getWorld(), fightStartSpellbook, wasInMulti);
    }

    private void endFightFor(String opponentName, String result)
    {
        FightEntry fe = activeFights.remove(opponentName);
        if (fe != null) fe.finalized = true;
        finalizeFight(opponentName, result, fe);
        inFight = !activeFights.isEmpty();
    }

    private void finalizeFight(String opponentName, String result, FightEntry entry)
    {
        final int currentSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
        final int world = client.getWorld();
        final long now = System.currentTimeMillis() / 1000;
        final String selfName = getLocalPlayerName();
        final String idTokenSafe = cognitoAuthService.getStoredIdToken();

        final long startTs = (entry != null) ? entry.startTs : fightStartTime;
        final int startSb = (entry != null) ? entry.startSpellbook : fightStartSpellbook;
        final boolean wasMulti = (entry != null) ? entry.wasInMulti : wasInMulti;
        // bucket logic removed as unused

        final String resolvedOpponent = (opponentName != null) ? opponentName : "Unknown";
        final long dmgOut = damageToOpponent.getOrDefault(resolvedOpponent, 0L);

        log.debug("[Fight] Finalizing: res={} opp={} world={} start={} end={} multi={} dmg={}", result, resolvedOpponent, world, startTs, now, wasMulti, dmgOut);

        // Async Submission
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            submitMatchResult(result, now, selfName, resolvedOpponent, world, startTs, startSb, currentSpellbook, wasMulti, idTokenSafe, dmgOut);
        }, scheduler);

        // Update UI
        // dashboardPanel.getTierLabelByName(resolvedOpponent, bucket); 

        // Suppression
        if (resolvedOpponent != null && !"Unknown".equals(resolvedOpponent)) {
            perOpponentSuppressUntilTicks.put(resolvedOpponent, 5);
        }
        
        // Reset if main fight
        if (entry == null || (opponent != null && opponent.equals(opponentName))) {
            resetFightState();
        }

        // Schedule API refreshes
        scheduleApiRefreshes(resolvedOpponent);
    }

    private void submitMatchResult(String result, long fightEndTime, String playerId, String opponentId, int world,
                                   long fightStartTs, int fightStartSpellbookLocal, int fightEndSpellbookLocal,
                                   boolean wasInMultiLocal, String idTokenLocal, long damageToOpponentLocal)
    {
        MatchResult match = MatchResult.builder()
                .playerId(playerId)
                .opponentId(opponentId)
                .result(result)
                .world(world)
                .fightStartTs(fightStartTs)
                .fightEndTs(fightEndTime)
                .fightStartSpellbook(getSpellbookName(fightStartSpellbookLocal))
                .fightEndSpellbook(getSpellbookName(fightEndSpellbookLocal))
                .wasInMulti(wasInMultiLocal)
                .idToken(idTokenLocal)
                .damageToOpponent(damageToOpponentLocal)
                .clientUniqueId(clientIdentityService.getClientUniqueId())
                .build();

        matchResultService.submitMatchResult(match).thenAccept(success -> {
             if (success) log.debug("Match result submitted");
        });
    }

    private void scheduleApiRefreshes(String opponentName) {
         // Always fetch fresh ranks after a fight so players see rank changes immediately
         // API results persist until shard is refreshed (1 hour)
         log.debug("[Fight] Scheduling API refresh in 15s for self and opponent={}", opponentName);
         scheduler.schedule(() -> {
             try {
                String bucket = bucketKey(config.rankBucket());
                String selfName = getLocalPlayerName();
                log.debug("[Fight] Executing API refresh: self={} opponent={} bucket={}", selfName, opponentName, bucket);
                
                // Refresh self rank using profile API (more reliable)
                if (selfName != null) {
                    pvpDataService.getTierFromProfile(selfName, bucket).thenAccept(tier -> {
                        log.debug("[Fight] Profile API returned tier={} for self={}", tier, selfName);
                        if (tier != null) {
                            eventBus.post(new PlayerRankEvent(selfName, bucket, tier));
                        }
                    });
                }
                
                // Refresh opponent rank using profile API
                if (opponentName != null) {
                    pvpDataService.getTierFromProfile(opponentName, bucket).thenAccept(tier -> {
                        log.debug("[Fight] Profile API returned tier={} for opponent={}", tier, opponentName);
                        if (tier != null) {
                            eventBus.post(new PlayerRankEvent(opponentName, bucket, tier));
                        }
                    });
                }
             } catch(Exception e){
                log.debug("[Fight] API refresh exception", e);
             }
         }, 15L, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void touchFight(String opponentName)
    {
        if (opponentName == null || opponentName.isEmpty()) return;
        long ts = System.currentTimeMillis() / 1000;
        int sb = client.getVarbitValue(Varbits.SPELLBOOK);
        boolean multi = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        activeFights.compute(opponentName, (k, v) -> {
            if (v == null) return new FightEntry(ts, sb, multi);
            v.lastActivityMs = System.currentTimeMillis();
            return v;
        });

        if (!shardPresence.containsKey(opponentName))
        {
            String bucket = bucketKey(config.rankBucket());
            
            // Single call - getShardRankByName handles everything internally
            pvpDataService.getShardRankByName(opponentName, bucket).thenAccept(shardRank -> {
                if (shardRank != null && shardRank.rank > 0) {
                    shardPresence.put(opponentName, Boolean.TRUE);
                    if (shardRank.tier != null) {
                        eventBus.post(new PlayerRankEvent(opponentName, bucket, shardRank.tier));
                    }
                } else {
                    shardPresence.putIfAbsent(opponentName, Boolean.FALSE);
                }
            }).exceptionally(ex -> {
                shardPresence.putIfAbsent(opponentName, Boolean.FALSE);
                return null;
            });
        }
    }
    
    // --- Helpers ---

    private String resolveInboundAttacker(Player localPlayer)
    {
        List<Player> players = client.getPlayers();
        if (players != null) {
            for (Player other : players) {
                if (other != null && other != localPlayer && other.getInteracting() == localPlayer) {
                    return other.getName();
                }
            }
        }
        String recent = mostRecentActiveOpponent();
        if (recent != null) return recent;
        return lastExactOpponentName;
    }

    private String mostRecentActiveOpponent()
    {
        long best = -1L; String bestName = null;
        for (Map.Entry<String, FightEntry> e : activeFights.entrySet())
        {
            if (e.getValue() == null) continue;
            long la = e.getValue().lastActivityMs;
            if (la > best) { best = la; bestName = e.getKey(); }
        }
        return bestName;
    }

    private String findKillerByDamage()
    {
        String killer = null; long bestDmg = -1L;
        for (Map.Entry<String, Long> e : damageFromOpponent.entrySet()) {
            long v = (e.getValue() != null ? e.getValue() : 0L);
            if (v > bestDmg) { bestDmg = v; killer = e.getKey(); }
        }
        return killer;
    }

    private String findActualKiller(Player localPlayer)
    {
        List<Player> players = client.getPlayers();
        if (players == null) return null;
        for (Player player : players) {
            if (player != localPlayer && player.getInteracting() == localPlayer) return player.getName();
        }
        return null;
    }

    private boolean isPlayerOpponent(String name)
    {
        if (name == null || "Unknown".equals(name)) return false;
        List<Player> players = client.getPlayers();
        if (players == null) return false;
        for (Player player : players) {
            String pName = player.getName();
            if (pName != null && pName.equals(name)) return true;
        }
        return false;
    }
    
    private String getLocalPlayerName()
    {
        try { return client != null && client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null; } catch (Exception ignore) { return null; }
    }

    private String getSpellbookName(int spellbook)
    {
        switch (spellbook) {
            case 0: return "Standard";
            case 1: return "Ancient";
            case 2: return "Lunar";
            case 3: return "Arceuus";
            default: return "Unknown";
        }
    }
    
    private static String bucketKey(RankBucket bucket)
    {
        if (bucket == null) return "overall";
        switch (bucket)
        {
            case NH: return "nh";
            case VENG: return "veng";
            case MULTI: return "multi";
            case DMM: return "dmm";
            case OVERALL:
            default: return "overall";
        }
    }

    private static class FightEntry {
        final long startTs;
        final int startSpellbook;
        final boolean wasInMulti;
        volatile long lastActivityMs;
        volatile boolean finalized = false;
        FightEntry(long ts, int sb, boolean multi) {
            startTs = ts;
            startSpellbook = sb;
            wasInMulti = multi;
            lastActivityMs = System.currentTimeMillis();
        }
    }
}
