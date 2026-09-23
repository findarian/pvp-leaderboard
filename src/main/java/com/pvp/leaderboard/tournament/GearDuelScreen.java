package com.pvp.leaderboard.tournament;

public final class GearDuelScreen
{
    public final String opponentName;
    public final String selectedBuild;
    public final int ownPanel;
    public final GearKit ownKit;

    public GearDuelScreen(String opponentName, String selectedBuild, int ownPanel, GearKit ownKit)
    {
        this.opponentName = opponentName;
        this.selectedBuild = selectedBuild;
        this.ownPanel = ownPanel;
        this.ownKit = ownKit;
    }
}
