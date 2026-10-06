package com.pvp.leaderboard.game;

import lombok.*;
import com.google.gson.*;
import com.pvp.leaderboard.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.config.PvPLeaderboardConfig.*;
import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.overlay.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.config.*;
import static java.lang.System.*;
import static java.util.concurrent.TimeUnit.*;

@Singleton
@SuppressWarnings("deprecation")
public class FightMonitor
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final ConfigManager configManager;
    private final ScheduledExecutorService scheduler;
    private final ResultSender resultSender;
    private final PvpApi pvpApi;
    private final IdentitySvc identitySvc;
    private final ProfileGate profileGate;

    // RankOverlay reference for MMR notifications
    private RankOverlay rankOverlay;

    // Tracks multiple simultaneous fights (per-opponent) - damage is now tracked per-FightEntry
    private final ConcurrentHashMap<String, FightEntry> activeFights = new ConcurrentHashMap<>();

    // Tick counters
    private int muteTicks;
    private final ConcurrentHashMap<String, Integer> oppMuteUntil = new ConcurrentHashMap<>();
    /** Opponents whose overall shard lookup has answered (only membership is ever read). */
    private final Set<String> shardPresence = ConcurrentHashMap.newKeySet();

    private static final int IDLE_TICKS = 16;
    private int gcTicks;

    /** Wall-clock timestamp (ms) of the most recent inbound damage
     *  hitsplat where the attacker is identifiable as another Player
     *  (not an NPC, not a self-deal). Updated on every qualifying
     *  hit in {@link #handleHit}; used by
     *  {@link #syncInCombat()} to gate popup suppression for
     *  the "passively-attacked, not yet retaliated" case —
     *  {@link #activeFights} is only populated by OUTBOUND damage
     *  (see findAttacker's intentional null-on-no-existing-fight
     *  contract), so without this separate signal a user being
     *  ganked / opener-attacked in a matchmaking arena reads as
     *  out-of-combat for the ~600ms–4s window before they retaliate,
     *  and an invite popup arriving in that window slips through
     *  the suppression gate (QA bug 2026-05-25). */
    private volatile long inboundHitMs;

    /** Push-model cache for {@link #isInCombat()}: combat is state, so it
     *  is cached and updated only at the mutation sites that can change
     *  it, by {@link #syncInCombat()}, which is called from every
     *  site that mutates {@link #activeFights} or
     *  {@link #inboundHitMs}:
     *  <ul>
     *    <li>{@link #handleHit} — after recording inbound
     *        damage and/or seeding/touching a {@link FightEntry}.</li>
     *    <li>{@link #handleTick} — after the 33-tick GC pass
     *        evicts stale entries (combat exit edge).</li>
     *    <li>{@link #endFightFor} — after the conditional
     *        {@link #shouldClear} clear
     *        on kill/death.</li>
     *    <li>{@link #clearFight} — covers the other entry-removal
     *        path used post-fight.</li>
     *    <li>{@link #resetFight} — login/logout bounce reset.</li>
     *  </ul>
     *  Default value {@code false} is the correct login/startup
     *  reading: a fresh plugin start has never seen a combat event
     *  and must not read as in-combat until one fires. */
    @Getter private volatile boolean inCombat;

    // --- LMS freeze-log detection state ---

    /** RuneLite config group these plugin settings live under. */
    public static final String CONFIG_GROUP = "PvPLeaderboard";

    /** Config key for the "show the plugin-disable ban warning on next
     *  login" marker. Written (value {@code "true"}) when a
     *  {@code plugin_disabled} freeze-log is submitted, so the warning
     *  survives the plugin being toggled off; read + cleared by the
     *  plugin on the next {@code LOGGED_IN}. */
    public static final String LMS_WARN_KEY = "lmsDisablePendingWarning";

    /** Config keys for the "show the doubled freeze-log MMR delta on next
     *  login" marker. A freeze-log is submitted at logout / shutdown, so
     *  there is no live session left to poll the match-history API and
     *  surface the {@code -XX.XX MMR} notification the way a normal loss
     *  does. Instead the submitted opponent id + fight_end_ts are
     *  persisted here (surviving the plugin being toggled off) and read
     *  back on the next {@code LOGGED_IN} to run the identical delta
     *  fetch + {@link RankOverlay#showMmrDelta} display. */
    public static final String LMS_OPP_KEY = "lmsFreezeMmrOpponent";
    public static final String LMS_END_KEY = "lmsFreezeMmrEndTs";

    /** How long an in-progress fight is retained for freeze-log
     *  purposes while the player is in an LMS arena. Much longer than
     *  the normal 10s idle-GC window because LMS freeze-logging
     *  happens deliberately after the combat timer has lapsed. */
    static final long LMS_KEEP_MS = 90_000L;

    /** How recently (ms) the player must have been confirmed inside an
     *  LMS arena for a logout to count as a freeze-log. Game ticks fire
     *  ~every 600ms while logged in, so {@link #lmsSeenMs} is
     *  sub-second-fresh at a genuine mid-arena logout; the window is
     *  padded to absorb a couple of missed ticks. */
    static final long LMS_AREA_MS = 10_000L;

    /** Wall-clock ms of the last game tick on which the local player
     *  was confirmed standing inside an {@link PvpConsts#LMS_AREAS}
     *  rectangle on an LMS world. Cached because at {@code LOGIN_SCREEN}
     *  (and during {@code shutDown}) the local player object is gone. */
    private volatile long lmsSeenMs;

    /** World number captured alongside {@link #lmsSeenMs}. */
    private volatile int lastLmsWorld;

    /** Local player name captured on the last tick it was resolvable,
     *  used as the {@code player_id} for a freeze-log submission when
     *  the live player object is already gone. */
    private volatile String lastSelfName;

    /** In-memory guard so a pending freeze-log MMR replay is scheduled at
     *  most once per login session. Both {@code startUp}'s already-logged-in
     *  branch and the {@code LOGGED_IN} event call
     *  {@link #showLmsMmr()}, and a world hop
     *  re-fires {@code LOGGED_IN}. Reset on {@link #resetFight()}
     *  (logout) so a genuine relog re-attempts when the prior login's
     *  fetch failed and left the persistent marker intact. */
    private volatile boolean mmrReplaying;

    @Inject
    public FightMonitor(
        Client client,
        PvPLeaderboardConfig config,
        ConfigManager configManager,
        ScheduledExecutorService scheduler,
        ResultSender resultSender,
        PvpApi pvpApi,
        IdentitySvc identitySvc,
        ProfileGate profileGate)
    {
        this.client = client;
        this.config = config;
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.resultSender = resultSender;
        this.pvpApi = pvpApi;
        this.identitySvc = identitySvc;
        this.profileGate = profileGate;
    }

    /**
     * Initialize with RankOverlay for MMR change notifications.
     */
    public void init(RankOverlay rankOverlay)
    {
        this.rankOverlay = rankOverlay;
    }

    /** Plan 10 F.2 (AS-72): while the local player is in a RUNNING tournament
     *  (and the config allows it) the auto-switch targets the Tournament
     *  bucket instead of the fight's style, so a tournament game does not
     *  flip the panel to NH. Cleared by the session tracker when the player
     *  leaves the bracket; the next ordinary fight then switches like every
     *  bucket. Wired by the plugin; defaults to "never pinned". */
    @Setter private volatile BooleanSupplier tournamentBucketPin = () -> false;

    /** The bucket the auto-switch should land on: the Tournament bucket while
     *  pinned, else the fight's own bucket (may be {@code null} when unmapped). */
    static RankBucket resolveAutoSwitchTarget(RankBucket fightBucket, boolean isPinned)
    {
        return isPinned ? RankBucket.TOURNAMENT : fightBucket;
    }

    public void resetFight()
    {
        muteTicks = 2;
        activeFights.clear();
        oppMuteUntil.clear();
        shardPresence.clear();
        // Reset the inbound-PvP-damage signal too — a logout/login
        // or game-state bounce should not leave the popup-suppression
        // gate stuck on "in combat" indefinitely.
        inboundHitMs = 0L;
        // Allow the freeze-log MMR replay to re-attempt on the next login
        // (logout clears the in-session guard, not the persistent marker).
        mmrReplaying = false;
        syncInCombat();
    }

    /** The bucket of the last fight this session classified — the
     *  kill-streak box's "current style" (BOARD row 33); {@code null} until
     *  then. Deliberately not cleared by {@link #resetFight()}: a relog
     *  does not change the style you were last fighting in. */
    private volatile RankBucket lastBucket;

    /** Where the bucket auto-switch would land right now: the Tournament
     *  bucket while pinned, else the last classified fight's bucket, else
     *  {@code null} (no fight yet this session). The kill-streak box reads
     *  this so "which style am I in" has one source of truth. */
    public RankBucket getAutoSwitchTarget()
    {
        return resolveAutoSwitchTarget(lastBucket, tournamentBucketPin.getAsBoolean());
    }

    @Setter private volatile BiConsumer<String, String> streakSink;

    @Setter private volatile Consumer<String> profileRefreshSink;

    Consumer<JsonObject> streakResolver(String guessBucket, String guessResult)
    {
        var settled = new AtomicBoolean();
        return row -> {
            BiConsumer<String, String> sink = streakSink;
            if (sink == null || !settled.compareAndSet(false, true)) return;
            String bucket = nonBlank(JsonLenient.optString(row, "bucket", null), guessBucket);
            String result = nonBlank(JsonLenient.optString(row, "result", null), guessResult);
            try
            {
                sink.accept(bucket, result);
            }
            catch (RuntimeException e)
            {
            }
        };
    }

    private static String nonBlank(String value, String fallback)
    {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    /** Recency window (ms) for {@link #isInCombat()}. Matches the GC
     *  threshold inside {@link #handleTick} so the
     *  popup-suppression window is coherent with when stale fights
     *  drop out of {@link #activeFights}. */
    static final long COMBAT_MS = 10_000L;

    /** Pure recency predicate. {@code true} iff {@code activityMs}
     *  sits within {@link #COMBAT_MS} of {@code nowMs}.
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
    static boolean isInWindow(long activityMs, long nowMs)
    {
        long elapsed = nowMs - activityMs;
        if (elapsed < 0L) return true;
        return elapsed <= COMBAT_MS;
    }

    /** Recompute {@link #inCombat} from the current values of
     *  {@link #activeFights} and {@link #inboundHitMs}.
     *  Called from every site that mutates either of those (see
     *  {@link #inCombat} field docs for the enumerated list).
     *
     *  <p><b>Decision rule:</b> in combat iff EITHER any non-finalized
     *  entry exists in {@link #activeFights} (regardless of recency — let
     *  the 33-tick GC at {@link #handleTick} decide when to evict),
     *  OR {@link #inboundHitMs} is within
     *  {@link #COMBAT_MS} of now. Both branches are pinned
     *  by {@link FightMonitorCombatWindowTest}.
     *
     *  <p><b>Logging:</b> one DEBUG line per {@code false→true} or
     *  {@code true→false} transition; steady state is silent. */
    void syncInCombat()
    {
        long now = currentTimeMillis();
        int fightCount = 0;
        for (FightEntry fe : activeFights.values())
        {
            if (!fe.finalized) fightCount++;
        }
        long inboundMs = inboundHitMs;
        boolean newValue = isInCombatDecision(inboundMs, fightCount > 0, now);

        if (newValue != inCombat)
        {
            inCombat = newValue;
        }
    }

    /** Pure decision helper: in combat iff EITHER
     *  {@code inboundHitMs} is within
     *  {@link #COMBAT_MS} of {@code nowMs}, OR
     *  {@code inFight} is true. Both signals are independent;
     *  either alone is sufficient.
     *
     *  <p>The active-fight branch deliberately does NOT consult a
     *  recency window: the source of truth for "match is over" is the GC
     *  pass that removes idle entries from {@link #activeFights},
     *  not a parallel 10s recency check that would un-suppress the
     *  popup before the GC actually runs. The inbound-damage branch
     *  still uses the recency window because it has no FightEntry
     *  backing it (no GC to defer to).
     *
     *  <p>Extracted as a static helper so
     *  {@code FightMonitorCombatWindowTest} can pin the two-signal
     *  OR-gate without instantiating FightMonitor. */
    static boolean isInCombatDecision(long inboundHitMs, boolean inFight, long nowMs)
    {
        if (inboundHitMs > 0L
            && isInWindow(inboundHitMs, nowMs)) return true;
        return inFight;
    }

    /**
     * Clear only a specific fight without affecting other ongoing fights.
     * Used when a fight ends but other multi-combat fights continue.
     */
    private void clearFight(String opponentName)
    {
        activeFights.remove(opponentName);
        oppMuteUntil.put(opponentName, 5);
        shardPresence.remove(opponentName);

        // Only reset global state if ALL fights are done
        if (activeFights.isEmpty())
        {
            muteTicks = 2;
        }
        // Recompute the cached in-combat flag — removing a FightEntry
        // can flip the gate to false (singles kill, last multi
        // opponent cleared, GC after fight-finalize).
        syncInCombat();
    }

    public void handleTick(GameTick tick)
    {
        try
        {
            // Handle fight suppression ticks
            if (muteTicks > 0)
            {
                muteTicks--;
            }

            // Per-opponent suppression: one tick less each; gone at zero.
            oppMuteUntil.replaceAll((name, ticks) -> ticks - 1);
            oppMuteUntil.values().removeIf(ticks -> ticks < 1);

            // Refresh the LMS-arena presence cache BEFORE the GC pass so
            // the extended-retention decision below sees the current
            // tick's position (and so a mid-arena logout has a fresh
            // lmsSeenMs to read after the player object is gone).
            updateLms();
            updateAreaFlags();

            // Handle GC (every 33 ticks approx 20s)
            if (++gcTicks >= 33)
            {
                gcTicks = 0;
                long now = currentTimeMillis();
                // While the player is inside an LMS arena, retain
                // in-progress fights far longer (90s) so a deliberate
                // freeze-log after the combat timer lapses still has a
                // live FightEntry to submit as a doubled loss. Outside
                // LMS the normal 10s idle window is unchanged.
                long idleLimit = isLmsRecent(lmsSeenMs, now) ? LMS_KEEP_MS : 10_000L;
                activeFights.values().removeIf(fe -> !fe.finalized && now - fe.activityMs > idleLimit);
            }

            // Recompute the cached in-combat flag every tick: the
            // inbound-damage recency window expires without an event, and
            // the GC pass above can evict entries. A no-op when the value
            // is unchanged.
            syncInCombat();
        }
        catch (Exception e)
        {
            // log.error("Uncaught exception in FightMonitor.onGameTick", e);
        }
    }

    @Setter private volatile ObjIntConsumer<String> combatSink;

    static boolean isOwnHitsplat(int hitsplatType, boolean isMine)
    {
        return isMine || hitsplatType == HitsplatID.DAMAGE_ME || hitsplatType == HitsplatID.BLOCK_ME;
    }

    private void reportOwnHit(String playerName)
    {
        ObjIntConsumer<String> sink = combatSink;
        if (sink == null) return;
        try
        {
            sink.accept(playerName, client.getWorld());
        }
        catch (RuntimeException e)
        {
        }
    }

    public void handleHit(HitsplatApplied event)
    {
        try
        {
            if (!(event.getActor() instanceof Player)) return;

            Player hitPlayer = (Player) event.getActor();
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer == null) return;

            Hitsplat hs = event.getHitsplat();
            if (hs == null) return;

            int hitsplatType = hs.getHitsplatType();
            int amt = hs.getAmount();
            boolean isMine = hs.isMine();
            String hitName = hitPlayer.getName();

            if (hitPlayer != localPlayer && hitName != null && isOwnHitsplat(hitsplatType, isMine))
            {
                reportOwnHit(hitName);
            }

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
            boolean isDamage = (amt > 0) &&
                (hitsplatType == HitsplatID.DAMAGE_ME || hitsplatType == HitsplatID.DAMAGE_OTHER);

            // Check if this is OUR damage - either isMine is true OR hitsplatType is DAMAGE_ME
            // DAMAGE_ME is the red hitsplat type shown specifically for YOUR damage on others
            // We check both because isMine can sometimes be incorrectly false
            boolean isOurDamage = isMine || (hitsplatType == HitsplatID.DAMAGE_ME);

            String opponentName = null;
            boolean startNow = false;

            if (hitPlayer == localPlayer)
            {
                // Hitsplat on us = opponent dealt damage to us (inbound).
                // Mark the inbound-PvP-damage timestamp BEFORE the
                // findAttacker call so the popup-suppression
                // gate engages even when there's no active fight yet
                // (findAttacker only returns existing fight
                // opponents, by design, to prevent NPC/trade/item-use
                // damage from spawning false PvP fights). Any other Player
                // whose getInteracting() is the local player counts as our
                // attacker for combat-state purposes only.
                if (isDamage && hasAttacker(localPlayer))
                {
                    inboundHitMs = currentTimeMillis();
                }
                opponentName = findAttacker(localPlayer);
                startNow = opponentName != null && isDamage;
            }
            else
            {
                // Hitsplat on another player (outbound)
                if (hitName == null) return;

                // If this is OUR damage (DAMAGE_ME type), this player is definitely our opponent
                // Don't rely on getInteracting() which can be unreliable in chaotic multi-combat
                if (isOurDamage && isDamage)
                {
                    opponentName = hitName;
                    startNow = true;
                }
                else
                {
                    // For non-damage hitsplats or other players' damage, use targeting checks.
                    // All three are read, and the casts stay: a non-player target throws
                    // here and skips the rest of this hitsplat, as it always has.
                    boolean isActiveOpponent = activeFights.containsKey(hitName);
                    boolean isTarget = (Player) localPlayer.getInteracting() == hitPlayer;
                    boolean targetsUs = (Player) hitPlayer.getInteracting() == localPlayer;

                    if (isActiveOpponent || isTarget || targetsUs)
                    {
                        opponentName = hitName;
                    }
                }
            }

            // Handle fight start/continuation
            boolean validOpp = (opponentName != null && isOpponent(opponentName));
            if (startNow && validOpp)
            {
                int tickNow = client.getTickCount();

                if (opponentName.equals(localPlayer.getName())) return;
                if (muteTicks > 0) return;
                if (oppMuteUntil.containsKey(opponentName)) return;

                // Check if THIS opponent's fight is stale (no activity for 16+ ticks)
                // If stale, remove only this opponent's fight entry so a fresh one is created
                FightEntry existingFight = activeFights.get(opponentName);
                if (existingFight != null && hitPlayer != localPlayer && existingFight.isStale(tickNow, IDLE_TICKS))
                {
                    activeFights.remove(opponentName);
                }

                touchFight(opponentName);
            }

            // Add the damage to per-fight tracking in FightEntry
            if (opponentName != null && amt > 0)
            {
                FightEntry fe = activeFights.get(opponentName);
                if (fe != null)
                {
                    int currentTick = client.getTickCount();
                    fe.mark(false, insideFfaPortal, insideBounty);

                    // Update combat timestamps for "hide rank out of combat" feature
                    if (rankOverlay != null)
                    {
                        rankOverlay.updateSelfCombatTime();
                    }

                    if (hitPlayer == localPlayer)
                    {
                        // Damage received from opponent
                        fe.addReceived(amt, currentTick);
                    }
                    else if (isOurDamage)
                    {
                        // Count damage dealt if it's OUR damage (isMine=true OR type=DAMAGE_ME)
                        fe.addDealt(amt, currentTick);
                    }
                }
            }

            // Recompute the cached in-combat flag — this hitsplat
            // path is the primary "false→true" transition source
            // (inbound damage seeds {@link #inboundHitMs};
            // outbound damage seeds {@link #activeFights}).
            syncInCombat();
        }
        catch (Exception e)
        {
            // log.debug("Uncaught exception in FightMonitor.onHitsplatApplied", e);
        }
    }

    public void handleDeath(ActorDeath event)
    {
        try
        {
            if (!(event.getActor() instanceof Player)) return;
            Player player = (Player) event.getActor();
            Player localPlayer = client.getLocalPlayer();

            if (player == localPlayer)
            {
                String killer = findKillerByDamage();

                if (killer == null) killer = findKiller(localPlayer);
                if (killer == null) killer = recentOpp();

                if (killer != null)
                {
                    endFightFor(killer, "loss");
                }

                for (String remaining : new ArrayList<>(activeFights.keySet()))
                {
                    clearFight(remaining);
                }

                resetFight();
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
        }
    }

    private void endFightFor(String opponentName, String result)
    {
        FightEntry fe = activeFights.get(opponentName);
        if (fe == null || fe.finalized) return;

        fe.finalized = true;
        finalizeFight(opponentName, result, fe);
        clearFight(opponentName);  // Use targeted clear instead of resetFight

        // Release the popup-suppression gate the moment the match is
        // submitted, instead of waiting up to {@link #COMBAT_MS}
        // for the lingering {@link #inboundHitMs} from the
        // just-finished fight to age out (user spec 2026-05-25). Stays
        // engaged while any other {@code activeFights} entry remains
        // OR a fresh player attacker is interacting-with the local player.
        Player local = client.getLocalPlayer();
        if (shouldClear(!activeFights.isEmpty(), local != null && hasAttacker(local)))
        {
            inboundHitMs = 0L;
        }
        // Final recompute after all the kill-path mutations.
        syncInCombat();
    }

    /** Pure decision helper: should {@link #inboundHitMs}
     *  be cleared after {@link #endFightFor} finalizes a fight?
     *
     *  <p>Clear iff BOTH:
     *  <ul>
     *    <li>{@code othersActive} is {@code false} — no
     *        multi-opponent FightEntry still in
     *        {@link #activeFights}. In multi the gate must stay
     *        engaged until ALL multi-opponents are cleared.</li>
     *    <li>{@code beingHit} is {@code false} — no Player
     *        on the scene whose {@code getInteracting()} is the local
     *        player (the singles-with-fresh-attacker flicker case).</li>
     *  </ul>
     *
     *  <p>Extracted as a static helper so
     *  {@code FightMonitorCombatWindowTest} can pin the truth table
     *  without instantiating FightMonitor. */
    static boolean shouldClear(boolean othersActive,
                                                           boolean beingHit)
    {
        return !othersActive && !beingHit;
    }

    @Getter private volatile boolean insideFfaPortal;

    static boolean isInFfa(WorldPoint wp)
    {
        return wp != null && PvpConsts.isFfaArea(wp.getX(), wp.getY());
    }

    private volatile boolean insideBounty;

    static boolean isInBounty(WorldPoint wp)
    {
        return wp != null && PvpConsts.isBountyArea(wp.getX(), wp.getY());
    }

    /** The local player's world tile ({@link WorldPoint#fromLocalInstance}: the
     *  template tile in an instance), or {@code null} without a player or location. */
    private WorldPoint selfPoint()
    {
        Player lp = client.getLocalPlayer();
        LocalPoint lpnt = lp == null ? null : lp.getLocalLocation();
        return lpnt == null ? null : WorldPoint.fromLocalInstance(client, lpnt);
    }

    /** Refresh the FFA portal and Bounty Hunter flags from one read of the
     *  player's tile; anything thrown reads as outside both. */
    private void updateAreaFlags()
    {
        boolean ffaPortal = false;
        boolean bountyHunter = false;
        try
        {
            WorldPoint wp = selfPoint();
            ffaPortal = isInFfa(wp);
            bountyHunter = isInBounty(wp);
        }
        catch (Exception ignore)
        {
            // Defensive: presence tracking must never break the tick loop.
        }
        insideFfaPortal = ffaPortal;
        insideBounty = bountyHunter;
    }

    // ------------------------------------------------------------------
    // LMS freeze-log detection
    // ------------------------------------------------------------------

    /** Pure recency predicate for LMS-arena presence — mirrors
     *  {@link #isInWindow}. A never-set ({@code <= 0})
     *  timestamp reads as "not in area". Negative elapsed (clock skew)
     *  reads as in-area (safer side). */
    static boolean isLmsRecent(long lmsSeenMs, long nowMs)
    {
        if (lmsSeenMs <= 0L) return false;
        long elapsed = nowMs - lmsSeenMs;
        if (elapsed < 0L) return true;
        return elapsed <= LMS_AREA_MS;
    }

    /** Pure freeze-log gate: submit a doubled loss iff the player is on
     *  an LMS world, was inside an LMS arena recently, and an eligible
     *  in-progress fight exists. Extracted static so the truth table is
     *  unit-testable without the injected-client graph (mirrors
     *  {@link #isInCombatDecision} / {@link #shouldClear}). */
    static boolean shouldFreeze(boolean lmsWorld,
                                         boolean inLmsLately,
                                         boolean hasEligible)
    {
        return lmsWorld && inLmsLately && hasEligible;
    }

    /** Normalise the caller-supplied reason to one of the two known
     *  wire values. Anything other than {@code "plugin_disabled"}
     *  (including null / blank) collapses to {@code "logout"} — the
     *  benign, no-ban variant. */
    static String cleanReason(String reason)
    {
        return "plugin_disabled".equals(reason) ? "plugin_disabled" : "logout";
    }

    /** Refresh the cached LMS-arena presence + self name from the live
     *  client. No-op unless the player is on an LMS world, inside an
     *  instanced region, and physically within an {@link
     *  PvpConsts#LMS_AREAS} rectangle (template coords via
     *  {@link WorldPoint#fromLocalInstance}). */
    private void updateLms()
    {
        try
        {
            Player lp = client.getLocalPlayer();
            if (lp == null) return;
            String name = lp.getName();
            if (name != null && !name.trim().isEmpty())
            {
                lastSelfName = name;
            }
            int world = client.getWorld();
            if (!PvpConsts.isLmsWorld(world)) return;
            if (!client.isInInstancedRegion()) return;
            WorldPoint wp = selfPoint();
            if (wp != null && PvpConsts.isInLmsArea(wp.getX(), wp.getY()))
            {
                lmsSeenMs = currentTimeMillis();
                lastLmsWorld = world;
            }
        }
        catch (Exception ignore)
        {
            // Defensive: presence tracking must never break the tick loop.
        }
    }

    /** Pick the opponent for a freeze-log loss: the most-recently-active
     *  non-finalized fight that exchanged damage and is still within the
     *  extended LMS retention window. Returns null when none qualifies. */
    private String findLmsOpp(long nowMs)
    {
        String best = null;
        long bestActivity = -1L;
        for (Map.Entry<String, FightEntry> e : activeFights.entrySet())
        {
            FightEntry fe = e.getValue();
            if (fe.finalized) continue;
            boolean damageTraded = fe.damageDealt.get() > 0 || fe.damageReceived.get() > 0;
            if (!damageTraded) continue;
            if (nowMs - fe.activityMs > LMS_KEEP_MS) continue;
            if (fe.activityMs > bestActivity)
            {
                bestActivity = fe.activityMs;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * Detect and submit an LMS freeze-log. Called from the plugin's
     * {@code LOGIN_SCREEN} (reason {@code "logout"}) and {@code shutDown}
     * (reason {@code "plugin_disabled"}) paths <b>before</b>
     * {@link #resetFight}. When an eligible in-progress fight is
     * found in an LMS arena, it is finalized as a {@code loss} carrying
     * the {@code lms_freeze_logout} flag (backend doubles the MMR loss).
     * For {@code plugin_disabled} it additionally persists a
     * pending-warning marker so the ban warning shows on next login.
     *
     * <p>Double-submit safe: the chosen fight's {@code finalized} flag
     * is set before submission, so a second call (e.g. LOGIN_SCREEN then
     * shutDown) is a no-op.
     *
     * @param reason {@code "logout"} or {@code "plugin_disabled"}
     * @return the submission future when a freeze-log was submitted, or
     *         {@code null} when nothing qualified.
     */
    public CompletableFuture<Boolean> handleFreeze(String reason)
    {
        try
        {
            long now = currentTimeMillis();
            boolean lmsWorld = PvpConsts.isLmsWorld(lastLmsWorld);
            boolean inArea = isLmsRecent(lmsSeenMs, now);
            String oppName = (lmsWorld && inArea) ? findLmsOpp(now) : null;
            if (!shouldFreeze(lmsWorld, inArea, oppName != null))
            {
                return null;
            }
            FightEntry fe = activeFights.get(oppName);
            if (fe == null || fe.finalized) return null;
            // Guard against double-submit across LOGIN_SCREEN + shutDown.
            fe.finalized = true;

            String normReason = cleanReason(reason);
            CompletableFuture<Boolean> future =
                finalizeLms(oppName, lastLmsWorld, fe, normReason);
            if ("plugin_disabled".equals(normReason))
            {
                markWarning();
            }
            return future;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Persist the "show ban warning on next login" marker. Written to
     *  RuneLite config so it survives the plugin being toggled off. */
    private void markWarning()
    {
        try
        {
            configManager.setConfiguration(CONFIG_GROUP, LMS_WARN_KEY, "true");
        }
        catch (Exception e)
        {
        }
    }

    /** Persist the "show the doubled freeze-log MMR delta on next login"
     *  marker (opponent id + the fight_end_ts the loss was submitted
     *  with). Written to RuneLite config so it survives the plugin being
     *  toggled off / the client restarting. */
    private void markLmsMmr(String opponentName, long endTs)
    {
        try
        {
            configManager.setConfiguration(CONFIG_GROUP, LMS_OPP_KEY, opponentName);
            configManager.setConfiguration(CONFIG_GROUP, LMS_END_KEY, String.valueOf(endTs));
        }
        catch (Exception e)
        {
        }
    }

    /** Clear the pending freeze-log MMR marker (both keys). */
    private void clearLmsMmr()
    {
        try
        {
            configManager.unsetConfiguration(CONFIG_GROUP, LMS_OPP_KEY);
            configManager.unsetConfiguration(CONFIG_GROUP, LMS_END_KEY);
        }
        catch (Exception e)
        {
        }
    }

    /**
     * Surface the doubled MMR loss from a prior-session freeze-log the
     * next time the player logs in. If the pending marker is present the
     * <em>same</em> match-history delta fetch the normal post-fight path
     * uses is scheduled — so the player sees the identical {@code -XX.XX
     * MMR} overlay a real loss produces, just triggered on login instead
     * of at fight end. No marker → complete no-op.
     *
     * <p>The fetch is deferred a few seconds so client identity (account
     * SHA) and the local player name resolve after login before the API
     * call. The marker is cleared only after the delta is displayed (the
     * onDisplayed hook), so a bad-connection login where the fetch never
     * lands leaves it intact to retry on the next login.
     */
    public void showLmsMmr()
    {
        try
        {
            String opponent = configManager.getConfiguration(CONFIG_GROUP, LMS_OPP_KEY);
            String endTsStr = configManager.getConfiguration(CONFIG_GROUP, LMS_END_KEY);
            if (opponent == null || opponent.trim().isEmpty()
                || endTsStr == null || endTsStr.trim().isEmpty())
            {
                return;
            }

            long endTs;
            try
            {
                endTs = Long.parseLong(endTsStr.trim());
            }
            catch (NumberFormatException nfe)
            {
                // Corrupt marker — drop it so it can't wedge future logins.
                clearLmsMmr();
                return;
            }

            // In-session guard: startUp's logged-in branch AND the
            // LOGGED_IN event both call this, and a world hop re-fires
            // LOGGED_IN — schedule the replay at most once per login. The
            // guard is reset on logout (resetFight), so a genuine
            // relog re-attempts if the prior login's fetch failed.
            if (mmrReplaying)
            {
                return;
            }
            mmrReplaying = true;

            String selfName = lastSelfName != null ? lastSelfName : getLocalName();
            scheduler.schedule(
                () -> fetchDelta(selfName, opponent, false, 0, endTs, this::clearLmsMmr, null),
                5L, SECONDS);
        }
        catch (Exception e)
        {
        }
    }

    /** The fight as the backend records it; the caller adds the client id and any flags. */
    private static MatchResult.MatchResultBuilder matchOf(String self, String opponent, String result, int world, FightEntry fe,
        long endTs, String startBook, String endSpellbook)
    {
        return MatchResult.builder()
            .playerId(self)
            .opponentId(opponent)
            .result(result)
            .world(world)
            .fightStartTs(fe.startTs)
            .fightEndTs(endTs)
            .fightStartSpellbook(startBook)
            .fightEndSpellbook(endSpellbook)
            .wasInMulti(fe.wasInMulti)
            .damageToOpponent(fe.damageDealt.get());
    }

    /** Build + submit the freeze-log loss using cached values (the live
     *  player object is already gone at logout / shutdown). Spellbook is
     *  best-effort from the fight's start value; the backend penalty is
     *  bucket-independent. */
    private CompletableFuture<Boolean> finalizeLms(String opponentName,
                                                              int world,
                                                              FightEntry fe,
                                                              String reason)
    {
        String selfName = lastSelfName;
        if (selfName == null)
        {
            selfName = getLocalName();
        }
        long endTs = currentTimeMillis() / 1000;
        String sb = getBookName(fe.startBook);
        MatchResult match = matchOf(selfName, opponentName, "loss", world, fe, endTs, sb, sb)
            .clientUniqueId(identitySvc.getClientUniqueId())
            .lmsFreezeLogout(true)
            .lmsFreezeReason(reason)
            .build();
        // Persist the pending-MMR marker BEFORE the fire-and-forget submit:
        // the player is logging out / disabling the plugin, so this session
        // can't poll for the resulting delta. On the next login we replay
        // the normal post-fight fetch keyed on this opponent + fight_end_ts.
        markLmsMmr(opponentName, endTs);
        return resultSender.submitResult(match);
    }

    /**
     * Submit the finished fight and, once the server has answered (or the
     * submit failed), fetch its rating change. The match is built on the
     * client thread; the client id is read and the submit made on the
     * scheduler, so the fetch always follows the server's acknowledgement.
     */
    private void finalizeFight(String opponentName, String result, FightEntry entry)
    {
        int endSb = client.getVarbitValue(Varbits.SPELLBOOK);
        int world = client.getWorld();
        long endTs = currentTimeMillis() / 1000;
        String selfName = getLocalName();
        entry.mark(false, insideFfaPortal, insideBounty);
        boolean ffaPortal = entry.wasInFfa;
        String startBook = getBookName(entry.startBook);
        String endSpellbook = getBookName(endSb);
        MatchResult.MatchResultBuilder match = matchOf(selfName, opponentName, result, world, entry, endTs, startBook, endSpellbook)
            .ffaPortal(ffaPortal)
            .bountyHunter(entry.wasInBounty);

        // Determine the bucket this fight will be classified as (server-side logic)
        RankBucket fb = determineBucket(world, entry.wasInMulti, startBook, endSpellbook, entry.wasInBounty);
        String fightBucket = RankBucket.key(fb);
        lastBucket = fb;
        Consumer<JsonObject> streakOutcome =
            streakResolver(tournamentBucketPin.getAsBoolean() ? "tournament" : fightBucket, result);

        // Show the bucket label on the rating pop-up when the fight's bucket is
        // not the one the leaderboard shows; with auto-switch on, switch to it.
        RankBucket currentBucket = config.rankBucket();
        // Plan 10 F.2: a running tournament pins the target to the Tournament bucket.
        boolean isPinned = tournamentBucketPin.getAsBoolean();
        RankBucket target = resolveAutoSwitchTarget(fb, isPinned);
        boolean bucketInMmr = currentBucket != target;
        String apiBucket;
        if (config.autoSwitchBucket())
        {
            if (bucketInMmr)
            {
                // Defer config write off the game thread — setConfiguration triggers
                // config change listeners, panel rebuilds, and disk I/O synchronously
                scheduler.execute(() -> configManager.setConfiguration(CONFIG_GROUP, "rankBucket", target.name()));
            }
            else
            {
            }
            // Use fight bucket for API refresh when auto-switch is enabled;
            // a pinned tournament game is rated in the tournament bucket (Plan 10 A.3).
            apiBucket = isPinned ? "tournament" : fightBucket;
        }
        else
        {
            apiBucket = RankBucket.key(config.rankBucket());
        }

        Runnable postFight = () -> fetchLater(selfName, opponentName, bucketInMmr, endTs, ffaPortal, streakOutcome);
        CompletableFuture.supplyAsync(() -> {
            try
            {
                return resultSender.submitResult(match.clientUniqueId(identitySvc.getClientUniqueId()).build());
            }
            catch (Exception e)
            {
                return CompletableFuture.completedFuture(false);
            }
        }, scheduler).thenCompose(f -> f).thenAccept(success -> {
            if (Boolean.TRUE.equals(success))
            {
                scheduleLobbyGateRefresh();
            }
            postFight.run();
        }).exceptionally(ex -> {
            postFight.run();
            return null;
        });

        // Tier refreshes for overlay (don't depend on match being processed)
        refreshTiers(opponentName, apiBucket);
    }

    private void fetchLater(String selfName, String opponent, boolean bucketInMmr, long endTs,
                                        boolean ffaPortal, Consumer<JsonObject> streakOutcome)
    {
        boolean wanted = config.showMmrChangeNotification() || (streakSink != null
            && WinStreakOverlay.isShown(config.showKillStreakBox(), config.killStreakBoxInFfaPortal(), ffaPortal));
        if (!wanted || selfName == null)
        {
            settleStreak(streakOutcome, null);
            return;
        }
        scheduler.schedule(() -> fetchDelta(selfName, opponent, bucketInMmr, 0, endTs, null, streakOutcome),
            3L, SECONDS);
    }

    private static void settleStreak(Consumer<JsonObject> streakOutcome, JsonObject row)
    {
        if (streakOutcome != null) streakOutcome.accept(row);
    }

    /** Re-fetch the local player's cumulative_stats for the lobby
     *  SMURF_GUARD gate after a successful match submit. Delayed so
     *  the backend has time to fold the new fight into /user. */
    private void scheduleLobbyGateRefresh()
    {
        scheduler.schedule(() -> {
            try
            {
                if (profileGate.isLoggedIn())
                {
                    profileGate.refresh();
                }
            }
            catch (Exception e)
            {
            }
        }, 5L, SECONDS);
    }

    /**
     * Refresh tier/rank display for self and opponent.
     * Runs 5s after fight end — independent of match submission.
     */
    private void refreshTiers(String opponentName, String displayBucket) {
        scheduler.schedule(() -> {
            try {
                String selfName = getLocalName();

                if (selfName != null) {
                    retryTier(selfName, displayBucket, 3);
                }
                retryTier(opponentName, displayBucket, 3);
            } catch (Exception e) {
            }
        }, 5L, SECONDS);
    }

    /** Find this fight's row in the player's match history (the same opponent,
     *  within 10 s of the submitted end) and pop its rating change. The row
     *  goes to {@code onRow} (the streak hook) first; {@code onDisplayed} runs
     *  after the pop-up. Not found, or a row that cannot be read: retry in 5 s,
     *  at most twice, then settle {@code onRow} with no row. */
    void fetchDelta(String selfName, String opponentName, boolean withBucket, int attempt,
                                       long submitEndTs, Runnable onDisplayed, Consumer<JsonObject> onRow) {
        Runnable retry = () -> {
            if (attempt < 2) {
                scheduler.schedule(() -> fetchDelta(selfName, opponentName, withBucket, attempt + 1,
                    submitEndTs, onDisplayed, onRow), 5L, SECONDS);
            } else {
                settleStreak(onRow, null);
            }
        };

        // Use account SHA for accurate match history across name changes; fall
        // back to the name. 15 rows for multi-kills, bypassing the cache.
        String selfAcctSha = pvpApi.getSelfSha();
        pvpApi.getMatches(selfAcctSha, selfName, null, 15, true).thenAccept(response -> {
            JsonArray matches = JsonLenient.optArray(response, "matches");

            // Log first few matches for diagnostics
            for (int i = 0; i < Math.min(matches.size(), 5); i++) {
                if (!matches.get(i).isJsonObject()) continue;
                JsonObject m = matches.get(i).getAsJsonObject();
                JsonElement o = val(m, "opponent_id");
                JsonElement w = val(m, "when");
                String mOpp = o == null ? "?" : o.getAsString();
                long mWhen = w == null ? 0 : w.getAsLong();
            }

            for (JsonElement element : matches) {
                if (!element.isJsonObject()) continue;
                JsonObject match = element.getAsJsonObject();
                JsonElement opp = val(match, "opponent_id");
                if (opp == null || !opp.getAsString().equalsIgnoreCase(opponentName)) continue;
                JsonElement when = val(match, "when");
                long matchWhen = when == null ? 0 : when.getAsLong();
                long timeDiff = Math.abs(matchWhen - submitEndTs);
                if (timeDiff > 10) {
                    continue;
                }
                settleStreak(onRow, match);

                JsonObject ratingChange = JsonLenient.optObject(match, "rating_change");
                JsonElement delta = ratingChange == null ? null : val(ratingChange, "mmr_delta");
                if (delta == null) {
                    return;
                }
                double mmrDelta = delta.getAsDouble();
                JsonElement bucket = val(match, "bucket");
                String matchBucket = bucket == null ? "nh" : bucket.getAsString();
                String bucketLabel = withBucket ? getBucketDisplayName(matchBucket) : null;
                JsonElement res = val(match, "result");
                String result = res == null ? "win" : res.getAsString();
                if ("loss".equalsIgnoreCase(result)) {
                    mmrDelta = -Math.abs(mmrDelta);
                }

                boolean portalCapped = PortalCap.isCapped(match);

                if (rankOverlay != null) {
                    if (portalCapped) {
                        rankOverlay.showCapped(bucketLabel);
                    } else {
                        rankOverlay.showMmrDelta(mmrDelta, bucketLabel);
                    }
                }
                // Delta surfaced — let the caller finalize. The freeze-log login
                // replay uses this to clear its persistent marker ONLY after a
                // successful display.
                if (onDisplayed != null) {
                    onDisplayed.run();
                }
                return;
            }
            retry.run();
        }).exceptionally(ex -> {
            retry.run();
            return null;
        });
    }

    /** The value at {@code key}; {@code null} when absent or JSON null. No other
     *  leniency: a strict {@code getAsX} on the result still throws as before. */
    private static JsonElement val(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    /** The local player's own profile was just refreshed: tell the sink. */
    private void reportOwn(String playerName)
    {
        Consumer<String> sink = profileRefreshSink;
        if (sink == null || playerName == null) return;
        String self = getLocalName();
        if (self == null || !NameUtils.canonicalKey(self).equals(NameUtils.canonicalKey(playerName))) return;
        try
        {
            sink.accept(playerName);
        }
        catch (RuntimeException e)
        {
        }
    }

    private void retryTier(String playerName, String bucket, int retriesLeft) {
        Runnable retry = () -> {
            if (retriesLeft > 0) {
                scheduler.schedule(() -> retryTier(playerName, bucket, retriesLeft - 1), 2L, SECONDS);
            } else {
            }
        };
        pvpApi.getTierFromProfile(playerName, bucket).thenAccept(tier -> {
            if (tier == null) {
                retry.run();
                return;
            }
            if (rankOverlay != null) {
                rankOverlay.setApiRank(playerName, tier);
            }
            reportOwn(playerName);
        }).exceptionally(ex -> {
            retry.run();
            return null;
        });
    }

    private void touchFight(String opponentName)
    {
        if (opponentName == null || opponentName.isEmpty()) return;
        long ts = currentTimeMillis() / 1000;
        int sb = client.getVarbitValue(Varbits.SPELLBOOK);
        // Only tracks if WE enter multi, not if the opponent does.
        boolean selfInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        boolean selfInFfa = insideFfaPortal;
        boolean selfInBounty = insideBounty;
        int currentTick = client.getTickCount();
        activeFights.compute(opponentName, (k, v) -> {
            if (v == null)
            {
                v = new FightEntry(ts, sb, selfInMulti, currentTick);
            }
            else
            {
                // Update activity timestamps
                v.activityMs = currentTimeMillis();
                v.activityTick = currentTick;
            }
            v.mark(selfInMulti, selfInFfa, selfInBounty);
            return v;
        });

        if (!shardPresence.contains(opponentName))
        {
            pvpApi.getShardRank(opponentName, "overall", false).whenComplete((rank, ex) -> shardPresence.add(opponentName));
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
    private String findAttacker(Player localPlayer)
    {
        List<Player> players = client.getPlayers();
        if (players == null) {
            return recentOpp();
        }

        // Only return players we already have an active fight with
        for (Player other : players) {
            if (other == null || other == localPlayer) continue;

            String otherName = other.getName();
            if (otherName != null && activeFights.containsKey(otherName)) {
                return otherName;
            }
        }
        return null;
    }

    private String recentOpp()
    {
        long best = -1L; String bestName = null;
        for (Map.Entry<String, FightEntry> e : activeFights.entrySet())
        {
            long la = e.getValue().activityMs;
            if (la > best) { best = la; bestName = e.getKey(); }
        }
        return bestName;
    }

    /** The opponent who dealt the most damage to us (at least 1), or null. */
    private String findKillerByDamage()
    {
        String killer = null;
        long bestDmg = 0L;
        for (Map.Entry<String, FightEntry> e : activeFights.entrySet())
        {
            long dmgReceived = e.getValue().damageReceived.get();
            if (dmgReceived > bestDmg)
            {
                bestDmg = dmgReceived;
                killer = e.getKey();
            }
        }
        return killer;
    }

    /** The first player in the scene {@code test} accepts; {@code null} when none does or the list is null. */
    private Player findPlayer(Predicate<Player> test)
    {
        List<Player> players = client.getPlayers();
        if (players != null)
        {
            for (Player p : players)
            {
                if (test.test(p)) return p;
            }
        }
        return null;
    }

    private String findKiller(Player localPlayer)
    {
        Player killer = findPlayer(p -> p != localPlayer && p.getInteracting() == localPlayer);
        return killer == null ? null : killer.getName();
    }

    /** True the moment any other Player is found whose
     *  {@code getInteracting()} is the local player. Used by the
     *  inbound-damage path of {@link #handleHit} and by
     *  {@link #endFightFor}. */
    private boolean hasAttacker(Player localPlayer)
    {
        return findPlayer(p -> p != null && p != localPlayer && p.getInteracting() == localPlayer) != null;
    }

    private boolean isOpponent(String name)
    {
        return name != null && !"Unknown".equals(name) && findPlayer(p -> name.equals(p.getName())) != null;
    }

    private String getLocalName()
    {
        try
        {
            Player localPlayer = client.getLocalPlayer();
            return localPlayer == null ? null : localPlayer.getName();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static String getBookName(int spellbook)
    {
        switch (spellbook) {
            case 0: return "Standard";
            case 1: return "Ancient";
            case 2: return "Lunar";
            case 3: return "Arceuus";
            default: return "Unknown";
        }
    }

    private static boolean isLunar(String spellbook)
    {
        return spellbook != null && "Lunar".equals(spellbook.trim());
    }

    /** The bucket the server will rate this fight in: DMM world, then the
     *  Bounty Hunter area (Veng), then multi, then Veng when either spellbook
     *  is Lunar, else NH. */
    private RankBucket determineBucket(int world, boolean wasInMulti, String startBook, String endSpellbook,
                                       boolean wasInBounty)
    {
        // 1. DMM check - uses cached DMM worlds from PvpApi
        if (pvpApi.isDmmWorld(world)) return RankBucket.DMM;
        if (wasInBounty) return RankBucket.VENG;
        if (wasInMulti) return RankBucket.MULTI;
        return isLunar(startBook) || isLunar(endSpellbook) ? RankBucket.VENG : RankBucket.NH;
    }

    /**
     * Get the display name for a bucket (capitalized for UI): the leaderboard's
     * own label for a known bucket key, else the key in capitals.
     */
    private String getBucketDisplayName(String bucket)
    {
        if (bucket == null) return "NH";
        for (RankBucket b : RankBucket.values())
        {
            if (RankBucket.key(b).equals(bucket.toLowerCase())) return b.toString();
        }
        return bucket.toUpperCase();
    }

    // Package-private (not private) so same-package unit tests can seed
    // an in-progress fight for the freeze-log path without replaying the
    // full hitsplat pipeline.
    static class FightEntry {
        final long startTs;
        final int startBook;
        volatile boolean wasInMulti;  // Mutable: set true if LOCAL player ever enters multi during this fight
        volatile boolean wasInFfa;
        volatile boolean wasInBounty;
        volatile long activityMs;
        volatile int activityTick;  // Track per-opponent combat activity in game ticks
        volatile boolean finalized;

        // Per-fight damage tracking for multi-combat accuracy
        final AtomicLong damageDealt = new AtomicLong();    // Damage we dealt TO this opponent
        final AtomicLong damageReceived = new AtomicLong(); // Damage we received FROM this opponent

        FightEntry(long ts, int sb, boolean multi, int currentTick) {
            startTs = ts;
            startBook = sb;
            wasInMulti = multi;
            activityMs = currentTimeMillis();
            activityTick = currentTick;
        }

        /** Sets each area flag that is true; a flag is never cleared (once
         *  multi / in the portal / in the Bounty Hunter area, always so). */
        void mark(boolean multi, boolean ffaPortal, boolean bountyHunter) {
            if (multi) wasInMulti = true;
            if (ffaPortal) wasInFfa = true;
            if (bountyHunter) wasInBounty = true;
        }

        void addDealt(long amount, int currentTick) {
            damageDealt.addAndGet(amount);
            activityMs = currentTimeMillis();
            activityTick = currentTick;
        }

        void addReceived(long amount, int currentTick) {
            damageReceived.addAndGet(amount);
            activityMs = currentTimeMillis();
            activityTick = currentTick;
        }

        boolean isStale(int currentTick, int timeout) {
            return (currentTick - activityTick) > timeout;
        }
    }
}
