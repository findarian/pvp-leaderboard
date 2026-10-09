package com.pvp.leaderboard.tournament;

import java.util.*;
import javax.inject.*;

/** Sends {@code tournament/world} for the open match ({@link OppTracker}'s, which hears each push first): at the
 *  assignment, for each new world and after a reconnect's re-sync; never a 0 world or the same series and world twice
 *  in a row. EDT only. */
@Singleton
public final class WorldReport implements TournamentEventListener
{
    private final TourneySvc svc;
    private final OppTracker opp;
    private int world;
    private String sent;

    @Inject
    WorldReport(TourneySvc svc, OppTracker opp)
    {
        this.svc = svc;
        this.opp = opp;
    }

    /** The world the player is in, 0 when not in one. */
    public void onWorld(int world)
    {
        this.world = world;
        send();
    }

    @Override
    public void onMatchAssigned(MatchSeries series)
    {
        send();
    }

    @Override
    public void onTournamentState(List<Tourney> registrations, LiveTourney active)
    {
        send();
    }

    @Override
    public void onConnected()
    {
        sent = null;
    }

    private void send()
    {
        MatchSeries s = opp.getActiveSeries();
        String key = s == null || world <= 0 ? null : s.seriesId + ":" + world;
        if (key == null || key.equals(sent)) return;
        sent = key;
        svc.world(s.tournamentId, s.seriesId, world);
    }
}
