package com.pvp.leaderboard.util;

/**
 * Watches a path that is supposed to be fast and produces a log line
 * only when it isn't.
 *
 * <p>Used on the three places where this plugin can stall the client:
 * an overlay frame, a roster re-render on the Swing EDT, and a
 * WebSocket frame handler on OkHttp's read thread. All three run tens
 * of times a second, so the monitor is deliberately silent while things
 * are healthy — a routine log line on any of them would itself become
 * the cost it's meant to measure.
 *
 * <p>When a path does stall it usually stalls repeatedly, so reports
 * are rate-limited and aggregated: one line per {@code logIntervalMs}
 * carrying how many times the budget was blown and the worst case seen.
 * That way a sustained problem reads as a single "47 times, worst
 * 210ms" line rather than 47 separate ones.
 *
 * <p>Not thread-safe. Each instance belongs to one path, and each of
 * those paths is single-threaded (the render thread, the EDT, the
 * socket read loop respectively). A lost count under a race would not
 * change the conclusion anyway.
 */
public final class SlowPathMonitor
{
    private final String label;
    private final long thresholdMs;
    private final long logIntervalMs;

    /** Stalls since the last report, and the worst of them. */
    private int slowCount;
    private long worstMs;
    /** Wall clock of the last emitted report; 0 until the first one, so
     *  the first stall of a session is never withheld. */
    private long lastReportMs;

    /**
     * @param label       what stalled, as it should read in the log
     * @param thresholdMs a duration at or above this counts as a stall
     * @param logIntervalMs minimum gap between reports
     */
    public SlowPathMonitor(String label, long thresholdMs, long logIntervalMs)
    {
        this.label = label;
        this.thresholdMs = thresholdMs;
        this.logIntervalMs = logIntervalMs;
    }

    /**
     * Records one execution.
     *
     * @param durationMs how long it took
     * @param nowMs      current wall clock, for rate limiting
     * @return a line to log, or {@code null} when there's nothing to
     *         say — which is the overwhelmingly common case
     */
    public String record(long durationMs, long nowMs)
    {
        if (durationMs < thresholdMs)
        {
            return null;
        }
        slowCount++;
        if (durationMs > worstMs) worstMs = durationMs;

        long sinceLast = nowMs - lastReportMs;
        // A backwards clock jump (NTP correction, sleep/wake) makes
        // sinceLast negative, which would suppress until the clock
        // catches up. Re-anchor instead so the monitor can't be wedged
        // silent by something unrelated to what it's measuring.
        if (sinceLast < 0)
        {
            lastReportMs = nowMs;
            return null;
        }
        if (lastReportMs != 0L && sinceLast < logIntervalMs)
        {
            return null;
        }

        String report = (slowCount == 1)
            ? label + " took " + durationMs + "ms (budget " + thresholdMs + "ms)"
            : label + " was slow " + slowCount + " times in the last "
                + (logIntervalMs / 1000L) + "s, worst " + worstMs + "ms (budget "
                + thresholdMs + "ms)";

        lastReportMs = nowMs;
        slowCount = 0;
        worstMs = 0L;
        return report;
    }

    /** Elapsed whole milliseconds between two {@link System#nanoTime}
     *  readings. Nanos because wall-clock millis are too coarse to
     *  measure a frame and can step sideways mid-measurement. */
    public static long millisSince(long startNanos, long endNanos)
    {
        return (endNanos - startNanos) / 1_000_000L;
    }
}
