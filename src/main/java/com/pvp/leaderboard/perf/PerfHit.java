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

import java.util.function.*;
import net.runelite.api.kit.KitType;

/** One hit from both sides' inputs: its expected damage, accuracy and hit range, and the chance it ends the fight.
 *  Adapted from PvP Performance Tracker's FightLogEntry and its hitsplat matching. */
final class PerfHit
{
    private static final int WEAPON = KitType.WEAPON.getIndex();

    /** One side of a hit: boosted attack, strength, defence, ranged and magic levels; the active prayers (bits of
     *  {@link PerfCalc#PRAYERS}); worn item ids by KitType index (-1 empty); the ring and ammo; an item's 13 bonuses
     *  (null when unknown); measured (its ring and ammo count) or assumed (the berserker ring (i), the weapon's usual
     *  ammo). */
    static final class Fighter
    {
        final int[] levels;
        final int prayers;
        final int[] gear;
        final int ring, ammo;
        final IntFunction<int[]> stats;
        final boolean measured;

        Fighter(int[] levels, int prayers, int[] gear, int ring, int ammo, IntFunction<int[]> stats, boolean measured)
        {
            this.levels = levels;
            this.prayers = prayers;
            this.gear = gear;
            this.ring = ring;
            this.ammo = ammo;
            this.stats = stats;
            this.measured = measured;
        }
    }

    /** A hit's average damage, accuracy, minimum and maximum, and its roll's shape. */
    static final class Hit
    {
        double avg, acc;
        int min, max, shape, shapeHits;
    }

    /** One attack; {@code procs}: 1 an Elysian proc on the defender, 2 a staff special's melee protection. */
    static Hit of(boolean lms, Fighter attacker, Fighter defender, Anim anim, boolean success, int stacks, int procs)
    {
        if (longsword(attacker.gear[WEAPON]))
        {
            int[] g = attacker.gear.clone();
            g[WEAPON] = Gear.VESTAS_LONGSWORD.id;
            attacker = new Fighter(attacker.levels, attacker.prayers, g, attacker.ring, attacker.ammo, attacker.stats,
                attacker.measured);
            anim = anim.spec ? Anim.MELEE_VLS_SPEC : Anim.MELEE_SCIM_SLASH;
        }
        PerfCalc c = new PerfCalc(lms);
        c.attack(attacker, defender, anim, success, stacks);
        if ((procs & 1) != 0) c.reduce(0.75);
        if ((procs & 2) != 0 && anim.style.melee()) c.reduce(0.5);
        Hit h = new Hit();
        h.avg = c.avg;
        h.acc = c.acc;
        h.min = c.min;
        h.max = c.max;
        h.shape = c.shape;
        h.shapeHits = c.shapeHits;
        return h;
    }

    /** The chance the hit ends the fight from {@code hpBefore}; {@code heal}: the hitpoints healed between the halves
     *  of a dragon claws special or a dark bow shot, null when not all of it landed. Null when it cannot. */
    static Double ko(Hit hit, Anim anim, int hpBefore, Integer heal)
    {
        if (anim == Anim.MELEE_DRAGON_CLAWS_SPEC)
        {
            return heal == null ? null : PerfCalc.clawsKo(hit.acc, hit.max, hpBefore, heal);
        }
        if (anim == Anim.RANGED_DARK_BOW || anim == Anim.RANGED_DARK_BOW_SPEC)
        {
            return heal == null ? null : PerfCalc.dbowKo(hit.acc, hit.min, hit.max, hpBefore, heal);
        }
        return hit.shape == PerfCalc.STANDARD ? PerfCalc.ko(hit.acc, hit.min, hit.max, hpBefore)
            : PerfCalc.clampedKo(hit.acc, hit.min, hit.max, hit.shape == PerfCalc.CLAMPED ? 1 : hit.shapeHits, hpBefore);
    }

    /** A dragon longsword or Vesta's longsword, which hit as Vesta's longsword. */
    static boolean longsword(int itemId)
    {
        Gear g = Gear.of(itemId);
        return g == Gear.DRAGON_LONGSWORD || g == Gear.VESTAS_LONGSWORD;
    }
}
