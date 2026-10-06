package com.pvp.leaderboard.ui;

import lombok.*;
import com.google.gson.*;
import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.queue.*;
import com.pvp.leaderboard.tournament.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import net.runelite.client.util.*;
import java.util.List;
import javax.swing.Timer;
import static com.pvp.leaderboard.util.JsonLenient.*;
import static com.pvp.leaderboard.ui.TournamentInfoCard.*;
import static com.pvp.leaderboard.ui.Ui.*;
import static java.awt.Font.*;
import static java.awt.BorderLayout.*;

public class TourneyPanel extends JPanel implements TournamentEventListener
{
    public static final String CARD_LIST = "tournaments-list";
    public static final String CARD_ACTIVE = "tournaments-active";
    /** The in-panel Rules page. */
    public static final String CARD_RULES = "tournaments-rules";
    /** Shown in place of the tab's view while the player is not logged into the game. */
    public static final String CARD_LOGIN = "tournaments-logged-out";
    /** The finished events, newest first, and one event's final standings. */
    public static final String CARD_PAST = "tournaments-past";
    public static final String CARD_PAST_STANDINGS = "tournaments-past-standings";
    static final String BOARD_FAILED = "Could not load the standings.";
    static final float HEADER_PT = 16f;
    static final float BODY_PT = 15f;

    final TourneySvc service;
    private final Supplier<String> regionSupplier;
    private volatile Supplier<String> selfNameSupplier = () -> null;
    private volatile BooleanSupplier inCombatProvider = () -> false;
    private volatile Consumer<String> linkOpener = TourneyPanel::browse;
    /** {@code DiscordLogin::isLoggedIn} once the dashboard wires it; unwired = logged out. */
    private volatile BooleanSupplier discordLoggedIn = () -> false;
    /** Whether the player is logged into the game; unwired = logged in. */
    private volatile BooleanSupplier gameLoggedIn = () -> true;
    /** The player's own match count per event bucket; unwired = unknown. */
    private volatile Function<String, Integer> matchCounts = bucket -> null;
    /** Opens a player's page in Player Lookup; unwired = nothing. */
    @Setter private volatile Consumer<String> onOpenProfile;
    /** The opponent card's rank by name; unwired = no rank. */
    @Setter private volatile Function<String, CompletableFuture<String>> rankLookup;
    /** The finished events for Past tournaments; unwired = cannot load. */
    @Setter private volatile IntFunction<CompletableFuture<JsonObject>> historyLoader;
    /** One finished event's standings; unwired = cannot load. */
    @Setter private volatile Function<String, CompletableFuture<JsonObject>> standingsLoader;
    /** The event whose Register was pressed and not yet answered. */
    private String pendingReg;
    /** Events whose registration was refused for their minimum, until the next connect. */
    private final Set<String> gamesRefused = new HashSet<>();
    private final LongSupplier nowMs;
    /** The viewer's zone for the list card's times (injected for tests). */
    private final ZoneId zone;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JLabel banner = new JLabel(" ");
    /** Whether a banner is up; it is hidden while the Rules page shows. */
    private boolean bannerShown;
    private final JPanel listBody = column();
    private final JLabel listStatus = new JLabel("Loading…", SwingConstants.LEFT);
    private final JLabel activeHeader = new JLabel(" ");
    private final JLabel activeEta = new JLabel(" ");
    private final JLabel activeMatch = new JLabel(" ");
    private final JLabel activeWhere = new JLabel(" ");
    private final JLabel activeStatus = new JLabel(" ");
    private final JPanel opponentSlot = new JPanel(new BorderLayout());
    /** The name the card shows, canonical; {@code null} while there is no card. */
    private String cardKey;
    /** The card's rank index, -1 while unknown. */
    private int opponentRankIdx = -1;
    private final JLabel roundEndBox = new JLabel(" ");
    private final BoardPanel boardPanel = new BoardPanel();
    private final JButton withdrawBtn = tabButton("Withdraw");
    /** The active footer's report button — gated like every card's. */
    private final JButton reportBtn = tabButton(REPORT_LABEL, "tournaments-report", () -> { if (this.activeId != null) onReport(this.activeId); });
    /** The running event's own Rules; enabled once its list entry is known. */
    private final JButton rulesBtn = tabButton("Rules", "tournaments-active-rules", this::openRulesForActive);
    /** The final standings' way back to the list. */
    private final JButton backBtn = tabButton("Back", "tournaments-active-back", this::clearActive);
    private JPanel activePair;
    private final Timer ticker;
    /** The open Rules page, if any, and the card to return to from it. */
    private JPanel rulesDialog;
    private String rulesBack = CARD_LIST;
    private final JPanel listGearSlot = new JPanel(new BorderLayout());
    private final JPanel activeSlot = new JPanel(new BorderLayout());
    private JComponent gearCard;
    private final JPanel pastBody = column();
    private final JLabel pastStatus = new JLabel(" ");
    private final JLabel pastTitle = new JLabel(" ");
    private final JLabel pastBoardMsg = new JLabel(" ");
    private final BoardPanel pastBoard = new BoardPanel();
    /** Counts the Past tournaments loads, so a late answer for an earlier visit is dropped. */
    private int pastGen;

    private List<Tourney> events = Collections.emptyList();
    private List<Tourney> myRegs = Collections.emptyList();
    private String activeId, activeName;
    private int round, rounds, myRank;
    private MatchSeries series;
    /** Local wall-clock ms at which the current round ends (server ETA at receipt), 0 = none. */
    private long roundEndMs;
    private long breakUntilS;
    private boolean bye;
    /** The match is over and the next round has not opened. */
    private boolean waitNext;
    private long respondByMs;
    private int extendCount;
    private long combatSentMs;
    private boolean showing;
    private String subscribedId;
    private String currentCard = CARD_LIST;

    public TourneyPanel(TourneySvc service, Supplier<String> regionSupplier)
    {
        this(service, regionSupplier, System::currentTimeMillis, ZoneId.systemDefault());
    }

