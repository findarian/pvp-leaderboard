package com.pvp.leaderboard.game;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.lobby.UserProfileLobbyJoinGate;
import com.pvp.leaderboard.overlay.RankOverlay;
import com.pvp.leaderboard.service.ClientIdentityService;
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
    private final ClientIdentityService clientIdentityService;
    private final UserProfileLobbyJoinGate lobbyJoinGate;

    // RankOverlay reference for MMR notifications
    private RankOverlay rankOverlay;

    // --- Fight State ---
    private String opponent = null;

    // Tracks multiple simultaneous fights (per-opponent) - damage is now tracked per-FightEntry
    private final ConcurrentHashMap<String, FightEntry> activeFights = new ConcurrentHashMap<>();
    
    // Tick counters
    private int suppressFightStartTicks = 0;
    private final ConcurrentHashMap<String, Integer> perOpponentSuppressUntilTicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> shardPresence = new ConcurrentHashMap<>();
    
    private static final int OUT_OF_COMBAT_TICKS = 16;
    private int gcTicksCounter = 0;

    /** Wall-clock timestamp (ms) of the most recent inbound damage
     *  hitsplat where the attacker is identifiable as another Player
     *  (not an NPC, not a self-deal). Updated on every qualifying
     *  hit in {@link #handleHitsplatApplied}; used by
     *  {@link #updateInCombatFlag()} to gate popup suppression for
     *  the "passively-attacked, not yet retaliated" case —
     *  {@link #activeFights} is only populated by OUTBOUND damage
     *  (see resolveInboundAttacker's intentional null-on-no-existing-fight
     *  contract), so without this separate signal a user being
     *  ganked / opener-attacked in a matchmaking arena reads as
     *  out-of-combat for the ~600ms–4s window before they retaliate,
     *  and an invite popup arriving in that window slips through
     *  the suppression gate (QA bug 2026-05-25). */
    private volatile long lastInboundPvpDamageMs = 0L;

    /** Push-model cache for {@link #isInCombat()}. The previous
     *  pull model recomputed the answer on every popup-render frame
     *  (~50 FPS × 2 overlays ≈ 100 calls/sec): walk
     *  {@link #activeFights}, read {@link #lastInboundPvpDamageMs},
     *  call {@link #isInCombatDecision}, emit a rate-limited DEBUG
     *  log. The user (2026-05-26) called out the obvious smell:
     *  combat is <em>state</em>, not a derived predicate, so it
     *  should be cached and updated only at the mutation sites that
     *  can change it. The cached field is updated by
     *  {@link #updateInCombatFlag()}, which is called from every
     *  site that mutates {@link #activeFights} or
     *  {@link #lastInboundPvpDamageMs}:
     *  <ul>
     *    <li>{@link #handleHitsplatApplied} — after recording inbound
     *        damage and/or seeding/touching a {@link FightEntry}.</li>
     *    <li>{@link #handleGameTick} — after the 33-tick GC pass
     *        evicts stale entries (combat exit edge).</li>
     *    <li>{@link #endFightFor} — after the conditional
     *        {@link #shouldClearInboundSignalAfterSubmission} clear
     *        on kill/death.</li>
     *    <li>{@link #clearFightFor} — covers the other entry-removal
     *        path used post-fight.</li>
     *    <li>{@link #resetFightState} — login/logout bounce reset.</li>
     *  </ul>
     *  Default value {@code false} is the correct login/startup
     *  reading: a fresh plugin start has never seen a combat event
     *  and must not read as in-combat until one fires. */
    private volatile boolean inCombat = false;

    // MMR tracking - no longer using pre-fight profile, using match history API instead

    @Inject
    public FightMonitor(
        Client client,
        PvPLeaderboardConfig config,
        ConfigManager configManager,
        ScheduledExecutorService scheduler,
        MatchResultService matchResultService,
        PvPDataService pvpDataService,
        ClientIdentityService clientIdentityService,
        UserProfileLobbyJoinGate lobbyJoinGate)
    {
        this.client = client;
        this.config = config;
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.matchResultService = matchResultService;
        this.pvpDataService = pvpDataService;
        this.clientIdentityService = clientIdentityService;
        this.lobbyJoinGate = lobbyJoinGate;
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
        opponent = null;
        suppressFightStartTicks = 2;
        activeFights.clear();
        perOpponentSuppressUntilTicks.clear();
        shardPresence.clear();
        // Reset the inbound-PvP-damage signal too — a logout/login
        // or game-state bounce should not leave the popup-suppression
        // gate stuck on "in combat" indefinitely.
        lastInboundPvpDamageMs = 0L;
        updateInCombatFlag();
        try { log.debug("[Fight] state reset; suppressTicks={}", suppressFightStartTicks); } catch (Exception ignore) {}
    }

    /** Recency window (ms) for {@link #isInCombat()}. Matches the GC
     *  threshold inside {@link #handleGameTick} (line ~157) so the
     *  popup-suppression window is coherent with when stale fights
     *  drop out of {@link #activeFights}. */
    static final long COMBAT_WINDOW_MS = 10_000L;

    /** Pure recency predicate. {@code true} iff {@code lastActivityMs}
     *  sits within {@link #COMBAT_WINDOW_MS} of {@code nowMs}.
     *
     *  <p>Edge cases (pinned in {@code FightMonitorCombatWindowTest}):
     *  <ul>
     *    <li>Exact-boundary elapsed → in combat (inclusive).</li>
     *    <li>Negative elapsed (clock skew, e.g. NTP correction
     *        mid-fight) → in combat. Safer side: at worst we delay
     *        a popup, we never replay one the user already
     *        dismissed.</li>
     *  </ul>
     */
    static boolean isWithinCombatWindow(long lastActivityMs, long nowMs)
    {
        long elapsed = nowMs - lastActivityMs;
        if (elapsed < 0L) return true;
        return elapsed <= COMBAT_WINDOW_MS;
    }

    /** Push-model accessor — returns the cached {@link #inCombat}
     *  flag. Single volatile read, no map walk, no decision math,
     *  no per-call log emission. The value is updated only at the
     *  mutation sites enumerated on {@link #inCombat}; see that
     *  field's javadoc for the full transition list and rationale.
     *
     *  <p>Used by the popup notification overlays to suppress
     *  in-game popups while the user is in PvP. The user-facing
     *  config gate
     *  {@link com.pvp.leaderboard.config.PvPLeaderboardConfig#suppressNotificationsInCombat()}
     *  decides whether to consult this.
     *
     *  <p>"PvP combat" specifically — both update sites
     *  ({@link #handleHitsplatApplied} for inbound damage,
     *  {@link #touchFight} for outbound) filter hitsplats so only
     *  player-vs-player damage seeds either signal; an item trade,
     *  NPC tick, or skilling action will never flip this flag. */
    public boolean isInCombat()
    {
        return inCombat;
    }

    /** Recompute {@link #inCombat} from the current values of
     *  {@link #activeFights} and {@link #lastInboundPvpDamageMs}.
     *  Called from every site that mutates either of those (see
     *  {@link #inCombat} field docs for the enumerated list).
     *
     *  <p><b>Decision rule</b> (unchanged from the pre-2026-05-26
     *  pull model — only the trigger model flipped, not the truth
     *  table): in combat iff EITHER any non-finalized entry exists
     *  in {@link #activeFights} (regardless of recency — let the
     *  33-tick GC at {@link #handleGameTick} decide when to evict),
     *  OR {@link #lastInboundPvpDamageMs} is within
     *  {@link #COMBAT_WINDOW_MS} of now. Both branches are pinned
     *  by {@link FightMonitorCombatWindowTest}.
     *
     *  <p><b>Why this method is package-private:</b> not for tests
     *  (those rely solely on the public {@link #isInCombat()} surface
     *  to avoid reflection / over-fitting to internals). It's
     *  package-private so the same-package overlay diagnostics
     *  could trigger a recompute on demand if a future regression
     *  needs it; production code only ever calls this from the
     *  five enumerated mutation sites.
     *
     *  <p><b>Logging:</b> emits a single DEBUG line per
     *  {@code false→true} or {@code true→false} transition,
     *  capturing which branch of {@link #isInCombatDecision}
     *  carried it (active-fight-count + inbound-damage age). Steady
     *  state is silent — that's the central refactor win, replacing
     *  the previous rate-limited 1-line-per-second polling spam. */
    void updateInCombatFlag()
    {
        long now = System.currentTimeMillis();
        int activeFightCount = 0;
        for (FightEntry fe : activeFights.values())
        {
            if (fe == null) continue;
            if (fe.finalized) continue;
            activeFightCount++;
        }
        boolean hasActiveFight = activeFightCount > 0;
        long inboundMs = lastInboundPvpDamageMs;
        boolean newValue = isInCombatDecision(inboundMs, hasActiveFight, now);

        if (newValue != inCombat)
        {
            long inboundAgeMs = (inboundMs > 0L) ? (now - inboundMs) : -1L;
            try
            {
                log.debug("[FightMonitor] in-combat transition {} -> {} hasActiveFight={}"
                        + " activeFightCount={} inboundAgeMs={} combatWindowMs={}",
                    inCombat, newValue, hasActiveFight, activeFightCount, inboundAgeMs,
                    COMBAT_WINDOW_MS);
            }
            catch (Exception ignore)
            {
                // Logger may be uninitialized in some test paths; the
                // flag update itself MUST still happen.
            }
            inCombat = newValue;
        }
    }

    /** Pure decision helper: in combat iff EITHER
     *  {@code lastInboundPvpDamageMs} is within
     *  {@link #COMBAT_WINDOW_MS} of {@code nowMs}, OR
     *  {@code hasActiveFight} is true. Both signals are independent;
     *  either alone is sufficient.
     *
     *  <p>The active-fight branch deliberately does NOT consult a
     *  recency window — see {@link #isInCombat()} for the rationale.
     *  In short: the source of truth for "match is over" is the GC
     *  pass that removes idle entries from {@link #activeFights},
     *  not a parallel 10s recency check that would un-suppress the
     *  popup before the GC actually runs. The inbound-damage branch
     *  still uses the recency window because it has no FightEntry
     *  backing it (no GC to defer to).
     *
     *  <p>Extracted as a static helper so
     *  {@code FightMonitorCombatWindowTest} can pin the two-signal
     *  OR-gate without instantiating FightMonitor (which has nine
     *  injected dependencies). */
    static boolean isInCombatDecision(long lastInboundPvpDamageMs, boolean hasActiveFight, long nowMs)
    {
        if (lastInboundPvpDamageMs > 0L
            && isWithinCombatWindow(lastInboundPvpDamageMs, nowMs)) return true;
        return hasActiveFight;
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
            opponent = null;
            suppressFightStartTicks = 2;
        }
        // Recompute the cached in-combat flag — removing a FightEntry
        // can flip the gate to false (singles kill, last multi
        // opponent cleared, GC after fight-finalize). Without this
        // the cache would lag the activeFights truth until the next
        // unrelated mutation.
        updateInCombatFlag();
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
            }

            // Combat window idle check - individual fight expiration is handled by GC above
            // No global damage maps to clear anymore; damage is per-FightEntry

            // Recompute the cached in-combat flag every tick. Two
            // reasons this lives here rather than only at GC time:
            //
            //  (1) The {@code lastInboundPvpDamageMs} recency window
            //      auto-expires without an event — no hitsplat fires
            //      to mark the moment 10 seconds have elapsed since
            //      the last inbound hit. The game tick (~600ms
            //      cadence) is the natural carrier for this exit
            //      transition. At ~1.7 ticks/sec we'll detect the
            //      edge within one tick of {@link #COMBAT_WINDOW_MS}
            //      elapsing, well below any user-visible latency.
            //  (2) The 33-tick GC pass above can evict entries that
            //      change the flag; sharing the recompute call with
            //      the per-tick recency check keeps both edges on
            //      the same code path.
            //
            // {@link #updateInCombatFlag} is a no-op when the
            // computed value matches the cache, so 99%+ of ticks
            // fire zero log lines and do only a small map walk —
            // far cheaper than the previous ~100 calls/sec from
            // the popup-render polling.
            updateInCombatFlag();
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
                // Hitsplat on us = opponent dealt damage to us (inbound).
                // Mark the inbound-PvP-damage timestamp BEFORE the
                // resolveInboundAttacker call so the popup-suppression
                // gate engages even when there's no active fight yet
                // (which is the whole reason this branch exists —
                // resolveInboundAttacker only returns existing fight
                // opponents, by design, to prevent NPC/trade/item-use
                // damage from spawning false PvP fights). The "is
                // there a real player attacker" check uses the cheap
                // {@link #findActualKiller}-equivalent walk: any
                // other Player whose getInteracting() is the local
                // player is treated as our attacker for combat-state
                // purposes only (still no fight registered, so no
                // false match-tracking).
                if (isDamageHitsplat && hasPlayerAttacker(localPlayer))
                {
                    lastInboundPvpDamageMs = System.currentTimeMillis();
                }
                opponentName = resolveInboundAttacker(localPlayer);
                if (opponentName != null)
                {
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

                touchFight(opponentName);
                
                boolean localPlayerInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
                if (localPlayerInMulti) {
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
                    
                    // Update combat timestamps for "hide rank out of combat" feature
                    if (rankOverlay != null)
                    {
                        rankOverlay.updateSelfCombatTime();
                        rankOverlay.updatePlayerCombatTime(opponentName);
                    }
                    
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

            // Recompute the cached in-combat flag — this hitsplat
            // path is the primary "false→true" transition source
            // (inbound damage seeds {@link #lastInboundPvpDamageMs};
            // outbound damage seeds {@link #activeFights}). The
            // recompute is a no-op when no signal changed (e.g. an
            // NPC hitsplat that bailed early), so it's free in
            // practice.
            updateInCombatFlag();
        }
        catch (Exception e)
        {
            // log.debug("Uncaught exception in FightMonitor.onHitsplatApplied", e);
        }
    }

    public void handleActorDeath(ActorDeath event)
    {
        long t0 = System.nanoTime();
        try
        {
            if (!(event.getActor() instanceof Player)) return;
            Player player = (Player) event.getActor();
            Player localPlayer = client.getLocalPlayer();

            if (player == localPlayer)
            {
                String killer = findKillerByDamage();
                
                if (killer == null) killer = findActualKiller(localPlayer);
                if (killer == null) killer = opponent;
                if (killer == null) killer = mostRecentActiveOpponent();

                if (killer != null)
                {
                    endFightFor(killer, "loss");
                }
                
                for (String remaining : new ArrayList<>(activeFights.keySet()))
                {
                    clearFightFor(remaining);
                }
                
                resetFightState();
            }
            else
            {
                String name = player.getName();
                if (name != null)
                {
                    FightEntry fe = activeFights.get(name);
                    if (fe != null && fe.damageDealt.get() > 0)
                    {
                        endFightFor(name, "win");
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.debug("[Death] Exception in handleActorDeath: {}", e.getMessage(), e);
        }
        finally
        {
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            if (elapsed > 5)
            {
                log.debug("[Death][PERF] handleActorDeath took {}ms (>5ms threshold)", elapsed);
            }
        }
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

        // Release the popup-suppression gate the moment the match is
        // submitted, instead of waiting up to {@link #COMBAT_WINDOW_MS}
        // for the lingering {@link #lastInboundPvpDamageMs} from the
        // just-finished fight to age out (user spec 2026-05-25:
        // "right after a match submission or when combat ends and
        // the match is discarded"). Multi-opponent guard: stays
        // engaged while any other {@code activeFights} entry remains
        // OR a fresh player attacker is interacting-with the local
        // player. See
        // {@link FightMonitorCombatWindowTest#shouldClearInboundSignalAfterSubmission_singlesKillNoAttacker_returnsTrue}
        // and the multi/attacker companion tests for the contract.
        Player local = (client != null) ? client.getLocalPlayer() : null;
        boolean hasOtherActiveFights = !activeFights.isEmpty();
        boolean hasCurrentAttacker = (local != null) && hasPlayerAttacker(local);
        if (shouldClearInboundSignalAfterSubmission(hasOtherActiveFights, hasCurrentAttacker))
        {
            lastInboundPvpDamageMs = 0L;
        }
        // Final recompute after all the kill-path mutations
        // (clearFightFor above already triggered one, but the
        // conditional inbound clear here can additionally flip the
        // flag in the singles-no-attacker case where activeFights
        // emptied AND inbound just zeroed in the same call).
        updateInCombatFlag();
    }

    /** Pure decision helper: should {@link #lastInboundPvpDamageMs}
     *  be cleared after {@link #endFightFor} finalizes a fight?
     *
     *  <p>Clear iff BOTH:
     *  <ul>
     *    <li>{@code hasOtherActiveFights} is {@code false} — no
     *        multi-opponent FightEntry still in
     *        {@link #activeFights}. In multi the gate must stay
     *        engaged until ALL multi-opponents are cleared (user
     *        spec 2026-05-25: "they can't be in combat with anyone
     *        since they may be in combat with multiple people").</li>
     *    <li>{@code hasCurrentAttacker} is {@code false} — no Player
     *        on the scene whose {@code getInteracting()} is the local
     *        player. Defends against the singles-with-fresh-attacker
     *        flicker case: opp1 dies and the gate would briefly read
     *        "out of combat" before opp2's first hitsplat re-seeds
     *        {@code lastInboundPvpDamageMs}; that one-frame
     *        un-suppression would surface a partial popup paint.</li>
     *  </ul>
     *
     *  <p>Extracted as a static helper so
     *  {@code FightMonitorCombatWindowTest} can pin the truth table
     *  without instantiating FightMonitor (which has nine injected
     *  dependencies). */
    static boolean shouldClearInboundSignalAfterSubmission(boolean hasOtherActiveFights,
                                                           boolean hasCurrentAttacker)
    {
        return !hasOtherActiveFights && !hasCurrentAttacker;
    }

    private void finalizeFight(String opponentName, String result, FightEntry entry)
    {
        long t0 = System.nanoTime();
        if (entry == null) return;
        
        final int currentSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
        final int world = client.getWorld();
        final long now = System.currentTimeMillis() / 1000;
        final String selfName = getLocalPlayerName();

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
                if (fightBucketDiffers)
                {
                    log.debug("[AutoSwitch] Switching leaderboard from {} to {}", currentBucket, fightBucketEnum);
                    // Defer config write off the game thread — setConfiguration triggers
                    // config change listeners, panel rebuilds, and disk I/O synchronously
                    final PvPLeaderboardConfig.RankBucket targetBucket = fightBucketEnum;
                    scheduler.execute(() -> configManager.setConfiguration("PvPLeaderboard", "rankBucket", targetBucket.name()));
                    showBucketInMmr = true;
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

        // Async Submission — chain MMR fetch off the 202 response
        final String finalApiRefreshBucket = apiRefreshBucket;
        final boolean finalShowBucketInMmr = showBucketInMmr;
        submitMatchAndFetchMmr(result, finalEndTs, selfName, resolvedOpponent, world, finalStartTs, startSb, currentSpellbook, wasMulti, dmgOut, finalApiRefreshBucket, finalShowBucketInMmr);

        // Tier refreshes for overlay (don't depend on match being processed)
        scheduleTierRefreshes(resolvedOpponent, apiRefreshBucket);
        
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        log.debug("[Death][PERF] finalizeFight took {}ms for opponent={} result={}", elapsed, opponentName, result);
    }

    private CompletableFuture<Boolean> submitMatchResult(String result, long fightEndTime, String playerId, String opponentId, int world,
                                   long fightStartTs, int fightStartSpellbookLocal, int fightEndSpellbookLocal,
                                   boolean wasInMultiLocal, long damageToOpponentLocal)
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
                .damageToOpponent(damageToOpponentLocal)
                .clientUniqueId(clientIdentityService.getClientUniqueId())
                .build();

        log.debug("[MatchSubmit] Submitting: player={} opponent={} result={} world={} startTs={} endTs={} dmgOut={} multi={}",
                playerId, opponentId, result, world, fightStartTs, fightEndTime, damageToOpponentLocal, wasInMultiLocal);

        return matchResultService.submitMatchResult(match).thenApply(success -> {
            if (success) {
                log.debug("[MatchSubmit] SUCCESS: match submitted for player={} vs opponent={} result={}", playerId, opponentId, result);
            } else {
                log.debug("[MatchSubmit] FAILED: match submission failed for player={} vs opponent={} result={}", playerId, opponentId, result);
            }
            return success;
        });
    }

    /**
     * Submit the match, then chain the MMR delta fetch off the 202 response.
     * This ensures the fetch always happens AFTER the server acknowledges the submission,
     * regardless of scheduler congestion from other plugins.
     */
    private void submitMatchAndFetchMmr(String result, long endTs, String selfName, String opponent, int world,
                                         long startTs, int startSb, int endSb, boolean wasMulti,
                                         long dmgOut, String displayBucket, boolean showBucketInMmr) {
        log.debug("[PostFight] Submitting match and chaining MMR fetch for opponent={}", opponent);

        CompletableFuture<Boolean> submissionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return submitMatchResult(result, endTs, selfName, opponent, world, startTs, startSb, endSb, wasMulti, dmgOut);
            } catch (Exception e) {
                log.debug("[MatchSubmit] EXCEPTION in async submission: {}", e.getMessage(), e);
                return CompletableFuture.completedFuture(false);
            }
        }, scheduler).thenCompose(f -> f);

        submissionFuture.thenAccept(success -> {
            if (Boolean.TRUE.equals(success))
            {
                scheduleLobbyGateRefresh();
            }
            if (config.showMmrChangeNotification() && opponent != null && selfName != null) {
                log.debug("[PostFight] Submission done (success={}), scheduling MMR fetch in 3s for opponent={}", success, opponent);
                scheduler.schedule(() -> {
                    fetchMmrDeltaFromMatchHistory(selfName, opponent, displayBucket, showBucketInMmr, 0, endTs);
                }, 3L, java.util.concurrent.TimeUnit.SECONDS);
            }
        }).exceptionally(ex -> {
            log.debug("[PostFight] Submission future failed, scheduling fallback MMR fetch: {}", ex.getMessage());
            if (config.showMmrChangeNotification() && opponent != null && selfName != null) {
                scheduler.schedule(() -> {
                    fetchMmrDeltaFromMatchHistory(selfName, opponent, displayBucket, showBucketInMmr, 0, endTs);
                }, 3L, java.util.concurrent.TimeUnit.SECONDS);
            }
            return null;
        });
    }

    /** Re-fetch the local player's cumulative_stats for the lobby
     *  SMURF_GUARD gate after a successful match submit. Delayed so
     *  the backend has time to fold the new fight into /user. */
    private void scheduleLobbyGateRefresh()
    {
        if (lobbyJoinGate == null) return;
        scheduler.schedule(() -> {
            try
            {
                if (lobbyJoinGate.isLoggedIn())
                {
                    lobbyJoinGate.refresh();
                }
            }
            catch (Exception e)
            {
                log.debug("[PostFight] Lobby gate refresh failed: {}", e.getMessage());
            }
        }, 5L, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Refresh tier/rank display for self and opponent.
     * Runs 5s after fight end — independent of match submission.
     */
    private void scheduleTierRefreshes(String opponentName, String displayBucket) {
        scheduler.schedule(() -> {
            try {
                String selfName = getLocalPlayerName();
                log.debug("[PostFight] Executing tier refresh: self={} opponent={} bucket={}", 
                    selfName, opponentName, displayBucket);
                
                if (selfName != null) {
                    fetchTierWithRetry(selfName, displayBucket, 3);
                }
                if (opponentName != null) {
                    fetchTierWithRetry(opponentName, displayBucket, 3);
                }
            } catch (Exception e) {
                log.debug("[PostFight] Tier refresh exception: {}", e.getMessage());
            }
        }, 5L, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static final long[] MMR_RETRY_DELAYS = {5L, 5L};

    /**
     * Fetch MMR delta from match history API.
     * Uses account SHA (UUID hash) to ensure ALL matches are returned even after name changes.
     * Retry schedule: first attempt at 3s post-submit, then 5s, then 10s.
     * 
     * @param attempt 0-based attempt index (0 = first try, 1 = retry at 5s, 2 = retry at 10s)
     * @param submittedMatchEndTs The fight_end_ts of the submitted match, used to validate we got the correct match
     */
    private void fetchMmrDeltaFromMatchHistory(String selfName, String opponentName, String displayBucket, boolean showBucketLabel, int attempt, long submittedMatchEndTs) {
        log.debug("[PostFight] Fetching MMR delta from match history: self={} opponent={} showBucket={} attempt={} submittedTs={}", 
            selfName, opponentName, showBucketLabel, attempt, submittedMatchEndTs);
        
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
                
                log.debug("[PostFight] Got {} matches from history, looking for opponent='{}' near ts={}", 
                    matches.size(), opponentName, submittedMatchEndTs);
                
                // Log first few matches for diagnostics
                int diagCount = Math.min(matches.size(), 5);
                for (int i = 0; i < diagCount; i++) {
                    if (!matches.get(i).isJsonObject()) continue;
                    JsonObject m = matches.get(i).getAsJsonObject();
                    String mOpp = m.has("opponent_id") && !m.get("opponent_id").isJsonNull() ? m.get("opponent_id").getAsString() : "?";
                    long mWhen = m.has("when") && !m.get("when").isJsonNull() ? m.get("when").getAsLong() : 0;
                    boolean hasRating = m.has("rating_change") && m.get("rating_change").isJsonObject();
                    log.debug("[PostFight]   match[{}]: opponent='{}' when={} hasRatingChange={}", i, mOpp, mWhen, hasRating);
                }
                
                for (var element : matches) {
                    if (!element.isJsonObject()) continue;
                    JsonObject match = element.getAsJsonObject();
                    
                    String matchOpponent = match.has("opponent_id") && !match.get("opponent_id").isJsonNull() 
                        ? match.get("opponent_id").getAsString() : null;
                    
                    if (matchOpponent != null && matchOpponent.equalsIgnoreCase(opponentName)) {
                        long matchWhen = match.has("when") && !match.get("when").isJsonNull() 
                            ? match.get("when").getAsLong() : 0;
                        long timeDiff = Math.abs(matchWhen - submittedMatchEndTs);
                        
                        if (timeDiff > 10) {
                            log.debug("[PostFight] Match timestamp mismatch: matchWhen={} submittedTs={} diff={}s (>10s), skipping this match", 
                                matchWhen, submittedMatchEndTs, timeDiff);
                            continue;
                        }
                        
                        log.debug("[PostFight] Match timestamp validated: matchWhen={} submittedTs={} diff={}s", 
                            matchWhen, submittedMatchEndTs, timeDiff);
                        
                        if (match.has("rating_change") && match.get("rating_change").isJsonObject()) {
                            JsonObject ratingChange = match.getAsJsonObject("rating_change");
                            
                            if (ratingChange.has("mmr_delta") && !ratingChange.get("mmr_delta").isJsonNull()) {
                                double mmrDelta = ratingChange.get("mmr_delta").getAsDouble();
                                
                                String matchBucket = match.has("bucket") && !match.get("bucket").isJsonNull()
                                    ? match.get("bucket").getAsString() : "nh";
                                
                                String bucketLabel = showBucketLabel ? getBucketDisplayName(matchBucket) : null;
                                
                                String result = match.has("result") && !match.get("result").isJsonNull()
                                    ? match.get("result").getAsString() : "win";
                                if ("loss".equalsIgnoreCase(result)) {
                                    mmrDelta = -Math.abs(mmrDelta);
                                }
                                
                                log.debug("[PostFight] Found match in history: opponent={} bucket={} mmrDelta={} result={} bucketLabel={}", 
                                    matchOpponent, matchBucket, mmrDelta, result, bucketLabel);
                                
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
                
                if (attempt < MMR_RETRY_DELAYS.length) {
                    long delay = MMR_RETRY_DELAYS[attempt];
                    log.debug("[PostFight] Match not found in history, retrying in {}s (attempt {})", delay, attempt + 1);
                    scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, showBucketLabel, attempt + 1, submittedMatchEndTs),
                        delay, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    log.debug("[PostFight] Match not found in history after all retries, skipping MMR notification");
                }
            } else if (attempt < MMR_RETRY_DELAYS.length) {
                long delay = MMR_RETRY_DELAYS[attempt];
                log.debug("[PostFight] No matches in response, retrying in {}s (attempt {})", delay, attempt + 1);
                scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, showBucketLabel, attempt + 1, submittedMatchEndTs),
                    delay, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.debug("[PostFight] Failed to get matches after all retries");
            }
        }).exceptionally(ex -> {
            if (attempt < MMR_RETRY_DELAYS.length) {
                long delay = MMR_RETRY_DELAYS[attempt];
                log.debug("[PostFight] Match history exception: {}, retrying in {}s (attempt {})", ex.getMessage(), delay, attempt + 1);
                scheduler.schedule(() -> fetchMmrDeltaFromMatchHistory(selfName, opponentName, displayBucket, showBucketLabel, attempt + 1, submittedMatchEndTs),
                    delay, java.util.concurrent.TimeUnit.SECONDS);
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

    private void fetchTierWithRetry(String playerName, String bucket, int retriesLeft) {
        log.debug("[PostFight] fetchTierWithRetry called: player={} bucket={} retriesLeft={}", playerName, bucket, retriesLeft);
        pvpDataService.getTierFromProfile(playerName, bucket).thenAccept(tier -> {
            if (tier != null) {
                log.debug("[PostFight] SUCCESS: tier={} for player={}", tier, playerName);
                if (rankOverlay != null) {
                    rankOverlay.setRankFromApi(playerName, tier);
                    // Also refresh the looked-up player cache for this bucket if they're in it
                    // This ensures fight results update the cached rank and reset the 1-hour timer
                    rankOverlay.refreshLookedUpPlayer(playerName, bucket, tier);
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

    /** Fast boolean variant of {@link #findActualKiller} — returns
     *  true the moment any other Player is found whose
     *  {@code getInteracting()} is the local player. Used by the
     *  inbound-damage path of {@link #handleHitsplatApplied} to
     *  decide whether to update {@link #lastInboundPvpDamageMs}.
     *  Doesn't allocate a String. Walks the same player list as
     *  findActualKiller so the two stay coherent. */
    private boolean hasPlayerAttacker(Player localPlayer)
    {
        if (client == null || localPlayer == null) return false;
        List<Player> players = client.getPlayers();
        if (players == null) return false;
        for (Player player : players)
        {
            if (player == null || player == localPlayer) continue;
            if (player.getInteracting() == localPlayer) return true;
        }
        return false;
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
