package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.PvPLeaderboardPlugin;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.service.CognitoAuthService;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.service.RankInfo;
import com.pvp.leaderboard.service.ShardRank;
import com.pvp.leaderboard.util.RankUtils;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

public class DashboardPanel extends PluginPanel
{
    private final PvPLeaderboardConfig config;
    private final PvPLeaderboardPlugin plugin; 
    private final PvPDataService pvpDataService;
    private final CognitoAuthService cognitoAuthService;

    // Sub-panels
    private LoginPanel loginPanel;
    private RankProgressPanel rankProgressPanel;
    private PerformanceStatsPanel performanceStatsPanel;
    private AdditionalStatsPanel extraStatsPanel;
    private WinRateChartPanel chartPanel;
    
    // Header elements
    private JLabel playerNameLabel;
    private JButton refreshButton;
    private JButton matchHistoryBtn;
    private JButton advancedToggle;
    private JButton rankTierToggle;
    private JScrollPane rankTierScrollPane;
    private JPanel statsContainer;

    // State
    private String currentMatchesPlayerId = null;
    private JsonArray allMatches = null;
    private long lastRefreshTimestamp = 0;
    private volatile int loadGeneration = 0;
    
    private static final int MATCHES_PAGE_SIZE = 100;
    private static final long STALE_DATA_THRESHOLD_MS = 60L * 60L * 1000L; // 1 hour
    
    public DashboardPanel(PvPLeaderboardConfig config, PvPLeaderboardPlugin plugin, PvPDataService pvpDataService, CognitoAuthService cognitoAuthService)
    {
        this.config = config;
        this.plugin = plugin;
        this.pvpDataService = pvpDataService;
        this.cognitoAuthService = cognitoAuthService;
        
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JPanel mainPanel = createMainPanel();
        disableNestedWheelScrolling(mainPanel);
        add(mainPanel, BorderLayout.CENTER);
    }
    
