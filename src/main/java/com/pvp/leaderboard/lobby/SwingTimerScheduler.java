package com.pvp.leaderboard.lobby;

import javax.swing.Timer;

/**
 * Production-mock implementation of {@link MockScheduler} backed by
 * {@code javax.swing.Timer} so scheduled tasks fire on the EDT (the same
 * thread the panel listens on). Used when {@link DevLobbyFixture} runs
 * inside RuneLite during the pre-backend window.
 *
 * <p>Created as part of {@code p1-plugin-mock-refactor}.
 */
public final class SwingTimerScheduler implements MockScheduler
{
    @Override
    public Cancellable schedule(Runnable task, long delayMs)
    {
        if (task == null) return () -> {};
        long clamped = Math.max(0L, delayMs);
        if (clamped > Integer.MAX_VALUE) clamped = Integer.MAX_VALUE;
        final Timer t = new Timer((int) clamped, e -> task.run());
        t.setRepeats(false);
        t.start();
        return () ->
        {
            if (t.isRunning()) t.stop();
        };
    }
}
