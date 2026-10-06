package com.pvp.leaderboard.lobby;

import java.util.*;

/**
 * One row in the lobby roster. Server is source of truth; the panel
 * only reads this type and never mutates it.
 *
 * <p><b>Identity model</b>:
 * <ul>
 *   <li>{@link #playerId} — canonical, lowercased display name.
 *   Stable wire identifier used as the {@code to_player_id} /
 *   {@code blocked_player_id} value on outbound cmds and as the
 *   panel's map key for outgoing-invite and block-list state.</li>
 *   <li>{@link #name} — the display-cased name the player shows up as
 *   in-game (e.g. {@code "Toyco"} when {@link #playerId} is
 *   {@code "toyco"}). What the UI renders.</li>
 * </ul>
 *
 * <p>Instances are immutable — every field is {@code final} and the
 * {@link #styles}/{@link #builds} sets are wrapped in
 * {@link Collections#unmodifiableSet}.
 */
public final class LobbyMember
{

    public final String playerId;
    public final String name;
    public final Set<Style> styles;
    public final Set<BuildType> builds;
    /** All-time peak rank index (0..24) for the viewer's sort bucket. The
     *  big rank label rendered on each card. **Display-only** — never
     *  used as a matchmaking gate. -1 means no peak has been computed
     *  yet (brand-new player with no completed matches in this bucket). */
    public final int peakRankIdx;
    public final String region;

    /** Backwards-compatible ctor — defaults the new slider-bound fields
     *  to {@link #UNKNOWN_RANK_IDX}. Kept so test fixtures, the
     *  self-preview builder, and the lookup-row constructors don't
     *  have to thread two more args through every call site. */
    public LobbyMember(String playerId, String name, Set<Style> styles, Set<BuildType> builds,
                       int peakRankIdx, String region)
    {
        this.playerId = playerId;
        this.name = name;
        this.styles = styles == null
            ? Collections.unmodifiableSet(EnumSet.noneOf(Style.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(styles));
        this.builds = builds == null
            ? Collections.unmodifiableSet(EnumSet.noneOf(BuildType.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(builds));
        this.peakRankIdx = peakRankIdx;
        this.region = region;
    }
}
