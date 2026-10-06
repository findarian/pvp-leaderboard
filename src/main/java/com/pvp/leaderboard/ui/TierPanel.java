package com.pvp.leaderboard.ui;

import com.google.gson.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import static com.pvp.leaderboard.ui.Ui.*;

/**
 * "What are the ranks" view: every tier from 3rd Age down to Bronze 3 with
 * a live "Top X%" derived from the infra-side rank histogram
 * ({@code rank_hist/<bucket>.json}, see {@code backend/core/rank_histogram.py}).
 *
 * <p>A bucket toggle ([Overall][NH][Veng][Multi][DMM], NH selected by
 * default) mirrors the Performance Breakdown selector in Player Lookup.
 * Switching buckets re-fetches that bucket's histogram (cached per bucket)
 * and recomputes the per-tier share of the population at or above each
 * tier's MMR cutoff.
 *
 * <p>Row text: the bold tier name is the row's primary element; the value
 * column renders one point below it, plain. The font cap (18 pt) matters
 * because 25 rows scale together: past it the list turns into a long scroll
 * and dwarfs the bucket toggles above it. The widest string in the view
 * governs the whole list — currently the "No one yet" placeholder.
 */
public class TierPanel extends RowsPanel
{
    private static final Color VALUE_COLOR = new Color(200, 200, 200);

    private final PvpApi pvpApi;

    private final JPanel bar;
    /** Tier name and value: 9-18 pt; a row loses 2 x 4 px padding and one 6 px gap. */
    private final RowTextFit fit = new RowTextFit(9, 18, 14, null, new int[]{Font.BOLD, Font.PLAIN}, new int[]{0, 1});
    /** bucket key → fetched histogram (avoids refetching on toggle). */
    private final Map<String, JsonObject> histCache = new ConcurrentHashMap<>();

    private volatile String selectedBucket = "nh";

    public TierPanel(PvpApi pvpApi)
    {
        this.pvpApi = pvpApi;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        // Two rows of 3 (Overall/NH/Veng · Multi/DMM) — five labels don't fit
        // on one row in the ~225px sidepanel.
        bar = left(maxH(bucketBar("rankTierBucketBar", "rankTierBucketBtn", 2, 3, Arrays.copyOf(BUCKETS, 5), this::loadBucket), 52));
        add(bar);
        add(buildTiers());
        styleBucketBar(bar, selectedBucket);
    }

    /**
     * Trigger (or refresh) the histogram load for the selected bucket.
     * Called by {@link Dashboard} when the view is revealed so no
     * network request happens until the user actually opens it. Cached
     * buckets render instantly; a previously-failed fetch is retried.
     */
    public void onShown()
    {
        loadBucket(selectedBucket);
    }

    /** One row per tier, 3rd Age first; row {@code j} is {@code THRESHOLDS[24 - j]}. */
    private JPanel buildTiers()
    {
        var list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setName("rankTierList");
        left(list);

        String[][] thresholds = RankUtils.THRESHOLDS;
        for (int i = thresholds.length - 1; i >= 0; i--)
        {
            String rankName = thresholds[i][0];
            var nameLabel = new JLabel("3rd Age".equals(rankName) ? rankName : rankName + " " + thresholds[i][1]);
            nameLabel.setName("tierName");
            nameLabel.setForeground(RankUtils.getRankColor(rankName));

            // Right-aligned in CENTER (not EAST) so the value uses all the
            // space left of the inner edge: every row's value ends flush at
            // the same right margin (an "equalized" column) and a long
            // "No one yet" stays fully visible.
            var valueLabel = new JLabel("—");
            valueLabel.setName("tierValue");
            valueLabel.setForeground(VALUE_COLOR);
            valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            list.add(fit.row("tierRow", nameLabel, valueLabel));
        }
        // Rows start at the cap; the first layout narrows them to whatever
        // the real sidepanel width allows.
        fit.restart(getWidth());
        return list;
    }

    /**
     * Re-fit the row text to the panel's current width before laying out.
     *
     * <p>Hooked here rather than on a resize listener so the fit is driven
     * by the same pass that positions the rows — a test can drive it with
     * {@code setSize} + {@code doLayout} instead of pumping resize events,
     * and there's no window where the text is sized for a stale width.
     */
    @Override
    public void doLayout()
    {
        fit.refit(getWidth());
        super.doLayout();
    }

    /** {@code bucketKey} is a bar key or {@link #selectedBucket}: trimmed, lower case. */
    private void loadBucket(String bucketKey)
    {
        selectedBucket = bucketKey;
        styleBucketBar(bar, bucketKey);

        JsonObject cached = histCache.get(bucketKey);
        if (cached != null)
        {
            applyHist(cached);
            return;
        }

        if (pvpApi == null)
        {
            setAllValues("Unavailable");
            return;
        }

        setAllValues("…"); // ellipsis = loading
        CompletableFuture<JsonObject> future = pvpApi.getHistogram(bucketKey);
        if (future == null)
        {
            setAllValues("Unavailable");
            return;
        }
        future.thenAccept(hist -> SwingUtilities.invokeLater(() ->
        {
            if (hist != null && hist.has("bins"))
            {
                histCache.put(bucketKey, hist);
                if (bucketKey.equals(selectedBucket)) applyHist(hist);
            }
            else if (bucketKey.equals(selectedBucket))
            {
                setAllValues("Unavailable");
            }
        })).exceptionally(ex ->
        {
            SwingUtilities.invokeLater(() ->
            {
                if (bucketKey.equals(selectedBucket)) setAllValues("Unavailable");
            });
            return null;
        });
    }

    private void applyHist(JsonObject hist)
    {
        long total = RankUtils.histTotal(hist);
        String[][] t = RankUtils.THRESHOLDS;
        for (int i = 0; i < t.length; i++)
        {
            long atOrAbove = RankUtils.totalAbove(hist, Double.parseDouble(t[i][2]));
            ((JLabel) fit.rows.get(t.length - 1 - i)[2]).setText(RankUtils.formatTopPercent(atOrAbove, total));
        }
        // The values just changed width ("—" → "No one yet"),
        // so the fit is stale even though the panel hasn't resized.
        fit.refit(getWidth());
    }

    private void setAllValues(String text)
    {
        for (JComponent[] row : fit.rows)
        {
            ((JLabel) row[2]).setText(text);
        }
        fit.refit(getWidth());
    }
}
