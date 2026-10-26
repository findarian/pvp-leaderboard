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
import javax.inject.*;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/** Feeds each fight's tracker from the client: attacks, hitsplats, hitpoints and magic experience; one fight per
 *  opponent. Every hook keeps its own faults, so a tracker fault never stops a match result; {@link #reset} may come
 *  from another thread. Adapted from PvP Performance Tracker's plugin hooks. */
@Singleton
public class PerfTracker
{
    private static final int IDLE = 16, GONE = 50;
    private static final Set<Integer> DAMAGE = new HashSet<>(Arrays.asList(HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_ME_ORANGE,
        HitsplatID.DAMAGE_OTHER_ORANGE, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_ORANGE,
        HitsplatID.POISON, HitsplatID.VENOM, HitsplatID.BURN));
    private static final Set<Integer> NOT_HITS = new HashSet<>(Arrays.asList(HitsplatID.HEAL, HitsplatID.POISON,
        HitsplatID.VENOM, HitsplatID.BURN, HitsplatID.DISEASE));

    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final FightLog fightLog;
    private final Map<String, PerfFight> fights = new LinkedHashMap<>();
    private final Map<Integer, List<PerfFight.Splat>> splats = new HashMap<>();
    private int magicXp = -1;

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
            String name = (ours ? defender : attacker).getName();
            if (name == null) return;
            PerfFight f = open(name, tick);
            int prayer = client.getRealSkillLevel(Skill.PRAYER);
            boolean prayed = false;
            for (Prayer p : anim.style.prayers()) prayed |= ours && client.getVarbitValue(p.getVarbit()) == 1;
            f.attack(ours, attacker, defender, anim, tick, assumed(f, anim.style, prayer), prayed, prayer,
                worn(EquipmentInventorySlot.RING), worn(EquipmentInventorySlot.AMMO));
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
            int prayer = client.getRealSkillLevel(Skill.PRAYER);
            f.ghost(me, (Player) t, client.getTickCount(), assumed(f, Anim.Style.MAGIC, prayer), prayer,
                worn(EquipmentInventorySlot.RING));
        }
        catch (RuntimeException ex)
        {
        }
    }

    /** Each tick: the last tick's hitsplats matched to attacks; fights idle for 50 ticks kept as Partial. Runs before
     *  the fight results of the tick are read. */
    public synchronized void onTick()
    {
        try
        {
            int now = client.getTickCount();
            List<PerfFight.Splat> last = splats.remove(now - 1);
            splats.keySet().removeIf(t -> t < now - 5);
            Player me = client.getLocalPlayer();
            boolean solo = fights.size() == 1;
            for (PerfFight f : new ArrayList<>(fights.values()))
            {
                List<PerfFight.Splat> own = new ArrayList<>();
                for (PerfFight.Splat s : last == null ? Collections.<PerfFight.Splat>emptyList() : last)
                {
                    if (s.actor == me || f.opponent.equals(s.actor.getName())) own.add(s);
                }
                if (!own.isEmpty()) f.settle(now, own, solo);
                if (now - f.lastTick > GONE) close(f, "partial");
            }
        }
        catch (RuntimeException ex)
        {
        }
    }

    /** The fight with {@code opponent} ended with {@code result}: kept in All fights; its eight stats for the match
     *  body, or null when there were none. */
    public synchronized Map<String, String> finish(String opponent, String result)
    {
        try
        {
            PerfFight f = opponent == null ? null : fights.get(opponent);
            if (f == null) return null;
            close(f, result);
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
            for (PerfFight f : new ArrayList<>(fights.values())) close(f, "partial");
        }
        catch (RuntimeException ex)
        {
        }
        fights.clear();
        splats.clear();
        magicXp = -1;
    }

    /** The newest {@code max} open fights, oldest first: each one's opponent, world and both sides' values. */
    public synchronized List<FightLog.Fight> live(int max)
    {
        List<FightLog.Fight> out = new ArrayList<>();
        for (PerfFight f : fights.values()) out.add(new FightLog.Fight(0, f.opponent, "", f.world, f.stats(true), f.stats(false)));
        return out.subList(Math.max(0, out.size() - max), out.size());
    }

    synchronized PerfFight fight(String opponent)
    {
        return fights.get(opponent);
    }

    /** The opponent's fight; after 16 idle ticks a new one, the old kept as Partial. */
    private PerfFight open(String name, int tick)
    {
        PerfFight f = fights.get(name);
        if (f != null && tick - f.lastTick > IDLE)
        {
            close(f, "partial");
            f = null;
        }
        if (f == null)
        {
            f = new PerfFight(name, client.getWorld(), client.getVarbitValue(VarbitID.BR_INGAME) != 0,
                client.getBoostedSkillLevel(Skill.DEFENCE), client.getBoostedSkillLevel(Skill.HITPOINTS), tick, this::stats);
            fights.put(name, f);
        }
        return f;
    }

    private void close(PerfFight f, String result)
    {
        fights.remove(f.opponent);
        if (f.me.attacks + f.them.attacks > 0)
        {
            fightLog.add(new FightLog.Fight(System.currentTimeMillis() / 1000, f.opponent, result, f.world, f.stats(true),
                f.stats(false)));
        }
    }

    private PerfFight solo()
    {
        return fights.size() == 1 ? fights.values().iterator().next() : null;
    }

    private Prayer assumed(PerfFight f, Anim.Style style, int prayer)
    {
        return PerfCalc.assumed(style, f.lms, KitRouter.ARENA_WORLDS.contains(f.world), zeal(), prayer,
            client.getBoostedSkillLevel(Skill.DEFENCE));
    }

    private boolean zeal()
    {
        return client.getVarbitValue(VarbitID.PRAYER_ZEAL_UNLOCKED) != 0 && client.getRealSkillLevel(Skill.ATTACK) >= 75
            && client.getRealSkillLevel(Skill.STRENGTH) >= 75;
    }

    private int worn(EquipmentInventorySlot slot)
    {
        ItemContainer c = client.getItemContainer(InventoryID.WORN);
        Item i = c == null ? null : c.getItem(slot.getSlotIdx());
        return i == null ? -1 : i.getId();
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
