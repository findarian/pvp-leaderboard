package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.LobbyMember;
import com.pvp.leaderboard.lobby.Style;
import com.pvp.leaderboard.queue.QueueText;
import com.pvp.leaderboard.tournament.NoOpTournamentService;
import com.pvp.leaderboard.tournament.StandingsRow;
import com.pvp.leaderboard.tournament.TournamentActive;
import com.pvp.leaderboard.tournament.TournamentEventListener;
import com.pvp.leaderboard.tournament.TournamentSeries;
import com.pvp.leaderboard.tournament.TournamentService;
import com.pvp.leaderboard.tournament.TournamentStandings;
import com.pvp.leaderboard.tournament.TournamentSummary;
import com.pvp.leaderboard.util.JsonLenient;
import com.pvp.leaderboard.util.NameUtils;
import com.pvp.leaderboard.util.RankUtils;
import com.pvp.leaderboard.util.TimestampText;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Slf4j
public class TournamentsPanel extends JPanel implements TournamentEventListener
{
    public static final String CARD_LIST = "tournaments-list";
    public static final String CARD_ACTIVE = "tournaments-active";
    public static final String NAME_LIST = "tournaments-list-body";
    public static final String NAME_STANDINGS = "tournaments-standings";
    public static final String NAME_BANNER = "tournaments-banner";
    public static final String NAME_ROUND_END = "tournaments-round-end";
    /** The in-panel Rules page. */
    public static final String CARD_RULES = "tournaments-rules";
    /** Shown in place of the tab's view while the player is not logged into the game. */
    public static final String CARD_LOGGED_OUT = "tournaments-logged-out";
    public static final String NAME_LOGGED_OUT_TITLE = "tournaments-logged-out-title";
    public static final String NAME_LOGGED_OUT_NOTICE = "tournaments-logged-out-notice";
    /** The list card's status line (also says "Connecting…"). */
    public static final String NAME_LIST_STATUS = "tournaments-list-status";
    /** The finished events, newest first, and one event's final standings. */
    public static final String CARD_PAST = "tournaments-past";
    public static final String CARD_PAST_STANDINGS = "tournaments-past-standings";
    public static final String NAME_PAST_LIST = "tournaments-past-list";
    public static final String NAME_PAST_STATUS = "tournaments-past-status";
    public static final String NAME_PAST_STANDINGS = "tournaments-past-standings-body";
    public static final String NAME_PAST_STANDINGS_STATUS = "tournaments-past-standings-status";
    /** The running event's opponent card. */
    public static final String NAME_OPPONENT_CARD = "tournament-opponent-card";
    /** Shown while the socket is not up yet — the list is asked for on connect. */
    static final String CONNECTING_TEXT = "Connecting…";
    static final String REPORT_CMD = "tournament/report_problem";
    /** The backend's not-linked answer to a report, and the disabled button's reason. */
    static final String REPORT_LOGIN_HINT = "Log in with Discord to contact the host.";
    /** The backend's per-player-per-event rate limit on reports. */
    static final String REPORT_RATE_LIMITED_TEXT = "You have already reported this tournament recently.";
    public static final String NAME_LIST_GEAR_SLOT = "tournaments-list-gear-slot";
    public static final String NAME_ACTIVE_GEAR_SLOT = "tournaments-active-gear-slot";
    static final String GEAR_STATUS_CMD = "tournament/gear_status";
    static final String REGISTER_CMD = "tournament/register";
    /** A registration refused for the event's minimum number of matches. */
    static final String MIN_GAMES_CODE = "TOURNAMENT_MIN_GAMES";
    static final String UPDATE_REQUIRED_TEXT = "This tournament needs a newer PvP Leaderboard plugin — update it to register.";
    static final String ROUND_END_SUBMIT_TEXT = "Submit within 30 seconds or you may be removed.";
    static final String WAIT_NEXT_ROUND_TEXT = "Wait until next round";
    static final String PAST_FAILED_TEXT = "Could not load past tournaments.";
    static final String PAST_EMPTY_TEXT = "No finished tournaments yet.";
    static final String PAST_STANDINGS_FAILED_TEXT = "Could not load the standings.";
    static final String LOADING_TEXT = "Loading…";
    static final int PAST_PAGE = 10;
    static final long IN_COMBAT_MIN_INTERVAL_MS = 30_000L;
    private static final int STANDINGS_MAX_ROWS = 40;
    /** The standings rows' size: the largest in this range at which every row fits. */
    static final float STANDINGS_MAX_PT = 16f;
    static final float STANDINGS_MIN_PT = 12f;
    static final float HEADER_PT = 16f;
    static final float BODY_PT = 15f;
    /** The opponent card's chip size. */
    static final int CARD_CHIP_PT = 14;
    private static final Color GREEN = new Color(0x3e, 0xcf, 0x8e);
    private static final Color AMBER = new Color(0xff, 0xb3, 0x47);
    private static final Color RED = new Color(0x5a, 0x2a, 0x2a);
    private static final Color MUTED = new Color(0x9a, 0x9a, 0x9a);

    private final TournamentService service;
    private final Supplier<String> regionSupplier;
    private volatile Supplier<String> selfNameSupplier = () -> null;
    private volatile BooleanSupplier inCombatProvider = () -> false;
    private volatile Consumer<String> linkOpener = TournamentsPanel::browse;
    /** {@code DiscordAuthService::isLoggedIn} once the dashboard wires it; unwired = logged out. */
    private volatile BooleanSupplier discordLoggedIn = () -> false;
    /** Whether the player is logged into the game; unwired = logged in. */
    private volatile BooleanSupplier gameLoggedIn = () -> true;
    /** The player's own match count per event bucket; unwired = unknown. */
    private volatile Function<String, Integer> matchCountProvider = bucket -> null;
    /** Opens a player's page in Player Lookup; unwired = nothing. */
    private volatile Consumer<String> onOpenProfile;
    /** The opponent card's rank by name; unwired = no rank. */
    private volatile Function<String, CompletableFuture<String>> rankLookup;
    /** The finished events for Past tournaments; unwired = cannot load. */
    private volatile IntFunction<CompletableFuture<JsonObject>> historyLoader;
    /** One finished event's standings; unwired = cannot load. */
    private volatile Function<String, CompletableFuture<JsonObject>> standingsLoader;
    /** The event whose Register was pressed and not yet answered. */
    private String pendingRegisterId;
    /** Events whose registration was refused for their minimum, until the next connect. */
    private final Set<String> minGamesRefused = new HashSet<>();
    private final LongSupplier nowMs;
    /** The viewer's zone for the list card's times (injected for tests). */
    private final ZoneId zone;
    /** One refresher per time label on the list card, re-run by the ticker
     *  so the relative phrase moves without a new {@code tournament/list}. */
    private final List<Runnable> liveTimes = new ArrayList<>();

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JLabel banner = new JLabel(" ");
    /** Whether a banner is up; it is hidden while the Rules page shows. */
    private boolean bannerShown;
    private final JPanel listBody = new JPanel();
    private final JLabel listStatus = new JLabel("Loading…", SwingConstants.LEFT);
    private final JLabel activeHeader = new JLabel(" ");
    private final JLabel activeEta = new JLabel(" ");
    private final JLabel activeMatch = new JLabel(" ");
    private final JLabel activeWhere = new JLabel(" ");
    private final JLabel activeStatus = new JLabel(" ");
    private final JPanel opponentSlot = new JPanel(new BorderLayout());
    private PlayerCard opponentCard;
    /** The name the card shows, canonical; {@code null} while there is no card. */
    private String opponentCardKey;
    /** The card's rank index, -1 while unknown. */
    private int opponentRankIdx = -1;
    private final JPanel roundEndBox = new JPanel();
    private final JLabel roundEndLabel = new JLabel(" ");
    private final StandingsBody standingsBody = new StandingsBody();
    private final JButton withdrawActiveBtn = tabButton("Withdraw");
    /** The active footer's report button — gated like every card's. */
    private final JButton reportActiveBtn = tabButton(TournamentInfoCard.REPORT_LABEL);
    /** The running event's own Rules; enabled once its list entry is known. */
    private final JButton activeRulesBtn = tabButton("Rules");
    /** The final standings' way back to the list. */
    private final JButton backActiveBtn = tabButton("Back");
    private JPanel activePairRow;
    private final Timer ticker;
    /** The open Rules page, if any, and the card to return to from it. */
    private TournamentRulesDialog rulesDialog;
    private String rulesReturnCard = CARD_LIST;
    private final JPanel listGearSlot = new JPanel(new BorderLayout());
    private final JPanel activeGearSlot = new JPanel(new BorderLayout());
    private javax.swing.JComponent gearCard;
    private final JPanel pastBody = new JPanel();
    private final JLabel pastStatus = new JLabel(" ");
    private final JLabel pastStandingsEvent = new JLabel(" ");
    private final JLabel pastStandingsStatus = new JLabel(" ");
    private final StandingsBody pastStandingsBody = new StandingsBody();
    /** Counts the Past tournaments loads, so a late answer for an earlier visit is dropped. */
    private int pastGeneration;
    /** The panel's side of every card's buttons — one implementation for all cards. */
    private final TournamentInfoCard.Actions cardActions = new TournamentInfoCard.Actions()
    {
        @Override public void register(TournamentSummary t)
        {
            pendingRegisterId = t.tournamentId;
            service.register(t.tournamentId, regionSupplier.get());
        }
        @Override public void withdraw(TournamentSummary t) { service.withdraw(t.tournamentId); }
        @Override public void rules(TournamentSummary t) { openRules(t); }
        @Override public void report(TournamentSummary t) { onReportProblem(t.tournamentId); }
    };