    /** {@code zone} is the viewer's zone for the list card's times — the
     *  computer's own zone in game, a fixed one in tests. */
    public TourneyPanel(TourneySvc service, Supplier<String> regionSupplier, LongSupplier nowMs, ZoneId zone)
    {
        this.service = service;
        this.regionSupplier = regionSupplier;
        this.nowMs = nowMs;
        this.zone = zone;
        setLayout(new BorderLayout());
        setBorder(pad(4, 4, 4, 4));
        banner.setName("tournaments-banner");
        bold(banner, BODY_PT).setForeground(AMBER);
        banner.setVisible(false);
        add(banner, NORTH);
        cardHost.add(buildList(), CARD_LIST);
        cardHost.add(buildActive(), CARD_ACTIVE);
        cardHost.add(buildLogin(), CARD_LOGIN);
        cardHost.add(buildPast(), CARD_PAST);
        cardHost.add(buildPastStandingsCard(), CARD_PAST_STANDINGS);
        add(cardHost, CENTER);
        showCard(CARD_LIST);
        this.service.addListener(this);
        ticker = new Timer(1000, e -> onTick());
        ticker.start();
    }

    // ---------------------------------------------------------------- wiring
    public void setSelf(Supplier<String> selfName)
    {
        selfNameSupplier = selfName;
    }

    /** {@code FightMonitor::isInCombat} — drives the automatic in-combat signal. */
    public void setInCombatProvider(BooleanSupplier provider)
    {
        inCombatProvider = provider;
    }

    /** Test seam for the Rules link (defaults to RuneLite's browser opener). */
    public void setOpener(Consumer<String> opener)
    {
        linkOpener = opener == null ? TourneyPanel::browse : opener;
    }

    /** The dashboard wires {@code DiscordLogin::isLoggedIn} — the
     *  Report gate: a report is accepted only from a player logged in with
     *  Discord, so the button follows the plugin's own login state. */
    public void setDiscordLoginProvider(BooleanSupplier provider)
    {
        discordLoggedIn = provider;
    }

    /** The dashboard calls this from its login-state hook: every Report
     *  button re-evaluates locally — no {@code tournament/list}, no status. */
    public void onDiscordLoginChanged()
    {
        renderList();
        syncReport();
    }

    /** The player's own match count for an event bucket ({@code "nh"}, ...),
     *  {@code null} when unknown. */
    public void setMatchCountProvider(Function<String, Integer> provider)
    {
        matchCounts = provider;
        renderList();
    }

    /** The count source changed: the cards re-evaluate locally. */
    public void onCounts()
    {
        renderList();
    }

    /** Whether the player is logged into the game; unwired = logged in. */
    public void setGameCheck(BooleanSupplier provider)
    {
        gameLoggedIn = provider;
        refreshLogin();
    }

    /** The game's login state changed: the logged-out notice or the tab's own view. */
    public void refreshLogin()
    {
        showCard(currentCard);
    }

    /** The player's count in the event's bucket; {@code null} when unknown. */
    private Integer countFor(Tourney t)
    {
        Integer count = matchCounts.apply(t.category.trim());
        return count == null || count < 0 ? null : count;
    }

    private void syncReport()
    {
        boolean in = discordLoggedIn.getAsBoolean();
        reportBtn.setEnabled(in);
        reportBtn.setToolTipText(in ? REPORT_TOOLTIP : LOGIN_TIP);
    }

    private static void browse(String url)
    {
        try
        {
            LinkBrowser.browse(url);
        }
        catch (Throwable t)
        {
        }
    }

