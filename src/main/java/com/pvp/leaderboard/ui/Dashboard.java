package com.pvp.leaderboard.ui;

import lombok.*;
import com.google.gson.*;
import com.pvp.leaderboard.*;
import com.pvp.leaderboard.cache.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.queue.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.tournament.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;
import javax.swing.Timer;
import static com.pvp.leaderboard.ui.Ui.*;
import static com.pvp.leaderboard.util.JsonLenient.*;
import static javax.swing.BorderFactory.*;
import static javax.swing.SwingUtilities.*;
import static javax.swing.BoxLayout.*;

public class Dashboard extends PluginPanel
{
    private final PvPLeaderboardPlugin plugin;
    private final PvpApi pvpApi;
    private final DiscordLogin discordLogin;

    // Sub-panels
    private LoginPanel loginPanel;
    private RankProgress rankProgress;
    private PerfStats perfStats;
    private ExtraStats extraStats;
    private WinRateChart chartPanel;

    // Header elements
    private JLabel playerLabel;
    private JButton refreshButton;
    private JButton streaksBtn;
    private JButton historyBtn;
    private JButton advancedToggle;
    private JButton tierToggle;
    private JScrollPane tierScroll;
    private JButton topToggle;
    private JScrollPane topScroll;
    private JPanel statsBox;

    // Top-level navigation: Matchmaking + Player Lookup on one row, the
    // Tournaments tab alone on the row below. Each tab shows one card.
    private static final String CARD_LOBBY = "matchmaking";
    private static final String CARD_LOOKUP = "lookup";
    private static final String CARD_TOURNEY = "tournaments";
    private JButton tabLobbyBtn;
    private JButton tabLookupBtn;
    private JButton tabTourneys;
    private CardLayout viewCards;
    private JPanel viewBox;
    /** The card {@link #showCard} showed last. */
    private String activeCard = CARD_LOBBY;
    /** Whether this panel is on screen (the sidebar open with this panel's
     *  tab selected); replaceable in tests. */
    private BooleanSupplier panelOpen = this::isShowing;

    static final String OFF_TOOLTIP = "Tournaments are turned off in the plugin settings";
    /** The Matchmaking card itself: the queue view. Held as a field so the
     *  dashboard can wire the profile-click → Player Lookup tab callback
     *  after construction. */
    private MatchmakingLobbyPanel lobbyCard;

    // The Tournaments card: a greyed placeholder until setTournamentService()
    // wires an available service, which swaps in the live TourneyPanel
    // and enables the tab.
    private JPanel tourneyStub;
    private TourneyPanel tourneyPanel;
    /** The live Tournaments panel's listener on the profile gate's counts. */
    private Runnable countsHook;
    /** What {@link #tourneyPanel} was last told by {@code setShowing}. */
    private boolean tourneyShown;
    private BooleanSupplier combatCheck;

    /** Block / Unblock toggle that lives just above {@link #playerLabel}.
     *  Hidden until a non-self player has been searched. */
    private JButton blockBtn;

    // State
    private String lookupId = null;
    private JsonArray allMatches = null;
    private volatile int loadGen = 0;

    private static final int PAGE_SIZE = 100;

    /** The lobby transport ({@code WebSocketLobbyService} in production). */
    private final LobbyService lobbyService;

    /** Anti-smurf gate driving {@link MatchmakingLobbyPanel}'s per-style
     *  lock state ({@code ProfileGate} in production, auto
     *  refreshed every hour via {@link PvpApi}). */
    private final JoinGate joinGate;

    /** Persistence for the gate's selections (region / rank-slider bounds /
     *  the Show streaks choice): the Guice-provided ConfigManager-backed
     *  {@link LobbyPrefs} in production, the in-memory variant in tests. */
    private final LobbyPrefs lobbyPrefs;

    public Dashboard(PvPLeaderboardPlugin plugin, PvpApi pvpApi,
                          DiscordLogin discordLogin,
                          LobbyService lobbyService,
                          JoinGate joinGate,
                          LobbyPrefs lobbyPrefs)
    {
        this.plugin = plugin;
        this.pvpApi = pvpApi;
        this.discordLogin = discordLogin;
        this.joinGate = joinGate;
        this.lobbyService = lobbyService;
        this.lobbyPrefs = lobbyPrefs;

        setLayout(new BorderLayout());
        setBorder(pad(10, 10, 10, 10));

        JPanel mainPanel = createMain();
        wire(mainPanel);
        add(mainPanel, BorderLayout.CENTER);
    }

