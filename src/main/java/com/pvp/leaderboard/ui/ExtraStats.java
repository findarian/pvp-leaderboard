package com.pvp.leaderboard.ui;

import lombok.*;
import com.google.gson.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.text.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import javax.swing.*;
import lombok.extern.slf4j.*;
import java.util.List;
import static com.pvp.leaderboard.ui.Ui.*;
import static com.pvp.leaderboard.util.JsonLenient.*;
import static java.awt.RenderingHints.*;
import static java.lang.Math.*;
import static com.pvp.leaderboard.util.RankUtils.*;

/**
 * The "Additional Stats" box (Overall): the highest rank defeated, the lowest
 * rank lost to, and the Tier Graph button.
 *
 * <p>Its height is capped at its content's ({@link CapPanel}): the content is
 * pinned to {@link BorderLayout#NORTH}, and {@code BorderLayout} otherwise
 * reports an unbounded maximum height, so the dashboard's vertical
 * {@code BoxLayout} would hand it all of the leftover vertical slack —
 * stretching the box far below its content (most visible right after login,
 * when the rank/advanced sections below are short). Width stays flexible.
 */
@Slf4j
public class ExtraStats extends CapPanel {
    private final JLabel highestRankLabel = new JLabel("-");
    private final JLabel highestTimeLabel = new JLabel("-");
    private final JLabel lowestRankLabel = new JLabel("-");
    private final JLabel lowestTimeLabel = new JLabel("-");

    private final SimpleDateFormat fmt = new SimpleDateFormat("M/d/yyyy, h:mm:ss a");
    private JsonArray matches = new JsonArray();

    private final PvpApi pvpApi;
    @Setter private String playerName;
    @Setter private String acctSha;

    private static final int DAILY_LIMIT = 3;
    private static final int FETCH_PAGE = 15;
    private static final int MAX_FETCHED = 200;
    private static final long LOOKBACK_MS = 24L * 60 * 60 * 1000;

    private final Map<String, CachedMatches> matchCache = new HashMap<>();

    private final Set<String> dailyLookups = new HashSet<>();
    private LocalDate lookupDate = LocalDate.now();

    private static class CachedMatches {
        final JsonArray matches;
        final long cachedAtMs;
        final double latestWhen;

        CachedMatches(JsonArray matches, long cachedAtMs) {
            this.matches = matches;
            this.cachedAtMs = cachedAtMs;
            double max = 0;
            for (int i = 0; i < matches.size(); i++) {
                max = Math.max(max, optDouble(matches.get(i).getAsJsonObject(), "when", 0));
            }
            latestWhen = max;
        }
    }

    private final JButton tierGraphBtn;
    private final JLabel graphInfo;

    public ExtraStats(PvpApi pvpApi) {
        this.pvpApi = pvpApi;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Additional Stats"));

        var content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        var statsCol = new JPanel();
        statsCol.setLayout(new BoxLayout(statsCol, BoxLayout.Y_AXIS));
        left(statsCol);
        statsCol.add(makeStat("Highest Rank Defeated", highestRankLabel, highestTimeLabel));
        statsCol.add(vgap(6));
        statsCol.add(makeStat("Lowest Rank Lost To", lowestRankLabel, lowestTimeLabel));
        content.add(statsCol);
        content.add(vgap(8));

        tierGraphBtn = new JButton(getGraphText());
        tierGraphBtn.addActionListener(e -> onGraphClick());
        content.add(tierGraphBtn);
        content.add(vgap(4));

        graphInfo = new JLabel(
                "<html>Free tier allows 3 unique player searches per day due to "
                + "backend cost, reach out to Toyco on discord to get more access</html>");
        graphInfo.setForeground(Color.GRAY);
        plain(graphInfo, 14f);
        content.add(graphInfo);

        add(content, BorderLayout.NORTH);
    }

    public void setMatches(JsonArray newMatches) {
        matches = (newMatches != null) ? newMatches : new JsonArray();
        updateExtra();
    }

