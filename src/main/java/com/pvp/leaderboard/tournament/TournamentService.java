package com.pvp.leaderboard.tournament;

/**
 * The seam between the Tournaments sub-tab (+ the session tracker and the
 * opponent overlay) and the tournament transport (Plan 10 Part C / F.3).
 * Fire-and-forget: outcomes arrive through the registered
 * {@link TournamentEventListener}s. {@link WebSocketTournamentService} in
 * production, {@link NoOpTournamentService} when tournaments are off (the
 * sub-tab then stays greyed).
 */
public interface TournamentService
{
    /** Longest {@code tournament/report_problem} text the server accepts. */
    int REPORT_MAX_CHARS = 280;

    void addListener(TournamentEventListener listener);

    void removeListener(TournamentEventListener listener);

    void start();

    /** {@code tournament/list}. */
    void list();

    /** {@code tournament/register} ({@code region} optional — the world resolver input). */
    void register(String tournamentId, String region);

    /** {@code tournament/withdraw}. */
    void withdraw(String tournamentId);

    /** {@code tournament/status} — registrations + the active tournament. */
    void status();

    /** {@code tournament/subscribe} — live standings while the leaderboard is visible. */
    void subscribe(String tournamentId);

    /** {@code tournament/unsubscribe}. */
    void unsubscribe(String tournamentId);

    /** {@code tournament/in_combat} — the round-clock extension signal. */
    void inCombat(String tournamentId, String seriesId);

    /** {@code tournament/round_end_reply} — "I'm here" at the 30 s check. */
    void roundEndReply(String tournamentId, String seriesId);

    /** {@code tournament/report_problem} (text truncated to {@link #REPORT_MAX_CHARS}). */
    void reportProblem(String tournamentId, String text);

    /** {@code true} for a real transport (the sub-tab is enabled). */
    default boolean isAvailable() { return true; }

    /** {@code true} while the socket is open (set 7): the sub-tab waits
     *  with "Connecting…" instead of sending frames the manager would drop,
     *  and re-asks from {@link TournamentEventListener#onSocketConnected()}.
     *  The inert service says {@code true} — nothing to wait for. */
    default boolean isConnected() { return true; }
}
