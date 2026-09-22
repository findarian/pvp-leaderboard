package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.util.RankUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * "Top players" view — a scrollable Top 100 leaderboard mirroring the website,
 * one row per account: <code>#rank &nbsp; Player Name &nbsp; Rank</code>, sized
 * to the largest font the sidepanel width allows.
 *
 * <p>Reads the exact same cached S3 artifact the website uses
 * ({@code /leaderboard[_<bucket>].json}) via
 * {@link PvPDataService#getLeaderboard(String)} — a single CDN call cached
 * ~1 hour. A bucket toggle ([Overall][NH][Veng][Multi][DMM], NH by default)
 * matches the website's leaderboard tabs and the sibling "What are the ranks"
 * view. Unlike the website, only the account's first/primary name is shown.
 */
public class TopPlayersPanel extends JPanel implements Scrollable
{
    private static final String[] BUCKET_LABELS = {"Overall", "NH", "Veng", "Multi", "DMM", "Tournament"};
    private static final String DEFAULT_BUCKET = "nh";
    private static final int TOP_N = 100;

    private static final Color SELECTED_BG = new Color(60, 60, 60);
    private static final Color RANKNUM_COLOR = new Color(0xCF, 0xA8, 0x4A); // muted gold

    /** Row geometry the auto-fit subtracts before it knows how much width
     *  the three columns actually get. BorderLayout puts a gap on both
     *  sides of the centre cell, hence two. */
    private static final int ROW_HGAP = 6;
    private static final int ROW_PAD_X = 4;
    private static final int ROW_PAD_Y = 2;
    private static final int ROW_GAPS = 2;

    /** Bounds for the auto-fitted row text, same reasoning as the tier
     *  list: the floor is a legibility limit, the cap only stops a very
     *  wide panel from turning 100 rows into an endless scroll. Width is
     *  meant to bind at any realistic sidepanel size. */
    private static final int ROW_MIN_PT = 9;
    private static final int ROW_MAX_PT = 20;

    /** The rank number and tier columns render one point below the player
     *  name, which is the row's primary element. */
    private static final int SECONDARY_PT_DELTA = 1;

    /** Widest rank number a top-100 list can show; the rank column is
     *  sized to it so the names line up down the list. */
    private static final String WIDEST_RANK_NUM = "#100";

    /** One leaderboard row, derived purely from the cached JSON. */
    static final class Row
    {
        final int worldRank;
        final String name;
        final String rankFamily; // e.g. "Rune" / "3rd Age" — drives the colour
        final String tierLabel;  // e.g. "Rune 1" / "3rd Age"
        final double progressPct;

        Row(int worldRank, String name, String rankFamily, String tierLabel, double progressPct)
        {
            this.worldRank = worldRank;
            this.name = name;
            this.rankFamily = rankFamily;
            this.tierLabel = tierLabel;
            this.progressPct = progressPct;
        }
    }

    private final PvPDataService pvpDataService;

    private JPanel bucketBar;
    private final JPanel listPanel = new JPanel();
    private final JLabel statusLabel = new JLabel();

    /** Every rendered row, for the width-driven font fit. */
    private final List<PlayerRow> playerRows = new ArrayList<>();
    /** Point size currently applied to the rows; -1 until the first fit. */
    private int appliedRowPt = -1;
    /** Shared width-driven font fit (search + Look-and-Feel-accurate measuring). */
    private final RowTextFit fit = new RowTextFit();

    private volatile String selectedBucket = DEFAULT_BUCKET;

    public TopPlayersPanel()
    {
        this(null);
    }

    public TopPlayersPanel(PvPDataService pvpDataService)
    {
        this.pvpDataService = pvpDataService;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildBucketBar());

        statusLabel.setName("topPlayersStatus");
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        add(statusLabel);

        listPanel.setName("topPlayersList");
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setAlignmentX(LEFT_ALIGNMENT);
        add(listPanel);

        styleBucketButtons(selectedBucket);
    }

    /** Lazy load entry-point — called when the view is revealed. */
    public void onShown()
    {
        loadBucket(selectedBucket);
    }

    private JPanel buildBucketBar()
    {
        bucketBar = new JPanel(new GridLayout(2, 3, 2, 2));
        bucketBar.setName("topPlayersBucketBar");
        bucketBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        bucketBar.setAlignmentX(LEFT_ALIGNMENT);

        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) baseFont = new Font("SansSerif", Font.PLAIN, 12);
        Font small = baseFont.deriveFont(Font.PLAIN, Math.max(10f, baseFont.getSize2D() - 1f));

        for (String label : BUCKET_LABELS)
        {
            JButton btn = new JButton(label);
            btn.setName("topPlayersBucketBtn");
            btn.setFont(small);
            btn.setMargin(new Insets(2, 2, 2, 2));
            btn.setFocusPainted(false);
            final String bucketKey = label.toLowerCase();
            btn.addActionListener(e -> loadBucket(bucketKey));
            bucketBar.add(btn);
        }
        return bucketBar;
    }

    private void loadBucket(String bucket)
    {
        final String bucketKey = (bucket == null || bucket.trim().isEmpty())
            ? DEFAULT_BUCKET
            : bucket.trim().toLowerCase();
        selectedBucket = bucketKey;
        styleBucketButtons(bucketKey);

        if (pvpDataService == null)
        {
            showStatus("Unavailable");
            return;
        }

        // Single source of truth for caching: PvPDataService.getLeaderboard
        // is backed by the shared 60-minute shard cache (same hourly refresh
        // the website's CDN object uses). We deliberately keep NO long-lived
        // copy here — a per-panel cache would shadow that TTL and serve stale
        // rows for the whole session. Each tab visit / bucket toggle re-asks;
        // within the hour it's a cache hit (no network), after the hour it
        // refetches.
        CompletableFuture<JsonObject> future = pvpDataService.getLeaderboard(bucketKey);
        if (future == null)
        {
            showStatus("Unavailable");
            return;
        }

        // Already cached (future completed) → render immediately, no flash.
        JsonObject ready = future.getNow(null);
        if (ready != null)
        {
            renderLeaderboard(bucketKey, ready);
            return;
        }

        showStatus("Loading\u2026");
        future.thenAccept(data -> SwingUtilities.invokeLater(() ->
        {
            if (bucketKey.equals(selectedBucket)) renderLeaderboard(bucketKey, data);
        })).exceptionally(ex ->
        {
            SwingUtilities.invokeLater(() ->
            {
                if (bucketKey.equals(selectedBucket)) showStatus("Unavailable");
            });
            return null;
        });
    }

    private void renderLeaderboard(String bucketKey, JsonObject data)
    {
        if (!bucketKey.equals(selectedBucket)) return;
        if (data != null && data.has("players"))
        {
            renderRows(parseTopPlayers(data, TOP_N));
        }
        else
        {
            showStatus("Unavailable");
        }
    }

    private void showStatus(String text)
    {
        listPanel.removeAll();
        playerRows.clear();
        statusLabel.setText(text);
        statusLabel.setVisible(true);
        listPanel.revalidate();
        listPanel.repaint();
        revalidateUpChain();
    }

    private void renderRows(List<Row> rows)
    {
        listPanel.removeAll();
        playerRows.clear();
        if (rows.isEmpty())
        {
            showStatus("No ranked players yet for this bucket.");
            return;
        }
        statusLabel.setText("Showing top " + rows.size());
        statusLabel.setVisible(true);
        for (Row r : rows)
        {
            listPanel.add(buildRow(r));
        }
        // Fresh labels carry no fitted font yet, and the rows that decide
        // the fit only exist now — the panel itself hasn't resized, so
        // nothing else would trigger it.
        // These are brand-new labels carrying no fitted font, so the fit
        // has to start from scratch — a size "already applied" to the
        // discarded rows would otherwise short-circuit it and leave this
        // batch at the Look-and-Feel default.
        // These are brand-new labels carrying no fitted font, so the fit
        // has to start from scratch — a size "already applied" to the
        // discarded rows would otherwise short-circuit it and leave this
        // batch at the Look-and-Feel default.
        appliedRowPt = -1;
        applyRowPt(ROW_MAX_PT); // defined size even if the panel is not laid out yet
        refitRowFonts();
        listPanel.revalidate();
        listPanel.repaint();
        revalidateUpChain();
    }

    /**
     * Rows load asynchronously after the view is first shown, but our enclosing
     * {@link JScrollPane} is a Swing "validate root" — so revalidating
     * {@code listPanel} alone re-lays-out the viewport WITHOUT resizing the
     * scroll pane itself within the lookup card (it stays stuck at whatever
     * height it had while empty/"Loading…", showing only a handful of rows).
     * Revalidating the scroll pane's parent forces the surrounding layout to
     * grow the view to the full row count immediately instead of waiting for an
     * unrelated outer resize/scroll event.
     */
    private void revalidateUpChain()
    {
        Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        Container target = (scrollPane != null) ? scrollPane.getParent() : getParent();
        if (target != null)
        {
            target.revalidate();
            target.repaint();
        }
    }

    private JPanel buildRow(Row r)
    {
        JPanel row = new JPanel(new BorderLayout(ROW_HGAP, 0));
        row.setName("topPlayerRow");
        row.setBorder(BorderFactory.createEmptyBorder(ROW_PAD_Y, ROW_PAD_X, ROW_PAD_Y, ROW_PAD_X));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel rankNum = new JLabel("#" + r.worldRank);
        rankNum.setName("topPlayerRankNum");
        rankNum.setForeground(RANKNUM_COLOR);
        row.add(rankNum, BorderLayout.WEST);

        JLabel name = new JLabel(r.name);
        name.setName("topPlayerName");
        name.setForeground(Color.WHITE);
        row.add(name, BorderLayout.CENTER);

        // Rank tier only — the in-tier "% to next" used to sit here, but it
        // was the widest thing in the row and bought the least: it pushed
        // the auto-fit down and squeezed the player name.
        JLabel tier = new JLabel(r.tierLabel);
        tier.setName("topPlayerTier");
        tier.setForeground("3rd Age".equals(r.rankFamily) ? Color.WHITE : RankUtils.getRankColor(r.rankFamily));
        tier.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(tier, BorderLayout.EAST);

        playerRows.add(new PlayerRow(row, rankNum, name, tier));
        return row;
    }

    /** A leaderboard row's moving parts, held together so the font fit can
     *  measure all three columns and resize the row that contains them. */
    private static final class PlayerRow
    {
        final JPanel row;
        final JLabel rankNum;
        final JLabel name;
        final JLabel tier;

        PlayerRow(JPanel row, JLabel rankNum, JLabel name, JLabel tier)
        {
            this.row = row;
            this.rankNum = rankNum;
            this.name = name;
            this.tier = tier;
        }
    }

    /**
     * Re-fit the row text to the panel's current width before laying out.
     *
     * <p>Hooked here rather than on a resize listener so the fit is driven
     * by the same pass that positions the rows — there's no window where
     * the text is sized for a stale width.
     */
    @Override
    public void doLayout()
    {
        refitRowFonts();
        super.doLayout();
    }

    /**
     * Pick the largest point size at which EVERY row's three columns fit
     * side by side, within {@link #ROW_MIN_PT}..{@link #ROW_MAX_PT}.
     *
     * <p>One size for all rows rather than per-row: mixed sizes down a
     * single list read as a rendering bug. The widest row therefore
     * governs the list — in practice the longest player name.
     */
    private void refitRowFonts()
    {
        int width = getWidth();
        if (width <= 0 || playerRows.isEmpty())
        {
            return; // not laid out yet, or nothing to size
        }
        int usable = width - (2 * ROW_PAD_X) - (ROW_GAPS * ROW_HGAP);
        if (usable <= 0)
        {
            return;
        }

        applyRowPt(fit.largestFitting(ROW_MIN_PT, ROW_MAX_PT, pt -> allRowsFit(pt, usable)));

        // Backstop. Everything above measures a stand-in label; these are
        // the real components the layout will size. If the installed
        // Look-and-Feel makes them even slightly wider than the stand-in,
        // step down until they genuinely fit — a shortfall here is what
        // Swing renders as an ellipsized player name. Normally costs zero
        // iterations.
        while (appliedRowPt > ROW_MIN_PT && !appliedRowsFit(usable))
        {
            applyRowPt(appliedRowPt - 1);
        }
    }

    private boolean allRowsFit(int namePt, int usable)
    {
        Font nameFont = nameFont(namePt);
        Font secondaryFont = secondaryFont(namePt);
        int rankColumn = rankColumnWidth(secondaryFont);
        for (PlayerRow row : playerRows)
        {
            int needed = rankColumn
                + fit.textWidth(row.name.getText(), nameFont)
                + fit.textWidth(row.tier.getText(), secondaryFont);
            if (needed > usable)
            {
                return false;
            }
        }
        return true;
    }

    /** Whether the rows fit at the size currently applied, measured on the
     *  live labels rather than the stand-in. */
    private boolean appliedRowsFit(int usable)
    {
        for (PlayerRow row : playerRows)
        {
            int needed = row.rankNum.getPreferredSize().width
                + row.name.getPreferredSize().width
                + row.tier.getPreferredSize().width;
            if (needed > usable)
            {
                return false;
            }
        }
        return true;
    }

    private void applyRowPt(int namePt)
    {
        if (namePt == appliedRowPt)
        {
            return; // idempotent — avoids a setFont/revalidate layout loop
        }
        appliedRowPt = namePt;

        Font nameFont = nameFont(namePt);
        Font secondaryFont = secondaryFont(namePt);
        int rowHeight = Math.max(fit.textHeight(nameFont), fit.textHeight(secondaryFont)) + (2 * ROW_PAD_Y);
        // Fixed-width rank column so the names line up down the list; it
        // has to track the font or "#100" gets truncated.
        Dimension rankSize = new Dimension(rankColumnWidth(secondaryFont), rowHeight);

        for (PlayerRow row : playerRows)
        {
            row.rankNum.setFont(secondaryFont);
            row.rankNum.setPreferredSize(rankSize);
            row.name.setFont(nameFont);
            row.tier.setFont(secondaryFont);
            // BoxLayout caps each row at its maximum size, so this has to
            // track the font or taller glyphs get clipped.
            row.row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
        }
    }

    private int rankColumnWidth(Font secondaryFont)
    {
        return fit.textWidth(WIDEST_RANK_NUM, secondaryFont);
    }

    private static Font nameFont(int namePt)
    {
        return RowTextFit.baseFont().deriveFont(Font.PLAIN, (float) namePt);
    }

    private static Font secondaryFont(int namePt)
    {
        return RowTextFit.baseFont().deriveFont(Font.BOLD, (float) (namePt - SECONDARY_PT_DELTA));
    }

    /** Per-account aggregate built while de-duplicating the raw player rows. */
    private static final class Agg
    {
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        double mmr = Double.NEGATIVE_INFINITY;
        String rank = null;
        int division = 0;
    }

    /**
     * Convert a cached leaderboard payload into the top {@code limit} display
     * rows, mirroring the website's {@code fetchLeaderboard} exactly:
     * <ol>
     *   <li>keep only rows with an account id + finite MMR;</li>
     *   <li>de-duplicate by account ({@code account_hash || account}) — the S3
     *       file can carry more than one row per account — unioning names in
     *       first-seen order and keeping the highest-MMR entry's rank/division;</li>
     *   <li>sort by MMR descending, assign world rank by position;</li>
     *   <li>take the top {@code limit}.</li>
     * </ol>
     * The plugin shows only the account's first/primary name (the website
     * joins them all). In-tier "% to next rank" matches the website's
     * {@code computeProgressToNextRank} via
     * {@link RankUtils#calculateProgressFromMMR(double)}.
     *
     * <p>Pure + null-safe so it's unit-testable without Swing/network.
     */
    static List<Row> parseTopPlayers(JsonObject data, int limit)
    {
        List<Row> rows = new ArrayList<>();
        if (data == null || !data.has("players") || !data.get("players").isJsonArray())
        {
            return rows;
        }
        JsonArray players = data.getAsJsonArray("players");

        Map<String, Agg> byAccount = new LinkedHashMap<>();
        for (JsonElement el : players)
        {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            double mmr = mmrOf(p);
            if (!Double.isFinite(mmr)) continue;
            String account = accountOf(p);
            if (account == null) continue;

            Agg agg = byAccount.computeIfAbsent(account, k -> new Agg());
            addNames(agg.names, p);
            if (mmr > agg.mmr)
            {
                agg.mmr = mmr;
                agg.rank = strOrNull(p, "rank");
                agg.division = intOr(p, "division", 0);
            }
        }

        List<Agg> ranked = new ArrayList<>(byAccount.values());
        ranked.sort((a, b) -> Double.compare(b.mmr, a.mmr));

        int n = Math.min(Math.max(0, limit), ranked.size());
        for (int i = 0; i < n; i++)
        {
            Agg a = ranked.get(i);
            String rankFamily = a.rank;
            int division = a.division;
            if (rankFamily == null)
            {
                com.pvp.leaderboard.service.RankInfo ri = RankUtils.rankLabelAndProgressFromMMR(a.mmr);
                rankFamily = ri.rank;
                division = ri.division;
            }
            String tierLabel = "3rd Age".equals(rankFamily) ? "3rd Age" : rankFamily + " " + division;
            double pct = RankUtils.calculateProgressFromMMR(a.mmr);
            String name = a.names.isEmpty() ? "Unknown" : a.names.iterator().next();

            rows.add(new Row(i + 1, name, rankFamily, tierLabel, pct));
        }
        return rows;
    }

    private static double mmrOf(JsonObject p)
    {
        try
        {
            if (p.has("mmr") && !p.get("mmr").isJsonNull()) return p.get("mmr").getAsDouble();
            if (p.has("MMR") && !p.get("MMR").isJsonNull()) return p.get("MMR").getAsDouble();
        }
        catch (RuntimeException ignore) { }
        return Double.NaN;
    }

    /** Account id, mirroring the website's {@code account_hash || account}. */
    private static String accountOf(JsonObject p)
    {
        String a = strOrNull(p, "account_hash");
        return (a != null) ? a : strOrNull(p, "account");
    }

    private static void addNames(Set<String> out, JsonObject p)
    {
        try
        {
            if (p.has("player_names") && p.get("player_names").isJsonArray())
            {
                for (JsonElement n : p.getAsJsonArray("player_names"))
                {
                    if (n != null && !n.isJsonNull())
                    {
                        String s = n.getAsString();
                        if (s != null && !s.trim().isEmpty()) out.add(s);
                    }
                }
            }
        }
        catch (RuntimeException ignore) { }
    }

    private static String strOrNull(JsonObject p, String key)
    {
        try
        {
            if (p.has(key) && !p.get(key).isJsonNull())
            {
                String s = p.get(key).getAsString();
                return (s == null || s.trim().isEmpty()) ? null : s;
            }
        }
        catch (RuntimeException ignore) { }
        return null;
    }

    private static int intOr(JsonObject p, String key, int fallback)
    {
        try
        {
            if (p.has(key) && !p.get(key).isJsonNull()) return p.get(key).getAsInt();
        }
        catch (RuntimeException ignore) { }
        return fallback;
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

    // --- Scrollable: track viewport width (rows never overflow sideways);
    // vertical scrolling handled by the enclosing JScrollPane. ---

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
}
