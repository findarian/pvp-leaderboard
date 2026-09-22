package com.pvp.leaderboard.util;

import java.util.Map;
import java.util.TreeMap;

/**
 * Tallies outbound HTTP requests per endpoint and emits one summary
 * line per window.
 *
 * <p><b>Why.</b> The 2026-08-01 fixes are all about request <em>rate</em>
 * — an unresolved lobby row that used to re-fetch on every roster push,
 * a join gate that re-fetched every minute forever, a login init that
 * re-ran on every map region load. Nothing in the logs reported rate
 * directly; you could infer it by counting per-request lines until
 * those were moved to TRACE. This makes the thing being fixed
 * measurable.
 *
 * <p>A summary rather than a line per request, for the same reason
 * {@link SlowPathMonitor} stays quiet: logging every call on a path
 * we're trying to prove is quiet would be its own kind of noise.
 *
 * <p><b>DIAGNOSTIC — safe to remove.</b> Tracked in
 * {@code PERF_CHANGES.txt} Part A. Nothing depends on it behaviourally.
 *
 * <p><b>Reporting is request-driven</b>, not timer-driven: a window is
 * reported by the first request that arrives after it closes. A client
 * that goes completely idle therefore won't emit its final window until
 * it makes another request, which is fine — a window with no traffic is
 * the state we're hoping for and reports nothing anyway.
 *
 * <p>Thread-safe: the counted call sites run on OkHttp dispatcher
 * threads, the client thread and the EDT.
 */
public final class HttpVolumeMonitor
{
    private final long windowMs;

    /** Per-endpoint counts for the open window. {@link TreeMap} so the
     *  rendered order is stable and two windows can be compared by eye. */
    private final Map<String, Integer> counts = new TreeMap<>();

    /** Wall clock the open window started. Paired with
     *  {@link #windowOpen} rather than using 0 as a "not started"
     *  sentinel — 0 is a legitimate timestamp, and conflating the two
     *  makes the first window behave differently depending on the
     *  clock's origin. */
    private long windowStartMs;
    private boolean windowOpen;

    /** @param windowMs how much traffic one summary line covers */
    public HttpVolumeMonitor(long windowMs)
    {
        this.windowMs = windowMs;
    }

    /**
     * Records one outbound request.
     *
     * @param endpoint grouping label, normally from {@link #endpointLabel}
     * @param nowMs    current wall clock
     * @return a summary of the window that just closed, or {@code null}
     *         when the window is still open (the common case)
     */
    public synchronized String record(String endpoint, long nowMs)
    {
        if (endpoint == null || endpoint.trim().isEmpty())
        {
            return null;
        }
        if (!windowOpen)
        {
            windowOpen = true;
            windowStartMs = nowMs;
            bump(endpoint);
            return null;
        }

        long elapsed = nowMs - windowStartMs;
        // A backwards clock jump would otherwise hold the window open
        // until the clock caught up, silencing the monitor for reasons
        // unrelated to traffic. Re-anchor and carry on.
        if (elapsed < 0)
        {
            windowStartMs = nowMs;
            bump(endpoint);
            return null;
        }
        if (elapsed < windowMs)
        {
            bump(endpoint);
            return null;
        }

        String report = render(elapsed);
        counts.clear();
        windowStartMs = nowMs;
        // The request that closed the window belongs to the new one, so
        // it isn't dropped from the tally.
        bump(endpoint);
        return report;
    }

    private void bump(String endpoint)
    {
        counts.merge(endpoint, 1, Integer::sum);
    }

    private String render(long elapsedMs)
    {
        if (counts.isEmpty())
        {
            return null;
        }
        int total = 0;
        StringBuilder sb = new StringBuilder("[http-volume] last ")
            .append(elapsedMs / 1000L)
            .append("s:");
        for (Map.Entry<String, Integer> e : counts.entrySet())
        {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            total += e.getValue();
        }
        return sb.append(" (total ").append(total).append(')').toString();
    }

    /**
     * Reduces a request URL to the endpoint it belongs to, so
     * per-player URLs collapse into one counter instead of thousands.
     *
     * @return the leading path segment (e.g. {@code /rank_idx}), or a
     *         placeholder for input that isn't a usable URL — this runs
     *         inside a request path and must never throw
     */
    public static String endpointLabel(String url)
    {
        if (url == null || url.trim().isEmpty())
        {
            return "unknown";
        }
        try
        {
            int schemeEnd = url.indexOf("://");
            int hostStart = (schemeEnd >= 0) ? schemeEnd + 3 : 0;
            int pathStart = url.indexOf('/', hostStart);
            if (pathStart < 0) return "unknown";
            int queryStart = url.indexOf('?', pathStart);
            String path = (queryStart >= 0)
                ? url.substring(pathStart, queryStart)
                : url.substring(pathStart);
            // Strip the API's stage prefix (".../prod/user") so the API
            // and CDN labels read the same way.
            if (path.startsWith("/prod/")) path = path.substring(5);
            int second = path.indexOf('/', 1);
            String label = (second > 0) ? path.substring(0, second) : path;
            return label.isEmpty() ? "unknown" : label;
        }
        catch (RuntimeException e)
        {
            return "unknown";
        }
    }
}
