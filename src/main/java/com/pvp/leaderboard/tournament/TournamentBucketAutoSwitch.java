package com.pvp.leaderboard.tournament;

import java.util.function.BooleanSupplier;

/**
 * Plan 10 F.2 (2026-09-21): the listener that flips the side panel +
 * overlay to the <b>Tournament</b> bucket the moment a round opens with an
 * opponent ({@code tournament/match_assigned}), while the
 * {@code autoSwitchTournamentBucket} config allows it. Nothing else
 * switches: the way back is the next ordinary fight, through
 * {@code FightMonitor}'s auto-switch like every other bucket (AS-72), and
 * the {@link TournamentSessionTracker} pin keeps tournament games from
 * flipping the panel to NH in between. The plugin supplies the config
 * read and the config write.
 */
public final class TournamentBucketAutoSwitch implements TournamentEventListener
{
    private final BooleanSupplier enabled;
    private final Runnable switchToTournament;

    public TournamentBucketAutoSwitch(BooleanSupplier enabled, Runnable switchToTournament)
    {
        this.enabled = enabled == null ? () -> false : enabled;
        this.switchToTournament = switchToTournament == null ? () -> {} : switchToTournament;
    }

    @Override
    public void onMatchAssigned(TournamentSeries series)
    {
        if (series == null) return;
        if (!enabled.getAsBoolean()) return;
        switchToTournament.run();
    }
}
