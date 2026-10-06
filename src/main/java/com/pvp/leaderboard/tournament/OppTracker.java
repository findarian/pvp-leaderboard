package com.pvp.leaderboard.tournament;

import lombok.*;
import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import javax.inject.*;

@Singleton
public class OppTracker implements TournamentEventListener
{
    private volatile String activeTournamentId;
    @Getter private volatile MatchSeries activeSeries;
    /** The canonical keys of every name the outline matches; {@code null} while nothing is highlighted. The outline
     *  ends on the first hit on the opponent, the match's clear, the next round's assignment or bye, the end of the
     *  event, a state that is not running and a logout - never by time: a push's {@code until} / {@code deadline_at}
     *  is not applied. */
    private volatile Set<String> oppKeys;
    private volatile String foughtKey;
    private volatile String foughtId;

    @Inject
    public OppTracker()
    {
    }

    /** {@code true} while the player is in a RUNNING tournament (not merely registered for an upcoming one). */
    public boolean isPlaying()
    {
        return activeTournamentId != null;
    }

    /** The canonical keys of every name the outline matches, or {@code null} while there is nothing to outline. */
    public Set<String> getHighlightedOpponentKeys()
    {
        Set<String> keys = oppKeys;
        return keys == null || fought(keys) ? null : keys;
    }

    public boolean isAwaiting()
    {
        Set<String> wanted = oppKeys;
        return wanted != null && !fought(wanted);
    }

    private boolean fought(Set<String> keys)
    {
        String fought = foughtKey;
        return fought != null && keys != null && keys.contains(fought);
    }

    public void onCombatWith(String playerName, int localWorld)
    {
        Set<String> wanted = oppKeys;
        if (wanted == null || fought(wanted)) return;
        String key = NameUtils.canonicalKey(playerName);
        if (!wanted.contains(key)) return;
        MatchSeries s = activeSeries;
        if (!onAssignedWorld(s, localWorld)) return;
        foughtId = s == null ? null : s.seriesId;
        foughtKey = key;
    }

    private static boolean onAssignedWorld(MatchSeries s, int localWorld)
    {
        int assigned = s == null ? 0 : s.worldNumber();
        return assigned == 0 || assigned == localWorld;
    }

    public void clear()
    {
        activeTournamentId = null;
        activeSeries = null;
        oppKeys = null;
    }

    private void endSession()
    {
        clear();
        forgetCombat();
    }

    private void clearIf(String tournamentId)
    {
        String active = activeTournamentId;
        if (active == null) return;
        if (tournamentId.isEmpty() || tournamentId.equals(active)) endSession();
    }

    private static Set<String> keysOf(List<String> names)
    {
        Set<String> keys = new LinkedHashSet<>();
        for (String n : names)
        {
            String key = NameUtils.canonicalKey(n);
            if (!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    private void assign(String seriesId, Set<String> keys)
    {
        boolean otherPairing = isOtherPairing(seriesId, keys);
        oppKeys = keys.isEmpty() ? null : keys;
        if (otherPairing) forgetCombat();
        else if (foughtKey != null && foughtId == null && seriesId != null) foughtId = seriesId;
    }

    private boolean isOtherPairing(String seriesId, Set<String> opponentKeys)
    {
        String fought = foughtKey;
        if (fought == null) return false;
        String foughtSeries = foughtId;
        boolean otherSeries = foughtSeries != null && seriesId != null && !foughtSeries.equals(seriesId);
        boolean otherOpponent = !opponentKeys.isEmpty() && !opponentKeys.contains(fought);
        return otherSeries || otherOpponent;
    }

    private void forgetCombat()
    {
        foughtKey = null;
        foughtId = null;
    }

    // ---- listener ----
    @Override
    public void onTournamentState(List<Tourney> registrations, LiveTourney active)
    {
        if (active == null || !active.isRunning())
        {
            endSession();
            return;
        }
        activeTournamentId = active.tournamentId;
        MatchSeries s = active.series != null && active.series.isOpen() ? active.series : null;
        activeSeries = s;
        if (s == null) oppKeys = null;
        else assign(s.seriesId, keysOf(s.outlineNames()));
        if (active.bye) forgetCombat();
    }

    @Override
    public void onMatchAssigned(MatchSeries series)
    {
        activeTournamentId = series.tournamentId;
        activeSeries = series;
        assign(series.seriesId, keysOf(series.outlineNames()));
    }

    /** The push's {@code until} is not applied: the outline ends on the match's events, not by time. */
    @Override
    public void onOpponentHighlight(String tournamentId, String opponentName, List<String> opponentNames)
    {
        Set<String> keys = keysOf(opponentNames);
        if (keys.isEmpty()) keys = keysOf(Collections.singletonList(opponentName));
        if (keys.isEmpty()) return;
        if (!tournamentId.isEmpty()) activeTournamentId = tournamentId;
        assign(null, keys);
    }

    @Override
    public void onOpponentHighlightClear(String tournamentId, String seriesId)
    {
        oppKeys = null;
        MatchSeries s = activeSeries;
        if (s != null && (seriesId == null || seriesId.isEmpty() || seriesId.equals(s.seriesId))) activeSeries = null;
    }

    @Override
    public void onBye(String tournamentId, int round)
    {
        if (!tournamentId.isEmpty()) activeTournamentId = tournamentId;
        activeSeries = null;
        oppKeys = null;
        forgetCombat();
    }

    @Override
    public void onRemoved(String tournamentId, String status, String reason, int round)
    {
        clearIf(tournamentId);
    }

    @Override
    public void onCancelled(String tournamentId, String reason)
    {
        clearIf(tournamentId);
    }

    @Override
    public void onFinished(String tournamentId, JsonObject winners, List<StandingsRow> standings)
    {
        clearIf(tournamentId);
    }
}
