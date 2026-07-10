package com.pvp.leaderboard.service;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@ToString
public class MatchResult
{
    private final String playerId;
    private final String opponentId;
    private final String result; // "win", "loss", "tie"
    private final int world;
    private final long fightStartTs;
    private final long fightEndTs;
    private final String fightStartSpellbook;
    private final String fightEndSpellbook;
    private final boolean wasInMulti;
    private final long damageToOpponent;
    private final String clientUniqueId;

    /**
     * LMS freeze-log flag. When {@code true}, this loss was auto-submitted
     * because the player logged out / disabled the plugin mid-fight inside
     * an LMS arena. Serialized as the trailing optional {@code
     * lms_freeze_logout} field only when true; a normal fight leaves this
     * false and the field is omitted entirely (byte-for-byte unchanged
     * payload).
     */
    private final boolean lmsFreezeLogout;

    /**
     * Detection reason accompanying {@link #lmsFreezeLogout}: {@code
     * "logout"} (logout / connection lost mid-fight) or {@code
     * "plugin_disabled"} (plugin toggled off mid-fight — carries the
     * ban-escalation consequence). Null/blank when {@link #lmsFreezeLogout}
     * is false; serialized as the trailing {@code lms_freeze_reason} field
     * only when present.
     */
    private final String lmsFreezeReason;
}
