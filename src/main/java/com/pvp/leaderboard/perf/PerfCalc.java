/*
 * Copyright (c) 2021, Matsyir <https://github.com/matsyir>
 * Copyright (c) 2020, Mazhar <https://twitter.com/maz_rs>
 * Copyright (c) 2022, Matsyir <https://github.com/matsyir>
 * Copyright (c) 2026, Matsyir <https://github.com/Matsyir>
 * Copyright (c) 2026, LogicalSolutions <https://github.com/LogicalSoIutions>
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
import net.runelite.api.Prayer;
import net.runelite.api.kit.KitType;
import static com.pvp.leaderboard.perf.Anim.Style.*;

/** One attack's expected damage, accuracy and hit range, and the chance a hit ends the fight. Adapted from PvP
 *  Performance Tracker's PvpDamageCalc, PvpUtils, AssumedPrayers and FightType. */
final class PerfCalc
{
    static final int MAGIC_ATK = 3, RANGE_ATK = 4, STAB_DEF = 5, MAGIC_DEF = 8, RANGE_DEF = 9, STR = 10, RANGE_STR = 11,
        MAGIC_DMG = 12;
    static final int STANDARD = 0, CLAMPED = 1, MULTI_CLAMPED = 2;
    private static final int ATK = 0, STR_LV = 1, DEF = 2, RANGE = 3, MAGE = 4;
    private static final double[] NONE = {1, 1};
    /** The prayers of a fighter's prayer bits, bit 0 first. */
    static final Prayer[] PRAYERS = {Prayer.THICK_SKIN, Prayer.ROCK_SKIN, Prayer.STEEL_SKIN, Prayer.CHIVALRY,
        Prayer.PIETY, Prayer.ZEAL, Prayer.RIGOUR, Prayer.AUGURY, Prayer.CLARITY_OF_THOUGHT, Prayer.IMPROVED_REFLEXES,
        Prayer.INCREDIBLE_REFLEXES, Prayer.BURST_OF_STRENGTH, Prayer.SUPERHUMAN_STRENGTH, Prayer.ULTIMATE_STRENGTH,
        Prayer.SHARP_EYE, Prayer.HAWK_EYE, Prayer.EAGLE_EYE, Prayer.DEADEYE, Prayer.MYSTIC_WILL, Prayer.MYSTIC_LORE,
        Prayer.MYSTIC_MIGHT, Prayer.MYSTIC_VIGOUR};
    /** Each prayer's boosts in percent: attack, strength, defence, ranged attack, ranged strength, magic attack, magic
     *  damage, magic defence; trailing zeros left out. */
    private static final int[][] BOOSTS = {{0, 0, 5}, {0, 0, 10}, {0, 0, 15}, {15, 18, 20}, {20, 23, 25}, {25, 28, 25},
        {0, 0, 25, 20, 23}, {0, 0, 25, 0, 0, 25, 4, 25}, {5}, {10}, {15}, {0, 5}, {0, 10}, {0, 15}, {0, 0, 0, 5, 5},
        {0, 0, 0, 10, 10}, {0, 0, 0, 15, 15}, {0, 0, 5, 18, 18}, {0, 0, 0, 0, 0, 5, 0, 5}, {0, 0, 0, 0, 0, 10, 1, 10},
        {0, 0, 0, 0, 0, 15, 2, 15}, {0, 0, 5, 0, 0, 18, 3, 18}};

    private final boolean lms;
    private int[] la, ld;

    double avg, acc;
    int min, max, shape, shapeHits;
    private double proc;
    private int seekMin;

    PerfCalc(boolean lms)
    {
        this.lms = lms;
    }

    /** The assumed attack, strength, defence, ranged and magic levels: in Last Man Standing by the build the local
     *  defence level shows, in the PvP Arena by the loadout the local base defence shows. */
    static int[] levels(boolean lms, boolean arena, int def)
    {
        if (lms)
        {
            return def <= 1 ? new int[]{91, 118, 1, 112, 99} : def <= 50 ? new int[]{97, 118, 50, 112, 99}
                : new int[]{118, 118, 85, 112, 99};
        }
        return !arena || def > 50 ? new int[]{118, 118, 120, 112, 99} : def > 1 ? new int[]{91, 118, 62, 112, 99}
            : new int[]{74, 118, 1, 112, 99};
    }

