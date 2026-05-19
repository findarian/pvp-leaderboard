package com.pvp.leaderboard.lobby;

import java.util.Set;

/**
 * Placeholder {@link LobbyService} used while {@code WebSocketLobbyService}
 * (task {@code p2-plugin-service}) is unbuilt and the backend's
 * {@code wss://api.pvp-leaderboard.com/ws/prod} endpoint is still being
 * deployed (see {@code docs/BACKEND_HANDOFF_LOBBY.md}).
 *
 * <p>Every command is a silent no-op. No event is ever pushed back to the
 * registered {@link LobbyEventListener}, so the panel renders an empty
 * roster (no rows, no incoming-invite cards, 0 players online) and every
 * gate / fight / invite UI affordance does nothing when clicked.
 *
 * <p>The presence of this service rather than {@link DevLobbyFixture} in
 * the production wiring (see
 * {@link com.pvp.leaderboard.ui.DashboardPanel}) is intentional: shipping
 * a fake roster to real users in a beta jar would be misleading. The
 * fixture stays in the codebase but is only reachable from tests
 * ({@code DevLobbyFixtureTest}) until the real service lands.
 *
 * <p>Swap target: drop-in replace this class with
 * {@code WebSocketLobbyService} at the {@code DashboardPanel} call-site.
 * The {@link MatchmakingLobbyPanel} contract is identical — only the
 * service implementation changes.
 */
public final class NoOpLobbyService implements LobbyService
{
    /** Cached listener so callers can still set + replace it without NPE
     *  (mirrors {@link DevLobbyFixture}'s lifecycle); never invoked. */
    @SuppressWarnings("unused")
    private LobbyEventListener listener;

    @Override public void setListener(LobbyEventListener listener) { this.listener = listener; }
    @Override public void start() { /* nothing to start */ }
    @Override public void stop() { /* nothing to stop */ }

    @Override public void joinLobby(String region, Set<Style> styles, Set<BuildType> builds,
                                    int minDisplayRankIdx, int maxDisplayRankIdx) { /* no-op */ }
    @Override public void leaveLobby() { /* no-op */ }
    @Override public void sendInvite(LobbyMember opponent, Style style, BuildType build, String location) { /* no-op */ }
    @Override public void cancelInvite(LobbyMember opponent) { /* no-op */ }
    @Override public void acceptInvite(IncomingInvite invite) { /* no-op */ }
    @Override public void declineInvite(IncomingInvite invite) { /* no-op */ }
    @Override public void confirmFight() { /* no-op */ }
    @Override public void block(LobbyMember member) { /* no-op */ }
    @Override public void unblock(LobbyMember member) { /* no-op */ }
}
