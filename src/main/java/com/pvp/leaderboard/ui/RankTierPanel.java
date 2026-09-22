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

    /** Row geometry the auto-fit has to subtract before it knows how much
     *  width the two text columns actually get. Kept as constants so
     *  {@link #buildTierList} and {@link #refitTierFonts} can't drift. */
    private static final int ROW_HGAP = 6;
    private static final int ROW_PAD_X = 4;
    private static final int ROW_PAD_Y = 2;

    /** Bounds for the auto-fitted row text. The floor is a legibility
     *  limit — below it we'd rather clip than render mush. The cap is
     *  only a backstop against absurdity on a very wide panel; width is
     *  meant to be the binding constraint at any realistic sidepanel
     *  size, so the rows actually spend the space they're given. The cap
     *  matters because 25 rows scale together: past ~18pt the list turns
     *  into a long scroll and dwarfs the bucket toggles above it. */
    private static final int TIER_MIN_PT = 9;
    private static final int TIER_MAX_PT = 18;

    /** The value column renders one point below the name column, so the
     *  bold rank reads as the primary element in the row. */
    private static final int TIER_VALUE_PT_DELTA = 1;

    private final PvPDataService pvpDataService;

    private JPanel bucketBar;
    /** THRESHOLDS index → that tier's right-hand "Top X%" label. */
    private final Map<Integer, JLabel> valueByThresholdIdx = new HashMap<>();
    /** Every tier row, for the width-driven font fit. */
    private final java.util.List<TierRow> tierRows = new java.util.ArrayList<>();
    /** Point size currently applied to the rows; -1 until the first fit. */
    private int appliedTierPt = -1;
    /** Shared width-driven font fit (search + Look-and-Feel-accurate measuring). */
    private final RowTextFit fit = new RowTextFit();
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

        Font base = RowTextFit.baseFont();
        Font small = base.deriveFont(Font.PLAIN, Math.max(10f, base.getSize2D() - 1f));

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

            JPanel row = new JPanel(new BorderLayout(ROW_HGAP, 0));
            row.setName("tierRow");
            row.setBorder(BorderFactory.createEmptyBorder(ROW_PAD_Y, ROW_PAD_X, ROW_PAD_Y, ROW_PAD_X));
            row.setAlignmentX(LEFT_ALIGNMENT);

            JLabel nameLabel = new JLabel(displayName);
            nameLabel.setName("tierName");
            nameLabel.setForeground("3rd Age".equals(rankName) ? Color.WHITE : RankUtils.getRankColor(rankName));
            row.add(nameLabel, BorderLayout.WEST);

            // Right-aligned in CENTER (not EAST) so the value uses all the
            // space left of the inner edge: every row's value ends flush at
            // the same right margin (an "equalized" column) and a long
            // "No one yet" stays fully visible.
            JLabel valueLabel = new JLabel("\u2014");
            valueLabel.setName("tierValue");
            valueLabel.setForeground(VALUE_COLOR);
            valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
            row.add(valueLabel, BorderLayout.CENTER);

            valueByThresholdIdx.put(i, valueLabel);
            tierRows.add(new TierRow(row, nameLabel, valueLabel));
            list.add(row);
        }
        // Rows start at the cap; the first layout narrows them to whatever
        // the real sidepanel width allows.
        applyTierPt(TIER_MAX_PT);
        return list;
    }

    /** A tier row's three moving parts, held together so the font fit can
     *  measure both columns and resize the row that contains them. */
    private static final class TierRow
    {
        final JPanel row;
        final JLabel name;
        final JLabel value;

        TierRow(JPanel row, JLabel name, JLabel value)
        {
            this.row = row;
            this.name = name;
            this.value = value;
        }
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
        refitTierFonts();
        super.doLayout();
    }

    /**
     * Pick the largest point size at which EVERY row's name and value fit
     * side by side, within {@link #TIER_MIN_PT}..{@link #TIER_MAX_PT}.
     *
     * <p>One size for all rows rather than per-row: mixed sizes down a
     * single column read as a rendering bug. That does mean the widest
     * string in the view governs the whole list — currently the
     * "No one yet" placeholder on empty tiers.
     */
    private void refitTierFonts()
    {
        int width = getWidth();
        if (width <= 0 || tierRows.isEmpty())
        {
            return; // not laid out yet — keep whatever size is applied
        }
        int usable = width - (2 * ROW_PAD_X) - ROW_HGAP;
        if (usable <= 0)
        {
            return;
        }

        applyTierPt(fit.largestFitting(TIER_MIN_PT, TIER_MAX_PT, pt -> allRowsFit(pt, usable)));

        // Backstop. Everything above measures a stand-in label; these are
        // the real components the layout will size. If the installed
        // Look-and-Feel makes them even slightly wider than the stand-in,
        // step down until they genuinely fit — a shortfall here is what
        // Swing renders as "Adaman...", and no rank name should ever be
        // truncated. Normally costs zero iterations.
        while (appliedTierPt > TIER_MIN_PT && !appliedRowsFit(usable))
        {
            applyTierPt(appliedTierPt - 1);
        }
    }

    /** Whether the rows fit at the size currently applied, measured on
     *  the live labels rather than the stand-in. */
    private boolean appliedRowsFit(int usable)
    {
        for (TierRow row : tierRows)
        {
            if (row.name.getPreferredSize().width + row.value.getPreferredSize().width > usable)
            {
                return false;
            }
        }
        return true;
    }

    private boolean allRowsFit(int namePt, int usable)
    {
        Font nameFont = tierNameFont(namePt);
        Font valueFont = tierValueFont(namePt);
        for (TierRow row : tierRows)
        {
            int needed = fit.textWidth(row.name.getText(), nameFont)
                + fit.textWidth(row.value.getText(), valueFont);
            if (needed > usable)
            {
                return false;
            }
        }
        return true;
    }

    private void applyTierPt(int namePt)
    {
        if (namePt == appliedTierPt)
        {
            return; // idempotent — avoids a setFont/revalidate layout loop
        }
        appliedTierPt = namePt;

        Font nameFont = tierNameFont(namePt);
        Font valueFont = tierValueFont(namePt);
        int rowHeight = Math.max(fit.textHeight(nameFont), fit.textHeight(valueFont)) + (2 * ROW_PAD_Y);

        for (TierRow row : tierRows)
        {
            row.name.setFont(nameFont);
            row.value.setFont(valueFont);
            // BoxLayout caps each row at its maximum size, so this has to
            // track the font or taller glyphs get clipped.
            row.row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
        }
    }

    private static Font tierNameFont(int namePt)
    {
        return RowTextFit.baseFont().deriveFont(Font.BOLD, (float) namePt);
    }

    private static Font tierValueFont(int namePt)
    {
        return RowTextFit.baseFont().deriveFont(Font.PLAIN, (float) (namePt - TIER_VALUE_PT_DELTA));
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
        // The values just changed width ("—" → "No one yet"),
        // so the fit is stale even though the panel hasn't resized.
        refitTierFonts();
    }

    private void setAllValues(String text)
    {
        for (JLabel label : valueByThresholdIdx.values())
        {
            label.setText(text);
        }
        refitTierFonts();
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