    /** The offensive prayer assumed for an attack in {@code style} by a player with the local prayer and defence;
     *  Zeal in place of Piety in Last Man Standing and the PvP Arena, and elsewhere when {@code zeal}: the local
     *  player has it unlocked, with its Attack and Strength levels. */
    static Prayer assumed(Anim.Style style, boolean lms, boolean arena, boolean zeal, int prayer, int def)
    {
        boolean top = def >= 70;
        if (style.melee() && prayer >= 84 && (lms || arena ? top : zeal)) return Prayer.ZEAL;
        if (style.melee()) return prayer >= 70 && top ? Prayer.PIETY : Prayer.ULTIMATE_STRENGTH;
        if (style == RANGED) return prayer >= 74 && top ? Prayer.RIGOUR : lms || prayer < 62 ? Prayer.EAGLE_EYE : Prayer.DEADEYE;
        return prayer >= 77 && top ? Prayer.AUGURY : lms || prayer < 63 ? Prayer.MYSTIC_MIGHT : Prayer.MYSTIC_VIGOUR;
    }

    /** The prayer bits an assumed defender holds against an attack in {@code style}: the assumed prayer's when it gives
     *  25% Defence, else Steel Skin's; {@code def}: the defender's assumed Defence level. */
    static int guard(Anim.Style style, boolean lms, boolean arena, int prayer, int def)
    {
        int b = bit(assumed(style, lms, arena, false, prayer, def));
        return mults(b)[2] >= 1.25 ? b : bit(Prayer.STEEL_SKIN);
    }

    /** A prayer's bit; 0 for none. */
    static int bit(Prayer p)
    {
        int i = Arrays.asList(PRAYERS).indexOf(p);
        return i < 0 ? 0 : 1 << i;
    }

    /** The active prayers' multipliers, their boosts added: attack, strength, defence, ranged attack, ranged strength,
     *  magic attack, magic damage, magic defence. */
    static double[] mults(int prayers)
    {
        double[] m = new double[8];
        for (int b = 0; b < BOOSTS.length; b++)
        {
            for (int i = 0; (prayers >> b & 1) != 0 && i < BOOSTS[b].length; i++) m[i] += BOOSTS[b][i];
        }
        for (int i = 0; i < m.length; i++) m[i] = (100 + m[i]) / 100;
        return m;
    }

    /** One attack between two fighters. */
    void attack(PerfHit.Fighter atk, PerfHit.Fighter def, Anim anim, boolean success, int stacks)
    {
        avg = acc = proc = 0;
        min = max = seekMin = 0;
        shape = STANDARD;
        shapeHits = 1;
        la = atk.levels;
        ld = def.levels;
        int[] g = atk.gear;
        Gear w = at(g, KitType.WEAPON);
        int[] a = bonuses(atk), d = bonuses(def);
        double[] p = mults(atk.prayers), q = mults(def.prayers);
        Anim.Style s = anim.style;
        boolean spec = anim.spec;
        if (s.melee() || anim == Anim.MELEE_VOIDWAKER_SPEC)
        {
            double[] v = voids(g, STAB), m = {p[0], p[1]};
            meleeMax(a[STR], spec, w, v, m, stacks);
            meleeAcc(a, d, s, spec, w, v, m, q[2], stacks);
        }
        else if (s == RANGED)
        {
            double[] v = voids(g, RANGED), m = {p[3], p[4]};
            Gear.Ammo am = Gear.ammo(w, atk.measured ? atk.ammo : -1);
            rangedMax(a, spec, w, v, m, g, anim, am);
            rangedAcc(a[RANGE_ATK], d[RANGE_DEF], spec, w, v, m, g, am, q[2]);
        }
        else
        {
            double[] v = voids(g, MAGIC), m = {p[5], p[6]};
            magicMax(a[MAGIC_DMG], anim, m, v, g, w);
            magicAcc(a[MAGIC_ATK], d[MAGIC_DEF], w, anim, v, m, q[7], q[2]);
        }
        average(success, w, spec);
        double off = success ? 1 : 0.6;
        max = (int) (max * off);
        min = (int) (min * off);
    }

