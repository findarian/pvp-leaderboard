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
    /** Sentinel value used by both {@link #minRankIdx} and
     *  {@link #maxRankIdx} when the server has not (yet) pushed the
     *  member's slider settings. Callers must treat either field
     *  equalling this value as "range unknown" and skip the
     *  out-of-their-range greyout — never as "accepts no-one" (which
     *  would incorrectly grey every row on every pre-deploy
     *  build of the backend). */
    public static final int UNKNOWN_RANK_IDX = -1;

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
    /** Lower bound of this member's own accept-invite slider (index into
     *  {@code RANK_LABELS}, inclusive). The plugin compares the
     *  <b>viewer's</b> own rank against {@code [minRankIdx, maxRankIdx]}
     *  to decide whether to grey their row out — when the viewer is
     *  outside this band the member would server-reject any
     *  {@code lobby/invite} with {@code RANK_OUT_OF_RANGE}, so showing
     *  a live [Fight] chip would be misleading.
     *
     *  <p>{@link #UNKNOWN_RANK_IDX} (-1) when the server did not push
     *  the field (pre-deploy build of the lobby backend, or a partial
     *  roster row); the greyout logic interprets that as "range
     *  unknown — show the row normally" so missing data never causes
     *  every row to grey out. */
    public final int minRankIdx;
    /** Upper bound of this member's own accept-invite slider — see
     *  {@link #minRankIdx} for semantics. {@link #UNKNOWN_RANK_IDX}
     *  (-1) when the server did not push the field. */
    public final int maxRankIdx;

    /** Backwards-compatible ctor — defaults the new slider-bound fields
     *  to {@link #UNKNOWN_RANK_IDX}. Kept so test fixtures, the
     *  self-preview builder, and the lookup-row constructors don't
     *  have to thread two more args through every call site. */
    public LobbyMember(String playerId, String name, Set<Style> styles, Set<BuildType> builds,
                       int currentRankIdx, int peakRankIdx, String region, boolean isMod)
    {
        this(playerId, name, styles, builds, currentRankIdx, peakRankIdx, region, isMod,
            UNKNOWN_RANK_IDX, UNKNOWN_RANK_IDX);
    }

    public LobbyMember(String playerId, String name, Set<Style> styles, Set<BuildType> builds,
                       int currentRankIdx, int peakRankIdx, String region, boolean isMod,
                       int minRankIdx, int maxRankIdx)
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
        this.minRankIdx = minRankIdx;
        this.maxRankIdx = maxRankIdx;
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
