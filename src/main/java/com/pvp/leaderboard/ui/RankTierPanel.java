package com.pvp.leaderboard.ui;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.util.RankUtils;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public class RankTierPanel extends JPanel implements Scrollable
{
    /** Button label → bucket key (label.toLowerCase()). NH is selected by default. */
    private static final String[] BUCKET_LABELS = {"Overall", "NH", "Veng", "Multi", "DMM"};
    private static final String DEFAULT_BUCKET = "nh";

    private static final Color VALUE_COLOR = new Color(200, 200, 200);
    private static final Color SELECTED_BG = new Color(60, 60, 60);

    private final PvPDataService pvpDataService;

    private JPanel bucketBar;
    /** THRESHOLDS index → that tier's right-hand "Top X%" label. */
    private final Map<Integer, JLabel> valueByThresholdIdx = new HashMap<>();
    /** bucket key → fetched histogram (avoids refetching on toggle). */
    private final Map<String, JsonObject> histogramCache = new ConcurrentHashMap<>();

    private volatile String selectedBucket = DEFAULT_BUCKET;

    /** No-arg constructor kept for callers/tests that don't need live data. */
    public RankTierPanel()
    {
        this(null);
    }

    public RankTierPanel(PvPDataService pvpDataService)
    {
        this.pvpDataService = pvpDataService;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(buildBucketBar());
        add(buildTierList());
        styleBucketButtons(selectedBucket);
    }

    /**
     * Trigger (or refresh) the histogram load for the selected bucket.
     * Called by {@link DashboardPanel} when the view is revealed so no
     * network request happens until the user actually opens it. Cached
     * buckets render instantly; a previously-failed fetch is retried.
     */
    public void onShown()
    {
        loadBucket(selectedBucket);
    }

    private JPanel buildBucketBar()
    {
        // Two rows of 3 (Overall/NH/Veng · Multi/DMM) — five labels don't fit
        // on one row in the ~225px sidepanel. Mirrors the Performance
        // Breakdown selector geometry for consistency.
        bucketBar = new JPanel(new GridLayout(2, 3, 2, 2));
        bucketBar.setName("rankTierBucketBar");
        bucketBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        bucketBar.setAlignmentX(LEFT_ALIGNMENT);

        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) baseFont = new Font("SansSerif", Font.PLAIN, 12);
        Font small = baseFont.deriveFont(Font.PLAIN, Math.max(10f, baseFont.getSize2D() - 1f));

        for (String label : BUCKET_LABELS)
        {
            JButton btn = new JButton(label);
            btn.setName("rankTierBucketBtn");
            btn.setFont(small);
            btn.setMargin(new Insets(2, 2, 2, 2));
            btn.setFocusPainted(false);
            final String bucketKey = label.toLowerCase();
            btn.addActionListener(e -> loadBucket(bucketKey));
            bucketBar.add(btn);
        }
        return bucketBar;
    }

    private JPanel buildTierList()
    {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setName("rankTierList");
        list.setAlignmentX(LEFT_ALIGNMENT);

        String[][] thresholds = RankUtils.THRESHOLDS;
        for (int i = thresholds.length - 1; i >= 0; i--)
        {
            String rankName = thresholds[i][0];
            int division = Integer.parseInt(thresholds[i][1]);

            String displayName = "3rd Age".equals(rankName)
                ? "3rd Age"
                : rankName + " " + division;

            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.setName("tierRow");
            row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            row.setAlignmentX(LEFT_ALIGNMENT);

            JLabel nameLabel = new JLabel(displayName);
            nameLabel.setName("tierName");
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
            nameLabel.setForeground("3rd Age".equals(rankName) ? Color.WHITE : RankUtils.getRankColor(rankName));
            row.add(nameLabel, BorderLayout.WEST);

            // Right-aligned in CENTER (not EAST) so the value uses all the
            // space left of the inner edge: every row's value ends flush at
            // the same right margin (an "equalized" column) and a long
            // "No one currently here" stays fully visible.
            JLabel valueLabel = new JLabel("\u2014");
            valueLabel.setName("tierValue");
            valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, 11f));
            valueLabel.setForeground(VALUE_COLOR);
            valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
            row.add(valueLabel, BorderLayout.CENTER);

            valueByThresholdIdx.put(i, valueLabel);
            list.add(row);
        }
        return list;
    }

    private void loadBucket(String bucket)
    {
        final String bucketKey = (bucket == null || bucket.trim().isEmpty())
            ? DEFAULT_BUCKET
            : bucket.trim().toLowerCase();
        selectedBucket = bucketKey;
        styleBucketButtons(bucketKey);

        JsonObject cached = histogramCache.get(bucketKey);
        if (cached != null)
        {
            applyHistogram(cached);
            return;
        }

        if (pvpDataService == null)
        {
            setAllValues("Unavailable");
            return;
        }

        setAllValues("\u2026"); // ellipsis = loading
        CompletableFuture<JsonObject> future = pvpDataService.getRankHistogram(bucketKey);
        if (future == null)
        {
            setAllValues("Unavailable");
            return;
        }
        future.thenAccept(hist -> SwingUtilities.invokeLater(() ->
        {
            if (hist != null && hist.has("bins"))
            {
                histogramCache.put(bucketKey, hist);
                if (bucketKey.equals(selectedBucket)) applyHistogram(hist);
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

    private void applyHistogram(JsonObject hist)
    {
        long total = RankUtils.histogramTotal(hist);
        for (Map.Entry<Integer, JLabel> e : valueByThresholdIdx.entrySet())
        {
            double cutoff = Double.parseDouble(RankUtils.THRESHOLDS[e.getKey()][2]);
            long atOrAbove = RankUtils.cumulativeCountAtOrAbove(hist, cutoff);
            e.getValue().setText(RankUtils.formatTopPercent(atOrAbove, total));
        }
    }

    private void setAllValues(String text)
    {
        for (JLabel label : valueByThresholdIdx.values())
        {
            label.setText(text);
        }
    }

    // --- Scrollable: track the viewport WIDTH so rows never overflow
    // horizontally (the right-aligned value column stays flush at the inner
    // edge), while still allowing vertical scrolling for the 25 tiers. ---

    @Override
    public Dimension getPreferredScrollableViewportSize()
    {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
    {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
    {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth()
    {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight()
    {
        return false;
    }

    private void styleBucketButtons(String activeBucket)
    {
        if (bucketBar == null) return;
        for (Component c : bucketBar.getComponents())
        {
            if (!(c instanceof JButton)) continue;
            JButton btn = (JButton) c;
            boolean selected = btn.getText().equalsIgnoreCase(activeBucket);
            if (selected)
            {
                btn.setForeground(Color.WHITE);
                btn.setBackground(SELECTED_BG);
            }
            else
            {
                btn.setForeground(Color.GRAY);
                btn.setBackground(UIManager.getColor("Button.background"));
            }
        }
    }
}
