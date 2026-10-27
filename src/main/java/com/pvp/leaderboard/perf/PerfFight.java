/*
 * Copyright (c) 2021, Matsyir <https://github.com/matsyir>
 * Copyright (c) 2020, Mazhar <https://twitter.com/maz_rs>
 * Copyright (c) 2025, Sacca <https://github.com/Sacca-1>
 * Copyright (c) 2025, Matsyir <https://github.com/matsyir>
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

import java.util.*;
import java.util.function.*;
import net.runelite.api.Actor;
import net.runelite.api.HeadIcon;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.kit.KitType;
import static com.pvp.leaderboard.perf.Anim.Style.*;

/** One fight against one opponent: each side's totals, the attacks waiting for their hitsplats, the stats and the log;
 *  the local player as measured, the opponent as assumed. Adapted from PvP Performance Tracker's Fighter,
 *  FightPerformance, FightLogEntry, HitsplatInfo and its hitsplat matching. */
final class PerfFight
{
    private static final int WEAPON = KitType.WEAPON.getIndex();
    private static final int[] STAFF_SPECS = {SpotanimID.SOTD_SPECIAL_START, SpotanimID.SOTD_SPECIAL_EXTRA,
        SpotanimID.STAFF_OF_LIGHT_SPECIAL_START, SpotanimID.STAFF_OF_LIGHT_SPECIAL_EXTRA,
        SpotanimID.STAFF_OF_BALANCE_SPECIAL_START, SpotanimID.STAFF_OF_BALANCE_SPECIAL_EXTRA};
    private static final String[] BUILDS = {"max_med", "zerker", "pure"};

    final String opponent;
    final int world;
    final boolean lms;
    final Side me = new Side(), them = new Side();
    int lastTick;
    /** Held whole at some point (a running tournament match's fight in the arena). */
    boolean held;
    /** The build the fight opened with ({@link #build(boolean, int, boolean, int)}), or null. */
    String build;
    private final boolean arena;
    private final int[] table;
    private final IntFunction<int[]> stats;
    private final PerfLog log;
    private final List<Pending> pending = new ArrayList<>();
    private int prevHp, ghostTick = -1;

    /** {@code defLevel}: the local defence level the build or the loadout is read from. */
    PerfFight(String opponent, int world, boolean lms, boolean arena, int defLevel, int hp, int tick,
        IntFunction<int[]> stats)
    {
        this.opponent = opponent;
        this.world = world;
        this.lms = lms;
        this.arena = arena;
        this.table = PerfCalc.levels(lms, arena, defLevel);
        this.stats = stats;
        this.log = new PerfLog(lms, stats);
        this.prevHp = hp;
        this.lastTick = tick;
    }

    /** The local player at one moment: boosted attack, strength, defence, ranged and magic; the active prayers (bits of
     *  {@link PerfCalc#PRAYERS}); worn item ids by KitType index, the ring and the ammo (-1 none); the real Prayer
     *  level; Zeal unlocked with its Attack and Strength. */
    static final class Self
    {
        final int[] lv;
        final int prayers;
        final int[] gear;
        final int ring, ammo, prayer;
        final boolean zeal;

        Self(int[] lv, int prayers, int[] gear, int ring, int ammo, int prayer, boolean zeal)
        {
            this.lv = lv;
            this.prayers = prayers;
            this.gear = gear;
            this.ring = ring;
            this.ammo = ammo;
            this.prayer = prayer;
            this.zeal = zeal;
        }
    }

    /** Worn item ids by KitType index, -1 for a slot without an item. */
    static int[] worn(Player p)
    {
        int[] out = new int[KitType.values().length];
        Arrays.fill(out, -1);
        PlayerComposition c = p == null ? null : p.getPlayerComposition();
        int[] ids = c == null ? null : c.getEquipmentIds();
        for (int i = 0; ids != null && i < Math.min(ids.length, out.length); i++)
        {
            if (ids[i] > PlayerComposition.ITEM_OFFSET) out[i] = ids[i] - PlayerComposition.ITEM_OFFSET;
        }
        return out;
    }

