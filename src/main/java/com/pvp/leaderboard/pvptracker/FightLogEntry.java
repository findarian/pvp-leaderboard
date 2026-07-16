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
package com.pvp.leaderboard.pvptracker;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import joptsimple.internal.Strings;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.HeadIcon;

// A fight log entry for a single Fighter. Will be saved in a List of FightLogEntries in the Fighter class.
@Getter
public class FightLogEntry implements Comparable<FightLogEntry>
{
	// general data
	// don't expose attacker name since it is present in the parent class (Fighter), so it is
	// redundant use of storage
	public String attackerName;
	@Expose
	@SerializedName("t")
	private long time;

	@Setter
	@Expose
	@SerializedName("T")
	private int tick;

	// this boolean represents if this is a "complete" fight log entry or not.
	// if a fight log entry is full/complete, then it has all attack data.
	// an "incomplete" fight log entry means it's only holding the current attacker's defensive stats to be used with
	// an opposing attack, to be matched up with fight analysis/data merging
	// very rough way to do this but itll work
	// we're no longer doing the merge/analysis on the client for the time being, but this is still being used for merges on pvp hub
	@Expose
	@SerializedName("f")
	private boolean isFullEntry;


	// attacker data
	@Expose
	@SerializedName("G")
	// current attacker's gear. The attacker is not necessarily the competitor.
	// Set using PlayerComposition::getEquipmentIds
	private int[] attackerGear;
	@Expose
	@SerializedName("O")
	private HeadIcon attackerOverhead;
	@Expose
	@SerializedName("m") // m because movement?
	private AnimationData animationData;
	@Setter
	@Expose
	@SerializedName("d")
	private double expectedDamage; // NOTE: previously referred to as "Deserved damage"
	@Expose
	@SerializedName("a")
	private double accuracy;
	@Setter
	@Expose
	@SerializedName("h") // h for highest hit
	private int maxHit;
	@Setter
	@Expose
	@SerializedName("l") // l for lowest hit
	private int minHit;
	@Expose
	@SerializedName("s")
	private boolean splash; // true if it was a magic attack and it splashed
	@Expose
	@SerializedName("C")
	@Setter
	private CombatLevels attackerLevels; // CAN BE NULL

	@Getter
	@Setter
	@Expose
	@SerializedName("k") // k for ko chance
	private Double koChance = null;

	@Getter // Added Getter for isKoChanceCalculated
	@Setter
	private transient boolean koChanceCalculated = false; // Flag to track if KO chance was processed for this entry

	@Getter
	@Setter
	@Expose
	@SerializedName("eH") // Estimated Hp before hit
	private Integer estimatedHpBeforeHit = null;

	@Getter
	@Setter
	@Expose
	@SerializedName("oH") // Opponent max Hp used for calc
	private Integer opponentMaxHp = null;

	@Expose
	@Getter
	@Setter
	@SerializedName("mC") // matched hits count so far
	private int matchedHitsCount;

	@Expose
	@Getter
	@Setter
	@SerializedName("aD") // actual Damage Sum
	private Integer actualDamageSum;

	// defender data
	@Expose
	@SerializedName("g")
	private int[] defenderGear;
	@Expose
	@SerializedName("o")
	private HeadIcon defenderOverhead;
	@Expose
	@SerializedName("E")
	@Setter
	private boolean defenderElyProc = false;
	@Expose
	@SerializedName("S")
	@Setter
	private boolean defenderSotdMeleeReductionProc = false;

	@Expose
	@SerializedName("p")
	private int attackerOffensivePray; // offensive pray saved as SpriteID since that's all we use it for.

	@Expose
	@Getter
	@SerializedName("R")
	private Integer attackerRingItemId;
	@Expose
	@Getter
	@SerializedName("A")
	private Integer attackerAmmoItemId;

	@Expose
	@Getter
	private int expectedHits; // Declare expectedHits field

	@Expose
	@SerializedName("GMS")
	@Getter
	@Setter
	private boolean isGmaulSpecial = false;

	// Recorded opponent health ratio and scale at the moment of the hitsplat
	private int recordedHealthRatio = -1;
	private int recordedHealthScale = -1;
	// Get the recorded hitsplat landing tick, or -1 if not recorded
	// Set the tick when the hitsplat landed
	// The tick at which the first hitsplat for this entry landed
	@Getter
	@Setter
	private int hitsplatTick = -1;
	@Getter
	@Setter
	private transient int hitsplatMatchTick = -1;

	// Display/Transient fields calculated during post-processing in onGameTick
	@Expose
	@Getter @Setter
	private Integer displayHpBefore = null;
	@Expose
	@Getter @Setter
	private Integer displayHpAfter = null;
	@Expose
	@Getter @Setter
	private Double displayKoChance = null;
	@Expose
	@Getter @Setter
	private boolean isPartOfTickGroup = false;

	// Transient fields for handling multi-tick Dragon Claws special attacks
	@Getter
	@Setter
	private transient Integer clawsPhase1Damage = null;
	@Getter
	@Setter
	private transient Integer clawsHpBeforePhase1 = null;
	@Getter
	@Setter
	private transient Integer clawsHpAfterPhase1 = null;
	@Getter
	@Setter
	private transient Integer clawsHpBeforePhase2 = null;
	// Transient fields for handling Dark Bow double-hit sequencing
	@Getter
	@Setter
	private transient Integer darkBowHpBeforeHit1 = null;
	@Getter
	@Setter
	private transient Integer darkBowHpAfterHit1 = null;
	@Getter
	@Setter
	private transient Integer darkBowHpBeforeHit2 = null;
	@Getter
	@Setter
	private transient boolean darkBowHitsStacked = false;

	// use to sort by last fight time, to sort fights by date/time.
	@Override
	public int compareTo(FightLogEntry o)
	{
		long diff = tick - o.tick;

		// if the attacks were same-ticked, further sort by attacker name so that the sorting is more consistent between 2
		// clients. Since we add competitor logs to the array before opponent logs, it seems like it would always
		// prioritize displaying the client player on same-tick attacks, which would result in an oddly conflicting
		// result when comparing 2 clients, even if the result is the same. This should help keep it more consistent.
		// note that this is when comparing 2 different fight logs, so when processing same-tick attacks, it should
		// never be comparing the attacker name with itself, always the opponent name.
		if (diff == 0 && !Strings.isNullOrEmpty(attackerName) && !Strings.isNullOrEmpty(o.attackerName))
		{
			diff = attackerName.compareTo(o.attackerName);
		}

		// if diff = 0, return 0. Otherwise, divide diff by its absolute value. This will result in
		// -1 for negative numbers, and 1 for positive numbers, keeping the sign and a safely small int.
		return diff == 0 ? 0 :
			(int)(diff / Math.abs(diff));
	}

}