    private List<TournamentSummary> events = Collections.emptyList();
    private List<TournamentSummary> myRegistrations = Collections.emptyList();
    private TournamentActive active;
    private TournamentSeries series;
    /** Local wall-clock ms at which the current round ends (server ETA at receipt), 0 = none. */
    private long roundEndsAtMs;
    private long breakUntilS;
    private boolean bye;
    /** The match is over and the next round has not opened. */
    private boolean waitUntilNextRound;
    /** The final standings are on screen until Back. */
    private boolean finalView;
    private long roundEndRespondByMs;
    private int roundExtensions;
    private long lastInCombatSentMs;
    private boolean showing;
    private String subscribedId;
    private String currentCard = CARD_LIST;

    public TournamentsPanel(TournamentService service, Supplier<String> regionSupplier)
    {
        this(service, regionSupplier, System::currentTimeMillis);
    }

    public TournamentsPanel(TournamentService service, Supplier<String> regionSupplier, LongSupplier nowMs)
    {
        this(service, regionSupplier, nowMs, ZoneId.systemDefault());
    }

    /** {@code zone} is the viewer's zone for the list card's times — the
     *  computer's own zone in game, a fixed one in tests. */
    public TournamentsPanel(TournamentService service, Supplier<String> regionSupplier, LongSupplier nowMs, ZoneId zone)
    {
        this.service = service == null ? new NoOpTournamentService() : service;
        this.regionSupplier = regionSupplier == null ? () -> null : regionSupplier;
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        banner.setName(NAME_BANNER);
        banner.setFont(banner.getFont().deriveFont(Font.BOLD, BODY_PT));
        banner.setForeground(AMBER);
        banner.setVisible(false);
        add(banner, BorderLayout.NORTH);
        cardHost.add(buildListCard(), CARD_LIST);
        cardHost.add(buildActiveCard(), CARD_ACTIVE);
        cardHost.add(buildLoggedOutCard(), CARD_LOGGED_OUT);
        cardHost.add(buildPastCard(), CARD_PAST);
        cardHost.add(buildPastStandingsCard(), CARD_PAST_STANDINGS);
        add(cardHost, BorderLayout.CENTER);
        showCard(CARD_LIST);
        if (!this.service.isAvailable())
        {
            setWrapped(listStatus, "Tournaments are turned off in the plugin settings.");
        }
        this.service.addListener(this);
        ticker = new Timer(1000, e -> onTick());
        ticker.setRepeats(true);
        ticker.start();
    }

    // ---------------------------------------------------------------- wiring
    public void setSelfIdentity(Supplier<String> selfName)
    {
        this.selfNameSupplier = selfName == null ? () -> null : selfName;
    }

    /** {@code FightMonitor::isInCombat} — drives the automatic in-combat signal. */
    public void setInCombatProvider(BooleanSupplier provider)
    {
        this.inCombatProvider = provider == null ? () -> false : provider;
    }

    /** Test seam for the Rules link (defaults to RuneLite's browser opener). */
    public void setLinkOpener(Consumer<String> opener)
    {
        this.linkOpener = opener == null ? TournamentsPanel::browse : opener;
    }

    /** Opens a player's page in Player Lookup; a click on the opponent card calls it with the name. */
    public void setOnOpenProfile(Consumer<String> opener)
    {
        this.onOpenProfile = opener;
    }

    /** The opponent card's rank: the name to the tier label, {@code null} when unknown. */
    public void setRankLookup(Function<String, CompletableFuture<String>> lookup)
    {
        this.rankLookup = lookup;
    }

    /** The finished events for Past tournaments: the page limit to the history route's answer. */
    public void setHistoryLoader(IntFunction<CompletableFuture<JsonObject>> loader)
    {
        this.historyLoader = loader;
    }

    /** One finished event's standings for Past tournaments: the id to the standings route's answer. */
    public void setStandingsLoader(Function<String, CompletableFuture<JsonObject>> loader)
    {
        this.standingsLoader = loader;
    }

    /** The dashboard wires {@code DiscordAuthService::isLoggedIn} — the
     *  Report gate: a report is accepted only from a player logged in with
     *  Discord, so the button follows the plugin's own login state.
     *  {@code null} = unwired = logged out. */
    public void setDiscordLoginProvider(BooleanSupplier provider)
    {
        this.discordLoggedIn = provider == null ? () -> false : provider;
    }

    /** The dashboard calls this from its login-state hook: every Report
     *  button re-evaluates locally — no {@code tournament/list}, no status. */
    public void onDiscordLoginChanged()
    {
        renderList();
        refreshActiveReportGate();
    }

    /** The player's own match count for an event bucket ({@code "nh"}, ...),
     *  {@code null} when unknown. {@code null} = unwired = unknown. */
    public void setMatchCountProvider(Function<String, Integer> provider)
    {
        this.matchCountProvider = provider == null ? bucket -> null : provider;
        renderList();
    }

    /** The count source changed: the cards re-evaluate locally. */
    public void onMatchCountsChanged()
    {
        renderList();
    }

    /** Whether the player is logged into the game; unwired = logged in. */
    public void setGameLoggedInProvider(BooleanSupplier provider)
    {
        this.gameLoggedIn = provider == null ? () -> true : provider;
        refreshLoginView();
    }

    /** The game's login state changed: the logged-out notice or the tab's own view. */
    public void refreshLoginView()
    {
        showCard(currentCard);
    }