    /**
     * The tier-graph bucket selector's wire keys, in display order. G-11
     * appended {@code tournament} so the selector matches the website's
     * Performance Overview; the "Overall" series is unchanged (the backend
     * adds Tournament to Overall at 10% ON TOP of the existing 100% —
     * {@code backend/core/buckets.with_tournament} — so the local
     * approximation in {@link TierGraphPanel#set} is untouched here).
     */
    static String[] bucketKeys() {
        return new String[]{"overall", "nh", "veng", "multi", "dmm", "tournament"};
    }

    /** Display labels for {@link #bucketKeys()}, same order. */
    static String[] bucketLabels() {
        return BUCKETS;
    }

    private String getGraphText() {
        resetDay();
        return "Tier Graph " + (DAILY_LIMIT - dailyLookups.size()) + "/" + DAILY_LIMIT + " remaining";
    }

    private void resetDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(lookupDate)) {
            lookupDate = today;
            dailyLookups.clear();
        }
    }

    private void onGraphClick() {
        resetDay();
        if (pvpApi == null || playerName == null || playerName.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No player loaded. Please search for a player first.",
                    "No Player", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        boolean alreadyCached = matchCache.containsKey(key);
        if (!alreadyCached && dailyLookups.size() >= DAILY_LIMIT
                && !dailyLookups.contains(key)) {
            JOptionPane.showMessageDialog(this,
                    "You have used all 3 unique new player tier graph lookups for today.\n"
                    + "Previously cached players can still be reopened.\n"
                    + "This limit resets daily.",
                    "Daily Limit Reached", JOptionPane.WARNING_MESSAGE);
            return;
        }

        openGraph(key, alreadyCached);
    }

    private void openGraph(String cacheKey, boolean alreadyCached) {
        JDialog dialog = dialog(this, "Tier Graph Over Time - " + playerName, 700, 500);

        var mainPanel = new JPanel(new BorderLayout(0, 4));

        String[] bucketKeys = bucketKeys();
        String[] bucketLabels = bucketLabels();
        var bucketBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        var bucketBtns = new JButton[bucketKeys.length];
        var graph = new TierGraphPanel();
        // The dialog paints only after this method returns, so the status is
        // chosen up front; the card layout already shows its first card.
        var statusLabel = new JLabel(alreadyCached ? "Updating with latest matches..." : "Loading all matches...");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        final String[] dialogBucket = { "overall" };
        final JsonArray[] dialogMatches = { new JsonArray() };

        for (int i = 0; i < bucketKeys.length; i++) {
            final String key = bucketKeys[i];
            bucketBtns[i] = new JButton(bucketLabels[i]);
            bucketBtns[i].setEnabled(alreadyCached);
            bucketBtns[i].addActionListener(e -> {
                dialogBucket[0] = key;
                styleBtns(bucketBtns, bucketKeys, key);
                graph.set(dialogMatches[0], key);
            });
            bucketBar.add(bucketBtns[i]);
        }
        styleBtns(bucketBtns, bucketKeys, dialogBucket[0]);

        mainPanel.add(bucketBar, BorderLayout.NORTH);

        var centerWrapper = new JPanel(new CardLayout());
        centerWrapper.add(statusLabel, "loading");
        centerWrapper.add(graph, "graph");

        mainPanel.add(centerWrapper, BorderLayout.CENTER);
        dialog.add(mainPanel);
        dialog.setVisible(true);

        Runnable showGraph = () -> SwingUtilities.invokeLater(() -> {
            JsonArray current = matchCache.containsKey(cacheKey)
                    ? matchCache.get(cacheKey).matches : new JsonArray();
            dialogMatches[0] = current;
            for (JButton btn : bucketBtns) btn.setEnabled(true);
            graph.set(current, dialogBucket[0]);
            ((CardLayout) centerWrapper.getLayout()).show(centerWrapper, "graph");
            tierGraphBtn.setText(getGraphText());
        });

        if (alreadyCached) {
            CachedMatches cached = matchCache.get(cacheKey);
            long now = System.currentTimeMillis();
            double cutoffSec = Math.max(cached.cachedAtMs / 1000.0, (now - LOOKBACK_MS) / 1000.0);
            var newMatches = new JsonArray();
            fetchPage(null, newMatches, cached.latestWhen, cutoffSec, 0, () -> {
                newMatches.addAll(cached.matches);
                matchCache.put(cacheKey, new CachedMatches(newMatches, System.currentTimeMillis()));
                showGraph.run();
            });
        } else {
            pvpApi.getAllMatches(acctSha, playerName).thenAccept(allMatches -> {
                matchCache.put(cacheKey, new CachedMatches(allMatches, System.currentTimeMillis()));
                dailyLookups.add(cacheKey);
                showGraph.run();
            }).exceptionally(ex -> {
                log.warn("Failed to fetch all matches for tier graph", ex);
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Failed to load matches.");
                    for (JButton btn : bucketBtns) btn.setEnabled(true);
                });
                return null;
            });
        }
    }

    /**
     * Pages newest-first through the player's matches newer than the cache
     * ({@code cachedWhen}) and the lookback ({@code cutoffSec}), up to
     * {@link #MAX_FETCHED} in all; a recursion only starts below
     * that cap. An empty page (or no {@code matches} array) ends it.
     */
    private void fetchPage(String nextToken, JsonArray accumulator,
                                      double cachedWhen, double cutoffSec,
                                      int totalFetched, Runnable onDone) {
        pvpApi.getMatches(acctSha, playerName, nextToken, FETCH_PAGE, true)
                .thenAccept(response -> {
                    JsonArray page = optArray(response, "matches");
                    boolean caughtUp = false;
                    int added = 0;
                    for (JsonElement e : page) {
                        JsonObject m = e.getAsJsonObject();
                        double when = optDouble(m, "when", 0);
                        if (when <= cachedWhen || when < cutoffSec) {
                            caughtUp = true;
                            break;
                        }
                        accumulator.add(m);
                        if (totalFetched + ++added >= MAX_FETCHED) {
                            caughtUp = true;
                            break;
                        }
                    }
                    if (caughtUp) {
                        onDone.run();
                        return;
                    }
                    String next = added > 0 ? optString(response, "next_token", null) : null;
                    if (next != null && !next.isEmpty()) {
                        fetchPage(next, accumulator, cachedWhen, cutoffSec,
                                totalFetched + added, onDone);
                    } else {
                        onDone.run();
                    }
                }).exceptionally(ex -> {
                    log.warn("Incremental match fetch failed", ex);
                    onDone.run();
                    return null;
                });
    }

    private void styleBtns(JButton[] btns, String[] keys, String selected) {
        Color selBg = SELECTED_BG;
        Color selFg = Color.WHITE;
        Color unselFg = Color.GRAY;
        for (int i = 0; i < btns.length; i++) {
            if (keys[i].equals(selected)) {
                btns[i].setBackground(selBg);
                btns[i].setForeground(selFg);
            } else {
                btns[i].setBackground(null);
                btns[i].setForeground(unselFg);
            }
        }
    }

    /** A match whose mu moves more than this from the bucket's previous
     *  point is treated as bad data and skipped (the point is still
     *  plotted at the previous value). */
    private static final int MAX_DELTA = 250;

    /**
     * The "Overall" history for the tier graph — one Overall mu per match,
     * in the given (already sorted) order. G-16 (operator decision
     * 2026-09-22): the formula is the backend's, mirrored in
     * {@link OverallMmr} — the four standard buckets weighted
     * {@code 0.55 / 0.30 / 0.05 / 0.10} (1000 until a bucket has a game)
     * plus {@code 0.10 × tournament} on top, where the tournament term
     * exists only once a tournament match with a rating has been seen; a
     * history without one plots exactly the legacy Overall. A point that
     * carries no usable mu (or jumps more than {@link #MAX_DELTA})
     * leaves its bucket where it was. Package-private for the tests.
     */
    static List<Double> muSeries(List<JsonObject> sortedItems) {
        var acc = new OverallMmr();
        List<Double> out = new ArrayList<>();
        for (JsonObject m : sortedItems) {
            String b = optString(m, "bucket").toLowerCase(Locale.ROOT);
            if (OverallMmr.isRated(b)) {
                Double prev = acc.lastMu(b);
                // A standard bucket starts from the 1000 default exactly as
                // before (a delta-only first point resolves against it and
                // the outlier guard measures from it). The tournament bucket
                // has no local seed: its first point must carry a rating of
                // its own, or the term stays absent.
                Double baseline = prev != null ? prev
                        : (OverallMmr.TOURNEY_KEY.equals(b) ? null : OverallMmr.DEFAULT_MU);
                double mu = resolveMu(m, baseline);
                if (Double.isFinite(mu) && (baseline == null || abs(mu - baseline) <= MAX_DELTA)) {
                    acc.record(b, mu);
                }
            }
            out.add(acc.overall());
        }
        return out;
    }

    private JPanel makeStat(String title, JLabel value, JLabel time) {
        var p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        var t = new JLabel(title);
        bold(t, 16f);
        bold(value, 18f);
        time.setForeground(new Color(230, 200, 80));
        p.add(t);
        p.add(vgap(4));
        p.add(value);
        p.add(time);
        return p;
    }

    private void updateExtra() {
        String bestRank = null; Long bestTs = null;
        String worstRank = null; Long worstTs = null;

        for (int i = 0; i < matches.size(); i++) {
            JsonObject m = matches.get(i).getAsJsonObject();
            String result = optString(m, "result").toLowerCase(Locale.ROOT);
            long ts = (long) floor(optDouble(m, "when", 0));

            String oppRank = rankLabel(optString(m, "opponent_rank"), optInt(m, "opponent_division", 0));
            if (oppRank.isEmpty()) continue;

            if ("win".equals(result)) {
                if (bestRank == null || getRankOrder(oppRank) > getRankOrder(bestRank)) {
                    bestRank = oppRank; bestTs = ts;
                }
            } else if ("loss".equals(result)) {
                if (worstRank == null || getRankOrder(oppRank) < getRankOrder(worstRank)) {
                    worstRank = oppRank; worstTs = ts;
                }
            }
        }

        highestRankLabel.setText(bestRank != null ? bestRank : "-");
        highestTimeLabel.setText(bestTs != null ? fmt.format(new Date(bestTs * 1000)) : "-");
        lowestRankLabel.setText(worstRank != null ? worstRank : "-");
        lowestTimeLabel.setText(worstTs != null ? fmt.format(new Date(worstTs * 1000)) : "-");
    }

    /** The first of the match's absolute mu keys that is present, else
     *  {@code prevMu} plus the rating change's delta, else NaN. */
    private static double resolveMu(JsonObject m, Double prevMu) {
        for (String k : new String[]{"player_mmr_after", "player_new_mmr", "player_mmr"}) {
            double mu = optDouble(m, k, Double.NaN);
            if (!Double.isNaN(mu)) return mu;
        }
        double delta = optDouble(optObject(m, "rating_change"), "mmr_delta", Double.NaN);
        return Double.isNaN(delta) || prevMu == null ? Double.NaN : prevMu + delta;
    }

    private static String rankLabel(String rank, int division) {
        if (rank == null || rank.isEmpty()) return "";
        return division > 0 ? (rank + " " + division) : rank;
    }

    /**
     * The Tier Graph: the rank labels drawn in the left 80 px and the plot in
     * the rest, each through its own clipped graphics exactly as two side-by-side
     * panels would be. The dialog is a fixed size and never packed, so the
     * panel needs no preferred size.
     */
    static class TierGraphPanel extends JPanel {
        double lo = 0.0, hi = 24.0;
        List<Double> series = new ArrayList<>();

        /**
         * Plots {@code bucket}'s history from {@code allMatches}: the visible
         * range is whole rank families (multiples of 3) around the points, and
         * each point is a match's 0-100 height in it. A bucket's point holds
         * the previous rating on a match with no usable rating or a jump over
         * {@link #MAX_DELTA}, and is 1000 before the first one.
         */
        void set(JsonArray allMatches, String bucket) {
            List<JsonObject> items = new ArrayList<>();
            for (int i = 0; i < allMatches.size(); i++) items.add(allMatches.get(i).getAsJsonObject());
            items = items.stream()
                    .filter(m -> bucket.equals("overall") || bucket.equalsIgnoreCase(optString(m, "bucket")))
                    .sorted(Comparator.comparingDouble(m -> optDouble(m, "when", 0)))
                    .collect(Collectors.toList());

            List<Double> rawY = new ArrayList<>();
            if ("overall".equals(bucket)) {
                for (double overallMu : muSeries(items)) {
                    rawY.add(tierValueOf(overallMu));
                }
            } else {
                Double prevMu = null;
                for (JsonObject m : items) {
                    double mu = resolveMu(m, prevMu);
                    if (Double.isFinite(mu) && (prevMu == null || abs(mu - prevMu) <= MAX_DELTA)) prevMu = mu;
                    rawY.add(tierValueOf(prevMu != null ? prevMu : 1000.0));
                }
            }

            // Every point is finite: OverallMmr records only finite mu, and the tier maths clamps.
            double minY = 24.0, maxY = 0.0;
            for (double y : rawY) {
                minY = min(minY, y);
                maxY = Math.max(maxY, y);
            }
            double low = Math.max(0, (int) floor(minY / 3.0) * 3);
            double high = min(24, (int) ceil(maxY / 3.0) * 3);
            if (high <= low) high = min(24, low + 3);

            List<Double> points = new ArrayList<>();
            double span = Math.max(1e-6, high - low);
            for (double y : rawY) {
                points.add(Math.max(0.0, min(100.0, ((y - low) / span) * 100.0)));
            }
            lo = low;
            hi = high;
            series = points;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight();
            paintLabels((Graphics2D) g.create(0, 0, 80, h), h);
            paintPlot((Graphics2D) g.create(80, 0, w - 80, h), w - 80, h);
        }

        private void paintLabels(Graphics2D g2, int h) {
            g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
            int innerH = Math.max(1, h - 40);
            int end = (int) ceil(hi);
            for (int gi = (int) floor(lo); gi <= end; gi++) {
                double frac = (gi - lo) / Math.max(1e-6, (hi - lo));
                int y = 20 + innerH - (int) round(frac * innerH);
                g2.setColor(new Color(0x787878));
                g2.drawLine(0, y, 80, y);
                String baseRank = THRESHOLDS[min(gi / 3 * 3, THRESHOLDS.length - 1)][0];
                if (gi % 3 == 0) {
                    g2.setColor(getRankColor(baseRank));
                    g2.drawString(baseRank.equals("3rd Age") ? baseRank : baseRank + " 3", 2, y + 5);
                }
            }
            g2.dispose();
        }

        private void paintPlot(Graphics2D g2, int w, int h) {
            g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
            int innerW = Math.max(1, w - 20);
            int innerH = Math.max(1, h - 40);

            g2.setColor(new Color(0x787878));
            int end = (int) ceil(hi);
            for (int gi = (int) floor(lo); gi <= end; gi++) {
                if (gi % 3 != 0) continue;
                double frac = (gi - lo) / Math.max(1e-6, (hi - lo));
                int y = 20 + innerH - (int) round(frac * innerH);
                g2.drawLine(0, y, innerW, y);
            }

            if (series.size() > 1) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f));
                for (int i = 0; i < series.size() - 1; i++) {
                    int x1 = i * innerW / (series.size() - 1);
                    int x2 = (i + 1) * innerW / (series.size() - 1);
                    int y1 = 20 + innerH - (int) round((series.get(i) / 100.0) * innerH);
                    int y2 = 20 + innerH - (int) round((series.get(i + 1) / 100.0) * innerH);
                    g2.drawLine(x1, y1, x2, y2);
                }
            } else {
                g2.setColor(Color.GRAY);
                g2.drawString("No tier data available", w / 2 - 60, h / 2);
            }
            g2.dispose();
        }
    }
}