    /** The defender's protection (an Elysian proc, a staff special) cutting the hit. */
    void reduce(double m)
    {
        avg *= m;
        min = (int) Math.floor(min * m);
        max = (int) Math.floor(max * m);
    }

    /** Robes: a worn top or bottom with no ranged defence. */
    static boolean robes(IntFunction<int[]> lookup, int[] gear)
    {
        for (KitType k : new KitType[]{KitType.LEGS, KitType.TORSO})
        {
            int id = gear[k.getIndex()];
            int[] s = id > 0 ? stats(lookup, id) : null;
            if (s != null && s[RANGE_DEF] <= 0) return true;
        }
        return false;
    }

    private static Gear at(int[] gear, KitType k)
    {
        return Gear.of(gear[k.getIndex()]);
    }

    /** An item's bonuses, else (a variant without its own) its main item's; null when neither is known. */
    static int[] stats(IntFunction<int[]> lookup, int id)
    {
        int[] s = lookup.apply(id);
        Gear g = s == null ? Gear.of(id) : null;
        return g == null ? s : lookup.apply(g.id);
    }

    /** A fighter's worn items' and ring's bonuses; an assumed fighter wears the berserker ring (i). */
    private static int[] bonuses(PerfHit.Fighter f)
    {
        int ring = f.measured ? f.ring : Gear.DEFAULT_RING;
        int[] r = ring > 0 ? stats(f.stats, ring) : null, out = r == null ? new int[13] : r.clone();
        for (int id : f.gear)
        {
            int[] s = id > 0 ? stats(f.stats, id) : null;
            if (s != null) for (int i = 0; i < 13; i++) out[i] += s[i];
        }
        return out;
    }

    /** {accuracy, damage} multipliers of a full void set whose helm suits the attack. */
    private static double[] voids(int[] g, Anim.Style family)
    {
        Gear top = at(g, KitType.TORSO), legs = at(g, KitType.LEGS), helm = at(g, KitType.HEAD);
        boolean elite = top == Gear.VOID_ELITE_BODY && legs == Gear.VOID_ELITE_LEGS;
        if (at(g, KitType.HANDS) != Gear.VOID_GLOVES || !(elite || top == Gear.VOID_BODY && legs == Gear.VOID_LEGS)) return NONE;
        if (family.melee()) return helm == Gear.VOID_MELEE_HELM ? new double[]{1.1, 1.1} : NONE;
        if (family == RANGED) return helm != Gear.VOID_RANGE_HELM ? NONE : elite ? new double[]{1.125, 1.125} : new double[]{1.1, 1.1};
        return helm == Gear.VOID_MAGE_HELM ? new double[]{1.45, elite ? 1.025 : 1} : NONE;
    }

    private static double chance(double att, double def)
    {
        return att > def ? 1 - (def + 2) / (2 * (att + 1)) : att / (2 * (def + 1));
    }

    private void meleeMax(int str, boolean spec, Gear w, double[] v, double[] m, int stacks)
    {
        boolean reaper = w == Gear.SOULREAPER_AXE;
        int eff = (int) Math.floor(la[STR_LV] * m[1]) + 8 + 3;
        if (reaper && !spec) eff += (int) Math.floor(la[STR_LV] * stacks * 0.06);
        eff = (int) (eff * v[1]);
        int base = (int) Math.floor(0.5 + eff * (str + 64) / 640.0);
        double mod = !spec ? 1 : w == Gear.ARMADYL_GODSWORD ? 1.375 : w == Gear.ANCIENT_GODSWORD ? 1.1
            : w == Gear.STATIUS_WARHAMMER ? 1.25 : w == Gear.DRAGON_DAGGER ? 2.3 : w == Gear.VESTAS_LONGSWORD ? 1.2
            : w == Gear.DRAGON_WARHAMMER || w == Gear.VOIDWAKER || w == Gear.ARKAN_BLADE ? 1.5
            : w == Gear.ABYSSAL_DAGGER ? 0.85 : reaper ? 1 + stacks * 0.06 : 1;
        max = (int) (mod * base);
        if (reaper && spec) min = (int) Math.floor(base * stacks * 0.06);
    }