    private JPanel createMainPanel()
    {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        
        // 1. Community (kept simple here)
        JPanel communityBox = createCommunityBox();
        communityBox.setAlignmentX(LEFT_ALIGNMENT);
        mainPanel.add(communityBox);
        mainPanel.add(Box.createVerticalStrut(12));

        // 2. Matchmaking & Tournaments (coming soon)
        String matchmakingDefault = "<html><center>Matchmaking & Tournaments</center></html>";
        String matchmakingClicked = "<html><center>Coming soon, check<br>Discord for updates</center></html>";
        JButton matchmakingBtn = new JButton(matchmakingDefault);
        matchmakingBtn.setAlignmentX(LEFT_ALIGNMENT);
        matchmakingBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        matchmakingBtn.setEnabled(false);
        matchmakingBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!matchmakingBtn.isEnabled() && matchmakingBtn.getText().equals(matchmakingDefault)) {
                    matchmakingBtn.setText(matchmakingClicked);
                    Timer revertTimer = new Timer(15000, ev -> {
                        matchmakingBtn.setText(matchmakingDefault);
                    });
                    revertTimer.setRepeats(false);
                    revertTimer.start();
                }
            }
        });
        mainPanel.add(matchmakingBtn);
        mainPanel.add(Box.createVerticalStrut(8));

        rankTierToggle = new JButton("What are the ranks");
        rankTierToggle.setAlignmentX(LEFT_ALIGNMENT);
        rankTierToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        rankTierToggle.setHorizontalAlignment(SwingConstants.CENTER);
        rankTierToggle.setVisible(true);
        mainPanel.add(rankTierToggle);
        mainPanel.add(Box.createVerticalStrut(8));

        // --- Stats view (everything below the toggle) ---
        statsContainer = new JPanel();
        statsContainer.setLayout(new BoxLayout(statsContainer, BoxLayout.Y_AXIS));
        statsContainer.setAlignmentX(LEFT_ALIGNMENT);

        // 3. Search box
        loginPanel = new LoginPanel(cognitoAuthService, this::loadMatchHistory, this::onLoginStateChanged);
        loginPanel.setAlignmentX(LEFT_ALIGNMENT);
        statsContainer.add(loginPanel);
        statsContainer.add(Box.createVerticalStrut(4));

        // 4. Login button (separate from search box)
        JButton loginBtn = loginPanel.getLoginButton();
        loginBtn.setAlignmentX(LEFT_ALIGNMENT);
        loginBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        statsContainer.add(loginBtn);
        statsContainer.add(Box.createVerticalStrut(16));

        // 5. Player name label
        playerNameLabel = new JLabel("");
        playerNameLabel.setFont(playerNameLabel.getFont().deriveFont(Font.BOLD, 18f));
        playerNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        playerNameLabel.setAlignmentX(LEFT_ALIGNMENT);
        playerNameLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        statsContainer.add(playerNameLabel);
        statsContainer.add(Box.createVerticalStrut(4));

        // Refresh button (hidden until a player is searched)
        refreshButton = new JButton("Refresh");
        refreshButton.setAlignmentX(LEFT_ALIGNMENT);
        refreshButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        refreshButton.setHorizontalAlignment(SwingConstants.CENTER);
        refreshButton.setVisible(false);
        refreshButton.addActionListener(e -> handleRefresh());
        statsContainer.add(refreshButton);
        statsContainer.add(Box.createVerticalStrut(24));
        
        // 4. Additional Stats (above rank progress)
        extraStatsPanel = new AdditionalStatsPanel();
        extraStatsPanel.setPvPDataService(pvpDataService);
        extraStatsPanel.setVisible(false);
        extraStatsPanel.setAlignmentX(LEFT_ALIGNMENT);
        try { extraStatsPanel.setBucket("overall"); } catch (Exception ignore) {}
        statsContainer.add(extraStatsPanel);
        statsContainer.add(Box.createVerticalStrut(12));

        // 5. Rank Progress
        rankProgressPanel = new RankProgressPanel();
        rankProgressPanel.setAlignmentX(LEFT_ALIGNMENT);
        statsContainer.add(rankProgressPanel);
        statsContainer.add(Box.createVerticalStrut(12));

        // 6. Match History button (standalone, below progress bars, hidden until search)
        matchHistoryBtn = new JButton("Popout Match History");
        matchHistoryBtn.setAlignmentX(LEFT_ALIGNMENT);
        matchHistoryBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        matchHistoryBtn.setHorizontalAlignment(SwingConstants.CENTER);
        matchHistoryBtn.setVisible(false);
        matchHistoryBtn.addActionListener(e -> {
            Window owner = SwingUtilities.getWindowAncestor(this);
            JDialog dialog = new JDialog(owner, "Match History", Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setSize(800, 500);
            dialog.setLocationRelativeTo(owner);
            MatchHistoryPanel dialogPanel = new MatchHistoryPanel();
            if (allMatches != null) {
                dialogPanel.setMatches(allMatches);
            }
            dialog.add(dialogPanel);
            dialog.setVisible(true);
        });
        statsContainer.add(matchHistoryBtn);
        statsContainer.add(Box.createVerticalStrut(12));
        
        // 7. Advanced Stats toggle (hidden until search)
        advancedToggle = new JButton("Advanced Stats");
        advancedToggle.setAlignmentX(LEFT_ALIGNMENT);
        advancedToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        advancedToggle.setHorizontalAlignment(SwingConstants.CENTER);
        advancedToggle.setVisible(false);
        statsContainer.add(advancedToggle);
        statsContainer.add(Box.createVerticalStrut(12));
        
        // 8. Advanced stats container (hidden by default)
        JPanel advancedContainer = new JPanel();
        advancedContainer.setLayout(new BoxLayout(advancedContainer, BoxLayout.Y_AXIS));
        advancedContainer.setAlignmentX(LEFT_ALIGNMENT);
        advancedContainer.setVisible(false);

        performanceStatsPanel = new PerformanceStatsPanel();
        performanceStatsPanel.setAlignmentX(LEFT_ALIGNMENT);
        advancedContainer.add(performanceStatsPanel);
        advancedContainer.add(Box.createVerticalStrut(12));
        
        chartPanel = new WinRateChartPanel();
        chartPanel.setPreferredSize(new Dimension(0, 200));
        chartPanel.setMinimumSize(new Dimension(0, 200));
        chartPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        chartPanel.setBorder(BorderFactory.createTitledBorder("Win Rate History"));
        chartPanel.setAlignmentX(LEFT_ALIGNMENT);
        advancedContainer.add(chartPanel);
        
        statsContainer.add(advancedContainer);
        
        advancedToggle.addActionListener(e -> {
            boolean showing = advancedContainer.isVisible();
            advancedToggle.setText(showing ? "Advanced Stats" : "Hide Advanced Stats");
            advancedToggle.setEnabled(false);
            SwingUtilities.invokeLater(() -> {
                advancedContainer.setVisible(!showing);
                statsContainer.revalidate();
                statsContainer.repaint();
                advancedToggle.setEnabled(true);
            });
        });

        mainPanel.add(statsContainer);

        // --- Rank tier view (hidden by default, swapped in when toggle is clicked) ---
        RankTierPanel rankTierPanel = new RankTierPanel();
        rankTierScrollPane = new JScrollPane(rankTierPanel);
        rankTierScrollPane.setAlignmentX(LEFT_ALIGNMENT);
        rankTierScrollPane.setBorder(BorderFactory.createTitledBorder("Rank Tiers"));
        rankTierScrollPane.setVisible(false);
        mainPanel.add(rankTierScrollPane);

        rankTierToggle.addActionListener(e -> {
            boolean showingTiers = rankTierScrollPane.isVisible();
            statsContainer.setVisible(showingTiers);
            rankTierScrollPane.setVisible(!showingTiers);
            rankTierToggle.setText(showingTiers ? "What are the ranks" : "Back to stats");
            mainPanel.revalidate();
            mainPanel.repaint();
        });
        
        return mainPanel;
    }
    
    // --- UI Creation Helpers ---
    
    private JPanel createCommunityBox()
    {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createTitledBorder("Join the community"));
        box.setMaximumSize(new Dimension(220, 90));
        box.setPreferredSize(new Dimension(220, 90));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton discordBtn = new JButton("Discord");
        discordBtn.setPreferredSize(new Dimension(90, 25));
        discordBtn.setToolTipText("Join our Discord");
        discordBtn.addActionListener(e -> {
            try { LinkBrowser.browse("https://discord.gg/TmFzcbW3Rp"); } catch (Exception ignore) {}
        });
        row.add(discordBtn);
        JButton websiteBtn = new JButton("Website");
        websiteBtn.setPreferredSize(new Dimension(90, 25));
        websiteBtn.setToolTipText("Open the website");
        websiteBtn.addActionListener(e -> {
            try { LinkBrowser.browse("https://devsecopsautomated.com/index.html"); } catch (Exception ignore) {}
        });
        row.add(websiteBtn);
        box.add(row);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton reportBugsBtn = new JButton("Report Bugs");
        reportBugsBtn.setPreferredSize(new Dimension(184, 25));
        reportBugsBtn.setToolTipText("Report bugs on Discord");
        reportBugsBtn.addActionListener(e -> {
            try { LinkBrowser.browse("https://discord.gg/TmFzcbW3Rp"); } catch (Exception ignore) {}
        });
        row2.add(reportBugsBtn);
        box.add(row2);
        return box;
    }
    
    
    // --- Actions ---

    private void handleRefresh() {
        if (currentMatchesPlayerId != null && !currentMatchesPlayerId.isEmpty()) {
            loadMatchHistory(currentMatchesPlayerId);
        } else {
            String self = null;
            if (plugin != null) self = plugin.getLocalPlayerName();
            if (self != null) loadMatchHistory(self);
        }
    }
    
    private void onLoginStateChanged() {
        boolean loggedIn = cognitoAuthService.isLoggedIn();
        extraStatsPanel.setVisible(loggedIn);
        // If we just logged in, reload current view to potentially show more info (or self if nothing loaded)
        if (loggedIn) {
            String search = loginPanel.getPluginSearchText();
            if (search != null && !search.isEmpty()) {
                loadMatchHistory(search);
            } else {
                String self = plugin != null ? plugin.getLocalPlayerName() : null;
                if (self != null) {
                    loadMatchHistory(self);
                }
            }
        } else {
             // Logged out
             if (currentMatchesPlayerId != null) loadMatchHistory(currentMatchesPlayerId);
        }
    }

    // --- Data Loading ---
    
    /**
     * Loads match history only if this is a new player search or data is stale (>1 hour old).
     * Called from game events - will skip refresh if data is fresh.
     */
    public void loadMatchHistoryIfNeeded(String playerId)
    {
        String normalizedId = normalizePlayerId(playerId);
        boolean isSamePlayer = normalizedId != null && normalizedId.equalsIgnoreCase(currentMatchesPlayerId);
        boolean isDataFresh = (System.currentTimeMillis() - lastRefreshTimestamp) < STALE_DATA_THRESHOLD_MS;
        
        // Skip if same player and data is fresh
        if (isSamePlayer && isDataFresh) {
            return;
        }
        
        loadMatchHistory(playerId);
    }
    
    /**
     * Loads match history only if NO player is currently being viewed.
     * This prevents game events from overwriting a user's active search.
     * Called on login/world hop - will not switch away from user's search.
     */
    public void loadMatchHistoryIfNotViewing(String playerId)
    {
        // If user is already viewing someone (searched for a player), don't overwrite
        if (currentMatchesPlayerId != null && !currentMatchesPlayerId.isEmpty()) {
            return;
        }
        
        // No one currently viewed - load the requested player (usually self)
        loadMatchHistory(playerId);
    }
    
    /**
     * Forces a full refresh of match history. Called from explicit user actions (search, refresh button).
     */
    public void loadMatchHistory(String playerId)
    {
        final int gen = ++loadGeneration;
        currentMatchesPlayerId = normalizePlayerId(playerId);
        lastRefreshTimestamp = System.currentTimeMillis();

        Runnable uiReset = () -> {
            playerNameLabel.setText(playerId);
            if (loginPanel != null) loginPanel.setPluginSearchText(playerId);
            refreshButton.setVisible(true);
            matchHistoryBtn.setVisible(false);
            advancedToggle.setVisible(false);
            extraStatsPanel.setPlayerName(currentMatchesPlayerId);
            resetUiForNewSearch();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            uiReset.run();
        } else {
            SwingUtilities.invokeLater(uiReset);
        }

        try {
            String normalizedId = currentMatchesPlayerId;
            loadPlayerStats(normalizedId, gen);

            String selfName = plugin != null ? plugin.getLocalPlayerName() : null;
            String selfNameNormalized = normalizePlayerId(selfName);
            boolean isSelf = selfNameNormalized != null && selfNameNormalized.equalsIgnoreCase(normalizedId);

            if (isSelf) {
                String sha = pvpDataService.getSelfAcctSha();
                extraStatsPanel.setAcctSha(sha);
            } else {
                extraStatsPanel.setAcctSha(null);
            }

            CompletableFuture<JsonObject> matchesFuture;
            if (isSelf) {
                String selfAcctSha = pvpDataService.getSelfAcctSha();
                if (selfAcctSha != null && !selfAcctSha.isEmpty()) {
                    matchesFuture = pvpDataService.getPlayerMatchesByAcct(selfAcctSha, null, MATCHES_PAGE_SIZE, false);
                } else {
                    matchesFuture = pvpDataService.getPlayerMatches(normalizedId, null, MATCHES_PAGE_SIZE);
                }
            } else {
                matchesFuture = pvpDataService.getPlayerMatches(normalizedId, null, MATCHES_PAGE_SIZE);
            }

            matchesFuture.thenAccept(jsonResponse -> {
                if (gen != loadGeneration || jsonResponse == null)
                {
                    return;
                }

                JsonArray matches = jsonResponse.has("matches") && jsonResponse.get("matches").isJsonArray() ? jsonResponse.getAsJsonArray("matches") : new JsonArray();

                SwingUtilities.invokeLater(() -> {
                    if (gen != loadGeneration) return;
                    updateUiWithMatches(matches);
                });
            }).exceptionally(ex -> {
                return null;
            });
        } catch (Exception ex) {
            // ignored
        }
    }

    private void updateUiWithMatches(JsonArray matches) {
        chartPanel.setMatches(matches);
        extraStatsPanel.setMatches(matches);
        allMatches = matches;

        boolean hasData = matches != null && matches.size() > 0;
        matchHistoryBtn.setVisible(hasData);
        advancedToggle.setVisible(hasData);
        
        updateBucketBarsFromMatches();
    }

    private void resetUiForNewSearch()
            {
        rankProgressPanel.reset();
        performanceStatsPanel.reset();
        chartPanel.setMatches(new JsonArray());
        extraStatsPanel.setMatches(new JsonArray());
        allMatches = null;
    }
    
    private void loadPlayerStats(String playerId, int gen)
    {
        try {
            String clientUniqueId = (plugin != null) ? plugin.getClientUniqueId() : null;

            pvpDataService.getUserProfile(playerId, clientUniqueId).thenAccept(stats -> {
                if (gen != loadGeneration || stats == null)
                {
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    if (gen != loadGeneration) return;
                    updateProgressBars(stats, gen);
                });
            }).exceptionally(ex -> {
                return null;
            });
        } catch (Exception ex) {
            // ignored
        }
    }

    private void updateProgressBars(JsonObject stats, int gen)
    {
        String playerName = null;
        if (stats.has("player_name")) playerName = stats.get("player_name").getAsString();
        else if (stats.has("player_id")) playerName = stats.get("player_id").getAsString();

        if (playerName != null)
        {
            applyAllBucketStats(stats, playerName, gen);
        }

        if (stats.has("opponent_rank_stats_by_bucket") && stats.get("opponent_rank_stats_by_bucket").isJsonObject())
        {
            performanceStatsPanel.setOpponentRankStats(stats.getAsJsonObject("opponent_rank_stats_by_bucket"));
        }
    }

    private void applyAllBucketStats(JsonObject stats, String playerName, int gen)
    {
        applyBucketStatsFromUser(playerName, "overall", stats, gen);

        JsonObject bucketsObj = stats.has("buckets") ? stats.getAsJsonObject("buckets") : null;
        String[] bucketKeys = {"nh", "veng", "multi", "dmm"};
        for (String bucketKey : bucketKeys) {
            JsonObject bucketObj = (bucketsObj != null && bucketsObj.has(bucketKey)) ? bucketsObj.getAsJsonObject(bucketKey) : null;
            applyBucketStatsFromUser(playerName, bucketKey, bucketObj, gen);
        }
    }

    private void applyBucketStatsFromUser(String playerName, String bucketKey, JsonObject bucketObj, int gen)
    {
        if (bucketObj == null) {
            rankProgressPanel.updateBucket(bucketKey, "—", 0, 0, -1);
            return;
        }

        String rankLabel = "—";
        int division = 0;
        double pct = 0.0;

        if (bucketObj.has("mmr")) {
            double mmr = bucketObj.get("mmr").getAsDouble();
            RankInfo ri = RankUtils.rankLabelAndProgressFromMMR(mmr);
            if (ri != null) { rankLabel = ri.rank; division = ri.division; pct = ri.progress; }
        }
        if (bucketObj.has("rank_progress")) {
             JsonObject rp = bucketObj.getAsJsonObject("rank_progress");
             if (rp.has("progress_to_next_rank_pct")) pct = rp.get("progress_to_next_rank_pct").getAsDouble();
        }
        if (bucketObj.has("rank")) {
             String r = bucketObj.get("rank").getAsString();
             if (!r.isEmpty()) rankLabel = r;
        }
        if (bucketObj.has("division")) division = bucketObj.get("division").getAsInt();

        final String fRank = rankLabel;
        final int fDiv = division;
        final double fPct = pct;

        rankProgressPanel.updateBucket(bucketKey, fRank, fDiv, fPct, -1);

        if (!"—".equals(fRank)) {
            new SwingWorker<Integer, Void>() {
                @Override protected Integer doInBackground() { return getRankNumberFromLeaderboard(playerName, bucketKey); }
                @Override protected void done() {
                     try {
                         if (gen != loadGeneration) return;
                         int rank = get();
                         if (rank > 0) rankProgressPanel.updateBucket(bucketKey, fRank, fDiv, fPct, rank);
                    } catch (Exception ignore) {
                    }
                }
            }.execute();
        }
    }
    
    private void updateBucketBarsFromMatches() {
        if (allMatches == null || allMatches.size() == 0) return;
        String playerName = playerNameLabel.getText();
        if ("Player Name".equals(playerName)) return;

        final int gen = loadGeneration;
        String[] buckets = {"overall", "nh", "veng", "multi", "dmm"};
        for (String bucket : buckets)
        {
            JsonArray items = new JsonArray();
            for (int i = 0; i < allMatches.size(); i++)
            {
                JsonObject match = allMatches.get(i).getAsJsonObject();
                String matchBucket = match.has("bucket") ? match.get("bucket").getAsString().toLowerCase() : "";
                if (matchBucket.equals(bucket)) items.add(match);
            }

            if (items.size() == 0) continue;

            JsonObject latest = null;
            for (int i = 0; i < items.size(); i++)
            {
                JsonObject item = items.get(i).getAsJsonObject();
                if (latest == null ||
                    (item.has("when") && latest.has("when") &&
                     item.get("when").getAsLong() > latest.get("when").getAsLong()))
                {
                    latest = item;
                }
            }

            if (latest != null && latest.has("player_mmr"))
            {
                double mmr = latest.get("player_mmr").getAsDouble();
                RankInfo est = RankUtils.rankLabelAndProgressFromMMR(mmr);

                String finalRank = latest.has("player_rank") ? latest.get("player_rank").getAsString() : (est != null ? est.rank : "—");
                int finalDiv = latest.has("player_division") ? latest.get("player_division").getAsInt() : (est != null ? est.division : 0);
                double pct = (est != null ? est.progress : 0.0);

                new SwingWorker<Integer, Void>()
                {
                    @Override protected Integer doInBackground() { return getRankNumberFromLeaderboard(playerName, bucket); }
                    @Override protected void done() {
                        try {
                            if (gen != loadGeneration) return;
                            int rankNumber = get();
                            if (rankNumber > 0) {
                                rankProgressPanel.updateBucket(bucket, finalRank, finalDiv, pct, rankNumber);
                            }
                        } catch (Exception ignore) {
                        }
                    }
                }.execute();
            }
        }
    }

    // --- Helpers ---
    
    public int getRankNumberFromLeaderboard(String playerName, String bucket)
    {
        try {
            String canonBucket = bucket == null ? "overall" : bucket.toLowerCase();
            ShardRank sr = pvpDataService.getShardRankByName(playerName, canonBucket)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
            return sr != null && sr.rank > 0 ? sr.rank : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private static String normalizePlayerId(String name) {
        return name != null ? name.trim().replaceAll("\\s+", " ").toLowerCase() : null;
    }

    private static void disableNestedWheelScrolling(Container container)
    {
        installSmartWheelForwarding(container);
    }

    private static void installSmartWheelForwarding(Container container)
    {
        for (Component child : container.getComponents())
        {
            if (child instanceof JScrollPane)
            {
                addBoundaryForwarding((JScrollPane) child);
            }
            if (child instanceof Container)
            {
                installSmartWheelForwarding((Container) child);
            }
        }
        container.addContainerListener(new java.awt.event.ContainerAdapter()
        {
            @Override
            public void componentAdded(java.awt.event.ContainerEvent e)
            {
                Component c = e.getChild();
                if (c instanceof JScrollPane)
                {
                    addBoundaryForwarding((JScrollPane) c);
                }
                if (c instanceof Container)
                {
                    installSmartWheelForwarding((Container) c);
                }
            }
        });
    }

    private static void addBoundaryForwarding(JScrollPane sp)
    {
        sp.setWheelScrollingEnabled(false);
        sp.addMouseWheelListener(e -> {
            JScrollBar vbar = sp.getVerticalScrollBar();
            if (vbar == null || !vbar.isVisible())
            {
                sp.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(sp, e, sp.getParent()));
                return;
            }
            int val = vbar.getValue();
            int max = vbar.getMaximum() - vbar.getVisibleAmount();
            boolean atTop = val <= vbar.getMinimum();
            boolean atBottom = val >= max;
            boolean scrollingDown = e.getWheelRotation() > 0;
            boolean scrollingUp = e.getWheelRotation() < 0;

            if ((scrollingUp && atTop) || (scrollingDown && atBottom))
            {
                sp.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(sp, e, sp.getParent()));
            }
            else
            {
                int delta = e.getUnitsToScroll() * vbar.getUnitIncrement();
                vbar.setValue(val + delta);
            }
        });
    }
}

