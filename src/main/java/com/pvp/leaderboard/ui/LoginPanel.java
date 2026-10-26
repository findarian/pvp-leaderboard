package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.*;
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

    private final Consumer<String> onSearch;

    private JTextField searchField;
    private JButton searchBtn;

    // Rate limiting for plugin search (10 per minute)
    private final Deque<Long> searchTimes = new ArrayDeque<>();

    /** The search box; {@code onSearch} is always wired (the dashboard passes it). */
    public LoginPanel(Consumer<String> onSearch)
    {
        this.onSearch = onSearch;

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