    /** The player's count in the event's bucket; {@code null} when unknown. */
    private Integer matchCountFor(TournamentSummary t)
    {
        try
        {
            Integer count = matchCountProvider.apply(t.category == null ? "" : t.category.trim());
            return count == null || count < 0 ? null : count;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private boolean discordLoggedInNow()
    {
        try
        {
            return discordLoggedIn.getAsBoolean();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private void refreshActiveReportGate()
    {
        boolean in = discordLoggedInNow();
        reportActiveBtn.setEnabled(in);
        reportActiveBtn.setToolTipText(in ? TournamentInfoCard.REPORT_TOOLTIP : TournamentInfoCard.REPORT_LOGIN_TOOLTIP);
    }

    private static void browse(String url)
    {
        try
        {
            net.runelite.client.util.LinkBrowser.browse(url);
        }
        catch (Throwable t)
        {
            log.debug("LinkBrowser failed for {}", url, t);
        }
    }

    /** The dashboard calls this when the sub-tab is shown / hidden: a shown
     *  panel re-syncs (status + list) and subscribes to the active event's
     *  standings; a hidden one unsubscribes and drops the Rules page. */
    public void setShowing(boolean visible)
    {
        this.showing = visible;
        if (visible)
        {
            sync();
        }
        else
        {
            unsubscribe();
            if (CARD_RULES.equals(currentCard)) closeRules();
        }
    }

    /** status + list + the standings subscription — or, while the socket is
     *  down (plugin start, the login window), "Connecting…" and nothing
     *  sent: {@code WebSocketManager.send} drops frames without a socket,
     *  and {@link #onSocketConnected()} runs this again once it is up. */
    private void sync()
    {
        if (!service.isConnected())
        {
            setWrapped(listStatus, CONNECTING_TEXT);
            return;
        }
        service.status();
        service.list();
        resubscribe();
    }

    /** The transport's connect hook: a showing tab re-asks for
     *  everything it may have missed; a hidden one waits for its next show. */
    @Override
    public void onSocketConnected()
    {
        pendingRegisterId = null;
        if (!minGamesRefused.isEmpty())
        {
            minGamesRefused.clear();
            renderList();
        }
        if (showing) sync();
    }

    public String currentCard()
    {
        return currentCard;
    }

    public void shutdown()
    {
        ticker.stop();
        unsubscribe();
        service.removeListener(this);
    }

    // ---------------------------------------------------------------- cards
    private JPanel buildListCard()
    {
        JPanel card = new JPanel(new BorderLayout());
        card.setName(CARD_LIST);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Tournaments");
        title.setFont(title.getFont().deriveFont(Font.BOLD, HEADER_PT));
        title.setAlignmentX(LEFT_ALIGNMENT);
        top.add(title);
        listStatus.setName(NAME_LIST_STATUS);
        listStatus.setFont(listStatus.getFont().deriveFont(Font.PLAIN, BODY_PT));
        listStatus.setForeground(MUTED);
        listStatus.setAlignmentX(LEFT_ALIGNMENT);
        top.add(listStatus);
        top.add(Box.createVerticalStrut(6));
        JButton refresh = tabButton("Refresh");
        refresh.setName("tournaments-refresh");
        refresh.addActionListener(e -> sync());
        top.add(refresh);
        top.add(Box.createVerticalStrut(4));
        JButton past = tabButton("Past tournaments");
        past.setName("tournaments-past");
        past.addActionListener(e -> openPast());
        top.add(past);
        top.add(Box.createVerticalStrut(6));
        listGearSlot.setName(NAME_LIST_GEAR_SLOT);
        listGearSlot.setOpaque(false);
        listGearSlot.setAlignmentX(LEFT_ALIGNMENT);
        top.add(listGearSlot);
        card.add(top, BorderLayout.NORTH);
        listBody.setLayout(new BoxLayout(listBody, BoxLayout.Y_AXIS));
        listBody.setName(NAME_LIST);
        JScrollPane scroll = new JScrollPane(listBody);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /** "Set up tournaments" and "Please log into the game to set up tournaments.", as the Matchmaking tab words it. */
    private JPanel buildLoggedOutCard()
    {
        JPanel card = new JPanel();
        card.setName(CARD_LOGGED_OUT);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Set up tournaments");
        title.setName(NAME_LOGGED_OUT_TITLE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, HEADER_PT));
        title.setAlignmentX(LEFT_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(8));
        JLabel notice = new JLabel();
        notice.setName(NAME_LOGGED_OUT_NOTICE);
        notice.setFont(notice.getFont().deriveFont(Font.BOLD, BODY_PT));
        notice.setForeground(new Color(0xcc, 0xcc, 0xcc));
        notice.setText(TournamentInfoCard.wrapHtml(notice.getFont(), TournamentInfoCard.TEXT_WIDTH_PX,
            "Please log into the game to set up tournaments."));
        notice.setAlignmentX(LEFT_ALIGNMENT);
        card.add(notice);
        return card;
    }

    private JPanel buildActiveCard()
    {
        JPanel card = new JPanel(new BorderLayout());
        card.setName(CARD_ACTIVE);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JLabel l : new JLabel[]{activeHeader, activeEta, activeMatch, activeWhere, activeStatus})
        {
            l.setAlignmentX(LEFT_ALIGNMENT);
            top.add(l);
        }
        activeGearSlot.setName(NAME_ACTIVE_GEAR_SLOT);
        activeGearSlot.setOpaque(false);
        activeGearSlot.setAlignmentX(LEFT_ALIGNMENT);
        top.add(activeGearSlot, 2);
        opponentSlot.setName("tournament-opponent-slot");
        opponentSlot.setOpaque(false);
        opponentSlot.setAlignmentX(LEFT_ALIGNMENT);
        opponentSlot.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        top.add(opponentSlot, 3);
        activeHeader.setName("tournaments-active-header");
        activeHeader.setFont(activeHeader.getFont().deriveFont(Font.BOLD, HEADER_PT));
        activeEta.setName("tournaments-active-eta");
        activeEta.setFont(activeEta.getFont().deriveFont(Font.BOLD, HEADER_PT));
        activeEta.setForeground(GREEN);
        activeMatch.setName("tournaments-active-match");
        activeMatch.setFont(activeMatch.getFont().deriveFont(Font.BOLD, HEADER_PT));
        activeWhere.setName("tournaments-active-where");
        activeWhere.setFont(activeWhere.getFont().deriveFont(Font.PLAIN, BODY_PT));
        activeStatus.setName("tournaments-active-status");
        activeStatus.setFont(activeStatus.getFont().deriveFont(Font.PLAIN, BODY_PT));
        activeStatus.setForeground(MUTED);

        roundEndBox.setLayout(new BoxLayout(roundEndBox, BoxLayout.Y_AXIS));
        roundEndBox.setName(NAME_ROUND_END);
        roundEndBox.setBackground(new Color(0x3a, 0x2d, 0x0a));
        roundEndBox.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        roundEndBox.setAlignmentX(LEFT_ALIGNMENT);
        roundEndLabel.setForeground(AMBER);
        roundEndLabel.setFont(roundEndLabel.getFont().deriveFont(Font.PLAIN, BODY_PT));
        roundEndLabel.setAlignmentX(LEFT_ALIGNMENT);
        roundEndBox.add(roundEndLabel);
        roundEndBox.setVisible(false);
        top.add(roundEndBox);

        JLabel lbTitle = new JLabel("Live leaderboard");
        lbTitle.setFont(lbTitle.getFont().deriveFont(Font.BOLD, HEADER_PT));
        lbTitle.setAlignmentX(LEFT_ALIGNMENT);
        lbTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        top.add(lbTitle);
        card.add(top, BorderLayout.NORTH);

        standingsBody.setLayout(new BoxLayout(standingsBody, BoxLayout.Y_AXIS));
        standingsBody.setName(NAME_STANDINGS);
        JScrollPane scroll = new JScrollPane(standingsBody);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        activeRulesBtn.setName("tournaments-active-rules");
        activeRulesBtn.addActionListener(e -> openRulesForActive());
        activeRulesBtn.setEnabled(false);
        reportActiveBtn.setName("tournaments-report");
        reportActiveBtn.addActionListener(e -> { if (active != null) onReportProblem(active.tournamentId); });
        refreshActiveReportGate();
        activePairRow = TournamentInfoCard.pairRow(reportActiveBtn, activeRulesBtn);
        footer.add(activePairRow);
        footer.add(Box.createVerticalStrut(4));
        withdrawActiveBtn.setName("tournaments-active-withdraw");
        withdrawActiveBtn.setBackground(RED);
        withdrawActiveBtn.setForeground(new Color(0xff, 0xb3, 0xb3));
        withdrawActiveBtn.addActionListener(e -> { if (active != null) service.withdraw(active.tournamentId); });
        footer.add(withdrawActiveBtn);
        backActiveBtn.setName("tournaments-active-back");
        backActiveBtn.addActionListener(e -> leaveFinalView());
        backActiveBtn.setVisible(false);
        footer.add(backActiveBtn);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    /** The finished events, newest first, each opening its final standings. */
    private JPanel buildPastCard()
    {
        JPanel card = new JPanel(new BorderLayout());
        card.setName(CARD_PAST);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Past tournaments");
        title.setFont(title.getFont().deriveFont(Font.BOLD, HEADER_PT));
        title.setAlignmentX(LEFT_ALIGNMENT);
        top.add(title);
        pastStatus.setName(NAME_PAST_STATUS);
        pastStatus.setFont(pastStatus.getFont().deriveFont(Font.PLAIN, BODY_PT));
        pastStatus.setForeground(MUTED);
        pastStatus.setAlignmentX(LEFT_ALIGNMENT);
        top.add(pastStatus);
        top.add(Box.createVerticalStrut(6));
        JButton back = tabButton("Back");
        back.setName("tournaments-past-back");
        back.addActionListener(e -> showCard(CARD_LIST));
        top.add(back);
        top.add(Box.createVerticalStrut(6));
        card.add(top, BorderLayout.NORTH);
        pastBody.setLayout(new BoxLayout(pastBody, BoxLayout.Y_AXIS));
        pastBody.setName(NAME_PAST_LIST);
        JScrollPane scroll = new JScrollPane(pastBody);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /** One finished event's final standings, drawn like the live leaderboard. */
    private JPanel buildPastStandingsCard()
    {
        JPanel card = new JPanel(new BorderLayout());
        card.setName(CARD_PAST_STANDINGS);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Final standings");
        title.setName("tournaments-past-standings-title");
        title.setFont(title.getFont().deriveFont(Font.BOLD, HEADER_PT));
        title.setAlignmentX(LEFT_ALIGNMENT);
        top.add(title);
        pastStandingsEvent.setName("tournaments-past-standings-event");
        pastStandingsEvent.setFont(pastStandingsEvent.getFont().deriveFont(Font.PLAIN, BODY_PT));
        pastStandingsEvent.setAlignmentX(LEFT_ALIGNMENT);
        top.add(pastStandingsEvent);
        pastStandingsStatus.setName(NAME_PAST_STANDINGS_STATUS);
        pastStandingsStatus.setFont(pastStandingsStatus.getFont().deriveFont(Font.PLAIN, BODY_PT));
        pastStandingsStatus.setForeground(MUTED);
        pastStandingsStatus.setAlignmentX(LEFT_ALIGNMENT);
        top.add(pastStandingsStatus);
        top.add(Box.createVerticalStrut(6));
        JButton back = tabButton("Back");
        back.setName("tournaments-past-standings-back");
        back.addActionListener(e -> showCard(CARD_PAST));
        top.add(back);
        top.add(Box.createVerticalStrut(6));
        card.add(top, BorderLayout.NORTH);
        pastStandingsBody.setLayout(new BoxLayout(pastStandingsBody, BoxLayout.Y_AXIS));
        pastStandingsBody.setName(NAME_PAST_STANDINGS);
        JScrollPane scroll = new JScrollPane(pastStandingsBody);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /** The tab's bold button — shared with the cards, the Rules page and the
     *  kit card — at the side panel's button size and as wide as its column. */
    static JButton tabButton(String label)
    {
        JButton b = new JButton(label);
        b.setFont(b.getFont().deriveFont(Font.BOLD, TournamentInfoCard.BUTTON_PT));
        b.setMargin(new Insets(6, 8, 6, 8));
        b.setFocusPainted(false);
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
        return b;
    }

    /** A line at its label's font, wrapped to the tab's text width; blank
     *  keeps the line's height. */
    private static void setWrapped(JLabel label, String plain)
    {
        String text = plain == null || plain.trim().isEmpty()
            ? " " : TournamentInfoCard.wrapHtml(label.getFont(), TournamentInfoCard.TEXT_WIDTH_PX, plain);
        if (!text.equals(label.getText())) label.setText(text);
    }

    private void showCard(String name)
    {
        currentCard = name;
        if (!CARD_RULES.equals(name)) dropRulesPage();
        cards.show(cardHost, gameLoggedInNow() ? name : CARD_LOGGED_OUT);
        syncBanner();
        placeGearCard();
    }

    private boolean gameLoggedInNow()
    {
        try
        {
            return gameLoggedIn.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            return true;
        }
    }

    public void setGearCard(javax.swing.JComponent card)
    {
        if (gearCard != null && gearCard.getParent() != null) gearCard.getParent().remove(gearCard);
        gearCard = card;
        placeGearCard();
    }

    private void placeGearCard()
    {
        if (gearCard == null) return;
        JPanel slot = active != null ? activeGearSlot : listGearSlot;
        if (gearCard.getParent() == slot) return;
        if (gearCard.getParent() != null) gearCard.getParent().remove(gearCard);
        slot.add(gearCard, BorderLayout.CENTER);
        listGearSlot.revalidate();
        activeGearSlot.revalidate();
        repaint();
    }

    private void showBanner(String text)
    {
        banner.setText(TournamentInfoCard.wrapEscaped(banner.getFont(), TournamentInfoCard.TEXT_WIDTH_PX, text));
        bannerShown = true;
        syncBanner();
        revalidate();
        repaint();
    }

    /** The banner shows while one is up, except on the Rules page. */
    private void syncBanner()
    {
        banner.setVisible(bannerShown && !CARD_RULES.equals(currentCard));
    }

    // ---------------------------------------------------------------- list rendering
    private void renderList()
    {
        listBody.removeAll();
        liveTimes.clear();
        if (!service.isAvailable())
        {
            setWrapped(listStatus, "Tournaments are turned off in the plugin settings.");
        }
        else if (events.isEmpty())
        {
            setWrapped(listStatus, "No open tournaments right now.");
        }
        else
        {
            setWrapped(listStatus, events.size() + (events.size() == 1 ? " event" : " events"));
        }
        for (TournamentSummary t : events)
        {
            listBody.add(eventCard(t));
            listBody.add(Box.createVerticalStrut(6));
        }
        listBody.revalidate();
        listBody.repaint();
    }

    private String myStatusFor(TournamentSummary t)
    {
        if (t.myStatus != null) return t.myStatus;
        for (TournamentSummary r : myRegistrations)
        {
            if (r.tournamentId.equals(t.tournamentId)) return r.myStatus;
        }
        return null;
    }

    /** One {@link TournamentInfoCard} per listed event; its time
     *  lines join the existing 1 Hz refresh. */
    private TournamentInfoCard eventCard(TournamentSummary t)
    {
        TournamentInfoCard card = new TournamentInfoCard(t, myStatusFor(t), discordLoggedInNow(), zone, nowMs, cardActions,
            matchCountFor(t), minGamesRefused.contains(t.tournamentId));
        liveTimes.add(card::tick);
        return card;
    }

    static String when(TournamentSummary t)
    {
        return when(t, ZoneId.systemDefault(), System.currentTimeMillis());
    }

    /** "Running · round 2 of 5", or "Starts Thu 24 Sep 19:00 · in 3 days"
     *  from {@code starts_at} in the viewer's zone, or the raw status when
     *  the event carries no start epoch. */
    static String when(TournamentSummary t, ZoneId zone, long nowMs)
    {
        if (t.isRunning()) return "Running · round " + t.currentRound + " of " + t.rounds;
        if (t.startsAt > 0) return "Starts " + TimestampText.describe(t.startsAt, zone, nowMs);
        return t.status == null ? "" : t.status;
    }

    /** "Registration closes today 18:30 · in 12 min" for an open event with
     *  a {@code registration_closes_at}; {@code ""} otherwise (a running or
     *  finished event has no window to show). */
    static String closes(TournamentSummary t, ZoneId zone, long nowMs)
    {
        if (!t.isOpenForRegistration() || t.registrationClosesAt <= 0) return "";
        return "Registration closes " + TimestampText.describe(t.registrationClosesAt, zone, nowMs);
    }

    static String gp(long amount)
    {
        if (amount >= 1_000_000) return trim(amount / 1_000_000.0) + "M";
        if (amount >= 1_000) return trim(amount / 1_000.0) + "K";
        return Long.toString(amount);
    }

    private static String trim(double v)
    {
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static String escape(String s)
    {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- past tournaments
    /** One finished event of the history route: the entry and when it ended. */
    static final class PastEvent
    {
        final TournamentSummary event;
        final long endedAt;

        PastEvent(TournamentSummary event, long endedAt)
        {
            this.event = event;
            this.endedAt = endedAt;
        }
    }

    /** The history route's {@code tournaments[]} in its order; entries without an id are skipped. */
    static List<PastEvent> pastEvents(JsonObject history)
    {
        List<PastEvent> out = new ArrayList<>();
        for (JsonElement e : JsonLenient.optArray(history, "tournaments"))
        {
            if (e == null || !e.isJsonObject()) continue;
            TournamentSummary t = TournamentSummary.fromJson(e.getAsJsonObject());
            if (t != null) out.add(new PastEvent(t, JsonLenient.optLong(e.getAsJsonObject(), "ended_at", 0L)));
        }
        return out;
    }

    private void openPast()
    {
        int gen = ++pastGeneration;
        pastBody.removeAll();
        setWrapped(pastStatus, LOADING_TEXT);
        showCard(CARD_PAST);
        IntFunction<CompletableFuture<JsonObject>> loader = historyLoader;
        CompletableFuture<JsonObject> answer = loader == null ? null : safeLoad(() -> loader.apply(PAST_PAGE));
        if (answer == null)
        {
            setWrapped(pastStatus, PAST_FAILED_TEXT);
            return;
        }
        answer.whenComplete((json, ex) -> SwingUtilities.invokeLater(() ->
        {
            if (gen != pastGeneration) return;
            if (ex != null || json == null)
            {
                setWrapped(pastStatus, PAST_FAILED_TEXT);
                return;
            }
            renderPast(pastEvents(json));
        }));
    }

    private static <T> CompletableFuture<T> safeLoad(Supplier<CompletableFuture<T>> load)
    {
        try
        {
            return load.get();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private void renderPast(List<PastEvent> past)
    {
        pastBody.removeAll();
        setWrapped(pastStatus, past.isEmpty() ? PAST_EMPTY_TEXT : past.size() + (past.size() == 1 ? " event" : " events"));
        for (PastEvent p : past)
        {
            pastBody.add(pastRow(p));
            pastBody.add(Box.createVerticalStrut(6));
        }
        pastBody.revalidate();
        pastBody.repaint();
    }

    /** A finished event's row: its name, the style and when it ended; a click opens its final standings. */
    private JPanel pastRow(PastEvent p)
    {
        JPanel row = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setName("past-event-" + p.event.tournamentId);
        row.setBackground(TournamentInfoCard.CARD_BG);
        row.setOpaque(true);
        row.setBorder(BorderFactory.createCompoundBorder(new javax.swing.border.MatteBorder(0, 0, 1, 0, new Color(0x40, 0x40, 0x40)),
            BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel name = new JLabel();
        name.setFont(name.getFont().deriveFont(Font.BOLD, HEADER_PT));
        name.setForeground(Color.WHITE);
        name.setAlignmentX(LEFT_ALIGNMENT);
        name.setText(TournamentInfoCard.wrapHtml(name.getFont(), TournamentInfoCard.TEXT_WIDTH_PX, p.event.name));
        row.add(name);
        JLabel line = new JLabel();
        line.setFont(line.getFont().deriveFont(Font.PLAIN, BODY_PT));
        line.setForeground(MUTED);
        line.setAlignmentX(LEFT_ALIGNMENT);
        line.setText(TournamentInfoCard.wrapHtml(line.getFont(), TournamentInfoCard.TEXT_WIDTH_PX, pastLine(p, zone, nowMs.getAsLong())));
        row.add(line);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter open = new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                openPastStandings(p.event);
            }
        };
        row.addMouseListener(open);
        name.addMouseListener(open);
        line.addMouseListener(open);
        return row;
    }

    /** {@code "NH Main · 3 rounds · 8 players · Ended today 13:00 · 1 h ago"}. */
    static String pastLine(PastEvent p, ZoneId zone, long nowMs)
    {
        List<String> parts = new ArrayList<>();
        parts.add(p.event.styleLabel());
        if (p.event.rounds > 0) parts.add(p.event.rounds + (p.event.rounds == 1 ? " round" : " rounds"));
        parts.add(p.event.registeredCount + (p.event.registeredCount == 1 ? " player" : " players"));
        if (p.endedAt > 0) parts.add("Ended " + TimestampText.describe(p.endedAt, zone, nowMs));
        return String.join(" · ", parts);
    }

    private void openPastStandings(TournamentSummary t)
    {
        int gen = ++pastGeneration;
        pastStandingsBody.removeAll();
        pastStandingsBody.rows.clear();
        setWrapped(pastStandingsEvent, t.name);
        setWrapped(pastStandingsStatus, LOADING_TEXT);
        showCard(CARD_PAST_STANDINGS);
        Function<String, CompletableFuture<JsonObject>> loader = standingsLoader;
        CompletableFuture<JsonObject> answer = loader == null ? null : safeLoad(() -> loader.apply(t.tournamentId));
        if (answer == null)
        {
            setWrapped(pastStandingsStatus, PAST_STANDINGS_FAILED_TEXT);
            return;
        }
        answer.whenComplete((json, ex) -> SwingUtilities.invokeLater(() ->
        {
            if (gen != pastGeneration) return;
            TournamentStandings s = ex != null ? null : TournamentStandings.fromJson(json);
            if (s == null)
            {
                setWrapped(pastStandingsStatus, PAST_STANDINGS_FAILED_TEXT);
                return;
            }
            setWrapped(pastStandingsStatus, " ");
            renderStandings(pastStandingsBody, "past-standings-row-", "past-standings-rank-", s.rows, -1);
        }));
    }

    // ---------------------------------------------------------------- active rendering
    private void renderActive()
    {
        if (active == null)
        {
            showCard(CARD_LIST);
            return;
        }
        finalView = false;
        setWrapped(activeHeader, active.name + " · Round " + active.round + "/" + active.rounds);
        if (series != null)
        {
            showOpponentCard(series);
            activeMatch.setVisible(false);
            setWrapped(activeWhere, "World · place: " + series.worldLabel() + " · " + (series.meetingPlace == null ? "?" : series.meetingPlace));
            activeWhere.setVisible(true);
            activeStatus.setVisible(true);
        }
        else
        {
            dropOpponentCard();
            activeMatch.setVisible(true);
            setWrapped(activeMatch, bye ? "Bye this round (counts as a win)" : waitUntilNextRound ? WAIT_NEXT_ROUND_TEXT : "Waiting for the next round");
            setWrapped(activeWhere, " ");
            activeWhere.setVisible(false);
            activeStatus.setVisible(false);
        }
        activePairRow.setVisible(true);
        withdrawActiveBtn.setVisible(true);
        backActiveBtn.setVisible(false);
        renderEta();
        renderStatus();
        refreshActiveReportGate();
        refreshActiveRules();
        showCard(CARD_ACTIVE);
    }

    private void renderEta()
    {
        long now = nowMs.getAsLong();
        if (breakUntilS > 0 && breakUntilS * 1000L > now)
        {
            setWrapped(activeEta, "On a break · next round in " + mmss((breakUntilS * 1000L - now) / 1000L));
            return;
        }
        if (roundEndsAtMs > 0)
        {
            long left = Math.max(0L, (roundEndsAtMs - now) / 1000L);
            setWrapped(activeEta, mmss(left) + " est. remaining in round" + extendedText(roundExtensions));
            return;
        }
        setWrapped(activeEta, "Between rounds");
    }

    static String extendedText(int extensions)
    {
        return extensions > 0 ? " (extended ×" + extensions + ")" : "";
    }

    static String pointsText(double points)
    {
        if (Double.isNaN(points) || Double.isInfinite(points)) return "0";
        if (points == Math.rint(points) && Math.abs(points) < 1e15) return Long.toString((long) points);
        return java.math.BigDecimal.valueOf(points).stripTrailingZeros().toPlainString();
    }

    static String standingsScore(StandingsRow r)
    {
        if (r == null) return "0";
        String score = pointsText(r.points);
        return r.draws > 0 ? score + " · " + r.draws + (r.draws == 1 ? " draw" : " draws") : score;
    }

    /** A row's right-hand cell: its score, then its status when it was removed ("2 · withdrew"). */
    static String rightCellText(StandingsRow r)
    {
        String score = standingsScore(r);
        String removed = r == null ? "" : r.removedLabel();
        return removed.isEmpty() ? score : score + " · " + removed;
    }

    static String mmss(long seconds)
    {
        long s = Math.max(0L, seconds);
        return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }

    private void renderStatus()
    {
        if (series == null)
        {
            setWrapped(activeStatus, " ");
            return;
        }
        boolean fighting = safeInCombat();
        setWrapped(activeStatus, "Status: " + (fighting ? "in combat (auto)" : "waiting for the fight · hop to " + series.worldLabel()));
    }

    private boolean safeInCombat()
    {
        try
        {
            return inCombatProvider.getAsBoolean();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    // ---------------------------------------------------------------- the opponent card
    /** The card for the series' opponent: rebuilt when the name changes, its rank filled in when the lookup answers. */
    private void showOpponentCard(TournamentSeries s)
    {
        String name = s.hasNamedOpponent() ? s.opponentName : TournamentSeries.UNKNOWN_OPPONENT;
        String key = NameUtils.canonicalKey(name);
        if (opponentCard == null || !key.equals(opponentCardKey))
        {
            opponentCardKey = key;
            opponentRankIdx = -1;
            if (s.hasNamedOpponent()) askRank(s.opponentName, key);
        }
        placeOpponentCard(s, name);
    }

    private void placeOpponentCard(TournamentSeries s, String name)
    {
        LobbyMember opponent = new LobbyMember(opponentCardKey, name, stylesOf(s.category), buildsOf(s.style),
            opponentRankIdx, opponentRankIdx, MatchmakingLobbyPanel.regionCodeFor(s.opponentRegion), false);
        PlayerCard card = new PlayerCard(opponent, (int) HEADER_PT, CARD_CHIP_PT, true, m -> false, m -> null, this::openProfile, null, null, m -> false, true);
        card.setName(NAME_OPPONENT_CARD);
        opponentSlot.removeAll();
        opponentSlot.add(card, BorderLayout.CENTER);
        opponentCard = card;
        opponentSlot.setVisible(true);
        opponentSlot.revalidate();
        opponentSlot.repaint();
    }

    private void dropOpponentCard()
    {
        opponentSlot.removeAll();
        opponentCard = null;
        opponentCardKey = null;
        opponentRankIdx = -1;
        opponentSlot.setVisible(false);
        opponentSlot.revalidate();
        opponentSlot.repaint();
    }

    private void openProfile(String name)
    {
        Consumer<String> open = onOpenProfile;
        if (open != null && name != null) open.accept(name);
    }

    /** Asks the rank seam; the answer lands on the card while it still shows that opponent. */
    private void askRank(String name, String key)
    {
        Function<String, CompletableFuture<String>> lookup = rankLookup;
        if (lookup == null) return;
        CompletableFuture<String> answer = safeLoad(() -> lookup.apply(name));
        if (answer == null) return;
        answer.whenComplete((tier, ex) -> SwingUtilities.invokeLater(() ->
        {
            if (ex != null || tier == null || series == null || !key.equals(opponentCardKey)) return;
            int idx = RankUtils.rankIndexForTier(tier);
            if (idx < 0 || idx == opponentRankIdx) return;
            opponentRankIdx = idx;
            placeOpponentCard(series, series.hasNamedOpponent() ? series.opponentName : TournamentSeries.UNKNOWN_OPPONENT);
        }));
    }

    /** The event's style as the card's lit chip; none when the event names no bucket the lobby knows. */
    static Set<Style> stylesOf(String category)
    {
        Style style = DashboardPanel.styleForBucket(category);
        return style == null ? EnumSet.noneOf(Style.class) : EnumSet.of(style);
    }

    /** The event's build as the card's lit chip; Main when unset or unknown. */
    static Set<BuildType> buildsOf(String build)
    {
        String b = build == null ? "" : build.trim().toUpperCase(java.util.Locale.ROOT);
        for (BuildType t : BuildType.values())
        {
            if (t.name().equals(b)) return EnumSet.of(t);
        }
        return EnumSet.of(BuildType.MAIN);
    }

    private void renderStandings(List<StandingsRow> rows, int myRank)
    {
        renderStandings(standingsBody, "standings-row-", "standings-rank-", rows, myRank);
    }

    private void renderStandings(StandingsBody body, String rowPrefix, String rankPrefix, List<StandingsRow> rows, int myRank)
    {
        body.removeAll();
        String self = selfNameSupplier.get();
        String selfKey = self == null ? null : NameUtils.canonicalKey(self);
        Component mine = null;
        body.rows.clear();
        if (rows == null || rows.isEmpty())
        {
            JLabel empty = new JLabel("No standings yet.");
            empty.setFont(empty.getFont().deriveFont(Font.PLAIN, BODY_PT));
            empty.setForeground(MUTED);
            body.add(empty);
        }
        int shown = 0;
        for (StandingsRow r : rows == null ? Collections.<StandingsRow>emptyList() : rows)
        {
            if (shown++ >= STANDINGS_MAX_ROWS) break;
            boolean me = (selfKey != null && r.displayName != null && selfKey.equals(NameUtils.canonicalKey(r.displayName)))
                || (myRank > 0 && r.rank == myRank && selfKey == null);
            JPanel line = new JPanel(new BorderLayout(StandingsBody.GAP, 0));
            line.setName(rowPrefix + r.rank);
            line.setAlignmentX(LEFT_ALIGNMENT);
            line.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
            if (me)
            {
                line.setBackground(new Color(0x1f, 0x3a, 0x2a));
                line.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GREEN), BorderFactory.createEmptyBorder(1, 4, 1, 4)));
            }
            String label = r.rank + " · " + r.displayName + (me ? " (you)" : "");
            String removed = r.removedLabel();
            String right = rightCellText(r);
            JLabel left = new JLabel(label);
            if (!removed.isEmpty()) left.setForeground(MUTED);
            JLabel pts = new JLabel(right);
            pts.setForeground(removed.isEmpty() ? Color.WHITE : MUTED);
            JLabel rank = null;
            if (r.rankLabel != null)
            {
                rank = new JLabel(r.rankLabel);
                rank.setName(rankPrefix + r.rank);
                rank.setForeground(RankUtils.getRankColor(r.rankLabel));
                JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, StandingsBody.GAP, 0));
                east.setOpaque(false);
                east.add(rank);
                east.add(pts);
                line.add(east, BorderLayout.EAST);
            }
            else
            {
                line.add(pts, BorderLayout.EAST);
            }
            line.add(left, BorderLayout.CENTER);
            body.add(line);
            body.rows.add(new StandingsBody.Row(line, left, pts, rank, me));
            if (me) mine = line;
        }
        body.applyPt(STANDINGS_MAX_PT);
        body.revalidate();
        body.repaint();
        if (mine != null)
        {
            final Component target = mine;
            javax.swing.SwingUtilities.invokeLater(() -> body.scrollRectToVisible(target.getBounds()));
        }
    }

    /** The live leaderboard's rows, as wide as the scroll pane's viewport,
     *  all at the largest size from {@link #STANDINGS_MAX_PT} down to
     *  {@link #STANDINGS_MIN_PT} at which every row fits. */
    static final class StandingsBody extends JPanel implements javax.swing.Scrollable
    {
        static final int GAP = 6;

        static final class Row
        {
            final JPanel line;
            final JLabel left;
            final JLabel right;
            /** The rank label beside the points, {@code null} when the row has none. */
            final JLabel rank;
            final boolean me;

            Row(JPanel line, JLabel left, JLabel right, JLabel rank, boolean me)
            {
                this.line = line;
                this.left = left;
                this.right = right;
                this.rank = rank;
                this.me = me;
            }
        }

        final List<Row> rows = new ArrayList<>();
        private final RowTextFit fit = new RowTextFit();
        private float appliedPt = -1f;

        @Override
        public void doLayout()
        {
            int width = getWidth();
            if (width > 0 && !rows.isEmpty())
            {
                int usable = width - getInsets().left - getInsets().right;
                int pt = fit.largestFitting((int) STANDINGS_MIN_PT, (int) STANDINGS_MAX_PT, p -> allFit(p, usable));
                applyPt(pt);
            }
            super.doLayout();
        }

        private boolean allFit(int pt, int usable)
        {
            for (Row r : rows)
            {
                java.awt.Insets in = r.line.getInsets();
                int needed = in.left + in.right + GAP + fit.textWidth(r.left.getText(), font(pt, r.me)) + fit.textWidth(r.right.getText(), font(pt, false));
                if (r.rank != null) needed += GAP + fit.textWidth(r.rank.getText(), font(pt, false));
                if (needed > usable) return false;
            }
            return true;
        }

        void applyPt(float pt)
        {
            if (pt == appliedPt && !rows.isEmpty() && rows.get(0).left.getFont().getSize2D() == pt) return;
            appliedPt = pt;
            for (Row r : rows)
            {
                r.left.setFont(font((int) pt, r.me));
                r.right.setFont(font((int) pt, false));
                if (r.rank != null) r.rank.setFont(font((int) pt, false));
                int height = Math.max(r.left.getPreferredSize().height, r.right.getPreferredSize().height)
                    + r.line.getInsets().top + r.line.getInsets().bottom;
                r.line.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            }
        }

        private static Font font(int pt, boolean bold)
        {
            return RowTextFit.baseFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) pt);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) { return 64; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ---------------------------------------------------------------- actions
    /** The one report dialog, for the active footer and every card's
     *  button alike: the answer goes out on the existing
     *  {@code tournament/report_problem}. */
    private void onReportProblem(String tournamentId)
    {
        if (tournamentId == null || tournamentId.isEmpty()) return;
        String text = JOptionPane.showInputDialog(this,
            "Describe the issue (up to 280 characters). It goes straight to this tournament's host on Discord:",
            "Report an issue", JOptionPane.PLAIN_MESSAGE);
        reportProblem(tournamentId, text);
    }

    /** Package-private so tests can skip the dialog: the active event's report. */
    void reportProblem(String text)
    {
        if (active == null) return;
        reportProblem(active.tournamentId, text);
    }

    /** One sender for both surfaces. Blank text or id sends nothing; the
     *  Discord-login gate lives on the buttons and on the server, whose
     *  answer ({@link #REPORT_LOGIN_HINT} / {@link #REPORT_RATE_LIMITED_TEXT})
     *  becomes a banner. */
    void reportProblem(String tournamentId, String text)
    {
        if (tournamentId == null || tournamentId.isEmpty() || text == null || text.trim().isEmpty()) return;
        service.reportProblem(tournamentId, text.trim());
    }

    // ---------------------------------------------------------------- rules
    /** A card's Rules button: the in-panel page when the entry carries a
     *  rules block, else the event's {@code rules_url} in the browser (an
     *  older backend, or a junk block — {@code TournamentRules.fromJson}
     *  already read that as absent). */
    void openRules(TournamentSummary t)
    {
        if (t == null) return;
        String url = t.rulesUrl == null ? "" : t.rulesUrl;
        if (t.rules == null)
        {
            if (!url.isEmpty()) linkOpener.accept(url);
            return;
        }
        if (rulesDialog != null) cardHost.remove(rulesDialog);
        if (!CARD_RULES.equals(currentCard)) rulesReturnCard = currentCard;
        rulesDialog = new TournamentRulesDialog(t.name, t.rules, this::closeRules, () -> { if (!url.isEmpty()) linkOpener.accept(url); });
        cardHost.add(rulesDialog, CARD_RULES);
        showCard(CARD_RULES);
        revalidate();
        repaint();
    }

    /** The active footer's Rules: the active event's listed entry, so it
     *  gets the same page as its card. */
    private void openRulesForActive()
    {
        openRules(active == null ? null : summaryFor(active.tournamentId));
    }

    /** The footer's Rules works once the running event's entry is known. */
    private void refreshActiveRules()
    {
        activeRulesBtn.setEnabled(active != null && summaryFor(active.tournamentId) != null);
    }

    private void closeRules()
    {
        showCard(CARD_ACTIVE.equals(rulesReturnCard) && active != null ? CARD_ACTIVE : CARD_LIST);
        revalidate();
        repaint();
    }

    /** Removes the Rules page whenever another page shows, so the hidden page never sizes the tab. */
    private void dropRulesPage()
    {
        if (rulesDialog == null) return;
        cardHost.remove(rulesDialog);
        rulesDialog = null;
    }

    /** The listed entry (else the registration row) for an id; {@code null} when unknown. */
    private TournamentSummary summaryFor(String tournamentId)
    {
        if (tournamentId == null) return null;
        for (TournamentSummary t : events) if (tournamentId.equals(t.tournamentId)) return t;
        for (TournamentSummary t : myRegistrations) if (tournamentId.equals(t.tournamentId)) return t;
        return null;
    }

    private void resubscribe()
    {
        if (!showing || active == null) return;
        if (active.tournamentId.equals(subscribedId)) return;
        unsubscribe();
        subscribedId = active.tournamentId;
        service.subscribe(subscribedId);
    }

    private void unsubscribe()
    {
        if (subscribedId == null) return;
        String id = subscribedId;
        subscribedId = null;
        service.unsubscribe(id);
    }

    private void onTick()
    {
        if (CARD_LIST.equals(currentCard))
        {
            for (Runnable refresh : liveTimes) refresh.run();
        }
        if (active != null && CARD_ACTIVE.equals(currentCard))
        {
            renderEta();
            renderStatus();
            if (roundEndBox.isVisible() && roundEndRespondByMs > 0)
            {
                long left = Math.max(0L, (roundEndRespondByMs - nowMs.getAsLong()) / 1000L);
                if (left == 0) roundEndBox.setVisible(false);
            }
        }
        maybeSignalInCombat();
    }

    private void maybeSignalInCombat()
    {
        if (active == null || series == null || !series.isOpen()) return;
        if (!safeInCombat()) return;
        long now = nowMs.getAsLong();
        if (now - lastInCombatSentMs < IN_COMBAT_MIN_INTERVAL_MS) return;
        lastInCombatSentMs = now;
        service.inCombat(active.tournamentId, series.seriesId);
    }

    /** Test hook: one ticker beat without waiting for the Swing timer. */
    void tickForTest()
    {
        onTick();
    }

    // ---------------------------------------------------------------- listener (EDT)
    @Override
    public void onTournamentList(List<TournamentSummary> tournaments, long nowEpochS)
    {
        events = tournaments == null ? Collections.<TournamentSummary>emptyList() : new ArrayList<>(tournaments);
        renderList();
        refreshActiveRules();
    }

    @Override
    public void onRegistered(TournamentSummary tournament, String registrationStatus)
    {
        pendingRegisterId = null;
        if (tournament != null) minGamesRefused.remove(tournament.tournamentId);
        showBanner(registeredBanner(tournament));
        service.list();
        service.status();
    }

    /** "Registered for X. Meet on W578 at PvP Arena entrance when round 1 opens." — the meeting line
     *  when the push carries one, else the sentence about the round's own message. */
    static String registeredBanner(TournamentSummary t)
    {
        String head = "Registered for " + escape(t.name) + ". ";
        if (t.meetingWorld != null && t.meetingPlace != null)
        {
            return head + "Meet on " + escape(t.meetingWorld) + " at " + escape(t.meetingPlace) + " when round 1 opens.";
        }
        if (t.meetingWorld != null) return head + "Meet on " + escape(t.meetingWorld) + " when round 1 opens.";
        return head + "Your opponent, world and meeting place arrive here when each round opens.";
    }

    @Override
    public void onWithdrawn(String tournamentId, String status)
    {
        showBanner("You withdrew from the tournament.");
        if (active != null && active.tournamentId.equals(tournamentId)) clearActive();
        service.list();
        service.status();
    }

    @Override
    public void onTournamentState(List<TournamentSummary> registrations, TournamentActive state)
    {
        myRegistrations = registrations == null ? Collections.<TournamentSummary>emptyList() : new ArrayList<>(registrations);
        if (state == null || !state.isRunning())
        {
            if (active != null) clearActive();
            renderList();
            return;
        }
        active = state;
        series = state.series != null && state.series.isOpen() ? state.series : null;
        if (series != null) waitUntilNextRound = false;
        bye = state.bye;
        breakUntilS = state.breakUntil;
        roundEndsAtMs = state.etaS >= 0 ? nowMs.getAsLong() + state.etaS * 1000L : 0L;
        renderActive();
        renderStandings(state.standings, state.myRank);
        resubscribe();
        renderList();
    }

    @Override
    public void onStandings(TournamentStandings standings)
    {
        if (active == null || !active.tournamentId.equals(standings.tournamentId)) return;
        if (standings.round > 0 && standings.round != active.round)
        {
            active = new TournamentActive(active.tournamentId, active.name, standings.status, standings.round, standings.rounds, standings.deadlineAt,
                standings.etaS, standings.breakUntil, series, bye, standings.rows, active.myRank, active.myPoints);
        }
        breakUntilS = standings.breakUntil;
        if (standings.etaS >= 0) roundEndsAtMs = nowMs.getAsLong() + standings.etaS * 1000L;
        else if (standings.breakUntil > 0) roundEndsAtMs = 0L;
        roundExtensions = Math.max(0, standings.extended);
        int myRank = -1;
        String self = selfNameSupplier.get();
        String selfKey = self == null ? null : NameUtils.canonicalKey(self);
        for (StandingsRow r : standings.rows)
        {
            if (selfKey != null && r.displayName != null && selfKey.equals(NameUtils.canonicalKey(r.displayName))) myRank = r.rank;
        }
        setWrapped(activeHeader, active.name + " · Round " + active.round + "/" + active.rounds);
        renderEta();
        renderStandings(standings.rows, myRank > 0 ? myRank : active.myRank);
        if (standings.isFinished() || "cancelled".equals(standings.status))
        {
            // the dedicated finished / cancelled pushes drive the exit; the standings just stop moving
            setWrapped(activeEta, standings.isFinished() ? "Final standings" : "Cancelled");
        }
    }

    @Override
    public void onMatchAssigned(TournamentSeries s)
    {
        if (active == null || !active.tournamentId.equals(s.tournamentId))
        {
            active = new TournamentActive(s.tournamentId, active != null ? active.name : s.tournamentId, "running", s.round, active != null ? active.rounds : 0,
                s.deadlineAt, -1, 0L, s, false, active != null ? active.standings : Collections.<StandingsRow>emptyList(), -1, -1);
        }
        else
        {
            active = new TournamentActive(active.tournamentId, active.name, "running", s.round > 0 ? s.round : active.round, active.rounds, s.deadlineAt, -1, 0L, s, false,
                active.standings, active.myRank, active.myPoints);
        }
        series = s;
        bye = false;
        waitUntilNextRound = false;
        breakUntilS = 0L;
        roundEndsAtMs = s.deadlineAt > 0 ? s.deadlineAt * 1000L : 0L;
        roundExtensions = 0;
        roundEndBox.setVisible(false);
        showBanner("Round " + s.round + ": you face " + escape(s.opponentName) + " on " + s.worldLabel() + " at " + escape(s.meetingPlace == null ? "the arranged spot" : s.meetingPlace));
        renderActive();
        resubscribe();
        service.status();
    }

    /** The match is over: the card, the world and place lines and the status line go until the next round opens. */
    @Override
    public void onOpponentHighlightClear(String tournamentId, String seriesId)
    {
        if (active == null || series == null) return;
        if (tournamentId == null || tournamentId.isEmpty() || !tournamentId.equals(active.tournamentId)) return;
        if (seriesId != null && !seriesId.isEmpty() && !seriesId.equals(series.seriesId)) return;
        series = null;
        waitUntilNextRound = true;
        renderActive();
    }

    @Override
    public void onBye(String tournamentId, int round)
    {
        if (active == null || !active.tournamentId.equals(tournamentId)) return;
        series = null;
        bye = true;
        waitUntilNextRound = false;
        roundExtensions = 0;
        // a bye is a point, never a rated game
        showBanner("Round " + round + ": you have a bye — it counts as a win (no rating change).");
        renderActive();
    }

    @Override
    public void onRoundEndCheck(String tournamentId, int round, String seriesId, String opponentName, long respondByEpochS, String message)
    {
        if (active == null || !active.tournamentId.equals(tournamentId)) return;
        roundEndRespondByMs = respondByEpochS > 0 ? respondByEpochS * 1000L : nowMs.getAsLong() + 30_000L;
        String sentence = message == null || message.trim().isEmpty() ? ROUND_END_SUBMIT_TEXT : message.trim();
        roundEndLabel.setText(TournamentInfoCard.wrapHtml(roundEndLabel.getFont(), TournamentInfoCard.TEXT_WIDTH_PX,
            roundEndTitle(round, opponentName), sentence));
        roundEndBox.setVisible(true);
        showCard(CARD_ACTIVE);
        revalidate();
        repaint();
    }

    static String roundEndTitle(int round, String opponentName)
    {
        String opponent = opponentName == null || opponentName.trim().isEmpty() ? "your opponent" : opponentName;
        return "Round " + round + " has ended — your match vs " + opponent + " has no result yet";
    }

    @Override
    public void onRemoved(String tournamentId, String status, String reason, int round)
    {
        String what;
        switch (status == null ? "" : status)
        {
            case "dq": what = "You were disqualified"; break;
            case "kicked": what = "You were removed by the organizer"; break;
            case "dnf": what = "You were marked DNF"; break;
            case "withdrawn": what = "You withdrew"; break;
            case "dropped_unpaid": what = "You were dropped from the tournament (buy-in not paid)"; break;
            case "dropped_gear": what = null; break;
            default: what = "You were removed";
        }
        if (what == null)
        {
            showBanner("You were removed — your kit didn't match the required set when " + (round > 0 ? "round " + round : "the round") + " started.");
        }
        else
        {
            showBanner(what + (round > 0 ? " in round " + round : "") + (reason == null || reason.isEmpty() ? "." : " — " + escape(reason) + "."));
        }
        if (active == null || tournamentId == null || tournamentId.isEmpty() || tournamentId.equals(active.tournamentId)) clearActive();
        service.list();
    }

    @Override
    public void onCancelled(String tournamentId, String reason)
    {
        showBanner("Tournament cancelled" + (reason == null || reason.isEmpty() ? "." : ": " + escape(reason)));
        if (active == null || tournamentId == null || tournamentId.isEmpty() || tournamentId.equals(active.tournamentId)) clearActive();
        service.list();
    }

    /** The finish: the banner, and the final standings stay on screen until Back. */
    @Override
    public void onFinished(String tournamentId, JsonObject winners, List<StandingsRow> standings)
    {
        showBanner("Tournament finished" + winnersText(winners) + placeText(standings, selfNameSupplier.get()));
        if (active == null || tournamentId == null || tournamentId.isEmpty() || tournamentId.equals(active.tournamentId))
        {
            showFinalStandings(standings);
        }
        service.list();
    }

    private void showFinalStandings(List<StandingsRow> standings)
    {
        String name = active == null ? null : active.name;
        unsubscribe();
        active = null;
        series = null;
        bye = false;
        waitUntilNextRound = false;
        breakUntilS = 0L;
        roundEndsAtMs = 0L;
        roundExtensions = 0;
        roundEndBox.setVisible(false);
        finalView = true;
        dropOpponentCard();
        setWrapped(activeHeader, name == null ? " " : name);
        setWrapped(activeEta, "Final standings");
        activeMatch.setVisible(false);
        activeWhere.setVisible(false);
        activeStatus.setVisible(false);
        activePairRow.setVisible(false);
        withdrawActiveBtn.setVisible(false);
        backActiveBtn.setVisible(true);
        renderStandings(standings, -1);
        showCard(CARD_ACTIVE);
        placeGearCard();
    }

    private void leaveFinalView()
    {
        if (!finalView) return;
        finalView = false;
        clearActive();
    }

    /** The winners clause of the finished banner — the placed winners and
     *  the random draw, mirroring the finished message's
     *  "Winners: … · Random draw: …". {@code "."} when nobody is named. */
    static String winnersText(JsonObject winners)
    {
        if (winners == null) return ".";
        List<String> top = displayNames(winners.get("top"));
        if (top.isEmpty()) return ".";
        List<String> random = displayNames(winners.get("random"));
        String drawn = random.isEmpty() ? "" : " · random draw: " + String.join(", ", random);
        return " — winners: " + String.join(", ", top) + drawn + ".";
    }

    /** The {@code display_name}s of a winners array; non-objects and rows
     *  without a name are skipped rather than printed. */
    private static List<String> displayNames(JsonElement arr)
    {
        List<String> names = new ArrayList<>();
        if (arr == null || !arr.isJsonArray()) return names;
        for (JsonElement e : (JsonArray) arr)
        {
            if (e == null || !e.isJsonObject()) continue;
            JsonElement n = e.getAsJsonObject().get("display_name");
            if (n != null && n.isJsonPrimitive()) names.add(n.getAsString());
        }
        return names;
    }

    static String placeText(List<StandingsRow> standings, String selfName)
    {
        String selfKey = NameUtils.canonicalKey(selfName);
        if (standings == null || selfKey == null) return "";
        for (StandingsRow r : standings)
        {
            if (r == null || r.displayName == null || r.rank <= 0) continue;
            if (selfKey.equals(NameUtils.canonicalKey(r.displayName)))
            {
                return " You finished #" + r.rank + " with " + pointsText(r.points) + " point" + (r.points == 1.0 ? "" : "s") + ".";
            }
        }
        return "";
    }

    @Override
    public void onProblemAck(String tournamentId)
    {
        showBanner("Problem report received — the organizer has been told.");
    }

    @Override
    public void onTournamentError(String code, String message, String cmd)
    {
        if (GEAR_STATUS_CMD.equals(cmd)) return;
        String pressed = null;
        if (cmd == null || cmd.isEmpty() || REGISTER_CMD.equals(cmd))
        {
            pressed = pendingRegisterId;
            pendingRegisterId = null;
        }
        if (MIN_GAMES_CODE.equals(code))
        {
            if (pressed != null)
            {
                minGamesRefused.add(pressed);
                renderList();
            }
            return;
        }
        showBanner(friendlyError(code, message, cmd));
    }

    static String friendlyError(String code, String message)
    {
        return friendlyError(code, message, "");
    }

    /** {@code cmd} is the server's echo of the rejected cmd. The two answers
     *  a report can get (parsed defensively): not linked to Discord, whatever
     *  its code spelling or a message saying so, → the login hint; the
     *  per-player-per-event rate limit ({@code REPORT_RATE_LIMITED}, or the
     *  protocol's {@code RATE_LIMITED} on the report cmd) → "already reported". */
    static String friendlyError(String code, String message, String cmd)
    {
        boolean report = cmd == null || cmd.isEmpty() || REPORT_CMD.equals(cmd);
        if (report && message != null && message.toLowerCase(java.util.Locale.ROOT).contains("not linked")) return REPORT_LOGIN_HINT;
        switch (code == null ? "" : code)
        {
            case "NOT_LINKED":
            case "DISCORD_NOT_LINKED":
            case "DISCORD_REQUIRED":
            case "TOURNAMENT_DISCORD_REQUIRED": return REPORT_LOGIN_HINT;
            case "REPORT_RATE_LIMITED": return REPORT_RATE_LIMITED_TEXT;
            case "RATE_LIMITED": return report ? REPORT_RATE_LIMITED_TEXT : "Too many requests — give it a moment and try again.";
            case "TOURNAMENT_FULL": return "Tournament full.";
            case "TOURNAMENT_REGISTRATION_CLOSED": return "Registration closed.";
            case "TOURNAMENT_PEAK_OUT_OF_RANGE": return "Your rank is outside this tournament's range.";
            case "TOURNAMENT_ALREADY_REGISTERED": return "You are already registered.";
            case "TOURNAMENT_NOT_REGISTERED": return "You are not registered.";
            case "TOURNAMENT_PLUGIN_REQUIRED": return "Plugin-verified accounts only.";
            case "TOURNAMENT_MIN_GAMES": return "You need more rated games before you can register.";
            case "TOURNAMENT_BANNED": return QueueText.BANNED;
            case "TOURNAMENT_NOT_FOUND": return "Tournament not found.";
            case "TOURNAMENT_STATE": return message == null || message.isEmpty() ? "That is not possible right now." : escape(message);
            case "TOURNAMENT_PLUGIN_UPDATE_REQUIRED": return message == null || message.isEmpty() ? UPDATE_REQUIRED_TEXT : escape(message);
            default: return "Tournaments: " + (message == null || message.isEmpty() ? code : escape(message));
        }
    }

    private void clearActive()
    {
        unsubscribe();
        active = null;
        series = null;
        bye = false;
        waitUntilNextRound = false;
        finalView = false;
        breakUntilS = 0L;
        roundEndsAtMs = 0L;
        roundExtensions = 0;
        roundEndBox.setVisible(false);
        dropOpponentCard();
        standingsBody.removeAll();
        standingsBody.rows.clear();
        activePairRow.setVisible(true);
        withdrawActiveBtn.setVisible(true);
        backActiveBtn.setVisible(false);
        showCard(CARD_LIST);
        renderList();
    }
}