    /** The build a match result names: in Last Man Standing the game's active build {@code lmsBuild} (0 max_med,
     *  1 zerker, 2 pure); in the PvP Arena the loadout the real Defence level {@code def} sets (over 50 max_med, over 1
     *  zerker, else pure); null elsewhere, and for another {@code lmsBuild}. */
    static String build(boolean lms, int lmsBuild, boolean arena, int def)
    {
        if (lms) return lmsBuild >= 0 && lmsBuild < BUILDS.length ? BUILDS[lmsBuild] : null;
        return arena ? BUILDS[def > 50 ? 0 : def > 1 ? 1 : 2] : null;
    }

    /** The local player as measured; the build's or the loadout's levels in Last Man Standing and the arena. */
    private PerfHit.Fighter mine(Self s)
    {
        return new PerfHit.Fighter(lms || arena ? table : s.lv, s.prayers, s.gear, s.ring, s.ammo, stats, true);
    }

    /** The opponent as assumed, wearing {@code gear}, with {@code prayers}. */
    private PerfHit.Fighter theirs(int[] gear, int prayers)
    {
        return new PerfHit.Fighter(table, prayers, gear, -1, -1, stats, false);
    }

    /** One attack; {@code ours} when the local player attacked; {@code self}: the local player at the attack. */
    void attack(boolean ours, Player attacker, Player defender, Anim anim, int tick, Self self)
    {
        log.state(tick, self);
        Side s = ours ? me : them;
        int[] opp = worn(ours ? defender : attacker);
        Gear w = Gear.of((ours ? self.gear : opp)[WEAPON]);
        int stacks = w == Gear.SOULREAPER_AXE && anim.soulreaper() ? s.stacks(anim, tick) : 0;
        boolean success = defender.getOverheadIcon() != anim.style.protection();
        if (w == Gear.DARK_BOW && anim == Anim.RANGED_SHORTBOW)
        {
            anim = defender.hasSpotAnim(SpotanimID.DARKBOW_DRAGON_HEAD_FLYING_IMPACT_ANIM) ? Anim.RANGED_DARK_BOW_SPEC
                : Anim.RANGED_DARK_BOW;
        }
        else if (w == Gear.DRAGON_CROSSBOW && (anim == Anim.RANGED_CROSSBOW_PVP || anim == Anim.RANGED_RUNE_CROSSBOW)
            && defender.hasSpotAnim(SpotanimID.FIREWAVE_IMPACT))
        {
            anim = Anim.RANGED_DRAGON_CROSSBOW_SPEC;
        }
        s.attacks++;
        if (success) s.offPray++;
        int right = 0;
        for (Prayer p : anim.style.prayers()) right |= PerfCalc.bit(p);
        if (ours && (self.prayers & right) != 0) s.prayed++;
        int procs = (defender.hasSpotAnim(SpotanimID.ELYSIAN_SHIELD_DEFEND_SPOTANIM) ? 1 : 0)
            | (Arrays.stream(STAFF_SPECS).anyMatch(defender::hasSpotAnim) ? 2 : 0);
        PerfHit.Fighter assumed = theirs(opp, ours ? PerfCalc.guard(anim.style, lms, arena, self.prayer, table[2])
            : PerfCalc.bit(PerfCalc.assumed(anim.style, lms, arena, self.zeal, self.prayer, self.lv[2])));
        PerfHit.Hit h = PerfHit.of(lms, ours ? mine(self) : assumed, ours ? assumed : mine(self), anim, success, stacks,
            procs);
        s.expected += h.avg;
        if (anim.spec && anim != Anim.MELEE_GRANITE_MAUL_SPEC) s.specTick = tick;
        lastTick = tick;
        Pending e = new Pending(ours, anim, tick, log.tick(tick), h, stacks, success, procs);
        log.put(ours ? 'A' : 'H', e.rel, e);
        if (anim.style == MAGIC)
        {
            s.magicAttacks++;
            s.magicExpected += h.acc;
            if (defender.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT)) return;
            s.magicHits++;
        }
        else if (PerfCalc.robes(stats, ours ? opp : self.gear))
        {
            (ours ? them : me).robeHits++;
        }
        pending.add(e);
    }

    /** Magic experience without a spell's animation: a barrage cast while the animation was stalled. */
    void ghost(Player opp, int tick, Self self)
    {
        if (tick <= ghostTick) return;
        ghostTick = tick;
        log.state(tick, self);
        boolean success = opp.getOverheadIcon() != HeadIcon.MAGIC;
        PerfHit.Hit h = PerfHit.of(lms, mine(self), theirs(worn(opp), PerfCalc.guard(MAGIC, lms, arena, self.prayer,
            table[2])), Anim.MAGIC_ANCIENT_MULTI_TARGET, success, 0, 0);
        me.ghosts++;
        me.ghostExpected += h.avg;
        log.put('B', log.tick(tick), "B," + log.tick(tick) + "," + (success ? 1 : 0));
    }

    /** The local player at {@code tick}, logged when it changed. */
    void state(int tick, Self self)
    {
        log.state(tick, self);
    }

    /** The local player's hitpoints now; a rise counts as healed. */
    void hp(int current)
    {
        if (current > prevHp) me.healed += current - prevHp;
        prevHp = current;
    }

    /** Matches the hitsplats of tick {@code now - 1} (on the opponent, and on the local player when this is the only
     *  fight and {@code startHp}, the local player's hitpoints at that tick's start, is known) to the attacks waiting
     *  for them, and adds each landed attack's KO chance. */
    void settle(int now, List<Splat> splats, boolean solo, int startHp)
    {
        int tick = now - 1;
        solo &= startHp >= 0;
        List<Splat> incoming = new ArrayList<>(splats), hits = new ArrayList<>();
        for (Splat s : splats)
        {
            if (solo || !s.onMe(opponent)) hits.add(s);
        }
        if (hits.isEmpty()) return;
        int expected = 0;
        for (Pending e : pending)
        {
            if ((solo || e.ours) && tick - e.tick <= Math.max(5, e.lookback())) expected += e.hits;
        }
        dropRetaliation(hits, incoming, expected);
        List<Pending> landed = new ArrayList<>();
        match(true, hits, now, tick, landed, startHp);
        if (solo) match(false, hits, now, tick, landed, startHp);
        Map<Integer, List<Pending>> groups = new TreeMap<>();
        for (Pending e : landed)
        {
            if (e.hitTick >= 0) groups.computeIfAbsent(e.hitTick * 2 + (e.ours ? 1 : 0), k -> new ArrayList<>()).add(e);
        }
        for (List<Pending> g : groups.values()) chances(g);
    }

    /** Vengeance and recoil splats beyond the hits the waiting attacks can make: each matches 75% (vengeance) or 10%
     *  plus one (recoil) of a hit the other player took that tick. */
    private void dropRetaliation(List<Splat> hits, List<Splat> incoming, int expected)
    {
        for (int round = 0, rounds = hits.size() * 2; hits.size() > expected && round < rounds; round++)
        {
            Splat drop = null;
            for (Splat h : hits)
            {
                for (Splat in : incoming)
                {
                    if (in.onMe(opponent) == h.onMe(opponent)) continue;
                    int veng = Math.max(1, (int) Math.floor(in.amount * 0.75)), recoil = Math.max(1, (int) Math.floor(in.amount * 0.1) + 1);
                    if (h.amount == veng || h.amount == recoil) drop = h;
                }
                if (drop != null) break;
            }
            if (drop == null) return;
            hits.remove(drop);
        }
    }

    /** One side's waiting attacks take their hitsplats in attack order: the opponent's hitpoints from its health bar,
     *  the local player's from {@code startHp} less the earlier attacks' damage that tick. */
    private void match(boolean ours, List<Splat> all, int now, int tick, List<Pending> landed, int startHp)
    {
        List<Splat> hits = new ArrayList<>();
        for (Splat s : all)
        {
            if (s.onMe(opponent) != ours) hits.add(s);
        }
        List<Pending> waiting = new ArrayList<>();
        for (Pending e : pending)
        {
            if (e.ours == ours && now - e.tick <= Math.max(5, e.lookback())) waiting.add(e);
        }
        waiting.sort(Comparator.comparingInt(e -> e.tick));
        List<Pending> mauls = new ArrayList<>();
        int maulHits = 0, spent = 0;
        for (Pending e : waiting)
        {
            if (now - e.tick > e.lookback() || e.hits <= e.matched)
            {
                pending.remove(e);
                continue;
            }
            int want = e.gmaul ? 2 : e.hits - e.matched;
            if (e.anim == Anim.MELEE_DRAGON_CLAWS_SPEC && e.matched < 2) want = Math.min(want, 2 - e.matched);
            if (e.gmaul && (ours ? me : them).specTick == e.tick - 1) want = Math.min(want, 1);
            int n = 0, dmg = 0;
            Splat last = null;
            for (Iterator<Splat> it = hits.iterator(); n < want && it.hasNext(); n++)
            {
                last = it.next();
                dmg += last.amount;
                it.remove();
                all.remove(last);
            }
            if (n > 0)
            {
                if (ours)
                {
                    int ratio = last.ratio, scale = last.scale;
                    if (ratio < 0 || scale <= 0)
                    {
                        ratio = last.actor.getHealthRatio();
                        scale = last.actor.getHealthScale();
                    }
                    int before = PerfCalc.hpBefore(ratio, scale, 99, e.dmg + dmg);
                    e.landed(n, dmg, tick, PerfCalc.hpBefore(ratio, scale, 99, dmg), before > 0 ? before : null);
                }
                else
                {
                    int before = Math.max(0, startHp - spent);
                    e.landed(n, dmg, tick, before, e.matched == 0 ? before : null);
                    spent += dmg;
                }
                landed.add(e);
                if (e.gmaul)
                {
                    mauls.add(e);
                    maulHits += n;
                }
            }
            if (e.matched >= e.hits || e.gmaul && e.tick == tick) pending.remove(e);
        }
        if (maulHits >= 2)
        {
            for (Pending g : mauls)
            {
                g.hit.max *= maulHits;
                g.hit.min *= maulHits;
            }
        }
    }

    /** One attacker's attacks that landed on one tick, in order: each one's KO chance from the hitpoints before it. */
    private void chances(List<Pending> group)
    {
        group.sort(Comparator.comparingInt(e -> e.tick));
        Pending last = group.get(group.size() - 1);
        Integer hp = group.get(0).hpBefore;
        if (last.hpBefore != null)
        {
            hp = last.hpBefore - last.dmg;
            for (Pending e : group) hp += e.dmg;
        }
        for (Pending e : group)
        {
            Integer before = e.ours ? hp : e.hpBefore;
            Double ko = before == null ? null : PerfHit.ko(e.hit, e.anim, before, e.heal());
            if (ko != null && ko > 0)
            {
                Side s = e.ours ? me : them;
                s.kos++;
                s.survive *= 1 - ko;
            }
            hp = hp == null ? null : hp - e.dmg;
        }
    }

    /** A client tick ended, after its records: the log counts it when it was quiet. */
    void endTick()
    {
        log.endTick();
    }

    /** The stats sent with the match, in the body's order: the version, the build when the fight has one, the eight,
     *  and the log, packed, when its text kept its bounds; null when no attack was seen. */
    Map<String, String> values()
    {
        if (me.attacks + them.attacks == 0) return null;
        String[] s = stats(true);
        Map<String, String> v = new LinkedHashMap<>();
        v.put("pf_v", "2");
        if (build != null) v.put("fight_build", build);
        String[] keys = {"pf_op", "pf_ed", null, "pf_mh", "pf_pr", "pf_hp", "pf_rh", "pf_ko", "pf_gb"};
        for (int i = 0; i < keys.length; i++)
        {
            if (keys[i] != null) v.put(keys[i], s[i]);
        }
        String text = log.text();
        if (text != null) v.put("pf_log", PerfLog.pack(text));
        return v;
    }

    /** One side's nine values as stored: off-pray, expected damage, damage dealt, magic hits, offensive prayer,
     *  hitpoints healed, hits on robes, KO chances, ghost barrages; "-" for what the client cannot see of the opponent. */
    String[] stats(boolean mine)
    {
        Side s = mine ? me : them, o = mine ? them : me;
        return new String[]{s.offPray + "/" + s.attacks, String.valueOf(Math.round(s.expected)), String.valueOf(s.dealt),
            s.magicHits + "/" + s.magicAttacks + "/" + one(s.magicExpected), mine ? s.prayed + "/" + s.attacks : "-",
            mine ? String.valueOf(s.healed) : "-", s.robeHits + "/" + (o.attacks - o.magicAttacks),
            s.kos + "/" + one((1 - s.survive) * 100), mine ? s.ghosts + "/" + Math.round(s.ghostExpected) : "-"};
    }

    private static String one(double v)
    {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** One side's totals. */
    static final class Side
    {
        int attacks, offPray, prayed, magicAttacks, magicHits, healed, robeHits, ghosts, dealt, kos;
        double expected, magicExpected, ghostExpected, survive = 1;
        private int stacks, stackTick = -1, specTick = Integer.MIN_VALUE;

        /** Soulreaper axe stacks for this attack: one more per hit up to five, spent by the special, one lost per 50
         *  ticks. */
        private int stacks(Anim anim, int tick)
        {
            if (stackTick >= 0 && stacks > 0) stacks = Math.max(0, stacks - Math.max(0, tick - stackTick) / 50);
            int now = stacks;
            stacks = anim == Anim.MELEE_SOULREAPER_AXE_SPEC ? 0 : Math.min(5, stacks + 1);
            stackTick = tick;
            return now;
        }
    }

    /** A hitsplat on the local player or the opponent, with the health bar read after it. */
    static final class Splat
    {
        final Actor actor;
        final int amount;
        int ratio = -1, scale = -1;

        Splat(Actor actor, int amount)
        {
            this.actor = actor;
            this.amount = amount;
        }

        boolean onMe(String opponent)
        {
            return !opponent.equals(actor.getName());
        }
    }

    /** An attack waiting for its hitsplats, and its log record. */
    private static final class Pending
    {
        final boolean ours, gmaul, success;
        final Anim anim;
        final int tick, rel, hits, stacks, procs;
        final PerfHit.Hit hit;
        int matched, dmg, hitTick = -1;
        Integer hpBefore, after1, before2;
        boolean first, stacked;

        Pending(boolean ours, Anim anim, int tick, int rel, PerfHit.Hit hit, int stacks, boolean success, int procs)
        {
            this.ours = ours;
            this.anim = anim;
            this.tick = tick;
            this.rel = rel;
            this.hits = anim.hits;
            this.gmaul = anim == Anim.MELEE_GRANITE_MAUL_SPEC;
            this.hit = hit;
            this.stacks = stacks;
            this.success = success;
            this.procs = procs;
        }

        int lookback()
        {
            return anim.style.melee() ? 3 : anim.style == MAGIC ? 6 : 4;
        }

        /** {@code n} hitsplats of {@code dmg} landed on {@code tick} on {@code now} hitpoints; {@code before}: the
         *  hitpoints before the attack's first, null to keep the last. */
        void landed(int n, int dmg, int tick, int now, Integer before)
        {
            this.dmg += dmg;
            matched += n;
            if (hitTick < 0) hitTick = tick;
            if (before != null) hpBefore = before;
            if (anim == Anim.MELEE_DRAGON_CLAWS_SPEC)
            {
                if (matched == 2 && after1 == null && now > 0) after1 = now - dmg;
                if (matched >= hits && before2 == null && now > 0) before2 = now;
            }
            else if (anim == Anim.RANGED_DARK_BOW || anim == Anim.RANGED_DARK_BOW_SPEC)
            {
                if (matched == n && n >= 2)
                {
                    stacked = true;
                    first |= now > 0;
                }
                else
                {
                    if (matched >= 1 && !first && now > 0)
                    {
                        first = true;
                        after1 = now - dmg;
                    }
                    if (matched >= hits && before2 == null && now > 0) before2 = now;
                }
            }
        }

        /** The hitpoints healed between the halves of a dragon claws special or a dark bow shot once all of it
         *  landed; null otherwise. */
        Integer heal()
        {
            boolean dbow = anim == Anim.RANGED_DARK_BOW || anim == Anim.RANGED_DARK_BOW_SPEC;
            if (matched < hits || !dbow && anim != Anim.MELEE_DRAGON_CLAWS_SPEC) return null;
            return !(dbow && stacked) && after1 != null && before2 != null ? Math.max(0, before2 - after1) : 0;
        }

        /** The log record: A for the local player's attack, H for an attack on the local player. */
        @Override
        public String toString()
        {
            if (ours) return "A," + rel + "," + anim.name() + "," + stacks + "," + (success ? 1 : 0) + "," + matched;
            Integer heal = heal();
            return "H," + rel + "," + (matched > 0 ? dmg + "," + hpBefore : ",") + "," + (heal == null ? "" : heal) + ","
                + procs;
        }
    }
}
