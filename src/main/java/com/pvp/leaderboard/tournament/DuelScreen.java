package com.pvp.leaderboard.tournament;

import lombok.*;

@AllArgsConstructor
public final class DuelScreen
{
    public final String opponentName;
    public final String selectedBuild;
    public final int ownPanel;
    public final GearKit ownKit;
}
