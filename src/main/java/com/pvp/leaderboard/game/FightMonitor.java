package com.pvp.leaderboard.game;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.overlay.RankOverlay;
import com.pvp.leaderboard.service.ClientIdentityService;
import com.pvp.leaderboard.service.CognitoAuthService;
import com.pvp.leaderboard.service.MatchResult;
import com.pvp.leaderboard.service.MatchResultService;
import com.pvp.leaderboard.service.PvPDataService;
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

    // RankOverlay reference for MMR notifications
    private RankOverlay rankOverlay;

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

    // MMR tracking for notifications
    private volatile Double preFightMmr = null;

    @Inject
    public FightMonitor(
        Client client,
        PvPLeaderboardConfig config,
        ScheduledExecutorService scheduler,
        MatchResultService matchResultService,
        PvPDataService pvpDataService,
        CognitoAuthService cognitoAuthService,
        ClientIdentityService clientIdentityService)
    {
        this.client = client;
        this.config = config;
        this.scheduler = scheduler;
        this.matchResultService = matchResultService;
        this.pvpDataService = pvpDataService;
        this.cognitoAuthService = cognitoAuthService;
        this.clientIdentityService = clientIdentityService;
    }

    /**
     * Initialize with RankOverlay for MMR change notifications.
     */
    public void init(RankOverlay rankOverlay)
    {
        this.rankOverlay = rankOverlay;
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
        preFightMmr = null;
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
            }
        }
        catch (Exception e)
        {
            // log.error("Uncaught exception in FightMonitor.onGameTick", e);
        }
    }

    public void handleHitsplatApplied(HitsplatApplied event)
    {
        try
        {
            if (!(event.getActor() instanceof Player)) return;
            if (client == null || config == null) return;

            Player hitPlayer = (Player) event.getActor();
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer == null) return;

            net.runelite.api.Hitsplat hs = event.getHitsplat();
            if (hs == null) return;

            int hitsplatType = hs.getHitsplatType();
            int amt = hs.getAmount();
            boolean isMine = hs.isMine();
            String hitPlayerName = hitPlayer.getName();
            String localPlayerName = localPlayer.getName();

            // Only process relevant damage hitsplat types
            if (amt > 0)
            {
                if (!(hitsplatType == HitsplatID.DAMAGE_ME
                    || hitsplatType == HitsplatID.DAMAGE_OTHER
                    || hitsplatType == HitsplatID.POISON
                    || hitsplatType == HitsplatID.VENOM))
                {
                    return;
                }
            }

            // Determine if this should start/continue a fight
            boolean startFromThisHit = (amt > 0) && 
                (hitsplatType == HitsplatID.DAMAGE_ME || hitsplatType == HitsplatID.DAMAGE_OTHER);

            String opponentName = null;
            boolean startNow = false;

            if (hitPlayer == localPlayer)
            {
                // Hitsplat on us = opponent dealt damage to us
                opponentName = resolveInboundAttacker(localPlayer);
                if (opponentName != null)
                {
                    lastExactOpponentName = opponentName;
                    if (startFromThisHit) startNow = true;
                }
            }
            else
            {
                // Hitsplat on another player
                if (hitPlayerName == null) return;

                boolean isActiveOpponent = activeFights.containsKey(hitPlayerName);
                Player interacting = (Player) localPlayer.getInteracting();
                boolean isCurrentTarget = interacting != null && interacting == hitPlayer;
                Player theirTarget = (Player) hitPlayer.getInteracting();
                boolean isTargetingUs = theirTarget != null && theirTarget == localPlayer;

                if (isActiveOpponent || isCurrentTarget || isTargetingUs)
                {
                    opponentName = hitPlayerName;
                    if (startFromThisHit) startNow = true;
                }
            }

            // Handle fight start/continuation
            boolean validOpp = (opponentName != null && isPlayerOpponent(opponentName));
            if (startNow && opponentName != null && validOpp)
            {
                int tickNow = client.getTickCount();
                
                // Check for combat window reset FIRST
                if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS)
                {
                    activeFights.clear();
                    damageFromOpponent.clear();
                    damageToOpponent.clear();
                }
                lastCombatActivityTick = tickNow;

                if (opponentName.equals(localPlayer.getName())) return;
                if (suppressFightStartTicks > 0) return;
                if (perOpponentSuppressUntilTicks.containsKey(opponentName)) return;

                touchFight(opponentName);
                inFight = true;
                if (!inFight) startFight(opponentName);
                if (client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1) wasInMulti = true;
            }

            // Add the damage to maps
            if (opponentName != null && amt > 0)
            {
                if (hitPlayer == localPlayer)
                {
                    damageFromOpponent.merge(opponentName, (long) amt, Long::sum);
                }
                else
                {
                    damageToOpponent.merge(opponentName, (long) amt, Long::sum);
                }
            }
        }
        catch (Exception e)
        {
            // log.debug("Uncaught exception in FightMonitor.onHitsplatApplied", e);
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
            // log.debug("Uncaught exception in FightMonitor.onActorDeath", e);
        }
    }

    private void startFight(String opponentName)
    {
        inFight = true;
        opponent = opponentName;
        wasInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        fightStartSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
        fightStartTime = System.currentTimeMillis() / 1000;
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

        final String resolvedOpponent = (opponentName != null) ? opponentName : "Unknown";
        final long dmgOut = damageToOpponent.getOrDefault(resolvedOpponent, 0L);

        // Async Submission
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                submitMatchResult(result, now, selfName, resolvedOpponent, world, startTs, startSb, currentSpellbook, wasMulti, idTokenSafe, dmgOut);
            } catch (Exception e) {
                log.debug("[MatchSubmit] EXCEPTION in async submission: {}", e.getMessage(), e);
            }
        }, scheduler);

        // Suppression
        if (resolvedOpponent != null && !"Unknown".equals(resolvedOpponent)) {
            perOpponentSuppressUntilTicks.put(resolvedOpponent, 5);
        }
        
        // Reset if main fight
        if (entry == null || (opponent != null && opponent.equals(opponentName))) {
            resetFightState();
        }

        // Schedule API refreshes (includes MMR notification)
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

        log.debug("[MatchSubmit] Submitting: player={} opponent={} result={} world={} startTs={} endTs={} dmgOut={} multi={} hasToken={}",
                playerId, opponentId, result, world, fightStartTs, fightEndTime, damageToOpponentLocal, wasInMultiLocal, (idTokenLocal != null && !idTokenLocal.isEmpty()));

        matchResultService.submitMatchResult(match).thenAccept(success -> {
            if (success) {
                log.debug("[MatchSubmit] SUCCESS: match submitted for player={} vs opponent={} result={}", playerId, opponentId, result);
            } else {
                log.debug("[MatchSubmit] FAILED: match submission failed for player={} vs opponent={} result={}", playerId, opponentId, result);
            }
        });
    }

    private void scheduleApiRefreshes(String opponentName) {
        log.debug("[PostFight] Scheduling API refresh in 15s for self and opponent={}", opponentName);
        
        // Store pre-fight MMR before the refresh happens
        final Double storedPreFightMmr = preFightMmr;
        
        scheduler.schedule(() -> {
            try {
                String bucket = "overall";
                String selfName = getLocalPlayerName();
                log.debug("[PostFight] Executing API refresh: self={} opponent={} bucket={}", selfName, opponentName, bucket);
                
                // Refresh self rank using profile API with MMR notification
                if (selfName != null) {
                    fetchProfileWithMmrNotification(selfName, bucket, 3, storedPreFightMmr);
                } else {
                    log.debug("[PostFight] selfName is null, skipping self refresh");
                }
                
                // Refresh opponent rank
                if (opponentName != null) {
                    fetchTierWithRetry(opponentName, bucket, 3);
                } else {
                    log.debug("[PostFight] opponentName is null, skipping opponent refresh");
                }
            } catch(Exception e){
                log.debug("[PostFight] API refresh exception: {}", e.getMessage());
            }
        }, 15L, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Fetch profile for self with MMR change notification support.
     */
    private void fetchProfileWithMmrNotification(String playerName, String bucket, int retriesLeft, Double oldMmr) {
        log.debug("[PostFight] fetchProfileWithMmrNotification: player={} bucket={} retriesLeft={} oldMmr={}", 
            playerName, bucket, retriesLeft, oldMmr);
        
        pvpDataService.getUserProfile(playerName, null, true).thenAccept(profile -> {
            if (profile != null) {
                // Extract and set tier in overlay
                String tier = extractTierFromProfile(profile);
                if (tier != null && rankOverlay != null) {
                    rankOverlay.setRankFromApi(playerName, tier);
                    log.debug("[PostFight] Set rank from API: player={} tier={}", playerName, tier);
                }
                
                // Handle MMR change notification
                if (oldMmr != null && config.showMmrChangeNotification() && rankOverlay != null) {
                    if (profile.has("mmr") && !profile.get("mmr").isJsonNull()) {
                        double newMmr = profile.get("mmr").getAsDouble();
                        rankOverlay.onMmrUpdated(newMmr, oldMmr);
                        log.debug("[PostFight] MMR notification triggered: old={} new={} delta={}", 
                            oldMmr, newMmr, newMmr - oldMmr);
                    }
                }
            } else if (retriesLeft > 0) {
                log.debug("[PostFight] profile is null for player={}, retrying ({} left)", playerName, retriesLeft - 1);
                scheduler.schedule(() -> fetchProfileWithMmrNotification(playerName, bucket, retriesLeft - 1, oldMmr), 
                    2L, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] FAILED: profile is null for player={}, no retries left", playerName);
            }
        }).exceptionally(ex -> {
            if (retriesLeft > 0) {
                log.debug("[PostFight] Exception for player={}: {}, retrying ({} left)", playerName, ex.getMessage(), retriesLeft - 1);
                scheduler.schedule(() -> fetchProfileWithMmrNotification(playerName, bucket, retriesLeft - 1, oldMmr), 
                    2L, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] FAILED with exception for player={}: {}, no retries left", playerName, ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Extract tier string from user profile response.
     */
    private String extractTierFromProfile(JsonObject profile) {
        if (profile == null) return null;
        
        String rank = null;
        int division = 0;
        
        if (profile.has("rank") && !profile.get("rank").isJsonNull()) {
            rank = profile.get("rank").getAsString();
        }
        if (profile.has("division") && !profile.get("division").isJsonNull()) {
            division = profile.get("division").getAsInt();
        }
        
        if (rank != null && !rank.isEmpty()) {
            return division > 0 ? rank + " " + division : rank;
        }
        return null;
    }
    
    private void fetchTierWithRetry(String playerName, String bucket, int retriesLeft) {
        log.debug("[PostFight] fetchTierWithRetry called: player={} bucket={} retriesLeft={}", playerName, bucket, retriesLeft);
        pvpDataService.getTierFromProfile(playerName, bucket).thenAccept(tier -> {
            if (tier != null) {
                log.debug("[PostFight] SUCCESS: tier={} for player={}", tier, playerName);
                if (rankOverlay != null) {
                    rankOverlay.setRankFromApi(playerName, tier);
                }
            } else if (retriesLeft > 0) {
                log.debug("[PostFight] tier is null for player={}, retrying ({} left)", playerName, retriesLeft - 1);
                scheduler.schedule(() -> fetchTierWithRetry(playerName, bucket, retriesLeft - 1), 
                    2L, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] FAILED: tier is null for player={}, no retries left", playerName);
            }
        }).exceptionally(ex -> {
            if (retriesLeft > 0) {
                log.debug("[PostFight] Exception for player={}: {}, retrying ({} left)", playerName, ex.getMessage(), retriesLeft - 1);
                scheduler.schedule(() -> fetchTierWithRetry(playerName, bucket, retriesLeft - 1), 
                    2L, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] FAILED with exception for player={}: {}, no retries left", playerName, ex.getMessage());
            }
            return null;
        });
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

        // Store pre-fight MMR for self (only once per fight session)
        if (preFightMmr == null && config.showMmrChangeNotification()) {
            String selfName = getLocalPlayerName();
            if (selfName != null) {
                pvpDataService.getUserProfile(selfName, null).thenAccept(profile -> {
                    if (profile != null && profile.has("mmr") && !profile.get("mmr").isJsonNull()) {
                        preFightMmr = profile.get("mmr").getAsDouble();
                        log.debug("[Fight] Stored pre-fight MMR for {}: {}", selfName, preFightMmr);
                    }
                });
            }
        }

        if (!shardPresence.containsKey(opponentName))
        {
            String bucket = "overall";
            
            pvpDataService.getShardRankByName(opponentName, bucket).thenAccept(shardRank -> {
                if (shardRank != null && shardRank.rank > 0) {
                    shardPresence.put(opponentName, Boolean.TRUE);
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
