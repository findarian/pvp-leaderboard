package com.pvp.leaderboard.lobby;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One row in the lobby roster. Server is source of truth (see
 * {@code docs/SOCKET_LOBBY_ARCHITECTURE.md} → Phase 2: "Server is source of
 * truth; client only renders"); the panel only reads this type and never
 * mutates it.
 *
 * <p>Replaces {@code MatchmakingLobbyPanel.MockPlayer} as part of
 * {@code p1-plugin-mock-refactor}. Fields are intentionally
 * {@code public final} (matches the pre-refactor {@code MockPlayer} idiom)
 * so the panel's rendering code accesses {@code member.name},
 * {@code member.peakRankIdx} etc. directly without getter ceremony.
 *
 * <p><b>Architecture growth notes:</b>
 * <ul>
 *   <li>{@link #uuid} is the canonical identity. In mock mode it's derived
 *   from {@link #name} (deterministic so the same mock player always gets
 *   the same UUID across launches); in production it's the OSRS client UUID
 *   the server stamped at {@code $connect}.</li>
 *   <li>{@link #peakRankIdx} is the single-bucket peak that today's mock
 *   uses. The architecture spec calls for a {@code peakRankByBucket} map
 *   (see {@code LobbyMember{...peakRankByBucket:Map<RankBucket,DisplayRank>...}}).
 *   That migration lands with Phase 1.5 (shard schema bump) and Phase 2
 *   ({@code p2-plugin-service}). For now the single int matches the mock.</li>
 * </ul>
 *
 * <p>Instances are <b>immutable</b> — every field is {@code final} and the
 * {@link #styles}/{@link #builds} sets are wrapped in
 * {@link Collections#unmodifiableSet}.
 */
public final class LobbyMember
{
    public final String uuid;
    public final String name;
    public final Set<Style> styles;
    public final Set<BuildType> builds;
    public final int peakRankIdx;
    public final String region;
    public final boolean isMod;

    public LobbyMember(String uuid, String name, Set<Style> styles, Set<BuildType> builds,
                       int peakRankIdx, String region, boolean isMod)
    {
        this.uuid = uuid;
        this.name = name;
        this.styles = styles == null
            ? Collections.unmodifiableSet(EnumSet.noneOf(Style.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(styles));
        this.builds = builds == null
            ? Collections.unmodifiableSet(EnumSet.noneOf(BuildType.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(builds));
        this.peakRankIdx = peakRankIdx;
        this.region = region;
        this.isMod = isMod;
    }

    /** Compact style-flag string used by some legacy rendering paths
     *  ("NVMD" if all four styles are set). Order follows {@link Style}
     *  declaration order. */
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
