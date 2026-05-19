package com.pvp.leaderboard.lobby;

/**
 * Account-build types a player can advertise / fight as. Multi-select at the
 * gate (≥1 required) and rendered as cyan chips on each roster row.
 *
 * <p>Mirrors the architecture's lobby-side build concept (see
 * {@code docs/SOCKET_LOBBY_ARCHITECTURE.md} → Phase 2). When the real
 * {@code WebSocketLobbyService} ships, this enum is the wire format on both
 * sides — server stores the player's advertised builds in
 * {@code OSRS-LobbyMembers}, sender picks one of the opponent's advertised
 * builds when issuing an invite.
 *
 * <p>Renamed from {@code MatchmakingLobbyPanel.AccountType} as part of
 * {@code p1-plugin-mock-refactor}. "AccountType" was an internal mock-side
 * name; "BuildType" is the lobby-facing name we ship to the server.
 *
 * <p><b>Declaration order is load-bearing.</b> Determines on-screen order
 * for the gate's toggle column AND the per-row chip strip (Main → Zerker →
 * Pure). {@code EnumSet} iteration follows declaration order, so the
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
