package com.pvp.leaderboard.ui;

import lombok.*;
import com.pvp.leaderboard.*;
import com.pvp.leaderboard.service.*;
import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import net.runelite.client.util.*;
import javax.swing.Timer;
import static com.pvp.leaderboard.ui.Ui.*;

public class LoginPanel extends JPanel
{
    private static final int MAX_SEARCHES = 10;

    /** Discord brand "blurple" (#5865F2) — matches flipping-copilot's button. */
    private static final Color BLURPLE = new Color(88, 101, 242);

    private final DiscordLogin discordLogin;
    private final Consumer<String> onSearch;
    private final Runnable onLoginState;

    private JTextField searchField;
    private JButton searchBtn;
    @Getter private JButton loginButton;

    private boolean loggingIn = false;
    private boolean isLoggedIn = false;

    // Rate limiting for plugin search (10 per minute)
    private final Deque<Long> searchTimes = new ArrayDeque<>();

    /** Both callbacks are always wired (the dashboard passes them). */
    public LoginPanel(DiscordLogin discordLogin, Consumer<String> onSearch, Runnable onLoginState)
    {
        this.discordLogin = discordLogin;
        this.onSearch = onSearch;
        this.onLoginState = onLoginState;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(pad(4, 4, 4, 4));
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
        maxH(searchField, 25);
        left(searchField);
        searchField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (searchField.getText().equals(PLACEHOLDER)) {
                    searchField.setText("");
                    searchField.setForeground(null);
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (searchField.getText().isEmpty()) {
                    searchField.setText(PLACEHOLDER);
                    searchField.setForeground(Color.GRAY);
                }
            }
        });
        searchField.addActionListener(e -> searchHere());
        add(searchField);

        add(vgap(4));

        var btnPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        left(btnPanel);
        maxH(btnPanel, 40);

        var siteBtn = new JButton("<html><center>Website<br>Search</center></html>");
        siteBtn.addActionListener(e -> searchSite());

        searchBtn = new JButton("<html><center>Plugin<br>Search</center></html>");
        searchBtn.addActionListener(e -> searchHere());

        btnPanel.add(siteBtn);
        btnPanel.add(searchBtn);
        add(btnPanel);

        // Hoisted into the dashboard's community box, which sizes it.
        // Discord blurple (#5865F2), matching flipping-copilot's login button.
        loginButton = new JButton("Login with Discord");
        loginButton.setBackground(BLURPLE);
        loginButton.setForeground(Color.WHITE);
        loginButton.setOpaque(true);
        loginButton.setFocusPainted(false);
        loginButton.addActionListener(e -> handleLogin());
    }

    /** RuneScape name format: 1-12 letters, digits, spaces, underscores or
     *  hyphens. Rejects null, blank and the 16-character placeholder. */
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

    private void searchSite()
    {
        String username = searchField.getText();
        if (username.trim().isEmpty() || PLACEHOLDER.equals(username))
        {
            LinkBrowser.browse(PvpConsts.SITE_URL);
            return;
        }
        if (!isValidUsername(username)) return;
        // A valid name encodes to URL-safe text, so the https link always opens.
        LinkBrowser.browse(PvpConsts.SITE_URL + "/profile.html?player="
            + URLEncoder.encode(normalizeUsername(username), StandardCharsets.UTF_8));
    }

    private void searchHere()
    {
        String username = searchField.getText();
        if (!isValidUsername(username))
        {
            return;
        }

        // Rate limit: 10 searches per minute
        if (!checkLimit())
        {
            // Show rate limit feedback briefly
            searchBtn.setText("Wait...");
            Timer timer = new Timer(1000, e -> searchBtn.setText("Search"));
            timer.setRepeats(false);
            timer.start();
            return;
        }

        onSearch.accept(normalizeUsername(username));

        searchField.setText(PLACEHOLDER);
        searchField.setForeground(Color.GRAY);
        searchField.transferFocus();
    }

    private boolean checkLimit()
    {
        long now = System.currentTimeMillis();
        long oneMinuteAgo = now - 60_000;

        // Remove timestamps older than 1 minute
        while (!searchTimes.isEmpty() && searchTimes.peekFirst() < oneMinuteAgo)
        {
            searchTimes.pollFirst();
        }

        // Check if we've exceeded the limit
        if (searchTimes.size() >= MAX_SEARCHES)
        {
            return false; // Rate limited
        }

        // Record this search
        searchTimes.addLast(now);
        return true;
    }

    private void handleLogin()
    {
        if (loggingIn)
        {
            // The button doubles as "Cancel login" while a handshake is in
            // flight (the OAuth redirect now lands on a hosted page, so login
            // takes a browser round-trip + polling).
            discordLogin.cancelLogin();
            setLoginBusy(false);
            return;
        }
        if (isLoggedIn)
        {
            setLoggedIn(false);
            discordLogin.logout();
            onLoginState.run();
            return;
        }

        // Use Discord OAuth flow. The login state is read on the Swing
        // thread; no logout can happen meanwhile, the button reads Cancel.
        setLoginBusy(true);
        discordLogin.login().whenComplete((success, ex) -> SwingUtilities.invokeLater(() -> {
            setLoginBusy(false);
            if (Boolean.TRUE.equals(success) && discordLogin.isLoggedIn())
            {
                setLoggedIn(true);
                onLoginState.run();
            }
        }));
    }

    /** The button stays enabled while busy so the user can cancel the handshake. */
    private void setLoginBusy(boolean busy)
    {
        loggingIn = busy;
        loginButton.setText(busy ? "Cancel login" : (isLoggedIn ? "Logout" : "Login with Discord"));
        searchField.setEnabled(!busy);
    }

    public void setLoggedIn(boolean loggedIn)
    {
        isLoggedIn = loggedIn;
        loginButton.setText(loggedIn ? "Logout" : "Login with Discord");
    }

    public void setPluginSearchText(String text)
    {
        searchField.setText(text);
    }

    public String getPluginSearchText()
    {
        String text = searchField.getText();
        return PLACEHOLDER.equals(text) ? "" : text;
    }
}
