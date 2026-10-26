/*
 * Copyright (c) 2021, Matsyir <https://github.com/matsyir>
 * Copyright (c) 2020, Mazhar <https://twitter.com/maz_rs>
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
import net.runelite.api.HeadIcon;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.AnimationID;
import static com.pvp.leaderboard.perf.Anim.Style.*;

/** Attack animations: the style, whether it is a special attack, a spell's base damage and the hitsplats it makes.
 *  Adapted from PvP Performance Tracker's AnimationData. */
enum Anim
{
    MELEE_VIGGORAS_CHAINMACE(AnimationID.WILD_CAVE_CHAINMACE_CRUSH, CRUSH),
    MELEE_DAGGER_SLASH(AnimationID.HUMAN_DDAGGER_LUNGE, SLASH),
    MELEE_SPEAR_STAB(AnimationID.HUMAN_DSPEAR_STAB, STAB),
    MELEE_SWORD_STAB(AnimationID.HUMAN_SWORD_STAB, STAB),
    MELEE_SCIM_SLASH(AnimationID.HUMAN_SWORD_SLASH, SLASH),
    MELEE_FANG_STAB(AnimationID.HUMAN_OSMUMTENS_FANG, STAB),
    MELEE_FANG_SPEC(AnimationID.WEAPON_SWORD_OSMUMTEN03_SPECIAL, STAB, true),
    MELEE_GENERIC_SLASH(AnimationID.HUMAN_AXE_CHOP, SLASH),
    MELEE_BATTLEAXE_SLASH(AnimationID.HUMAN_AXE_HACK, SLASH),
    MELEE_MACE_STAB(AnimationID.HUMAN_BLUNT_SPIKE, STAB),
    MELEE_BATTLEAXE_CRUSH(AnimationID.HUMAN_BLUNT_POUND, CRUSH),
    MELEE_2H_CRUSH(AnimationID.HUMAN_DHSWORD_CHOP, CRUSH),
    MELEE_2H_SLASH(AnimationID.HUMAN_DHSWORD_SLASH, SLASH),
    MELEE_STAFF_CRUSH_2(AnimationID.HUMAN_STAFF_PUMMEL, CRUSH),
    MELEE_STAFF_CRUSH_3(AnimationID.HUMAN_STAFFORB_PUMMEL, CRUSH),
    MELEE_PUNCH(AnimationID.HUMAN_UNARMEDPUNCH, CRUSH),
    MELEE_KICK(AnimationID.HUMAN_UNARMEDKICK, CRUSH),
    MELEE_STAFF_STAB(AnimationID.HUMAN_SPEAR_SPIKE, STAB),
    MELEE_SPEAR_CRUSH(AnimationID.HUMAN_SPEAR_LUNGE, CRUSH),
    MELEE_STAFF_SLASH(AnimationID.HUMAN_SCYTHE_SWEEP, SLASH),
    MELEE_BLUE_MOON_FEND(AnimationID.HUMAN_ZAMORAKSPEAR_LUNGE, CRUSH),
    MELEE_BLUE_MOON_JAB(AnimationID.HUMAN_ZAMORAKSPEAR_STAB, STAB),
    MELEE_BLUE_MOON_SWIPE(AnimationID.HUMAN_ZAMORAKSPEAR_SLASH, SLASH),
    MELEE_DLONG_SPEC(AnimationID.CLEAVE, SLASH, true),
    MELEE_DRAGON_MACE_SPEC(AnimationID.SHATTER, CRUSH, true),
    MELEE_DRAGON_DAGGER_SPEC(AnimationID.PUNCTURE, STAB, true, 0, 2),
    MELEE_DRAGON_WARHAMMER_SPEC(AnimationID.DRAGON_WARHAMMER_SA_PLAYER, CRUSH, true),
    MELEE_VOIDWAKER_SPEC(AnimationID.HUMAN_SPECIAL02_VOIDWAKER, MAGIC, true),
    MELEE_ABYSSAL_WHIP(AnimationID.SLAYER_ABYSSAL_WHIP_ATTACK, SLASH),
    MELEE_GRANITE_MAUL(AnimationID.SLAYER_GRANITE_MAUL_ATTACK, CRUSH),
    MELEE_GRANITE_MAUL_SPEC(AnimationID.SLAYER_GRANITE_MAUL_SPECIAL_ATTACK, CRUSH, true),
    MELEE_DHAROKS_GREATAXE_CRUSH(AnimationID.BARROW_DHAROK_SLASH, CRUSH),
    MELEE_DHAROKS_GREATAXE_SLASH(AnimationID.BARROW_DHAROK_CRUSH, SLASH),
    MELEE_AHRIMS_STAFF_CRUSH(AnimationID.BARROWS_QUARTERSTAFF_ATTACK, CRUSH),
    MELEE_OBBY_MAUL_CRUSH(AnimationID.DRAGON_PICKAXE_ANIM, CRUSH),
    MELEE_ABYSSAL_DAGGER_STAB(AnimationID.ABYSSAL_DAGGER_LUNGE, STAB),
    MELEE_ABYSSAL_BLUDGEON_CRUSH(AnimationID.ABYSSAL_BLUDGEON_CRUSH, CRUSH),
    MELEE_ABYSSAL_DAGGER_SPEC(AnimationID.ABYSSAL_DAGGER_SPECIAL, SLASH, true, 0, 2),
    MELEE_LEAF_BLADED_BATTLEAXE_CRUSH(AnimationID.BATTLEAXE_CRUSH, CRUSH),
    MELEE_INQUISITORS_MACE(AnimationID.HUMAN_INQUISITORS_MACE_CRUSH, CRUSH),
    MELEE_BARRELCHEST_ANCHOR_CRUSH(AnimationID.BRAIN_PLAYER_ANCHOR_ATTACK, CRUSH),
    MELEE_LEAF_BLADED_BATTLEAXE_SLASH(AnimationID.GODWARS_GODSWORD_ZAMORAK_PLAYER, SLASH),
    MELEE_GODSWORD_SLASH(AnimationID.DH_SWORD_UPDATE_SLASH, SLASH),
    MELEE_GODSWORD_CRUSH(AnimationID.DH_SWORD_UPDATE_SMASH, CRUSH),
    MELEE_DRAGON_CLAWS_SPEC(AnimationID.HUMAN_DRAGON_CLAWS_SPEC, SLASH, true, 0, 4),
    MELEE_VLS_SPEC(AnimationID.HUMAN_DRAGON_SWORD_SPEC, SLASH, true),
    MELEE_ELDER_MAUL(AnimationID.HUMAN_ELDER_MAUL_ATTACK, CRUSH),
    MELEE_ZAMORAK_GODSWORD_SPEC(AnimationID.ZGS_SPECIAL_PLAYER, SLASH, true),
    MELEE_ZAMORAK_GODSWORD_OR_SPEC(AnimationID.ZGS_SPECIAL_ORNATE_PLAYER, SLASH, true),
    MELEE_SARADOMIN_GODSWORD_SPEC(AnimationID.SGS_SPECIAL_PLAYER, SLASH, true),
    MELEE_SARADOMIN_GODSWORD_OR_SPEC(AnimationID.SGS_SPECIAL_ORNATE_PLAYER, SLASH, true),
    MELEE_BANDOS_GODSWORD_SPEC(AnimationID.BGS_SPECIAL_PLAYER, SLASH, true),
    MELEE_BANDOS_GODSWORD_OR_SPEC(AnimationID.BGS_SPECIAL_ORNATE_PLAYER, SLASH, true),
    MELEE_ARMADYL_GODSWORD_SPEC(AnimationID.AGS_SPECIAL_PLAYER, SLASH, true),
    MELEE_ARMADYL_GODSWORD_OR_SPEC(AnimationID.AGS_SPECIAL_ORNATE_PLAYER, SLASH, true),
    MELEE_ARMADYL_GODSWORD_LMS_SPEC(AnimationID.AGS_SPECIAL_BLIGHTED_PLAYER, SLASH, true),
    MELEE_SCYTHE(AnimationID.SCYTHE_OF_VITUR_ATTACK, SLASH),
    MELEE_GHAZI_RAPIER_STAB(AnimationID.GHRAZI_RAPIER_ATTACK, STAB),
    MELEE_ANCIENT_GODSWORD_SPEC(AnimationID.NGS_SPECIAL_PLAYER, SLASH, true),
    MELEE_SOULREAPER_AXE_CRUSH(AnimationID.ANCIENT_AXE_SLASH, CRUSH),
    MELEE_SOULREAPER_AXE_SLASH(AnimationID.ANCIENT_AXE_CRUSH, SLASH),
    MELEE_SOULREAPER_AXE_SPEC(AnimationID.ANCIENT_AXE_SPECIAL, SLASH, true),
    MELEE_DUAL_MACUACHUITL(AnimationID.PMOON_MACUAHUITL_CRUSH, CRUSH),
    MELEE_ELDER_MAUL2(AnimationID.HUMAN_ELDER_MAUL_SPEC, CRUSH),
    MELEE_BURNING_CLAWS_SPEC(AnimationID.HUMAN_WEAPON_BURNING_CLAWS_02_SPEC, SLASH, true, 0, 3),
    MELEE_ARKAN_BLADE_SPEC(AnimationID.VMQ4_ARKAN_BLADE_SPECIAL, SLASH, true),

