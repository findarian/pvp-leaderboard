package com.pvp.leaderboard.perf;

import java.util.*;

/** The Overall stats box's numbers, as running totals: the fights, wins, losses, ties and your net v3 change, then each
 *  side's nine stats. Off-pray, Offensive prayer and Hits on robes are one percentage of the summed parts; Magic luck
 *  the summed hits over the summed attempts with the luck, as the card shows it; the rest are per-fight averages, HP
 *  healed whole, KO chances over the fights where either side had one. "-" where no fight has the stat. */
final class OverallStats
{
    /** Each stat's number of parts: an amount 1, "a/b" or a count with its total 2, magic hits "h/a/e" 3. */
    private static final int[] PARTS = {2, 1, 1, 3, 2, 1, 2, 2, 2};
    private static final List<Integer> RATIOS = List.of(0, 4, 6);

    int fights, wins, losses, ties;
    /** Your summed v3 change and the fights that have one. */
    private double change;
    private int changes;
    /** Per side and stat: the sums of the first, second and last parts, and the fights counted. */
    private final double[][][] sum = new double[2][9][4];

    static OverallStats of(List<FightLog.Fight> fights)
    {
        OverallStats s = new OverallStats();
        for (FightLog.Fight f : fights) s.add(f, 1);
        return s;
    }

    /** {@code f} added ({@code sign} 1) or taken away (-1). */
    void add(FightLog.Fight f, int sign)
    {
        fights += sign;
        if ("win".equals(f.result)) wins += sign;
        if ("loss".equals(f.result)) losses += sign;
        if ("tie".equals(f.result)) ties += sign;
        double c = value(f.change);
        if (Double.isFinite(c))
        {
            change += sign * c;
            changes += sign;
        }
        double[] mk = parts(f.mine[7], 2), tk = parts(f.theirs[7], 2);
        boolean ko = mk != null && mk[0] > 0 || tk != null && tk[0] > 0;
        for (int side = 0; side < 2; side++)
        {
            for (int i = 0; i < 9; i++)
            {
                double[] p = parts((side == 0 ? f.mine : f.theirs)[i], PARTS[i]);
                if (p == null || i == 7 && !ko) continue;
                double[] t = sum[side][i];
                t[0] += sign * p[0];
                t[1] += sign * p[1 % p.length];
                t[2] += sign * p[p.length - 1];
                t[3] += sign;
            }
        }
    }

    String[] mine()
    {
        return shown(0);
    }

    String[] theirs()
    {
        return shown(1);
    }

    /** Your net change as the card shows one; "-" when no fight has one. */
    String mmr()
    {
        return changes < 1 ? "-" : FightsPanel.mmr(String.valueOf(change));
    }

    private String[] shown(int side)
    {
        String[] out = new String[9];
        for (int i = 0; i < 9; i++)
        {
            double[] t = sum[side][i];
            out[i] = t[3] < 0.5 ? "-" : i == 3 ? (t[1] < 0.5 ? "-" : FightsPanel.shown(3, magic(t)))
                : RATIOS.contains(i) ? (t[2] < 1e-9 ? "-" : Math.round(100 * t[0] / t[2]) + "%")
                : i == 5 ? String.valueOf(Math.round(t[0] / t[3]))
                : PARTS[i] == 1 ? num(t[0] / t[3]) : num(t[0] / t[3]) + " (" + Math.round(t[2] / t[3]) + (i == 7 ? "%)" : ")");
        }
        return out;
    }

    /** {@code side}'s stats in the card's own form, for its colours ({@link FightsPanel#tones}): the averages as shown,
     *  magic hits as their sums, hits on robes as the percentage shown over 100, KO chances as the count and the
     *  percentage shown; "-" for the rest. */
    String[] compared(int side)
    {
        String[] s = shown(side), out = new String[9];
        Arrays.fill(out, "-");
        out[1] = s[1];
        out[2] = s[2];
        if (!"-".equals(s[3])) out[3] = magic(sum[side][3]);
        if (!"-".equals(s[6])) out[6] = s[6].replace("%", "/100");
        if (!"-".equals(s[7])) out[7] = s[7].replace(" (", "/").replace("%)", "");
        return out;
    }

    /** Summed magic hits as one fight's "h/a/e". */
    private static String magic(double[] t)
    {
        return Math.round(t[0]) + "/" + Math.round(t[1]) + "/" + t[2];
    }

    /** An average as shown: whole from 10 up, else one decimal, without a trailing ".0". */
    private static String num(double v)
    {
        String s = v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static double value(String s)
    {
        try
        {
            return Double.parseDouble(s);
        }
        catch (RuntimeException e)
        {
            return Double.NaN;
        }
    }

    /** A stored value's {@code n} parts; null when it does not read. */
    private static double[] parts(String v, int n)
    {
        String[] p = FightMerge.number(v) ? v.split("/", -1) : new String[0];
        if (p.length != n) return null;
        double[] out = new double[n];
        try
        {
            for (int i = 0; i < n; i++) out[i] = Double.parseDouble(p[i]);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
        return out;
    }
}
