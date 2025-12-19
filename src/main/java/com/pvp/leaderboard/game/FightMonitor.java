package com.pvp.leaderboard.game;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.overlay.RankOverlay;
import com.pvp.leaderboard.service.ClientIdentityService;
import com.pvp.leaderboard.service.CognitoAuthService;
import com.pvp.leaderboard.service.MatchResult;
import com.pvp.leaderboard.service.MatchResultService;
import com.pvp.leaderboard.service.PvPDataService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
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

    // Tracks multiple simultaneous fights (per-opponent) - damage is now tracked per-FightEntry
    private final ConcurrentHashMap<String, FightEntry> activeFights = new ConcurrentHashMap<>();
    
    // Tick counters
    private int suppressFightStartTicks = 0;
    private final ConcurrentHashMap<String, Integer> perOpponentSuppressUntilTicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> shardPresence = new ConcurrentHashMap<>();
    
    private volatile int lastCombatActivityTick = 0;
    private static final int OUT_OF_COMBAT_TICKS = 16;
    private int gcTicksCounter = 0;

    // MMR tracking - no longer using pre-fight profile, using match history API instead

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
        perOpponentSuppressUntilTicks.clear();
        shardPresence.clear();
        lastCombatActivityTick = 0;
        lastExactOpponentName = null;
        try { log.debug("[Fight] state reset; suppressTicks={}", suppressFightStartTicks); } catch (Exception ignore) {}
    }
    
    /**
     * Clear only a specific fight without affecting other ongoing fights.
     * Used when a fight ends but other multi-combat fights continue.
     */
    private void clearFightFor(String opponentName)
    {
        activeFights.remove(opponentName);
        perOpponentSuppressUntilTicks.put(opponentName, 5);
        shardPresence.remove(opponentName);
        
        // Only reset global state if ALL fights are done
        if (activeFights.isEmpty())
        {
            inFight = false;
            wasInMulti = false;
            fightStartSpellbook = -1;
            opponent = null;
            fightStartTime = 0;
            suppressFightStartTicks = 2;
        }
        else
        {
            inFight = true;
        }
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

            // Combat window idle check - individual fight expiration is handled by GC above
            // No global damage maps to clear anymore; damage is per-FightEntry
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
                
                // Check for combat window reset FIRST - clear stale fights after long idle
                if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS)
                {
                    activeFights.clear();
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

            // Add the damage to per-fight tracking in FightEntry
            if (opponentName != null && amt > 0)
            {
                FightEntry fe = activeFights.get(opponentName);
                if (fe != null)
                {
                    if (hitPlayer == localPlayer)
                    {
                        fe.addDamageReceived(amt);
                    }
                    else
                    {
                        fe.addDamageDealt(amt);
                    }
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
                // Self death - find who killed us
                String killer = findActualKiller(localPlayer);
                if (killer != null) opponent = killer;

                if (killer == null) killer = findKillerByDamage();
                if (killer == null) killer = opponent;
                if (killer == null) killer = mostRecentActiveOpponent();

                // Submit loss against the killer
                if (killer != null)
                {
                    endFightFor(killer, "loss");
                }
                
                // Clear remaining fights without submitting (we died, they didn't kill us)
                for (String remaining : new ArrayList<>(activeFights.keySet()))
                {
                    clearFightFor(remaining);
                }
                
                // Ensure full reset after self-death
                resetFightState();
            }
            else
            {
                // Other player death
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
        FightEntry fe = activeFights.get(opponentName);
        if (fe == null || fe.finalized) return;
        
        fe.finalized = true;
        finalizeFight(opponentName, result, fe);
        clearFightFor(opponentName);  // Use targeted clear instead of resetFightState
    }

    private void finalizeFight(String opponentName, String result, FightEntry entry)
    {
        if (entry == null) return;  // Require valid FightEntry for accurate data
        
        final int currentSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
        final int world = client.getWorld();
        final long now = System.currentTimeMillis() / 1000;
        final String selfName = getLocalPlayerName();
        final String idTokenSafe = cognitoAuthService.getStoredIdToken();

        final long startTs = entry.startTs;
        
        // Skip submission if start timestamp wasn't captured (fight was already in progress)
        if (startTs <= 0)
        {
            log.debug("[MatchSubmit] Skipping - fight start not captured (startTs={}). Plugin likely started tracking mid-fight.", startTs);
            return;
        }
        final int startSb = entry.startSpellbook;
        final boolean wasMulti = entry.wasInMulti;

        final String resolvedOpponent = (opponentName != null) ? opponentName : "Unknown";
        final long dmgOut = entry.damageDealt.get();  // Read from FightEntry

        // Determine the bucket this fight will be classified as (server-side logic)
        final String startSpellbookName = getSpellbookName(startSb);
        final String endSpellbookName = getSpellbookName(currentSpellbook);
        final String fightBucket = determineBucket(world, wasMulti, startSpellbookName, endSpellbookName);
        
        log.debug("[MatchSubmit] Determined bucket: {} (world={} multi={} startSb={} endSb={})", 
            fightBucket, world, wasMulti, startSpellbookName, endSpellbookName);

        // Async Submission
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                submitMatchResult(result, now, selfName, resolvedOpponent, world, startTs, startSb, currentSpellbook, wasMulti, idTokenSafe, dmgOut);
            } catch (Exception e) {
                log.debug("[MatchSubmit] EXCEPTION in async submission: {}", e.getMessage(), e);
            }
        }, scheduler);

        // Schedule API refreshes (includes MMR notification) - pass the fight bucket
        scheduleApiRefreshes(resolvedOpponent, fightBucket);
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

    private void scheduleApiRefreshes(String opponentName, String fightBucket) {
        log.debug("[PostFight] Scheduling API refresh in 5s for self and opponent={} fightBucket={}", opponentName, fightBucket);
        
        // Get the user's configured display bucket for rank display
        final String displayBucket = getConfigBucketKey();
        
        scheduler.schedule(() -> {
            try {
                String selfName = getLocalPlayerName();
                log.debug("[PostFight] Executing API refresh: self={} opponent={} displayBucket={} fightBucket={}", 
                    selfName, opponentName, displayBucket, fightBucket);
                
                if (selfName != null) {
                    // Refresh rank display using the user's configured bucket
                    fetchTierWithRetry(selfName, displayBucket, 3);
                    
                    // Get MMR delta from match history (more accurate than profile diff)
                    // First attempt at 5s, second attempt at 15s if first fails (1 retry with 10s delay)
                    if (config.showMmrChangeNotification() && opponentName != null) {
                        fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, 1);
                    }
                } else {
                    log.debug("[PostFight] selfName is null, skipping self refresh");
                }
                
                // Refresh opponent rank using the user's display bucket
                if (opponentName != null) {
                    fetchTierWithRetry(opponentName, displayBucket, 3);
                } else {
                    log.debug("[PostFight] opponentName is null, skipping opponent refresh");
                }
            } catch(Exception e){
                log.debug("[PostFight] API refresh exception: {}", e.getMessage());
            }
        }, 5L, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Fetch MMR delta from match history API.
     * This is more accurate than calculating from profile differences.
     * First attempt at ~5s after fight, second (final) attempt at ~15s if first fails.
     */
    private void fetchMmrDeltaFromMatchHistory(String selfName, String opponentName, String displayBucket, int retriesLeft) {
        log.debug("[PostFight] Fetching MMR delta from match history: self={} opponent={} retriesLeft={}", 
            selfName, opponentName, retriesLeft);
        
        // Fetch more matches to handle multi-kill scenarios (up to 10 kills in quick succession)
        // Bypass cache to ensure we get fresh data with the new match
        pvpDataService.getPlayerMatches(selfName, null, 15, true).thenAccept(response -> {
            if (response != null && response.has("matches") && response.get("matches").isJsonArray()) {
                var matches = response.getAsJsonArray("matches");
                
                // Find the most recent match against this opponent
                for (var element : matches) {
                    if (!element.isJsonObject()) continue;
                    JsonObject match = element.getAsJsonObject();
                    
                    String matchOpponent = match.has("opponent_id") && !match.get("opponent_id").isJsonNull() 
                        ? match.get("opponent_id").getAsString() : null;
                    
                    // Check if this match is against our opponent (case-insensitive)
                    if (matchOpponent != null && matchOpponent.equalsIgnoreCase(opponentName)) {
                        // Found the match - extract MMR delta and bucket
                        if (match.has("rating_change") && match.get("rating_change").isJsonObject()) {
                            JsonObject ratingChange = match.getAsJsonObject("rating_change");
                            
                            if (ratingChange.has("mmr_delta") && !ratingChange.get("mmr_delta").isJsonNull()) {
                                double mmrDelta = ratingChange.get("mmr_delta").getAsDouble();
                                
                                // Get the actual bucket from the match
                                String matchBucket = match.has("bucket") && !match.get("bucket").isJsonNull()
                                    ? match.get("bucket").getAsString() : "nh";
                                
                                // Determine if we should show bucket label
                                String bucketLabel = null;
                                if (!matchBucket.equalsIgnoreCase(displayBucket) || "overall".equalsIgnoreCase(displayBucket)) {
                                    bucketLabel = getBucketDisplayName(matchBucket);
                                }
                                
                                // Check result to determine if delta should be negative
                                String result = match.has("result") && !match.get("result").isJsonNull()
                                    ? match.get("result").getAsString() : "win";
                                if ("loss".equalsIgnoreCase(result)) {
                                    mmrDelta = -Math.abs(mmrDelta);
                                }
                                
                                log.debug("[PostFight] Found match in history: opponent={} bucket={} mmrDelta={} result={} bucketLabel={}", 
                                    matchOpponent, matchBucket, mmrDelta, result, bucketLabel);
                                
                                // Trigger the notification
                                if (rankOverlay != null) {
                                    rankOverlay.showMmrDelta(mmrDelta, bucketLabel);
                                }
                                return;
                            }
                        }
                        
                        log.debug("[PostFight] Found match but no rating_change data");
                        return;
                    }
                }
                
                // Match not found yet - retry if we have retries left (retry after 10s so total is ~15s)
                if (retriesLeft > 0) {
                    log.debug("[PostFight] Match not found in history, retrying in 10s ({} left)", retriesLeft - 1);
                    scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, retriesLeft - 1),
                        10L, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    log.debug("[PostFight] Match not found in history after all retries, skipping MMR notification");
                }
            } else if (retriesLeft > 0) {
                log.debug("[PostFight] No matches in response, retrying in 10s ({} left)", retriesLeft - 1);
                scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, retriesLeft - 1),
                    10L, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] Failed to get matches after all retries");
            }
        }).exceptionally(ex -> {
            if (retriesLeft > 0) {
                log.debug("[PostFight] Match history exception: {}, retrying in 10s ({} left)", ex.getMessage(), retriesLeft - 1);
                scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, retriesLeft - 1),
                    10L, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] Match history failed after all retries: {}", ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Get the bucket key from user config for API calls.
     */
    private String getConfigBucketKey() {
        if (config == null) return "overall";
        var bucket = config.rankBucket();
        if (bucket == null) return "overall";
        switch (bucket) {
            case NH: return "nh";
            case VENG: return "veng";
            case MULTI: return "multi";
            case DMM: return "dmm";
            case OVERALL:
            default: return "overall";
        }
    }

    /**
     * Extract tier string from user profile response for a specific bucket.
     */
    private String extractTierFromProfile(JsonObject profile, String bucket) {
        if (profile == null) return null;
        
        // Try bucket-specific data first
        if (bucket != null && !bucket.isEmpty() && !"overall".equalsIgnoreCase(bucket)) {
            if (profile.has("buckets") && profile.get("buckets").isJsonObject()) {
                JsonObject buckets = profile.getAsJsonObject("buckets");
                if (buckets.has(bucket) && buckets.get(bucket).isJsonObject()) {
                    JsonObject bucketData = buckets.getAsJsonObject(bucket);
                    String tier = extractTierFromBucketData(bucketData);
                    if (tier != null) return tier;
                }
            }
        }
        
        // Fallback to top-level (overall) data
        return extractTierFromBucketData(profile);
    }

    /**
     * Extract tier from a bucket data object.
     */
    private String extractTierFromBucketData(JsonObject data) {
        if (data == null) return null;
        
        String rank = null;
        int division = 0;
        
        if (data.has("rank") && !data.get("rank").isJsonNull()) {
            rank = data.get("rank").getAsString();
        }
        if (data.has("division") && !data.get("division").isJsonNull()) {
            division = data.get("division").getAsInt();
        }
        
        if (rank != null && !rank.isEmpty()) {
            return division > 0 ? rank + " " + division : rank;
        }
        return null;
    }

    /**
     * Extract MMR from user profile response for a specific bucket.
     */
    private Double extractMmrFromProfile(JsonObject profile, String bucket) {
        return extractDoubleFieldFromProfile(profile, bucket, "mmr");
    }

    /**
     * Extract Sigma (TrueSkill uncertainty) from user profile response for a specific bucket.
     * Each bucket maintains separate TrueSkill values since they act as separate leaderboards.
     */
    private Double extractSigmaFromProfile(JsonObject profile, String bucket) {
        return extractDoubleFieldFromProfile(profile, bucket, "sigma");
    }

    /**
     * Generic helper to extract a Double field from user profile for a specific bucket.
     * Tries bucket-specific data first, falls back to top-level.
     */
    private Double extractDoubleFieldFromProfile(JsonObject profile, String bucket, String fieldName) {
        if (profile == null || fieldName == null) return null;
        
        // Try bucket-specific data first
        if (bucket != null && !bucket.isEmpty() && !"overall".equalsIgnoreCase(bucket)) {
            if (profile.has("buckets") && profile.get("buckets").isJsonObject()) {
                JsonObject buckets = profile.getAsJsonObject("buckets");
                if (buckets.has(bucket) && buckets.get(bucket).isJsonObject()) {
                    JsonObject bucketData = buckets.getAsJsonObject(bucket);
                    if (bucketData.has(fieldName) && !bucketData.get(fieldName).isJsonNull()) {
                        return bucketData.get(fieldName).getAsDouble();
                    }
                }
            }
        }
        
        // Fallback to top-level field
        if (profile.has(fieldName) && !profile.get(fieldName).isJsonNull()) {
            return profile.get(fieldName).getAsDouble();
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
        String killer = null;
        long bestDmg = -1L;
        for (Map.Entry<String, FightEntry> e : activeFights.entrySet())
        {
            FightEntry fe = e.getValue();
            if (fe == null) continue;
            long v = fe.damageReceived.get();
            if (v > bestDmg)
            {
                bestDmg = v;
                killer = e.getKey();
            }
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
        if (client == null)
        {
            return null;
        }

        try
        {
            var localPlayer = client.getLocalPlayer();
            if (localPlayer == null)
            {
                return null;
            }
            return localPlayer.getName();
        }
        catch (Exception e)
        {
            log.debug("Failed to get local player name", e);
            return null;
        }
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

    /**
     * Determine the bucket for a fight based on server-side logic.
     * Priority: DMM > Multi > Veng > NH
     * 
     * @param world The world number
     * @param wasInMulti Whether the fight was in multi-combat
     * @param startSpellbook The spellbook name at fight start
     * @param endSpellbook The spellbook name at fight end
     * @return The bucket name: "dmm", "multi", "veng", or "nh"
     */
    private String determineBucket(int world, boolean wasInMulti, String startSpellbook, String endSpellbook)
    {
        // 1. DMM check - uses cached DMM worlds from PvPDataService
        if (pvpDataService.isDmmWorld(world))
        {
            return "dmm";
        }

        // 2. Multi check
        if (wasInMulti)
        {
            return "multi";
        }

        // 3. Veng check - both start AND end spellbook must be Lunar
        if ("Lunar".equals(startSpellbook) && "Lunar".equals(endSpellbook))
        {
            return "veng";
        }

        // 4. Default to NH
        return "nh";
    }

    /**
     * Get the display name for a bucket (capitalized for UI).
     */
    private String getBucketDisplayName(String bucket)
    {
        if (bucket == null) return "NH";
        switch (bucket.toLowerCase())
        {
            case "dmm": return "DMM";
            case "multi": return "Multi";
            case "veng": return "Veng";
            case "nh": return "NH";
            case "overall": return "Overall";
            default: return bucket.toUpperCase();
        }
    }
    
    private static class FightEntry {
        final long startTs;
        final int startSpellbook;
        final boolean wasInMulti;
        volatile long lastActivityMs;
        volatile boolean finalized = false;
        
        // Per-fight damage tracking for multi-combat accuracy
        final AtomicLong damageDealt = new AtomicLong(0);    // Damage we dealt TO this opponent
        final AtomicLong damageReceived = new AtomicLong(0); // Damage we received FROM this opponent
        
        FightEntry(long ts, int sb, boolean multi) {
            startTs = ts;
            startSpellbook = sb;
            wasInMulti = multi;
            lastActivityMs = System.currentTimeMillis();
        }
        
        void addDamageDealt(long amount) {
            damageDealt.addAndGet(amount);
            lastActivityMs = System.currentTimeMillis();
        }
        
        void addDamageReceived(long amount) {
            damageReceived.addAndGet(amount);
            lastActivityMs = System.currentTimeMillis();
        }
    }
}
