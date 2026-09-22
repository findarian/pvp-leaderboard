package com.pvp.leaderboard.tournament;

/** Inert {@link TournamentService}: no transport, no events; the
 *  Tournaments sub-tab stays greyed ({@link #isAvailable()} is false). */
public final class NoOpTournamentService implements TournamentService
{
    @Override public void addListener(TournamentEventListener listener) {}
    @Override public void removeListener(TournamentEventListener listener) {}
    @Override public void start() {}
    @Override public void list() {}
    @Override public void register(String tournamentId, String region) {}
    @Override public void withdraw(String tournamentId) {}
    @Override public void status() {}
    @Override public void subscribe(String tournamentId) {}
    @Override public void unsubscribe(String tournamentId) {}
    @Override public void inCombat(String tournamentId, String seriesId) {}
    @Override public void roundEndReply(String tournamentId, String seriesId) {}
    @Override public void reportProblem(String tournamentId, String text) {}
    @Override public boolean isAvailable() { return false; }
}
