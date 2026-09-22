package com.pvp.leaderboard.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The backend's Overall rating, mirrored for the tier graph's locally
 * computed "Overall" history (parity gap G-16, operator decision
 * 2026-09-22). Keep in step with {@code backend/core/buckets.py}
 * ({@code STANDARD_WEIGHTS}, {@code TOURNAMENT_WEIGHT},
 * {@code with_tournament}, {@code missing_overrides}) and
 * {@code backend/core/mmr.compute_overall_with_tournament} — the same
 * obligation {@link RankUtils} carries for the rank thresholds.
 *
 * <p>The formula is a plain weighted SUM, never normalised: the four
 * standard buckets at {@code nh 0.55 / veng 0.30 / multi 0.05 / dmm 0.10}
 * (a bucket with no rating counts as {@link #DEFAULT_MU}), plus
 * {@code 0.10 × tournament} <b>on top</b> of that 100 %. The tournament
 * term exists only once the player has a tournament match — on the
 * backend a sign-up seed alone contributes nothing, and locally there is
 * no seed at all, only match points — so a history with no tournament
 * game keeps exactly the legacy Overall.
 *
 * <p>Two uses: the pure {@link #overall(Map)} over a map of last-known
 * mus, and an accumulator ({@link #record}, {@link #overall()}) that walks
 * a match history in order.
 */
public final class OverallMmr
{
    public static final String TOURNAMENT_BUCKET = "tournament";
    public static final double DEFAULT_MU = 1000.0;
    public static final double TOURNAMENT_WEIGHT = 0.10;

    private static final Map<String, Double> STANDARD_WEIGHTS;

    static
    {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("nh", 0.55);
        w.put("veng", 0.30);
        w.put("multi", 0.05);
        w.put("dmm", 0.10);
        STANDARD_WEIGHTS = Collections.unmodifiableMap(w);
    }

    private final Map<String, Double> lastMu = new HashMap<>();

    /** {@code nh 0.55 / veng 0.30 / multi 0.05 / dmm 0.10}, in that order. */
    public static Map<String, Double> standardWeights()
    {
        return STANDARD_WEIGHTS;
    }

    /** The five buckets a match can be rated in (case-insensitive). */
    public static boolean isRated(String bucket)
    {
        String b = key(bucket);
        return b != null && (STANDARD_WEIGHTS.containsKey(b) || TOURNAMENT_BUCKET.equals(b));
    }

    /** The Overall for a map of last-known mus keyed by bucket: a missing
     *  or {@code null} standard bucket counts as 1000, a missing or
     *  {@code null} tournament bucket contributes nothing. */
    public static double overall(Map<String, Double> mus)
    {
        double sum = 0.0;
        for (Map.Entry<String, Double> e : STANDARD_WEIGHTS.entrySet())
        {
            Double mu = mus == null ? null : mus.get(e.getKey());
            sum += (mu == null ? DEFAULT_MU : mu) * e.getValue();
        }
        Double tournament = mus == null ? null : mus.get(TOURNAMENT_BUCKET);
        if (tournament != null) sum += tournament * TOURNAMENT_WEIGHT;
        return sum;
    }

    /** The last recorded mu for the bucket, {@code null} when none was —
     *  a standard bucket then counts as 1000, the tournament bucket as
     *  nothing. */
    public Double lastMu(String bucket)
    {
        String b = key(bucket);
        return b == null ? null : lastMu.get(b);
    }

    /** Records a match's post-game mu; {@code false} (and untouched) for a
     *  bucket the formula does not know or a non-finite value. */
    public boolean record(String bucket, double mu)
    {
        String b = key(bucket);
        if (b == null || !isRated(b) || !Double.isFinite(mu)) return false;
        lastMu.put(b, mu);
        return true;
    }

    /** {@link #overall(Map)} over everything recorded so far. */
    public double overall()
    {
        return overall(lastMu);
    }

    private static String key(String bucket)
    {
        if (bucket == null) return null;
        String b = bucket.trim().toLowerCase(Locale.ROOT);
        return b.isEmpty() ? null : b;
    }
}
