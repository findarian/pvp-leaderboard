/*
 * Copyright (c) 2021, Matsyir <https://github.com/Matsyir>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.pvp.leaderboard.perf;

import com.pvp.leaderboard.game.KitRouter;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/** Feeds each fight's tracker from the client: attacks, hitsplats, hitpoints, magic experience and the local
 *  player's levels, prayers and worn items; one fight per opponent. Every hook keeps its own faults, so a tracker fault
 *  never stops a match result; {@link #reset} may come from another thread. Adapted from PvP Performance Tracker's
 *  plugin hooks. */
@Slf4j
@Singleton
public class PerfTracker
{
    /** Ticks (21 s) without an attack or hit after which a fight ends as Partial; within them the same opponent's
     *  next attack or hit continues it. */
    private static final int WINDOW = 35;
    /** How long an ended fight stays in {@link #live}, in ms. */
    private static final long SHOWN_MS = 3000;
    private static final Set<Integer> DAMAGE = new HashSet<>(Arrays.asList(HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_ME_ORANGE,
        HitsplatID.DAMAGE_OTHER_ORANGE, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_ORANGE,
        HitsplatID.POISON, HitsplatID.VENOM, HitsplatID.BURN));
    private static final Set<Integer> NOT_HITS = new HashSet<>(Arrays.asList(HitsplatID.HEAL, HitsplatID.POISON,
        HitsplatID.VENOM, HitsplatID.BURN, HitsplatID.DISEASE));
    private static final Skill[] LEVELS = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC};

    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final FightLog fightLog;
    private final Map<String, PerfFight> fights = new LinkedHashMap<>();
    private final Map<Integer, List<PerfFight.Splat>> splats = new HashMap<>();
    /** The open fights and those ended in the last 3 s, oldest first; when each ended. */
    private final List<PerfFight> shown = new ArrayList<>();
    private final Map<PerfFight, Long> endedAt = new HashMap<>();
    private volatile Predicate<String> hold = n -> false;
    LongSupplier clock = System::currentTimeMillis;
    private int magicXp = -1, hpTick = -1, hpStart;
    /** The local player's name at the last attack. */
    private String myName;

    @Inject
    public PerfTracker(Client client, ClientThread clientThread, ItemManager itemManager, FightLog fightLog)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.fightLog = fightLog;
    }

    /** An attack animation by the local player on a player, or by a player on the local player. Read a moment later,
     *  once the attack's gear has loaded. */
    public void onAnimation(AnimationChanged e)
    {
        try
        {
            Actor a = e.getActor();
            Player me = client.getLocalPlayer();
            Anim anim = a instanceof Player && me != null ? Anim.of(a.getAnimation()) : null;
            Actor target = anim == null ? null : a.getInteracting();
            boolean ours = a == me;
            if (!(target instanceof Player) || !ours && target != me) return;
            int tick = client.getTickCount();
            clientThread.invokeLater(() -> attack(ours, (Player) a, (Player) target, anim, tick));
        }
        catch (RuntimeException ex)
        {
        }
    }

    private synchronized void attack(boolean ours, Player attacker, Player defender, Anim anim, int tick)
    {
        try
        {
            String name = (ours ? defender : attacker).getName(), mine = (ours ? attacker : defender).getName();
            if (mine != null) myName = mine;
            if (name == null) return;
            open(name, tick).attack(ours, attacker, defender, anim, tick, self());
        }
        catch (RuntimeException ex)
        {
        }
    }

    /** A hitsplat on an opponent or on the local player: damage dealt, and a hit to match to an attack. */
    public synchronized void onHit(HitsplatApplied e)
    {
        try
        {
            Actor a = e.getActor();
            Player me = client.getLocalPlayer();
            if (fights.isEmpty() || !(a instanceof Player) || me == null) return;
            boolean onMe = a == me;
            PerfFight f = onMe ? solo() : fights.get(a.getName());
            if (!onMe && f == null) return;
            int type = e.getHitsplat().getHitsplatType(), amount = e.getHitsplat().getAmount(), tick = client.getTickCount();
            if (amount > 0 && !DAMAGE.contains(type)) return;
            if (f != null)
            {
                (onMe ? f.them : f.me).dealt += amount;
                f.lastTick = tick;
            }
            if (NOT_HITS.contains(type)) return;
            PerfFight.Splat s = new PerfFight.Splat(a, amount);
            splats.computeIfAbsent(tick, k -> new ArrayList<>()).add(s);
            clientThread.invokeLater(() ->
            {
                s.ratio = a.getHealthRatio();
                s.scale = a.getHealthScale();
            });
        }
        catch (RuntimeException ex)
        {
        }
    }

    /** Hitpoints healed, and magic experience without a spell's animation (a ghost barrage). */
    public synchronized void onStat(StatChanged e)
    {
        try
        {
            if (e.getSkill() == Skill.HITPOINTS)
            {
                for (PerfFight f : fights.values()) f.hp(e.getBoostedLevel());
            }
            else if (e.getSkill() == Skill.MAGIC)
            {
                boolean rose = magicXp >= 0 && e.getXp() > magicXp;
                magicXp = e.getXp();
                if (rose && !fights.isEmpty()) clientThread.invokeLater(this::ghost);
            }
        }
        catch (RuntimeException ex)
        {
        }
    }

    /** Magic experience for a player whose experience no longer rises. */
    public synchronized void onFakeXp(FakeXpDrop e)
    {
        try
        {
            if (e.getSkill() == Skill.MAGIC && !fights.isEmpty()) clientThread.invokeLater(this::ghost);
        }
        catch (RuntimeException ex)
        {
        }
    }

    private synchronized void ghost()
    {
        try
        {
            Player me = client.getLocalPlayer();
            Actor t = me == null ? null : me.getInteracting();
            PerfFight f = t instanceof Player ? fights.get(t.getName()) : null;
            Anim a = f == null ? null : Anim.of(me.getAnimation());
            if (f == null || a != null && a.style == Anim.Style.MAGIC) return;
            f.ghost((Player) t, client.getTickCount(), self());
        }
        catch (RuntimeException ex)
        {
        }
    }

    /** Each tick: the local player's hitpoints at its start; the last tick's hitsplats matched to attacks; the local
     *  player's state for the open fights; fights idle for 35 ticks kept as Partial, unless held; a held fight whose
     *  hold has ended kept as Partial. Runs before the fight results of the tick are read. */
    public synchronized void onTick()
    {
        try
        {
            int now = client.getTickCount();
            List<PerfFight.Splat> last = splats.remove(now - 1);
            splats.keySet().removeIf(t -> t < now - 5);
            int start = hpTick == now - 1 ? hpStart : -1;
            hpTick = now;
            hpStart = client.getBoostedSkillLevel(Skill.HITPOINTS);
            Player me = client.getLocalPlayer();
            boolean solo = fights.size() == 1;
            PerfFight.Self self = fights.isEmpty() ? null : self();
            for (PerfFight f : new ArrayList<>(fights.values()))
            {
                List<PerfFight.Splat> own = new ArrayList<>();
                for (PerfFight.Splat s : last == null ? Collections.<PerfFight.Splat>emptyList() : last)
                {
                    if (s.actor == me || f.opponent.equals(s.actor.getName())) own.add(s);
                }
                if (!own.isEmpty()) f.settle(now, own, solo, start);
                f.state(now, self);
                f.endTick();
                if (held(f.opponent)) f.held = true;
                else if (f.held) close(f, "partial", null, "left_arena");
                else if (now - f.lastTick >= WINDOW) close(f, "partial", null, "no_hits=" + WINDOW);
            }
        }
        catch (RuntimeException ex)
        {
        }
    }

    /** The fight with {@code opponent} ended with {@code result}: kept with this computer's fights; its eight stats
     *  for the match body, or null when there were none. */
    public synchronized Map<String, String> finish(String opponent, String result)
    {
        return finish(opponent, result, null);
    }

    /** The same, with the fight's style (its bucket). */
    public synchronized Map<String, String> finish(String opponent, String result, String style)
    {
        try
        {
            PerfFight f = opponent == null ? null : fights.get(opponent);
            if (f == null) return null;
            close(f, result, style, "result=" + result);
            return f.values();
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    /** Logout or shutdown: open fights are kept as Partial. */
    public synchronized void reset()
    {
        try
        {
            for (PerfFight f : new ArrayList<>(fights.values())) close(f, "partial", null, "reset");
        }
        catch (RuntimeException ex)
        {
        }
        fights.clear();
        splats.clear();
        magicXp = hpTick = -1;
    }

    /** The newest {@code max} fights to show, oldest first: the open ones and those ended in the last 3 s (until the
     *  same opponent's next fight), each with its opponent, world and both sides' values. */
    public synchronized List<FightLog.Fight> live(int max)
    {
        prune();
        List<FightLog.Fight> out = new ArrayList<>();
        for (PerfFight f : shown) out.add(new FightLog.Fight(0, f.opponent, "", f.world, f.stats(true), f.stats(false)));
        return out.subList(Math.max(0, out.size() - max), out.size());
    }

    /** The fights FightMonitor holds whole (a running tournament match's, in the arena), by opponent; null holds
     *  none. */
    public void setHold(Predicate<String> hold)
    {
        this.hold = hold == null ? n -> false : hold;
    }

    synchronized PerfFight fight(String opponent)
    {
        return fights.get(opponent);
    }

    /** The opponent's fight; after 35 ticks without an attack or hit a new one, the old kept as Partial, unless the
     *  fight is held; a held fight whose hold has ended is kept as Partial too. */
    private PerfFight open(String name, int tick)
    {
        PerfFight f = fights.get(name);
        boolean held = held(name);
        if (f != null && !held && (f.held || tick - f.lastTick >= WINDOW))
        {
            close(f, "partial", null, f.held ? "left_arena" : "idle_gap=" + (tick - f.lastTick));
            f = null;
        }
        if (f == null)
        {
            int world = client.getWorld();
            boolean lms = client.getVarbitValue(VarbitID.BR_INGAME) != 0;
            f = new PerfFight(name, world, lms, KitRouter.ARENA_WORLDS.contains(world), lms
                ? client.getBoostedSkillLevel(Skill.DEFENCE) : client.getRealSkillLevel(Skill.DEFENCE),
                client.getBoostedSkillLevel(Skill.HITPOINTS), tick, this::stats);
            fights.put(name, f);
            shown.removeIf(x -> x.opponent.equals(name) && endedAt.remove(x) != null);
            prune();
            shown.add(f);
            log.info("[Perf] fight start opponent={} world={} tick={}", name, world, tick);
        }
        if (held) f.held = true;
        return f;
    }

    /** Whether FightMonitor holds the fight against {@code opponent} now; a fault holds nothing. */
    private boolean held(String opponent)
    {
        try
        {
            return hold.test(opponent);
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    /** Drops the fights that ended 3 s ago or more. */
    private void prune()
    {
        long now = clock.getAsLong();
        shown.removeIf(f -> {
            Long at = endedAt.get(f);
            if (at == null || now - at < SHOWN_MS) return false;
            endedAt.remove(f);
            return true;
        });
    }

    /** Ends {@code f} for {@code reason}, logged with each side's attacks: kept with this computer's fights when it
     *  had an attack, and shown 3 s more. */
    private void close(PerfFight f, String result, String style, String reason)
    {
        fights.remove(f.opponent);
        endedAt.put(f, clock.getAsLong());
        log.info("[Perf] fight end opponent={} {} my_attacks={} their_attacks={}", f.opponent, reason, f.me.attacks,
            f.them.attacks);
        if (f.me.attacks + f.them.attacks > 0)
        {
            FightLog.Fight x = new FightLog.Fight(clock.getAsLong() / 1000, f.opponent, result, f.world,
                f.stats(true), f.stats(false));
            x.self = myName == null ? "" : myName;
            x.style = style == null ? "" : style;
            fightLog.add(x);
        }
    }

    private PerfFight solo()
    {
        return fights.size() == 1 ? fights.values().iterator().next() : null;
    }

    /** The local player now: boosted levels, active prayers, worn items, ring and ammo, Prayer level and Zeal. */
    private PerfFight.Self self()
    {
        int[] lv = new int[LEVELS.length], gear = new int[KitType.values().length];
        for (int i = 0; i < lv.length; i++) lv[i] = client.getBoostedSkillLevel(LEVELS[i]);
        int prayers = 0;
        for (int b = 0; b < PerfCalc.PRAYERS.length; b++)
        {
            if (client.getVarbitValue(PerfCalc.PRAYERS[b].getVarbit()) == 1) prayers |= 1 << b;
        }
        ItemContainer c = client.getItemContainer(InventoryID.WORN);
        for (int i = 0; i < gear.length; i++) gear[i] = worn(c, i);
        return new PerfFight.Self(lv, prayers, gear, worn(c, EquipmentInventorySlot.RING.getSlotIdx()),
            worn(c, EquipmentInventorySlot.AMMO.getSlotIdx()), client.getRealSkillLevel(Skill.PRAYER), zeal());
    }

    private boolean zeal()
    {
        return client.getVarbitValue(VarbitID.PRAYER_ZEAL_UNLOCKED) != 0 && client.getRealSkillLevel(Skill.ATTACK) >= 75
            && client.getRealSkillLevel(Skill.STRENGTH) >= 75;
    }

    /** The item id worn in equipment slot {@code slot}, -1 for none; slots 0-11 are KitType's. */
    private static int worn(ItemContainer c, int slot)
    {
        Item i = c == null ? null : c.getItem(slot);
        return i == null || i.getId() < 0 ? -1 : i.getId();
    }

    /** An item's 13 equipment bonuses, in RuneLite's order; null when unknown. */
    private int[] stats(int id)
    {
        ItemStats s = itemManager.getItemStats(id);
        ItemEquipmentStats e = s == null ? null : s.getEquipment();
        return e == null ? null : new int[]{e.getAstab(), e.getAslash(), e.getAcrush(), e.getAmagic(), e.getArange(),
            e.getDstab(), e.getDslash(), e.getDcrush(), e.getDmagic(), e.getDrange(), e.getStr(), e.getRstr(), (int) e.getMdmg()};
    }
}
