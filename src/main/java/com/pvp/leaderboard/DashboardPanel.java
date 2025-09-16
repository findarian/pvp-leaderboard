package com.pvp.leaderboard;

import net.runelite.client.ui.PluginPanel;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private JPanel statsGrid;
    private JLabel winPercentLabel;
    private JLabel tiesLabel;
    private JLabel killsLabel;
    private JLabel deathsLabel;
    private JLabel kdLabel;
    private JPanel chartPanel;
    private java.util.List<Double> winRateHistory = new java.util.ArrayList<>();
    private JPanel additionalStatsPanel;
    private DefaultTableModel rankBreakdownModel;
    private JTable rankBreakdownTable;
    private boolean isLoggedIn = false;
    private String idToken = null;
    private String accessToken = null;
    private JLabel highestRankLabel;
    private JLabel highestRankTimeLabel;
    private JLabel lowestRankLabel;
    private JLabel lowestRankTimeLabel;
    private JPanel tierGraphPanel;
    private String selectedBucket = "overall";
    private java.util.List<Double> tierHistory = new java.util.ArrayList<>();
    private JsonArray allMatches = null;
    private JButton[] bucketButtons = new JButton[5];
    private JButton refreshButton;
    private long lastRefreshTime = 0;
    private static final long REFRESH_COOLDOWN_MS = 60000; // 1 minute
    private static final Map<String, Color> RANK_COLORS = new HashMap<String, Color>() {{
        put("Bronze", new Color(184, 115, 51));
        put("Iron", new Color(192, 192, 192));
        put("Steel", new Color(154, 162, 166));
        put("Black", new Color(106, 106, 106));
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

    private PvPLeaderboardConfig config;
    private ProfileState currentProfile = new ProfileState();
    private Map<String, Integer> bucketRankNumbers = new HashMap<>();
    
    private String canonName(String name) {
        return String.valueOf(name != null ? name : "").trim().replaceAll("\\s+", " ").toLowerCase();
    }
    
    public DashboardPanel(PvPLeaderboardConfig config)
    {
        this.config = config;
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
        if (additionalStatsPanel != null)
        {
            additionalStatsPanel.setVisible(show);
            revalidate();
            repaint();
        }
    }
    
    private JPanel createMainPanel()
    {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        
        // Auth Bar (Login Section)
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
        
        // Performance Overview
        mainPanel.add(createPerformanceOverview());
        mainPanel.add(Box.createVerticalStrut(24));
        
        // Additional Stats
        mainPanel.add(createAdditionalStats());
        mainPanel.add(Box.createVerticalStrut(24));
        
        // Match History
        mainPanel.add(createMatchHistory());
        
        return mainPanel;
    }
    
    private JPanel createAuthBar()
    {
        JPanel authBar = new JPanel();
        authBar.setLayout(new BoxLayout(authBar, BoxLayout.Y_AXIS));
        authBar.setBorder(BorderFactory.createTitledBorder("Login to view stats in runelite"));
        authBar.setMaximumSize(new Dimension(220, 190));
        authBar.setPreferredSize(new Dimension(220, 190));
        
        // Website search
        authBar.add(new JLabel("Search user on website:"));
        JPanel websitePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
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
        authBar.add(new JLabel("Search user on plugin:"));
        JPanel pluginPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
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
        
        loginButton = new JButton("Login to view stats in runelite");
        loginButton.setMaximumSize(new Dimension(200, 25));
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
        
        String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};
        
        for (int i = 0; i < buckets.length; i++)
        {
            JPanel bucketPanel = new JPanel(new BorderLayout());
            
            progressLabels[i] = new JLabel(buckets[i] + " - — (0.0%) ");
            progressLabels[i].setFont(progressLabels[i].getFont().deriveFont(Font.BOLD));
            bucketPanel.add(progressLabels[i], BorderLayout.NORTH);
            
            progressBars[i] = new JProgressBar(0, 100);
            progressBars[i].setValue(0);
            progressBars[i].setStringPainted(true);
            progressBars[i].setString("0%");
            progressBars[i].setPreferredSize(new Dimension(0, 16));
            bucketPanel.add(progressBars[i], BorderLayout.CENTER);
            
            section.add(bucketPanel);
            if (i < buckets.length - 1) section.add(Box.createVerticalStrut(8));
        }
        
        return section;
    }
    
    private JPanel createPerformanceOverview()
    {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createTitledBorder("Performance Overview"));
        
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        
        // Stats summary row
        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        winPercentLabel = new JLabel("Win %: -");
        tiesLabel = new JLabel("Ties: -");
        killsLabel = new JLabel("Kills: -");
        deathsLabel = new JLabel("Deaths: -");
        kdLabel = new JLabel("KD: -");
        
        summaryPanel.add(winPercentLabel);
        summaryPanel.add(tiesLabel);
        summaryPanel.add(killsLabel);
        summaryPanel.add(deathsLabel);
        summaryPanel.add(kdLabel);
        
        contentPanel.add(summaryPanel);
        contentPanel.add(Box.createVerticalStrut(8));
        
        // Win rate chart
        chartPanel = createWinRateChart();
        contentPanel.add(chartPanel);
        contentPanel.add(Box.createVerticalStrut(16));
        
        // Rank breakdown table
        contentPanel.add(createRankBreakdownTable());
        
        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(null);
        scrollPane.setPreferredSize(new Dimension(0, 500));
        
        mainPanel.add(scrollPane);
        return mainPanel;
    }
    
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
        
        // Bucket selector above the title - horizontal with scroll
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        String[] buckets = {"Overall", "NH", "Veng", "Multi", "DMM"};
        for (int i = 0; i < buckets.length; i++) {
            String bucket = buckets[i];
            JButton btn = new JButton(bucket);
            btn.setPreferredSize(new Dimension(70, 25));
            btn.addActionListener(e -> {
                selectedBucket = bucket.toLowerCase();
                updateBucketButtonStates(bucket);
                if (allMatches != null) {
                    updateTierGraph(allMatches);
                }
            });
            bucketButtons[i] = btn;
            buttonPanel.add(btn);
        }
        updateBucketButtonStates("Overall");
        
        JScrollPane buttonScrollPane = new JScrollPane(buttonPanel);
        buttonScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        buttonScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        buttonScrollPane.setBorder(null);
        buttonScrollPane.setPreferredSize(new Dimension(0, 35));
        additionalStatsPanel.add(buttonScrollPane);
        
        // Tier Graph title - left aligned
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel tierTitle = new JLabel("Tier Graph");
        tierTitle.setFont(tierTitle.getFont().deriveFont(Font.BOLD, 14f));
        titlePanel.add(tierTitle);
        additionalStatsPanel.add(titlePanel);
        additionalStatsPanel.add(Box.createVerticalStrut(8));
        
        tierGraphPanel = createTierGraph();
        JScrollPane tierScrollPane = new JScrollPane(tierGraphPanel);
        tierScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        tierScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tierScrollPane.setPreferredSize(new Dimension(0, 260));
        additionalStatsPanel.add(tierScrollPane);
        
        return additionalStatsPanel;
    }
    
    private JPanel createMatchHistory()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Match History"));
        
        String[] columns = {"Result", "Opponent", "Match Type", "Match", "Change", "Time"};
        tableModel = new DefaultTableModel(columns, 0);
        matchHistoryTable = new JTable(tableModel);
        matchHistoryTable.setFillsViewportHeight(true);
        matchHistoryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        
        JScrollPane scrollPane = new JScrollPane(matchHistoryTable);
        scrollPane.setPreferredSize(new Dimension(0, 300));
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private void handleLogin()
    {
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
            CognitoAuthService authService = new CognitoAuthService();
            authService.login().thenAccept(success -> {
                if (success) {
                    SwingUtilities.invokeLater(() -> completeLogin());
                } else {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Login failed", "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Login error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
                return null;
            });
        }
        catch (Exception e)
        {
            JOptionPane.showMessageDialog(this, "Failed to open login page", "Login Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    public void loadMatchHistory(String playerId)
    {
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws Exception
            {
                try
                {
                    loadPlayerStats(playerId);
                    
                    // Use player_id parameter like website does for matches
                    String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/matches?player_id=" + URLEncoder.encode(playerId, "UTF-8") + "&limit=500";
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        response.append(line);
                    }
                    reader.close();
                    
                    JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
                    JsonArray matches = jsonResponse.getAsJsonArray("matches");
                    
                    SwingUtilities.invokeLater(() ->
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
                            
                            // Count match results
                            if ("win".equalsIgnoreCase(result)) wins++;
                            else if ("loss".equalsIgnoreCase(result)) losses++;
                            else if ("tie".equalsIgnoreCase(result)) ties++;
                            
                            tableModel.addRow(new Object[]{result, opponent, matchType, matchDisplay, change, time});
                        }
                        
                        updatePerformanceStats(wins, losses, ties);
                        updateWinRateChart(matches);
                        updateRankBreakdown(matches);
                        
                        // Update additional stats if logged in
                        if (isLoggedIn) {
                            allMatches = matches;
                            updateAdditionalStats(matches);
                        }
                        
                        // Update bucket bars from matches after they're loaded
                        allMatches = matches;
                        updateBucketBarsFromMatches();
                    });
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return null;
            }
        };
        worker.execute();
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
                    return mmrText + "<br><small>" + (fromLabel.trim().isEmpty() ? "?" : fromLabel) + " → " + (toLabel.trim().isEmpty() ? "?" : toLabel) + "</small>";
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
            
            return progressLine + "<br><small>" + deltaText + "</small>";
        }
        return "-";
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
            // Use user endpoint by player_id like website does
            String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/user?player_id=" + URLEncoder.encode(playerId, "UTF-8");
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
            reader.close();
            
            JsonObject stats = JsonParser.parseString(response.toString()).getAsJsonObject();
            

            
            SwingUtilities.invokeLater(() -> {
                updateProgressBars(stats);
                // Force immediate rank number lookup for all buckets
                String playerName = stats.has("player_name") ? stats.get("player_name").getAsString() : 
                                  (stats.has("player_id") ? stats.get("player_id").getAsString() : null);
                if (playerName != null) {
                    updateAllRankNumbers(playerName);
                }
            });
        }
        catch (Exception e)
        {
            e.printStackTrace();
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
                    
                    // Get rank number from leaderboard for Overall
                    int rankNumber = getRankNumberFromLeaderboard(playerName, "overall");
                    
                    SwingUtilities.invokeLater(() -> {
                        setBucketBarWithRank("overall", overall.rank, overall.division, overall.progress, rankNumber);
                    });
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
        
        String[] buckets = {"nh", "veng", "multi", "dmm"};
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
                
                // Get rank number from leaderboard asynchronously
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
        }
    }
    
    private int getRankNumberFromLeaderboard(String playerName, String bucket)
    {
        try
        {
            String s3Key;
            switch (bucket.toLowerCase()) {
                case "overall":
                    s3Key = "/leaderboard.json";
                    break;
                case "nh":
                    s3Key = "/leaderboard_nh.json";
                    break;
                case "veng":
                    s3Key = "/leaderboard_veng.json";
                    break;
                case "multi":
                    s3Key = "/leaderboard_multi.json";
                    break;
                case "dmm":
                    s3Key = "/leaderboard_dmm.json";
                    break;
                default:
                    return -1;
            }
            
            String s3Url = "https://devsecopsautomated.com" + s3Key;
            URL url = new URL(s3Url);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cache-Control", "no-store");
            
            if (conn.getResponseCode() != 200) return -1;
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
            reader.close();
            
            JsonObject data = JsonParser.parseString(response.toString()).getAsJsonObject();
            if (!data.has("players")) return -1;
            
            JsonArray players = data.getAsJsonArray("players");
            
            // If no players in leaderboard, return rank 1 like website does
            if (players.size() == 0) return 1;
            
            String canonPlayerName = canonName(playerName);
            
            for (int i = 0; i < players.size(); i++)
            {
                JsonObject player = players.get(i).getAsJsonObject();
                String foundName = null;
                
                if (player.has("player_names") && !player.get("player_names").isJsonNull())
                {
                    JsonArray names = player.getAsJsonArray("player_names");
                    if (names.size() > 0) {
                        foundName = names.get(0).getAsString();
                    }
                }
                
                if (foundName != null && canonName(foundName).equals(canonPlayerName))
                {
                    return i + 1;
                }
            }
        }
        catch (Exception e)
        {
            // Silent failure
        }
        return -1;
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
            labelText += " - Rank #" + rankNumber;
        }
        
        progressLabels[index].setText(labelText);
        progressLabels[index].setForeground(getRankColor(rank));
        
        int pctValue = (int) Math.max(0, Math.min(100, pct));
        progressBars[index].setValue(pctValue);
        progressBars[index].setString(String.format("%.1f%%", pct));
        progressBars[index].setForeground(getRankColor(rank));
    }
    
    private void updateRankNumber(int index, int rankNumber)
    {
        String currentText = progressLabels[index].getText();
        if (!currentText.contains("Rank #"))
        {
            progressLabels[index].setText(currentText + " - Rank #" + rankNumber);
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
    

    
    private void updateProgressBar(int index, String bucketName, String rank, int division, double progress)
    {
        updateProgressBar(index, bucketName, rank, division, progress, -1);
    }
    
    private void updateProgressBar(int index, String bucketName, String rank, int division, double progress, int rankNumber)
    {
        if (rank.equals("Bronze") && division == 3 && progress == 0)
        {
            // No data case - show like website
            progressLabels[index].setText(bucketName + " - — (0.0%)");
            progressBars[index].setValue(0);
            progressBars[index].setString("0.0%");
        }
        else
        {
            String rankText = rank + (division > 0 ? " " + division : "");
            String rankNumText = (rankNumber > 0 && index == 0) ? " - Rank #" + rankNumber : ""; // Only show rank number for Overall
            progressLabels[index].setText(bucketName + " - " + rankText + rankNumText);
            progressBars[index].setValue((int) progress);
            progressBars[index].setString(String.format("%.1f%%", progress));
        }
    }
    
    private RankInfo calculateRankFromMMR(double mmr)
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
        
        String rank = current[0];
        int division = Integer.parseInt(current[1]);
        double progress = 0;
        
        if (!rank.equals("3rd Age"))
        {
            int currentIndex = -1;
            for (int i = 0; i < thresholds.length; i++)
            {
                if (thresholds[i][0].equals(rank) && thresholds[i][1].equals(String.valueOf(division)))
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
                progress = Math.max(0, Math.min(100, ((mmr - currentThreshold) / span) * 100));
            }
        }
        else
        {
            progress = 100;
        }
        
        return new RankInfo(rank, division, progress);
    }
    
    private JPanel createRankBreakdownTable()
    {
        JPanel panel = new JPanel(new BorderLayout());
        
        String[] columns = {"Opponent Tier", "Kills", "Deaths", "KD"};
        rankBreakdownModel = new DefaultTableModel(columns, 0);
        rankBreakdownTable = new JTable(rankBreakdownModel);
        rankBreakdownTable.setFillsViewportHeight(false);
        rankBreakdownTable.setPreferredScrollableViewportSize(new Dimension(0, 150));
        
        JScrollPane scrollPane = new JScrollPane(rankBreakdownTable);
        scrollPane.setPreferredSize(new Dimension(0, 150));
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
    
    private void startTokenPolling()
    {
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws Exception
            {
                // Poll for token from callback (simulate OAuth flow completion)
                for (int i = 0; i < 30; i++) // Poll for 30 seconds
                {
                    Thread.sleep(1000);
                    
                    // Check if callback completed (in real implementation, check callback server/file)
                    if (checkCallbackCompletion())
                    {
                        SwingUtilities.invokeLater(() -> completeLogin());
                        break;
                    }
                }
                return null;
            }
        };
        worker.execute();
    }
    
    private boolean checkCallbackCompletion()
    {
        // Simulate callback completion after 3 seconds
        // In real implementation, check callback endpoint or local server
        return System.currentTimeMillis() % 10000 > 3000;
    }
    
    private CognitoAuthService authService = new CognitoAuthService();
    
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
        String playerName = websiteSearchField.getText().trim();
        if (playerName.isEmpty()) return;
        
        SwingWorker<String, Void> worker = new SwingWorker<String, Void>()
        {
            @Override
            protected String doInBackground() throws Exception
            {
                try
                {
                    // Get account hash from API - match website logic
                    String apiUrl = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/user?player_id=" + URLEncoder.encode(playerName, "UTF-8");
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        response.append(line);
                    }
                    reader.close();
                    
                    JsonObject data = JsonParser.parseString(response.toString()).getAsJsonObject();
                    
                    // Check for account_hash like website does
                    if (data.has("account_hash") && !data.get("account_hash").isJsonNull())
                    {
                        String accountHash = data.get("account_hash").getAsString();
                        return generateAccountSha(accountHash);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return null;
            }
            
            @Override
            protected void done()
            {
                try
                {
                    String accountSha = get();
                    if (accountSha != null)
                    {
                        String profileUrl = "https://devsecopsautomated.com/profile.html?acct=" + accountSha;
                        Desktop.getDesktop().browse(URI.create(profileUrl));
                    }
                    else
                    {
                        JOptionPane.showMessageDialog(DashboardPanel.this, "Player not found", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
                catch (Exception e)
                {
                    JOptionPane.showMessageDialog(DashboardPanel.this, "Failed to open profile", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
    
    private void searchUserOnPlugin()
    {
        String playerName = pluginSearchField.getText().trim();
        if (playerName.isEmpty()) return;
        
        playerNameLabel.setText(playerName);
        
        // Update player name in current profile for rank lookups
        if (currentProfile != null) {
            currentProfile.name = playerName;
        }
        loadMatchHistory(playerName);
        
        // Show additional stats only if logged in
        if (isLoggedIn)
        {
            showAdditionalStats(true);
        }
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
                    new Color(46, 46, 46), new Color(59, 167, 214), new Color(26, 139, 111),
                    new Color(78, 159, 227), new Color(229, 57, 53), new Color(229, 193, 0)
                };
                
                for (int i = 0; i < tiers.length; i++)
                {
                    int y = 20 + (i * height / tiers.length);
                    g2.setColor(tierColors[i]);
                    g2.drawLine(20, y, width + 20, y);
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
        panel.setPreferredSize(new Dimension(Math.max(800, tierHistory.size() * 2), 260));
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
            // Update panel size for horizontal scrolling
            tierGraphPanel.setPreferredSize(new Dimension(Math.max(800, tierHistory.size() * 2), 260));
            tierGraphPanel.getParent().setPreferredSize(new Dimension(Math.max(800, tierHistory.size() * 2), 300));
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
        accessToken = null;
    }
    
    private void updatePerformanceStats(int wins, int losses, int ties)
    {
        int totalMatches = wins + losses + ties;
        int nonTieMatches = wins + losses;
        
        double winPercent = nonTieMatches > 0 ? (wins * 100.0 / nonTieMatches) : 0;
        double kd = losses > 0 ? (wins / (double) losses) : (wins > 0 ? wins : 0);
        
        winPercentLabel.setText(String.format("Win %%: %.1f%%", winPercent));
        tiesLabel.setText("Ties: " + ties);
        killsLabel.setText("Kills: " + wins);
        deathsLabel.setText("Deaths: " + losses);
        kdLabel.setText(String.format("KD: %.1f", kd));
    }
    
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
            long remainingSeconds = (REFRESH_COOLDOWN_MS - timeSinceLastRefresh) / 1000;
            JOptionPane.showMessageDialog(this, 
                "Please wait " + remainingSeconds + " seconds before refreshing again.", 
                "Refresh Cooldown", 
                JOptionPane.INFORMATION_MESSAGE);
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
            JOptionPane.showMessageDialog(this, 
                "No player data to refresh. Search for a player first.", 
                "No Data", 
                JOptionPane.INFORMATION_MESSAGE);
        }
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
    
    private void updateAllRankNumbers(String playerName)
    {
        String[] buckets = {"overall", "nh", "veng", "multi", "dmm"};
        
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws Exception
            {
                for (String bucket : buckets)
                {
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
            if (!currentText.contains("Rank #"))
            {
                progressLabels[index].setText(currentText + " - Rank #" + rankNumber);
            }
        }
    }
    
    private static class ProfileState
    {
        String name;
        
        ProfileState()
        {
            this.name = null;
        }
    }
}