package com.pvp.leaderboard.lobby;

/**
 * Tiny scheduling abstraction used by {@link DevLobbyFixture} for its
 * mock-auto-accept (5 s) and mock-peer-confirm (6 s) timers. Two known
 * implementations:
 * <ul>
 *   <li>{@link SwingTimerScheduler} — wraps {@code javax.swing.Timer};
 *   tasks fire on the EDT. Used in production-mock-mode (i.e. running
 *   inside RuneLite during the pre-backend window).</li>
 *   <li>{@code ManualScheduler} — test-only, advances simulated time
 *   synchronously. Lives in {@code src/test/java}.</li>
 * </ul>
 *
 * <p>Created as part of {@code p1-plugin-mock-refactor} so the fixture's
 * delay-based behavior is unit-testable without spinning up a real Swing
 * event-dispatch thread.
 */
public interface MockScheduler
{
    /** Schedules {@code task} to run once after {@code delayMs}. Returns a
     *  handle the caller can use to cancel the pending task if it hasn't
     *  fired yet (the timer is gone after firing, and {@link Cancellable#cancel}
     *  becomes a no-op). */
    Cancellable schedule(Runnable task, long delayMs);

    interface Cancellable
    {
        void cancel();
    }
}
