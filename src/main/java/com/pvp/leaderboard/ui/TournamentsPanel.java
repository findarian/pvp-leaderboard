package com.pvp.leaderboard.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.tournament.NoOpTournamentService;
import com.pvp.leaderboard.tournament.StandingsRow;
import com.pvp.leaderboard.tournament.TournamentActive;
import com.pvp.leaderboard.tournament.TournamentEventListener;
import com.pvp.leaderboard.tournament.TournamentSeries;
import com.pvp.leaderboard.tournament.TournamentService;
import com.pvp.leaderboard.tournament.TournamentStandings;
import com.pvp.leaderboard.tournament.TournamentSummary;
import com.pvp.leaderboard.util.NameUtils;
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
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
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
    /** The in-panel Rules dialog (set 6). */
    public static final String CARD_RULES = "tournaments-rules";
    /** Shown in place of the tab's view while the player is not logged into the game. */
    public static final String CARD_LOGGED_OUT = "tournaments-logged-out";
    public static final String NAME_LOGGED_OUT_TITLE = "tournaments-logged-out-title";
    public static final String NAME_LOGGED_OUT_NOTICE = "tournaments-logged-out-notice";
    /** The list card's status line (set 7: also says "Connecting…"). */
    public static final String NAME_LIST_STATUS = "tournaments-list-status";
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
    static final long IN_COMBAT_MIN_INTERVAL_MS = 30_000L;
    private static final int STANDINGS_MAX_ROWS = 40;
    /** The standings rows' size: the largest in this range at which every row fits. */
    static final float STANDINGS_MAX_PT = 16f;
    static final float STANDINGS_MIN_PT = 12f;
    static final float HEADER_PT = 16f;
    static final float BODY_PT = 15f;
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
    private final JPanel listBody = new JPanel();
    private final JLabel listStatus = new JLabel("Loading…", SwingConstants.LEFT);
    private final JLabel activeHeader = new JLabel(" ");
    private final JLabel activeEta = new JLabel(" ");
    private final JLabel activeMatch = new JLabel(" ");
    private final JLabel activeWhere = new JLabel(" ");
    private final JLabel activeStatus = new JLabel(" ");
    private final JPanel roundEndBox = new JPanel();
    private final JLabel roundEndLabel = new JLabel(" ");
    private final StandingsBody standingsBody = new StandingsBody();
    private final JButton withdrawActiveBtn = tabButton("Withdraw");
    /** The active footer's report button — gated like every card's (set 6). */
    private final JButton reportActiveBtn = tabButton(TournamentInfoCard.REPORT_LABEL);
    /** The running event's own Rules; enabled once its list entry is known. */
    private final JButton activeRulesBtn = tabButton("Rules");
    private final Timer ticker;
    /** The open Rules dialog, if any, and the card to return to from it. */
    private TournamentRulesDialog rulesDialog;
    private String rulesReturnCard = CARD_LIST;
    private final JPanel listGearSlot = new JPanel(new BorderLayout());
    private final JPanel activeGearSlot = new JPanel(new BorderLayout());
    private javax.swing.JComponent gearCard;
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

    /** The dashboard wires {@code DiscordAuthService::isLoggedIn} — the
     *  Report gate (operator decision 2026-09-22: a report is accepted only
     *  from a player logged in with Discord, so the button follows the
     *  plugin's own login state). {@code null} = unwired = logged out. */
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
     *  standings; a hidden one unsubscribes. */
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

    /** The transport's connect hook (set 7): a showing tab re-asks for
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
        footer.add(TournamentInfoCard.pairRow(reportActiveBtn, activeRulesBtn));
        footer.add(Box.createVerticalStrut(4));
        withdrawActiveBtn.setName("tournaments-active-withdraw");
        withdrawActiveBtn.setBackground(RED);
        withdrawActiveBtn.setForeground(new Color(0xff, 0xb3, 0xb3));
        withdrawActiveBtn.addActionListener(e -> { if (active != null) service.withdraw(active.tournamentId); });
        footer.add(withdrawActiveBtn);
        card.add(footer, BorderLayout.SOUTH);
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
        cards.show(cardHost, gameLoggedInNow() ? name : CARD_LOGGED_OUT);
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
        banner.setVisible(true);
        revalidate();
        repaint();
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

    /** One {@link TournamentInfoCard} per listed event (set 6); its time
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

    // ---------------------------------------------------------------- active rendering
    private void renderActive()
    {
        if (active == null)
        {
            showCard(CARD_LIST);
            return;
        }
        setWrapped(activeHeader, active.name + " · Round " + active.round + "/" + active.rounds);
        if (series != null)
        {
            setWrapped(activeMatch, "Your match vs " + series.opponentName);
            setWrapped(activeWhere, "World · place: " + series.worldLabel() + " · " + (series.meetingPlace == null ? "?" : series.meetingPlace));
        }
        else if (bye)
        {
            setWrapped(activeMatch, "Bye this round (counts as a win)");
            setWrapped(activeWhere, " ");
        }
        else
        {
            setWrapped(activeMatch, "Waiting for the next round");
            setWrapped(activeWhere, " ");
        }
        withdrawActiveBtn.setVisible(true);
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

    private void renderStandings(List<StandingsRow> rows, int myRank)
    {
        standingsBody.removeAll();
        String self = selfNameSupplier.get();
        String selfKey = self == null ? null : NameUtils.canonicalKey(self);
        Component mine = null;
        standingsBody.rows.clear();
        if (rows == null || rows.isEmpty())
        {
            JLabel empty = new JLabel("No standings yet.");
            empty.setFont(empty.getFont().deriveFont(Font.PLAIN, BODY_PT));
            empty.setForeground(MUTED);
            standingsBody.add(empty);
        }
        int shown = 0;
        for (StandingsRow r : rows == null ? Collections.<StandingsRow>emptyList() : rows)
        {
            if (shown++ >= STANDINGS_MAX_ROWS) break;
            boolean me = (selfKey != null && r.displayName != null && selfKey.equals(NameUtils.canonicalKey(r.displayName)))
                || (myRank > 0 && r.rank == myRank && selfKey == null);
            JPanel line = new JPanel(new BorderLayout(StandingsBody.GAP, 0));
            line.setName("standings-row-" + r.rank);
            line.setAlignmentX(LEFT_ALIGNMENT);
            line.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
            if (me)
            {
                line.setBackground(new Color(0x1f, 0x3a, 0x2a));
                line.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GREEN), BorderFactory.createEmptyBorder(1, 4, 1, 4)));
            }
            String label = r.rank + " · " + r.displayName + (me ? " (you)" : "");
            String removed = r.removedLabel();
            String right = removed.isEmpty() ? standingsScore(r) : removed;
            JLabel left = new JLabel(label);
            if (!removed.isEmpty()) left.setForeground(MUTED);
            JLabel pts = new JLabel(right);
            pts.setForeground(removed.isEmpty() ? Color.WHITE : MUTED);
            line.add(left, BorderLayout.CENTER);
            line.add(pts, BorderLayout.EAST);
            standingsBody.add(line);
            standingsBody.rows.add(new StandingsBody.Row(line, left, pts, me));
            if (me) mine = line;
        }
        standingsBody.applyPt(STANDINGS_MAX_PT);
        standingsBody.revalidate();
        standingsBody.repaint();
        if (mine != null)
        {
            final Component target = mine;
            javax.swing.SwingUtilities.invokeLater(() -> standingsBody.scrollRectToVisible(target.getBounds()));
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
            final boolean me;

            Row(JPanel line, JLabel left, JLabel right, boolean me)
            {
                this.line = line;
                this.left = left;
                this.right = right;
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
     *  button alike (set 6): the answer goes out on the existing
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

    // ---------------------------------------------------------------- rules (set 6)
    /** A card's Rules button: the in-panel dialog when the entry carries a
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
        if (rulesDialog != null)
        {
            cardHost.remove(rulesDialog);
            rulesDialog = null;
        }
        showCard(CARD_ACTIVE.equals(rulesReturnCard) && active != null ? CARD_ACTIVE : CARD_LIST);
        revalidate();
        repaint();
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
        showBanner("✓ Registered for " + escape(tournament.name) + ". Your opponent, world and meeting place arrive here when each round opens.");
        service.list();
        service.status();
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
        breakUntilS = 0L;
        roundEndsAtMs = s.deadlineAt > 0 ? s.deadlineAt * 1000L : 0L;
        roundExtensions = 0;
        roundEndBox.setVisible(false);
        showBanner("⚔ Round " + s.round + ": you face " + escape(s.opponentName) + " on " + s.worldLabel() + " at " + escape(s.meetingPlace == null ? "the arranged spot" : s.meetingPlace));
        renderActive();
        resubscribe();
        service.status();
    }

    @Override
    public void onBye(String tournamentId, int round)
    {
        if (active == null || !active.tournamentId.equals(tournamentId)) return;
        series = null;
        bye = true;
        roundExtensions = 0;
        // G-7: bye_dm says "(no rating change)" and so must the plugin —
        // a bye is a point, never a rated game.
        showBanner("🎟 Round " + round + ": you have a bye — it counts as a win (no rating change).");
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
            // G-7: removed_dm's _REMOVED_WORDING["dropped_unpaid"]; without
            // it an unpaid drop read as the generic "You were removed".
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

    @Override
    public void onFinished(String tournamentId, JsonObject winners, List<StandingsRow> standings)
    {
        // G-4: finished_dm names the random-draw winners and the player's
        // own place; the website event view shows both too.
        showBanner("🏁 Tournament finished" + winnersText(winners) + placeText(standings, selfNameSupplier.get()));
        if (active == null || tournamentId == null || tournamentId.isEmpty() || tournamentId.equals(active.tournamentId)) clearActive();
        service.list();
    }

    /** The winners clause of the finished banner — the placed winners and,
     *  since G-4, the random draw, mirroring {@code finished_dm}'s
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

    /** {@code cmd} is the server's echo of the rejected cmd. Set 6 reads
     *  the two answers a report can get (parsed defensively — the backend
     *  half is in flight): not linked to Discord, whatever its code spelling
     *  or a message saying so, → the login hint; the per-player-per-event
     *  rate limit ({@code REPORT_RATE_LIMITED}, or the protocol's
     *  {@code RATE_LIMITED} on the report cmd) → "already reported". */
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
            case "TOURNAMENT_BANNED": return "Your account is banned from matchmaking and tournaments. DM Toyco if this is a mistake.";
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
        breakUntilS = 0L;
        roundEndsAtMs = 0L;
        roundExtensions = 0;
        roundEndBox.setVisible(false);
        standingsBody.removeAll();
        showCard(CARD_LIST);
        renderList();
    }
}