    private void meleeAcc(int[] a, int[] d, Anim.Style s, boolean spec, Gear w, double[] v, double[] m, double defPray,
        int stacks)
    {
        if (w == Gear.VOIDWAKER && spec)
        {
            acc = 1;
            return;
        }
        int i = s == STAB ? 0 : s == SLASH ? 1 : 2;
        double att = Math.floor(Math.floor(la[ATK] * m[0] + 8) * v[0] * (a[i] + 64));
        if (spec)
        {
            att *= w == Gear.DRAGON_DAGGER || w == Gear.ABYSSAL_DAGGER ? 1.25
                : w == Gear.ARMADYL_GODSWORD || w == Gear.ANCIENT_GODSWORD ? 2
                : w == Gear.OSMUMTENS_FANG || w == Gear.ARKAN_BLADE ? 1.5
                : w == Gear.SOULREAPER_AXE ? 1 + stacks * 0.12 : 1;
        }
        double eff = Math.floor(ld[DEF] * defPray + 8);
        acc = chance(att, w == Gear.VESTAS_LONGSWORD && spec ? Math.floor(eff * (d[STAB_DEF] + 64) * 0.25)
            : Math.floor(eff * (d[STAB_DEF + i] + 64)));
    }

    /** The crystal armour bonus with a crystal bow or the Bow of Faerdhinen. */
    private static double crystal(Gear w, int[] g, double helm, double body, double legs)
    {
        if (w != Gear.BOW_OF_FAERDHINEN && w != Gear.CRYSTAL_BOW && w != Gear.CRYSTAL_BOW_I) return 1;
        return 1 + (at(g, KitType.HEAD) == Gear.CRYSTAL_HELM ? helm : 0) + (at(g, KitType.TORSO) == Gear.CRYSTAL_BODY ? body : 0)
            + (at(g, KitType.LEGS) == Gear.CRYSTAL_LEGS ? legs : 0);
    }

    private void rangedMax(int[] a, boolean spec, Gear w, double[] v, double[] m, int[] g, Anim anim, Gear.Ammo am)
    {
        boolean dbow = w == Gear.DARK_BOW, dcb = w == Gear.DRAGON_CROSSBOW;
        boolean opals = am == Gear.Ammo.OPAL_DRAGON_BOLTS_E, procs = !(dcb && spec);
        int hits = Math.max(1, anim.hits);
        if (!(dbow && spec) && !lms && am != null && am.seeking())
        {
            seekMin = 3 * hits;
            shapeHits = hits;
            shape = hits > 1 ? MULTI_CLAMPED : CLAMPED;
        }
        double eff = Math.floor(la[RANGE] * m[1] + 8) * v[1];
        int base = (int) Math.floor(0.5 + eff * (a[RANGE_STR] + (am == null ? 0 : am.str) + 64) / 640.0);
        double mod = am == null ? 1 : am.dmg;
        if (procs && (am == Gear.Ammo.DIAMOND_BOLTS_E || am == Gear.Ammo.DIAMOND_DRAGON_BOLTS_E))
        {
            proc = (mod - 1) * base / 2.0;
            mod = 1;
        }
        else if (procs && opals)
        {
            proc = am.bonusMax(la[RANGE]);
        }
        mod = w == Gear.HEAVY_BALLISTA && spec ? 1.25 : dbow ? (spec ? 3 : 2) : dcb && spec ? 1.2 : mod;
        if (w == Gear.ECLIPSE_ATLATL)
        {
            double e = Math.floor(la[STR_LV] * m[1] + 8) * v[1];
            max = (int) Math.floor(0.5 + e * (a[STR] + 64) / 640.0);
        }
        else
        {
            max = (int) (mod * base + (am != null && !opals ? am.bonusMax(la[RANGE]) : 0));
        }
        max = (int) (max * crystal(w, g, .025, .075, .05));
        if (dbow && spec) max = Math.min(max, 96);
        else if (spec && hits > 1 && (w == Gear.MAGIC_SHORTBOW || w == Gear.MAGIC_SHORTBOW_I)) max *= hits;
    }

