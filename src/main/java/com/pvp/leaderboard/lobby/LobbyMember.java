package com.pvp.leaderboard.lobby;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

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
    /** Current rank index (0..24) for the viewer's sort bucket. Source of
     *  truth for matchmaking — drives the rank-slider filter and is what
     *  the server's RANK_OUT_OF_RANGE check reads on
     *  {@code lobby/invite}. -1 means the server returned no value. */
    public final int currentRankIdx;
    /** All-time peak rank index (0..24) for the viewer's sort bucket. The
     *  big rank label rendered on each card. **Display-only** — never
     *  used as a matchmaking gate. -1 means no peak has been computed
     *  yet (brand-new player with no completed matches in this bucket). */
    public final int peakRankIdx;
    public final String region;
    public final boolean isMod;

    public LobbyMember(String playerId, String name, Set<Style> styles, Set<BuildType> builds,
                       int currentRankIdx, int peakRankIdx, String region, boolean isMod)
    {
        this.playerId = playerId;
        this.name = name;
        this.styles = styles == null
            ? Collections.unmodifiableSet(EnumSet.noneOf(Style.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(styles));
        this.builds = builds == null
            ? Collections.unmodifiableSet(EnumSet.noneOf(BuildType.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(builds));
        this.currentRankIdx = currentRankIdx;
        this.peakRankIdx = peakRankIdx;
        this.region = region;
        this.isMod = isMod;
    }

    /** Compact style-flag string ("NVMD" if all four styles are set).
     *  Order follows {@link Style} declaration order. */
    public String styleFlags()
    {
        StringBuilder sb = new StringBuilder();
        for (Style s : Style.values())
        {
            if (styles.contains(s)) sb.append(s.label.charAt(0));
        }
        return sb.toString();
    }
}
