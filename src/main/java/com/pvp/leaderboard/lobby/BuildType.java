package com.pvp.leaderboard.lobby;

/**
 * Account-build types a player can advertise / fight as. Multi-select at
 * the gate (≥1 required) and rendered as cyan chips on each roster row.
 * This enum is the wire format on both sides — the server stores the
 * player's advertised builds, and the sender picks one of the opponent's
 * advertised builds when issuing an invite.
 *
 * <p><b>Declaration order is load-bearing.</b> Determines on-screen order
 * for the gate's toggle column AND the per-row chip strip (Main → Zerker
 * → Pure). {@code EnumSet} iteration follows declaration order, so the
 * current-style bar's build CSV always renders Main-first.
 */
public enum BuildType
{
    MAIN("Main"),
    ZERKER("Zerker"),
    PURE("Pure");

    public final String label;

    BuildType(String label)
    {
        this.label = label;
    }
}
