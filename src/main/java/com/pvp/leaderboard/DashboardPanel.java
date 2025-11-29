package com.pvp.leaderboard;

import net.runelite.client.ui.PluginPanel;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class DashboardPanel extends PluginPanel
{
    private JTable matchHistoryTable;
    private DefaultTableModel tableModel;
    private JTextField websiteSearchField;
    private JTextField pluginSearchField;
    private JPasswordField passwordField;
    private JButton loginButton;
    private JLabel playerNameLabel;
    private final JProgressBar[] progressBars;
    private final JLabel[] progressLabels;
    // Reserve space on the right for the sidebar scrollbar to avoid clipping bars
    private static final int SIDEBAR_SCROLLBAR_RESERVE_PX = 16;
    // Fixed progress bar dimensions suitable for narrow sidebar
    private static final int PROGRESS_BAR_WIDTH = 200;
    private static final int PROGRESS_BAR_HEIGHT = 16;
    
    private JLabel winPercentLabel;
    private JLabel tiesLabel;
    private JLabel killsLabel;
    private JLabel deathsLabel;
    private JLabel kdLabel;
    private JPanel chartPanel;
    private java.util.List<Double> winRateHistory = new java.util.ArrayList<>();
    private JPanel additionalStatsPanel; // legacy container (kept for compatibility)
    private AdditionalStatsPanel extraStatsPanel; // new compact additional stats panel
    private DefaultTableModel rankBreakdownModel;
    private JTable rankBreakdownTable;
    private boolean isLoggedIn = false;
    private String idToken = null;
    
    private JLabel highestRankLabel;
    private JLabel highestRankTimeLabel;
    private JLabel lowestRankLabel;
    private JLabel lowestRankTimeLabel;
    private JPanel tierGraphPanel;
    private String selectedBucket = "overall";
    private java.util.List<Double> tierHistory = new java.util.ArrayList<>();
    private JsonArray allMatches = null;
    private JButton refreshButton;
    private JButton[] bucketButtons = new JButton[5];
    private long lastRefreshTime = 0;
    private static final long REFRESH_COOLDOWN_MS = 60000; // 1 minute
    private JButton loadMoreButton;
    private String currentMatchesPlayerId = null;
    private String matchesNextToken = null;
    private static final int MATCHES_PAGE_SIZE = 100;
    private final Map<String, MatchesCache> matchesCache = new HashMap<>();
    private boolean loginInProgress = false;
    private final OkHttpClient httpClient;
    private final Gson gson;
    // Shard lookup caching
    private final Map<String, ShardEntry> shardCache = Collections.synchronizedMap(
        new LinkedHashMap<String, ShardEntry>(128, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ShardEntry> eldest)
            {
                return size() > 512; // LRU cap
            }
        }
    );
    private final ConcurrentHashMap<String, Long> shardThrottle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> shardFailUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> shardLocks = new ConcurrentHashMap<>();
    // Negative cache for missing players (avoid repeated network/errors)
    private final ConcurrentHashMap<String, Long> missingPlayerUntilMs = new ConcurrentHashMap<>();
    private static final long MISSING_PLAYER_BACKOFF_MS = 60L * 60L * 1000L; // 1 hour
    private static final long SHARD_FAIL_BACKOFF_MS = 60L * 60L * 1000L; // 1 hour
    private static final long SHARD_CACHE_EXPIRY_MS = 60L * 60L * 1000L;
    // Global fetch throttle to reduce bursty lookups in populated areas
    private final Object globalFetchLock = new Object();
    private long lastGlobalFetchMs = 0L;
    private static final Map<String, Color> RANK_COLORS = new HashMap<String, Color>() {{
        put("Bronze", new Color(184, 115, 51));
        put("Iron", new Color(192, 192, 192));
        put("Steel", new Color(154, 162, 166));
        put("Black", Color.GRAY);
        put("Mithril", new Color(59, 167, 214));
        put("Adamant", new Color(26, 139, 111));
        put("Rune", new Color(78, 159, 227));
        put("Dragon", new Color(229, 57, 53));
        put("3rd", new Color(229, 193, 0));
    }};
    
    private Color getRankColor(String rankName) {
        if (rankName == null) return new Color(102, 102, 102);
        String baseName = rankName.split(" ")[0];
        return RANK_COLORS.getOrDefault(baseName, new Color(102, 102, 102));
    }

    private Color getRankTextColor(String rankName) {
        if (config != null && config.colorblindMode()) return Color.WHITE;
        return getRankColor(rankName);
    }

    // In-memory cache for /user profile responses to avoid blank UI when backend throttles or fails
    private static final long USER_CACHE_TTL_MS = 60L * 1000L; // 60 seconds
    private static class UserStatsCache { final JsonObject stats; final long ts; UserStatsCache(JsonObject s, long t) { this.stats = s; this.ts = t; } }
    private final ConcurrentHashMap<String, UserStatsCache> userStatsCache = new ConcurrentHashMap<>();

    private JsonObject getFreshUserStatsFromCache(String playerId) {
        try {
            String key = normalizePlayerId(playerId);
            UserStatsCache entry = userStatsCache.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.ts > USER_CACHE_TTL_MS) return null;
            // Return a defensive copy so downstream code cannot mutate our cache
            return entry.stats != null ? entry.stats.deepCopy().getAsJsonObject() : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private void putUserStatsInCache(String playerId, JsonObject stats) {
        try {
            if (playerId == null || stats == null) return;
            String key = normalizePlayerId(playerId);
            userStatsCache.put(key, new UserStatsCache(stats.deepCopy().getAsJsonObject(), System.currentTimeMillis()));
        } catch (Exception ignore) {}
    }

    private PvPLeaderboardConfig config;
    private final ConfigManager configManager;
    private Map<String, Integer> bucketRankNumbers = new HashMap<>();
    private PvPLeaderboardPlugin plugin; 
    // Prevent repeated API attempts when network/DNS is down
    private volatile long apiDownUntilMs = 0L;
    // Cache for per-(player,bucket) rank number lookups (API/shard fast-path)
    private static final long RANK_NUMBER_CACHE_TTL_MS = 10L * 60L * 1000L; // 10 minutes
    private static class RankNumberCache { final int rank; final long ts; RankNumberCache(int r, long t) { this.rank = r; this.ts = t; } }
    private final ConcurrentHashMap<String, RankNumberCache> rankNumberCache = new ConcurrentHashMap<>();
    // Cache for overall world rank (can be cached longer; backend TTL ~24h)
    private static final long WORLD_RANK_CACHE_TTL_MS = 12L * 60L * 60L * 1000L; // 12 hours
    private static class WorldRankCache { final int worldRank; final long ts; WorldRankCache(int r, long t) { this.worldRank = r; this.ts = t; } }
    private final ConcurrentHashMap<String, WorldRankCache> worldRankCache = new ConcurrentHashMap<>();
    private static final String API_BASE = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod";
    
    private Client getClientSafe()
    {
        try { return plugin != null ? plugin.getClient() : null; } catch (Exception ignore) { return null; }
    }

    private boolean isApiTemporarilyDown()
    {
        return System.currentTimeMillis() < apiDownUntilMs;
    }

    private void markApiDownForMillis(long millis)
    {
        long now = System.currentTimeMillis();
        long until = now + Math.max(0L, millis);
        // Only extend, never shorten
        if (until > apiDownUntilMs) apiDownUntilMs = until;
    }

    private static String normalizeDisplayName(String name) {
        if (name == null) return null;
        return name.trim().replaceAll("\\s+", " ");
    }

    private static String normalizePlayerId(String name)
    {
        String display = normalizeDisplayName(name);
        return display; // keep spaces intact for player_id; URL-encoding will handle safely
    }

    private static String toApiBucket(String bucket)
    {
        if (bucket == null || bucket.trim().isEmpty()) return "overall.weighted";
        String b = bucket.toLowerCase();
        return "overall".equals(b) ? "overall.weighted" : b;
    }
    
    public DashboardPanel(PvPLeaderboardConfig config, ConfigManager configManager, PvPLeaderboardPlugin plugin, OkHttpClient httpClient, Gson gson)
    {
        this.config = config;
        this.configManager = configManager;
        this.plugin = plugin;
        this.httpClient = httpClient;
        this.gson = gson;
        progressBars = new JProgressBar[5];
        progressLabels = new JLabel[5];
        
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JScrollPane scrollPane = new JScrollPane(createMainPanel());
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);
    }
    
    private void showAdditionalStats(boolean show)
    {
        isLoggedIn = show;
        if (additionalStatsPanel != null) { additionalStatsPanel.setVisible(show); }
        if (extraStatsPanel != null) { extraStatsPanel.setVisible(show); }
        revalidate();
        repaint();
    }
    
    private JPanel createMainPanel()
    {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        
        // Community Box (Discord)
        JPanel communityContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        communityContainer.add(createCommunityBox());
        mainPanel.add(communityContainer);
        mainPanel.add(Box.createVerticalStrut(12));

        // Auth Bar (Login/Search Section)
        JPanel authContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        authContainer.add(createAuthBar());
        mainPanel.add(authContainer);
        mainPanel.add(Box.createVerticalStrut(16));

        // Profile Header
        mainPanel.add(createProfileHeader());
        mainPanel.add(Box.createVerticalStrut(24));
        
        // Rank Progress Section
        mainPanel.add(createRankProgressSection());
        mainPanel.add(Box.createVerticalStrut(24));
        
        // Compact performance overview
        mainPanel.add(createCompactPerformanceOverview());
        mainPanel.add(Box.createVerticalStrut(12));
        
        // Additional Stats (new compact panel) - hidden until login
        extraStatsPanel = new AdditionalStatsPanel();
        extraStatsPanel.setVisible(false);
        // Initialize bucket to config's current setting
        try { setStatsBucketFromConfig(config.rankBucket()); } catch (Exception ignore) {}
        mainPanel.add(extraStatsPanel);
        mainPanel.add(Box.createVerticalStrut(24));
        
        // Match History
        mainPanel.add(createMatchHistory());
        
        return mainPanel;
    }
    
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
            try { Desktop.getDesktop().browse(URI.create("https://discord.gg/3Ct5CQmCPr")); } catch (Exception ignore) {}
        });
        row.add(discordBtn);
        JButton websiteBtn = new JButton("Website");
        websiteBtn.setPreferredSize(new Dimension(90, 25));
        websiteBtn.setToolTipText("Open the website");
        websiteBtn.addActionListener(e -> {
            try { Desktop.getDesktop().browse(URI.create("https://devsecopsautomated.com/index.html")); } catch (Exception ignore) {}
        });
        row.add(websiteBtn);
        box.add(row);
        return box;
    }

    private JPanel createAuthBar()
    {
        JPanel authBar = new JPanel();
        authBar.setLayout(new BoxLayout(authBar, BoxLayout.Y_AXIS));
        authBar.setBorder(BorderFactory.createTitledBorder("Login to view stats in runelite"));
        authBar.setMaximumSize(new Dimension(220, 190));
        authBar.setPreferredSize(new Dimension(220, 190));
        
        // Website search
        JLabel websiteLabel = new JLabel("Search user on website:");
        websiteLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        authBar.add(websiteLabel);
        JPanel websitePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        websitePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        websiteSearchField = new JTextField();
        websiteSearchField.setPreferredSize(new Dimension(120, 25));
        websiteSearchField.addActionListener(e -> searchUserOnWebsite());
        JButton websiteSearchBtn = new JButton("Search");
        websiteSearchBtn.setPreferredSize(new Dimension(70, 25));
        websiteSearchBtn.addActionListener(e -> searchUserOnWebsite());
        websitePanel.add(websiteSearchField);
        websitePanel.add(websiteSearchBtn);
        authBar.add(websitePanel);
        
        authBar.add(Box.createVerticalStrut(5));
        
        // Plugin search
        JLabel pluginLabel = new JLabel("Search user on plugin:");
        pluginLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        authBar.add(pluginLabel);
        JPanel pluginPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        pluginPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        pluginSearchField = new JTextField();
        pluginSearchField.setPreferredSize(new Dimension(120, 25));
        pluginSearchField.addActionListener(e -> searchUserOnPlugin());
        JButton pluginSearchBtn = new JButton("Search");
        pluginSearchBtn.setPreferredSize(new Dimension(70, 25));
        pluginSearchBtn.addActionListener(e -> searchUserOnPlugin());
        pluginPanel.add(pluginSearchField);
        pluginPanel.add(pluginSearchBtn);
        authBar.add(pluginPanel);
        
        authBar.add(Box.createVerticalStrut(5));
        
        loginButton = new JButton("Login to view more stats");
        loginButton.setPreferredSize(new Dimension(210, 25));
        loginButton.setMaximumSize(new Dimension(220, 25));
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.addActionListener(e -> handleLogin());
        authBar.add(loginButton);
        

        
        return authBar;
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
        
//        JPanel bucketRanks = new JPanel(new FlowLayout(FlowLayout.LEFT));
//        bucketRanks.add(new JLabel("Overall: Rune 3"));
//        bucketRanks.add(new JLabel("NH: Rune 3"));
//        bucketRanks.add(new JLabel("Veng: Rune 3"));
//        bucketRanks.add(new JLabel("Multi: Rune 3"));
//        header.add(bucketRanks, BorderLayout.CENTER);
        
        return header;
    }
    
    private JPanel createRankProgressSection()
    {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder("Rank Progress"));

        JLabel seasonLabel = new JLabel("Season 0");
        seasonLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        Font baseFont = seasonLabel.getFont();
        if (baseFont != null)
        {
            seasonLabel.setFont(baseFont.deriveFont(Font.BOLD, Math.max(12f, baseFont.getSize2D())));
        }
        section.add(seasonLabel);
        section.add(Box.createVerticalStrut(6));
        
        String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};
        
        for (int i = 0; i < buckets.length; i++)
        {
            JPanel bucketPanel = new JPanel(new BorderLayout());
            // Add horizontal padding; keep extra space on the right so bars never sit under the scrollbar
            bucketPanel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, SIDEBAR_SCROLLBAR_RESERVE_PX));
            // Allow the row to stretch to the full narrow sidebar width (height only)
            bucketPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            
            progressLabels[i] = new JLabel(buckets[i] + " - — (0.0%) ");
            progressLabels[i].setFont(progressLabels[i].getFont().deriveFont(Font.BOLD));
            bucketPanel.add(progressLabels[i], BorderLayout.NORTH);
            
            progressBars[i] = new JProgressBar(0, 100);
            progressBars[i].setValue(0);
            progressBars[i].setStringPainted(true);
            progressBars[i].setString("0%");
            // Fixed width/height so it never stretches under the scrollbar
            progressBars[i].setPreferredSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setMinimumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            progressBars[i].setMaximumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
            // Remove extra insets so the fill amount maps 1:1 to percentage on tiny widths
            progressBars[i].setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            // Wrap in a left-aligned FlowLayout row so BorderLayout doesn't stretch the bar
            JPanel barRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            barRow.setOpaque(false);
            barRow.add(progressBars[i]);
            bucketPanel.add(barRow, BorderLayout.CENTER);
            
            section.add(bucketPanel);
            if (i < buckets.length - 1) section.add(Box.createVerticalStrut(8));
        }
        
        return section;
    }
    
    private JPanel createCompactPerformanceOverview()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Last 100 Game Performance"));

        JPanel summaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        winPercentLabel = new JLabel("- % Winrate");
        kdLabel = new JLabel("KD:");
        killsLabel = new JLabel("K:");
        deathsLabel = new JLabel("D:");
        tiesLabel = new JLabel("Ties:");
        Font small = winPercentLabel.getFont().deriveFont(Font.PLAIN, Math.max(10f, winPercentLabel.getFont().getSize2D() - 1f));
        winPercentLabel.setFont(small);
        kdLabel.setFont(small);
        killsLabel.setFont(small);
        deathsLabel.setFont(small);
        tiesLabel.setFont(small);
        summaryRow.add(winPercentLabel);
        summaryRow.add(Box.createHorizontalStrut(6));
        summaryRow.add(kdLabel);
        summaryRow.add(Box.createHorizontalStrut(6));
        summaryRow.add(killsLabel);
        summaryRow.add(new JLabel(" | "));
        summaryRow.add(deathsLabel);
        summaryRow.add(Box.createHorizontalStrut(6));
        summaryRow.add(tiesLabel);
        panel.add(summaryRow);

        // Rank breakdown table (taller + compact rows)
        JPanel breakdown = createRankBreakdownTable();
        breakdown.setPreferredSize(new Dimension(0, 340));
        panel.add(breakdown);
        return panel;
    }
    
    @SuppressWarnings("unused")
    private JPanel createAdditionalStats()
    {
        additionalStatsPanel = new JPanel();
        additionalStatsPanel.setLayout(new BoxLayout(additionalStatsPanel, BoxLayout.Y_AXIS));
        additionalStatsPanel.setBorder(BorderFactory.createTitledBorder("Additional Stats"));
        additionalStatsPanel.setVisible(false); // Hidden by default
        
        // Stats row like website with scroller
        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 24, 0));
        
        JPanel highestRank = new JPanel();
        highestRank.setLayout(new BoxLayout(highestRank, BoxLayout.Y_AXIS));
        highestRank.add(new JLabel("Highest Rank Defeated"));
        highestRankLabel = new JLabel("-");
        highestRankTimeLabel = new JLabel("-");
        highestRank.add(highestRankLabel);
        highestRank.add(highestRankTimeLabel);
        
        JPanel lowestRank = new JPanel();
        lowestRank.setLayout(new BoxLayout(lowestRank, BoxLayout.Y_AXIS));
        lowestRank.add(new JLabel("Lowest Rank Lost To"));
        lowestRankLabel = new JLabel("-");
        lowestRankTimeLabel = new JLabel("-");
        lowestRank.add(lowestRankLabel);
        lowestRank.add(lowestRankTimeLabel);
        
        statsPanel.add(highestRank);
        statsPanel.add(lowestRank);
        
        JScrollPane statsScrollPane = new JScrollPane(statsPanel);
        statsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        statsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        statsScrollPane.setBorder(null);
        statsScrollPane.setPreferredSize(new Dimension(0, 60));
        additionalStatsPanel.add(statsScrollPane);
        additionalStatsPanel.add(Box.createVerticalStrut(16));
        
        // Bucket selector across two rows (to guarantee visibility in narrow panel)
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};
        for (int i = 0; i < buckets.length; i++) {
            String bucket = buckets[i];
            JButton btn = new JButton(bucket);
            int chipWidth = 64; // fit within sidebar
            btn.setPreferredSize(new Dimension(chipWidth, 25));
            btn.setMinimumSize(btn.getPreferredSize());
            btn.setFocusable(false);
            btn.addActionListener(e -> {
                selectedBucket = bucket.toLowerCase();
                updateBucketButtonStates(bucket);
                if (allMatches != null) {
                    updateTierGraph(allMatches);
                }
            });
            bucketButtons[i] = btn;
            if (i < 2) row1.add(btn); else row2.add(btn);
        }
        updateBucketButtonStates("Overall");

        JPanel chipsContainer = new JPanel();
        chipsContainer.setLayout(new BoxLayout(chipsContainer, BoxLayout.Y_AXIS));
        chipsContainer.setAlignmentX(LEFT_ALIGNMENT);
        row1.setAlignmentX(LEFT_ALIGNMENT);
        row2.setAlignmentX(LEFT_ALIGNMENT);
        chipsContainer.add(row1);
        chipsContainer.add(Box.createVerticalStrut(4));
        chipsContainer.add(row2);
        additionalStatsPanel.add(chipsContainer);
        
        // Tier Graph title - left aligned
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel tierTitle = new JLabel("Tier Graph");
        tierTitle.setFont(tierTitle.getFont().deriveFont(Font.BOLD, 14f));
        titlePanel.add(tierTitle);
        additionalStatsPanel.add(titlePanel);
        additionalStatsPanel.add(Box.createVerticalStrut(8));
        
        tierGraphPanel = createTierGraph();
        // Fit within ~240px width and avoid scrollbars by constraining preferred size
        tierGraphPanel.setPreferredSize(new Dimension(240, 240));
        additionalStatsPanel.add(tierGraphPanel);
        
        // Preview button removed per request
        
        return additionalStatsPanel;
    }
    
    private JPanel createMatchHistory()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Match History"));
        
        String[] columns = {"Res", "Opponent", "Type", "Match", "Change", "Time"};
        tableModel = new DefaultTableModel(columns, 0);
        matchHistoryTable = new JTable(tableModel);
        matchHistoryTable.setFillsViewportHeight(true);
        matchHistoryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        try {
            matchHistoryTable.getColumnModel().getColumn(0).setPreferredWidth(40);
            matchHistoryTable.getColumnModel().getColumn(1).setPreferredWidth(140);
            matchHistoryTable.getColumnModel().getColumn(2).setPreferredWidth(60);
            matchHistoryTable.getColumnModel().getColumn(3).setPreferredWidth(220);
            matchHistoryTable.getColumnModel().getColumn(4).setPreferredWidth(180);
            matchHistoryTable.getColumnModel().getColumn(5).setPreferredWidth(120);
        } catch (Exception ignore) {}

        JScrollPane scrollPane = new JScrollPane(matchHistoryTable);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(0, 300));
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private void handleLogin()
    {
        if (loginInProgress) { return; }
        if (isLoggedIn)
        {
            // Logout
            showAdditionalStats(false);
            loginButton.setText("Login to view stats in runelite");
            // Plugin search always enabled now
            pluginSearchField.setText("");
            clearTokens();
            return;
        }
        
        // Use Cognito OAuth flow
        try
        {
            if (authService == null) {
                okhttp3.OkHttpClient ok = net.runelite.client.RuneLite.getInjector().getInstance(okhttp3.OkHttpClient.class);
                com.google.gson.Gson gs = net.runelite.client.RuneLite.getInjector().getInstance(com.google.gson.Gson.class);
                java.util.concurrent.ScheduledExecutorService sched = net.runelite.client.RuneLite.getInjector().getInstance(java.util.concurrent.ScheduledExecutorService.class);
                authService = new CognitoAuthService(configManager, ok, gs, sched);
            }
            setLoginBusy(true);
            authService.login().thenAccept(success -> {
                if (success && authService != null && authService.isLoggedIn() && authService.getStoredIdToken() != null)
                {
                    SwingUtilities.invokeLater(() -> {
                        setLoginBusy(false);
                        completeLogin();
                    });
                }
                else
                {
                    SwingUtilities.invokeLater(() -> {
                        setLoginBusy(false);
                        // UI error popups always allowed
                        // Popup disabled per requirement
                    });
                }
            }).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> {
                    setLoginBusy(false);
                    // Popup disabled per requirement
                });
                return null;
            });
        }
        catch (Exception e)
        {
            // Popup disabled per requirement
        }
    }

    private void setLoginBusy(boolean busy)
    {
        loginInProgress = busy;
        try
        {
            if (loginButton != null)
            {
                loginButton.setEnabled(!busy);
                loginButton.setText(busy ? "Logging in..." : (isLoggedIn ? "Logout" : "Login to view more stats"));
            }
            if (websiteSearchField != null) websiteSearchField.setEnabled(!busy);
            if (pluginSearchField != null) pluginSearchField.setEnabled(!busy);
            if (refreshButton != null) refreshButton.setEnabled(!busy);
        }
        catch (Exception ignore) {}
    }
    
    public void loadMatchHistory(String playerId)
    {
        currentMatchesPlayerId = normalizePlayerId(playerId);
        matchesNextToken = null;

        // Reset UI immediately so stale data isn't shown when a lookup fails or is invalid
        resetUiForNewSearch();

        // Serve from cache immediately if present
        MatchesCache cached = matchesCache.get(currentMatchesPlayerId);
        if (cached != null && cached.matches != null)
        {
            // Inline applyMatchesToUI to avoid method dependency
            tableModel.setRowCount(0);
            int wins = 0, losses = 0, ties = 0;
            JsonArray matches = cached.matches;
            for (int i = 0; i < matches.size(); i++)
            {
                JsonObject match = matches.get(i).getAsJsonObject();
                String result = match.has("result") ? match.get("result").getAsString() : "";
                String opponent = match.has("opponent_id") ? match.get("opponent_id").getAsString() : "";
                String matchType = match.has("bucket") ? match.get("bucket").getAsString().toUpperCase() : "Unknown";
                String playerRank = computeRank(match, "player_");
                String opponentRank = computeRank(match, "opponent_");
                String matchDisplay = playerRank + " vs " + opponentRank;
        String change = computeRatingChangePlain(match);
                String time = match.has("when") ? formatTime(match.get("when").getAsLong()) : "";
                if ("win".equalsIgnoreCase(result)) wins++;
                else if ("loss".equalsIgnoreCase(result)) losses++;
                else if ("tie".equalsIgnoreCase(result)) ties++;
                tableModel.addRow(new Object[]{result, opponent, matchType, matchDisplay, change, time});
            }
            updatePerformanceStats(wins, losses, ties);
            updateWinRateChart(matches);
            updateRankBreakdown(matches);
            allMatches = matches;
            updateBucketBarsFromMatches();
            if (extraStatsPanel != null) {
                extraStatsPanel.setMatches(matches);
            }
            matchesNextToken = cached.nextToken;
            if (loadMoreButton != null) loadMoreButton.setVisible(matchesNextToken != null && !matchesNextToken.isEmpty());
        }

        // Begin async loads using OkHttp
        try {
            String normalizedId = currentMatchesPlayerId;
            loadPlayerStats(normalizedId);

            String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/matches?player_id=" + URLEncoder.encode(normalizedId, "UTF-8") + "&limit=" + MATCHES_PAGE_SIZE;
            Request req = new Request.Builder().url(apiUrl).get().header("Cache-Control","no-store").build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    // Popup disabled per requirement
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (Response res = response) {
                        if (!res.isSuccessful() || res.body() == null) {
                            return;
                        }
                        okhttp3.ResponseBody rb = res.body();
                        String body;
                        try {
                            body = rb != null ? rb.string() : "";
                        } catch (Exception ex) {
                            // Fallback to cached stats
                            JsonObject fallback = getFreshUserStatsFromCache(normalizedId);
                            if (fallback != null) {
                                SwingUtilities.invokeLater(() -> updateProgressBars(fallback));
                            }
                            return;
                        }
                        JsonObject jsonResponse = gson.fromJson(body, JsonObject.class);
                        JsonArray matches = jsonResponse.has("matches") && jsonResponse.get("matches").isJsonArray() ? jsonResponse.getAsJsonArray("matches") : new JsonArray();
                        String nextToken = jsonResponse.has("next_token") && !jsonResponse.get("next_token").isJsonNull() ? jsonResponse.get("next_token").getAsString() : null;

                        SwingUtilities.invokeLater(() -> {
                            tableModel.setRowCount(0);
                            int wins = 0, losses = 0, ties = 0;
                            for (int i = 0; i < matches.size(); i++)
                            {
                                JsonObject match = matches.get(i).getAsJsonObject();
                                String result = match.has("result") ? match.get("result").getAsString() : "";
                                String opponent = match.has("opponent_id") ? match.get("opponent_id").getAsString() : "";
                                String matchType = match.has("bucket") ? match.get("bucket").getAsString().toUpperCase() : "Unknown";
                                String playerRank = computeRank(match, "player_");
                                String opponentRank = computeRank(match, "opponent_");
                                String matchDisplay = playerRank + " vs " + opponentRank;
                                String change = computeRatingChange(match);
                                String time = match.has("when") ? formatTime(match.get("when").getAsLong()) : "";
                                if ("win".equalsIgnoreCase(result)) wins++;
                                else if ("loss".equalsIgnoreCase(result)) losses++;
                                else if ("tie".equalsIgnoreCase(result)) ties++;
                                tableModel.addRow(new Object[]{result, opponent, matchType, matchDisplay, change, time});
                            }
                            updatePerformanceStats(wins, losses, ties);
                            updateWinRateChart(matches);
                            updateRankBreakdown(matches);
                            if (isLoggedIn) {
                                allMatches = matches;
                                updateAdditionalStats(matches);
                            }
                            allMatches = matches;
                            updateBucketBarsFromMatches();
                            if (extraStatsPanel != null) {
                                extraStatsPanel.setMatches(matches);
                            }
                            matchesNextToken = nextToken;
                            if (loadMoreButton != null) {
                                loadMoreButton.setVisible(matchesNextToken != null && !matchesNextToken.isEmpty());
                            }
                            if (currentMatchesPlayerId != null)
                            {
                                matchesCache.put(currentMatchesPlayerId, new MatchesCache(matches.deepCopy(), matchesNextToken, System.currentTimeMillis()));
                            }
                        });
                    }
                }
            });
        } catch (Exception ignore) {}
    }

    private void resetUiForNewSearch()
    {
        try
        {
            String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};
            for (int i = 0; i < progressLabels.length && i < buckets.length; i++)
            {
                if (progressLabels[i] != null)
                {
                    progressLabels[i].setText(buckets[i] + " - — (0.0%)");
                }
                if (progressBars[i] != null)
                {
                    progressBars[i].setValue(0);
                    progressBars[i].setString("0%");
                }
            }

            bucketRankNumbers.clear();
            allMatches = null;

            // Clear performance overview
            updatePerformanceStats(0, 0, 0);

            // Clear rank breakdown table
            if (rankBreakdownModel != null)
            {
                rankBreakdownModel.setRowCount(0);
            }

            // Clear additional stats
            if (highestRankLabel != null) highestRankLabel.setText("-");
            if (highestRankTimeLabel != null) highestRankTimeLabel.setText("-");
            if (lowestRankLabel != null) lowestRankLabel.setText("-");
            if (lowestRankTimeLabel != null) lowestRankTimeLabel.setText("-");
            if (extraStatsPanel != null)
            {
                extraStatsPanel.setMatches(new com.google.gson.JsonArray());
            }

            // Reset tier graph
            tierHistory = new java.util.ArrayList<>();
            updateTierGraphDisplay();
        }
        catch (Exception ignore) {}
    }

    @SuppressWarnings("unused")
    private void applyMatchesToUI(JsonArray matches, String nextToken, boolean updateCache)
    {
        tableModel.setRowCount(0);
        int wins = 0, losses = 0, ties = 0;

        for (int i = 0; i < matches.size(); i++)
        {
            JsonObject match = matches.get(i).getAsJsonObject();
            String result = match.has("result") ? match.get("result").getAsString() : "";
            String opponent = match.has("opponent_id") ? match.get("opponent_id").getAsString() : "";
            String matchType = match.has("bucket") ? match.get("bucket").getAsString().toUpperCase() : "Unknown";
            String playerRank = computeRank(match, "player_");
            String opponentRank = computeRank(match, "opponent_");
            String matchDisplay = playerRank + " vs " + opponentRank;
            String change = computeRatingChange(match);
            String time = match.has("when") ? formatTime(match.get("when").getAsLong()) : "";

            if ("win".equalsIgnoreCase(result)) wins++;
            else if ("loss".equalsIgnoreCase(result)) losses++;
            else if ("tie".equalsIgnoreCase(result)) ties++;

            tableModel.addRow(new Object[]{result, opponent, matchType, matchDisplay, change, time});
        }

        updatePerformanceStats(wins, losses, ties);
        updateWinRateChart(matches);
        updateRankBreakdown(matches);

        if (isLoggedIn) {
            allMatches = matches;
            updateAdditionalStats(matches);
        }

        allMatches = matches;
        updateBucketBarsFromMatches();

        matchesNextToken = nextToken;
        if (loadMoreButton != null) {
            loadMoreButton.setVisible(matchesNextToken != null && !matchesNextToken.isEmpty());
        }
        if (updateCache && currentMatchesPlayerId != null) {
            matchesCache.put(currentMatchesPlayerId, new MatchesCache(matches, matchesNextToken, System.currentTimeMillis()));
        }
        if (extraStatsPanel != null) {
            extraStatsPanel.setMatches(matches);
        }
    }
    
    private String computeRank(JsonObject match, String prefix)
    {
        if (match.has(prefix + "rank"))
        {
            String rank = match.get(prefix + "rank").getAsString();
            int division = match.has(prefix + "division") ? match.get(prefix + "division").getAsInt() : 0;
            return rank + (division > 0 ? " " + division : "");
        }
        return "Unknown";
    }
    
    private String computeRatingChange(JsonObject match)
    {
        if (match.has("rating_change"))
        {
            JsonObject ratingChange = match.getAsJsonObject("rating_change");
            
            // Prefer actual MMR delta from backend when present
            if (ratingChange.has("mmr_delta"))
            {
                double mmrDelta = ratingChange.get("mmr_delta").getAsDouble();
                String mmrText = String.format("%+.2f MMR", mmrDelta);
                
                String fromRank = ratingChange.has("from_rank") ? ratingChange.get("from_rank").getAsString() : "";
                String toRank = ratingChange.has("to_rank") ? ratingChange.get("to_rank").getAsString() : "";
                int fromDiv = ratingChange.has("from_division") ? ratingChange.get("from_division").getAsInt() : 0;
                int toDiv = ratingChange.has("to_division") ? ratingChange.get("to_division").getAsInt() : 0;
                
                String fromLabel = fromRank + (fromDiv > 0 ? " " + fromDiv : "");
                String toLabel = toRank + (toDiv > 0 ? " " + toDiv : "");
                
                if (!fromLabel.trim().isEmpty() || !toLabel.trim().isEmpty())
                {
                    return mmrText + " | " + (fromLabel.trim().isEmpty() ? "?" : fromLabel) + " → " + (toLabel.trim().isEmpty() ? "?" : toLabel);
                }
                return mmrText;
            }
            
            // Fallback to progress-based calculation
            String fromRank = ratingChange.has("from_rank") ? ratingChange.get("from_rank").getAsString() : "";
            String toRank = ratingChange.has("to_rank") ? ratingChange.get("to_rank").getAsString() : "";
            int fromDiv = ratingChange.has("from_division") ? ratingChange.get("from_division").getAsInt() : 0;
            int toDiv = ratingChange.has("to_division") ? ratingChange.get("to_division").getAsInt() : 0;
            double progressChange = ratingChange.has("progress_change") ? ratingChange.get("progress_change").getAsDouble() : 0;
            
            String fromLabel = fromRank + (fromDiv > 0 ? " " + fromDiv : "");
            String toLabel = toRank + (toDiv > 0 ? " " + toDiv : "");
            
            // Calculate progress percentages
            double playerMmr = match.has("player_mmr") ? match.get("player_mmr").getAsDouble() : 0;
            double afterProg = calculateProgressFromMMR(playerMmr);
            double beforeProg = afterProg - progressChange;
            
            // Handle rank wrapping for cross-rank changes
            String fromKey = fromRank + "|" + fromDiv;
            String toKey = toRank + "|" + toDiv;
            if (!fromKey.equals(toKey) && Math.abs(progressChange) > 0)
            {
                String result = match.has("result") ? match.get("result").getAsString().toLowerCase() : "";
                int signShouldBe = "win".equals(result) ? 1 : ("loss".equals(result) ? -1 : (int)Math.signum(progressChange));
                if (Math.signum(progressChange) != signShouldBe)
                {
                    beforeProg = afterProg + (100 - Math.abs(progressChange)) * signShouldBe;
                }
            }
            
            beforeProg = Math.max(0, Math.min(100, beforeProg));
            
            String progressLine = (fromLabel.trim().isEmpty() ? "?" : fromLabel) + " (" + Math.round(beforeProg) + "%) → " + 
                                 (toLabel.trim().isEmpty() ? "?" : toLabel) + " (" + Math.round(afterProg) + "%)";
            
            // Calculate wrapped delta
            int fromIdx = getRankIndex(fromRank, fromDiv);
            int toIdx = getRankIndex(toRank, toDiv);
            double rawDelta = (afterProg - beforeProg) + (toIdx - fromIdx) * 100;
            
            String result = match.has("result") ? match.get("result").getAsString().toLowerCase() : "";
            String deltaText = "tie".equals(result) ? "0% change" : 
                              String.format("%+.2f%% change", rawDelta);
            
            return progressLine + " | " + deltaText;
        }
        return "-";
    }

    // Plain variant for Match History table to avoid HTML snippets
    private String computeRatingChangePlain(JsonObject match)
    {
        String text = computeRatingChange(match);
        return text.replace("<br><small>", " | ").replace("</small>", "");
    }
    
    private double calculateProgressFromMMR(double mmr)
    {
        String[][] thresholds = {
            {"Bronze", "3", "0"}, {"Bronze", "2", "170"}, {"Bronze", "1", "240"},
            {"Iron", "3", "310"}, {"Iron", "2", "380"}, {"Iron", "1", "450"},
            {"Steel", "3", "520"}, {"Steel", "2", "590"}, {"Steel", "1", "660"},
            {"Black", "3", "730"}, {"Black", "2", "800"}, {"Black", "1", "870"},
            {"Mithril", "3", "940"}, {"Mithril", "2", "1010"}, {"Mithril", "1", "1080"},
            {"Adamant", "3", "1150"}, {"Adamant", "2", "1250"}, {"Adamant", "1", "1350"},
            {"Rune", "3", "1450"}, {"Rune", "2", "1550"}, {"Rune", "1", "1650"},
            {"Dragon", "3", "1750"}, {"Dragon", "2", "1850"}, {"Dragon", "1", "1950"},
            {"3rd Age", "0", "2100"}
        };
        
        String[] current = thresholds[0];
        for (String[] threshold : thresholds)
        {
            if (mmr >= Double.parseDouble(threshold[2]))
            {
                current = threshold;
            }
            else
            {
                break;
            }
        }
        
        if ("3rd Age".equals(current[0]))
        {
            return 100.0;
        }
        
        int currentIndex = -1;
        for (int i = 0; i < thresholds.length; i++)
        {
            if (thresholds[i][0].equals(current[0]) && thresholds[i][1].equals(current[1]))
            {
                currentIndex = i;
                break;
            }
        }
        
        if (currentIndex >= 0 && currentIndex < thresholds.length - 1)
        {
            double currentThreshold = Double.parseDouble(current[2]);
            double nextThreshold = Double.parseDouble(thresholds[currentIndex + 1][2]);
            double span = nextThreshold - currentThreshold;
            return Math.max(0, Math.min(100, ((mmr - currentThreshold) / span) * 100));
        }
        
        return 0.0;
    }
    
    private int getRankIndex(String rank, int division)
    {
        String[][] thresholds = {
            {"Bronze", "3"}, {"Bronze", "2"}, {"Bronze", "1"},
            {"Iron", "3"}, {"Iron", "2"}, {"Iron", "1"},
            {"Steel", "3"}, {"Steel", "2"}, {"Steel", "1"},
            {"Black", "3"}, {"Black", "2"}, {"Black", "1"},
            {"Mithril", "3"}, {"Mithril", "2"}, {"Mithril", "1"},
            {"Adamant", "3"}, {"Adamant", "2"}, {"Adamant", "1"},
            {"Rune", "3"}, {"Rune", "2"}, {"Rune", "1"},
            {"Dragon", "3"}, {"Dragon", "2"}, {"Dragon", "1"},
            {"3rd Age", "0"}
        };
        
        for (int i = 0; i < thresholds.length; i++)
        {
            if (thresholds[i][0].equals(rank) && Integer.parseInt(thresholds[i][1]) == division)
            {
                return i;
            }
        }
        return 0;
    }
    
    private String formatTime(long timestamp)
    {
        return new SimpleDateFormat("MM/dd/yyyy, HH:mm:ss").format(new Date(timestamp * 1000));
    }
    
    public String getUsername()
    {
        return pluginSearchField.getText();
    }
    
    public String getPassword()
    {
        return new String(passwordField.getPassword());
    }
    
    private void loadPlayerStats(String playerId)
    {
        try
        {
            // Serve from in-memory cache immediately, to keep UI populated if backend throttles
            try {
                JsonObject cached = getFreshUserStatsFromCache(playerId);
                if (cached != null) {
                    SwingUtilities.invokeLater(() -> {
                        updateProgressBars(cached);
                        // updateProgressBars will call updatePlayerStats which schedules rank number
                    });
                }
            } catch (Exception ignore) {}
            if (isApiTemporarilyDown())
            {
                return;
            }
			// Use user endpoint by player_id like website does (OkHttp + Gson)
            String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/user?player_id=" + URLEncoder.encode(normalizePlayerId(playerId), "UTF-8");
            Request.Builder reqBuilder = new Request.Builder().url(apiUrl).get().header("Cache-Control","no-store");
            if (plugin != null && plugin.getClientUniqueId() != null)
            {
                reqBuilder.header("X-Client-Unique-Id", plugin.getClientUniqueId());
            }
            Request req = reqBuilder.build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    // Fallback to cached stats if available; otherwise mark API briefly down
                    JsonObject fallback = getFreshUserStatsFromCache(playerId);
                    if (fallback != null) {
                        SwingUtilities.invokeLater(() -> updateProgressBars(fallback));
                    } else {
                        markApiDownForMillis(10_000L);
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (Response res = response) {
                        if (!res.isSuccessful() || res.body() == null) {
                            JsonObject fallback = getFreshUserStatsFromCache(playerId);
                            if (fallback != null) {
                                SwingUtilities.invokeLater(() -> updateProgressBars(fallback));
                            }
                            return;
                        }
                        okhttp3.ResponseBody rb = res.body();
                        String body;
                        try {
                            body = rb != null ? rb.string() : "";
                        } catch (Exception ex) {
                            JsonObject fallback = getFreshUserStatsFromCache(playerId);
                            if (fallback != null) {
                                SwingUtilities.invokeLater(() -> updateProgressBars(fallback));
                            }
                            return;
                        }
                        if (body == null || body.isEmpty()) return;
                        JsonObject stats = gson.fromJson(body, JsonObject.class);
                        if (stats == null) return;
                        if (stats.has("account_hash") && !stats.get("account_hash").isJsonNull()) {
                            try { lastLoadedAccountHash = stats.get("account_hash").getAsString(); } catch (Exception ignore) {}
                        }
                        // Cache fresh stats for 60s to serve future lookups and as failure fallback
                        putUserStatsInCache(playerId, stats);

                        SwingUtilities.invokeLater(() -> {
                            updateProgressBars(stats);
                        });
                    }
                }
            });
        }
        // Network errors are handled in the OkHttp callback; keep a broad fallback here
        catch (Exception e)
        {
            markApiDownForMillis(10_000L);
        }
    }
    
    private void updateProgressBars(JsonObject stats)
    {
        String playerName = null;
        if (stats.has("player_name"))
        {
            playerName = stats.get("player_name").getAsString();
        }
        else if (stats.has("player_id"))
        {
            playerName = stats.get("player_id").getAsString();
        }
        
        if (playerName != null)
        {
            updatePlayerStats(stats, playerName);
        }
    }
    
    private void updatePlayerStats(JsonObject stats, String playerName)
    {
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws Exception
            {
                // Overall from top-level profile stats (MMR) - like website
                if (stats.has("mmr"))
                {
                    double mmr = stats.get("mmr").getAsDouble();
                    RankInfo overall = rankLabelAndProgressFromMMR(mmr);
                    
                    // Show progress immediately without waiting for network
                    SwingUtilities.invokeLater(() -> setBucketBar("overall", overall.rank, overall.division, overall.progress));

                    // Fetch rank number asynchronously and update label when ready
                    new SwingWorker<Integer, Void>()
                    {
                        @Override
                        protected Integer doInBackground() throws Exception
                        {
                            return getRankNumberFromLeaderboard(playerName, "overall");
                        }

                        @Override
                        protected void done()
                        {
                            try
                            {
                                int rankNumber = get();
                                int idx = getBucketIndex("overall");
                                if (idx >= 0 && rankNumber > 0)
                                {
                                    updateRankNumber(idx, rankNumber);
                                }
                            }
                            catch (Exception ignore) {}
                        }
                    }.execute();
                }
                
                JsonObject bucketsObj = null;
                try {
                    if (stats.has("buckets") && stats.get("buckets").isJsonObject())
                    {
                        bucketsObj = stats.getAsJsonObject("buckets");
                    }
                } catch (Exception ignore) {}

                String[] bucketKeys = {"nh", "veng", "multi", "dmm"};
                for (String bucketKey : bucketKeys)
                {
                    JsonObject bucketObj = null;
                    try {
                        if (bucketsObj != null && bucketsObj.has(bucketKey) && bucketsObj.get(bucketKey).isJsonObject())
                        {
                            bucketObj = bucketsObj.getAsJsonObject(bucketKey);
                        }
                    } catch (Exception ignore) {}
                    applyBucketStatsFromUser(playerName, bucketKey, bucketObj);
                }

                return null;
            }
        };
        worker.execute();
    }
    
    private void updateBucketBarsFromMatches()
    {
        if (allMatches == null || allMatches.size() == 0) return;
        
        String playerName = playerNameLabel.getText();
        if (playerName == null || playerName.equals("Player Name")) return;
        
                String[] buckets = {"overall", "nh", "veng", "multi", "dmm"};
        for (String bucket : buckets)
        {
            JsonArray items = new JsonArray();
            for (int i = 0; i < allMatches.size(); i++)
            {
                JsonObject match = allMatches.get(i).getAsJsonObject();
                String matchBucket = match.has("bucket") ? match.get("bucket").getAsString().toLowerCase() : "";
                if (matchBucket.equals(bucket))
                {
                    items.add(match);
                }
            }
            
            if (items.size() == 0)
            {
                continue;
            }
            
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
                RankInfo est = rankLabelAndProgressFromMMR(mmr);
                
                String finalRank = latest.has("player_rank") ? latest.get("player_rank").getAsString() : est.rank;
                int finalDiv = latest.has("player_division") ? latest.get("player_division").getAsInt() : est.division;
                double pct = est.progress;
                
                // Only fetch rank number for currently selected rank bucket to avoid multi-bucket shard loads
                String currentBucket = bucketKey(config != null ? config.rankBucket() : null);
                if (bucket.equals(currentBucket))
                {
                SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>()
                {
                    @Override
                    protected Integer doInBackground() throws Exception
                    {
                        return getRankNumberFromLeaderboard(playerName, bucket);
                    }
                    
                    @Override
                    protected void done()
                    {
                        try
                        {
                            int rankNumber = get();
                            setBucketBarWithRank(bucket, finalRank, finalDiv, pct, rankNumber);
                        }
                        catch (Exception e)
                        {
                            setBucketBar(bucket, finalRank, finalDiv, pct);
                        }
                    }
                };
                worker.execute();
                }
                else
                {
                    setBucketBar(bucket, finalRank, finalDiv, pct);
                }
            }
        }
    }
    
    public int getRankNumberFromLeaderboard(String playerName, String bucket)
    {
        try
        {
            String canonBucket = bucket == null ? "overall" : bucket.toLowerCase();
            String playerKey = normalizeDisplayName(playerName);
            String cacheKey = (canonBucket + "|" + playerKey);
            // For overall, prefer world rank via /user; do not use shards
            if ("overall".equals(canonBucket))
            {
                // Use acct_sha when available for self; else use player name
                String acctSha = null;
                try {
                    if (lastLoadedAccountHash != null && !lastLoadedAccountHash.isEmpty()) {
                        acctSha = generateAccountSha(lastLoadedAccountHash);
                    }
                } catch (Exception ignore) {}
                int wr = getWorldRank(playerKey, acctSha);
                return wr > 0 ? wr : -1;
            }
            // For per-bucket, try shard via account when available
            int cached = getCachedRankNumber(cacheKey);
            if (cached > 0) return cached;
            ShardRank sr = getRankTupleFromShard(playerName, (lastLoadedAccountHash != null ? lastLoadedAccountHash : null), canonBucket);
            if (sr != null && sr.rank > 0)
            {
                putCachedRankNumber(cacheKey, sr.rank);
                return sr.rank;
            }
            // Fallback to API rank endpoint
            int apiRank = fetchRankIndexFromApi(playerKey, canonBucket);
            if (apiRank > 0) {
                putCachedRankNumber(cacheKey, apiRank);
                return apiRank;
            }
        }
        catch (Exception ignore) {}
        return -1;
    }

    public int getCachedRankNumberByName(String playerName, String bucket)
    {
        try
        {
            String canonBucket = bucket == null ? "overall" : bucket.toLowerCase();
            String playerKey = normalizeDisplayName(playerName);
            String cacheKey = (canonBucket + "|" + playerKey);
            
            if ("overall".equals(canonBucket))
            {
                // Check world rank cache
                String key = "name:" + playerKey;
                WorldRankCache c = worldRankCache.get(key);
                if (c != null && (System.currentTimeMillis() - c.ts) <= WORLD_RANK_CACHE_TTL_MS) {
                    return c.worldRank;
                }
                return -1;
            }
            
            return getCachedRankNumber(cacheKey);
        }
        catch (Exception ignore) {}
        return -1;
    }

    public int getRankNumberByName(String playerName, String bucket)
    {
        try
        {
            String canonBucket = bucket == null ? "overall" : bucket.toLowerCase();
            String playerKey = normalizeDisplayName(playerName);
            String cacheKey = (canonBucket + "|" + playerKey);
            // For overall, prefer world rank via /user; do not use shards
            if ("overall".equals(canonBucket))
            {
                int wr = getWorldRank(playerKey, null);
                return wr > 0 ? wr : -1;
            }
            // Name-only lookups: skip shard (no acct_sha) and use API directly
            int cached = getCachedRankNumber(cacheKey);
            if (cached > 0) return cached;
            int apiRank = fetchRankIndexFromApi(playerKey, canonBucket);
            if (apiRank > 0) {
                putCachedRankNumber(cacheKey, apiRank);
                return apiRank;
            }
        }
        catch (Exception ignore) {}
        return -1;
    }

    private void applyBucketStatsFromUser(String playerName, String bucketKey, JsonObject bucketObj)
    {
        try
        {
            if (bucketObj == null)
            {
                SwingUtilities.invokeLater(() -> setBucketBar(bucketKey, "—", 0, 0));
                return;
            }

            String rankLabel = null;
            int division = 0;
            double pct = 0.0;

            if (bucketObj.has("mmr") && !bucketObj.get("mmr").isJsonNull())
            {
                double mmr = bucketObj.get("mmr").getAsDouble();
                RankInfo ri = rankLabelAndProgressFromMMR(mmr);
                if (ri != null)
                {
                    rankLabel = ri.rank;
                    division = ri.division;
                    pct = ri.progress;
                }
            }

            if (bucketObj.has("rank_progress") && bucketObj.get("rank_progress").isJsonObject())
            {
                try
                {
                    JsonObject rp = bucketObj.getAsJsonObject("rank_progress");
                    if (rp.has("progress_to_next_rank_pct") && !rp.get("progress_to_next_rank_pct").isJsonNull())
                    {
                        pct = Math.max(0.0, Math.min(100.0, rp.get("progress_to_next_rank_pct").getAsDouble()));
                    }
                }
                catch (Exception ignore) {}
            }

            if ((rankLabel == null || rankLabel.isEmpty()) && bucketObj.has("rank") && !bucketObj.get("rank").isJsonNull())
            {
                String formatted = formatTierLabel(bucketObj.get("rank").getAsString());
                String[] parts = formatted.split(" ");
                rankLabel = parts.length > 0 ? parts[0] : formatted;
                if (parts.length > 1)
                {
                    try { division = Integer.parseInt(parts[1]); } catch (Exception ignore) {}
                }
            }

            if (bucketObj.has("division") && !bucketObj.get("division").isJsonNull())
            {
                try { division = bucketObj.get("division").getAsInt(); } catch (Exception ignore) {}
            }

            final String resolvedRank = (rankLabel != null && !rankLabel.isEmpty()) ? rankLabel : "—";
            final int resolvedDivision = division;
            final double resolvedPct = Math.max(0.0, Math.min(100.0, pct));

            SwingUtilities.invokeLater(() -> setBucketBar(bucketKey, resolvedRank, resolvedDivision, resolvedPct));

            if (!"—".equals(resolvedRank))
            {
                new SwingWorker<Integer, Void>()
                {
                    @Override
                    protected Integer doInBackground() throws Exception
                    {
                        return getRankNumberFromLeaderboard(playerName, bucketKey);
                    }

                    @Override
                    protected void done()
                    {
                        try
                        {
                            int rankNumber = get();
                            if (rankNumber > 0)
                            {
                                setBucketBarWithRank(bucketKey, resolvedRank, resolvedDivision, resolvedPct, rankNumber);
                            }
                        }
                        catch (Exception ignore) {}
                    }
                }.execute();
            }
        }
        catch (Exception ignore) {}
    }

    public String getTierLabelFromLeaderboard(String playerName, String bucket)
    {
        ShardRank sr = getRankTupleFromShard(playerName, (lastLoadedAccountHash != null ? lastLoadedAccountHash : null), bucket);
        return sr != null ? sr.tier : null;
    }

    public String getTierLabelByName(String playerName, String bucket)
    {
        // Name-only: shard is skipped; attempt API when needed
        String canonBucket = bucket == null ? "overall" : bucket.toLowerCase();
        if ("overall".equals(canonBucket))
        {
            // For overall, tier/division still computed from MMR via profile fetch when needed
            // Fallback to existing API calls elsewhere
        }
        ShardRank sr = getRankTupleFromShard(playerName, null, bucket);
        if (sr != null && sr.tier != null) return sr.tier;
        // Fallback to API for tier/rank name when shard miss
        try { return fetchSelfTierFromApi(playerName, bucket); } catch (Exception ignore) { return null; }
    }

    // Fallback for self: fetch tier via API when shard rank is missing
    public String fetchSelfTierFromApi(String playerName, String bucket)
    {
        try
        {
            String canonBucket = bucket == null ? "overall" : bucket.toLowerCase();
            String pid = normalizePlayerId(playerName);
            if (pid == null || pid.isEmpty()) return null;

            // Use /user endpoint for all buckets as it contains the full breakdown
            // Use 'player' parameter which supports name lookup (player_id seemed flaky in tests)
            String apiUrl = API_BASE + "/user?player=" + URLEncoder.encode(pid, "UTF-8");
            
            Request req = new Request.Builder().url(apiUrl).get().build();
            final java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) { fut.complete(null); }
                @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (Response res = response) {
                        if (!res.isSuccessful() || res.body() == null) { fut.complete(null); return; }
                        okhttp3.ResponseBody rb = res.body();
                        String body = rb != null ? rb.string() : "";
                        fut.complete(body);
                    }
                }
            });
            String response = fut.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (response == null || response.isEmpty()) return null;
            
            JsonObject stats = gson.fromJson(response, JsonObject.class);
            if (stats == null) return null;

            JsonObject targetObj = stats; // Default to root (mirrors overall)
            
            // If specific bucket requested, look in 'buckets' map
            if (!"overall".equals(canonBucket)) {
                 if (stats.has("buckets") && !stats.get("buckets").isJsonNull()) {
                     JsonObject bucketsObj = stats.getAsJsonObject("buckets");
                     if (bucketsObj.has(canonBucket) && !bucketsObj.get(canonBucket).isJsonNull()) {
                         targetObj = bucketsObj.getAsJsonObject(canonBucket);
                     } else {
                         return null; // Bucket not found
                     }
                 } else {
                     return null; // No buckets map
                 }
            } else {
                // For overall, check if we should prefer root or buckets.overall
                // Root properties usually mirror overall. Let's stick to root but if missing, check buckets.
                 if ((!stats.has("rank") || stats.get("rank").isJsonNull()) && stats.has("buckets")) {
                     JsonObject bucketsObj = stats.getAsJsonObject("buckets");
                     if (bucketsObj.has("overall") && !bucketsObj.get("overall").isJsonNull()) {
                         targetObj = bucketsObj.getAsJsonObject("overall");
                     }
                 }
            }
            
            // Extract rank and division
            String rank = targetObj.has("rank") && !targetObj.get("rank").isJsonNull() ? targetObj.get("rank").getAsString() : null;
            int div = targetObj.has("division") && !targetObj.get("division").isJsonNull() ? targetObj.get("division").getAsInt() : 0;
            
            if (rank != null && !rank.isEmpty()) {
                return rank + (div > 0 ? " " + div : "");
            }
            
            // Fallback: if no explicit rank string, try MMR for overall/root
            if (targetObj.has("mmr") && !targetObj.get("mmr").isJsonNull())
            {
                double mmr = targetObj.get("mmr").getAsDouble();
                if (mmr > 0) {
                    RankInfo ri = rankLabelAndProgressFromMMR(mmr);
                    return ri != null ? (ri.rank + (ri.division > 0 ? " " + ri.division : "")) : null;
                }
            }
            
            return null;
        }
        catch (Exception ignore)
        {
            return null;
        }
    }

    // Alias for general use (not self-only)
    public String fetchTierFromApi(String playerName, String bucket)
    {
        return fetchSelfTierFromApi(playerName, bucket);
    }

    private String lastLoadedAccountHash = null;

    private ShardRank getRankTupleFromShard(String playerName, String accountHash, String bucket)
    {
        // long t0 = System.nanoTime();
        try
        {
            boolean useAccount = accountHash != null && !accountHash.isEmpty();
            if (!useAccount)
            {
                // Name-based shards are not reliable; skip shard and use API instead
                return null;
            }
            String accountKey;
            try {
                accountKey = generateAccountSha(accountHash);
            } catch (Exception e) {
                accountKey = accountHash; // fallback
            }
            if (accountKey == null || accountKey.length() < 2) return null;
            String dir = (bucket == null || bucket.trim().isEmpty() || "overall".equalsIgnoreCase(bucket))
                ? "overall"
                : bucket.toLowerCase();
            String shard = accountKey.substring(0, 2).toLowerCase();
            String urlStr = "https://devsecopsautomated.com/rank_idx/" + dir + "/" + shard + ".json";

            String cacheKey = dir + "/" + shard;
            long now = System.currentTimeMillis();
            ShardEntry cached = shardCache.get(cacheKey);
            if (cached != null && now - cached.timestamp < SHARD_CACHE_EXPIRY_MS)
            {
                ShardRank sr = extractShardRank(cached.payload, useAccount, accountKey, null);
                if (sr != null) return sr;
                // Fresh shard cached and account not present → do not refetch until TTL expiry
                return null;
            }

            Long failUntil = shardFailUntil.get(cacheKey);
            if (failUntil != null && now < failUntil) { return null; }
            Long lastReq = shardThrottle.getOrDefault(cacheKey, 0L);
            if (now - lastReq < 60_000L)
            {
                return null;
            }

            // Single-flight per shard
            Object lock = shardLocks.computeIfAbsent(cacheKey, k -> new Object());
            synchronized (lock)
            {
                // re-check cache once we hold the lock
                now = System.currentTimeMillis();
                cached = shardCache.get(cacheKey);
                if (cached != null && now - cached.timestamp < SHARD_CACHE_EXPIRY_MS)
                {
                    ShardRank sr = extractShardRank(cached.payload, useAccount, accountKey, null);
                    if (sr != null) return sr;
                    return null;
                }

                // Global throttle (spacing between network fetches)
                int level = config != null ? Math.max(0, Math.min(10, config.lookupThrottleLevel())) : 0;
                if (level > 0)
                {
                    long delayMs = computeThrottleDelayMs(level);
                    synchronized (globalFetchLock)
                    {
                        long waitMs = lastGlobalFetchMs + delayMs - now;
                        if (waitMs > 0)
                        {
                            try { Thread.sleep(waitMs); } catch (InterruptedException ignore) {}
                            now = System.currentTimeMillis();
                        }
                        lastGlobalFetchMs = now;
                    }
                }

                // Fetch
            Request req = new Request.Builder().url(urlStr).get().build();
            String body;
            try (Response res = httpClient.newCall(req).execute())
            {
                if (res.code() != 200 || res.body() == null)
                {
                    shardFailUntil.put(cacheKey, now + SHARD_FAIL_BACKOFF_MS);
                    shardThrottle.put(cacheKey, now);
                    return null;
                }
                okhttp3.ResponseBody rb = res.body();
                body = rb != null ? rb.string() : "";
            }
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            shardCache.put(cacheKey, new ShardEntry(obj, now));
            shardThrottle.put(cacheKey, now);
                shardFailUntil.remove(cacheKey);
                ShardRank out = extractShardRank(obj, useAccount, accountKey, null);
                // long dtMs = (System.nanoTime() - t0) / 1_000_000L;
                // try { log.info("[ShardFetch] url={} status={} dtMs={} found={} ", urlStr, status, dtMs, (out != null)); } catch (Exception ignore) {}
                if (out == null)
                {
                    try { missingPlayerUntilMs.put((dir + "|" + accountKey), System.currentTimeMillis() + MISSING_PLAYER_BACKOFF_MS); } catch (Exception ignore) {}
                }
                return out;
            }
        }
        catch (java.net.UnknownHostException uhe)
        {
            try {
                long nowEx = System.currentTimeMillis();
                String dir = (bucket == null || bucket.trim().isEmpty() || "overall".equalsIgnoreCase(bucket)) ? "overall" : bucket.toLowerCase();
                String shard = null;
                try { String k = generateAccountSha(accountHash); shard = (k != null && k.length() >= 2) ? k.substring(0,2).toLowerCase() : ""; } catch (Exception ignore) { shard = ""; }
                String cacheKey = dir + "/" + shard;
                shardThrottle.put(cacheKey, nowEx);
                shardFailUntil.put(cacheKey, nowEx + 60_000L);
            } catch (Exception ignore) {}
        }
        catch (java.net.SocketTimeoutException ste)
        {
            try {
                long nowEx = System.currentTimeMillis();
                String dir = (bucket == null || bucket.trim().isEmpty() || "overall".equalsIgnoreCase(bucket)) ? "overall" : bucket.toLowerCase();
                String shard = null;
                try { String k = generateAccountSha(accountHash); shard = (k != null && k.length() >= 2) ? k.substring(0,2).toLowerCase() : ""; } catch (Exception ignore) { shard = ""; }
                String cacheKey = dir + "/" + shard;
                shardThrottle.put(cacheKey, nowEx);
                shardFailUntil.put(cacheKey, nowEx + 30_000L);
            } catch (Exception ignore) {}
        }
        catch (Exception ex)
        {
            try {
                long nowEx = System.currentTimeMillis();
                String dir = (bucket == null || bucket.trim().isEmpty() || "overall".equalsIgnoreCase(bucket)) ? "overall" : bucket.toLowerCase();
                String shard = null;
                try { String k = generateAccountSha(accountHash); shard = (k != null && k.length() >= 2) ? k.substring(0,2).toLowerCase() : ""; } catch (Exception ex2) { shard = ""; }
                String cacheKey = dir + "/" + shard;
                shardThrottle.put(cacheKey, nowEx);
            } catch (Exception ex3) {}
            // Swallow and return null – overlay handles absence gracefully
        }
        return null;
    }

    private ShardRank extractShardRank(JsonObject obj, boolean useAccount, String accountHash, String canon)
    {
        try
        {
            // Only trust account-based map; name-based is optional/non-authoritative
            if (useAccount && obj.has("account_rank_info_map"))
            {
                JsonObject m = obj.getAsJsonObject("account_rank_info_map");
                if (m.has(accountHash))
                {
                    com.google.gson.JsonElement v = m.get(accountHash);
                    return parseShardValue(v);
                }
            }
        }
        catch (Exception ex) {}
        return null;
    }

    private ShardRank parseShardValue(com.google.gson.JsonElement v)
    {
        try
        {
            if (v == null || v.isJsonNull()) return null;
            if (v.isJsonArray())
            {
                com.google.gson.JsonArray arr = v.getAsJsonArray();
                String tier = arr.size() > 0 && !arr.get(0).isJsonNull() ? formatTierLabel(arr.get(0).getAsString()) : null;
                int idx = arr.size() > 1 && !arr.get(1).isJsonNull() ? arr.get(1).getAsInt() : -1;
                if (idx > 0) return new ShardRank(tier, idx);
            }
            else if (v.isJsonPrimitive())
            {
                int idx = v.getAsInt();
                if (idx > 0) return new ShardRank(null, idx);
            }
            else if (v.isJsonObject())
            {
                JsonObject o = v.getAsJsonObject();
                int idx = o.has("index") && !o.get("index").isJsonNull() ? o.get("index").getAsInt() : -1;
                String tier = null;
                if (o.has("tier") && !o.get("tier").isJsonNull())
                {
                    tier = formatTierLabel(o.get("tier").getAsString());
                }
                if ((tier == null || tier.isEmpty()))
                {
                    String r = o.has("rank") && !o.get("rank").isJsonNull() ? o.get("rank").getAsString() : null;
                    int d = o.has("division") && !o.get("division").isJsonNull() ? o.get("division").getAsInt() : 0;
                    if (r != null && !r.isEmpty())
                    {
                        tier = r + (d > 0 ? (" " + d) : "");
                    }
                }
                if (idx > 0) return new ShardRank(tier, idx);
            }
        }
        catch (Exception ignore) {}
        return null;
    }

    private static String formatTierLabel(String raw)
    {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.equalsIgnoreCase("3rdAge")) return "3rd Age";
        return s.replaceAll("([A-Za-z]+)(\\d+)$", "$1 $2");
    }

    private int getCachedRankNumber(String cacheKey)
    {
        try {
            RankNumberCache c = rankNumberCache.get(cacheKey);
            if (c == null) return -1;
            if (System.currentTimeMillis() - c.ts > RANK_NUMBER_CACHE_TTL_MS) return -1;
            return c.rank;
        } catch (Exception ignore) { return -1; }
    }

    private void putCachedRankNumber(String cacheKey, int rank)
    {
        try {
            rankNumberCache.put(cacheKey, new RankNumberCache(rank, System.currentTimeMillis()));
        } catch (Exception ignore) {}
    }

    private int getWorldRank(String playerName, String acctSha)
    {
        try
        {
            String key;
            String url;
            if (acctSha != null && !acctSha.isEmpty())
            {
                key = "acct:" + acctSha;
                WorldRankCache c = worldRankCache.get(key);
                if (c != null && (System.currentTimeMillis() - c.ts) <= WORLD_RANK_CACHE_TTL_MS) {
                    return c.worldRank;
                }
                url = API_BASE + "/user?acct=" + acctSha + "&include_world_rank=1";
            }
            else
            {
                String pid = URLEncoder.encode(normalizePlayerId(playerName), "UTF-8");
                key = "name:" + normalizeDisplayName(playerName);
                WorldRankCache c = worldRankCache.get(key);
                if (c != null && (System.currentTimeMillis() - c.ts) <= WORLD_RANK_CACHE_TTL_MS) {
                    return c.worldRank;
                }
                // New API: user?player=NAME
                url = API_BASE + "/user?player=" + pid + "&include_world_rank=1";
            }

            Request req = new Request.Builder().url(url).get().build();
            try (Response res = httpClient.newCall(req).execute())
            {
                if (res.code() != 200 || res.body() == null) return -1;
                okhttp3.ResponseBody rb = res.body();
                String body = rb != null ? rb.string() : "";
                if (body.isEmpty()) return -1;
                JsonObject obj = gson.fromJson(body, JsonObject.class);
                int wr = -1;
                if (obj != null && obj.has("world_rank") && !obj.get("world_rank").isJsonNull())
                {
                    try { wr = obj.get("world_rank").getAsInt(); } catch (Exception ignore) { wr = -1; }
                }
                if (wr > 0)
                {
                    worldRankCache.put(key, new WorldRankCache(wr, System.currentTimeMillis()));
                }
                return wr;
            }
        }
        catch (Exception ignore) {}
        return -1;
    }

    private int fetchRankIndexFromApi(String playerName, String bucket)
    {
        try
        {
            String pid = URLEncoder.encode(normalizePlayerId(playerName), "UTF-8");
            String apiBucket = URLEncoder.encode(toApiBucket(bucket), "UTF-8");
            String url = API_BASE + "/rank?player_id=" + pid + "&bucket=" + apiBucket;
            Request req = new Request.Builder().url(url).get().build();
            try (Response res = httpClient.newCall(req).execute())
            {
                if (res.code() != 200 || res.body() == null) return -1;
                okhttp3.ResponseBody rb = res.body();
                String body = rb != null ? rb.string() : "";
                if (body.isEmpty()) return -1;
                JsonObject obj = gson.fromJson(body, JsonObject.class);
                if (obj == null) return -1;
                // Accept either 'rank' or 'index' field
                if (obj.has("rank") && !obj.get("rank").isJsonNull())
                {
                    try { int r = obj.get("rank").getAsInt(); return r > 0 ? r : -1; } catch (Exception ignore) {}
                }
                if (obj.has("index") && !obj.get("index").isJsonNull())
                {
                    try { int r = obj.get("index").getAsInt(); return r > 0 ? r : -1; } catch (Exception ignore) {}
                }
            }
        }
        catch (Exception ignore) {}
        return -1;
    }

    private static class ShardRank
    {
        final String tier;
        final int rank;
        ShardRank(String tier, int rank) { this.tier = tier; this.rank = rank; }
    }

    private static class ShardEntry
    {
        final JsonObject payload;
        final long timestamp;
        ShardEntry(JsonObject payload, long timestamp)
        {
            this.payload = payload;
            this.timestamp = timestamp;
        }
    }

    private static long computeThrottleDelayMs(int level)
    {
        if (level <= 0) return 0L;
        if (level >= 10) return 2000L; // level 10 => 2s
        if (level <= 5)
        {
            // Linear from 1->20ms to 5->200ms: +45ms per level step
            return 20L + (long)(level - 1) * 45L; // 1:20,2:65,3:110,4:155,5:200
        }
        // Linear from 5->200ms to 10->2000ms: +360ms per level step
        return 200L + (long)(level - 5) * 360L; // 6:560,7:920,8:1280,9:1640
    }
    
    private RankInfo rankLabelAndProgressFromMMR(double mmrVal)
    {
        String[][] thresholds = {
            {"Bronze", "3", "0"}, {"Bronze", "2", "170"}, {"Bronze", "1", "240"},
            {"Iron", "3", "310"}, {"Iron", "2", "380"}, {"Iron", "1", "450"},
            {"Steel", "3", "520"}, {"Steel", "2", "590"}, {"Steel", "1", "660"},
            {"Black", "3", "730"}, {"Black", "2", "800"}, {"Black", "1", "870"},
            {"Mithril", "3", "940"}, {"Mithril", "2", "1010"}, {"Mithril", "1", "1080"},
            {"Adamant", "3", "1150"}, {"Adamant", "2", "1250"}, {"Adamant", "1", "1350"},
            {"Rune", "3", "1450"}, {"Rune", "2", "1550"}, {"Rune", "1", "1650"},
            {"Dragon", "3", "1750"}, {"Dragon", "2", "1850"}, {"Dragon", "1", "1950"},
            {"3rd Age", "0", "2100"}
        };
        
        double v = mmrVal;
        String[] curr = thresholds[0];
        for (String[] t : thresholds)
        {
            if (v >= Double.parseDouble(t[2])) curr = t;
            else break;
        }
        
        int idx = -1;
        for (int i = 0; i < thresholds.length; i++)
        {
            if (thresholds[i][0].equals(curr[0]) && thresholds[i][1].equals(curr[1]) && thresholds[i][2].equals(curr[2]))
            {
                idx = i;
                break;
            }
        }
        
        String[] next = idx >= 0 && idx < thresholds.length - 1 ? thresholds[idx + 1] : curr;
        double pct = curr[0].equals("3rd Age") ? 100 : 
                    Math.max(0, Math.min(100, ((v - Double.parseDouble(curr[2])) / Math.max(1, Double.parseDouble(next[2]) - Double.parseDouble(curr[2]))) * 100));
        
        return new RankInfo(curr[0], Integer.parseInt(curr[1]), pct);
    }
    
    private void setBucketBarWithRank(String key, String rank, int division, double pct, int rankNumber)
    {
        if (rankNumber > 0) {
            bucketRankNumbers.put(key, rankNumber);
        }
        setBucketBar(key, rank, division, pct);
    }
    
    private void setBucketBar(String key, String rank, int division, double pct)
    {
        int index = getBucketIndex(key);
        if (index < 0 || progressLabels[index] == null || progressBars[index] == null) return;
        
        String rankLabel = rank + (division > 0 ? " " + division : "");
        String bucketName = key.equals("overall") ? "Overall" : key.toUpperCase();
        
        // Build label text with rank number if available
        String labelText = bucketName + " - " + rankLabel;
        Integer rankNumber = bucketRankNumbers.get(key);
        if (rankNumber != null && rankNumber > 0) {
            labelText += " - Rank " + rankNumber;
        }
        
        progressLabels[index].setText(labelText);
        progressLabels[index].setForeground(getRankTextColor(rank));
        
        int pctValue = (int) Math.round(Math.max(0.0, Math.min(100.0, pct)));
        if (pctValue >= 99) {
            pctValue = 100; // ensure full bar visually fills at 100%
        }
        progressBars[index].setMaximum(100);
        progressBars[index].setValue(pctValue);
        progressBars[index].setString(String.format("%.1f%%", pct));
        // Enforce fixed width/height every update to avoid layout managers stretching the bar
        progressBars[index].setPreferredSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
        progressBars[index].setMinimumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
        progressBars[index].setMaximumSize(new Dimension(PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT));
        progressBars[index].setForeground(getRankColor(rank));
        progressBars[index].revalidate();
        progressBars[index].repaint();
    }
    
    private void updateRankNumber(int index, int rankNumber)
    {
        String currentText = progressLabels[index].getText();
        if (!currentText.contains("Rank "))
        {
            progressLabels[index].setText(currentText + " - Rank " + rankNumber);
        }
    }
    
    private int getBucketIndex(String bucket)
    {
        switch (bucket.toLowerCase())
        {
            case "overall": return 0;
            case "nh": return 1;
            case "veng": return 2;
            case "multi": return 3;
            case "dmm": return 4;
            default: return -1;
        }
    }
    

    
    // Removed legacy progress bar helpers; UI uses setBucketBar/rankLabelAndProgressFromMMR
    
    private JPanel createRankBreakdownTable()
    {
        JPanel panel = new JPanel(new BorderLayout());
        
        String[] columns = {"Tier", "K", "D", "KD"};
        rankBreakdownModel = new DefaultTableModel(columns, 0);
        rankBreakdownTable = new JTable(rankBreakdownModel);
        // Compact the table display to fit more rows without scrolling
        try {
            rankBreakdownTable.setRowHeight(16);
        } catch (Exception ignore) {}
        rankBreakdownTable.setFillsViewportHeight(true);
        rankBreakdownTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        rankBreakdownTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        rankBreakdownTable.getColumnModel().getColumn(1).setPreferredWidth(30);
        rankBreakdownTable.getColumnModel().getColumn(2).setPreferredWidth(30);
        rankBreakdownTable.getColumnModel().getColumn(3).setPreferredWidth(40);
        
        JScrollPane scrollPane = new JScrollPane(rankBreakdownTable);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(0, 180));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private void updateRankBreakdown(JsonArray matches)
    {
        if (rankBreakdownModel == null) return;
        
        java.util.Map<String, int[]> rankStats = new java.util.HashMap<>();
        
        for (int i = 0; i < matches.size(); i++)
        {
            JsonObject match = matches.get(i).getAsJsonObject();
            String result = match.has("result") ? match.get("result").getAsString().toLowerCase() : "";
            String opponentRank = computeRank(match, "opponent_");
            
            if (!rankStats.containsKey(opponentRank))
            {
                rankStats.put(opponentRank, new int[]{0, 0}); // [kills, deaths]
            }
            
            int[] stats = rankStats.get(opponentRank);
            if ("win".equals(result))
            {
                stats[0]++; // kills
            }
            else if ("loss".equals(result))
            {
                stats[1]++; // deaths
            }
        }
        
        // Sort by rank order (highest tiers first)
        java.util.List<java.util.Map.Entry<String, int[]>> sortedEntries = new java.util.ArrayList<>(rankStats.entrySet());
        sortedEntries.sort((a, b) -> {
            int orderA = getRankOrder(a.getKey());
            int orderB = getRankOrder(b.getKey());
            return Integer.compare(orderB, orderA); // Descending order (highest first)
        });
        
        rankBreakdownModel.setRowCount(0);
        for (java.util.Map.Entry<String, int[]> entry : sortedEntries)
        {
            String tier = entry.getKey();
            int[] stats = entry.getValue();
            int kills = stats[0];
            int deaths = stats[1];
            String kd = deaths > 0 ? String.format("%.2f", (double) kills / deaths) : String.valueOf(kills);
            
            rankBreakdownModel.addRow(new Object[]{"vs " + tier, kills, deaths, kd});
        }
    }
    
    @SuppressWarnings("unused")
    private void startTokenPolling()
    {
        // Removed (login flow updated to callback-only)
    }
    
    @SuppressWarnings("unused")
    private boolean checkCallbackCompletion()
    {
        // Simulate callback completion after 3 seconds
        // In real implementation, check callback endpoint or local server
        return System.currentTimeMillis() % 10000 > 3000;
    }
    
    private CognitoAuthService authService;
    
    private void completeLogin()
    {
        String username = pluginSearchField.getText();
        
        // Store the token from auth service
        idToken = authService.getStoredIdToken();
        
        playerNameLabel.setText(username);
        showAdditionalStats(true);
        if (!username.isEmpty()) {
            loadMatchHistory(username);
        }
        loginButton.setText("Logout");
        // Plugin search always enabled
    }
    
    private void searchUserOnWebsite()
    {
        String input = websiteSearchField.getText().trim();
        if (input.isEmpty()) return;
        String exact = normalizeDisplayName(input);

        final String fallbackUrl;
        try {
            fallbackUrl = "https://devsecopsautomated.com/profile.html?player=" + URLEncoder.encode(exact, "UTF-8");
        } catch (Exception e) {
            return;
        }

        // Async via OkHttp; UI updates on EDT
        try {
            String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/user?player_id=" + URLEncoder.encode(exact, "UTF-8");
            Request req = new Request.Builder().url(apiUrl).get().header("Cache-Control","no-store").build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    // API failed, fallback to player URL
                    SwingUtilities.invokeLater(() -> {
                        try { Desktop.getDesktop().browse(URI.create(fallbackUrl)); } catch (Exception ignore) {}
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (Response res = response) {
                        if (!res.isSuccessful() || res.body() == null) {
                            SwingUtilities.invokeLater(() -> {
                                try { Desktop.getDesktop().browse(URI.create(fallbackUrl)); } catch (Exception ignore) {}
                            });
                            return;
                        }
                        okhttp3.ResponseBody rb = res.body();
                        String body;
                        try {
                            body = rb != null ? rb.string() : "";
                        } catch (Exception ex) {
                            SwingUtilities.invokeLater(() -> {
                                try { Desktop.getDesktop().browse(URI.create(fallbackUrl)); } catch (Exception ignore) {}
                            });
                            return;
                        }
                        
                        try {
                            JsonObject data = gson.fromJson(body, JsonObject.class);
                            if (data.has("account_hash") && !data.get("account_hash").isJsonNull()) {
                                String accountHash = data.get("account_hash").getAsString();
                                String accountSha = generateAccountSha(accountHash);
                                String profileUrl = "https://devsecopsautomated.com/profile.html?acct=" + accountSha;
                                SwingUtilities.invokeLater(() -> {
                                    try { Desktop.getDesktop().browse(URI.create(profileUrl)); } catch (Exception ignore) {}
                                });
                            } else {
                                // No account hash, fallback
                                SwingUtilities.invokeLater(() -> {
                                    try { Desktop.getDesktop().browse(URI.create(fallbackUrl)); } catch (Exception ignore) {}
                                });
                            }
                        } catch (Exception e) {
                            // JSON parse error or other error, fallback
                            SwingUtilities.invokeLater(() -> {
                                try { Desktop.getDesktop().browse(URI.create(fallbackUrl)); } catch (Exception ignore) {}
                            });
                        }
                    }
                }
            });
        } catch (Exception ignore) {}
    }
    
    private void searchUserOnPlugin()
    {
        String input = pluginSearchField != null ? pluginSearchField.getText().trim() : "";
        if (input.isEmpty() && plugin != null && getClientSafe() != null && getClientSafe().getLocalPlayer() != null)
        {
            input = getClientSafe().getLocalPlayer().getName();
            if (pluginSearchField != null) pluginSearchField.setText(input);
        }
        final String playerName = normalizeDisplayName(input);
        if (playerName.isEmpty()) return;
        // Reset UI immediately so previous player's progress bars don't linger when new user has no data
        resetUiForNewSearch();

        playerNameLabel.setText(playerName);
        
        // Update player name in current profile for rank lookups
        // no-op: ProfileState no longer carries fields
        
        // Use /user API to populate panel first; match history remains as-is
        try {
            String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/user?player_id=" + URLEncoder.encode(normalizePlayerId(playerName), "UTF-8");
            Request req = new Request.Builder().url(apiUrl).get().header("Cache-Control","no-store").build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) { /* popup disabled */ }
                @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (Response res = response) {
                        if (!res.isSuccessful() || res.body() == null) return;
                        okhttp3.ResponseBody rb = res.body();
                        String body = rb != null ? rb.string() : "";
                        if (body.isEmpty()) return;
                        JsonObject stats = gson.fromJson(body, JsonObject.class);
                        if (stats.has("account_hash") && !stats.get("account_hash").isJsonNull()) {
                            try { lastLoadedAccountHash = stats.get("account_hash").getAsString(); } catch (Exception ignore) {}
                        }
                        String[] buckets = new String[]{"overall","nh","veng","multi","dmm"};
                        JsonObject bucketsObj = null;
                        try { if (stats.has("buckets") && stats.get("buckets").isJsonObject()) bucketsObj = stats.getAsJsonObject("buckets"); } catch (Exception ignore) {}
                        for (String key : buckets) {
                            String rankLabel = null; int division = 0; double pct = 0.0;
                            if (bucketsObj != null && bucketsObj.has(key) && bucketsObj.get(key).isJsonObject()) {
                                JsonObject b = bucketsObj.getAsJsonObject(key);
                                try {
                                    if (b.has("mmr") && !b.get("mmr").isJsonNull()) {
                                        double mmr = b.get("mmr").getAsDouble();
                                        RankInfo ri = rankLabelAndProgressFromMMR(mmr);
                                        if (ri != null) { rankLabel = ri.rank; division = ri.division; pct = ri.progress; }
                                    }
                                    if (b.has("rank_progress") && b.get("rank_progress").isJsonObject()) {
                                        try {
                                            JsonObject rp = b.getAsJsonObject("rank_progress");
                                            if (rp.has("progress_to_next_rank_pct") && !rp.get("progress_to_next_rank_pct").isJsonNull()) {
                                                pct = Math.max(0, Math.min(100, rp.get("progress_to_next_rank_pct").getAsDouble()));
                                            }
                                        } catch (Exception ignore) {}
                                    }
                                    if (rankLabel == null && b.has("rank") && !b.get("rank").isJsonNull()) {
                                        String raw = b.get("rank").getAsString();
                                        String formatted = formatTierLabel(raw);
                                        String[] parts = formatted.split(" ");
                                        rankLabel = parts.length > 0 ? parts[0] : formatted;
                                        if (parts.length > 1) { try { division = Integer.parseInt(parts[1]); } catch (Exception ignore) {} }
                                    }
                                    if (b.has("division") && !b.get("division").isJsonNull()) {
                                        try { division = b.get("division").getAsInt(); } catch (Exception ignore) {}
                                    }
                                } catch (Exception ignore) {}
                            }
                            // World rank number is legacy; do not use. Display only rank/division/progress.
                            if (rankLabel != null) {
                                final String fKey = key; final String fRank = rankLabel; final int fDiv = division; final double fPct = pct;
                                // Synchronously derive Rank # from shard for this bucket
                                int rankNum = -1;
                                try { rankNum = getRankNumberFromLeaderboard(playerName, fKey); } catch (Exception ignore) {}
                                final int fRankNum = rankNum;
                                SwingUtilities.invokeLater(() -> {
                                    if (fRankNum > 0) setBucketBarWithRank(fKey, fRank, fDiv, fPct, fRankNum);
                                    else setBucketBar(fKey, fRank, fDiv, fPct);
                                });
                            } else {
                                final String fKey = key;
                                SwingUtilities.invokeLater(() -> setBucketBar(fKey, "—", 0, 0));
                            }
                        }
                    }
                    // keep behavior: load match history and show extra stats if logged in
                    SwingUtilities.invokeLater(() -> {
                        loadMatchHistory(playerName);
                        if (isLoggedIn) showAdditionalStats(true);
                    });
                }
            });
        } catch (Exception ignore) {}
    }

    public void lookupPlayerFromRightClick(String name)
    {
        String exact = normalizeDisplayName(name);
        if (websiteSearchField != null)
        {
            websiteSearchField.setText(exact);
        }
        if (pluginSearchField != null)
        {
            pluginSearchField.setText(exact);
        }
        searchUserOnPlugin();
    }

    public void preloadSelfRankNumbers(String displayName)
    {
        final String playerName = normalizeDisplayName(displayName);
        if (playerName.isEmpty()) return;
        try {
            String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/user?player_id=" + URLEncoder.encode(normalizePlayerId(playerName), "UTF-8");
            Request req = new Request.Builder().url(apiUrl).get().header("Cache-Control","no-store").build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) { }
                @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (Response res = response) {
                        if (!res.isSuccessful() || res.body() == null) return;
                        okhttp3.ResponseBody rb = res.body();
                        String body;
                        try {
                            body = rb != null ? rb.string() : "";
                        } catch (Exception ex) {
                            JsonObject fallback = getFreshUserStatsFromCache(playerName);
                            if (fallback != null) {
                                SwingUtilities.invokeLater(() -> updateProgressBars(fallback));
                            }
                            return;
                        }
                        if (body.isEmpty()) return;
                        JsonObject stats = gson.fromJson(body, JsonObject.class);
                        if (stats.has("account_hash") && !stats.get("account_hash").isJsonNull()) {
                            try { lastLoadedAccountHash = stats.get("account_hash").getAsString(); } catch (Exception ignore) {}
                        }
                    }
                    SwingUtilities.invokeLater(() -> updateAllRankNumbers(playerName));
                }
            });
        } catch (Exception ignore) {}
    }
    
    private String generateAccountSha(String accountHash) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(accountHash.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash)
        {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    private void updateAdditionalStats(JsonArray matches)
    {
        if (highestRankLabel == null || lowestRankLabel == null || highestRankTimeLabel == null || lowestRankTimeLabel == null)
        {
            return; // UI not initialized yet; avoid NPE
        }
        String highestRankDefeated = null;
        String lowestRankLostTo = null;
        String highestTime = null;
        String lowestTime = null;
        
        for (int i = 0; i < matches.size(); i++)
        {
            JsonObject match = matches.get(i).getAsJsonObject();
            String result = match.has("result") ? match.get("result").getAsString().toLowerCase() : "";
            String opponentRank = computeRank(match, "opponent_");
            String time = match.has("when") ? formatTime(match.get("when").getAsLong()) : "";
            
            if ("win".equals(result))
            {
                if (highestRankDefeated == null || isHigherRank(opponentRank, highestRankDefeated))
                {
                    highestRankDefeated = opponentRank;
                    highestTime = time;
                }
            }
            else if ("loss".equals(result))
            {
                if (lowestRankLostTo == null || isLowerRank(opponentRank, lowestRankLostTo))
                {
                    lowestRankLostTo = opponentRank;
                    lowestTime = time;
                }
            }
        }
        
        highestRankLabel.setText(highestRankDefeated != null ? highestRankDefeated : "-");
        highestRankTimeLabel.setText(highestTime != null ? highestTime : "-");
        lowestRankLabel.setText(lowestRankLostTo != null ? lowestRankLostTo : "-");
        lowestRankTimeLabel.setText(lowestTime != null ? lowestTime : "-");
        
        // Update tier graph
        updateTierGraph(matches);
    }
    
    private boolean isHigherRank(String rank1, String rank2)
    {
        return getRankOrder(rank1) > getRankOrder(rank2);
    }
    
    private boolean isLowerRank(String rank1, String rank2)
    {
        return getRankOrder(rank1) < getRankOrder(rank2);
    }
    
    private int getRankOrder(String rank)
    {
        String[] parts = rank.split(" ");
        String baseName = parts[0];
        int division = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        
        int baseOrder;
        switch (baseName) {
            case "Bronze":
                baseOrder = 0;
                break;
            case "Iron":
                baseOrder = 1;
                break;
            case "Steel":
                baseOrder = 2;
                break;
            case "Black":
                baseOrder = 3;
                break;
            case "Mithril":
                baseOrder = 4;
                break;
            case "Adamant":
                baseOrder = 5;
                break;
            case "Rune":
                baseOrder = 6;
                break;
            case "Dragon":
                baseOrder = 7;
                break;
            case "3rd Age":
                baseOrder = 8;
                break;
            default:
                baseOrder = -1;
                break;
        }
        
        return baseOrder * 10 + (4 - division);
    }
    
    private JPanel createTierGraph()
    {
        JPanel panel = new JPanel()
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                int width = getWidth() - 40;
                int height = getHeight() - 40;
                
                if (width <= 0 || height <= 0) return;
                
                // Draw axes
                g2.setColor(Color.LIGHT_GRAY);
                g2.drawLine(20, height + 20, width + 20, height + 20);
                g2.drawLine(20, 20, 20, height + 20);
                
                // Draw tier lines and labels
                String[] tiers = {"Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune", "Dragon", "3rd Age"};
                Color[] tierColors = {
                    new Color(184, 115, 51), new Color(192, 192, 192), new Color(154, 162, 166),
                    Color.GRAY, new Color(59, 167, 214), new Color(26, 139, 111),
                    new Color(78, 159, 227), new Color(229, 57, 53), new Color(229, 193, 0)
                };
                
                for (int i = 0; i < tiers.length; i++)
                {
                    int y = 20 + (i * height / tiers.length);
                    g2.setColor(tierColors[i]);
                    g2.drawLine(20, y, width + 20, y);
                    if (config != null && config.colorblindMode()) {
                        g2.setColor(Color.WHITE);
                    }
                    g2.drawString(tiers[tiers.length - 1 - i], 2, y + 5);
                }
                
                // Draw X-axis labels (match numbers) - show every ~50th match for 500+ matches
                if (tierHistory.size() > 1)
                {
                    g2.setColor(Color.WHITE);
                    int totalMatches = tierHistory.size();
                    int labelInterval = Math.max(1, totalMatches / 8); // Show ~8 labels max
                    
                    for (int i = 0; i < totalMatches; i += labelInterval)
                    {
                        int x = 20 + (i * width / Math.max(1, totalMatches - 1));
                        int matchNum = totalMatches - i; // Reverse order (most recent first)
                        g2.drawString("#" + matchNum, x - 10, height + 35);
                    }
                    
                    // Draw tier progression line
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2));
                    for (int i = 0; i < tierHistory.size() - 1; i++)
                    {
                        int x1 = 20 + (i * width / Math.max(1, tierHistory.size() - 1));
                        int y1 = height + 20 - (int)(tierHistory.get(i) * height / 100);
                        int x2 = 20 + ((i + 1) * width / Math.max(1, tierHistory.size() - 1));
                        int y2 = height + 20 - (int)(tierHistory.get(i + 1) * height / 100);
                        g2.drawLine(x1, y1, x2, y2);
                    }
                }
                else
                {
                    g2.setColor(Color.GRAY);
                    g2.drawString("No tier data available", width / 2 - 60, height / 2);
                }
            }
        };
        panel.setPreferredSize(new Dimension(240, 240));
        return panel;
    }
    
    private void updateTierGraph(JsonArray matches)
    {
        java.util.List<Double> tierData = new java.util.ArrayList<>();
        
        // Sort matches by timestamp for proper chronological order
        java.util.List<JsonObject> sortedMatches = new java.util.ArrayList<>();
        for (int i = 0; i < matches.size(); i++)
        {
            sortedMatches.add(matches.get(i).getAsJsonObject());
        }
        sortedMatches.sort((a, b) -> {
            long timeA = a.has("when") ? a.get("when").getAsLong() : 0;
            long timeB = b.has("when") ? b.get("when").getAsLong() : 0;
            return Long.compare(timeA, timeB);
        });
        
        // Cap at 500 matches maximum
        int maxMatches = Math.min(500, sortedMatches.size());
        
        for (int i = 0; i < maxMatches; i++)
        {
            JsonObject match = sortedMatches.get(i);
            String bucket = match.has("bucket") ? match.get("bucket").getAsString().toLowerCase() : "";
            
            if (selectedBucket.equals("overall") || selectedBucket.equals(bucket))
            {
                if (match.has("player_mmr"))
                {
                    double mmr = match.get("player_mmr").getAsDouble();
                    double tierValue = calculateTierValue(mmr);
                    tierData.add(tierValue);
                }
            }
        }
        
        tierHistory = tierData;
        updateTierGraphDisplay();
    }
    
    private void updateTierGraphDisplay()
    {
        if (tierGraphPanel != null)
        {
            // Keep fixed size to avoid scrollbars
            tierGraphPanel.setPreferredSize(new Dimension(240, 240));
            tierGraphPanel.revalidate();
            tierGraphPanel.repaint();
        }
    }
    
    private double calculateTierValue(double mmr)
    {
        String[][] thresholds = {
            {"Bronze", "3", "0"}, {"Bronze", "2", "170"}, {"Bronze", "1", "240"},
            {"Iron", "3", "310"}, {"Iron", "2", "380"}, {"Iron", "1", "450"},
            {"Steel", "3", "520"}, {"Steel", "2", "590"}, {"Steel", "1", "660"},
            {"Black", "3", "730"}, {"Black", "2", "800"}, {"Black", "1", "870"},
            {"Mithril", "3", "940"}, {"Mithril", "2", "1010"}, {"Mithril", "1", "1080"},
            {"Adamant", "3", "1150"}, {"Adamant", "2", "1250"}, {"Adamant", "1", "1350"},
            {"Rune", "3", "1450"}, {"Rune", "2", "1550"}, {"Rune", "1", "1650"},
            {"Dragon", "3", "1750"}, {"Dragon", "2", "1850"}, {"Dragon", "1", "1950"},
            {"3rd Age", "0", "2100"}
        };
        
        String[] current = thresholds[0];
        for (String[] threshold : thresholds)
        {
            if (mmr >= Double.parseDouble(threshold[2]))
            {
                current = threshold;
            }
            else
            {
                break;
            }
        }
        
        // Convert to percentage for graph display
        String[] tiers = {"Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune", "Dragon", "3rd Age"};
        for (int i = 0; i < tiers.length; i++)
        {
            if (tiers[i].equals(current[0]))
            {
                return (i * 100.0 / tiers.length) + (Integer.parseInt(current[1]) * 10.0 / tiers.length);
            }
        }
        return 0;
    }
    
    private void clearTokens()
    {
        authService.logout();
        idToken = null;
        
    }
    
    private void updatePerformanceStats(int wins, int losses, int ties)
    {
        int nonTieMatches = wins + losses;
        
        double winPercent = nonTieMatches > 0 ? (wins * 100.0 / nonTieMatches) : 0;
        double kd = losses > 0 ? (wins / (double) losses) : (wins > 0 ? wins : 0);
        
        winPercentLabel.setText(String.format("%.0f%% Winrate", winPercent));
        kdLabel.setText(String.format("KD: %.2f", kd));
        killsLabel.setText("K: " + wins);
        deathsLabel.setText("D: " + losses);
        tiesLabel.setText("Ties: " + ties);
    }
    
    @SuppressWarnings("unused")
    private JPanel createWinRateChart()
    {
        JPanel panel = new JPanel()
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                int width = getWidth() - 40;
                int height = getHeight() - 40;
                
                if (width <= 0 || height <= 0) return;
                
                // Draw axes
                g2.setColor(Color.LIGHT_GRAY);
                g2.drawLine(20, height + 20, width + 20, height + 20);
                g2.drawLine(20, 20, 20, height + 20);
                
                // Draw grid lines and Y-axis labels
                g2.setColor(Color.GRAY);
                for (int i = 0; i <= 10; i++)
                {
                    int y = 20 + (i * height / 10);
                    g2.drawLine(20, y, width + 20, y);
                    g2.setColor(Color.WHITE);
                    g2.drawString((100 - i * 10) + "%", 2, y + 5);
                    g2.setColor(Color.GRAY);
                }
                
                // Draw X-axis labels and win rate line
                if (winRateHistory.size() > 1)
                {
                    // Draw X-axis labels (match numbers)
                    g2.setColor(Color.WHITE);
                    int maxTicks = Math.min(8, winRateHistory.size());
                    for (int i = 0; i < maxTicks; i++)
                    {
                        int x = 20 + (i * width / (maxTicks - 1));
                        int matchNum = winRateHistory.size() - (i * winRateHistory.size() / (maxTicks - 1));
                        g2.drawString("#" + matchNum, x - 10, height + 35);
                    }
                    
                    // Draw win rate line
                    g2.setColor(new Color(255, 215, 0)); // Gold color like JS
                    g2.setStroke(new BasicStroke(2));
                    for (int i = 0; i < winRateHistory.size() - 1; i++)
                    {
                        int x1 = 20 + (i * width / (winRateHistory.size() - 1));
                        int y1 = height + 20 - (int)(winRateHistory.get(i) * height / 100);
                        int x2 = 20 + ((i + 1) * width / (winRateHistory.size() - 1));
                        int y2 = height + 20 - (int)(winRateHistory.get(i + 1) * height / 100);
                        g2.drawLine(x1, y1, x2, y2);
                    }
                }
                else
                {
                    g2.setColor(Color.GRAY);
                    g2.drawString("No match data available", width / 2 - 60, height / 2);
                }
            }
        };
        panel.setPreferredSize(new Dimension(Math.max(1024, winRateHistory.size() * 2), 280)); // Dynamic width based on data
        return panel;
    }
    
    private void updateWinRateChart(JsonArray matches)
    {
        java.util.List<Double> rolling = new java.util.ArrayList<>();
        
        // Calculate rolling win percentage for each match (20-match window like JS)
        for (int i = 0; i < matches.size(); i++)
        {
            int start = Math.max(0, i - 19);
            int winCount = 0, totalCount = 0;
            
            for (int j = start; j <= i; j++)
            {
                JsonObject match = matches.get(j).getAsJsonObject();
                String result = match.has("result") ? match.get("result").getAsString().toLowerCase() : "";
                if ("win".equals(result) || "loss".equals(result))
                {
                    totalCount++;
                    if ("win".equals(result)) winCount++;
                }
            }
            
            double winRate = totalCount > 0 ? (winCount * 100.0 / totalCount) : 0;
            rolling.add(winRate);
        }
        
        winRateHistory = rolling;
        
        if (chartPanel != null)
        {
            chartPanel.repaint();
        }
    }
    
    public void updateAdditionalStatsFromPlugin(String highestRankDefeated, String lowestRankLostTo)
    {
        if (isLoggedIn && additionalStatsPanel != null && additionalStatsPanel.isVisible())
        {
            SwingUtilities.invokeLater(() -> {
                if (highestRankDefeated != null)
                {
                    highestRankLabel.setText(highestRankDefeated);
                    highestRankTimeLabel.setText("Live tracking");
                }
                if (lowestRankLostTo != null)
                {
                    lowestRankLabel.setText(lowestRankLostTo);
                    lowestRankTimeLabel.setText("Live tracking");
                }
            });
        }

        // Optimistically add a synthetic row to match history when available
        try
        {
            String opponent = lowestRankLostTo != null ? "Opponent" : "Opponent";
            String result = highestRankDefeated != null ? "win" : (lowestRankLostTo != null ? "loss" : null);
            if (result != null && tableModel != null)
            {
                String matchType = selectedBucket.toUpperCase();
                String matchDisplay = (highestRankDefeated != null ? highestRankDefeated : (lowestRankLostTo != null ? lowestRankLostTo : "-")) + " vs ?";
                String change = "—";
                String time = formatTime(System.currentTimeMillis() / 1000);
                tableModel.insertRow(0, new Object[]{result, opponent, matchType, matchDisplay, change, time});
            }
        }
        catch (Exception ignore) {}
    }
    
    public void updateTierGraphRealTime(String bucket, double mmr)
    {
        if (isLoggedIn && (selectedBucket.equals("overall") || selectedBucket.equals(bucket.toLowerCase())))
        {
            SwingUtilities.invokeLater(() -> {
                double tierValue = calculateTierValue(mmr);
                tierHistory.add(tierValue);
                
                // Keep only last 500 points for performance
                if (tierHistory.size() > 500)
                {
                    tierHistory = tierHistory.subList(tierHistory.size() - 500, tierHistory.size());
                }
                
                updateTierGraphDisplay();
            });
        }
    }
    
    public String getIdToken()
    {
        return idToken;
    }

    public boolean isAuthLoggedIn()
    {
        try { return authService != null && authService.isLoggedIn(); } catch (Exception ignore) { return false; }
    }
    
    private void updateBucketButtonStates(String activeBucket)
    {
        String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};
        for (int i = 0; i < bucketButtons.length; i++)
        {
            if (bucketButtons[i] != null)
            {
                boolean isActive = buckets[i].equals(activeBucket);
                bucketButtons[i].setEnabled(!isActive);
                bucketButtons[i].setBackground(isActive ? Color.DARK_GRAY : null);
            }
        }
    }
    
    private void handleRefresh()
    {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRefresh = currentTime - lastRefreshTime;
        
        if (timeSinceLastRefresh < REFRESH_COOLDOWN_MS)
        {
            // Popup disabled per requirement; ignore remaining cooldown seconds
            return;
        }
        
        String currentPlayer = playerNameLabel.getText();
        if (currentPlayer != null && !currentPlayer.equals("Player Name"))
        {
            lastRefreshTime = currentTime;
            loadMatchHistory(currentPlayer);
        }
        else
        {
            // Popup disabled per requirement
        }
    }


    public void setStatsBucketFromConfig(PvPLeaderboardConfig.RankBucket b)
    {
        if (extraStatsPanel == null || b == null) return;
        String bucket;
        switch (b)
        {
            case NH: bucket = "nh"; break;
            case VENG: bucket = "veng"; break;
            case MULTI: bucket = "multi"; break;
            case DMM: bucket = "dmm"; break;
            case OVERALL:
            default: bucket = "overall"; break;
        }
        extraStatsPanel.setBucket(bucket);
    }
    
    private static class RankInfo
    {
        String rank;
        int division;
        double progress;
        
        RankInfo(String rank, int division, double progress)
        {
            this.rank = rank;
            this.division = division;
            this.progress = progress;
        }
    }
    
    public void updateAllRankNumbers(String playerName)
    {
        String[] buckets = {"overall", "nh", "veng", "multi", "dmm"};
        
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws Exception
            {
                // Only update the currently selected bucket's rank number to prevent multi-bucket shard loads
                String currentBucket = bucketKey(config != null ? config.rankBucket() : null);
                for (String bucket : buckets)
                {
                    if (!bucket.equals(currentBucket))
                    {
                        continue;
                    }
                    int rankNumber = getRankNumberFromLeaderboard(playerName, bucket);
                    if (rankNumber > 0)
                    {
                        SwingUtilities.invokeLater(() -> {
                            bucketRankNumbers.put(bucket, rankNumber);
                            updateBucketLabel(bucket);
                        });
                    }
                }
                return null;
            }
        };
        worker.execute();
    }
    
    private void updateBucketLabel(String bucket)
    {
        int index = getBucketIndex(bucket);
        if (index < 0 || progressLabels[index] == null) return;
        
        Integer rankNumber = bucketRankNumbers.get(bucket);
        if (rankNumber != null && rankNumber > 0)
        {
            String currentText = progressLabels[index].getText();
            if (!currentText.contains("Rank "))
            {
                progressLabels[index].setText(currentText + " - Rank " + rankNumber);
            }
        }
    }

    private static String bucketKey(PvPLeaderboardConfig.RankBucket bucket)
    {
        if (bucket == null) return "overall";
        switch (bucket)
        {
            case NH: return "nh";
            case VENG: return "veng";
            case MULTI: return "multi";
            case DMM: return "dmm";
            case OVERALL:
            default: return "overall";
        }
    }
    
    

    private static class MatchesCache
    {
        final JsonArray matches;
        final String nextToken;
        MatchesCache(JsonArray matches, String nextToken, long timestampMs)
        {
            this.matches = matches;
            this.nextToken = nextToken;
        }
    }
}