package com.pvp.leaderboard.tournament;

import com.google.gson.JsonObject;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Pure, thread-safe memory of the local player's tournament participation
 * (Plan 10 F.2 / F.4, 2026-09-21), fed by the {@link TournamentEventListener}
 * pushes and read by:
 * <ul>
 *   <li>the bucket auto-switch ({@link #isActiveParticipant()} pins the
 *       Tournament bucket while the player is in a running tournament,
 *       AS-72: the switch back happens at the next fight like every bucket);</li>
 *   <li>the opponent outline overlay ({@link #getHighlightedOpponentName()},
 *       driven only by {@code tournament/opponent_highlight} / the assigned
 *       series, cleared by {@code _clear}, removal, cancel, finish, or the
 *       {@code until} deadline);</li>
 *   <li>the in-combat signal ({@link #getActiveSeries()}).</li>
 * </ul>
 * Fields are volatile: the listener writes on the EDT, the overlay reads
 * on the render thread.
 */
@Singleton
public class TournamentSessionTracker implements TournamentEventListener
{
    private final LongSupplier nowEpochMs;
    private volatile String activeTournamentId;
    private volatile String activeTournamentName;
    private volatile TournamentSeries activeSeries;
    private volatile String highlightName;
    private volatile long highlightUntilEpochS;

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

    /** The opponent to outline in yellow right now, or {@code null}. */
    public String getHighlightedOpponentName()
    {
        String name = highlightName;
        if (name == null) return null;
        long until = highlightUntilEpochS;
        if (until > 0 && nowEpochMs.getAsLong() / 1000L >= until) return null;
        return name;
    }

    public void clear()
    {
        activeTournamentId = null;
        activeTournamentName = null;
        activeSeries = null;
        highlightName = null;
        highlightUntilEpochS = 0L;
    }

    private void clearIf(String tournamentId)
    {
        String active = activeTournamentId;
        if (active == null) return;
        if (tournamentId == null || tournamentId.isEmpty() || tournamentId.equals(active)) clear();
    }

    // ---- listener ----
    @Override
    public void onTournamentState(List<TournamentSummary> registrations, TournamentActive active)
    {
        if (active == null || !active.isRunning())
        {
            clear();
            return;
        }
        activeTournamentId = active.tournamentId;
        activeTournamentName = active.name;
        TournamentSeries s = active.series != null && active.series.isOpen() ? active.series : null;
        activeSeries = s;
        if (s != null && s.opponentName != null)
        {
            highlightName = s.opponentName;
            highlightUntilEpochS = s.deadlineAt;
        }
        else
        {
            highlightName = null;
            highlightUntilEpochS = 0L;
        }
    }

    @Override
    public void onMatchAssigned(TournamentSeries series)
    {
        if (series == null) return;
        activeTournamentId = series.tournamentId;
        activeSeries = series;
        highlightName = series.opponentName;
        highlightUntilEpochS = series.deadlineAt;
    }

    @Override
    public void onOpponentHighlight(String tournamentId, String opponentName, String opponentAcctSha, long untilEpochS)
    {
        if (opponentName == null || opponentName.isEmpty()) return;
        if (tournamentId != null && !tournamentId.isEmpty()) activeTournamentId = tournamentId;
        highlightName = opponentName;
        highlightUntilEpochS = untilEpochS;
    }

    @Override
    public void onOpponentHighlightClear(String tournamentId, String seriesId)
    {
        highlightName = null;
        highlightUntilEpochS = 0L;
        TournamentSeries s = activeSeries;
        if (s != null && (seriesId == null || seriesId.isEmpty() || seriesId.equals(s.seriesId))) activeSeries = null;
    }

    @Override
    public void onBye(String tournamentId, int round)
    {
        if (tournamentId != null && !tournamentId.isEmpty()) activeTournamentId = tournamentId;
        activeSeries = null;
        highlightName = null;
        highlightUntilEpochS = 0L;
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
