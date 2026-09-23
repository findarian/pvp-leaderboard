package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.NameUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.function.LongSupplier;

@Singleton
public class TournamentSessionTracker implements TournamentEventListener
{
    private final LongSupplier nowEpochMs;
    private volatile String activeTournamentId;
    private volatile String activeTournamentName;
    private volatile TournamentSeries activeSeries;
    private volatile String highlightName;
    private volatile String highlightKey;
    private volatile long highlightUntilEpochS;
    private volatile String foughtKey;
    private volatile String foughtSeriesId;

    @Inject
    public TournamentSessionTracker()
    {
        this(System::currentTimeMillis);
    }

    public TournamentSessionTracker(LongSupplier nowEpochMs)
    {
        this.nowEpochMs = nowEpochMs;
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

    public String getHighlightedOpponentName()
    {
        String name = highlightName;
        if (name == null) return null;
        long until = highlightUntilEpochS;
        if (until > 0 && nowEpochMs.getAsLong() / 1000L >= until) return null;
        String fought = foughtKey;
        if (fought != null && fought.equals(highlightKey)) return null;
        return name;
    }

    public boolean isAwaitingCombat()
    {
        String wanted = highlightKey;
        return wanted != null && !wanted.equals(foughtKey);
    }

    public void onCombatWith(String playerName, int localWorld)
    {
        String wanted = highlightKey;
        if (wanted == null || wanted.equals(foughtKey)) return;
        if (playerName == null || !wanted.equals(NameUtils.canonicalKey(playerName))) return;
        TournamentSeries s = activeSeries;
        if (!onAssignedWorld(s, localWorld)) return;
        foughtSeriesId = s == null ? null : s.seriesId;
        foughtKey = wanted;
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
        setHighlight(null, null, 0L);
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

    private void setHighlight(String name, String key, long untilEpochS)
    {
        highlightName = null;
        highlightUntilEpochS = untilEpochS;
        highlightKey = key;
        highlightName = name;
    }

    private void assign(String seriesId, String opponentName, long untilEpochS)
    {
        String key = opponentName == null ? "" : NameUtils.canonicalKey(opponentName);
        boolean otherPairing = isOtherPairing(seriesId, key.isEmpty() ? null : key);
        if (key.isEmpty()) setHighlight(null, null, 0L);
        else setHighlight(opponentName, key, untilEpochS);
        if (otherPairing) forgetCombat();
        else if (foughtKey != null && foughtSeriesId == null && seriesId != null) foughtSeriesId = seriesId;
    }

    private boolean isOtherPairing(String seriesId, String opponentKey)
    {
        String fought = foughtKey;
        if (fought == null) return false;
        String foughtSeries = foughtSeriesId;
        boolean otherSeries = foughtSeries != null && seriesId != null && !foughtSeries.equals(seriesId);
        boolean otherOpponent = opponentKey != null && !fought.equals(opponentKey);
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
        if (s == null) setHighlight(null, null, 0L);
        else assign(s.seriesId, s.hasNamedOpponent() ? s.opponentName : null, s.deadlineAt);
        if (active.bye) forgetCombat();
    }

    @Override
    public void onMatchAssigned(TournamentSeries series)
    {
        if (series == null) return;
        activeTournamentId = series.tournamentId;
        activeSeries = series;
        assign(series.seriesId, series.hasNamedOpponent() ? series.opponentName : null, series.deadlineAt);
    }

    @Override
    public void onOpponentHighlight(String tournamentId, String opponentName, String opponentAcctSha, long untilEpochS)
    {
        if (opponentName == null || NameUtils.canonicalKey(opponentName).isEmpty()) return;
        if (tournamentId != null && !tournamentId.isEmpty()) activeTournamentId = tournamentId;
        assign(null, opponentName, untilEpochS);
    }

    @Override
    public void onOpponentHighlightClear(String tournamentId, String seriesId)
    {
        setHighlight(null, null, 0L);
        TournamentSeries s = activeSeries;
        if (s != null && (seriesId == null || seriesId.isEmpty() || seriesId.equals(s.seriesId))) activeSeries = null;
    }

    @Override
    public void onBye(String tournamentId, int round)
    {
        if (tournamentId != null && !tournamentId.isEmpty()) activeTournamentId = tournamentId;
        activeSeries = null;
        setHighlight(null, null, 0L);
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
