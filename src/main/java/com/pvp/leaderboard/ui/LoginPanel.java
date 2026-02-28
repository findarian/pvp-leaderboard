package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.service.CognitoAuthService;
import javax.swing.*;
import java.awt.*;
import java.net.URLEncoder;
import java.util.function.Consumer;
import net.runelite.client.util.LinkBrowser;

public class LoginPanel extends JPanel
{
    private static final int MAX_PLUGIN_SEARCHES_PER_MINUTE = 10;
    
    private final CognitoAuthService cognitoAuthService;
    private final Consumer<String> onPluginSearch;
    private final Runnable onLoginStateChanged;

    private JTextField searchField;
    private JButton pluginSearchBtn;
    private JButton loginButton;
    
    private boolean loginInProgress = false;
    private boolean isLoggedIn = false;
    
    // Rate limiting for plugin search (10 per minute)
    private final java.util.Deque<Long> pluginSearchTimestamps = new java.util.ArrayDeque<>();

    public LoginPanel(CognitoAuthService cognitoAuthService, Consumer<String> onPluginSearch, Runnable onLoginStateChanged)
    {
        this.cognitoAuthService = cognitoAuthService;
        this.onPluginSearch = onPluginSearch;
        this.onLoginStateChanged = onLoginStateChanged;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setMaximumSize(new Dimension(220, 85));
        setPreferredSize(new Dimension(220, 85));

        initUI();
    }

    private static final String PLACEHOLDER = "Player to search";

    private void initUI()
    {
        searchField = new JTextField(PLACEHOLDER);
        searchField.setHorizontalAlignment(JTextField.CENTER);
        searchField.setForeground(Color.GRAY);
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if (searchField.getText().equals(PLACEHOLDER)) {
                    searchField.setText("");
                    searchField.setForeground(null);
                }
            }
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (searchField.getText().isEmpty()) {
                    searchField.setText(PLACEHOLDER);
                    searchField.setForeground(Color.GRAY);
                }
            }
        });
        searchField.addActionListener(e -> searchUserOnPlugin());
        add(searchField);

        add(Box.createVerticalStrut(4));

        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JButton websiteSearchBtn = new JButton("<html><center>Website<br>Search</center></html>");
        websiteSearchBtn.addActionListener(e -> searchUserOnWebsite());

        pluginSearchBtn = new JButton("<html><center>Plugin<br>Search</center></html>");
        pluginSearchBtn.addActionListener(e -> searchUserOnPlugin());

        btnPanel.add(websiteSearchBtn);
        btnPanel.add(pluginSearchBtn);
        add(btnPanel);

        loginButton = new JButton("Login to view more stats");
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.addActionListener(e -> handleLogin());
    }

    public JButton getLoginButton()
    {
        return loginButton;
    }

    private boolean isValidUsername(String username)
    {
        if (username == null) return false;
        String trimmed = username.trim();
        if (trimmed.isEmpty() || trimmed.length() > 12) return false;
        // Allow alphanumeric, spaces, underscores, and hyphens (RuneScape username format)
        return trimmed.matches("^[a-zA-Z0-9 _-]+$");
    }
    
    private String normalizeUsername(String username)
    {
        if (username == null) return null;
        // Normalize: trim, lowercase, collapse multiple spaces to single space
        return username.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private void searchUserOnWebsite()
    {
        String username = searchField.getText();
        if (username == null || username.trim().isEmpty() || PLACEHOLDER.equals(username))
        {
            LinkBrowser.browse("https://devsecopsautomated.com/index.html");
            return;
        }
        if (!isValidUsername(username)) return;
        try
        {
            String normalizedUsername = normalizeUsername(username);
            String encodedUsername = URLEncoder.encode(normalizedUsername, "UTF-8");
            String url = "https://devsecopsautomated.com/profile.html?player=" + encodedUsername;
            LinkBrowser.browse(url);
        }
        catch (Exception ignore) {}
    }

    private void searchUserOnPlugin()
    {
        String username = searchField.getText();
        if (username == null || username.trim().isEmpty() || PLACEHOLDER.equals(username))
        {
            return;
        }
        if (!isValidUsername(username))
        {
            return;
        }
        
        // Rate limit: 10 searches per minute
        if (!checkPluginSearchRateLimit())
        {
            // Show rate limit feedback briefly
            if (pluginSearchBtn != null)
            {
                pluginSearchBtn.setText("Wait...");
                Timer timer = new Timer(1000, e -> pluginSearchBtn.setText("Search"));
                timer.setRepeats(false);
                timer.start();
            }
            return;

        }
        
        if (onPluginSearch != null)
        {
            String normalizedUsername = normalizeUsername(username);
            onPluginSearch.accept(normalizedUsername);

            searchField.setText(PLACEHOLDER);
            searchField.setForeground(Color.GRAY);
            searchField.transferFocus();
        }
    }
    
    private boolean checkPluginSearchRateLimit()
    {
        long now = System.currentTimeMillis();
        long oneMinuteAgo = now - 60_000;
        
        // Remove timestamps older than 1 minute
        while (!pluginSearchTimestamps.isEmpty() && pluginSearchTimestamps.peekFirst() < oneMinuteAgo)
        {
            pluginSearchTimestamps.pollFirst();
        }
        
        // Check if we've exceeded the limit
        if (pluginSearchTimestamps.size() >= MAX_PLUGIN_SEARCHES_PER_MINUTE)
        {
            return false; // Rate limited
        }
        
        // Record this search
        pluginSearchTimestamps.addLast(now);
        return true;
    }

    private void handleLogin()
    {
        if (loginInProgress) { return; }
        if (isLoggedIn)
        {
            // Logout
            setLoggedIn(false);
            clearTokens();
            if (onLoginStateChanged != null) onLoginStateChanged.run();
            return;
        }
        
        // Use Cognito OAuth flow
        try
        {
            setLoginBusy(true);
            cognitoAuthService.login().thenAccept(success -> {
                if (success && cognitoAuthService.isLoggedIn() && cognitoAuthService.getStoredIdToken() != null)
                {
                    SwingUtilities.invokeLater(() -> {
                        setLoginBusy(false);
                        setLoggedIn(true);
                        if (onLoginStateChanged != null) onLoginStateChanged.run();
                    });
                }
                else
                {
                    SwingUtilities.invokeLater(() -> {
                        setLoginBusy(false);
                    });
                }
            }).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> {
                    setLoginBusy(false);
                });
                return null;
            });
        }
        catch (Exception e)
        {
            setLoginBusy(false);
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
            if (searchField != null) searchField.setEnabled(!busy);
        }
        catch (Exception ignore) {}
    }

    public void setLoggedIn(boolean loggedIn)
    {
        this.isLoggedIn = loggedIn;
        if (loginButton != null)
        {
            loginButton.setText(loggedIn ? "Logout" : "Login to view more stats");
        }
    }
    
    public void setPluginSearchText(String text)
    {
        if (searchField != null)
        {
            searchField.setText(text);
        }
    }
    
    public String getPluginSearchText()
    {
        if (searchField == null) return "";
        String text = searchField.getText();
        return PLACEHOLDER.equals(text) ? "" : text;
    }

    private void clearTokens() {
        cognitoAuthService.logout();
        isLoggedIn = false;
        if (loginButton != null) loginButton.setText("Login to view stats in runelite");
    }
}
