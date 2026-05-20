package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.PvPLeaderboardConstants;
import com.pvp.leaderboard.PvPLeaderboardPlugin;
import com.pvp.leaderboard.service.BlockedPlayersService;
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

    // Top-level navigation (Segment 1.5): Matchmaking + Player Lookup. Two
    // tabs only so each label has plenty of horizontal room (no cutoff).
    private static final String CARD_MATCHMAKING = "matchmaking";
    private static final String CARD_LOOKUP = "lookup";
    private JButton tabMatchmakingBtn;
    private JButton tabLookupBtn;
    private CardLayout viewCards;
    private JPanel viewContainer;

    // Matchmaking sub-tabs (Segment 1.5): "Matchmaking Lobby" + greyed
    // "Tournaments" with the same Coming-soon-flash UX as the old top-level
    // matchmaking button.
    private static final String SUBCARD_LOBBY = "lobby";
    private static final String SUBCARD_TOURNAMENTS = "tournaments";
    private JButton subTabLobbyBtn;
    private JButton subTabTournamentsBtn;
    private CardLayout matchmakingSubCards;
    private JPanel matchmakingSubCardContainer;
    /** Held as a field so the dashboard can wire the profile-click → Player
     *  Lookup tab callback after construction. */
    private MatchmakingLobbyPanel matchmakingLobbyPanel;

    /** Block / Unblock toggle that lives just above {@link #playerNameLabel}.
     *  Hidden until a non-self player has been searched. */
    private JButton blockPlayerBtn;

    // State
    private String currentMatchesPlayerId = null;
    private JsonArray allMatches = null;
    private long lastRefreshTimestamp = 0;
    private volatile int loadGeneration = 0;
    
    private static final int MATCHES_PAGE_SIZE = 100;
    private static final long STALE_DATA_THRESHOLD_MS = 60L * 60L * 1000L; // 1 hour
    
    /** Single {@link com.pvp.leaderboard.lobby.LobbyService} dependency used
     *  by {@link #createMatchmakingCard}. Injected from
     *  {@code PvPLeaderboardPlugin.startUp()} so production gets the
     *  real {@code WebSocketLobbyService}. Falls back to
     *  {@link com.pvp.leaderboard.lobby.NoOpLobbyService} when no
     *  service is supplied (e.g. unit tests). */
    private final com.pvp.leaderboard.lobby.LobbyService lobbyService;

    /** Anti-smurf gate driving {@link MatchmakingLobbyPanel}'s
     *  per-style lock state. Production uses
     *  {@code UserProfileLobbyJoinGate} (auto-refresh every hour via
     *  {@link PvPDataService}); tests typically pass
     *  {@code NoOpLobbyJoinGate}. Falls back to a no-op gate at
     *  construction if {@code null} is provided so callers that don't
     *  know about the gate yet still build cleanly. */
    private final com.pvp.leaderboard.lobby.LobbyJoinGate joinGate;

    /** Persistence backend for the lobby gate's selections (region /
     *  styles / builds / rank-slider bounds / hasJoined sticky flag).
     *  Production uses the Guice-provided {@link com.pvp.leaderboard.lobby.LobbyPreferences}
     *  backed by {@code ConfigManager}; tests pass
     *  {@link com.pvp.leaderboard.lobby.LobbyPreferences#inMemory()}.
     *  Always non-null — the older 4-arg + 5-arg ctors substitute the
     *  in-memory variant so source-compat is preserved. */
    private final com.pvp.leaderboard.lobby.LobbyPreferences lobbyPreferences;

    /** Three-arg ctor kept for source-compatibility with any pre-gate
     *  call sites; substitutes a {@link com.pvp.leaderboard.lobby.NoOpLobbyJoinGate}
     *  so the panel still builds (server-side {@code SMURF_GUARD v2}
     *  remains the authoritative check). */
    public DashboardPanel(PvPLeaderboardPlugin plugin, PvPDataService pvpDataService,
                          CognitoAuthService cognitoAuthService,
                          com.pvp.leaderboard.lobby.LobbyService lobbyService)
    {
        this(plugin, pvpDataService, cognitoAuthService, lobbyService,
            new com.pvp.leaderboard.lobby.NoOpLobbyJoinGate(),
            com.pvp.leaderboard.lobby.LobbyPreferences.inMemory());
    }

    /** Five-arg ctor kept for source-compatibility with the pre-prefs
     *  wiring; substitutes an in-memory {@link com.pvp.leaderboard.lobby.LobbyPreferences}
     *  so the panel still builds without a {@code ConfigManager}. */
    public DashboardPanel(PvPLeaderboardPlugin plugin, PvPDataService pvpDataService,
                          CognitoAuthService cognitoAuthService,
                          com.pvp.leaderboard.lobby.LobbyService lobbyService,
                          com.pvp.leaderboard.lobby.LobbyJoinGate joinGate)
    {
        this(plugin, pvpDataService, cognitoAuthService, lobbyService, joinGate,
            com.pvp.leaderboard.lobby.LobbyPreferences.inMemory());
    }

    public DashboardPanel(PvPLeaderboardPlugin plugin, PvPDataService pvpDataService,
                          CognitoAuthService cognitoAuthService,
                          com.pvp.leaderboard.lobby.LobbyService lobbyService,
                          com.pvp.leaderboard.lobby.LobbyJoinGate joinGate,
                          com.pvp.leaderboard.lobby.LobbyPreferences lobbyPreferences)
    {
        this.plugin = plugin;
        this.pvpDataService = pvpDataService;
        this.cognitoAuthService = cognitoAuthService;
        this.joinGate = joinGate != null
            ? joinGate
            : new com.pvp.leaderboard.lobby.NoOpLobbyJoinGate();
        this.lobbyService = lobbyService != null
            ? lobbyService
            : new com.pvp.leaderboard.lobby.NoOpLobbyService();
        this.lobbyPreferences = lobbyPreferences != null
            ? lobbyPreferences
            : com.pvp.leaderboard.lobby.LobbyPreferences.inMemory();

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

        // Build the rank-tier toggle button up front so the community box can
        // own it (the listener is wired below, once statsContainer +
        // rankTierScrollPane exist).
        rankTierToggle = new JButton("What are the ranks");
        rankTierToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        rankTierToggle.setHorizontalAlignment(SwingConstants.CENTER);

        // 1. Community box — always pinned to the very top, never inside a tab.
        JPanel communityBox = createCommunityBox();
        communityBox.setAlignmentX(LEFT_ALIGNMENT);
        mainPanel.add(communityBox);
        mainPanel.add(Box.createVerticalStrut(8));

        // 2. Two-tab navigation row (Matchmaking / Player Lookup). Tournaments
        // is now a sub-tab inside Matchmaking, so each top-level button gets
        // ~half the panel width — no cutoff.
        JPanel navRow = createTabNavRow();
        navRow.setAlignmentX(LEFT_ALIGNMENT);
        mainPanel.add(navRow);
        mainPanel.add(Box.createVerticalStrut(6));

        // 3. Card-switched view container. Each tab maps to one card; the
        // greyed Tournaments tab doesn't have a card and never switches.
        viewCards = new CardLayout();
        viewContainer = new JPanel(viewCards);
        viewContainer.setAlignmentX(LEFT_ALIGNMENT);

        // --- Stats view (everything that used to live below the rank-tier toggle) ---
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

        // 5. Block Player toggle — lives directly above the player name label
        // so the affordance is obvious ("this is the person you're about to
        // block"). Hidden by default; loadMatchHistory toggles visibility +
        // text once a player is selected (and never for self).
        blockPlayerBtn = new JButton("Block Player");
        blockPlayerBtn.setAlignmentX(LEFT_ALIGNMENT);
        blockPlayerBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        blockPlayerBtn.setHorizontalAlignment(SwingConstants.CENTER);
        blockPlayerBtn.setVisible(false);
        blockPlayerBtn.setToolTipText("Block this player from sending you matchmaking invites");
        blockPlayerBtn.addActionListener(e -> handleBlockPlayerToggle());
        statsContainer.add(blockPlayerBtn);
        statsContainer.add(Box.createVerticalStrut(4));

        // 6. Player name label
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

        // --- Rank tier view (hidden by default, swapped in when toggle is clicked) ---
        RankTierPanel rankTierPanel = new RankTierPanel();
        rankTierScrollPane = new JScrollPane(rankTierPanel);
        rankTierScrollPane.setAlignmentX(LEFT_ALIGNMENT);
        rankTierScrollPane.setBorder(BorderFactory.createTitledBorder("Rank Tiers"));
        rankTierScrollPane.setVisible(false);

        // Player Lookup card holds both the stats container and the rank-tier
        // scroll pane. Only one is visible at a time; the rank-tier toggle (now
        // living in the community box) flips between them and also forces the
        // active tab to Player Lookup.
        JPanel lookupCard = new JPanel();
        lookupCard.setLayout(new BoxLayout(lookupCard, BoxLayout.Y_AXIS));
        lookupCard.setAlignmentX(LEFT_ALIGNMENT);
        lookupCard.add(statsContainer);
        lookupCard.add(rankTierScrollPane);

        // Matchmaking card hosts its own sub-tab nav + sub-card layout.
        JPanel matchmakingCard = createMatchmakingCard();

        viewContainer.add(matchmakingCard, CARD_MATCHMAKING);
        viewContainer.add(lookupCard, CARD_LOOKUP);
        mainPanel.add(viewContainer);

        rankTierToggle.addActionListener(e -> {
            // Always pull focus into the Player Lookup tab so the toggle is
            // useful regardless of which tab the user is on.
            setActiveTab(CARD_LOOKUP);
            boolean showingTiers = rankTierScrollPane.isVisible();
            statsContainer.setVisible(showingTiers);
            rankTierScrollPane.setVisible(!showingTiers);
            rankTierToggle.setText(showingTiers ? "What are the ranks" : "Back to stats");
            mainPanel.revalidate();
            mainPanel.repaint();
        });

        // Default tab: Matchmaking Lobby (per design — encourages people to
        // explore the new lobby; stats keep loading in the background so
        // switching to Player Lookup is instant).
        setActiveTab(CARD_MATCHMAKING);

        return mainPanel;
    }

    /**
     * Two-tab nav row (Matchmaking + Player Lookup). Active tab uses a bold
     * font; inactive uses plain. Tournaments is no longer here — it's a
     * sub-tab inside Matchmaking now.
     */
    private JPanel createTabNavRow()
    {
        JPanel nav = new JPanel(new GridLayout(1, 2, 4, 0));
        nav.setName("top-tab-nav");
        // Tall enough to fit NAV_FONT_PT (16pt BOLD) without clipping the descenders.
        nav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        nav.setPreferredSize(new Dimension(220, 40));

        tabMatchmakingBtn = makeTabButton("Matchmaking", true);
        tabMatchmakingBtn.addActionListener(e -> { revertRankTierIfShowing(); setActiveTab(CARD_MATCHMAKING); });
        nav.add(tabMatchmakingBtn);

        tabLookupBtn = makeTabButton("Player Lookup", false);
        tabLookupBtn.addActionListener(e -> { revertRankTierIfShowing(); setActiveTab(CARD_LOOKUP); });
        nav.add(tabLookupBtn);

        return nav;
    }

    private static JButton makeTabButton(String label, boolean active)
    {
        JButton b = new JButton(label);
        b.setMargin(new Insets(2, 4, 2, 4));
        // Always BOLD — switching weight per-state changes the text width and
        // clipped longer labels ("Player Lookup" → "Player Loo...") when active.
        // Active state is now indicated purely by the bottom underline below.
        b.setFont(b.getFont().deriveFont(Font.BOLD, NAV_FONT_PT));
        b.setHorizontalAlignment(SwingConstants.CENTER);
        b.setFocusPainted(false);
        applyTabActiveStyle(b, active);
        return b;
    }

    /** Stable client-property key tests use to assert active-tab state without
     *  depending on visual styling internals. Value is a {@link Boolean}. */
    public static final String TAB_ACTIVE_PROPERTY = "pvp.tab.active";

    /** Active-tab indicator: a 3px bottom underline in {@link #TAB_ACCENT}. The
     *  inactive state uses the same height border but in the panel background
     *  colour, so flipping active/inactive doesn't shift the layout by a pixel.
     *  Critically, font weight + size never change — that was the source of
     *  the "Player Loo..." clipping bug (BOLD vs PLAIN have different metrics).
     *  Also stamps {@link #TAB_ACTIVE_PROPERTY} for test introspection. */
    private static void applyTabActiveStyle(JButton b, boolean active)
    {
        if (b == null) return;
        Color underline = active ? TAB_ACCENT : TAB_UNDERLINE_BG;
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 3, 0, underline),
            BorderFactory.createEmptyBorder(2, 4, 0, 4)));
        b.putClientProperty(TAB_ACTIVE_PROPERTY, Boolean.valueOf(active));
    }

    /** Underline colour for the active tab. Matches the gate's "Go to lobby"
     *  green so the accent reads as one product across the panel. */
    private static final Color TAB_ACCENT = new Color(0x2e, 0x7d, 0x32);
    /** Same-height underline colour for inactive tabs — keeps every tab the
     *  exact same height regardless of selection. */
    private static final Color TAB_UNDERLINE_BG = new Color(0x40, 0x40, 0x40);

    /** Single font size shared by every navigation button — top tabs
     *  ([Matchmaking] / [Player Lookup]) and Matchmaking sub-tabs
     *  ([Lobby] / [Tournaments]) — and by every community-box button
     *  ([Discord], [Website], [Report Bugs], [What are the ranks]).
     *  Matches {@code MatchmakingLobbyPanel.GATE_HEADER_PT} so the whole
     *  sidepanel reads as one consistent typographic block.
     *
     *  <p>Was 18pt; trimmed to 16pt because at 18pt "Player Lookup" was
     *  clipping at the right edge of its half-width tab cell on the
     *  default 215px sidepanel. 16pt fits comfortably with ~6px slack. */
    private static final float NAV_FONT_PT = 16f;

    /** Per-row height the community-box buttons are sized to. Tracks
     *  {@link #NAV_FONT_PT} — at 16pt BOLD the button needs ~34px to
     *  show without clipping descenders or wasting whitespace. */
    private static final int COMMUNITY_BTN_H = 34;

    /** Switch the visible top-level card and re-style nav buttons. */
    /** If the rank-tier explainer is currently showing, fold it back to the
     *  stats view (same effect as clicking "Back to stats" on the toggle).
     *  Called from the top-tab action listeners so switching tabs while the
     *  tier view is up automatically takes the user back to stats — they
     *  don't have to remember to click the toggle off first. Intentionally
     *  not called from inside {@link #setActiveTab} because the toggle's own
     *  listener calls setActiveTab(CARD_LOOKUP) and would double-flip. */
    private void revertRankTierIfShowing()
    {
        if (rankTierScrollPane == null || statsContainer == null || rankTierToggle == null) return;
        if (!rankTierScrollPane.isVisible()) return;
        statsContainer.setVisible(true);
        rankTierScrollPane.setVisible(false);
        rankTierToggle.setText("What are the ranks");
    }

    private void setActiveTab(String key)
    {
        if (viewCards != null && viewContainer != null) {
            viewCards.show(viewContainer, key);
        }
        applyTabActiveStyle(tabMatchmakingBtn, CARD_MATCHMAKING.equals(key));
        applyTabActiveStyle(tabLookupBtn, CARD_LOOKUP.equals(key));
    }

    /**
     * Matchmaking top-level card. Hosts its own sub-tab row (Matchmaking
     * Lobby / Tournaments-greyed) plus a sub-card layout swapping between
     * the lobby panel and an empty tournaments placeholder. The Tournaments
     * sub-tab is greyed and flashes "Coming soon" on click.
     */
    private JPanel createMatchmakingCard()
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(LEFT_ALIGNMENT);

        // Sub-tab nav row — same 40px height as the top tab nav so the four nav
        // buttons render at identical heights (they share NAV_FONT_PT).
        JPanel subNav = new JPanel(new GridLayout(1, 2, 4, 0));
        subNav.setName("matchmaking-subtab-nav");
        subNav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        subNav.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        // CRITICAL: every direct child of `card` (BoxLayout.Y_AXIS) must share the
        // same alignmentX. The sub-card container below is LEFT_ALIGNMENT, so subNav
        // must match — otherwise BoxLayout's off-axis alignment math right-shifts the
        // wider sibling (the sub-card container) into a half-width gutter on the right
        // and leaves the rest of the card empty. This is the SAME failure mode that
        // bit MatchmakingLobbyPanel.buildUi() — see leftAlignedStrut() there.
        subNav.setAlignmentX(LEFT_ALIGNMENT);

        // Renamed from "Matchmaking Lobby" → "Lobby" so the sub-tab fits next
        // to "Tournaments" at NAV_FONT_PT (18pt) without clipping in a 220px
        // sidepanel. The matchmaking context is already established by the
        // parent top-level "Matchmaking" tab.
        subTabLobbyBtn = new JButton("Lobby");
        subTabLobbyBtn.setMargin(new Insets(1, 4, 1, 4));
        // Always BOLD — same rationale as the top tab buttons (see makeTabButton).
        subTabLobbyBtn.setFont(subTabLobbyBtn.getFont().deriveFont(Font.BOLD, NAV_FONT_PT));
        subTabLobbyBtn.setFocusPainted(false);
        applyTabActiveStyle(subTabLobbyBtn, true);
        subTabLobbyBtn.addActionListener(e -> setActiveMatchmakingSubTab(SUBCARD_LOBBY));
        subNav.add(subTabLobbyBtn);

        final String tournamentsDefault = "Tournaments";
        final String tournamentsClicked = "<html><center>Coming soon,<br>check Discord</center></html>";
        subTabTournamentsBtn = new JButton(tournamentsDefault);
        subTabTournamentsBtn.setMargin(new Insets(1, 4, 1, 4));
        subTabTournamentsBtn.setFont(subTabTournamentsBtn.getFont().deriveFont(Font.BOLD, NAV_FONT_PT));
        subTabTournamentsBtn.setEnabled(false);
        subTabTournamentsBtn.setFocusPainted(false);
        applyTabActiveStyle(subTabTournamentsBtn, false);
        subTabTournamentsBtn.setToolTipText("Coming soon — check Discord for updates");
        subTabTournamentsBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!subTabTournamentsBtn.isEnabled() && subTabTournamentsBtn.getText().equals(tournamentsDefault)) {
                    subTabTournamentsBtn.setText(tournamentsClicked);
                    Timer revertTimer = new Timer(15000, ev -> subTabTournamentsBtn.setText(tournamentsDefault));
                    revertTimer.setRepeats(false);
                    revertTimer.start();
                }
            }
        });
        subNav.add(subTabTournamentsBtn);
        card.add(subNav);

        // Sub-card container: lobby panel + (currently empty) tournaments placeholder
        matchmakingSubCards = new CardLayout();
        matchmakingSubCardContainer = new JPanel(matchmakingSubCards);
        matchmakingSubCardContainer.setAlignmentX(LEFT_ALIGNMENT);

        // Production wiring uses the injected LobbyService (the real
        // WebSocketLobbyService) + the ConfigManager-backed
        // LobbyPreferences so gate selections persist across plugin
        // restarts (region / styles / builds / rank slider /
        // hasJoinedLobby sticky flag).
        matchmakingLobbyPanel = new MatchmakingLobbyPanel(lobbyService, joinGate, lobbyPreferences);
        // Lobby profile-row clicks → same code path as right-click "PvP lookup".
        matchmakingLobbyPanel.setOnOpenProfile(this::openPlayerLookup);
        // Self-profile preview ("Your profile displayed to others")
        // above the rank slider — supplies the local OSRS name lazily
        // so the row pre-login renders empty (supplier returns null)
        // and auto-populates on the next gate refresh after the user
        // logs in. No-op when running without a plugin handle (e.g.
        // unit tests that don't construct a plugin).
        if (plugin != null)
        {
            matchmakingLobbyPanel.setSelfIdentity(plugin::getLocalPlayerName);
            // Eager game-state signal: flips true the instant
            // GameState.LOGGED_IN fires, well before
            // lobbyJoinGate.onLogin() (which waits 10 ticks for the
            // local player name to resolve). Drives the lobby gate's
            // three-phase notice — "Please log into the game" → "Loading…"
            // → fully-built gate — so the user doesn't see a stale
            // logged-out prompt during the ~6s startup window.
            matchmakingLobbyPanel.setIsGameLoggedInSupplier(plugin::isGameLoggedIn);
        }
        matchmakingSubCardContainer.add(matchmakingLobbyPanel, SUBCARD_LOBBY);

        JPanel tournamentsPlaceholder = new JPanel();
        tournamentsPlaceholder.setLayout(new BorderLayout());
        JLabel tphint = new JLabel("Tournaments — coming soon", SwingConstants.CENTER);
        tphint.setFont(tphint.getFont().deriveFont(Font.ITALIC, 11f));
        tphint.setForeground(new Color(0x999999));
        tournamentsPlaceholder.add(tphint, BorderLayout.CENTER);
        matchmakingSubCardContainer.add(tournamentsPlaceholder, SUBCARD_TOURNAMENTS);

        card.add(matchmakingSubCardContainer);
        return card;
    }

    private void setActiveMatchmakingSubTab(String key)
    {
        if (matchmakingSubCards != null && matchmakingSubCardContainer != null) {
            matchmakingSubCards.show(matchmakingSubCardContainer, key);
        }
        applyTabActiveStyle(subTabLobbyBtn, SUBCARD_LOBBY.equals(key));
        // Tournaments stays inactive (no underline) — it's greyed and never lights up.
        applyTabActiveStyle(subTabTournamentsBtn, false);
    }
    
    // --- UI Creation Helpers ---
    
    private JPanel createCommunityBox()
    {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        // TitledBorder font matches NAV_FONT_PT (18pt BOLD) so the "Join the
        // community" header reads at the same weight as the gate's
        // "Set up matchmaking" header and the nav buttons below it.
        javax.swing.border.TitledBorder titled = BorderFactory.createTitledBorder("Join the community");
        Font titleFont = box.getFont().deriveFont(Font.BOLD, NAV_FONT_PT);
        titled.setTitleFont(titleFont);
        box.setBorder(titled);
        // Sized for: title (~28px) + 3 button rows × COMMUNITY_BTN_H (38) + 3
        // small inter-row gaps + bottom slack.
        int boxH = 28 + 3 * COMMUNITY_BTN_H + 12;
        box.setMaximumSize(new Dimension(220, boxH));
        box.setPreferredSize(new Dimension(220, boxH));

        // Discord + Website share a row via GridLayout(1,2) so each button
        // gets exactly half the panel width — at 18pt they no longer fit
        // side-by-side under FlowLayout's preferred-size sizing.
        JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, COMMUNITY_BTN_H));
        JButton discordBtn = makeCommunityBtn("Discord", "Join our Discord");
        discordBtn.addActionListener(e -> {
            try { LinkBrowser.browse("https://discord.gg/TmFzcbW3Rp"); } catch (Exception ignore) {}
        });
        row.add(discordBtn);
        JButton websiteBtn = makeCommunityBtn("Website", "Open the website");
        websiteBtn.addActionListener(e -> {
            try { LinkBrowser.browse(PvPLeaderboardConstants.PUBLIC_SITE_BASE_URL); } catch (Exception ignore) {}
        });
        row.add(websiteBtn);
        box.add(row);

        JPanel row2 = new JPanel(new GridLayout(1, 1, 0, 0));
        row2.setMaximumSize(new Dimension(Integer.MAX_VALUE, COMMUNITY_BTN_H));
        JButton reportBugsBtn = makeCommunityBtn("Report Bugs", "Report bugs on Discord");
        reportBugsBtn.addActionListener(e -> {
            try { LinkBrowser.browse("https://discord.gg/TmFzcbW3Rp"); } catch (Exception ignore) {}
        });
        row2.add(reportBugsBtn);
        box.add(row2);

        // "What are the ranks" lives here (below Report Bugs) so it's reachable
        // from any active tab. The action listener is wired in createMainPanel
        // once statsContainer + rankTierScrollPane exist.
        JPanel row3 = new JPanel(new GridLayout(1, 1, 0, 0));
        row3.setMaximumSize(new Dimension(Integer.MAX_VALUE, COMMUNITY_BTN_H));
        rankTierToggle.setFont(rankTierToggle.getFont().deriveFont(Font.BOLD, NAV_FONT_PT));
        rankTierToggle.setToolTipText("Show every rank tier from Bronze 3 to 3rd Age");
        // Override the earlier setMaximumSize(MAX,25) — that 25px ceiling
        // would cap the button at the nav-bar height instead of letting it
        // grow to fit 18pt text.
        rankTierToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, COMMUNITY_BTN_H));
        rankTierToggle.setPreferredSize(null);
        row3.add(rankTierToggle);
        box.add(row3);

        return box;
    }

    /** Community-box button factory — uniform NAV_FONT_PT BOLD font, no
     *  per-button preferred size (parent {@link GridLayout} controls width),
     *  focus painting off to match the nav buttons. */
    private static JButton makeCommunityBtn(String label, String tooltip)
    {
        JButton b = new JButton(label);
        b.setFont(b.getFont().deriveFont(Font.BOLD, NAV_FONT_PT));
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setHorizontalAlignment(SwingConstants.CENTER);
        b.setFocusPainted(false);
        b.setToolTipText(tooltip);
        return b;
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

        // Site auth gates extraStatsPanel + match-history reload below.

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

    /**
     * Toggles the blocked state for the currently-displayed player.
     * Updates the button label so the user gets immediate confirmation
     * of the new state. The in-memory {@link BlockedPlayersService}
     * registry is consulted by the lobby roster filter.
     */
    private void handleBlockPlayerToggle()
    {
        if (currentMatchesPlayerId == null || currentMatchesPlayerId.isEmpty()) return;
        boolean nowBlocked = BlockedPlayersService.toggle(currentMatchesPlayerId);
        refreshBlockButtonState(nowBlocked);
    }

    /** Updates the Block / Unblock button text + tooltip based on the
     *  {@code blocked} state. Visibility is managed separately by
     *  {@link #updateBlockButtonVisibility(String)}. The Block state gets a
     *  red text + red outline treatment to mark it as a destructive action;
     *  Unblock reverts to default styling because allowing invites again is
     *  not destructive. */
    private void refreshBlockButtonState(boolean blocked)
    {
        if (blockPlayerBtn == null) return;
        if (blocked)
        {
            blockPlayerBtn.setText("Unblock Player");
            blockPlayerBtn.setToolTipText("Allow this player to send you matchmaking invites again");
            blockPlayerBtn.setForeground(null); // restore L&F default
            blockPlayerBtn.setBorder(UIManager.getBorder("Button.border"));
        }
        else
        {
            blockPlayerBtn.setText("Block Player");
            blockPlayerBtn.setToolTipText("Block this player from sending you matchmaking invites");
            Color danger = new Color(0xd0, 0x45, 0x45);
            blockPlayerBtn.setForeground(danger);
            blockPlayerBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(danger, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        }
    }

    /** Shows the Block / Unblock toggle for non-self lookups, hides it
     *  otherwise (you can't block yourself). Called from
     *  {@link #loadMatchHistory(String)}'s UI reset. */
    private void updateBlockButtonVisibility(String playerId)
    {
        if (blockPlayerBtn == null) return;
        String normalized = normalizePlayerId(playerId);
        String selfName = plugin != null ? plugin.getLocalPlayerName() : null;
        String selfNormalized = normalizePlayerId(selfName);
        boolean isSelf = normalized != null && selfNormalized != null
            && normalized.equalsIgnoreCase(selfNormalized);
        boolean show = normalized != null && !normalized.isEmpty() && !isSelf;
        blockPlayerBtn.setVisible(show);
        if (show)
        {
            refreshBlockButtonState(BlockedPlayersService.isBlocked(normalized));
        }
    }

    // --- Data Loading ---
    
    /** Forwards a {@code GameStateChanged} signal from the plugin to
     *  the matchmaking panel so the lobby-gate notice can flip
     *  between "Please log into the game" and "Loading\u2026" without
     *  waiting for the 10-tick {@link com.pvp.leaderboard.lobby.LobbyJoinGate#onLogin()}
     *  delay. Safe to call before {@link MatchmakingLobbyPanel} has
     *  wired its game-state supplier (no-ops gracefully). */
    public void refreshLobbyLoginView()
    {
        if (matchmakingLobbyPanel != null)
        {
            matchmakingLobbyPanel.refreshLoginGateView();
        }
    }

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
     * Public entry point for the right-click "PvP lookup" menu (and any future
     * caller that wants to surface a specific player). Forces the Player Lookup
     * tab to the foreground BEFORE kicking off the match-history load so the
     * user actually sees the data they asked for.
     *
     * <p>Kept separate from {@link #loadMatchHistory(String)} on purpose:
     * loadMatchHistory is also invoked by auto-paths (login, game events,
     * refresh) where forcibly switching tabs would yank focus away from the
     * Matchmaking Lobby unexpectedly. Right-click menu = explicit intent =
     * tab switch is desired; everything else = silent background load.
     */
    public void openPlayerLookup(String playerId)
    {
        Runnable open = () ->
        {
            setActiveTab(CARD_LOOKUP);
            loadMatchHistory(playerId);
        };
        if (SwingUtilities.isEventDispatchThread())
        {
            open.run();
        }
        else
        {
            SwingUtilities.invokeLater(open);
        }
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
            updateBlockButtonVisibility(currentMatchesPlayerId);
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