    private void rangedAcc(int ratk, int rdef, boolean spec, Gear w, double[] v, double[] m, int[] g, Gear.Ammo am,
        double defPray)
    {
        if (!lms && am != null && am.seeking()) ratk += 20;
        double eff = Math.floor(la[RANGE] * m[0] + (w == Gear.DARK_BOW && spec ? 3 : 0) + 8) * v[0]
            * crystal(w, g, .05, .15, .1);
        double att = Math.floor(eff * (ratk + 64));
        if (spec) att = Math.floor(att * (w == Gear.ARMADYL_CROSSBOW ? 2 : w == Gear.HEAVY_BALLISTA ? 1.25 : 1));
        acc = chance(att, Math.floor(Math.floor(ld[DEF] * defPray + 8) * (rdef + 64)));
        boolean procs = am == Gear.Ammo.DIAMOND_BOLTS_E || am == Gear.Ammo.DIAMOND_DRAGON_BOLTS_E
            || am == Gear.Ammo.OPAL_DRAGON_BOLTS_E;
        if (procs && !(w == Gear.DRAGON_CROSSBOW && spec)) acc = acc * .95 + .05;
    }

    private void magicMax(int mdmg, Anim anim, double[] m, double[] v, int[] g, Gear w)
    {
        mdmg += (at(g, KitType.HEAD) == Gear.VIRTUS_MASK ? 3 : 0) + (at(g, KitType.TORSO) == Gear.VIRTUS_ROBE_TOP ? 3 : 0)
            + (at(g, KitType.LEGS) == Gear.VIRTUS_ROBE_BOTTOM ? 3 : 0);
        double b = (1 + mdmg / 100.0) * m[1];
        if (w == Gear.SMOKE_BATTLESTAFF && anim.standardSpell()) b *= 1.1;
        if (at(g, KitType.SHIELD) == Gear.TOME_OF_FIRE && anim.standardSpell()) b *= 1.5;
        max = (int) (anim.spell * (b * v[1]));
    }

    private void magicAcc(int matk, int mdef, Gear w, Anim anim, double[] v, double[] m, double mageMult, double defPray)
    {
        double att = Math.floor(Math.floor(la[MAGE] * m[0] + 8) * v[0] * (matk + 64));
        double mageDef = Math.floor(ld[MAGE] * mageMult * 0.7) + Math.floor(Math.floor(ld[DEF] * defPray + 8) * 0.3);
        acc = chance(att, Math.floor(mageDef * (mdef + 64)));
        if (w == Gear.SMOKE_BATTLESTAFF && anim.standardSpell()) acc *= 1.1;
        else if (w == Gear.VOLATILE_NIGHTMARE_STAFF && anim == Anim.MAGIC_VOLATILE_NIGHTMARE_STAFF_SPEC) acc *= 0.5;
    }