    private JPanel createMain()
    {
        var mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, Y_AXIS));

        // The two community-box view toggles and the login panel are built up
        // front so the community box can own their buttons (the toggles'
        // listeners are wired below, once their views exist). The login
        // panel's "Login with Discord" button lives in the community box so
        // login is reachable from any tab; its search field stays in the
        // Player Lookup card.
        tierToggle = new JButton("What are the ranks");
        topToggle = new JButton("Top players");
        loginPanel = left(new LoginPanel(discordLogin, this::loadMatches, this::onLoginState));

        // 1. Community box — always pinned to the very top, never inside a tab.
        mainPanel.add(left(createLinks()));
        mainPanel.add(vgap(8));

        // 2. Navigation: Matchmaking / Player Lookup, then Tournaments on its own row.
        mainPanel.add(left(createNav()));
        mainPanel.add(vgap(4));
        mainPanel.add(left(createTournamentsTabRow()));
        mainPanel.add(vgap(6));

        // 3. Card-switched view container: one card per tab. It is as tall as
        // the card on show, not the tallest card.
        viewCards = new CardLayout();
        viewBox = left(new JPanel(viewCards)
        {
            @Override
            public Dimension getPreferredSize()
            {
                for (Component card : getComponents())
                {
                    if (!card.isVisible()) continue;
                    Dimension d = card.getPreferredSize();
                    Insets in = getInsets();
                    return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
                }
                return super.getPreferredSize();
            }
        });
        viewBox.setName("view-container");

        // --- Stats view ---
        statsBox = left(new JPanel());
        statsBox.setLayout(new BoxLayout(statsBox, Y_AXIS));

        // The search box (its login button lives in the community box).
        statsBox.add(loginPanel);
        statsBox.add(vgap(16));

        // Block Player toggle — directly above the player name label so the
        // affordance is obvious ("this is the person you're about to block").
        // Hidden until a player is selected (and never for self); its words
        // and tooltip come from refreshBlock.
        blockBtn = colBtn("Block Player", e -> handleBlockPlayerToggle(), 4);

        playerLabel = bold(new JLabel(), 18f);
        playerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statsBox.add(maxH(playerLabel, 25));
        statsBox.add(vgap(4));

        // Refresh, then Show streaks / Hide streaks (hidden until a player is
        // searched; the streaks choice is kept in the panel's own key).
        refreshButton = colBtn("Refresh", e -> handleRefresh(), 4);
        streaksBtn = colBtn(null, e -> setStreaksShown(!rankProgress.isStreaksShown()), 24);
        streaksBtn.setName("show-streaks-toggle");

        // Additional Stats, built here and added at the end of the column.
        extraStats = left(new ExtraStats(pvpApi));
        extraStats.setVisible(false);

        rankProgress = left(new RankProgress());
        statsBox.add(rankProgress);
        statsBox.add(vgap(12));
        applyStreaks(lobbyPrefs.getShowStreaks());

        // Match History (below the rating rows, hidden until search).
        historyBtn = colBtn("Popout Match History", e -> {
            JDialog d = dialog(this, "Match History", 800, 500);
            var dialogPanel = new HistoryPanel();
            if (allMatches != null) {
                dialogPanel.setMatches(allMatches);
            }
            d.add(dialogPanel);
            d.setVisible(true);
        }, 12);

        // Advanced Stats toggle (hidden until search) and its section (hidden by default).
        advancedToggle = colBtn("Advanced Stats", null, 12);
        JPanel advancedBox = left(new JPanel());
        advancedBox.setLayout(new BoxLayout(advancedBox, Y_AXIS));
        advancedBox.setVisible(false);

        perfStats = left(new PerfStats());
        advancedBox.add(perfStats);
        advancedBox.add(vgap(12));

        chartPanel = new WinRateChart();
        chartPanel.setPreferredSize(new Dimension(0, 200));
        chartPanel.setMinimumSize(new Dimension(0, 200));
        chartPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        chartPanel.setBorder(createTitledBorder("Win Rate History"));
        chartPanel.setAlignmentX(LEFT_ALIGNMENT);
        advancedBox.add(chartPanel);

        statsBox.add(advancedBox);

        // Additional Stats last: directly under the Advanced Stats button while that is closed
        statsBox.add(vgap(12));
        statsBox.add(extraStats);

        advancedToggle.addActionListener(e -> {
            boolean showing = advancedBox.isVisible();
            advancedToggle.setText(showing ? "Advanced Stats" : "Hide Advanced Stats");
            advancedToggle.setEnabled(false);
            invokeLater(() -> {
                advancedBox.setVisible(!showing);
                statsBox.revalidate();
                statsBox.repaint();
                advancedToggle.setEnabled(true);
            });
        });

        // --- The two alternative views (hidden by default, swapped in by the toggles) ---
        var tierPanel = new TierPanel(pvpApi);
        tierScroll = altPane(tierPanel, "Rank Tiers");
        var topPanel = new TopPlayers(pvpApi);
        topScroll = altPane(topPanel, "Top Players");

        // Player Lookup card holds the stats container plus the rank-tier and
        // top-players scroll panes. Only one is visible at a time; the two
        // community-box toggles flip between them and force the active tab to
        // Player Lookup.
        var lookupCard = new JPanel();
        lookupCard.setLayout(new BoxLayout(lookupCard, Y_AXIS));
        lookupCard.add(statsBox);
        lookupCard.add(tierScroll);
        lookupCard.add(topScroll);

        // The Matchmaking card is the queue view itself. Production wiring uses
        // the injected LobbyService (the real WebSocketLobbyService) + the
        // ConfigManager-backed LobbyPrefs so gate selections persist
        // across plugin restarts.
        lobbyCard = new MatchmakingLobbyPanel(lobbyService, joinGate, lobbyPrefs);
        lobbyCard.setShownHook(this::onMatchShown);
        // Eager game-state signal: flips true the instant GameState.LOGGED_IN
        // fires, well before profileGate.onLogin() (which waits 10 ticks for
        // the local player name to resolve). Drives the gate's three-phase
        // notice — "Please log into the game" → "Loading…" → fully-built gate.
        lobbyCard.setInGame(plugin::isInGame);

        // The Tournaments card while tournaments are off: a one-line hint.
        tourneyStub = new JPanel(new BorderLayout());
        var tphint = new JLabel(OFF_TOOLTIP, SwingConstants.CENTER);
        tphint.setFont(tphint.getFont().deriveFont(Font.ITALIC, 11f));
        tphint.setForeground(new Color(0x999999));
        tourneyStub.add(tphint, BorderLayout.CENTER);

        viewBox.add(lobbyCard, CARD_LOBBY);
        viewBox.add(lookupCard, CARD_LOOKUP);
        viewBox.add(tourneyStub, CARD_TOURNEY);
        mainPanel.add(viewBox);

        // "What are the ranks" / "Top players" behave like top-level tabs: each
        // click reveals its view inside the Player Lookup card AND becomes the
        // single active underline (deactivating the other four nav buttons).
        // There's no "Back to stats" — the user returns to stats by clicking
        // Player Lookup (or any other nav button), exactly like switching tabs.
        tierToggle.addActionListener(e -> showAlt(tierScroll, tierToggle, tierPanel::onShown, mainPanel));
        topToggle.addActionListener(e -> showAlt(topScroll, topToggle, topPanel::onShown, mainPanel));

        // Default tab: Matchmaking Lobby (per design — encourages people to
        // explore the new lobby; stats keep loading in the background so
        // switching to Player Lookup is instant).
        setActiveTab(CARD_LOBBY);

        return mainPanel;
    }

    /** A button for the stats column: as wide as the column, 25 px high,
     *  hidden until a player is searched, followed by a {@code gap}. */
    private JButton colBtn(String text, ActionListener action, int gap)
    {
        JButton b = maxH(new JButton(text), 25);
        b.setVisible(false);
        b.addActionListener(action);
        statsBox.add(b);
        statsBox.add(vgap(gap));
        return b;
    }

    /** One of the Player Lookup card's alternative views, hidden; it never
     *  scrolls sideways: the view tracks the viewport width (Scrollable), so
     *  a right-aligned column always sits flush against the inner edge
     *  instead of being clipped off-screen. */
    private static JScrollPane altPane(JComponent view, String title)
    {
        JScrollPane pane = left(new JScrollPane(view));
        pane.setBorder(createTitledBorder(title));
        pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setVisible(false);
        return pane;
    }

    /** Shows {@code pane} (one of the two alternative views) in the Player
     *  Lookup card in place of the stats and the other view, lights its
     *  {@code nav} button and loads it. */
    private void showAlt(JScrollPane pane, JButton nav, Runnable onShown, JPanel mainPanel)
    {
        showCard(CARD_LOOKUP);
        statsBox.setVisible(false);
        (pane == tierScroll ? topScroll : tierScroll).setVisible(false);
        pane.setVisible(true);
        setActiveNav(nav);
        onShown.run();
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    /** A nav row of {@code cols} equal cells, tall enough for NAV_FONT_PT
     *  (16pt BOLD) without clipping the descenders. */
    private static JPanel navRow(String name, int cols)
    {
        JPanel row = maxH(new JPanel(new GridLayout(1, cols, 4, 0)), 40);
        row.setName(name);
        row.setPreferredSize(new Dimension(220, 40));
        return row;
    }

    /**
     * Two-tab nav row (Matchmaking + Player Lookup). The Tournaments tab has
     * its own row below it ({@link #createTournamentsTabRow()}).
     */
    private JPanel createNav()
    {
        JPanel nav = navRow("top-tab-nav", 2);

        tabLobbyBtn = makeTab("Matchmaking", true);
        tabLobbyBtn.addActionListener(e -> setActiveTab(CARD_LOBBY));
        nav.add(tabLobbyBtn);

        tabLookupBtn = makeTab("Player Lookup", false);
        tabLookupBtn.addActionListener(e -> setActiveTab(CARD_LOOKUP));
        nav.add(tabLookupBtn);

        return nav;
    }

    /**
     * The Tournaments tab, alone on its own row under the two top tabs. It
     * is greyed (with a short "off in plugin settings" flash on click) until
     * {@link #setTournamentService} wires an available service.
     */
    private JPanel createTournamentsTabRow()
    {
        JPanel row = navRow("tournaments-tab-nav", 1);

        tabTourneys = makeTab("Tournaments", false);
        tabTourneys.setName("tournaments-tab");
        tabTourneys.setEnabled(false);
        tabTourneys.setToolTipText(OFF_TOOLTIP);
        onClick(tabTourneys, () -> {
            if (!tabTourneys.isEnabled() && tabTourneys.getText().equals("Tournaments")) {
                tabTourneys.setText("<html><center>Off in plugin<br>settings</center></html>");
                Timer revertTimer = new Timer(15000, ev -> tabTourneys.setText("Tournaments"));
                revertTimer.setRepeats(false);
                revertTimer.start();
            }
        });
        tabTourneys.addActionListener(e -> setActiveTab(CARD_TOURNEY));
        row.add(tabTourneys);
        return row;
    }

    private static JButton makeTab(String label, boolean active)
    {
        // Always BOLD — switching weight per-state changes the text width and
        // clipped longer labels ("Player Lookup" → "Player Loo...") when active.
        // Active state is indicated purely by the bottom underline below.
        JButton b = bold(new JButton(label), NAV_FONT_PT);
        b.setMargin(new Insets(2, 4, 2, 4));
        b.setFocusPainted(false);
        styleTab(b, active);
        return b;
    }

    /** Stable client-property key tests use to assert active-tab state without
     *  depending on visual styling internals. Value is a {@link Boolean}. */
    public static final String TAB_ACTIVE = "pvp.tab.active";

    /** Active-tab indicator: a 3px bottom underline in {@link Ui#ACCENT}. The
     *  inactive state uses the same height border but in the panel background
     *  colour, so flipping active/inactive doesn't shift the layout by a pixel.
     *  Critically, font weight + size never change — that was the source of
     *  the "Player Loo..." clipping bug (BOLD vs PLAIN have different metrics).
     *  Also stamps {@link #TAB_ACTIVE} for test introspection. */
    private static void styleTab(JButton b, boolean active)
    {
        Color underline = active ? ACCENT : DIVIDER;
        b.setBorder(createCompoundBorder(
            createMatteBorder(0, 0, 3, 0, underline),
            createEmptyBorder(2, 4, 0, 4)));
        b.putClientProperty(TAB_ACTIVE, Boolean.valueOf(active));
    }

    /** Single font size shared by every navigation button — the tabs
     *  ([Matchmaking] / [Player Lookup] / [Tournaments]) — and by every
     *  community-box button ([Discord], [Website], [Top players],
     *  [What are the ranks], [Login with Discord]).
     *  Matches {@code MatchmakingLobbyPanel.GATE_PT} so the whole
     *  sidepanel reads as one consistent typographic block.
     *
     *  <p>Was 18pt; trimmed to 16pt because at 18pt "Player Lookup" was
     *  clipping at the right edge of its half-width tab cell on the
     *  default 215px sidepanel. 16pt fits comfortably with ~6px slack. */
    private static final float NAV_FONT_PT = 16f;

    /** Per-row height the community-box buttons are sized to. Tracks
     *  {@link #NAV_FONT_PT} — at 16pt BOLD the button needs ~34px to
     *  show without clipping descenders or wasting whitespace. */
    private static final int COMMUNITY_H = 34;

    /** Switch the visible top-level card only (no nav re-styling). */
    private void showCard(String key)
    {
        activeCard = key;
        viewCards.show(viewBox, key);
        viewBox.revalidate();
        syncTourneys();
    }

    /** The Show streaks switch: the rows, the button's words and the saved choice. */
    private void setStreaksShown(boolean shown)
    {
        lobbyPrefs.setShowStreaks(shown);
        applyStreaks(shown);
    }

    private void applyStreaks(boolean shown)
    {
        rankProgress.setStreaksShown(shown);
        streaksBtn.setText(shown ? "Hide streaks" : "Show streaks");
    }

    /** Tells the live Tournaments panel when it comes on screen (it re-syncs
     *  and subscribes to the standings) and when it leaves (it unsubscribes). */
    private void syncTourneys()
    {
        boolean showing = tourneyPanel != null && CARD_TOURNEY.equals(activeCard);
        if (tourneyPanel == null || showing == tourneyShown) return;
        tourneyShown = showing;
        tourneyPanel.setShowing(showing);
    }

    /** Single source of truth for the green active underline: stamps exactly
     *  one of the five nav buttons (Matchmaking, Player Lookup, Tournaments,
     *  Top players, What are the ranks) active and the rest inactive — so the
     *  accent always tracks whatever the user clicked last. */
    private void setActiveNav(JButton active)
    {
        styleTab(tabLobbyBtn, active == tabLobbyBtn);
        styleTab(tabLookupBtn, active == tabLookupBtn);
        styleTab(tabTourneys, active == tabTourneys);
        styleTab(tierToggle, active == tierToggle);
        styleTab(topToggle, active == topToggle);
    }

    /** Switch the visible top-level card and move the active underline to the
     *  matching tab (Matchmaking, Player Lookup or Tournaments). First folds
     *  the Player Lookup card back to its stats view, so the lookup card is
     *  always on stats unless the user explicitly clicked one of the
     *  alternative views' nav buttons. */
    private void setActiveTab(String key)
    {
        tierScroll.setVisible(false);
        topScroll.setVisible(false);
        statsBox.setVisible(true);
        showCard(key);
        JButton tab = CARD_LOBBY.equals(key) ? tabLobbyBtn
            : CARD_TOURNEY.equals(key) ? tabTourneys
            : tabLookupBtn;
        setActiveNav(tab);
    }

    // -------------------- Plan 10 step 7: queue + tournaments wiring --------------------

    /** Plan 10 F.1: hands the queue transport to the lobby gate (the plugin
     *  passes the inert service while {@code enableQuickMatch} is off). */
    public void setQueue(QueueService svc)
    {
        lobbyCard.setQueue(svc);
    }

    /** A service replaces the greyed placeholder with the live
     *  {@link TourneyPanel} and enables the Tournaments tab; {@code null}
     *  ({@code enableTournaments} off) restores the greyed placeholder.
     *  Re-entrant: a previous live panel is shut down first. */
    public void setTournamentService(TourneySvc svc)
    {
        dropHook();
        if (tourneyPanel != null)
        {
            tourneyPanel.shutdown();
            viewBox.remove(tourneyPanel);
            tourneyPanel = null;
            tourneyShown = false;
        }
        boolean on = svc != null;
        if (on)
        {
            tourneyPanel = new TourneyPanel(svc, () -> lobbyPrefs.getRegion("na-e"));
            tourneyPanel.setSelf(plugin::getLocalName);
            tourneyPanel.setOnOpenProfile(this::openLookup);
            tourneyPanel.setRankLookup(pvpApi::getCardTier);
            tourneyPanel.setHistoryLoader(pvpApi::getTournamentHistory);
            tourneyPanel.setStandingsLoader(pvpApi::getTournamentStandings);
            tourneyPanel.setGameCheck(plugin::isInGame);
            if (combatCheck != null) tourneyPanel.setInCombatProvider(combatCheck);
            // Set 6: the Report gate is the same Discord login state onLoginState() reloads on.
            tourneyPanel.setDiscordLoginProvider(discordLogin::isLoggedIn);
            tourneyPanel.setMatchCountProvider(this::ownCount);
            countsHook = tourneyPanel::onCounts;
            joinGate.addListener(countsHook);
            if (tourneyGear != null) tourneyPanel.setGearCard(tourneyGear);
            viewBox.remove(tourneyStub);
            viewBox.add(tourneyPanel, CARD_TOURNEY);
            tabTourneys.setText("Tournaments");
        }
        else if (tourneyStub.getParent() != viewBox)
        {
            viewBox.add(tourneyStub, CARD_TOURNEY);
        }
        tabTourneys.setToolTipText(on ? null : OFF_TOOLTIP);
        tabTourneys.setEnabled(on);
        // Never leave the user on a card that just went away; otherwise re-show
        // the current card: the swap can change the visible card, and a re-wire
        // while the tab is open shows + syncs the new panel.
        if (!on && CARD_TOURNEY.equals(activeCard)) setActiveTab(CARD_LOBBY);
        else showCard(activeCard);
        viewBox.repaint();
    }

    /** Plan 10 F.3: {@code FightMonitor::isInCombat} — drives the automatic
     *  {@code tournament/in_combat} signal. Kept so a panel built later
     *  (config flip) gets it too. */
    public void setTournamentInCombatProvider(BooleanSupplier provider)
    {
        combatCheck = provider;
        if (tourneyPanel != null) tourneyPanel.setInCombatProvider(provider);
    }

    /** Plugin shutdown: stops the live panel's ticker + unsubscribes. */
    public void stopTourneys()
    {
        dropHook();
        if (tourneyPanel != null) tourneyPanel.shutdown();
    }

    /** The Tournaments tab's view of the profile gate's counts: the count in
     *  the event's bucket, {@code null} when unknown. */
    private Integer ownCount(String bucket)
    {
        Style style = bucketStyle(bucket);
        if (style == null) return null;
        return joinGate.getMatchCounts().get(style);
    }

    /** {@code "nh"} → NH, {@code "veng"} → Veng, {@code "multi"} → Multi, {@code "dmm"} → DMM (any case,
     *  trimmed); anything else {@code null}. */
    static Style bucketStyle(String bucket)
    {
        try
        {
            return Style.valueOf(bucket.trim().toUpperCase(Locale.ROOT));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private void dropHook()
    {
        if (countsHook == null) return;
        joinGate.removeListener(countsHook);
        countsHook = null;
    }

    /** Plugin shutdown: shuts down the matchmaking panel and the
     *  Tournaments tab. */
    public void shutdown()
    {
        lobbyCard.shutdown();
        stopTourneys();
    }

    private JComponent tourneyGear;

    public void setTournamentGearCard(JComponent card)
    {
        tourneyGear = card;
        if (tourneyPanel != null) tourneyPanel.setGearCard(card);
    }

    public void showTourneys()
    {
        if (tourneyPanel != null) setActiveTab(CARD_TOURNEY);
    }

    /** Replaces the on-screen check made before a queue match shows the
     *  Matchmaking tab; {@code null} reads as not on screen. */
    void setPanelOpen(BooleanSupplier provider)
    {
        panelOpen = provider == null ? () -> false : provider;
    }

    /** A queue match's Confirm card is up: shows the Matchmaking tab when this
     *  panel is on screen and another tab is showing. Never opens the panel. */
    private void onMatchShown()
    {
        if (!CARD_LOBBY.equals(activeCard) && GearKit.safe(panelOpen)) setActiveTab(CARD_LOBBY);
    }

    // --- UI Creation Helpers ---

    private JPanel createLinks()
    {
        var box = new JPanel();
        box.setLayout(new BoxLayout(box, Y_AXIS));
        // TitledBorder font matches NAV_FONT_PT (BOLD) so the "Join the
        // community" header reads at the same weight as the gate's
        // "Set up matchmaking" header and the nav buttons below it.
        TitledBorder titled = createTitledBorder("Join the community");
        titled.setTitleFont(box.getFont().deriveFont(Font.BOLD, NAV_FONT_PT));
        box.setBorder(titled);
        // Sized for: title (~28px) + 4 button rows × COMMUNITY_H + small
        // inter-row gaps + bottom slack.
        var size = new Dimension(220, 28 + 4 * COMMUNITY_H + 12);
        box.setMaximumSize(size);
        box.setPreferredSize(size);

        // Discord + Website share a row via GridLayout(1,2) so each button
        // gets exactly half the panel width. The row is aligned left like the
        // buttons below it, which BoxLayout lines up only when every child
        // shares one alignment.
        JPanel row = left(maxH(new JPanel(new GridLayout(1, 2, 4, 0)), COMMUNITY_H));
        row.add(communityBtn("Discord", "Join our Discord", "https://discord.gg/TmFzcbW3Rp"));
        row.add(communityBtn("Website", "Open the website", PvpConsts.SITE_URL));
        box.add(row);

        // Then Top players, What are the ranks and Login with Discord, one per
        // row (reachable from any tab).
        topToggle.setToolTipText("Show the Top 100 players for the selected bucket");
        tierToggle.setToolTipText("Show every rank tier from Bronze 3 to 3rd Age");
        for (JButton b : new JButton[]{topToggle, tierToggle, loginPanel.getLoginButton()})
        {
            box.add(maxH(bold(b, NAV_FONT_PT), COMMUNITY_H));
        }
        return box;
    }

    /** Community-box link button — uniform NAV_FONT_PT BOLD font (the parent
     *  {@link GridLayout} controls width), focus painting off to match the
     *  nav buttons. Opens {@code url} (an https constant). */
    private static JButton communityBtn(String label, String tooltip, String url)
    {
        JButton b = bold(new JButton(label), NAV_FONT_PT);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setFocusPainted(false);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> LinkBrowser.browse(url));
        return b;
    }

    // --- Actions ---

    private void handleRefresh()
    {
        String id = lookupId != null && !lookupId.isEmpty()
            ? lookupId : plugin.getLocalName();
        if (id != null) loadMatches(id);
    }

    /** After a login: reload the searched player, else yourself. After a
     *  logout: reload whoever is shown. Site auth gates the Additional Stats
     *  box; set 6: the Tournaments cards' Report buttons follow the same login. */
    private void onLoginState()
    {
        boolean loggedIn = discordLogin.isLoggedIn();
        extraStats.setVisible(loggedIn);
        if (tourneyPanel != null) tourneyPanel.onDiscordLoginChanged();
        String search = loginPanel.getPluginSearchText();
        String id = !loggedIn ? lookupId : !search.isEmpty() ? search : plugin.getLocalName();
        if (id != null) loadMatches(id);
    }

    /**
     * Toggles the blocked state for the currently-displayed player.
     * Updates the button label so the user gets immediate confirmation
     * of the new state. The in-memory {@link BlockList}
     * registry is consulted by the lobby roster filter.
     */
    private void handleBlockPlayerToggle()
    {
        // Read the id once so a concurrent loadMatches mid-click
        // can't wire the wrong wire frame.
        String playerId = lookupId;
        if (playerId == null || playerId.isEmpty()) return;
        boolean nowBlocked = BlockList.toggle(playerId);
        // Wire the toggle to the lobby/server (the server records the block
        // and drops invites between the two players), then update the
        // matchmaking panel at once: the server's lobby/block_added echo lands
        // 100–500 ms later and is a no-op then (onBlockAdded short-circuits
        // when the id is already in the set).
        if (nowBlocked)
        {
            lobbyService.blockById(playerId);
            lobbyCard.onBlockAdded(playerId);
        }
        else
        {
            lobbyService.unblockById(playerId);
            lobbyCard.onBlockRemoved(playerId);
        }
        refreshBlock(nowBlocked);
    }

    /** Updates the Block / Unblock button text + tooltip based on the
     *  {@code blocked} state. Visibility is managed separately by
     *  {@link #updateBlock(String)}. The Block state gets a
     *  red text + red outline treatment to mark it as a destructive action;
     *  Unblock reverts to default styling (the L&F's) because allowing
     *  invites again is not destructive. */
    private void refreshBlock(boolean blocked)
    {
        Color danger = new Color(0xd04545);
        blockBtn.setText(blocked ? "Unblock Player" : "Block Player");
        blockBtn.setToolTipText(blocked ? "Allow this player to send you matchmaking invites again"
            : "Block this player from sending you matchmaking invites");
        blockBtn.setForeground(blocked ? null : danger);
        blockBtn.setBorder(blocked ? UIManager.getBorder("Button.border")
            : createCompoundBorder(createLineBorder(danger, 1), pad(2, 6, 2, 6)));
    }

    /** Shows the Block / Unblock toggle for a non-self lookup ({@code id} is
     *  normalised), hides it otherwise (you can't block yourself). Called
     *  from {@link #loadMatches(String)}'s UI reset. */
    private void updateBlock(String id)
    {
        boolean show = id != null && !id.isEmpty() && !isSelf(id);
        blockBtn.setVisible(show);
        if (show)
        {
            refreshBlock(BlockList.isBlocked(id));
        }
    }

    // --- Data Loading ---

    /** Forwards a {@code GameStateChanged} signal from the plugin to
     *  the matchmaking panel so the lobby-gate notice can flip
     *  between "Please log into the game" and "Loading…" without
     *  waiting for the 10-tick {@link JoinGate#onLogin()}
     *  delay. */
    public void syncLogins()
    {
        lobbyCard.refreshGate();
        if (tourneyPanel != null)
        {
            tourneyPanel.refreshLogin();
        }
    }

    /** Forwards the match-found popup notifier from the plugin lifecycle
     *  layer to the matchmaking panel. */
    public void setMatchFoundNotifier(MatchmakingLobbyPanel.MatchAlert notifier)
    {
        lobbyCard.setMatchFoundNotifier(notifier);
    }

    /**
     * Loads match history only if NO player is currently being viewed.
     * This prevents game events from overwriting a user's active search.
     * Called on login/world hop - will not switch away from user's search.
     */
    public void loadIfIdle(String playerId)
    {
        // If user is already viewing someone (searched for a player), don't overwrite
        if (lookupId != null && !lookupId.isEmpty()) {
            return;
        }

        // No one currently viewed - load the requested player (usually self)
        loadMatches(playerId);
    }

    /**
     * Public entry point for the right-click "PvP lookup" menu (and any future
     * caller that wants to surface a specific player). Forces the Player Lookup
     * tab to the foreground BEFORE kicking off the match-history load so the
     * user actually sees the data they asked for.
     *
     * <p>Kept separate from {@link #loadMatches(String)} on purpose:
     * loadMatches is also invoked by auto-paths (login, game events,
     * refresh) where forcibly switching tabs would yank focus away from the
     * Matchmaking Lobby unexpectedly. Right-click menu = explicit intent =
     * tab switch is desired; everything else = silent background load.
     */
    public void openLookup(String playerId)
    {
        Runnable open = () ->
        {
            setActiveTab(CARD_LOOKUP);
            loadMatches(playerId);
        };
        if (isEventDispatchThread())
        {
            open.run();
        }
        else
        {
            invokeLater(open);
        }
    }

    /**
     * Forces a full refresh of match history. Called from explicit user actions (search, refresh button).
     */
    public void loadMatches(String playerId)
    {
        final int gen = ++loadGen;
        lookupId = normalizeId(playerId);

        Runnable uiReset = () -> {
            playerLabel.setText(playerId);
            loginPanel.setPluginSearchText(playerId);
            refreshButton.setVisible(true);
            streaksBtn.setVisible(true);
            historyBtn.setVisible(false);
            advancedToggle.setVisible(false);
            extraStats.setPlayerName(lookupId);
            updateBlock(lookupId);
            resetUi();
        };
        if (isEventDispatchThread()) {
            uiReset.run();
        } else {
            invokeLater(uiReset);
        }

        try {
            String normalizedId = lookupId;
            loadStats(normalizedId, gen);

            // Your own history is read by account (the hash of the stored
            // client id) when there is one, everyone else's by name.
            boolean isSelf = isSelf(normalizedId);
            String sha = isSelf ? pvpApi.getSelfSha() : null;
            extraStats.setAcctSha(sha);
            pvpApi.getMatches(sha, normalizedId, null, PAGE_SIZE, false).thenAccept(jsonResponse -> {
                if (gen != loadGen || jsonResponse == null)
                {
                    return;
                }

                JsonArray matches = optArray(jsonResponse, "matches");

                invokeLater(() -> {
                    if (gen != loadGen) return;
                    if (isSelf) seedStreaks(matches);
                    updateUi(matches);
                });
            });
        } catch (Exception ex) {
            // ignored
        }
    }

    private void updateUi(JsonArray matches) {
        chartPanel.setMatches(matches);
        extraStats.setMatches(matches);
        allMatches = matches;

        boolean hasData = matches != null && matches.size() > 0;
        historyBtn.setVisible(hasData);
        advancedToggle.setVisible(hasData);

        refreshBars();
    }

    private void resetUi()
    {
        rankProgress.reset();
        perfStats.reset();
        chartPanel.setMatches(new JsonArray());
        extraStats.setMatches(new JsonArray());
        allMatches = null;
    }

    private void loadStats(String playerId, int gen)
    {
        try {
            pvpApi.getProfile(playerId, false).thenAccept(stats -> {
                if (gen != loadGen || stats == null)
                {
                    return;
                }
                invokeLater(() -> {
                    if (gen != loadGen) return;
                    updateBars(stats, gen);
                    if (isShowingSelf()) syncStreaks(playerId);
                });
            });
        } catch (Exception ex) {
            // ignored
        }
    }

    @Setter private volatile WinStreakTracker winStreakTracker;

    private void seedStreaks(JsonArray matches)
    {
        WinStreakTracker tracker = winStreakTracker;
        if (tracker == null) return;
        tracker.seedHistory(matches);
        refreshLines();
    }

    private void syncStreaks(String playerId)
    {
        WinStreakTracker tracker = winStreakTracker;
        if (tracker == null) return;
        tracker.observeProfile(pvpApi.peekProfile(playerId));
        refreshLines();
    }

    private void refreshLines()
    {
        for (StreakBucket b : StreakBucket.values()) refreshLine(b.bucketKey);
    }

    /** The local player's own profile was refreshed after a fight: redraws the rating rows from the cached profile while it is shown. */
    public void refreshOwn(String playerName)
    {
        String normalized = normalizeId(playerName);
        if (normalized == null || normalized.isEmpty()) return;
        invokeLater(() ->
        {
            if (!isShowingSelf() || !normalized.equalsIgnoreCase(lookupId)) return;
            UserStats cached = pvpApi.peekProfile(playerName);
            JsonObject stats = cached == null ? null : cached.getStats();
            if (stats == null) return;
            String name = optString(stats, "player_name", playerName);
            applyAll(stats, name, loadGen);
        });
    }

    public void refreshLine(String bucketKey)
    {
        WinStreakTracker tracker = winStreakTracker;
        StreakBucket bucket = StreakBucket.forKey(bucketKey);
        if (tracker == null || bucket == null || !isShowingSelf() || !tracker.isKnown()) return;
        String key = bucket.bucketKey;
        rankProgress.updateStreak(key, tracker.current(key), tracker.plus(key), tracker.longest(key));
    }

    /** Whether the normalised {@code id} is the logged-in player's own name. */
    private boolean isSelf(String id)
    {
        String self = normalizeId(plugin.getLocalName());
        return self != null && !self.isEmpty() && self.equalsIgnoreCase(id);
    }

    private boolean isShowingSelf()
    {
        return isSelf(lookupId);
    }

    private void updateBars(JsonObject stats, int gen)
    {
        String playerName = optString(stats, "player_name", optString(stats, "player_id", null));
        if (playerName != null)
        {
            applyAll(stats, playerName, gen);
        }

        JsonObject cumulative = optObject(stats, "cumulative_stats");
        if (cumulative != null) perfStats.setTotals(cumulative);
        JsonObject opponents = optObject(stats, "opponent_rank_stats_by_bucket");
        if (opponents != null) perfStats.setOppRanks(opponents);
    }

    /** Every rating row in screen order: Overall reads the profile's root,
     *  each other bucket its object under {@code buckets}. Plan 10 F.5: the
     *  tournament bucket is null until the player's first recognised
     *  tournament game (AS-97) — optObject keeps that JsonNull member from
     *  throwing and the row stays hidden ("—"). */
    private void applyAll(JsonObject stats, String playerName, int gen)
    {
        for (String bucketKey : RankProgress.BUCKET_KEYS)
        {
            applyBucket(playerName, bucketKey, "overall".equals(bucketKey) ? stats
                : optObject(optObject(stats, "buckets"), bucketKey), gen);
        }
    }

    private void applyBucket(String playerName, String bucketKey, JsonObject bucketObj, int gen)
    {
        if (bucketObj == null) {
            // Unplayed bucket: no rank, no streak line (hides the tournament row).
            rankProgress.updateBucket(bucketKey, "—", 0, 0, -1, null, 0, 0);
            return;
        }

        String rankLabel = "—";
        int division = 0;
        double pct = 0.0;
        double mmr = Double.NaN;

        if (bucketObj.has("mmr")) {
            mmr = bucketObj.get("mmr").getAsDouble();
            RankInfo ri = RankUtils.toRankInfo(mmr);
            rankLabel = ri.rank;
            division = ri.division;
            pct = ri.progress;
        }
        pct = optDouble(optObject(bucketObj, "rank_progress"), "progress_to_next_rank_pct", pct);
        String r = optString(bucketObj, "rank");
        if (!r.isEmpty()) rankLabel = r;
        division = optInt(bucketObj, "division", division);

        // Plan 10 / BOARD row 33: the streak line under the bar. The top-level
        // (overall) object spells it current_streak; bucket objects spell it
        // streak. The later rank-number / Top % refresh uses the 6-arg form so
        // it leaves the line alone.
        int streak = optInt(bucketObj, "overall".equals(bucketKey) ? "current_streak" : "streak", 0);
        int bestStreak = optInt(bucketObj, "best_streak", 0);
        rankProgress.updateBucket(bucketKey, rankLabel, division, pct, -1, null, streak, bestStreak);

        if (!"—".equals(rankLabel)) refreshRankLine(playerName, bucketKey, rankLabel, division, pct, mmr, gen);
    }

    /** Adds the rank number and the "Top X.XX%" to a bucket's rank line: both
     *  computed on a background thread so the row updates once with both, and
     *  dropped when another lookup has started meanwhile. */
    private void refreshRankLine(String playerName, String bucket, String rank, int division, double pct, double mmr, int gen)
    {
        new SwingWorker<Integer, Void>()
        {
            String top;

            @Override protected Integer doInBackground() {
                top = computeTop(bucket, mmr);
                return getBoardRank(playerName, bucket);
            }

            @Override protected void done() {
                try {
                    if (gen == loadGen) rankProgress.updateBucket(bucket, rank, division, pct, get(), top);
                } catch (Exception ignore) {
                }
            }
        }.execute();
    }

    private void refreshBars() {
        if (allMatches == null || allMatches.size() == 0) return;
        String playerName = playerLabel.getText();
        if ("Player Name".equals(playerName)) return;

        final int gen = loadGen;
        for (String bucket : RankProgress.BUCKET_KEYS)
        {
            // The bucket's latest match: the first one, replaced only by a strictly later "when".
            JsonObject latest = null;
            for (JsonElement e : allMatches)
            {
                JsonObject match = e.getAsJsonObject();
                if (bucket.equals(optString(match, "bucket").toLowerCase())
                    && (latest == null || (match.has("when") && latest.has("when")
                        && match.get("when").getAsLong() > latest.get("when").getAsLong())))
                {
                    latest = match;
                }
            }

            if (latest != null && latest.has("player_mmr"))
            {
                double mmr = latest.get("player_mmr").getAsDouble();
                RankInfo est = RankUtils.toRankInfo(mmr);
                refreshRankLine(playerName, bucket,
                    optString(latest, "player_rank", est.rank),
                    optInt(latest, "player_division", est.division),
                    est.progress, mmr, gen);
            }
        }
    }

    // --- Helpers ---

    /**
     * The player's "Top X.XX%" for a bucket, derived from the cached rank
     * histogram ({@code rank_hist/<bucket>.json}). Runs on a background
     * thread (it blocks briefly on the cached future), so it must NOT be
     * called from the EDT. Reuses {@link PvpApi#getHistogram}
     * which is backed by the shared 60-minute shard cache — so the histogram
     * for each bucket is fetched at most once per hour and shared with the
     * "What are the ranks" view, never re-fetched per lookup. Returns
     * {@code null} when it can't be determined (a missing histogram counts
     * nobody) so the caller omits the suffix.
     */
    private String computeTop(String bucket, double mmr)
    {
        if (!Double.isFinite(mmr)) return null;
        try {
            JsonObject hist = pvpApi.getHistogram(bucket).get(5, TimeUnit.SECONDS);
            return RankUtils.formatTopPercentPrecise(RankUtils.totalAbove(hist, mmr), RankUtils.histTotal(hist));
        } catch (Exception ignore) {
            return null;
        }
    }

    /** {@code bucket} is one of {@link RankProgress#BUCKET_KEYS}. */
    public int getBoardRank(String playerName, String bucket)
    {
        try {
            // bypassCache=true: this method runs on a SwingWorker
            // background thread spawned from openLookup —
            // i.e. a user gesture. The 2026-05-24 backend handoff
            // moved shard writes to a DynamoDB-stream-driven path
            // with ≈30 s propagation, so an explicit "look this
            // player up" should pick up post-match rank changes
            // instead of serving the cached payload (which could
            // be up to 60 min stale).
            ShardRank sr = pvpApi.getShardRank(playerName, bucket, true)
                .get(5, TimeUnit.SECONDS);
            return sr != null && sr.rank > 0 ? sr.rank : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private static String normalizeId(String name) {
        return name != null ? name.trim().replaceAll("\\s+", " ").toLowerCase() : null;
    }

    /** Nested scroll panes hand the mouse wheel on: each scrolls itself while
     *  it can move that way and passes the wheel to its parent at its top
     *  (turning up) or bottom (turning down), or always without a visible
     *  bar. Covers every pane in {@code c} now and any added later. */
    private static void wire(Component c)
    {
        if (c instanceof JScrollPane)
        {
            var sp = (JScrollPane) c;
            sp.setWheelScrollingEnabled(false);
            sp.addMouseWheelListener(e -> {
                JScrollBar vbar = sp.getVerticalScrollBar();
                int turn = e.getWheelRotation();
                if (!vbar.isVisible() || turn < 0 && vbar.getValue() <= vbar.getMinimum()
                    || turn > 0 && vbar.getValue() >= vbar.getMaximum() - vbar.getVisibleAmount())
                {
                    sp.getParent().dispatchEvent(convertMouseEvent(sp, e, sp.getParent()));
                }
                else
                {
                    vbar.setValue(vbar.getValue() + e.getUnitsToScroll() * vbar.getUnitIncrement());
                }
            });
        }
        if (c instanceof Container)
        {
            var container = (Container) c;
            for (Component child : container.getComponents())
            {
                wire(child);
            }
            container.addContainerListener(new ContainerAdapter()
            {
                @Override
                public void componentAdded(ContainerEvent e)
                {
                    wire(e.getChild());
                }
            });
        }
    }
}
