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

/** One fight against one opponent: each side's totals, the attacks waiting for their hitsplats, the eight stats.
 *  Adapted from PvP Performance Tracker's Fighter, FightPerformance, FightLogEntry, HitsplatInfo and its hitsplat
 *  matching. */
final class PerfFight
{
    private static final int WEAPON = KitType.WEAPON.getIndex();
    private static final int[] STAFF_SPECS = {SpotanimID.SOTD_SPECIAL_START, SpotanimID.SOTD_SPECIAL_EXTRA,
        SpotanimID.STAFF_OF_LIGHT_SPECIAL_START, SpotanimID.STAFF_OF_LIGHT_SPECIAL_EXTRA,
        SpotanimID.STAFF_OF_BALANCE_SPECIAL_START, SpotanimID.STAFF_OF_BALANCE_SPECIAL_EXTRA};

    final String opponent;
    final int world;
    final boolean lms;
    final Side me = new Side(), them = new Side();
    int lastTick;
    private final PerfCalc calc;
    private final List<Pending> pending = new ArrayList<>();
    private int prevHp, ghostTick = -1;

    PerfFight(String opponent, int world, boolean lms, int defLevel, int hp, int tick, IntFunction<int[]> stats)
    {
        this.opponent = opponent;
        this.world = world;
        this.lms = lms;
        this.calc = new PerfCalc(stats, PerfCalc.levels(lms, defLevel), lms);
        this.prevHp = hp;
        this.lastTick = tick;
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

    /** One attack; {@code ours} when the local player attacked. {@code pray}: the assumed offensive prayer;
     *  {@code prayed}: the local player had the right one on; {@code ring} and {@code ammo}: the local player's. */
    void attack(boolean ours, Player attacker, Player defender, Anim anim, int tick, Prayer pray, boolean prayed,
        int prayerLevel, int ring, int ammo)
    {
        Side s = ours ? me : them;
        int[] atk = worn(attacker), def = worn(defender);
        Gear w = Gear.of(atk[WEAPON]);
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
        if (prayed) s.prayed++;
        if (w == Gear.DRAGON_LONGSWORD || w == Gear.VESTAS_LONGSWORD)
        {
            atk[WEAPON] = Gear.VESTAS_LONGSWORD.id;
            anim = anim.spec ? Anim.MELEE_VLS_SPEC : Anim.MELEE_SCIM_SLASH;
        }
        calc.attack(atk, def, ours ? Gear.ring(ring) : Gear.DEFAULT_RING, ours ? Gear.DEFAULT_RING : Gear.ring(ring),
            ours ? ammo : -1, success, anim, pray, stacks, prayerLevel);
        if (defender.hasSpotAnim(SpotanimID.ELYSIAN_SHIELD_DEFEND_SPOTANIM)) calc.reduce(0.75);
        if (anim.style.melee() && Arrays.stream(STAFF_SPECS).anyMatch(defender::hasSpotAnim)) calc.reduce(0.5);
        s.expected += calc.avg;
        if (anim.spec && anim != Anim.MELEE_GRANITE_MAUL_SPEC) s.specTick = tick;
        lastTick = tick;
        if (anim.style == MAGIC)
        {
            s.magicAttacks++;
            s.magicExpected += calc.acc;
            if (defender.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT)) return;
            s.magicHits++;
        }
        else if (calc.robes(def))
        {
            (ours ? them : me).robeHits++;
        }
        pending.add(new Pending(ours, anim, tick, calc));
    }

    /** Magic experience without a spell's animation: a barrage cast while the animation was stalled. */
    void ghost(Player self, Player opp, int tick, Prayer pray, int prayerLevel, int ring)
    {
        if (tick <= ghostTick) return;
        ghostTick = tick;
        calc.attack(worn(self), worn(opp), Gear.ring(ring), Gear.DEFAULT_RING, -1, opp.getOverheadIcon() != HeadIcon.MAGIC,
            Anim.MAGIC_ANCIENT_MULTI_TARGET, pray, 0, prayerLevel);
        me.ghosts++;
        me.ghostExpected += calc.avg;
    }

    /** The local player's hitpoints now; a rise counts as healed. */
    void hp(int current)
    {
        if (current > prevHp) me.healed += current - prevHp;
        prevHp = current;
    }

