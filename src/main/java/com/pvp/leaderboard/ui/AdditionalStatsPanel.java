package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.util.RankUtils;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
public class AdditionalStatsPanel extends JPanel {
    private final JLabel highestRankLabel = new JLabel("-");
    private final JLabel highestTimeLabel = new JLabel("-");
    private final JLabel lowestRankLabel = new JLabel("-");
    private final JLabel lowestTimeLabel = new JLabel("-");

    private final SimpleDateFormat fmt = new SimpleDateFormat("M/d/yyyy, h:mm:ss a");
    private String selectedBucket = "overall";
    private JsonArray matches = new JsonArray();

    private PvPDataService pvpDataService;
    private String playerName;
    private String acctSha;

    private static final int MAX_UNIQUE_PLAYERS_PER_DAY = 3;
    private static final int INCREMENTAL_PAGE_SIZE = 15;
    private static final int MAX_INCREMENTAL_MATCHES = 200;
    private static final long MAX_LOOKBACK_MS = 24L * 60 * 60 * 1000;

    private final Map<String, CachedMatches> matchCache = new HashMap<>();

    private final Set<String> dailyNewLookups = new HashSet<>();
    private LocalDate dailyLookupDate = LocalDate.now();

    private static class CachedMatches {
        final JsonArray matches;
        final long cachedAtMs;
        final double newestMatchWhen;

        CachedMatches(JsonArray matches, long cachedAtMs) {
            this.matches = matches;
            this.cachedAtMs = cachedAtMs;
            double max = 0;
            for (int i = 0; i < matches.size(); i++) {
                JsonObject m = matches.get(i).getAsJsonObject();
                if (m.has("when") && !m.get("when").isJsonNull()) {
                    max = Math.max(max, m.get("when").getAsDouble());
                }
            }
            this.newestMatchWhen = max;
        }
    }

    private final JButton tierGraphBtn;
    private final JLabel tierGraphInfo;

    public AdditionalStatsPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Additional Stats"));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel statsCol = new JPanel();
        statsCol.setLayout(new BoxLayout(statsCol, BoxLayout.Y_AXIS));
        statsCol.setAlignmentX(LEFT_ALIGNMENT);
        statsCol.add(makeStat("Highest Rank Defeated", highestRankLabel, highestTimeLabel));
        statsCol.add(Box.createVerticalStrut(6));
        statsCol.add(makeStat("Lowest Rank Lost To", lowestRankLabel, lowestTimeLabel));
        content.add(statsCol);
        content.add(Box.createVerticalStrut(8));

        tierGraphBtn = new JButton(getTierGraphButtonText());
        tierGraphBtn.setAlignmentX(LEFT_ALIGNMENT);
        tierGraphBtn.addActionListener(e -> onTierGraphClicked());
        content.add(tierGraphBtn);
        content.add(Box.createVerticalStrut(4));

        tierGraphInfo = new JLabel(
                "<html>Free tier allows 3 unique player searches per day due to "
                + "backend cost, reach out to Toyco on discord to get more access</html>");
        tierGraphInfo.setForeground(Color.GRAY);
        tierGraphInfo.setFont(tierGraphInfo.getFont().deriveFont(Font.PLAIN, 14f));
        tierGraphInfo.setAlignmentX(LEFT_ALIGNMENT);
        content.add(tierGraphInfo);

