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
    private MatchHistoryPanel matchHistoryPanel;
    private AdditionalStatsPanel extraStatsPanel;
    private WinRateChartPanel chartPanel;
    
    // Header elements
    private JLabel playerNameLabel;
    private JButton refreshButton;

    // State
    private String currentMatchesPlayerId = null;
    private JsonArray allMatches = null;
    private long lastRefreshTimestamp = 0;
    
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
        
        JScrollPane scrollPane = new JScrollPane(createMainPanel());
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);
    }
    
    private JPanel createMainPanel()
    {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        
        // 1. Community (kept simple here)
        mainPanel.add(createCommunityBox());
        mainPanel.add(Box.createVerticalStrut(12));

        // 2. Auth / Login
        loginPanel = new LoginPanel(cognitoAuthService, this::loadMatchHistory, this::onLoginStateChanged);
        JPanel authContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        authContainer.add(loginPanel);
        mainPanel.add(authContainer);
        mainPanel.add(Box.createVerticalStrut(16));

        // 3. Profile Header
        mainPanel.add(createProfileHeader());
        mainPanel.add(Box.createVerticalStrut(24));
        
        // 4. Rank Progress
        rankProgressPanel = new RankProgressPanel();
        mainPanel.add(rankProgressPanel);
        mainPanel.add(Box.createVerticalStrut(24));
        
        // 5. Performance Overview
        performanceStatsPanel = new PerformanceStatsPanel();
        mainPanel.add(performanceStatsPanel);
        mainPanel.add(Box.createVerticalStrut(12));
        
        // 6. Additional Stats (Hidden by default)
        extraStatsPanel = new AdditionalStatsPanel();
        extraStatsPanel.setVisible(false);
        try { if (extraStatsPanel != null) extraStatsPanel.setBucket("overall"); } catch (Exception ignore) {}
        mainPanel.add(extraStatsPanel);
        mainPanel.add(Box.createVerticalStrut(24));
        
        // 7. Win Rate Chart
        chartPanel = new WinRateChartPanel();
        JScrollPane chartScroll = new JScrollPane(chartPanel);
        chartScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        chartScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        chartScroll.setPreferredSize(new Dimension(0, 220));
        chartScroll.setBorder(BorderFactory.createTitledBorder("Win Rate History"));
        mainPanel.add(chartScroll);
        mainPanel.add(Box.createVerticalStrut(24));

        // 8. Match History
        matchHistoryPanel = new MatchHistoryPanel();
        mainPanel.add(matchHistoryPanel);
        
        return mainPanel;
    }
    
    // --- UI Creation Helpers ---
    
    private JPanel createCommunityBox()
    {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createTitledBorder("Join the community"));
        box.setMaximumSize(new Dimension(220, 60));
        box.setPreferredSize(new Dimension(220, 60));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton discordBtn = new JButton("Discord");
        discordBtn.setPreferredSize(new Dimension(90, 25));
        discordBtn.setToolTipText("Join our Discord");
        discordBtn.addActionListener(e -> {
            try { LinkBrowser.browse("https://discord.gg/3Ct5CQmCPr"); } catch (Exception ignore) {}
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
        return box;
    }
    
    private JPanel createProfileHeader()
    {
        JPanel header = new JPanel(new BorderLayout());
        JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        
        playerNameLabel = new JLabel("Player Name");
        playerNameLabel.setFont(playerNameLabel.getFont().deriveFont(Font.BOLD, 18f));
        namePanel.add(playerNameLabel);
        namePanel.add(Box.createHorizontalStrut(8));
        
        refreshButton = new JButton("Refresh");
        refreshButton.setPreferredSize(new Dimension(80, 25));
        refreshButton.addActionListener(e -> handleRefresh());
        namePanel.add(refreshButton);
        
        header.add(namePanel, BorderLayout.NORTH);
        return header;
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
                    loginPanel.setPluginSearchText(self);
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
        currentMatchesPlayerId = normalizePlayerId(playerId);
        playerNameLabel.setText(playerId); // Update header immediately
        lastRefreshTimestamp = System.currentTimeMillis();
        
        // Update the search box (needed for pvp lookup right-click menu)
        if (loginPanel != null) {
            loginPanel.setPluginSearchText(playerId);
        }

        resetUiForNewSearch();

        try {
            String normalizedId = currentMatchesPlayerId;
            loadPlayerStats(normalizedId);

            // Determine if this is "self" lookup - use acct-based query for accurate match history
            // across name changes; use name-based query for searching other players
            String selfName = plugin != null ? plugin.getLocalPlayerName() : null;
            String selfNameNormalized = normalizePlayerId(selfName);
            boolean isSelf = selfNameNormalized != null && selfNameNormalized.equalsIgnoreCase(normalizedId);
            
            CompletableFuture<JsonObject> matchesFuture;
            if (isSelf) {
                // Self lookup - use acct SHA for accurate history across name changes
                String selfAcctSha = pvpDataService.getSelfAcctSha();
                if (selfAcctSha != null && !selfAcctSha.isEmpty()) {
                    matchesFuture = pvpDataService.getPlayerMatchesByAcct(selfAcctSha, null, MATCHES_PAGE_SIZE, false);
                } else {
                    // Fallback to name-based if acct SHA not available
                    matchesFuture = pvpDataService.getPlayerMatches(normalizedId, null, MATCHES_PAGE_SIZE);
                }
            } else {
                // Other player lookup - use name-based query
                matchesFuture = pvpDataService.getPlayerMatches(normalizedId, null, MATCHES_PAGE_SIZE);
            }

            matchesFuture.thenAccept(jsonResponse -> {
                if (jsonResponse == null) return;
                
                JsonArray matches = jsonResponse.has("matches") && jsonResponse.get("matches").isJsonArray() ? jsonResponse.getAsJsonArray("matches") : new JsonArray();
                // String nextToken = jsonResponse.has("next_token") && !jsonResponse.get("next_token").isJsonNull() ? jsonResponse.get("next_token").getAsString() : null;

                SwingUtilities.invokeLater(() -> {
                    updateUiWithMatches(matches);
                });
            });
        } catch (Exception ignore) {}
    }

    private void updateUiWithMatches(JsonArray matches) {
        matchHistoryPanel.setMatches(matches);
        chartPanel.setMatches(matches);
        extraStatsPanel.setMatches(matches);
        performanceStatsPanel.updateBreakdown(matches);
        allMatches = matches;
        
        // Update summary stats
        int wins = 0, losses = 0, ties = 0;
        for (int i = 0; i < matches.size(); i++) {
             JsonObject m = matches.get(i).getAsJsonObject();
             String r = m.has("result") ? m.get("result").getAsString().toLowerCase() : "";
             if ("win".equals(r)) wins++;
             else if ("loss".equals(r)) losses++;
             else if ("tie".equals(r)) ties++;
                }
        performanceStatsPanel.updateStats(wins, losses, ties);
        
        updateBucketBarsFromMatches();
    }

    private void resetUiForNewSearch()
            {
        rankProgressPanel.reset();
        performanceStatsPanel.reset();
        matchHistoryPanel.clear();
                chartPanel.setMatches(new JsonArray());
        extraStatsPanel.setMatches(new JsonArray());
        allMatches = null;
    }
    
    private void loadPlayerStats(String playerId)
    {
        try {
            String clientUniqueId = (plugin != null) ? plugin.getClientUniqueId() : null;
            
            pvpDataService.getUserProfile(playerId, clientUniqueId).thenAccept(stats -> {
                if (stats == null) return;
                SwingUtilities.invokeLater(() -> updateProgressBars(stats));
                
                // Extract and pass cumulative stats to PerformanceStatsPanel
                if (stats.has("cumulative_stats")) {
                    JsonObject cs = stats.getAsJsonObject("cumulative_stats");
                    SwingUtilities.invokeLater(() -> {
                        performanceStatsPanel.setCumulativeStats(cs);
                    });
                }
                
                // Extract and pass opponent rank stats to PerformanceStatsPanel
                // Prefer new per-bucket format, fallback to flat format
                if (stats.has("opponent_rank_stats_by_bucket")) {
                    JsonObject ors = stats.getAsJsonObject("opponent_rank_stats_by_bucket");
                    SwingUtilities.invokeLater(() -> {
                        performanceStatsPanel.setOpponentRankStats(ors);
                    });
                } else if (stats.has("opponent_rank_stats")) {
                    JsonObject ors = stats.getAsJsonObject("opponent_rank_stats");
                    SwingUtilities.invokeLater(() -> {
                        performanceStatsPanel.setOpponentRankStats(ors);
                    });
                }
            });
        } catch (Exception ignore) {}
    }
    
    private void updateProgressBars(JsonObject stats)
    {
        String playerName = null;
        if (stats.has("player_name")) playerName = stats.get("player_name").getAsString();
        else if (stats.has("player_id")) playerName = stats.get("player_id").getAsString();
        
        if (playerName != null) updatePlayerStats(stats, playerName);
    }
    
    private void updatePlayerStats(JsonObject stats, String playerName)
    {
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws Exception
            {
                // Overall
                applyBucketStatsFromUser(playerName, "overall", stats);
                
                // Buckets
                JsonObject bucketsObj = stats.has("buckets") ? stats.getAsJsonObject("buckets") : null;
                String[] bucketKeys = {"nh", "veng", "multi", "dmm"};
                for (String bucketKey : bucketKeys) {
                    JsonObject bucketObj = (bucketsObj != null && bucketsObj.has(bucketKey)) ? bucketsObj.getAsJsonObject(bucketKey) : null;
                    applyBucketStatsFromUser(playerName, bucketKey, bucketObj);
                }
                return null;
            }
        };
        worker.execute();
    }
    
    private void applyBucketStatsFromUser(String playerName, String bucketKey, JsonObject bucketObj)
    {
        if (bucketObj == null) {
            rankProgressPanel.updateBucket(bucketKey, "—", 0, 0, -1);
            return;
        }
        
        String rankLabel = "—";
        int division = 0;
        double pct = 0.0;
        
        if (bucketObj.has("mmr")) {
            RankInfo ri = RankUtils.rankLabelAndProgressFromMMR(bucketObj.get("mmr").getAsDouble());
            if (ri != null) { rankLabel = ri.rank; division = ri.division; pct = ri.progress; }
        }
        if (bucketObj.has("rank_progress")) {
             JsonObject rp = bucketObj.getAsJsonObject("rank_progress");
             if (rp.has("progress_to_next_rank_pct")) pct = rp.get("progress_to_next_rank_pct").getAsDouble();
        }
        if (bucketObj.has("rank")) {
             String r = bucketObj.get("rank").getAsString();
             if (!r.isEmpty()) rankLabel = r; // Simple parse if needed
             // Reuse existing RankUtils parsing if complex, simplified here
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
                         int rank = get();
                         if (rank > 0) rankProgressPanel.updateBucket(bucketKey, fRank, fDiv, fPct, rank);
                    } catch (Exception ignore) {}
                }
            }.execute();
            }
    }
    
    private void updateBucketBarsFromMatches() {
        if (allMatches == null || allMatches.size() == 0) return;
        String playerName = playerNameLabel.getText();
        if ("Player Name".equals(playerName)) return;
        
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
            
            // Find latest match by timestamp
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
                
                // Only fetch rank number for currently selected rank bucket
                String currentBucket = "overall";
                if (bucket.equals(currentBucket))
                {
                SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>()
                {
                        @Override protected Integer doInBackground() { return getRankNumberFromLeaderboard(playerName, bucket); }
                        @Override protected void done() {
                            try {
                            int rankNumber = get();
                                rankProgressPanel.updateBucket(bucket, finalRank, finalDiv, pct, rankNumber);
                            } catch (Exception e) {
                                rankProgressPanel.updateBucket(bucket, finalRank, finalDiv, pct, -1);
                        }
                    }
                };
                worker.execute();
                } else {
                    rankProgressPanel.updateBucket(bucket, finalRank, finalDiv, pct, -1);
                }
            }
        }
    }

    // --- Helpers ---
    
    public int getRankNumberFromLeaderboard(String playerName, String bucket)
    {
        try {
            String canonBucket = bucket == null ? "overall" : bucket.toLowerCase();
            // Single path - shard contains world_rank
            ShardRank sr = pvpDataService.getShardRankByName(playerName, canonBucket).get();
            return sr != null && sr.rank > 0 ? sr.rank : -1;
        } catch (Exception ignore) { return -1; }
    }

    private static String normalizePlayerId(String name) {
        return name != null ? name.trim().replaceAll("\\s+", " ").toLowerCase() : null;
    }
}
