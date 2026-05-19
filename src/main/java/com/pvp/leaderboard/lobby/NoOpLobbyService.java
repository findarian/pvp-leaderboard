package com.pvp.leaderboard.lobby;

import java.util.Set;

/**
 * Fallback {@link LobbyService} used when {@link
 * com.pvp.leaderboard.ui.DashboardPanel} is constructed without an
 * injected service (e.g. in unit tests that don't wire matchmaking).
 *
 * <p>Every command is a silent no-op and no event is ever pushed back
 * to the registered {@link LobbyEventListener}, so the panel renders
 * an empty roster (no rows, no incoming-invite cards, 0 players online)
 * and every gate / fight / invite UI affordance does nothing when
 * clicked. Production wiring supplies {@code WebSocketLobbyService}
 * instead.
 */
public final class NoOpLobbyService implements LobbyService
{
    /** Cached listener so callers can still set + replace it without NPE;
     *  never invoked. */
    @SuppressWarnings("unused")
    private LobbyEventListener listener;

    @Override public void setListener(LobbyEventListener listener) { this.listener = listener; }
    @Override public void start() { /* nothing to start */ }
    @Override public void stop() { /* nothing to stop */ }

    @Override public void joinLobby(String region, Set<Style> styles, Set<BuildType> builds,
                                    int minDisplayRankIdx, int maxDisplayRankIdx,
                                    String sortBucket) { /* no-op */ }
    @Override public void leaveLobby() { /* no-op */ }
    @Override public void sendInvite(LobbyMember opponent, Style style, BuildType build, String location) { /* no-op */ }
    @Override public void cancelInvite(LobbyMember opponent) { /* no-op */ }
    @Override public void acceptInvite(IncomingInvite invite) { /* no-op */ }
    @Override public void declineInvite(IncomingInvite invite) { /* no-op */ }
    @Override public void confirmFight() { /* no-op */ }
    @Override public void block(LobbyMember member) { /* no-op */ }
    @Override public void unblock(LobbyMember member) { /* no-op */ }
}
