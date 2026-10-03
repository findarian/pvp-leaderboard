package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.NameUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Singleton
public class TournamentSessionTracker implements TournamentEventListener
{
    private volatile String activeTournamentId;
    private volatile String activeTournamentName;
    private volatile TournamentSeries activeSeries;
    /** Every name the outline matches; {@code null} while nothing is highlighted. The outline ends on the first hit
     *  on the opponent, the match's clear, the next round's assignment or bye, the end of the event, a state that is
     *  not running and a logout - never by time: a push's {@code until} / {@code deadline_at} is not applied. */
    private volatile List<String> highlightNames;
    /** The canonical keys of {@link #highlightNames}. */
    private volatile Set<String> highlightKeys;
    private volatile String foughtKey;
    private volatile String foughtSeriesId;

    @Inject
    public TournamentSessionTracker()
    {
    }

    /** {@code true} while the player is in a RUNNING tournament (not merely registered for an upcoming one). */
    public boolean isActiveParticipant()
    {
        return activeTournamentId != null;
    }

    public String getActiveTournamentId()
    {
        return activeTournamentId;
    }

    public String getActiveTournamentName()
    {
        return activeTournamentName;
    }

    /** The open series (opponent, world, place) or {@code null} (bye / between rounds / not in a tournament). */
    public TournamentSeries getActiveSeries()
    {
        return activeSeries;
    }

    /** The first of {@link #getHighlightedOpponentNames()}, or {@code null}. */
    public String getHighlightedOpponentName()
    {
        List<String> names = getHighlightedOpponentNames();
        return names == null ? null : names.get(0);
    }

    /** Every name the outline matches, or {@code null} while there is nothing to outline. */
    public List<String> getHighlightedOpponentNames()
    {
        List<String> names = highlightNames;
        if (names == null) return null;
        if (fought(highlightKeys)) return null;
        return names;
    }

    public boolean isAwaitingCombat()
    {
        Set<String> wanted = highlightKeys;
        return wanted != null && !wanted.isEmpty() && !fought(wanted);
    }

    private boolean fought(Set<String> keys)
    {
        String fought = foughtKey;
        return fought != null && keys != null && keys.contains(fought);
    }

    public void onCombatWith(String playerName, int localWorld)
    {
        Set<String> wanted = highlightKeys;
        if (wanted == null || wanted.isEmpty() || fought(wanted)) return;
        String key = playerName == null ? "" : NameUtils.canonicalKey(playerName);
        if (!wanted.contains(key)) return;
        TournamentSeries s = activeSeries;
        if (!onAssignedWorld(s, localWorld)) return;
        foughtSeriesId = s == null ? null : s.seriesId;
        foughtKey = key;
    }

    private static boolean onAssignedWorld(TournamentSeries s, int localWorld)
    {
        int assigned = s == null ? 0 : s.worldNumber();
        return assigned == 0 || assigned == localWorld;
    }

    public void clear()
    {
        activeTournamentId = null;
        activeTournamentName = null;
        activeSeries = null;
        setHighlight(null, null);
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
        if (tournamentId == null || tournamentId.isEmpty() || tournamentId.equals(active)) endSession();
    }

    private void setHighlight(List<String> names, Set<String> keys)
    {
        highlightNames = null;
        highlightKeys = keys;
        highlightNames = names;
    }

    /** The usable names of a list: trimmed, non-blank, with a canonical key, each once. */
    private static List<String> cleaned(List<String> names)
    {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (names == null) return out;
        for (String n : names)
        {
            if (n == null) continue;
            String key = NameUtils.canonicalKey(n);
            if (key.isEmpty() || !seen.add(key)) continue;
            out.add(n);
        }
        return out;
    }

    private static Set<String> keysOf(List<String> names)
    {
        Set<String> keys = new LinkedHashSet<>();
        for (String n : names) keys.add(NameUtils.canonicalKey(n));
        return Collections.unmodifiableSet(keys);
    }

    private void assign(String seriesId, List<String> opponentNames)
    {
        List<String> names = cleaned(opponentNames);
        Set<String> keys = keysOf(names);
        boolean otherPairing = isOtherPairing(seriesId, keys);
        if (names.isEmpty()) setHighlight(null, null);
        else setHighlight(Collections.unmodifiableList(names), keys);
        if (otherPairing) forgetCombat();
        else if (foughtKey != null && foughtSeriesId == null && seriesId != null) foughtSeriesId = seriesId;
    }

    private boolean isOtherPairing(String seriesId, Set<String> opponentKeys)
    {
        String fought = foughtKey;
        if (fought == null) return false;
        String foughtSeries = foughtSeriesId;
        boolean otherSeries = foughtSeries != null && seriesId != null && !foughtSeries.equals(seriesId);
        boolean otherOpponent = !opponentKeys.isEmpty() && !opponentKeys.contains(fought);
        return otherSeries || otherOpponent;
    }

    private void forgetCombat()
    {
        foughtKey = null;
        foughtSeriesId = null;
    }

    // ---- listener ----
    @Override
    public void onTournamentState(List<TournamentSummary> registrations, TournamentActive active)
    {
        if (active == null || !active.isRunning())
        {
            endSession();
            return;
        }
        activeTournamentId = active.tournamentId;
        activeTournamentName = active.name;
        TournamentSeries s = active.series != null && active.series.isOpen() ? active.series : null;
        activeSeries = s;
        if (s == null) setHighlight(null, null);
        else assign(s.seriesId, s.outlineNames());
        if (active.bye) forgetCombat();
    }

    @Override
    public void onMatchAssigned(TournamentSeries series)
    {
        if (series == null) return;
        activeTournamentId = series.tournamentId;
        activeSeries = series;
        assign(series.seriesId, series.outlineNames());
    }

    @Override
    public void onOpponentHighlight(String tournamentId, String opponentName, String opponentAcctSha, long untilEpochS)
    {
        onOpponentHighlight(tournamentId, opponentName, opponentAcctSha, untilEpochS, null);
    }

    /** {@code untilEpochS} is read and not applied: the outline ends on the match's events, not by time. */
    @Override
    public void onOpponentHighlight(String tournamentId, String opponentName, String opponentAcctSha, long untilEpochS, List<String> opponentNames)
    {
        List<String> names = cleaned(opponentNames);
        if (names.isEmpty()) names = cleaned(Collections.singletonList(opponentName));
        if (names.isEmpty()) return;
        if (tournamentId != null && !tournamentId.isEmpty()) activeTournamentId = tournamentId;
        assign(null, names);
    }

    @Override
    public void onOpponentHighlightClear(String tournamentId, String seriesId)
    {
        setHighlight(null, null);
        TournamentSeries s = activeSeries;
        if (s != null && (seriesId == null || seriesId.isEmpty() || seriesId.equals(s.seriesId))) activeSeries = null;
    }

    @Override
    public void onBye(String tournamentId, int round)
    {
        if (tournamentId != null && !tournamentId.isEmpty()) activeTournamentId = tournamentId;
        activeSeries = null;
        setHighlight(null, null);
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
