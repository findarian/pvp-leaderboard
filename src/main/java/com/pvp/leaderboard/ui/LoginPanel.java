package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.service.CognitoAuthService;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.URLEncoder;
import java.util.function.Consumer;
import net.runelite.client.util.LinkBrowser;

public class LoginPanel extends JPanel
{
    private final CognitoAuthService cognitoAuthService;
    private final Consumer<String> onPluginSearch;
    private final Runnable onLoginStateChanged;

    private JTextField websiteSearchField;
    private JTextField pluginSearchField;
    private JButton loginButton;
    
    private boolean loginInProgress = false;
    private boolean isLoggedIn = false;

    public LoginPanel(CognitoAuthService cognitoAuthService, Consumer<String> onPluginSearch, Runnable onLoginStateChanged)
    {
        this.cognitoAuthService = cognitoAuthService;
        this.onPluginSearch = onPluginSearch;
        this.onLoginStateChanged = onLoginStateChanged;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Login to view stats in runelite"));
        setMaximumSize(new Dimension(220, 190));
        setPreferredSize(new Dimension(220, 190));

        initUI();
    }

    private void initUI()
    {
        // Website search
        JLabel websiteLabel = new JLabel("Search user on website:");
        websiteLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(websiteLabel);
        
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
        add(websitePanel);
        
        add(Box.createVerticalStrut(5));
        
        // Plugin search
        JLabel pluginLabel = new JLabel("Search user on plugin:");
        pluginLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(pluginLabel);
        
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
        add(pluginPanel);
        
        add(Box.createVerticalStrut(5));
        
        loginButton = new JButton("Login to view more stats");
        loginButton.setPreferredSize(new Dimension(210, 25));
        loginButton.setMaximumSize(new Dimension(220, 25));
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.addActionListener(e -> handleLogin());
        add(loginButton);
    }

    private void searchUserOnWebsite()
    {
        String username = websiteSearchField.getText();
        if (username == null || username.trim().isEmpty())
        {
            try { LinkBrowser.browse("https://devsecopsautomated.com/index.html"); } catch (Exception ignore) {}
            return;
        }
        try
        {
            String encoded = URLEncoder.encode(username.trim(), "UTF-8");
            LinkBrowser.browse("https://devsecopsautomated.com/profile.html?player=" + encoded);
        }
        catch (Exception ignore) {}
    }

    private void searchUserOnPlugin()
    {
        String username = pluginSearchField.getText();
        if (username != null && !username.trim().isEmpty())
        {
            if (onPluginSearch != null)
            {
                onPluginSearch.accept(username.trim());
            }
        }
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
            if (websiteSearchField != null) websiteSearchField.setEnabled(!busy);
            if (pluginSearchField != null) pluginSearchField.setEnabled(!busy);
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
        if (pluginSearchField != null)
        {
            pluginSearchField.setText(text);
        }
    }
    
    public String getPluginSearchText()
    {
        return pluginSearchField != null ? pluginSearchField.getText() : "";
    }

    private void clearTokens() {
        cognitoAuthService.logout();
        isLoggedIn = false;
        if (loginButton != null) loginButton.setText("Login to view stats in runelite");
    }
}

