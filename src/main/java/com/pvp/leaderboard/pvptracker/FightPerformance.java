/*
 * Copyright (c) 2021, Matsyir <https://github.com/matsyir>
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
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

// Holds two Fighters which contain data about PvP fight performance, and has many methods to
// add to the fight, display stats or check the status of the fight.
@Slf4j
@Getter
public class FightPerformance implements Comparable<FightPerformance>
{
	@Expose
	@SerializedName("c") // use 1 letter serialized variable names for more compact storage
	public Fighter competitor;
	@Expose
	@SerializedName("o")
	public Fighter opponent;
	@Expose
	@SerializedName("t")
	public long lastFightTime; // last fight time saved as epochMilli timestamp (serializing an Instant was a bad time)
	@Expose
	@SerializedName("l")
	public FightType fightType; // save a FightType if the fight was done in LMS, and with what build
	@Expose
	@SerializedName("w")
	private int world;
	@Expose
	@SerializedName("fightID")
	@Getter
	private String fightId;
	@Expose
	@SerializedName("v")
	@Getter
	private String pluginVersion;
	@Expose
	@SerializedName("pn")
	private String pvpHubUploadName;

	@Getter
	private transient FightPerformance pvpHubSyncedFight;
	@Getter
	// returns true if this IS the pvpHubSyncedFight, rather than the core/parent fight containing it.
	private transient boolean isSyncedFight = false;
	@Getter
	private transient boolean pvpHubSyncInProgress = false;
	private transient boolean fightIdGenerated = false;
	private transient long initialTime = 0;
	private transient int initialFightTick = -1;
	private transient boolean logTicksRelative = false;
	private transient int[] lastNonEmptyInventorySnapshot;

	private int competitorPrevHp; // intentionally don't serialize this, temp variable used to calculate hp healed.

	// KO Chance stats, updated per-attack. Not serialized.
	@Getter
	private transient double competitorTotalKoChance = 0;
	@Getter
	private transient double opponentTotalKoChance = 0;
	@Getter
	private transient Double competitorLastKoChance = null;
	@Getter
	private transient Double opponentLastKoChance = null;
	@Getter
	private transient int competitorKoChanceCount = 0;
	@Getter
	private transient int opponentKoChanceCount = 0;
	private transient double competitorSurvivalProb = 1.0;
	private transient double opponentSurvivalProb = 1.0;
	@Getter
	@Setter
	private transient String loadedFromFname;
	@Getter
	@Setter
	private transient boolean isFavorite;

	// use to sort by last fight time, to sort fights by date/time.
	@Override
	public int compareTo(FightPerformance o)
	{
		long diff = lastFightTime - o.lastFightTime;

		// if diff = 0, return 0. Otherwise, divide diff by its absolute value. This will result in
		// -1 for negative numbers, and 1 for positive numbers, keeping the sign and a safely small int.
		return diff == 0 ? 0 :
			(int)(diff / Math.abs(diff));
	}
}