    RANGED_SHORTBOW(AnimationID.HUMAN_BOW, RANGED),
    RANGED_RUNE_KNIFE_PVP(AnimationID.HUMAN_STAKE2, RANGED),
    RANGED_MAGIC_SHORTBOW_SPEC(AnimationID.SNAPSHOT, RANGED, true, 0, 2),
    RANGED_CROSSBOW_PVP(AnimationID.XBOWS_HUMAN_FIRE_AND_RELOAD, RANGED),
    RANGED_BLOWPIPE(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, RANGED),
    RANGED_DARTS(AnimationID.II_HUMAN_DART_THROW, RANGED),
    RANGED_BALLISTA(AnimationID.BALLISTA_ATTACK, RANGED),
    RANGED_DRAGON_THROWNAXE_SPEC(AnimationID.HUMAN_DRAGON_TAXE_SPEC, RANGED, true),
    RANGED_RUNE_CROSSBOW(AnimationID.XBOWS_HUMAN_FIRE_AND_RELOAD_PVN, RANGED),
    RANGED_BALLISTA_2(AnimationID.BALLISTA_ATTACK_PVN, RANGED),
    RANGED_RUNE_KNIFE(AnimationID.HUMAN_STAKE2_PVN, RANGED),
    RANGED_DRAGON_KNIFE(AnimationID.HUMAN_DRAGON_KNIFE, RANGED),
    RANGED_DRAGON_KNIFE_POISONED(AnimationID.HUMAN_DRAGON_KNIFE_P, RANGED),
    RANGED_DRAGON_KNIFE_SPEC(AnimationID.HUMAN_DRAGON_TKNIVES_SPEC_POISON, RANGED, true),
    RANGED_ZARYTE_CROSSBOW(AnimationID.ZCB_ATTACK_PVN, RANGED),
    RANGED_ZARYTE_CROSSBOW_PVP(AnimationID.ZCB_ATTACK, RANGED),
    RANGED_VENATOR(AnimationID.HUMAN_WEAPON_BOW_VENATOR01_SHOOT, RANGED),
    RANGED_ECLIPSE_ATLATL(AnimationID.HUMAN_ATLATL_ATTACK_RANGED_01, RANGED),
    RANGED_ECLIPSE_ATLATL_SPEC(AnimationID.HUMAN_SPECIAL_ATLATL_01, RANGED, true),
    RANGED_KARILS_CROSSBOW(AnimationID.BARROWS_REPEATING_CROSSBOW_FIRE, RANGED),
    RANGED_HUNTERS_SUNLIGHT_CROSSBOW(AnimationID.XBOWS_HUMAN_FIRE_AND_RELOAD_NO_STALL, RANGED),
    // Set from the bow's spot animation on the target, never from an animation id.
    RANGED_DARK_BOW(-1, RANGED, false, 0, 2),
    RANGED_DARK_BOW_SPEC(-1, RANGED, true, 0, 2),
    RANGED_DRAGON_CROSSBOW_SPEC(-1, RANGED, true),

