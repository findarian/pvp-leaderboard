package com.pvp.leaderboard.queue;

import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.Style;

/** Inert {@link QueueService}: no transport, no events, the gate hides
 *  the queue button ({@link #isAvailable()} is {@code false}). */
public final class NoOpQueueService implements QueueService
{
    @Override public void setListener(QueueEventListener listener) {}
    @Override public void start() {}
    @Override public void join(String region, Style style, BuildType build, int minRankIdx, int maxRankIdx, int waitPrefS) {}
    @Override public void leave() {}
    @Override public void expandRange() {}
    @Override public void requestStatus() {}
    @Override public boolean isAvailable() { return false; }
}