    private void average(boolean success, Gear w, boolean spec)
    {
        double off = success ? 1 : 0.6, hit;
        if (spec && (w == Gear.DARK_BOW || w == Gear.VESTAS_LONGSWORD || w == Gear.STATIUS_WARHAMMER))
        {
            double adj = w == Gear.DARK_BOW ? acc : 1;
            min = w == Gear.DARK_BOW ? 16 : (int) (max * (w == Gear.VESTAS_LONGSWORD ? 0.2 : 0.25));
            int total = 0;
            for (int i = 0; i <= max; i++) total += i < min ? min / adj : i;
            hit = (double) total / max;
        }
        else if (seekMin > 0)
        {
            min = seekMin;
            hit = shape == MULTI_CLAMPED ? clampAvg(min, shapeHits) : clampAvg(min);
        }
        else if (spec && w == Gear.DRAGON_CLAWS)
        {
            double a = acc, miss = 1 - a;
            avg = ((a * 3 + miss * a * 2.5 + miss * miss * a * 2 + miss * miss * miss * a * 1.5) * (max / 2.0)
                + Math.pow(miss, 4) * (4.0 / 3.0)) * off;
            acc = 1 - Math.pow(miss, 4);
            int first = max - 1;
            max = first + first / 2 + first / 4 + first / 4 + 1;
            return;
        }
        else if (spec && w == Gear.BURNING_CLAWS)
        {
            double a = acc, miss = 1 - a;
            avg = (a * burning(.75, 1.75) + miss * a * burning(.5, 1.5) + miss * miss * a * burning(.25, 1.25)
                + miss * miss * miss * burning(0, 1)) * off;
            max = burnTotal((int) Math.floor(1.75 * max));
            min = 0;
            return;
        }
        else if (w == Gear.OSMUMTENS_FANG)
        {
            acc = 1 - Math.pow(1 - acc, 2);
            int base = max;
            min = (int) Math.ceil(base * 0.15);
            max = (int) Math.ceil((spec ? 1 : 0.85) * base);
            hit = (min + max) / 2.0;
        }
        else if (spec && (w == Gear.VOIDWAKER || w == Gear.SOULREAPER_AXE))
        {
            if (w == Gear.VOIDWAKER) min = (int) Math.floor(max / 1.5 * 0.5);
            hit = (min + max) / 2.0;
        }
        else
        {
            hit = (min + max) / 2.0;
            if (spec && w == Gear.ABYSSAL_DAGGER)
            {
                hit *= 2;
                max *= 2;
            }
        }
        avg = acc * hit * off + proc * off;
        if (spec && w == Gear.ANCIENT_GODSWORD) avg += 25;
    }

    /** The average landed hit when rolls under {@code minimum} count as {@code minimum}. */
    private double clampAvg(int minimum)
    {
        minimum = Math.max(0, minimum);
        max = Math.max(max, minimum);
        return clampMean(minimum, max);
    }

    private double clampAvg(int minimum, int hits)
    {
        hits = Math.max(1, hits);
        int lo = Math.max(0, minimum / hits), hi = Math.max(lo, max / hits);
        max = Math.max(max, hi * hits);
        min = lo * hits;
        return hits * clampMean(lo, hi);
    }

    private static double clampMean(int minimum, int maximum)
    {
        int total = 0;
        for (int i = 0; i <= maximum; i++) total += Math.max(i, minimum);
        return (double) total / (maximum + 1);
    }

    /** The burning claws' average total over rolls from {@code lo} to {@code hi} times the max hit. */
    private double burning(double lo, double hi)
    {
        int a = (int) Math.floor(lo * max), b = (int) Math.floor(hi * max);
        if (a > b) return 0;
        double sum = 0;
        for (int d = a; d <= b; d++) sum += burnTotal(d);
        return sum / (b - a + 1);
    }

    private static int burnTotal(int d)
    {
        return (int) Math.floor(0.25 * d) * 2 + (int) Math.floor(0.5 * d);
    }

    /** The chance one roll of {@code min}..{@code max} at {@code acc} reaches {@code hp}; null when it cannot. */
    static Double ko(double acc, int min, int max, int hp)
    {
        if (max < hp || hp <= 0) return null;
        min = Math.max(0, Math.min(min, max));
        int koHits = Math.max(0, max - Math.max(min, hp) + 1);
        return Math.max(0.0, Math.min(acc * ((double) koHits / (max - min + 1)), 1.0));
    }

    /** The same for {@code hits} rolls whose low rolls count as the minimum. */
    static Double clampedKo(double acc, int min, int max, int hits, int hp)
    {
        if (max < hp || hp <= 0) return null;
        hits = Math.max(1, hits);
        int lo = Math.max(0, min / hits), hi = Math.max(lo, max / hits);
        double[] one = dist(acc, lo, hi, hi), total = {1.0};
        for (int h = 0; h < hits; h++) total = convolve(total, one);
        if (hp >= total.length) return null;
        double c = 0;
        for (int d = hp; d < total.length; d++) c += total[d];
        return Math.max(0.0, Math.min(c, 1.0));
    }