    MAGIC_STANDARD_BIND(AnimationID.HUMAN_CASTENTANGLE, MAGIC),
    MAGIC_STANDARD_STRIKE_BOLT_BLAST(AnimationID.HUMAN_CASTSTRIKE, 16),
    MAGIC_STANDARD_BIND_STAFF(AnimationID.HUMAN_CASTENTANGLE_STAFF, MAGIC),
    MAGIC_STANDARD_STRIKE_BOLT_BLAST_STAFF(AnimationID.HUMAN_CASTSTRIKE_STAFF, 16),
    MAGIC_STANDARD_WAVE_STAFF(AnimationID.HUMAN_CASTWAVE_STAFF, 20),
    MAGIC_STANDARD_SURGE_STAFF(AnimationID.HUMAN_CAST_SURGE, 24),
    MAGIC_STANDARD_GOD_SPELL(AnimationID.HUMAN_CASTING, 30),
    MAGIC_ANCIENT_SINGLE_TARGET(AnimationID.ZAROS_CASTING, 26),
    MAGIC_ANCIENT_MULTI_TARGET(AnimationID.ZAROS_VERTICAL_CASTING, 30),
    MAGIC_VOLATILE_NIGHTMARE_STAFF_SPEC(AnimationID.NIGHTMARE_STAFF_SPECIAL, 66);

