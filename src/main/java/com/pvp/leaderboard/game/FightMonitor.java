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
import java.util.concurrent.CompletableFuture;
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
import net.runelite.client.config.ConfigManager;

@Slf4j
@Singleton
@SuppressWarnings("deprecation")
public class FightMonitor
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final ConfigManager configManager;
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
        ConfigManager configManager,
        ScheduledExecutorService scheduler,
        MatchResultService matchResultService,
        PvPDataService pvpDataService,
        CognitoAuthService cognitoAuthService,
        ClientIdentityService clientIdentityService)
    {
        this.client = client;
        this.config = config;
        this.configManager = configManager;
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
            
            // Get hitsplat type name for logging
            String hitsplatTypeName = getHitsplatTypeName(hitsplatType);

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
            // For inbound damage (on us): any damage from opponent starts the fight
            // For outbound damage (on them): only OUR hitsplats count
            boolean isDamageHitsplat = (amt > 0) && 
                (hitsplatType == HitsplatID.DAMAGE_ME || hitsplatType == HitsplatID.DAMAGE_OTHER);
            
            // Check if this is OUR damage - either isMine is true OR hitsplatType is DAMAGE_ME
            // DAMAGE_ME is the red hitsplat type shown specifically for YOUR damage on others
            // We check both because isMine can sometimes be incorrectly false
            boolean isOurDamage = isMine || (hitsplatType == HitsplatID.DAMAGE_ME);

            // Debug log every hitsplat on players (commented out - enable for debugging)
            // boolean isOnUs = (hitPlayer == localPlayer);
            // if (amt > 0) {
            //     log.debug("[Hitsplat] {} on={} amt={} type={} isMine={} isOurDmg={}", 
            //         isOnUs ? "INBOUND" : "OUTBOUND",
            //         hitPlayerName,
            //         amt,
            //         hitsplatTypeName,
            //         isMine,
            //         isOurDamage);
            // }

            String opponentName = null;
            boolean startNow = false;

            if (hitPlayer == localPlayer)
            {
                // Hitsplat on us = opponent dealt damage to us (inbound)
                opponentName = resolveInboundAttacker(localPlayer);
                if (opponentName != null)
                {
                    lastExactOpponentName = opponentName;
                    if (isDamageHitsplat) startNow = true;
                }
            }
            else
            {
                // Hitsplat on another player (outbound)
                if (hitPlayerName == null) return;

                // If this is OUR damage (DAMAGE_ME type), this player is definitely our opponent
                // Don't rely on getInteracting() which can be unreliable in chaotic multi-combat
                if (isOurDamage && isDamageHitsplat)
                {
                    opponentName = hitPlayerName;
                    startNow = true;
                }
                else
                {
                    // For non-damage hitsplats or other players' damage, use targeting checks
                    boolean isActiveOpponent = activeFights.containsKey(hitPlayerName);
                    Player interacting = (Player) localPlayer.getInteracting();
                    boolean isCurrentTarget = interacting != null && interacting == hitPlayer;
                    Player theirTarget = (Player) hitPlayer.getInteracting();
                    boolean isTargetingUs = theirTarget != null && theirTarget == localPlayer;

                    if (isActiveOpponent || isCurrentTarget || isTargetingUs)
                    {
                        opponentName = hitPlayerName;
                    }
                }
            }

            // Handle fight start/continuation
            boolean validOpp = (opponentName != null && isPlayerOpponent(opponentName));
            if (startNow && opponentName != null && validOpp)
            {
                int tickNow = client.getTickCount();

                if (opponentName.equals(localPlayer.getName())) return;
                if (suppressFightStartTicks > 0) return;
                if (perOpponentSuppressUntilTicks.containsKey(opponentName)) return;

                // Check if THIS opponent's fight is stale (no activity for 16+ ticks)
                // If stale, remove only this opponent's fight entry so a fresh one is created
                FightEntry existingFight = activeFights.get(opponentName);
                if (existingFight != null && existingFight.isStale(tickNow, OUT_OF_COMBAT_TICKS))
                {
                    // log.debug("[FightStale] Clearing stale fight for {} (lastTick={} currentTick={} gap={})", 
                    //     opponentName, existingFight.lastActivityTick, tickNow, 
                    //     tickNow - existingFight.lastActivityTick);
                    activeFights.remove(opponentName);
                    existingFight = null;
                }

                boolean isNewFight = (existingFight == null);
                touchFight(opponentName);
                inFight = true;
                if (!inFight) startFight(opponentName);
                
                // Check if LOCAL player is in multi - if so, mark this fight as multi
                // This ensures if YOU ever enter multi during the fight, it counts as multi
                // But if opponent dies in multi while you stayed in singles, it's a singles kill
                boolean localPlayerInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
                if (localPlayerInMulti) {
                    wasInMulti = true;
                    // Also update the per-opponent FightEntry
                    FightEntry fe = activeFights.get(opponentName);
                    if (fe != null) {
                        fe.markMultiIfNeeded(true);
                    }
                }
                
                // if (isNewFight)
                // {
                //     log.debug("[FightStart] NEW fight with opponent={}", opponentName);
                // }
            }

            // Add the damage to per-fight tracking in FightEntry
            if (opponentName != null && amt > 0)
            {
                FightEntry fe = activeFights.get(opponentName);
                if (fe != null)
                {
                    int currentTick = client.getTickCount();
                    
                    if (hitPlayer == localPlayer)
                    {
                        // Damage received from opponent
                        fe.addDamageReceived(amt, currentTick);
                        // log.debug("[DmgTrack] RECEIVED {} from {} (total received: {})", 
                        //     amt, opponentName, fe.damageReceived.get());
                    }
                    else if (isOurDamage)
                    {
                        // Count damage dealt if it's OUR damage (isMine=true OR type=DAMAGE_ME)
                        fe.addDamageDealt(amt, currentTick);
                        // log.debug("[DmgTrack] DEALT {} to {} (total dealt: {}) [isMine={} type={}]", 
                        //     amt, opponentName, fe.damageDealt.get(), isMine, hitsplatTypeName);
                    }
                    // else
                    // {
                    //     // Not our damage - log why we're NOT counting it
                    //     log.debug("[DmgTrack] SKIPPED {} on {} - not our damage (isMine={} type={})", 
                    //         amt, opponentName, isMine, hitsplatTypeName);
                    // }
                }
                // else
                // {
                //     // No FightEntry yet
                //     log.debug("[DmgTrack] NO_ENTRY {} on {} - FightEntry doesn't exist yet", 
                //         amt, hitPlayerName);
                // }
            }
            // else if (amt > 0 && opponentName == null)
            // {
            //     // Hitsplat on player but no opponent resolved
            //     log.debug("[DmgTrack] NO_OPPONENT {} on {} - couldn't resolve as opponent", 
            //         amt, hitPlayerName);
            // }
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
                // Priority: whoever dealt the most damage to us gets the kill credit
                // This is the best approximation since the actual killer might have the plugin
                // and would submit the correct result from their side
                String killer = findKillerByDamage();
                
                // Fallbacks if no damage was tracked
                if (killer == null) killer = findActualKiller(localPlayer);
                if (killer == null) killer = opponent;
                if (killer == null) killer = mostRecentActiveOpponent();

                // Log the killer determination for debugging
                // log.debug("[Death] Self death - killer={} (determined by most damage received)", killer);

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
                    FightEntry fe = activeFights.get(name);
                    // Only count as a win if we have an active fight AND dealt actual damage
                    // This prevents counting kills where we just clicked on someone but didn't attack
                    if (fe != null && fe.damageDealt.get() > 0)
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
        
        // Log final damage totals before finalizing
        // log.debug("[FightEnd] opponent={} result={} FINAL_DMG_DEALT={} FINAL_DMG_RECEIVED={}", 
        //     opponentName, result, fe.damageDealt.get(), fe.damageReceived.get());
        
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

        // Ensure valid timestamps - never send 0
        // If start wasn't captured, set it to 60 seconds before end
        // If end is somehow 0, set it to 60 seconds after start
        long startTs = entry.startTs;
        long endTs = now;
        
        if (startTs <= 0 && endTs > 0)
        {
            startTs = endTs - 60;
            log.debug("[MatchSubmit] Start timestamp missing, using endTs-60: startTs={}", startTs);
        }
        else if (endTs <= 0 && startTs > 0)
        {
            endTs = startTs + 60;
            log.debug("[MatchSubmit] End timestamp missing, using startTs+60: endTs={}", endTs);
        }
        else if (startTs <= 0 && endTs <= 0)
        {
            // Both missing - use current time and 60 seconds ago
            endTs = System.currentTimeMillis() / 1000;
            startTs = endTs - 60;
            log.debug("[MatchSubmit] Both timestamps missing, using now and now-60: startTs={} endTs={}", startTs, endTs);
        }
        
        final long finalStartTs = startTs;
        final long finalEndTs = endTs;
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

        // Determine which bucket to use for API calls
        // If auto-switch is enabled, use the fight bucket; otherwise use the user's manual selection
        final String apiRefreshBucket;
        boolean showBucketInMmr = false;  // Show bucket label in MMR notification when bucket differs
        
        // Check if fight bucket differs from user's current selection
        PvPLeaderboardConfig.RankBucket currentBucket = config.rankBucket();
        PvPLeaderboardConfig.RankBucket fightBucketEnum = bucketStringToEnum(fightBucket);
        boolean fightBucketDiffers = (fightBucketEnum != null && currentBucket != fightBucketEnum);
        
        // Auto-switch leaderboard if enabled - always switch to match fight style
        if (config.autoSwitchBucket())
        {
            log.debug("[AutoSwitch] Check: fightBucket={} currentBucket={} newBucket={}", 
                fightBucket, currentBucket, fightBucketEnum);
            
            if (fightBucketEnum != null)
            {
                // Always switch to the fight bucket, even if user manually changed it
                // This ensures the leaderboard always reflects the current fight style
                if (fightBucketDiffers)
                {
                    log.debug("[AutoSwitch] Switching leaderboard from {} to {}", currentBucket, fightBucketEnum);
                    configManager.setConfiguration("PvPLeaderboard", "rankBucket", fightBucketEnum.name());
                    showBucketInMmr = true;  // Show bucket label since we switched
                }
                else
                {
                    log.debug("[AutoSwitch] Already on correct leaderboard: {}", fightBucketEnum);
                }
            }
            else
            {
                log.debug("[AutoSwitch] Could not map fightBucket '{}' to enum", fightBucket);
            }
            
            // Use fight bucket for API refresh when auto-switch is enabled
            apiRefreshBucket = fightBucket;
        }
        else
        {
            log.debug("[AutoSwitch] Disabled - keeping manual selection: {}", currentBucket);
            // Use user's manual selection for API refresh
            apiRefreshBucket = getConfigBucketKey();
            
            // Show bucket label if fight was in a different bucket than user's selection
            // This informs the user which bucket the MMR change applies to
            if (fightBucketDiffers)
            {
                showBucketInMmr = true;
            }
        }

        // Async Submission
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                submitMatchResult(result, finalEndTs, selfName, resolvedOpponent, world, finalStartTs, startSb, currentSpellbook, wasMulti, idTokenSafe, dmgOut);
            } catch (Exception e) {
                log.debug("[MatchSubmit] EXCEPTION in async submission: {}", e.getMessage(), e);
            }
        }, scheduler);

        // Schedule API refreshes in 5s (gives backend time to process the match)
        // Uses the determined bucket (fight bucket if auto-switch, manual selection otherwise)
        // Show bucket label in MMR notification when fight bucket differs from user's selection
        scheduleApiRefreshes(resolvedOpponent, apiRefreshBucket, showBucketInMmr);
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

    private void scheduleApiRefreshes(String opponentName, String displayBucket, boolean showBucketInMmr) {
        log.debug("[PostFight] Scheduling API refresh in 5s for self and opponent={} bucket={} showBucket={}", 
            opponentName, displayBucket, showBucketInMmr);
        
        scheduler.schedule(() -> {
            try {
                String selfName = getLocalPlayerName();
                log.debug("[PostFight] Executing API refresh: self={} opponent={} bucket={}", 
                    selfName, opponentName, displayBucket);
                
                if (selfName != null) {
                    // Refresh rank display using the specified bucket
                    fetchTierWithRetry(selfName, displayBucket, 3);
                    
                    // Get MMR delta from match history (more accurate than profile diff)
                    // First attempt at 5s, second attempt at 15s if first fails (1 retry with 10s delay)
                    // Only show bucket label in notification if we auto-switched
                    if (config.showMmrChangeNotification() && opponentName != null) {
                        fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, showBucketInMmr, 1);
                    }
                } else {
                    log.debug("[PostFight] selfName is null, skipping self refresh");
                }
                
                // Refresh opponent rank using the same bucket
                if (opponentName != null) {
                    fetchTierWithRetry(opponentName, displayBucket, 3);
                } else {
                    log.debug("[PostFight] opponentName is null, skipping opponent refresh");
                }
                
                // Trigger overlay refresh after API calls complete
                if (rankOverlay != null) {
                    rankOverlay.scheduleSelfRankRefresh(0L);
                }
            } catch(Exception e){
                log.debug("[PostFight] API refresh exception: {}", e.getMessage());
            }
        }, 5L, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Fetch MMR delta from match history API.
     * This is more accurate than calculating from profile differences.
     * Uses account SHA (UUID hash) to ensure ALL matches are returned even after name changes.
     * First attempt at ~5s after fight, second (final) attempt at ~15s if first fails.
     * 
     * @param showBucketLabel If true, include bucket name in MMR notification (used when auto-switch occurred)
     */
    private void fetchMmrDeltaFromMatchHistory(String selfName, String opponentName, String displayBucket, boolean showBucketLabel, int retriesLeft) {
        log.debug("[PostFight] Fetching MMR delta from match history: self={} opponent={} showBucket={} retriesLeft={}", 
            selfName, opponentName, showBucketLabel, retriesLeft);
        
        // Use account SHA for accurate match history across name changes
        // This ensures MMR delta is correct even if the player changed their name
        String selfAcctSha = pvpDataService.getSelfAcctSha();
        
        // Fetch more matches to handle multi-kill scenarios (up to 10 kills in quick succession)
        // Bypass cache to ensure we get fresh data with the new match
        // Prefer acct-based lookup if available, fallback to name-based
        CompletableFuture<JsonObject> matchesFuture;
        if (selfAcctSha != null && !selfAcctSha.isEmpty()) {
            log.debug("[PostFight] Using acct-based match lookup: acct={}", selfAcctSha.substring(0, 8) + "...");
            matchesFuture = pvpDataService.getPlayerMatchesByAcct(selfAcctSha, null, 15, true);
        } else {
            log.debug("[PostFight] Falling back to name-based match lookup: name={}", selfName);
            matchesFuture = pvpDataService.getPlayerMatches(selfName, null, 15, true);
        }
        
        matchesFuture.thenAccept(response -> {
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
                                
                                // Only show bucket label if we auto-switched to a different bucket
                                String bucketLabel = showBucketLabel ? getBucketDisplayName(matchBucket) : null;
                                
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
                    scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, showBucketLabel, retriesLeft - 1),
                        10L, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    log.debug("[PostFight] Match not found in history after all retries, skipping MMR notification");
                }
            } else if (retriesLeft > 0) {
                log.debug("[PostFight] No matches in response, retrying in 10s ({} left)", retriesLeft - 1);
                scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, showBucketLabel, retriesLeft - 1),
                    10L, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] Failed to get matches after all retries");
            }
        }).exceptionally(ex -> {
            if (retriesLeft > 0) {
                log.debug("[PostFight] Match history exception: {}, retrying in 10s ({} left)", ex.getMessage(), retriesLeft - 1);
                scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, showBucketLabel, retriesLeft - 1),
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
        boolean localPlayerInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        int currentTick = client.getTickCount();
        activeFights.compute(opponentName, (k, v) -> {
            if (v == null) return new FightEntry(ts, sb, localPlayerInMulti, currentTick);
            // Update activity timestamps
            v.lastActivityMs = System.currentTimeMillis();
            v.lastActivityTick = currentTick;
            // If LOCAL player is now in multi, mark this fight as multi
            // (only tracks if WE enter multi, not if opponent enters multi)
            v.markMultiIfNeeded(localPlayerInMulti);
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

    /**
     * Resolve who attacked us when we receive inbound damage.
     * 
     * CRITICAL: This method should ONLY return existing fight opponents.
     * We do NOT start new fights from inbound damage because:
     * - getInteracting() returns true for non-combat actions (trading, item use, following)
     * - We cannot distinguish player damage from NPC damage by hitsplat alone
     * - If someone attacks us first, THEY will track the fight from their side
     * 
     * New fights are ONLY started via outbound damage (when we attack someone).
     * This ensures we only track fights where we actually participated in combat.
     * 
     * @param localPlayer The local player who received damage
     * @return The name of an existing fight opponent, or null if none found
     */
    private String resolveInboundAttacker(Player localPlayer)
    {
        List<Player> players = client.getPlayers();
        if (players == null) {
            return existingFightAttacker();
        }

        // Only return players we already have an active fight with
        // This ensures inbound damage is only tracked for fights WE initiated via outbound damage
        for (Player other : players) {
            if (other == null || other == localPlayer) continue;
            
            String otherName = other.getName();
            if (otherName != null && activeFights.containsKey(otherName)) {
                // Existing fight opponent - track their damage to us
                return otherName;
            }
        }
        
        // No existing fight opponent found
        // DO NOT identify new attackers from inbound damage - this causes false positives:
        // - Trading partner identified as attacker when we take NPC damage
        // - Player using items on us identified as attacker
        // - Nearby player in multi-combat identified incorrectly
        return null;
    }
    
    /**
     * Get an existing fight attacker from the most recent active fight.
     * Only returns names of players we already have active fights with.
     */
    private String existingFightAttacker()
    {
        String recent = mostRecentActiveOpponent();
        return recent;  // Returns null if no active fights, which is correct
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
        long bestDmg = 0L;  // Must have dealt at least some damage
        
        // Find opponent who dealt most damage to us
        for (Map.Entry<String, FightEntry> e : activeFights.entrySet())
        {
            FightEntry fe = e.getValue();
            if (fe == null) continue;
            long dmgReceived = fe.damageReceived.get();
            // log.debug("[KillerSearch] Candidate: {} dealt {} damage to us", e.getKey(), dmgReceived);
            
            if (dmgReceived > bestDmg)
            {
                bestDmg = dmgReceived;
                killer = e.getKey();
            }
        }
        
        // if (killer != null)
        // {
        //     log.debug("[KillerSearch] Winner: {} with {} damage", killer, bestDmg);
        // }
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
     * Get readable name for hitsplat type for debug logging.
     */
    private String getHitsplatTypeName(int type)
    {
        switch (type) {
            case HitsplatID.DAMAGE_ME: return "DAMAGE_ME";
            case HitsplatID.DAMAGE_OTHER: return "DAMAGE_OTHER";
            case HitsplatID.POISON: return "POISON";
            case HitsplatID.VENOM: return "VENOM";
            case HitsplatID.BLOCK_ME: return "BLOCK_ME";
            case HitsplatID.BLOCK_OTHER: return "BLOCK_OTHER";
            case HitsplatID.HEAL: return "HEAL";
            default: return "TYPE_" + type;
        }
    }

    /**
     * Determine the bucket for a fight based on server-side logic.
     * Priority: DMM > Multi > Veng > NH
     * 
     * Multi-combat classification:
     * - If YOU (local player) ever entered multi during the fight, it's a "multi" fight
     * - If you stayed in singles but opponent died in multi, it's still a "singles" kill (NH/Veng)
     * 
     * @param world The world number
     * @param wasInMulti Whether the LOCAL player was ever in multi-combat during this fight
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
     * Convert bucket string to RankBucket enum for config updates.
     */
    private PvPLeaderboardConfig.RankBucket bucketStringToEnum(String bucket)
    {
        if (bucket == null) return null;
        switch (bucket.toLowerCase())
        {
            case "nh": return PvPLeaderboardConfig.RankBucket.NH;
            case "veng": return PvPLeaderboardConfig.RankBucket.VENG;
            case "multi": return PvPLeaderboardConfig.RankBucket.MULTI;
            case "dmm": return PvPLeaderboardConfig.RankBucket.DMM;
            default: return null;
        }
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
        volatile boolean wasInMulti;  // Mutable: set true if LOCAL player ever enters multi during this fight
        volatile long lastActivityMs;
        volatile int lastActivityTick;  // Track per-opponent combat activity in game ticks
        volatile boolean finalized = false;
        
        // Per-fight damage tracking for multi-combat accuracy
        final AtomicLong damageDealt = new AtomicLong(0);    // Damage we dealt TO this opponent
        final AtomicLong damageReceived = new AtomicLong(0); // Damage we received FROM this opponent
        
        FightEntry(long ts, int sb, boolean multi, int currentTick) {
            startTs = ts;
            startSpellbook = sb;
            wasInMulti = multi;
            lastActivityMs = System.currentTimeMillis();
            lastActivityTick = currentTick;
        }
        
        /**
         * Mark this fight as multi if the local player entered multi-combat area.
         * Only updates to true (never reverts to singles once multi is flagged).
         */
        void markMultiIfNeeded(boolean isInMulti) {
            if (isInMulti && !wasInMulti) {
                wasInMulti = true;
            }
        }
        
        void addDamageDealt(long amount, int currentTick) {
            damageDealt.addAndGet(amount);
            lastActivityMs = System.currentTimeMillis();
            lastActivityTick = currentTick;
        }
        
        void addDamageReceived(long amount, int currentTick) {
            damageReceived.addAndGet(amount);
            lastActivityMs = System.currentTimeMillis();
            lastActivityTick = currentTick;
        }
        
        boolean isStale(int currentTick, int timeout) {
            return (currentTick - lastActivityTick) > timeout;
        }
    }
}
