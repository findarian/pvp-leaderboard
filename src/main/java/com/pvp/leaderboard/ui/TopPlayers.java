package com.pvp.leaderboard.ui;

import lombok.*;
import com.google.gson.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import java.util.List;
import static com.pvp.leaderboard.ui.Ui.*;

/**
 * "Top players" view — a scrollable Top 100 leaderboard mirroring the website,
 * one row per account: <code>#rank &nbsp; Player Name &nbsp; Rank</code>, sized
 * to the largest font the sidepanel width allows.
 *
 * <p>Reads the exact same cached S3 artifact the website uses
 * ({@code /leaderboard[_<bucket>].json}) via
 * {@link PvpApi#getLeaderboard(String)} — a single CDN call cached
 * ~1 hour. A bucket toggle ([Overall][NH][Veng][Multi][DMM][Tournament], NH
 * by default) matches the website's leaderboard tabs and the sibling "What
 * are the ranks" view. Unlike the website, only the account's first/primary
 * name is shown.
 *
 * <p>Row text: the player name is the row's primary element; the rank number
 * and tier columns render one point below it, bold. The rank column is sized
 * to {@code "#100"}, the widest rank number a top-100 list can show, so the
 * names line up down the list. The font cap (20 pt) only stops a very wide
 * panel turning 100 rows into an endless scroll; width is meant to bind at
 * any realistic sidepanel size.
 */
public class TopPlayers extends RowsPanel
{
    private static final Color RANKNUM_COLOR = new Color(0xcfa84a); // muted gold

    /** One leaderboard row, derived purely from the cached JSON. */
    @AllArgsConstructor
    static final class Row
    {
        final int worldRank;
        final String name;
        final String rankFamily; // e.g. "Rune" / "3rd Age" — drives the colour
        final String tierLabel;  // e.g. "Rune 1" / "3rd Age"
    }

    private final PvpApi pvpApi;

    private final JPanel bar;
    private final JPanel listPanel = new JPanel();
    private final JLabel statusLabel = new JLabel();

    /** Rank number, name and tier: 9-20 pt; a row loses 2 x 4 px padding and two 6 px gaps. */
    private final RowTextFit fit = new RowTextFit(9, 20, 20, "#100",
        new int[]{Font.BOLD, Font.PLAIN, Font.BOLD}, new int[]{1, 0, 1});

    private volatile String selectedBucket = "nh";

    public TopPlayers(PvpApi pvpApi)
    {
        this.pvpApi = pvpApi;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        bar = left(maxH(bucketBar("topPlayersBucketBar", "topPlayersBucketBtn", 2, 3, BUCKETS, this::loadBucket), 52));
        add(bar);

        statusLabel.setName("topPlayersStatus");
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setBorder(pad(2, 6, 4, 6));
        add(statusLabel);

        listPanel.setName("topPlayersList");
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        left(listPanel);
        add(listPanel);

        styleBucketBar(bar, selectedBucket);
    }

    /** Lazy load entry-point — called when the view is revealed. */
    public void onShown()
    {
        loadBucket(selectedBucket);
    }

    /** {@code bucketKey} is a bar key or {@link #selectedBucket}: trimmed, lower case. */
    private void loadBucket(String bucketKey)
    {
        selectedBucket = bucketKey;
        styleBucketBar(bar, bucketKey);

        if (pvpApi == null)
        {
            showStatus("Unavailable");
            return;
        }

        // Single source of truth for caching: PvpApi.getLeaderboard
        // is backed by the shared 60-minute shard cache (same hourly refresh
        // the website's CDN object uses). We deliberately keep NO long-lived
        // copy here — a per-panel cache would shadow that TTL and serve stale
        // rows for the whole session. Each tab visit / bucket toggle re-asks;
        // within the hour it's a cache hit (no network), after the hour it
        // refetches.
        CompletableFuture<JsonObject> future = pvpApi.getLeaderboard(bucketKey);
        if (future == null)
        {
            showStatus("Unavailable");
            return;
        }

        // Already cached (future completed) → render immediately, no flash.
        JsonObject ready = future.getNow(null);
        if (ready != null)
        {
            renderTop(ready);
            return;
        }

        showStatus("Loading…");
        future.thenAccept(data -> SwingUtilities.invokeLater(() ->
        {
            if (bucketKey.equals(selectedBucket)) renderTop(data);
        })).exceptionally(ex ->
        {
            SwingUtilities.invokeLater(() ->
            {
                if (bucketKey.equals(selectedBucket)) showStatus("Unavailable");
            });
            return null;
        });
    }