    /** A dragon claws special over its two ticks, with {@code heal} between them. */
    static Double clawsKo(double acc, int max, int hp, int heal)
    {
        if (max <= 0 || hp <= 0) return null;
        double a = Math.max(0.0, Math.min(1.0, acc));
        int top = Math.max(0, (max - 1) / 2);
        if (top <= 0) return null;
        double swing = a <= 0 ? 0 : a >= 1 ? 1 : 1 - Math.pow(1 - a, 0.25), miss = 1 - swing, each = 1.0 / (top + 1);
        double ko = 0;
        for (int r = 0; r <= top; r++)
        {
            int t1 = r + (r + 1) / 2, rest = Math.max(0, 2 * r - t1 - r / 4);
            int late = (int) Math.ceil(0.75 * r) + (int) Math.floor(0.75 * r) >= hp + heal ? 1 : 0;
            ko += swing * each * (t1 >= hp || r / 4 + rest >= hp - t1 + heal ? 1 : 0);
            ko += miss * swing * each * (r >= hp || r >= hp - r + heal ? 1 : 0);
            ko += miss * miss * swing * each * late;
            ko += miss * miss * miss * swing * each * late;
        }
        return Math.max(0.0, Math.min(ko, 1.0));
    }

    /** A dark bow's two arrows, each capped at 48, with {@code heal} between them. */
    static Double dbowKo(double acc, int min, int max, int hp, int heal)
    {
        if (max <= 0 || hp <= 0) return null;
        int each = Math.max(0, max / 2);
        if (each <= 0) return null;
        double[] d = dist(Math.max(0.0, Math.min(1.0, acc)), Math.max(0, min / 2), each, 48);
        int top = d.length - 1;
        double[] tail = new double[top + 2];
        for (int i = top; i >= 0; i--) tail[i] = tail[i + 1] + d[i];
        double ko = 0;
        for (int d1 = 0; d1 <= top; d1++)
        {
            int need = hp - d1 + Math.max(0, heal);
            if (d[d1] <= 0) continue;
            if (d1 >= hp || need <= 0) ko += d[d1];
            else if (need <= top) ko += d[d1] * tail[need];
        }
        return Math.max(0.0, Math.min(ko, 1.0));
    }

    /** Damage chances for one roll of {@code min}..{@code max} at {@code acc}, rolls above {@code cap} capped. */
    private static double[] dist(double acc, int min, int max, int cap)
    {
        int eff = cap > 0 ? cap : max, top = Math.min(max, eff);
        double[] d = new double[top + 1];
        double a = Math.max(0.0, Math.min(1.0, acc));
        if (a <= 0)
        {
            d[0] = 1;
            return d;
        }
        int lo = Math.min(Math.min(Math.max(0, min), max), eff);
        for (int r = 0; r <= max; r++) d[Math.min(Math.min(Math.max(r, lo), eff), top)] += a / (max + 1);
        d[0] += 1 - a;
        return d;
    }

    private static double[] convolve(double[] x, double[] y)
    {
        double[] out = new double[x.length + y.length - 1];
        for (int i = 0; i < x.length; i++)
        {
            if (x[i] <= 0) continue;
            for (int j = 0; j < y.length; j++)
            {
                if (y[j] > 0) out[i + j] += x[i] * y[j];
            }
        }
        return out;
    }

    /** Hitpoints before a hit of {@code dmg}, from the health bar after it; -1 when it cannot be read. */
    static int hpBefore(int ratio, int scale, int maxHp, int dmg)
    {
        if (ratio < 0 || scale <= 0 || maxHp <= 0) return -1;
        if (ratio == 0) return dmg;
        int lo = 1, hi = maxHp;
        if (scale > 1)
        {
            if (ratio > 1) lo = (maxHp * (ratio - 1) + scale - 2) / (scale - 1);
            hi = Math.min(maxHp, (maxHp * ratio - 1) / (scale - 1));
        }
        return (lo + hi + 1) / 2 + dmg;
    }
}
