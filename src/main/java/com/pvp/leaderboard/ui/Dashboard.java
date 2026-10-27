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

    // Sub-panels
    private LoginPanel loginPanel;
    private RankProgress rankProgress;
    private PerfStats perfStats;
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

    // Top-level navigation: Discord | FAQ; with FAQ open Top Players | Ranks,
    // Website and Matchmaking, else Performance; then Events | Player Lookup.
    // Each tab shows one card.
    private static final String CARD_LOBBY = "matchmaking";
    private static final String CARD_LOOKUP = "lookup";
    private static final String CARD_TOURNEY = "tournaments";
    private static final String CARD_PERF = "performance";
    private static final Color BLURPLE = new Color(88, 101, 242);
    private JButton tabLobbyBtn;
    private JButton tabLookupBtn;
    private JButton tabTourneys;
    private JButton tabPerf;
    private JButton faqBtn;
    private JPanel faqBox;
    private CardLayout viewCards;
    private JPanel viewBox;
    /** The card {@link #showCard} showed last. */
    private String activeCard = CARD_LOOKUP;
    /** Whether this panel is on screen (the sidebar open with this panel's
     *  tab selected); replaceable in tests. */
    private BooleanSupplier panelOpen = this::isShowing;
    /** Between RuneLite's {@link #onActivate} and {@link #onDeactivate}. */
    private boolean shown;

    static final String OFF_TOOLTIP = "Events are turned off in the plugin settings";
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

    /** Match-count gate driving {@link MatchmakingLobbyPanel}'s per-style
     *  lock state ({@code ProfileGate} in production, auto
     *  refreshed every hour via {@link PvpApi}). */
    private final JoinGate joinGate;

    /** Persistence for the gate's selections (region / rank-slider bounds /
     *  the Show streaks choice): the Guice-provided ConfigManager-backed
     *  {@link LobbyPrefs} in production, the in-memory variant in tests. */
    private final LobbyPrefs lobbyPrefs;

    public Dashboard(PvPLeaderboardPlugin plugin, PvpApi pvpApi,
                          LobbyService lobbyService,
                          JoinGate joinGate,
                          LobbyPrefs lobbyPrefs)
    {
        this.plugin = plugin;
        this.pvpApi = pvpApi;
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

        // The two alternative-view tabs are built up front so the FAQ rows can
        // own them (their listeners are wired below, once their views exist).
        tierToggle = makeTab("Ranks", false);
        topToggle = makeTab("Top Players", false);
        loginPanel = left(new LoginPanel(this::loadMatches));

        // 1. Navigation, always on top: Discord | FAQ; the FAQ rows (Top
        // Players | Ranks, Website, Matchmaking) or Performance; Events |
        // Player Lookup.
        mainPanel.add(left(createLinks()));
        mainPanel.add(left(createFaq()));
        mainPanel.add(left(createPerfRow()));
        mainPanel.add(vgap(4));
        mainPanel.add(left(createNav()));
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

        // The search box.
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
        // top-players scroll panes. Only one is visible at a time; the Top
        // Players and Ranks tabs flip between them.
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

        perfView.setName("performance-view");

        viewBox.add(lobbyCard, CARD_LOBBY);
        viewBox.add(lookupCard, CARD_LOOKUP);
        viewBox.add(tourneyStub, CARD_TOURNEY);
        viewBox.add(perfView, CARD_PERF);
        mainPanel.add(viewBox);

        // Ranks / Top Players behave like top-level tabs: each click reveals
        // its view inside the Player Lookup card AND becomes the single active
        // underline. The user returns to stats by clicking Player Lookup (or
        // any other nav button), exactly like switching tabs.
        tierToggle.addActionListener(e -> showAlt(tierScroll, tierToggle, tierPanel::onShown, mainPanel));
        topToggle.addActionListener(e -> showAlt(topScroll, topToggle, topPanel::onShown, mainPanel));

        // Default tab: Player Lookup.
        setActiveTab(CARD_LOOKUP);

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
     * The Events | Player Lookup row. Events (the tournaments) is greyed, with
     * a short "off in settings" flash on click, until
     * {@link #setTournamentService} wires an available service.
     */
    private JPanel createNav()
    {
        JPanel nav = navRow("nav-tabs", 2);

        tabTourneys = makeTab("Events", false);
        tabTourneys.setName("tournaments-tab");
        tabTourneys.setEnabled(false);
        tabTourneys.setToolTipText(OFF_TOOLTIP);
        onClick(tabTourneys, () -> {
            if (!tabTourneys.isEnabled() && tabTourneys.getText().equals("Events")) {
                tabTourneys.setText("<html><center>Off in<br>settings</center></html>");
                Timer revertTimer = new Timer(15000, ev -> tabTourneys.setText("Events"));
                revertTimer.setRepeats(false);
                revertTimer.start();
            }
        });
        tabTourneys.addActionListener(e -> setActiveTab(CARD_TOURNEY));
        nav.add(tabTourneys);

        tabLookupBtn = makeTab("Player Lookup", false);
        tabLookupBtn.addActionListener(e -> setActiveTab(CARD_LOOKUP));
        nav.add(tabLookupBtn);

        return nav;
    }

    /** The rows FAQ shows: Top Players | Ranks, Website, Matchmaking. Hidden until FAQ is pressed. */
    private JPanel createFaq()
    {
        faqBox = new JPanel();
        faqBox.setLayout(new BoxLayout(faqBox, Y_AXIS));
        faqBox.setName("nav-faq");
        JPanel ranks = navRow("nav-ranks", 2);
        topToggle.setToolTipText("Show the Top 100 players for the selected bucket");
        tierToggle.setToolTipText("Show every rank tier from Bronze 3 to 3rd Age");
        ranks.add(topToggle);
        ranks.add(tierToggle);
        JPanel site = navRow("nav-website", 1);
        site.add(linkBtn("Website", "Open the website", PvpConsts.SITE_URL));
        JPanel queue = navRow("nav-matchmaking", 1);
        tabLobbyBtn = makeTab("Matchmaking", false);
        tabLobbyBtn.addActionListener(e -> setActiveTab(CARD_LOBBY));
        queue.add(tabLobbyBtn);
        for (JPanel row : new JPanel[]{ranks, site, queue})
        {
            faqBox.add(vgap(4));
            faqBox.add(left(row));
        }
        faqBox.setVisible(false);
        return faqBox;
    }

    /** The Performance row, under FAQ's rows when they show. */
    private JPanel createPerfRow()
    {
        var perfBox = new JPanel();
        perfBox.setLayout(new BoxLayout(perfBox, Y_AXIS));
        perfBox.setName("nav-performance");
        JPanel row = navRow("nav-performance-row", 1);
        tabPerf = makeTab("Performance", false);
        tabPerf.addActionListener(e -> setActiveTab(CARD_PERF));
        row.add(tabPerf);
        perfBox.add(vgap(4));
        perfBox.add(left(row));
        return perfBox;
    }

    /** FAQ open: its rows show; closed: they hide. A view whose tab closing hides (Matchmaking, Top Players, Ranks)
     *  gives way to Player Lookup. */
    private void setFaq(boolean open)
    {
        faqBox.setVisible(open);
        faqBtn.setText(open ? "Hide FAQ" : "FAQ");
        if (!open && faqViewShowing()) setActiveTab(CARD_LOOKUP);
        revalidate();
        repaint();
    }

    /** Matchmaking, Top Players or Ranks is the view on screen. */
    private boolean faqViewShowing()
    {
        return CARD_LOBBY.equals(activeCard)
            || (CARD_LOOKUP.equals(activeCard) && (topScroll.isVisible() || tierScroll.isVisible()));
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
     *  Critically, font weight + size never change (BOLD vs PLAIN have
     *  different metrics).
     *  Also stamps {@link #TAB_ACTIVE} for test introspection. */
    private static void styleTab(JButton b, boolean active)
    {
        Color underline = active ? ACCENT : DIVIDER;
        b.setBorder(createCompoundBorder(
            createMatteBorder(0, 0, 3, 0, underline),
            createEmptyBorder(2, 4, 0, 4)));
        b.putClientProperty(TAB_ACTIVE, Boolean.valueOf(active));
    }

    /** Single font size shared by every navigation button: the tabs and the
     *  Discord, FAQ and Website buttons. Matches
     *  {@code MatchmakingLobbyPanel.GATE_PT} so the whole sidepanel reads as
     *  one consistent typographic block.
     *
     *  <p>16pt fits "Player Lookup" in its half-width tab cell on the
     *  default 215px sidepanel with ~6px slack. */
    private static final float NAV_FONT_PT = 16f;

    /** Switch the visible top-level card only (no nav re-styling). */
    private void showCard(String key)
    {
        activeCard = key;
        viewCards.show(viewBox, key);
        viewBox.revalidate();
        syncTourneys();
        syncRecent();
    }

    @Override
    public void onActivate()
    {
        super.onActivate();
        shown = true;
        syncRecent();
    }

    @Override
    public void onDeactivate()
    {
        super.onDeactivate();
        shown = false;
        syncRecent();
    }

    /** The Matchmaking tab is open while this panel is shown with that tab selected. */
    private void syncRecent()
    {
        lobbyCard.watchRecent(shown && CARD_LOBBY.equals(activeCard));
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
     *  one of the six nav tabs (Performance, Events, Player Lookup,
     *  Matchmaking, Top Players, Ranks) active and the rest inactive — so the
     *  accent always tracks whatever the user clicked last. */
    private void setActiveNav(JButton active)
    {
        for (JButton b : new JButton[]{tabLobbyBtn, tabLookupBtn, tabTourneys, tabPerf, tierToggle, topToggle})
        {
            styleTab(b, active == b);
        }
    }

    /** Switch the visible top-level card and move the active underline to the
     *  matching tab. First folds the Player Lookup card back to its stats
     *  view, so the lookup card is always on stats unless the user explicitly
     *  clicked one of the alternative views' nav buttons. */
    private void setActiveTab(String key)
    {
        tierScroll.setVisible(false);
        topScroll.setVisible(false);
        statsBox.setVisible(true);
        showCard(key);
        JButton tab = CARD_LOBBY.equals(key) ? tabLobbyBtn
            : CARD_TOURNEY.equals(key) ? tabTourneys
            : CARD_PERF.equals(key) ? tabPerf
            : tabLookupBtn;
        setActiveNav(tab);
    }

    // -------------------- Queue + tournaments wiring --------------------

    /** Hands the queue transport to the lobby gate. */
    public void setQueue(QueueService svc)
    {
        lobbyCard.setQueue(svc);
    }

    /** A service replaces the greyed placeholder with the live
     *  {@link TourneyPanel} and enables the Tournaments tab; {@code null}
     *  restores the greyed placeholder.
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
            tourneyPanel.setWorldCheck(plugin::loggedInWorld);
            if (combatCheck != null) tourneyPanel.setInCombatProvider(combatCheck);
            tourneyPanel.setMatchCountProvider(this::ownCount);
            countsHook = tourneyPanel::onCounts;
            joinGate.addListener(countsHook);
            if (tourneyGear != null) tourneyPanel.setGearCard(tourneyGear);
            viewBox.remove(tourneyStub);
            viewBox.add(tourneyPanel, CARD_TOURNEY);
            tabTourneys.setText("Events");
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
        if (!on && CARD_TOURNEY.equals(activeCard)) setActiveTab(CARD_LOOKUP);
        else showCard(activeCard);
        viewBox.repaint();
    }

    /** {@code FightMonitor::isInCombat} — drives the automatic
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

    /** The Performance card: empty until {@link #setPerformanceView} fills it. */
    private final JPanel perfView = new JPanel(new BorderLayout());

    /** Puts {@code view} in the Performance card. */
    public void setPerformanceView(JComponent view)
    {
        perfView.removeAll();
        perfView.add(view, BorderLayout.CENTER);
        perfView.revalidate();
    }

    public void setTournamentGearCard(JComponent card)
    {
        tourneyGear = card;
        if (tourneyPanel != null) tourneyPanel.setGearCard(card);
    }

    public void showTourneys()
    {
        if (tourneyPanel != null) setActiveTab(CARD_TOURNEY);
    }

    /** The tournament box's Register: the Events tab's own sign-up for {@code t}. */
    public void register(Tourney t)
    {
        if (tourneyPanel != null) tourneyPanel.register(t);
    }

    /** Replaces the on-screen check made before a queue match shows the
     *  Matchmaking tab; {@code null} reads as not on screen. */
    void setPanelOpen(BooleanSupplier provider)
    {
        panelOpen = provider == null ? () -> false : provider;
    }

    /** A queue match's Confirm card is up: shows the Matchmaking tab, with the
     *  FAQ rows that hold its button, when this panel is on screen and another
     *  tab is showing. Never opens the panel. */
    private void onMatchShown()
    {
        if (CARD_LOBBY.equals(activeCard) || !GearKit.safe(panelOpen)) return;
        setFaq(true);
        setActiveTab(CARD_LOBBY);
    }

    // --- UI Creation Helpers ---

    /** The top row: Discord (Discord blurple) and the FAQ toggle. */
    private JPanel createLinks()
    {
        JPanel row = navRow("nav-links", 2);
        JButton discord = linkBtn("Discord", "Join our Discord", "https://discord.gg/ZuWzyzbtTW");
        discord.setBackground(BLURPLE);
        discord.setForeground(Color.WHITE);
        discord.setOpaque(true);
        row.add(discord);
        faqBtn = bold(new JButton("FAQ"), NAV_FONT_PT);
        faqBtn.setMargin(new Insets(2, 6, 2, 6));
        faqBtn.setFocusPainted(false);
        faqBtn.addActionListener(e -> setFaq(!faqBox.isVisible()));
        row.add(faqBtn);
        return row;
    }

    /** A link button — uniform NAV_FONT_PT BOLD font (the parent
     *  {@link GridLayout} controls width), focus painting off to match the
     *  nav buttons. Opens {@code url} (an https constant). */
    private static JButton linkBtn(String label, String tooltip, String url)
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
        // Send the toggle to the lobby, then update the matchmaking
        // panel at once: the lobby/block_added echo lands 100–500 ms
        // later and is a no-op then (onBlockAdded short-circuits when
        // the id is already in the set).
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

    /** A login: the open Events tab asks for its events at once. */
    public void loginReady()
    {
        invokeLater(() -> { if (tourneyPanel != null) tourneyPanel.onConnected(); });
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
        allMatches = matches;

        boolean hasData = matches != null && matches.size() > 0;
        historyBtn.setVisible(hasData);
        advancedToggle.setVisible(hasData);
    }

    private void resetUi()
    {
        rankProgress.reset();
        perfStats.reset();
        chartPanel.setMatches(new JsonArray());
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
        perfStats.setOppRanks(stats);
    }

    /** Every rating row in screen order, each from its bucket's v3 card ({@code buckets.<b>.v3}) and the bucket's
     *  streak pair. A bucket without a card reads "—" (the tournament row stays hidden). */
    private void applyAll(JsonObject stats, String playerName, int gen)
    {
        for (String b : RankProgress.BUCKET_KEYS)
        {
            JsonObject bucket = optObject(optObject(stats, "buckets"), b);
            rankProgress.updateBucket(b, optObject(bucket, "v3"), optInt(bucket, "streak", 0), optInt(bucket, "best_streak", 0));
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