        add(content, BorderLayout.NORTH);
    }

    public void setPvPDataService(PvPDataService service) {
        this.pvpDataService = service;
    }

    public void setPlayerName(String name) {
        this.playerName = name;
    }

    public void setAcctSha(String sha) {
        this.acctSha = sha;
    }

    public void setMatches(JsonArray newMatches) {
        this.matches = (newMatches != null) ? newMatches : new JsonArray();
        updateAdditionalStats();
    }

    public void setBucket(String bucket) {
        this.selectedBucket = (bucket == null ? "overall" : bucket.toLowerCase(Locale.ROOT));
    }

    private String getTierGraphButtonText() {
        resetDayIfNeeded();
        int used = dailyNewLookups.size();
        int remaining = MAX_UNIQUE_PLAYERS_PER_DAY - used;
        return "Tier Graph " + remaining + "/" + MAX_UNIQUE_PLAYERS_PER_DAY + " remaining";
    }

    private void resetDayIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(dailyLookupDate)) {
            dailyLookupDate = today;
            dailyNewLookups.clear();
        }
    }

    private void onTierGraphClicked() {
        resetDayIfNeeded();
        if (pvpDataService == null || playerName == null || playerName.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No player loaded. Please search for a player first.",
                    "No Player", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        boolean alreadyCached = matchCache.containsKey(key);
        if (!alreadyCached && dailyNewLookups.size() >= MAX_UNIQUE_PLAYERS_PER_DAY
                && !dailyNewLookups.contains(key)) {
            JOptionPane.showMessageDialog(this,
                    "You have used all 3 unique new player tier graph lookups for today.\n"
                    + "Previously cached players can still be reopened.\n"
                    + "This limit resets daily.",
                    "Daily Limit Reached", JOptionPane.WARNING_MESSAGE);
            return;
        }

        showTierGraphDialog(key, alreadyCached);
    }

    private void showTierGraphDialog(String cacheKey, boolean alreadyCached) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Tier Graph Over Time - " + playerName, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setSize(700, 500);
        dialog.setLocationRelativeTo(owner);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 4));

        String[] bucketKeys = {"overall", "nh", "veng", "multi", "dmm"};
        String[] bucketLabels = {"Overall", "NH", "Veng", "Multi", "DMM"};
        JPanel bucketBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton[] bucketBtns = new JButton[bucketKeys.length];
        TierGraphLabelsPanel labelsPanel = new TierGraphLabelsPanel();
        TierGraphPlotPanel plotPanel = new TierGraphPlotPanel();
        JLabel statusLabel = new JLabel("Loading all matches...");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        final String[] dialogBucket = { selectedBucket };
        final JsonArray[] dialogMatches = { new JsonArray() };

        for (int i = 0; i < bucketKeys.length; i++) {
            final String key = bucketKeys[i];
            bucketBtns[i] = new JButton(bucketLabels[i]);
            bucketBtns[i].setEnabled(alreadyCached);
            bucketBtns[i].addActionListener(e -> {
                dialogBucket[0] = key;
                updateBucketBtnStyles(bucketBtns, bucketKeys, key);
                rebuildDialogSeries(dialogMatches[0], key, labelsPanel, plotPanel);
            });
            bucketBar.add(bucketBtns[i]);
        }
        updateBucketBtnStyles(bucketBtns, bucketKeys, dialogBucket[0]);

        mainPanel.add(bucketBar, BorderLayout.NORTH);

        JPanel graphPanel = new JPanel(new BorderLayout());
        labelsPanel.setPreferredSize(new Dimension(80, 400));
        plotPanel.setPreferredSize(new Dimension(580, 400));
        graphPanel.add(labelsPanel, BorderLayout.WEST);
        graphPanel.add(plotPanel, BorderLayout.CENTER);

        JPanel centerWrapper = new JPanel(new CardLayout());
        centerWrapper.add(statusLabel, "loading");
        centerWrapper.add(graphPanel, "graph");

        mainPanel.add(centerWrapper, BorderLayout.CENTER);
        dialog.add(mainPanel);
        dialog.setVisible(true);

        Runnable showGraph = () -> SwingUtilities.invokeLater(() -> {
            JsonArray current = matchCache.containsKey(cacheKey)
                    ? matchCache.get(cacheKey).matches : new JsonArray();
            dialogMatches[0] = current;
            for (JButton btn : bucketBtns) btn.setEnabled(true);
            rebuildDialogSeries(current, dialogBucket[0], labelsPanel, plotPanel);
            ((CardLayout) centerWrapper.getLayout()).show(centerWrapper, "graph");
            tierGraphBtn.setText(getTierGraphButtonText());
        });

        if (alreadyCached) {
            statusLabel.setText("Updating with latest matches...");
            ((CardLayout) centerWrapper.getLayout()).show(centerWrapper, "loading");
            CachedMatches cached = matchCache.get(cacheKey);
            long now = System.currentTimeMillis();
            double cutoffSec = Math.max(cached.cachedAtMs / 1000.0, (now - MAX_LOOKBACK_MS) / 1000.0);
            log.debug("[TierGraph] Incremental refresh for '{}': cachedMatches={}, newestWhen={}, cutoffSec={}, cacheAge={}s",
                    cacheKey, cached.matches.size(), cached.newestMatchWhen, cutoffSec,
                    (now - cached.cachedAtMs) / 1000);
            JsonArray newMatches = new JsonArray();
            fetchIncrementalPage(null, newMatches, cached.newestMatchWhen, cutoffSec, 0, () -> {
                log.debug("[TierGraph] Incremental done: {} new matches fetched", newMatches.size());
                JsonArray merged = new JsonArray();
                for (int i = 0; i < newMatches.size(); i++) merged.add(newMatches.get(i));
                for (int i = 0; i < cached.matches.size(); i++) merged.add(cached.matches.get(i));
                log.debug("[TierGraph] Merged total: {} matches", merged.size());
                matchCache.put(cacheKey, new CachedMatches(merged, System.currentTimeMillis()));
                showGraph.run();
            });
        } else {
            ((CardLayout) centerWrapper.getLayout()).show(centerWrapper, "loading");
            log.debug("[TierGraph] Full fetch for '{}', acctSha={}", playerName, acctSha != null);
            CompletableFuture<JsonArray> fetchFuture;
            if (acctSha != null && !acctSha.isEmpty()) {
                fetchFuture = pvpDataService.getAllPlayerMatchesByAcct(acctSha);
            } else {
                fetchFuture = pvpDataService.getAllPlayerMatches(playerName);
            }
            fetchFuture.thenAccept(allMatches -> {
                log.debug("[TierGraph] Full fetch complete: {} total matches for '{}'", allMatches.size(), playerName);
                matchCache.put(cacheKey, new CachedMatches(allMatches, System.currentTimeMillis()));
                dailyNewLookups.add(cacheKey);
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

    private void fetchIncrementalPage(String nextToken, JsonArray accumulator,
                                      double newestCachedWhen, double cutoffSec,
                                      int totalFetched, Runnable onDone) {
        if (totalFetched >= MAX_INCREMENTAL_MATCHES) {
            log.debug("[TierGraph] Incremental: hit max {} matches, stopping", MAX_INCREMENTAL_MATCHES);
            onDone.run();
            return;
        }
        log.debug("[TierGraph] Incremental: fetching page (totalSoFar={}, nextToken={}, acct={})",
                totalFetched, nextToken != null, acctSha != null);
        CompletableFuture<JsonObject> pageFuture;
        if (acctSha != null && !acctSha.isEmpty()) {
            pageFuture = pvpDataService.getPlayerMatchesByAcct(acctSha, nextToken, INCREMENTAL_PAGE_SIZE, true);
        } else {
            pageFuture = pvpDataService.getPlayerMatches(playerName, nextToken, INCREMENTAL_PAGE_SIZE, true);
        }
        pageFuture
                .thenAccept(response -> {
                    if (response == null || !response.has("matches")
                            || !response.get("matches").isJsonArray()) {
                        log.debug("[TierGraph] Incremental: null/empty response, stopping");
                        onDone.run();
                        return;
                    }
                    JsonArray page = response.getAsJsonArray("matches");
                    log.debug("[TierGraph] Incremental: page returned {} matches", page.size());
                    if (page.size() == 0) {
                        onDone.run();
                        return;
                    }
                    boolean caughtUp = false;
                    int added = 0;
                    for (int i = 0; i < page.size(); i++) {
                        JsonObject m = page.get(i).getAsJsonObject();
                        double when = m.has("when") && !m.get("when").isJsonNull()
                                ? m.get("when").getAsDouble() : 0;
                        if (when <= newestCachedWhen) {
                            log.debug("[TierGraph] Incremental: match when={} <= newestCached={}, caught up", when, newestCachedWhen);
                            caughtUp = true;
                            break;
                        }
                        if (when < cutoffSec) {
                            log.debug("[TierGraph] Incremental: match when={} < cutoff={}, stopping", when, cutoffSec);
                            caughtUp = true;
                            break;
                        }
                        accumulator.add(m);
                        added++;
                        if (totalFetched + added >= MAX_INCREMENTAL_MATCHES) {
                            caughtUp = true;
                            break;
                        }
                    }
                    if (caughtUp) {
                        log.debug("[TierGraph] Incremental: caught up after adding {} this page", added);
                        onDone.run();
                        return;
                    }
                    String next = response.has("next_token") && !response.get("next_token").isJsonNull()
                            ? response.get("next_token").getAsString() : null;
                    if (next != null && !next.isEmpty()) {
                        fetchIncrementalPage(next, accumulator, newestCachedWhen, cutoffSec,
                                totalFetched + added, onDone);
                    } else {
                        log.debug("[TierGraph] Incremental: no more pages, done with {} added", added);
                        onDone.run();
                    }
                }).exceptionally(ex -> {
                    log.warn("Incremental match fetch failed", ex);
                    onDone.run();
                    return null;
                });
    }

    private void updateBucketBtnStyles(JButton[] btns, String[] keys, String selected) {
        Color selBg = new Color(60, 60, 60);
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

    private void rebuildDialogSeries(JsonArray allMatches, String bucket,
                                     TierGraphLabelsPanel labelsPanel, TierGraphPlotPanel plotPanel) {
        log.debug("[TierGraph] rebuildDialogSeries: totalMatches={}, bucket='{}'", allMatches.size(), bucket);
        List<JsonObject> items = new ArrayList<>();
        for (int i = 0; i < allMatches.size(); i++) items.add(allMatches.get(i).getAsJsonObject());
        items = items.stream()
                .filter(m -> bucket.equals("overall") || bucket.equalsIgnoreCase(asStr(m, "bucket")))
                .sorted(Comparator.comparingDouble(m -> m.has("when") ? m.get("when").getAsDouble() : 0))
                .collect(Collectors.toList());
        log.debug("[TierGraph] After filter/sort for '{}': {} matches with MMR data", bucket, items.size());

        final int MAX_MMR_DELTA_PER_MATCH = 250;
        List<Double> rawY = new ArrayList<>();

        if ("overall".equals(bucket)) {
            final Map<String, Double> weights = new LinkedHashMap<>();
            weights.put("nh", 0.55); weights.put("veng", 0.30); weights.put("multi", 0.05); weights.put("dmm", 0.10);
            final Map<String, Double> last = new HashMap<>();
            weights.keySet().forEach(k -> last.put(k, 1000.0));

            for (JsonObject m : items) {
                String b = asStr(m, "bucket").toLowerCase(Locale.ROOT);
                if (weights.containsKey(b)) {
                    Double prev = last.get(b);
                    double mu = resolveMuFromMatch(m, prev);
                    if (Double.isFinite(mu)) {
                        if (!Double.isFinite(prev) || Math.abs(mu - prev) <= MAX_MMR_DELTA_PER_MATCH) {
                            last.put(b, mu);
                        }
                    }
                }
                double overallMu = 0.0;
                for (Map.Entry<String, Double> e : weights.entrySet()) {
                    overallMu += last.getOrDefault(e.getKey(), 1000.0) * e.getValue();
                }
                rawY.add(RankUtils.calculateContinuousTierValue(overallMu));
            }
        } else {
            Double prevMu = null;
            for (JsonObject m : items) {
                double mu = resolveMuFromMatch(m, prevMu);
                double chosen;
                if (Double.isFinite(mu)) {
                    if (prevMu != null && Double.isFinite(prevMu) && Math.abs(mu - prevMu) > MAX_MMR_DELTA_PER_MATCH) {
                        chosen = prevMu;
                    } else {
                        chosen = mu;
                        prevMu = mu;
                    }
                } else {
                    chosen = (prevMu != null) ? prevMu : 1000.0;
                }
                rawY.add(RankUtils.calculateContinuousTierValue(chosen));
            }
        }

        double minY = 24.0, maxYv = 0.0;
        for (Double yv : rawY) {
            if (yv != null && Double.isFinite(yv)) {
                minY = Math.min(minY, yv);
                maxYv = Math.max(maxYv, yv);
            }
        }
        if (!Double.isFinite(minY)) minY = 0.0;
        if (!Double.isFinite(maxYv)) maxYv = 24.0;
        double low = Math.max(0, (int) Math.floor(minY / 3.0) * 3);
        double high = Math.min(24, (int) Math.ceil(maxYv / 3.0) * 3);
        if (high <= low) high = Math.min(24, low + 3);

        labelsPanel.setVisibleRange(low, high);
        plotPanel.setVisibleRange(low, high);

        List<Double> series = new ArrayList<>();
        double span = Math.max(1e-6, high - low);
        for (Double yv : rawY) {
            double y = (yv != null && Double.isFinite(yv)) ? yv : low;
            series.add(Math.max(0.0, Math.min(100.0, ((y - low) / span) * 100.0)));
        }
        plotPanel.setSeries(series);
        log.debug("[TierGraph] Series built: {} data points, range [{}, {}]", series.size(), low, high);
        labelsPanel.repaint();
    }

    private JPanel makeStat(String title, JLabel value, JLabel time) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 16f));
        value.setFont(value.getFont().deriveFont(Font.BOLD, 18f));
        time.setForeground(new Color(230, 200, 80));
        p.add(t);
        p.add(Box.createVerticalStrut(4));
        p.add(value);
        p.add(time);
        return p;
    }

    private void updateAdditionalStats() {
        String bestRank = null; Long bestTs = null;
        String worstRank = null; Long worstTs = null;

        for (int i = 0; i < matches.size(); i++) {
            JsonObject m = matches.get(i).getAsJsonObject();
            String bucket = asStr(m, "bucket").toLowerCase(Locale.ROOT);
            if (!selectedBucket.equals("overall") && !bucket.equals(selectedBucket)) continue;

            String result = asStr(m, "result").toLowerCase(Locale.ROOT);
            long ts = m.has("when") ? (long) Math.floor(m.get("when").getAsDouble()) : 0;

            String oppRank = rankLabel(asStr(m, "opponent_rank"), asInt(m, "opponent_division"));
            if (oppRank.isEmpty()) continue;

            if ("win".equals(result)) {
                if (bestRank == null || RankUtils.getRankOrder(oppRank) > RankUtils.getRankOrder(bestRank)) {
                    bestRank = oppRank; bestTs = ts;
                }
            } else if ("loss".equals(result)) {
                if (worstRank == null || RankUtils.getRankOrder(oppRank) < RankUtils.getRankOrder(worstRank)) {
                    worstRank = oppRank; worstTs = ts;
                }
            }
        }

        highestRankLabel.setText(bestRank != null ? bestRank : "-");
        highestTimeLabel.setText(bestTs != null ? fmt.format(new Date(bestTs * 1000)) : "-");
        lowestRankLabel.setText(worstRank != null ? worstRank : "-");
        lowestTimeLabel.setText(worstTs != null ? fmt.format(new Date(worstTs * 1000)) : "-");
    }

    private static double resolveMuFromMatch(JsonObject m, Double prevMu) {
        try {
            if (m.has("player_mmr_after") && !m.get("player_mmr_after").isJsonNull()) {
                return m.get("player_mmr_after").getAsDouble();
            }
            if (m.has("player_new_mmr") && !m.get("player_new_mmr").isJsonNull()) {
                return m.get("player_new_mmr").getAsDouble();
            }
            if (m.has("player_mmr") && !m.get("player_mmr").isJsonNull()) {
                return m.get("player_mmr").getAsDouble();
            }
            if (m.has("rating_change") && m.get("rating_change").isJsonObject()) {
                JsonObject rc = m.getAsJsonObject("rating_change");
                if (rc.has("mmr_delta") && !rc.get("mmr_delta").isJsonNull() && prevMu != null) {
                    return prevMu + rc.get("mmr_delta").getAsDouble();
                }
            }
        } catch (Exception ignore) {}
        return Double.NaN;
    }

    private static String asStr(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : "";
    }

    private static int asInt(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0; } catch (Exception e) { return 0; }
    }

    private static String rankLabel(String rank, int division) {
        if (rank == null || rank.isEmpty()) return "";
        return division > 0 ? (rank + " " + division) : rank;
    }

    static class TierGraphLabelsPanel extends JPanel {
        double vMin = 0.0, vMax = 24.0;
        void setVisibleRange(double a, double b) { this.vMin = a; this.vMax = b; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int top = 20, bottom = 20;
            int innerH = Math.max(1, h - top - bottom);
            int start = (int) Math.floor(vMin);
            int end = (int) Math.ceil(vMax);
            for (int gi = start; gi <= end; gi++) {
                double frac = (gi - vMin) / Math.max(1e-6, (vMax - vMin));
                int y = top + innerH - (int) Math.round(frac * innerH);
                g2.setColor(new Color(120, 120, 120));
                g2.drawLine(0, y, w, y);
                int base = gi / 3;
                int off = gi % 3;
                String baseRank = RankUtils.THRESHOLDS[Math.min(base * 3, RankUtils.THRESHOLDS.length - 1)][0];
                if (off == 0) {
                    String label = baseRank.equals("3rd Age") ? baseRank : baseRank + " 3";
                    g2.setColor(RankUtils.getRankColor(baseRank));
                    g2.drawString(label, 2, y + 5);
                }
            }
        }
    }

    static class TierGraphPlotPanel extends JPanel {
        double vMin = 0.0, vMax = 24.0;
        void setVisibleRange(double a, double b) { this.vMin = a; this.vMax = b; repaint(); }
        List<Double> series = new ArrayList<>();
        void setSeries(List<Double> s) { this.series = (s != null) ? s : new ArrayList<>(); repaint(); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int left = 0, bottom = 20, top = 20, right = 20;
            int innerW = Math.max(1, w - left - right);
            int innerH = Math.max(1, h - top - bottom);

            g2.setColor(new Color(120, 120, 120));
            int start = (int) Math.floor(vMin);
            int end = (int) Math.ceil(vMax);
            for (int gi = start; gi <= end; gi++) {
                if (gi % 3 != 0) continue;
                double frac = (gi - vMin) / Math.max(1e-6, (vMax - vMin));
                int y = top + innerH - (int) Math.round(frac * innerH);
                g2.drawLine(left, y, left + innerW, y);
            }

            if (series.size() > 1) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f));
                for (int i = 0; i < series.size() - 1; i++) {
                    int x1 = left + (i * innerW / Math.max(1, series.size() - 1));
                    int x2 = left + ((i + 1) * innerW / Math.max(1, series.size() - 1));
                    int y1 = top + innerH - (int) Math.round((series.get(i) / 100.0) * innerH);
                    int y2 = top + innerH - (int) Math.round((series.get(i + 1) / 100.0) * innerH);
                    g2.drawLine(x1, y1, x2, y2);
                }
            } else {
                g2.setColor(Color.GRAY);
                g2.drawString("No tier data available", w / 2 - 60, h / 2);
            }
        }
    }
}
