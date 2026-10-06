package com.pvp.leaderboard.util;

import java.util.*;

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
 * <p>An accumulator ({@link #record}, {@link #overall()}) that walks a
 * match history in order.
 */
public final class OverallMmr
{
    public static final String TOURNEY_KEY = "tournament";
    public static final double DEFAULT_MU = 1000.0;
    public static final double TOURNAMENT_WEIGHT = 0.10;

    /** {@code nh 0.55 / veng 0.30 / multi 0.05 / dmm 0.10}, summed in that order. */
    private static final String[] STANDARD = {"nh", "veng", "multi", "dmm"};
    private static final double[] WEIGHTS = {0.55, 0.30, 0.05, 0.10};

    private final Map<String, Double> lastMu = new HashMap<>();

    /** The five buckets a match can be rated in (case-insensitive). */
    public static boolean isRated(String bucket)
    {
        String b = key(bucket);
        return TOURNEY_KEY.equals(b) || Arrays.asList(STANDARD).contains(b);
    }

    /** The last recorded mu for the bucket, {@code null} when none was —
     *  a standard bucket then counts as 1000, the tournament bucket as
     *  nothing. */
    public Double lastMu(String bucket)
    {
        return lastMu.get(key(bucket));
    }

    /** Records a match's post-game mu; ignored for a bucket the formula
     *  does not know or a non-finite value. */
    public void record(String bucket, double mu)
    {
        String b = key(bucket);
        if (isRated(b) && Double.isFinite(mu)) lastMu.put(b, mu);
    }

    /** The Overall of everything recorded so far: a standard bucket with no
     *  record counts as 1000, a tournament bucket with none contributes
     *  nothing. */
    public double overall()
    {
        double sum = 0.0;
        for (int i = 0; i < 4; i++)
        {
            Double mu = lastMu.get(STANDARD[i]);
            sum += (mu == null ? DEFAULT_MU : mu) * WEIGHTS[i];
        }
        Double tournament = lastMu.get(TOURNEY_KEY);
        if (tournament != null) sum += tournament * TOURNAMENT_WEIGHT;
        return sum;
    }

    private static String key(String bucket)
    {
        if (bucket == null) return null;
        String b = bucket.trim().toLowerCase(Locale.ROOT);
        return b.isEmpty() ? null : b;
    }
}
