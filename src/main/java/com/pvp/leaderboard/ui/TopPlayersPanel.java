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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * "Top players" view — a scrollable Top 100 leaderboard mirroring the website,
 * one row per account: <code>#rank &nbsp; Player Name &nbsp; Rank (% to next)</code>.
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
    private static final String[] BUCKET_LABELS = {"Overall", "NH", "Veng", "Multi", "DMM"};
    private static final String DEFAULT_BUCKET = "nh";
    private static final int TOP_N = 100;

    private static final Color SELECTED_BG = new Color(60, 60, 60);
    private static final Color RANKNUM_COLOR = new Color(0xCF, 0xA8, 0x4A); // muted gold
    private static final Color PCT_COLOR = new Color(0x9A, 0x88, 0x66);     // muted gold for "(xx.x%)"

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
        statusLabel.setText(text);
        statusLabel.setVisible(true);
        listPanel.revalidate();
        listPanel.repaint();
        revalidateUpChain();
    }

    private void renderRows(List<Row> rows)
    {
        listPanel.removeAll();
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
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setName("topPlayerRow");
        row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel rankNum = new JLabel("#" + r.worldRank);
        rankNum.setName("topPlayerRankNum");
        rankNum.setFont(rankNum.getFont().deriveFont(Font.BOLD, 12f));
        rankNum.setForeground(RANKNUM_COLOR);
        rankNum.setPreferredSize(new Dimension(36, 20));
        row.add(rankNum, BorderLayout.WEST);

        JLabel name = new JLabel(r.name);
        name.setName("topPlayerName");
        name.setFont(name.getFont().deriveFont(Font.PLAIN, 13f));
        name.setForeground(Color.WHITE);
        row.add(name, BorderLayout.CENTER);

        JLabel tier = new JLabel(tierHtml(r));
        tier.setName("topPlayerTier");
        tier.setFont(tier.getFont().deriveFont(Font.PLAIN, 12f));
        tier.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(tier, BorderLayout.EAST);

        return row;
    }

    private static String tierHtml(Row r)
    {
        Color c = "3rd Age".equals(r.rankFamily) ? Color.WHITE : RankUtils.getRankColor(r.rankFamily);
        String tierHex = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        String pctHex = String.format("#%02x%02x%02x", PCT_COLOR.getRed(), PCT_COLOR.getGreen(), PCT_COLOR.getBlue());
        return "<html><span style='color:" + tierHex + ";'><b>" + escape(r.tierLabel) + "</b></span>"
            + " <span style='color:" + pctHex + ";'>(" + String.format(Locale.US, "%.1f", r.progressPct) + "%)</span></html>";
    }

    private static String escape(String s)
    {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