    /** Both callers have just checked that {@code data} is for the selected bucket. */
    private void renderTop(JsonObject data)
    {
        if (data != null && data.has("players"))
        {
            renderRows(parseTop(data, 100));
        }
        else
        {
            showStatus("Unavailable");
        }
    }

    private void showStatus(String text)
    {
        listPanel.removeAll();
        fit.rows.clear();
        statusLabel.setText(text);
        listPanel.revalidate();
        listPanel.repaint();
        revalidateUp();
    }

    private void renderRows(List<Row> rows)
    {
        listPanel.removeAll();
        fit.rows.clear();
        if (rows.isEmpty())
        {
            showStatus("No ranked players yet for this bucket.");
            return;
        }
        statusLabel.setText("Showing top " + rows.size());
        for (Row r : rows)
        {
            listPanel.add(buildRow(r));
        }
        // The rows that decide the fit only exist now, and the panel itself
        // hasn't resized, so nothing else would trigger it.
        fit.restart(getWidth());
        listPanel.revalidate();
        listPanel.repaint();
        revalidateUp();
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
    private void revalidateUp()
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
        var rankNum = new JLabel("#" + r.worldRank);
        rankNum.setName("topPlayerRankNum");
        rankNum.setForeground(RANKNUM_COLOR);

        var name = new JLabel(r.name);
        name.setName("topPlayerName");
        name.setForeground(Color.WHITE);

        // Rank tier only — the in-tier "% to next" used to sit here, but it
        // was the widest thing in the row and bought the least: it pushed
        // the auto-fit down and squeezed the player name.
        var tier = new JLabel(r.tierLabel);
        tier.setName("topPlayerTier");
        tier.setForeground(RankUtils.getRankColor(r.rankFamily));
        tier.setHorizontalAlignment(SwingConstants.RIGHT);

        return fit.row("topPlayerRow", rankNum, name, tier);
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
        fit.refit(getWidth());
        super.doLayout();
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
     * joins them all).
     *
     * <p>Pure + null-safe so it's unit-testable without Swing/network.
     */
    static List<Row> parseTop(JsonObject data, int limit)
    {
        Map<String, Agg> byAccount = new LinkedHashMap<>();
        for (JsonElement el : JsonLenient.optArray(data, "players"))
        {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            double mmr = JsonLenient.optDouble(p, "mmr", JsonLenient.optDouble(p, "MMR", Double.NaN));
            String account = accountOf(p);
            if (!Double.isFinite(mmr) || account == null) continue;

            Agg agg = byAccount.computeIfAbsent(account, k -> new Agg());
            addNames(agg.names, p);
            if (mmr > agg.mmr)
            {
                agg.mmr = mmr;
                agg.rank = strOrNull(p, "rank");
                agg.division = JsonLenient.optInt(p, "division", 0);
            }
        }

        List<Agg> ranked = new ArrayList<>(byAccount.values());
        ranked.sort((a, b) -> Double.compare(b.mmr, a.mmr));

        List<Row> rows = new ArrayList<>();
        int n = Math.min(Math.max(0, limit), ranked.size());
        for (int i = 0; i < n; i++)
        {
            Agg a = ranked.get(i);
            String rankFamily = a.rank;
            int division = a.division;
            if (rankFamily == null)
            {
                RankInfo ri = RankUtils.toRankInfo(a.mmr);
                rankFamily = ri.rank;
                division = ri.division;
            }
            rows.add(new Row(i + 1, a.names.isEmpty() ? "Unknown" : a.names.iterator().next(), rankFamily,
                "3rd Age".equals(rankFamily) ? rankFamily : rankFamily + " " + division));
        }
        return rows;
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
            for (JsonElement n : JsonLenient.optArray(p, "player_names"))
            {
                if (!n.isJsonNull() && !n.getAsString().trim().isEmpty()) out.add(n.getAsString());
            }
        }
        catch (RuntimeException ignore) { }
    }

    /** The key's text, or {@code null} when absent, not text, or blank. */
    private static String strOrNull(JsonObject p, String key)
    {
        String s = JsonLenient.optString(p, key);
        return s.trim().isEmpty() ? null : s;
    }
}
