/*
 * Copyright (c)  2021, Matsyir <https://github.com/Matsyir>
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
package com.pvp.leaderboard.pvptracker;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Queue;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;

@Slf4j
@Getter
public
class Fighter
{
	// Target graphics IDs indicating special attacks
	private static final int GFX_TARGET_DBOW_SPEC = 1100;   // dragon-arrow gfx on target
	private static final int GFX_TARGET_DCBOW_SPEC = 157;    // Annihilate AOE gfx on target

	@Setter
	private Player player;
	@Setter
	@Expose
	@SerializedName("n") // use 1 letter serialized variable names for more compact storage
	private String name; // username
	@Expose
	@SerializedName("a")
	private int attackCount; // total number of attacks
	@Expose
	@SerializedName("s")
	private int offPraySuccessCount; // total number of successful off-pray attacks
									 // (when you use a different combat style than your opponent's overhead)
	@Expose
	@SerializedName("d") // NOTE: previously referred to as "Deserved damage"
	private double expectedDamage; // total expected damage based on gear & opponent's pray
	@Expose
	@SerializedName("h") // h for "hitsplats", real hits
	private int damageDealt; // actual damage dealt based on opponent's hitsplats

	@Expose
	@SerializedName("z") // z because idk and want to keep 1 character for most compact storage
	private int totalMagicAttackCount; // total count of magic attacks
	@Expose
	@SerializedName("m")
	private int magicHitCount; // count of 'successful' magic hits (where you don't splash)
	@Expose
	@SerializedName("M")
	private double magicHitCountExpected; // cumulative magic accuracy percentage for each attack

	@Expose
	@SerializedName("p")
	private int offensivePraySuccessCount;

	@Expose
	@SerializedName("g")
	private int ghostBarrageCount;
	@Expose
	@SerializedName("y")
	private double ghostBarrageExpectedDamage;

	@Expose
	@SerializedName("H")
	private int hpHealed;

	@Expose
	@SerializedName("rh") // robe hits
	private int robeHits = 0;

	@Expose
	@SerializedName("x") // x for X_X
	private boolean dead; // will be true if the fighter died in the fight

	@Expose
	@SerializedName("l")
	private ArrayList<FightLogEntry> fightLogEntries;
	@Expose
	@SerializedName("b")
	private CombatLevels baseLevels;

	private int lastGhostBarrageCheckedTick = -1;
	@Setter
	private int lastGhostBarrageCheckedMageXp = -1;

	@Getter
	private transient Queue<FightLogEntry> pendingAttacks;
}