    /** Matches the hitsplats of tick {@code now - 1} (on the opponent, and on the local player when this is the only
     *  fight) to the attacks waiting for them, and adds each landed attack's KO chance. */
    void settle(int now, List<Splat> splats, boolean solo)
    {
        int tick = now - 1;
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
        match(true, hits, now, tick, landed);
        if (solo) match(false, hits, now, tick, landed);
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

    private void match(boolean ours, List<Splat> all, int now, int tick, List<Pending> landed)
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
        int maulHits = 0;
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
                e.landed(n, dmg, tick, last);
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
                g.max *= maulHits;
                g.min *= maulHits;
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
            Double ko = null;
            boolean whole = hp != null && e.matched >= e.hits;
            if (e.anim == Anim.MELEE_DRAGON_CLAWS_SPEC && e.hits >= 4)
            {
                if (whole) ko = PerfCalc.clawsKo(e.acc, e.max, hp, e.after1 != null && e.before2 != null ? Math.max(0, e.before2 - e.after1) : 0);
            }
            else if (e.anim == Anim.RANGED_DARK_BOW || e.anim == Anim.RANGED_DARK_BOW_SPEC)
            {
                if (whole) ko = PerfCalc.dbowKo(e.acc, e.min, e.max, hp,
                    !e.stacked && e.after1 != null && e.before2 != null ? Math.max(0, e.before2 - e.after1) : 0);
            }
            else if (hp != null)
            {
                ko = e.shape == PerfCalc.STANDARD ? PerfCalc.ko(e.acc, e.min, e.max, hp)
                    : PerfCalc.clampedKo(e.acc, e.min, e.max, e.shape == PerfCalc.CLAMPED ? 1 : e.shapeHits, hp);
            }
            if (ko != null && ko > 0)
            {
                Side s = e.ours ? me : them;
                s.kos++;
                s.survive *= 1 - ko;
            }
            hp = hp == null ? null : hp - e.dmg;
        }
    }

    /** The eight stats sent with the match, in the body's order; null when no attack was seen. */
    Map<String, String> values()
    {
        if (me.attacks + them.attacks == 0) return null;
        String[] s = stats(true);
        Map<String, String> v = new LinkedHashMap<>();
        String[] keys = {"pf_op", "pf_ed", null, "pf_mh", "pf_pr", "pf_hp", "pf_rh", "pf_ko", "pf_gb"};
        for (int i = 0; i < keys.length; i++)
        {
            if (keys[i] != null) v.put(keys[i], s[i]);
        }
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

    /** An attack waiting for its hitsplats. */
    private static final class Pending
    {
        final boolean ours, gmaul;
        final Anim anim;
        final int tick, hits, shape, shapeHits;
        final double acc;
        int min, max, matched, dmg, hitTick = -1;
        Integer hpBefore, after1, before2;
        boolean first, stacked;

        Pending(boolean ours, Anim anim, int tick, PerfCalc c)
        {
            this.ours = ours;
            this.anim = anim;
            this.tick = tick;
            this.hits = anim.hits;
            this.gmaul = anim == Anim.MELEE_GRANITE_MAUL_SPEC;
            this.acc = c.acc;
            this.min = c.min;
            this.max = c.max;
            this.shape = c.shape;
            this.shapeHits = c.shapeHits;
        }

        int lookback()
        {
            return anim.style.melee() ? 3 : anim.style == MAGIC ? 6 : 4;
        }

        /** {@code n} hitsplats of {@code dmg} landed on {@code tick}; {@code last} carries the health bar after them. */
        void landed(int n, int dmg, int tick, Splat last)
        {
            this.dmg += dmg;
            matched += n;
            if (hitTick < 0) hitTick = tick;
            int ratio = last.ratio, scale = last.scale;
            if (ratio < 0 || scale <= 0)
            {
                ratio = last.actor.getHealthRatio();
                scale = last.actor.getHealthScale();
            }
            int before = PerfCalc.hpBefore(ratio, scale, 99, this.dmg), now = PerfCalc.hpBefore(ratio, scale, 99, dmg);
            if (before > 0) hpBefore = before;
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
    }
}