    /** The dashboard calls this when the sub-tab is shown / hidden: a shown
     *  panel re-syncs (status + list) and subscribes to the active event's
     *  standings; a hidden one unsubscribes and drops the Rules page. */
    public void setShowing(boolean visible)
    {
        showing = visible;
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
     *  sent: {@code SocketMgr.send} drops frames without a socket,
     *  and {@link #onConnected()} runs this again once it is up. */
    private void sync()
    {
        if (!service.isConnected())
        {
            setWrapped(listStatus, "Connecting…");
            return;
        }
        service.status();
        service.list();
        resubscribe();
    }

    /** The transport's connect hook: a showing tab re-asks for
     *  everything it may have missed; a hidden one waits for its next show. */
    @Override
    public void onConnected()
    {
        pendingReg = null;
        if (!gamesRefused.isEmpty())
        {
            gamesRefused.clear();
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
    private JPanel buildList()
    {
        var card = new JPanel(new BorderLayout());
        card.setName(CARD_LIST);
        JPanel top = column();
        top.add(label(null, "Tournaments", BOLD, HEADER_PT, null));
        listStatus.setName("tournaments-list-status");
        plain(listStatus, BODY_PT).setForeground(MUTED);
        top.add(listStatus);
        top.add(vgap(6));
        top.add(tabButton("Refresh", "tournaments-refresh", this::sync));
        top.add(vgap(4));
        top.add(tabButton("Past tournaments", "tournaments-past", this::openPast));
        top.add(vgap(6));
        listGearSlot.setName("tournaments-list-gear-slot");
        listGearSlot.setOpaque(false);
        top.add(left(listGearSlot));
        card.add(top, NORTH);
        listBody.setName("tournaments-list-body");
        card.add(scroll(listBody), CENTER);
        return card;
    }

    /** "Set up tournaments" and "Please log into the game to set up tournaments.", as the Matchmaking tab words it. */
    private JPanel buildLogin()
    {
        JPanel card = column();
        card.setName(CARD_LOGIN);
        card.add(label("tournaments-logged-out-title", "Set up tournaments", BOLD, HEADER_PT, null));
        card.add(vgap(8));
        JLabel notice = label("tournaments-logged-out-notice", "", BOLD, BODY_PT, new Color(0xcccccc));
        wrap(notice, "Please log into the game to set up tournaments.");
        card.add(notice);
        return card;
    }

    private JPanel buildActive()
    {
        var card = new JPanel(new BorderLayout());
        card.setName(CARD_ACTIVE);
        JPanel top = column();
        for (JLabel l : new JLabel[]{activeHeader, activeEta, activeMatch, activeWhere, activeStatus}) top.add(l);
        activeSlot.setName("tournaments-active-gear-slot");
        activeSlot.setOpaque(false);
        top.add(left(activeSlot), 2);
        opponentSlot.setName("tournament-opponent-slot");
        opponentSlot.setOpaque(false);
        opponentSlot.setBorder(pad(2, 0, 2, 0));
        top.add(left(opponentSlot), 3);
        activeHeader.setName("tournaments-active-header");
        bold(activeHeader, HEADER_PT);
        activeEta.setName("tournaments-active-eta");
        bold(activeEta, HEADER_PT).setForeground(GREEN);
        activeMatch.setName("tournaments-active-match");
        bold(activeMatch, HEADER_PT);
        activeWhere.setName("tournaments-active-where");
        plain(activeWhere, BODY_PT);
        activeStatus.setName("tournaments-active-status");
        plain(activeStatus, BODY_PT).setForeground(MUTED);

        roundEndBox.setName("tournaments-round-end");
        roundEndBox.setOpaque(true);
        roundEndBox.setBackground(new Color(0x3a2d0a));
        roundEndBox.setBorder(pad(6, 6, 6, 6));
        roundEndBox.setForeground(AMBER);
        plain(roundEndBox, BODY_PT);
        roundEndBox.setVisible(false);
        top.add(roundEndBox);

        JLabel lbTitle = label(null, "Live leaderboard", BOLD, HEADER_PT, null);
        lbTitle.setBorder(pad(8, 0, 2, 0));
        top.add(lbTitle);
        card.add(top, NORTH);

        boardPanel.setName("tournaments-standings");
        card.add(scroll(boardPanel), CENTER);

        JPanel footer = column();
        footer.setBorder(pad(6, 0, 0, 0));
        rulesBtn.setEnabled(false);
        syncReport();
        activePair = pairRow(reportBtn, rulesBtn);
        footer.add(activePair);
        footer.add(vgap(4));
        withdrawBtn.setName("tournaments-active-withdraw");
        withdrawBtn.setBackground(RED);
        withdrawBtn.setForeground(RED_FG);
        withdrawBtn.addActionListener(e -> { if (activeId != null) service.withdraw(activeId); });
        footer.add(withdrawBtn);
        backBtn.setVisible(false);
        footer.add(backBtn);
        card.add(footer, SOUTH);
        return card;
    }

    /** The finished events, newest first, each opening its final standings. */
    private JPanel buildPast()
    {
        var card = new JPanel(new BorderLayout());
        card.setName(CARD_PAST);
        JPanel top = column();
        top.add(label(null, "Past tournaments", BOLD, HEADER_PT, null));
        pastStatus.setName("tournaments-past-status");
        plain(pastStatus, BODY_PT).setForeground(MUTED);
        top.add(pastStatus);
        top.add(vgap(6));
        top.add(tabButton("Back", "tournaments-past-back", () -> showCard(CARD_LIST)));
        top.add(vgap(6));
        card.add(top, NORTH);
        pastBody.setName("tournaments-past-list");
        card.add(scroll(pastBody), CENTER);
        return card;
    }

    /** One finished event's final standings, drawn like the live leaderboard. */
    private JPanel buildPastStandingsCard()
    {
        var card = new JPanel(new BorderLayout());
        card.setName(CARD_PAST_STANDINGS);
        JPanel top = column();
        top.add(label("tournaments-past-standings-title", "Final standings", BOLD, HEADER_PT, null));
        pastTitle.setName("tournaments-past-standings-event");
        plain(pastTitle, BODY_PT);
        top.add(pastTitle);
        pastBoardMsg.setName("tournaments-past-standings-status");
        plain(pastBoardMsg, BODY_PT).setForeground(MUTED);
        top.add(pastBoardMsg);
        top.add(vgap(6));
        top.add(tabButton("Back", "tournaments-past-standings-back", () -> showCard(CARD_PAST)));
        top.add(vgap(6));
        card.add(top, NORTH);
        pastBoard.setName("tournaments-past-standings-body");
        card.add(scroll(pastBoard), CENTER);
        return card;
    }

    /** The in-panel Rules page: the title, then <b>Back</b> and <b>Open on the site</b>, then the
     *  sections in wire order as a bold heading + the lines as wrapped <b>plain text</b> (a
     *  {@link JTextArea}, so nothing in the rules is ever interpreted as HTML), in a scroll pane. */
    static JPanel rulesPage(String eventName, TourneyRules rules, Runnable onBack, Runnable onOpenSite)
    {
        var page = new JPanel(new BorderLayout());
        page.setName("tournament-rules-dialog");
        JPanel top = column();
        JLabel title = label("tournament-rules-title", "", BOLD, HEADER_PT, null);
        title.setText(wrapEscaped(title.getFont(), TEXT_WIDTH,
            escape(eventName == null ? "" : eventName) + " · Rules"));
        top.add(title);
        top.add(label(null, "Version " + rules.version + " · " + rules.sections.size() + (rules.sections.size() == 1 ? " section" : " sections"),
            PLAIN, 14f, MUTED));
        top.add(vgap(6));
        top.add(tabButton("Back", "tournament-rules-back", onBack));
        top.add(vgap(4));
        top.add(tabButton("Open on the site", "tournament-rules-open-site", onOpenSite));
        top.add(vgap(6));
        page.add(top, NORTH);

        JPanel body = column();
        body.setBorder(pad(2, 2, 2, 2));
        int i = 0;
        for (TourneyRules.Section s : rules.sections)
        {
            if (!s.heading.isEmpty())
            {
                JLabel heading = label("tournament-rules-heading-" + i, s.heading, BOLD, HEADER_PT, null);
                heading.setBorder(pad(i == 0 ? 0 : 8, 0, 2, 0));
                body.add(heading);
            }
            if (!s.lines.isEmpty())
            {
                var lines = new JTextArea(String.join("\n", s.lines));
                lines.setName("tournament-rules-lines-" + i);
                lines.setEditable(false);
                lines.setFocusable(false);
                lines.setLineWrap(true);
                lines.setWrapStyleWord(true);
                lines.setOpaque(false);
                lines.setForeground(INFO);
                plain(lines, BODY_PT);
                lines.setBorder(pad(0, 0, 0, 0));
                body.add(left(lines));
            }
            i++;
        }
        body.add(Box.createVerticalGlue());
        JScrollPane sp = scroll(body);
        sp.getVerticalScrollBar().setUnitIncrement(12);
        page.add(sp, CENTER);
        return page;
    }

    /** The tab's bold button — shared with the cards, the Rules page and the
     *  kit card — at the side panel's button size and as wide as its column. */
    static JButton tabButton(String label)
    {
        JButton b = bold(new JButton(label), BUTTON_PT);
        b.setMargin(new Insets(6, 8, 6, 8));
        b.setFocusPainted(false);
        return pin(b);
    }

    /** {@link #tabButton(String)} named {@code name}, running {@code action} when pressed. */
    static JButton tabButton(String label, String name, Runnable action)
    {
        JButton b = tabButton(label);
        b.setName(name);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** A line at its label's font, wrapped to the tab's text width; blank
     *  keeps the line's height. */
    static void setWrapped(JLabel label, String plain)
    {
        String text = plain == null || plain.trim().isEmpty()
            ? " " : wrapHtml(label.getFont(), TEXT_WIDTH, plain);
        if (!text.equals(label.getText())) label.setText(text);
    }

    private void showCard(String name)
    {
        currentCard = name;
        if (!CARD_RULES.equals(name)) dropRules();
        cards.show(cardHost, gameLoggedIn.getAsBoolean() ? name : CARD_LOGIN);
        syncBanner();
        placeCard();
    }

    public void setGearCard(JComponent card)
    {
        if (gearCard != null && gearCard.getParent() != null) gearCard.getParent().remove(gearCard);
        gearCard = card;
        placeCard();
    }

    private void placeCard()
    {
        if (gearCard == null) return;
        JPanel slot = activeId != null ? activeSlot : listGearSlot;
        if (gearCard.getParent() == slot) return;
        if (gearCard.getParent() != null) gearCard.getParent().remove(gearCard);
        slot.add(gearCard, CENTER);
        listGearSlot.revalidate();
        activeSlot.revalidate();
        repaint();
    }

    private void showBanner(String text)
    {
        banner.setText(wrapEscaped(banner.getFont(), TEXT_WIDTH, text));
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
        fill(listBody, listStatus, "No open tournaments right now.", events, this::eventCard);
    }

    /** A list card's rows, each followed by a gap, and its count line ({@code empty} without rows). */
    private <T> void fill(JPanel body, JLabel status, String empty, List<T> items, Function<T, Component> row)
    {
        body.removeAll();
        setWrapped(status, items.isEmpty() ? empty : items.size() + (items.size() == 1 ? " event" : " events"));
        for (T item : items)
        {
            body.add(row.apply(item));
            body.add(vgap(6));
        }
        body.revalidate();
        body.repaint();
    }

    private String myStatusFor(Tourney t)
    {
        if (t.myStatus != null) return t.myStatus;
        for (Tourney r : myRegs)
        {
            if (r.tournamentId.equals(t.tournamentId)) return r.myStatus;
        }
        return null;
    }

    /** One {@link TournamentInfoCard} per listed event; its time
     *  lines join the existing 1 Hz refresh. */
    private TournamentInfoCard eventCard(Tourney t)
    {
        return new TournamentInfoCard(t, myStatusFor(t), discordLoggedIn.getAsBoolean(), zone, nowMs, this,
            countFor(t), gamesRefused.contains(t.tournamentId));
    }

    /** "Running · round 2 of 5", or "Starts Thu 24 Sep 19:00 · in 3 days"
     *  from {@code starts_at} in the viewer's zone, or the raw status when
     *  the event carries no start epoch. */
    static String when(Tourney t, ZoneId zone, long nowMs)
    {
        if (t.isRunning()) return "Running · round " + t.currentRound + " of " + t.rounds;
        if (t.startsAt > 0) return "Starts " + TimeText.describe(t.startsAt, zone, nowMs);
        return t.status;
    }

    /** "Registration closes today 18:30 · in 12 min" for an open event with
     *  a {@code registration_closes_at}; {@code ""} otherwise (a running or
     *  finished event has no window to show). */
    static String closes(Tourney t, ZoneId zone, long nowMs)
    {
        if (!t.isRegOpen() || t.regClosesAt <= 0) return "";
        return "Registration closes " + TimeText.describe(t.regClosesAt, zone, nowMs);
    }

    static String gp(long amount)
    {
        if (amount >= 1_000_000) return trim(amount / 1_000_000.0) + "M";
        if (amount >= 1_000) return trim(amount / 1_000.0) + "K";
        return Long.toString(amount);
    }

    private static String trim(double v)
    {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ---------------------------------------------------------------- past tournaments
    private void openPast()
    {
        pastBody.removeAll();
        showCard(CARD_PAST);
        IntFunction<CompletableFuture<JsonObject>> loader = historyLoader;
        load(pastStatus, "Could not load past tournaments.", () -> loader == null ? null : loader.apply(10),
            json -> fill(pastBody, pastStatus, "No finished tournaments yet.", Tourney.listOf(optArray(json, "tournaments")), this::pastRow));
    }

    /** One Past tournaments load: Loading…, then {@code done} with the answer or {@code failed}; a late answer for an
     *  earlier visit is dropped. */
    private void load(JLabel status, String failed, Supplier<CompletableFuture<JsonObject>> request, Consumer<JsonObject> done)
    {
        int gen = ++pastGen;
        setWrapped(status, "Loading…");
        CompletableFuture<JsonObject> answer = safeLoad(request);
        if (answer == null)
        {
            setWrapped(status, failed);
            return;
        }
        answer.whenComplete((json, ex) -> SwingUtilities.invokeLater(() ->
        {
            if (gen != pastGen) return;
            if (ex != null || json == null) setWrapped(status, failed);
            else done.accept(json);
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

    /** A finished event's row: its name, the style and when it ended; a click opens its final standings. */
    private JPanel pastRow(Tourney t)
    {
        JPanel row = new CapPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setName("past-event-" + t.tournamentId);
        cardStyle(row);
        JLabel name = label(null, "", BOLD, HEADER_PT, Color.WHITE);
        wrap(name, t.name);
        row.add(name);
        JLabel line = label(null, "", PLAIN, BODY_PT, MUTED);
        wrap(line, pastLine(t, zone, nowMs.getAsLong()));
        row.add(line);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Runnable open = () -> openBoard(t);
        onClick(row, open);
        onClick(name, open);
        onClick(line, open);
        return row;
    }

    /** {@code "NH Main · 3 rounds · 8 players · Ended today 13:00 · 1 h ago"}. */
    static String pastLine(Tourney t, ZoneId zone, long nowMs)
    {
        List<String> parts = new ArrayList<>();
        parts.add(t.styleLabel());
        if (t.rounds > 0) parts.add(t.rounds + (t.rounds == 1 ? " round" : " rounds"));
        parts.add(t.regCount + (t.regCount == 1 ? " player" : " players"));
        if (t.endedAt > 0) parts.add("Ended " + TimeText.describe(t.endedAt, zone, nowMs));
        return String.join(" · ", parts);
    }

    private void openBoard(Tourney t)
    {
        pastBoard.removeAll();
        pastBoard.rows.clear();
        setWrapped(pastTitle, t.name);
        showCard(CARD_PAST_STANDINGS);
        Function<String, CompletableFuture<JsonObject>> loader = standingsLoader;
        load(pastBoardMsg, BOARD_FAILED, () -> loader == null ? null : loader.apply(t.tournamentId), json ->
        {
            TourneyBoard s = TourneyBoard.fromJson(json);
            if (s == null)
            {
                setWrapped(pastBoardMsg, BOARD_FAILED);
                return;
            }
            setWrapped(pastBoardMsg, " ");
            renderBoard(pastBoard, "past-standings-", s.rows, -1);
        });
    }

    // ---------------------------------------------------------------- active rendering
    private void renderActive()
    {
        if (activeId == null)
        {
            showCard(CARD_LIST);
            return;
        }
        setWrapped(activeHeader, activeName + " · Round " + round + "/" + rounds);
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
            dropCard();
            activeMatch.setVisible(true);
            setWrapped(activeMatch, bye ? "Bye this round (counts as a win)" : waitNext ? "Wait until next round" : "Waiting for the next round");
            setWrapped(activeWhere, " ");
            activeWhere.setVisible(false);
            activeStatus.setVisible(false);
        }
        footer(true);
        renderEta();
        renderStatus();
        syncReport();
        refreshRules();
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
        if (roundEndMs > 0)
        {
            long left = Math.max(0L, (roundEndMs - now) / 1000L);
            setWrapped(activeEta, mmss(left) + " est. remaining in round" + extendedText(extendCount));
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
        return BigDecimal.valueOf(points).stripTrailingZeros().toPlainString();
    }

    static String boardScore(StandingsRow r)
    {
        String score = pointsText(r.points);
        return r.draws > 0 ? score + " · " + r.draws + (r.draws == 1 ? " draw" : " draws") : score;
    }

    /** A row's right-hand cell: its score, then its status when it was removed ("2 · withdrew"). */
    static String rightText(StandingsRow r)
    {
        String score = boardScore(r);
        String removed = r.removedLabel();
        return removed.isEmpty() ? score : score + " · " + removed;
    }

    private void renderStatus()
    {
        if (series == null)
        {
            setWrapped(activeStatus, " ");
            return;
        }
        boolean fighting = inCombatProvider.getAsBoolean();
        setWrapped(activeStatus, "Status: " + (fighting ? "in combat (auto)" : "waiting for the fight · hop to " + series.worldLabel()));
    }

    // ---------------------------------------------------------------- the opponent card
    /** The card for the series' opponent: rebuilt when the name changes, its rank filled in when the lookup answers. */
    private void showOpponentCard(MatchSeries s)
    {
        String name = s.hasNamedOpponent() ? s.opponentName : MatchSeries.UNKNOWN_OPPONENT;
        String key = NameUtils.canonicalKey(name);
        if (!key.equals(cardKey))
        {
            cardKey = key;
            opponentRankIdx = -1;
            if (s.hasNamedOpponent()) askRank(s.opponentName, key);
        }
        placeOpponentCard(s, name);
    }

    private void placeOpponentCard(MatchSeries s, String name)
    {
        var opponent = new LobbyMember(cardKey, name, stylesOf(s.category), buildsOf(s.style),
            opponentRankIdx, MatchmakingLobbyPanel.regionCode(s.opponentRegion));
        var card = new PlayerCard(opponent, (int) HEADER_PT, 14, this::openProfile);
        card.setName("tournament-opponent-card");
        opponentSlot.removeAll();
        opponentSlot.add(card, CENTER);
        opponentSlot.setVisible(true);
        opponentSlot.revalidate();
        opponentSlot.repaint();
    }

    private void dropCard()
    {
        opponentSlot.removeAll();
        cardKey = null;
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
            if (ex != null || tier == null || series == null || !key.equals(cardKey)) return;
            int idx = RankUtils.indexOfTier(tier);
            if (idx < 0 || idx == opponentRankIdx) return;
            opponentRankIdx = idx;
            placeOpponentCard(series, series.hasNamedOpponent() ? series.opponentName : MatchSeries.UNKNOWN_OPPONENT);
        }));
    }

    /** The event's style as the card's lit chip; none when the event names no bucket the lobby knows. */
    static Set<Style> stylesOf(String category)
    {
        Style style = Dashboard.bucketStyle(category);
        return style == null ? EnumSet.noneOf(Style.class) : EnumSet.of(style);
    }

    /** The event's build as the card's lit chip; Main when unset or unknown. */
    static Set<BuildType> buildsOf(String build)
    {
        BuildType b = QueueText.parseBuild(build);
        return EnumSet.of(b == null ? BuildType.MAIN : b);
    }

    /** The live leaderboard: the first 40 rows. */
    private void renderBoard(List<StandingsRow> rows, int myRank)
    {
        renderBoard(boardPanel, "standings-", rows.subList(0, Math.min(rows.size(), 40)), myRank);
    }

    /** Every row of {@code rows}; a prize winner's row has its prize on a second line under the name. */
    private void renderBoard(BoardPanel body, String prefix, List<StandingsRow> rows, int myRank)
    {
        body.removeAll();
        String self = selfNameSupplier.get();
        String selfKey = self == null ? null : NameUtils.canonicalKey(self);
        Component mine = null;
        body.rows.clear();
        if (rows.isEmpty())
        {
            body.add(label(null, "No standings yet.", PLAIN, BODY_PT, MUTED));
        }
        for (StandingsRow r : rows)
        {
            boolean me = (selfKey != null && selfKey.equals(NameUtils.canonicalKey(r.displayName)))
                || (myRank > 0 && r.rank == myRank && selfKey == null);
            var line = new JPanel(new BorderLayout(BoardPanel.GAP, 0));
            line.setName(prefix + "row-" + r.rank);
            left(line).setBorder(pad(1, 4, 1, 4));
            if (me)
            {
                line.setBackground(new Color(0x1f3a2a));
                line.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GREEN), pad(1, 4, 1, 4)));
            }
            String label = r.rank + " · " + r.displayName + (me ? " (you)" : "");
            String removed = r.removedLabel();
            String right = rightText(r);
            var left = new JLabel(label);
            if (!removed.isEmpty()) left.setForeground(MUTED);
            var pts = new JLabel(right);
            pts.setForeground(removed.isEmpty() ? Color.WHITE : MUTED);
            JLabel rank = null;
            if (r.rankLabel != null)
            {
                rank = new JLabel(r.rankLabel);
                rank.setName(prefix + "rank-" + r.rank);
                rank.setForeground(RankUtils.getRankColor(r.rankLabel));
                var east = new JPanel(new FlowLayout(FlowLayout.RIGHT, BoardPanel.GAP, 0));
                east.setOpaque(false);
                east.add(rank);
                east.add(pts);
                line.add(east, EAST);
            }
            else
            {
                line.add(pts, EAST);
            }
            line.add(left, CENTER);
            JLabel prize = r.prizeGp < 0 ? null : label(prefix + "prize-" + r.rank, gp(r.prizeGp) + " gp" + (r.prizeRandom ? " random draw" : ""), PLAIN, BODY_PT, AMBER);
            if (prize != null) line.add(prize, SOUTH);
            body.add(line);
            body.rows.add(new BoardPanel.Row(line, left, pts, rank, prize, me));
            if (me) mine = line;
        }
        body.applyPt(HEADER_PT);
        body.revalidate();
        body.repaint();
        if (mine != null)
        {
            final Component target = mine;
            SwingUtilities.invokeLater(() -> body.scrollRectToVisible(target.getBounds()));
        }
    }

    /** The live leaderboard's rows, as wide as the scroll pane's viewport,
     *  all at the largest size from {@link #HEADER_PT} down to 12 pt at
     *  which every row fits. */
    static final class BoardPanel extends RowsPanel
    {
        static final int GAP = 6;

        static final class Row
        {
            final JPanel line;
            final JLabel left;
            final JLabel right;
            /** The rank label beside the points, {@code null} when the row has none. */
            final JLabel rank;
            /** The prize line under the name, {@code null} when the row has none. */
            final JLabel prize;
            final boolean me;

            Row(JPanel line, JLabel left, JLabel right, JLabel rank, JLabel prize, boolean me)
            {
                this.line = line;
                this.left = left;
                this.right = right;
                this.rank = rank;
                this.prize = prize;
                this.me = me;
            }
        }

        final List<Row> rows = new ArrayList<>();
        private final RowTextFit fit = new RowTextFit();
        private float appliedPt = -1f;

        BoardPanel()
        {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public void doLayout()
        {
            int width = getWidth();
            if (width > 0 && !rows.isEmpty())
            {
                int usable = width - getInsets().left - getInsets().right;
                int pt = fit.largestFitting((int) 12f, (int) HEADER_PT, p -> allFit(p, usable));
                applyPt(pt);
            }
            super.doLayout();
        }

        private boolean allFit(int pt, int usable)
        {
            for (Row r : rows)
            {
                Insets in = r.line.getInsets();
                int needed = in.left + in.right + GAP + fit.textWidth(r.left.getText(), rowFont(pt, r.me)) + fit.textWidth(r.right.getText(), rowFont(pt, false));
                if (r.rank != null) needed += GAP + fit.textWidth(r.rank.getText(), rowFont(pt, false));
                if (needed > usable || r.prize != null && in.left + in.right + fit.textWidth(r.prize.getText(), rowFont(pt, false)) > usable) return false;
            }
            return true;
        }

        void applyPt(float pt)
        {
            if (pt == appliedPt && !rows.isEmpty() && rows.get(0).left.getFont().getSize2D() == pt) return;
            appliedPt = pt;
            for (Row r : rows)
            {
                r.left.setFont(rowFont((int) pt, r.me));
                for (JLabel l : new JLabel[]{r.right, r.rank, r.prize}) if (l != null) l.setFont(rowFont((int) pt, false));
                int height = Math.max(r.left.getPreferredSize().height, r.right.getPreferredSize().height)
                    + (r.prize == null ? 0 : r.prize.getPreferredSize().height)
                    + r.line.getInsets().top + r.line.getInsets().bottom;
                maxH(r.line, height);
            }
        }

        private static Font rowFont(int pt, boolean bold)
        {
            return RowTextFit.baseFont().deriveFont(bold ? BOLD : PLAIN, (float) pt);
        }

        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return 64; }
    }

    // ---------------------------------------------------------------- actions
    /** The one report dialog, for the active footer and every card's
     *  button alike: the answer goes out on the existing
     *  {@code tournament/report_problem}. */
    void onReport(String tournamentId)
    {
        String text = JOptionPane.showInputDialog(this,
            "Describe the issue (up to 280 characters). It goes straight to this tournament's host on Discord:",
            "Report an issue", JOptionPane.PLAIN_MESSAGE);
        reportProblem(tournamentId, text);
    }

    /** Package-private so tests can skip the dialog: the active event's report. */
    void reportProblem(String text)
    {
        if (activeId == null) return;
        reportProblem(activeId, text);
    }

    /** One sender for both surfaces (the transport trims and drops blank text). The
     *  Discord-login gate lives on the buttons and on the server, whose answer
     *  becomes a banner. */
    void reportProblem(String tournamentId, String text)
    {
        service.reportProblem(tournamentId, text);
    }

    /** A card's Register: remembered until the server answers, for a refusal that names it. */
    void register(Tourney t)
    {
        pendingReg = t.tournamentId;
        service.register(t.tournamentId, regionSupplier.get());
    }

    // ---------------------------------------------------------------- rules
    /** A card's Rules button: the in-panel page when the entry carries a
     *  rules block, else the event's {@code rules_url} in the browser (an
     *  older backend, or a junk block — {@code TourneyRules.fromJson}
     *  already read that as absent). */
    void openRules(Tourney t)
    {
        if (t == null) return;
        String url = t.rulesUrl == null ? "" : t.rulesUrl;
        if (t.rules == null)
        {
            if (!url.isEmpty()) linkOpener.accept(url);
            return;
        }
        if (rulesDialog != null) cardHost.remove(rulesDialog);
        if (!CARD_RULES.equals(currentCard)) rulesBack = currentCard;
        rulesDialog = rulesPage(t.name, t.rules, this::closeRules, () -> { if (!url.isEmpty()) linkOpener.accept(url); });
        cardHost.add(rulesDialog, CARD_RULES);
        showCard(CARD_RULES);
        revalidate();
        repaint();
    }

    /** The active footer's Rules: the active event's listed entry, so it
     *  gets the same page as its card. */
    private void openRulesForActive()
    {
        openRules(summaryFor(activeId));
    }

    /** The footer's Rules works once the running event's entry is known. */
    private void refreshRules()
    {
        rulesBtn.setEnabled(summaryFor(activeId) != null);
    }

    private void closeRules()
    {
        showCard(CARD_ACTIVE.equals(rulesBack) && activeId != null ? CARD_ACTIVE : CARD_LIST);
        revalidate();
        repaint();
    }

    /** Removes the Rules page whenever another page shows, so the hidden page never sizes the tab. */
    private void dropRules()
    {
        if (rulesDialog == null) return;
        cardHost.remove(rulesDialog);
        rulesDialog = null;
    }

    /** The listed entry (else the registration row) for an id; {@code null} when unknown. */
    private Tourney summaryFor(String tournamentId)
    {
        if (tournamentId == null) return null;
        for (Tourney t : events) if (tournamentId.equals(t.tournamentId)) return t;
        for (Tourney t : myRegs) if (tournamentId.equals(t.tournamentId)) return t;
        return null;
    }

    private void resubscribe()
    {
        if (!showing || activeId == null || activeId.equals(subscribedId)) return;
        unsubscribe();
        subscribedId = activeId;
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
            for (Component c : listBody.getComponents()) if (c instanceof TournamentInfoCard) ((TournamentInfoCard) c).tick();
        }
        if (activeId != null && CARD_ACTIVE.equals(currentCard))
        {
            renderEta();
            renderStatus();
            if (roundEndBox.isVisible() && nowMs.getAsLong() > respondByMs - 1000) roundEndBox.setVisible(false);
        }
        signalCombat();
    }

    private void signalCombat()
    {
        if (activeId == null || series == null || !series.isOpen()) return;
        if (!inCombatProvider.getAsBoolean()) return;
        long now = nowMs.getAsLong();
        if (now - combatSentMs < 30_000L) return;
        combatSentMs = now;
        service.inCombat(activeId, series.seriesId);
    }

    /** Test hook: one ticker beat without waiting for the Swing timer. */
    void tickForTest()
    {
        onTick();
    }

    // ---------------------------------------------------------------- listener (EDT)
    @Override
    public void onTournamentList(List<Tourney> tournaments)
    {
        events = tournaments;
        renderList();
        refreshRules();
    }

    @Override
    public void onRegistered(Tourney tournament, String regStatus)
    {
        pendingReg = null;
        gamesRefused.remove(tournament.tournamentId);
        showBanner(regBanner(tournament));
        service.list();
        service.status();
    }

    /** "Registered for X. Meet on W578 at PvP Arena entrance when round 1 opens." — the meeting line
     *  when the push carries one, else the sentence about the round's own message. */
    static String regBanner(Tourney t)
    {
        String head = "Registered for " + escape(t.name) + ". ";
        if (t.meetingWorld != null) return head + "Meet on " + escape(t.meetingWorld) + (t.meetingPlace == null ? "" : " at " + escape(t.meetingPlace)) + " when round 1 opens.";
        return head + "Your opponent, world and meeting place arrive here when each round opens.";
    }

    @Override
    public void onWithdrawn(String tournamentId)
    {
        showBanner("You withdrew from the tournament.");
        if (tournamentId.equals(activeId)) clearActive();
        service.list();
        service.status();
    }

    @Override
    public void onTournamentState(List<Tourney> registrations, LiveTourney state)
    {
        myRegs = registrations;
        if (state == null || !state.isRunning())
        {
            if (activeId != null) clearActive();
            renderList();
            return;
        }
        activeId = state.tournamentId;
        activeName = state.name;
        round = state.round;
        rounds = state.rounds;
        myRank = state.myRank;
        series = state.series != null && state.series.isOpen() ? state.series : null;
        if (series != null) waitNext = false;
        bye = state.bye;
        breakUntilS = state.breakUntil;
        roundEndMs = state.etaS >= 0 ? nowMs.getAsLong() + state.etaS * 1000L : 0L;
        renderActive();
        renderBoard(state.standings, state.myRank);
        resubscribe();
        renderList();
    }

    @Override
    public void onStandings(TourneyBoard standings)
    {
        if (!standings.tournamentId.equals(activeId)) return;
        if (standings.round > 0 && standings.round != round)
        {
            round = standings.round;
            rounds = standings.rounds;
        }
        breakUntilS = standings.breakUntil;
        if (standings.etaS >= 0) roundEndMs = nowMs.getAsLong() + standings.etaS * 1000L;
        else if (standings.breakUntil > 0) roundEndMs = 0L;
        extendCount = Math.max(0, standings.extended);
        setWrapped(activeHeader, activeName + " · Round " + round + "/" + rounds);
        renderEta();
        renderBoard(standings.rows, myRank);
        if (standings.isFinished() || "cancelled".equals(standings.status))
        {
            // the dedicated finished / cancelled pushes drive the exit; the standings just stop moving
            setWrapped(activeEta, standings.isFinished() ? "Final standings" : "Cancelled");
        }
    }

    @Override
    public void onMatchAssigned(MatchSeries s)
    {
        boolean same = s.tournamentId.equals(activeId);
        if (activeId == null)
        {
            activeName = s.tournamentId;
            rounds = 0;
        }
        if (!same) myRank = -1;
        if (!same || s.round > 0) round = s.round;
        activeId = s.tournamentId;
        series = s;
        bye = false;
        waitNext = false;
        breakUntilS = 0L;
        roundEndMs = s.deadlineAt > 0 ? s.deadlineAt * 1000L : 0L;
        extendCount = 0;
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
        if (series == null || !tournamentId.equals(activeId)) return;
        if (seriesId != null && !seriesId.isEmpty() && !seriesId.equals(series.seriesId)) return;
        series = null;
        waitNext = true;
        renderActive();
    }

    @Override
    public void onBye(String tournamentId, int round)
    {
        if (!tournamentId.equals(activeId)) return;
        series = null;
        bye = true;
        waitNext = false;
        extendCount = 0;
        // a bye is a point, never a rated game
        showBanner("Round " + round + ": you have a bye — it counts as a win (no rating change).");
        renderActive();
    }

    @Override
    public void onRoundEndCheck(String tournamentId, int round, String opponentName, long respondByS, String message)
    {
        if (!tournamentId.equals(activeId)) return;
        respondByMs = respondByS > 0 ? respondByS * 1000L : nowMs.getAsLong() + 30_000L;
        String sentence = message == null || message.trim().isEmpty() ? "Submit within 30 seconds or you may be removed." : message.trim();
        wrap(roundEndBox, endTitle(round, opponentName), sentence);
        roundEndBox.setVisible(true);
        showCard(CARD_ACTIVE);
        revalidate();
        repaint();
    }

    static String endTitle(int round, String opponentName)
    {
        String opponent = opponentName.trim().isEmpty() ? "your opponent" : opponentName;
        return "Round " + round + " has ended — your match vs " + opponent + " has no result yet";
    }

    @Override
    public void onRemoved(String tournamentId, String status, String reason, int round)
    {
        String what;
        switch (status)
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
            showBanner(what + (round > 0 ? " in round " + round : "") + (reason.isEmpty() ? "." : " — " + escape(reason) + "."));
        }
        if (mine(tournamentId)) clearActive();
        service.list();
    }

    @Override
    public void onCancelled(String tournamentId, String reason)
    {
        showBanner("Tournament cancelled" + (reason.isEmpty() ? "." : ": " + escape(reason)));
        if (mine(tournamentId)) clearActive();
        service.list();
    }

    /** The finish: the banner, and the final standings stay on screen until Back. */
    @Override
    public void onFinished(String tournamentId, JsonObject winners, List<StandingsRow> standings)
    {
        showBanner("Tournament finished" + winnersText(winners) + placeText(standings, selfNameSupplier.get()));
        if (mine(tournamentId))
        {
            showFinal(standings);
        }
        service.list();
    }

    private void showFinal(List<StandingsRow> standings)
    {
        String name = activeId == null ? null : activeName;
        reset(false);
        setWrapped(activeHeader, name == null ? " " : name);
        setWrapped(activeEta, "Final standings");
        activeMatch.setVisible(false);
        activeWhere.setVisible(false);
        activeStatus.setVisible(false);
        renderBoard(boardPanel, "standings-", standings, -1);
        showCard(CARD_ACTIVE);
    }

    /** The winners clause of the finished banner — the placed winners and
     *  the random draw, mirroring the finished message's
     *  "Winners: … · Random draw: …". With an {@code amount_gp} on any winner
     *  the placed ones are numbered and each name carries its amount
     *  ("1. psyop9 - 25M gp"). {@code "."} when nobody is named. */
    static String winnersText(JsonObject winners)
    {
        List<JsonObject> top = named(winners, "top"), drawn = named(winners, "random");
        if (top.isEmpty()) return ".";
        boolean paid = false;
        for (JsonObject w : top) paid |= optWhole(w, "amount_gp") > 0;
        for (JsonObject w : drawn) paid |= optWhole(w, "amount_gp") > 0;
        List<String> placed = new ArrayList<>(), random = new ArrayList<>();
        for (JsonObject w : top) placed.add((paid ? placed.size() + 1 + ". " : "") + winner(w));
        for (JsonObject w : drawn) random.add(winner(w));
        return " — winners: " + String.join(", ", placed) + (random.isEmpty() ? "" : " · random draw: " + String.join(", ", random)) + ".";
    }

    /** The entries of a winners array with a {@code display_name}; non-objects and nameless entries are skipped. */
    private static List<JsonObject> named(JsonObject winners, String key)
    {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : optArray(winners, key)) if (e.isJsonObject() && e.getAsJsonObject().get("display_name") instanceof JsonPrimitive) out.add(e.getAsJsonObject());
        return out;
    }

    /** "psyop9 - 25M gp", or the name alone without a usable {@code amount_gp}. */
    private static String winner(JsonObject w)
    {
        long amount = optWhole(w, "amount_gp");
        return optString(w, "display_name") + (amount > 0 ? " - " + gp(amount) + " gp" : "");
    }

    static String placeText(List<StandingsRow> standings, String selfName)
    {
        String selfKey = NameUtils.canonicalKey(selfName);
        for (StandingsRow r : standings)
        {
            if (r.rank <= 0) continue;
            if (selfKey.equals(NameUtils.canonicalKey(r.displayName)))
            {
                return " You finished #" + r.rank + " with " + pointsText(r.points) + " point" + (r.points == 1.0 ? "" : "s") + ".";
            }
        }
        return "";
    }

    @Override
    public void onProblemAck()
    {
        showBanner("Problem report received — the organizer has been told.");
    }

    @Override
    public void onTournamentError(String code, String message, String cmd)
    {
        if ("tournament/gear_status".equals(cmd)) return;
        String pressed = null;
        if ("tournament/register".equals(cmd))
        {
            pressed = pendingReg;
            pendingReg = null;
        }
        if ("TOURNAMENT_MIN_GAMES".equals(code))
        {
            if (pressed != null)
            {
                gamesRefused.add(pressed);
                renderList();
            }
            return;
        }
        showBanner(friendlyError(code, message));
    }

    /** The banner for an {@code error/tournament}. The server's own sentence for the report gate
     *  ({@code DISCORD_NOT_LINKED}, {@code REPORT_RATE_LIMITED}: discord_tournament_messages
     *  REPORT_LINK_REQUIRED_TEXT / REPORT_RATE_LIMITED_TEXT), a state refusal and an update request. */
    static String friendlyError(String code, String message)
    {
        switch (code)
        {
            case "TOURNAMENT_FULL": return "Tournament full.";
            case "TOURNAMENT_REGISTRATION_CLOSED": return "Registration closed.";
            case "TOURNAMENT_PEAK_OUT_OF_RANGE": return "Your rank is outside this tournament's range.";
            case "TOURNAMENT_ALREADY_REGISTERED": return "You are already registered.";
            case "TOURNAMENT_NOT_REGISTERED": return "You are not registered.";
            case "TOURNAMENT_NOT_FOUND": return "Tournament not found.";
            case "DISCORD_NOT_LINKED":
            case "REPORT_RATE_LIMITED":
            case "TOURNAMENT_STATE":
            case "TOURNAMENT_PLUGIN_UPDATE_REQUIRED": return escape(message);
            default: return "Tournaments: " + (message.isEmpty() ? code : escape(message));
        }
    }

    /** No running event, an id-less push, or this event's push. */
    private boolean mine(String tournamentId)
    {
        return activeId == null || tournamentId.isEmpty() || tournamentId.equals(activeId);
    }

    private void clearActive()
    {
        reset(true);
        boardPanel.removeAll();
        boardPanel.rows.clear();
        showCard(CARD_LIST);
        renderList();
    }

    /** Leaves the running event; {@code live} = the next view is a live one (Report, Rules and Withdraw), else the final
     *  standings (Back). */
    private void reset(boolean live)
    {
        unsubscribe();
        activeId = null;
        series = null;
        bye = false;
        waitNext = false;
        breakUntilS = 0L;
        roundEndMs = 0L;
        extendCount = 0;
        roundEndBox.setVisible(false);
        dropCard();
        footer(live);
    }

    private void footer(boolean live)
    {
        activePair.setVisible(live);
        withdrawBtn.setVisible(live);
        backBtn.setVisible(!live);
    }
}
