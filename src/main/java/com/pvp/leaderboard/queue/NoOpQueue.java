package com.pvp.leaderboard.queue;

/** Inert {@link QueueService}: no transport, no events, the gate hides
 *  the queue button ({@link #isAvailable()} is {@code false}). */
public final class NoOpQueue implements QueueService
{
    @Override public boolean isAvailable() { return false; }
}