    private static final Map<Integer, Anim> BY_ID = new HashMap<>();

    static
    {
        for (Anim a : values())
        {
            if (a.id > 0) BY_ID.putIfAbsent(a.id, a);
        }
    }

    final int id;
    final Style style;
    final boolean spec;
    final int spell;
    final int hits;

    Anim(int id, Style style)
    {
        this(id, style, false, 0, 1);
    }

    Anim(int id, Style style, boolean spec)
    {
        this(id, style, spec, 0, 1);
    }

    Anim(int id, int spell)
    {
        this(id, MAGIC, false, spell, 1);
    }

    Anim(int id, Style style, boolean spec, int spell, int hits)
    {
        this.id = id;
        this.style = style;
        this.spec = spec;
        this.spell = spell;
        this.hits = hits;
    }

    /** The attack an animation is, or null. */
    static Anim of(int animationId)
    {
        return BY_ID.get(animationId);
    }

    /** A standard spellbook strike, bolt, blast, wave or surge (the fire spells' animations). */
    boolean standardSpell()
    {
        return this == MAGIC_STANDARD_STRIKE_BOLT_BLAST || this == MAGIC_STANDARD_STRIKE_BOLT_BLAST_STAFF
            || this == MAGIC_STANDARD_WAVE_STAFF || this == MAGIC_STANDARD_SURGE_STAFF;
    }

    boolean soulreaper()
    {
        return this == MELEE_SOULREAPER_AXE_CRUSH || this == MELEE_SOULREAPER_AXE_SLASH || this == MELEE_SOULREAPER_AXE_SPEC;
    }

    enum Style
    {
        STAB, SLASH, CRUSH, RANGED, MAGIC;

        boolean melee()
        {
            return ordinal() < 3;
        }

        /** The overhead that blocks this style. */
        HeadIcon protection()
        {
            return melee() ? HeadIcon.MELEE : this == RANGED ? HeadIcon.RANGED : HeadIcon.MAGIC;
        }

        /** The offensive prayers that suit this style. */
        Prayer[] prayers()
        {
            return melee() ? new Prayer[]{Prayer.ZEAL, Prayer.PIETY, Prayer.ULTIMATE_STRENGTH}
                : this == RANGED ? new Prayer[]{Prayer.RIGOUR, Prayer.DEADEYE, Prayer.EAGLE_EYE}
                : new Prayer[]{Prayer.AUGURY, Prayer.MYSTIC_VIGOUR, Prayer.MYSTIC_MIGHT};
        }
    }
}
