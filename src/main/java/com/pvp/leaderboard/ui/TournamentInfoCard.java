package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.tournament.TournamentSummary;
import com.pvp.leaderboard.util.RankUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

final class TournamentInfoCard extends JPanel
{
    /** The panel's side of the card's buttons. */
    interface Actions
    {
        void register(TournamentSummary t);

        void withdraw(TournamentSummary t);

        void rules(TournamentSummary t);

        void report(TournamentSummary t);
    }

    static final String AUTO_ROUNDS_TEXT = "rounds: auto (set at the start)";
    static final String REPORT_LABEL = "Report an issue";
    static final String REPORT_TOOLTIP = "Report an issue with this tournament — the message goes to its host on Discord";
    static final String REPORT_LOGIN_TOOLTIP = "Log in with Discord to contact the host";

    /** The lobby row's card background + divider. */
    static final Color CARD_BG = new Color(0x2b, 0x2b, 0x2b);
    private static final Color DIVIDER = new Color(0x40, 0x40, 0x40);
    /** The lobby's chip palette: region white, style yellow, build cyan. */
    private static final Color CHIP_FORMAT = Color.WHITE;
    private static final Color CHIP_CATEGORY = new Color(0xff, 0xc1, 0x07);
    private static final Color CHIP_BUILD = new Color(0x4f, 0xc3, 0xf7);
    private static final Color GREEN = new Color(0x3e, 0xcf, 0x8e);
    private static final Color RED = new Color(0x5a, 0x2a, 0x2a);
    private static final Color RED_FG = new Color(0xff, 0xb3, 0xb3);
    private static final Color MUTED = new Color(0x9a, 0x9a, 0x9a);
    private static final Color INFO = new Color(0xdd, 0xdd, 0xdd);
    private static final float NAME_PT = 16f;
    private static final float LINE_PT = 15f;
    private static final float CHIP_PT = 14f;
    private static final float STATUS_PT = 14f;
    /** Card buttons: the full-width one, and the most / least the Report + Rules row may use. */
    static final float BUTTON_PT = 16f;
    private static final float PAIR_MIN_PT = 14f;
    /** The width a card's text wraps to (the side panel's card, inside its border). */
    static final int TEXT_WIDTH_PX = 170;
    /** Room left for the HTML view's own rounding when a line is measured. */
    private static final int WRAP_SLACK_PX = 4;
    private static final String[] RANK_LABELS = buildRankLabels();

    private final TournamentSummary t;
    private final ZoneId zone;
    private final LongSupplier nowMs;
    private final JLabel when = new JLabel();
    /** {@code null} when the event has no registration window to show. */
    private final JLabel closes;
    private final JButton report;
    private String whenPhrase;
    private String closesPhrase;

    /** {@code myCount} is the player's own match count in the event's bucket
     *  ({@code null} when unknown); {@code refused} marks an event whose
     *  registration was refused for its minimum. */
    TournamentInfoCard(TournamentSummary t, String myStatus, boolean discordLoggedIn, ZoneId zone, LongSupplier nowMs, Actions actions,
                       Integer myCount, boolean refused)
    {
        this.t = t;
        this.zone = zone;
        this.nowMs = nowMs;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setName("tournament-card-" + t.tournamentId);
        setBackground(CARD_BG);
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(new MatteBorder(0, 0, 1, 0, DIVIDER), BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        setAlignmentX(LEFT_ALIGNMENT);

        add(header(myStatus));
        add(Box.createVerticalStrut(3));
        add(chips());
        add(Box.createVerticalStrut(3));
        addLine("tournament-players-", playersLine(t), INFO);
        String prize = prizeLine(t);
        if (!prize.isEmpty()) addLine("tournament-prize-", prize, INFO);
        String ranks = rankLimitsLine(t);
        if (!ranks.isEmpty()) addLine("tournament-ranks-", ranks, MUTED);
        String need = minGamesLine(t);
        if (!need.isEmpty()) addLine("tournament-min-games-", need, MUTED);
        if (t.creatorName != null && !t.creatorName.trim().isEmpty()) addLine("tournament-host-", "Host: " + t.creatorName.trim(), MUTED);
        if (t.gearSet != null) addLine("tournament-kit-", kitLine(t), INFO);
        when.setName("tournament-when-" + t.tournamentId);
        when.setFont(when.getFont().deriveFont(Font.PLAIN, LINE_PT));
        when.setAlignmentX(LEFT_ALIGNMENT);
        add(when);
        if (TournamentsPanel.closes(t, zone, nowMs.getAsLong()).isEmpty())
        {
            closes = null;
        }
        else
        {
            closes = new JLabel();
            closes.setName("tournament-closes-" + t.tournamentId);
            closes.setFont(closes.getFont().deriveFont(Font.PLAIN, LINE_PT));
            closes.setForeground(MUTED);
            closes.setAlignmentX(LEFT_ALIGNMENT);
            add(closes);
        }
        tick();
        add(Box.createVerticalStrut(6));
        report = TournamentsPanel.tabButton(REPORT_LABEL);
        report.setName("tournament-report-" + t.tournamentId);
        report.addActionListener(e -> actions.report(t));
        JButton rules = TournamentsPanel.tabButton("Rules");
        rules.setName("tournament-rules-" + t.tournamentId);
        rules.addActionListener(e -> actions.rules(t));
        add(pairRow(report, rules));
        JComponent signUp = signUp(myStatus, actions, myCount, refused);
        if (signUp != null)
        {
            add(Box.createVerticalStrut(4));
            add(signUp);
        }
        setReportEnabled(discordLoggedIn);
    }

    /** Pin the card to its preferred height like the lobby cards, so
     *  BoxLayout never stretches it to fill the viewport. */
    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** The Report gate: enabled + the host tooltip while logged in with
     *  Discord, disabled + the login hint otherwise. */
    void setReportEnabled(boolean discordLoggedIn)
    {
        report.setEnabled(discordLoggedIn);
        report.setToolTipText(discordLoggedIn ? REPORT_TOOLTIP : REPORT_LOGIN_TOOLTIP);
    }

    /** Re-renders the two time lines; {@code setText} only when the phrase
     *  moved, so the panel's 1 Hz beat costs a string compare per label. */
    void tick()
    {
        long now = nowMs.getAsLong();
        String w = TournamentsPanel.when(t, zone, now);
        if (!w.equals(whenPhrase))
        {
            whenPhrase = w;
            when.setText(wrapHtml(when.getFont(), TEXT_WIDTH_PX, w));
        }
        if (closes != null)
        {
            String c = TournamentsPanel.closes(t, zone, now);
            if (!c.equals(closesPhrase))
            {
                closesPhrase = c;
                closes.setText(wrapHtml(closes.getFont(), TEXT_WIDTH_PX, c));
            }
        }
    }

    // ---------------------------------------------------------------- rows
    /** The event's name at the card's full width, then the player's status
     *  marker (if any) on its own line. */
    private JPanel header(String myStatus)
    {
        JPanel block = new JPanel();
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setOpaque(false);
        block.setAlignmentX(LEFT_ALIGNMENT);
        JLabel name = new JLabel();
        name.setName("tournament-name-" + t.tournamentId);
        name.setFont(name.getFont().deriveFont(Font.BOLD, NAME_PT));
        name.setForeground(Color.WHITE);
        name.setAlignmentX(LEFT_ALIGNMENT);
        name.setText(wrapHtml(name.getFont(), TEXT_WIDTH_PX, t.name));
        block.add(name);
        if (myStatus != null && !myStatus.isEmpty())
        {
            boolean registered = "registered".equals(myStatus);
            JLabel my = new JLabel(registered ? "Registered" : "You: " + myStatus);
            my.setName("tournament-my-" + t.tournamentId);
            my.setFont(my.getFont().deriveFont(Font.BOLD, STATUS_PT));
            my.setForeground(registered ? GREEN : MUTED);
            my.setAlignmentX(LEFT_ALIGNMENT);
            block.add(my);
            String meeting = meetingLine(t);
            if (registered && !meeting.isEmpty())
            {
                JLabel meet = new JLabel();
                meet.setName("tournament-meeting-" + t.tournamentId);
                meet.setFont(meet.getFont().deriveFont(Font.PLAIN, STATUS_PT));
                meet.setForeground(MUTED);
                meet.setAlignmentX(LEFT_ALIGNMENT);
                meet.setText(wrapHtml(meet.getFont(), TEXT_WIDTH_PX, meeting));
                block.add(meet);
            }
        }
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height));
        return block;
    }

    /** [Swiss] [NH] [Main] — the lobby's [Region] [style] / [build] rows collapsed to one. */
    private JPanel chips()
    {
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        chips.setOpaque(false);
        chips.setName("tournament-chips-" + t.tournamentId);
        chips.setAlignmentX(LEFT_ALIGNMENT);
        chips.add(chip(formatLabel(t), CHIP_FORMAT));
        chips.add(chip(t.categoryLabel(), CHIP_CATEGORY));
        chips.add(chip(t.buildLabel(), CHIP_BUILD));
        chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, chips.getPreferredSize().height));
        return chips;
    }

    private void addLine(String namePrefix, String text, Color fg)
    {
        JLabel l = new JLabel();
        l.setName(namePrefix + t.tournamentId);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, LINE_PT));
        l.setText(wrapHtml(l.getFont(), TEXT_WIDTH_PX, text));
        l.setForeground(fg);
        l.setAlignmentX(LEFT_ALIGNMENT);
        add(l);
    }

    /** Register or Withdraw, whichever the event and the player's status
     *  allow, as the card's full-width button; in Register's place the
     *  minimum's sentence when the player is below it; {@code null} for
     *  neither. */
    private JComponent signUp(String myStatus, Actions actions, Integer myCount, boolean refused)
    {
        boolean registered = "registered".equals(myStatus);
        if (registered && (t.isOpenForRegistration() || t.isRunning()))
        {
            JButton withdraw = TournamentsPanel.tabButton("Withdraw");
            withdraw.setName("tournament-withdraw-" + t.tournamentId);
            withdraw.setBackground(RED);
            withdraw.setForeground(RED_FG);
            withdraw.addActionListener(e -> actions.withdraw(t));
            return withdraw;
        }
        if (!registered && (t.isOpenForRegistration() || (t.isRunning() && t.midEventJoins)))
        {
            if (belowMinimum(t, myCount, refused))
            {
                JLabel blocked = new JLabel();
                blocked.setName("tournament-min-games-blocked-" + t.tournamentId);
                blocked.setFont(blocked.getFont().deriveFont(Font.PLAIN, LINE_PT));
                blocked.setText(wrapHtml(blocked.getFont(), TEXT_WIDTH_PX, minGamesRefusal(t)));
                blocked.setForeground(INFO);
                blocked.setAlignmentX(LEFT_ALIGNMENT);
                return blocked;
            }
            JButton register = TournamentsPanel.tabButton("Register");
            register.setName("tournament-register-" + t.tournamentId);
            register.setBackground(GREEN);
            register.setForeground(Color.BLACK);
            register.addActionListener(e -> actions.register(t));
            return register;
        }
        return null;
    }

    /** The pair's side margins: the narrow button takes 4 px a side from the wide one. */
    static final Insets PAIR_WIDE_MARGIN = new Insets(6, 4, 6, 4);
    static final Insets PAIR_NARROW_MARGIN = new Insets(6, 12, 6, 12);
    /** Room the narrow button keeps beside its label, measured at {@link #BUTTON_PT}. */
    static final int PAIR_SPARE_PX = 8;

    /** Two buttons on one row: {@code narrow} is as wide as its label at
     *  {@link #BUTTON_PT} with its insets plus {@link #PAIR_SPARE_PX}, at
     *  every size; {@code wide} fills the rest, both at the largest size from
     *  {@link #BUTTON_PT} down that keeps {@code wide}'s label on one line;
     *  {@code narrow} gets the wider side margins. */
    static JPanel pairRow(JButton wide, JButton narrow)
    {
        wide.setMargin(PAIR_WIDE_MARGIN);
        narrow.setMargin(PAIR_NARROW_MARGIN);
        narrow.setPreferredSize(null);
        narrow.setFont(narrow.getFont().deriveFont(BUTTON_PT));
        int narrowWidth = narrow.getPreferredSize().width + PAIR_SPARE_PX;
        for (float pt = BUTTON_PT; pt >= PAIR_MIN_PT; pt--)
        {
            wide.setFont(wide.getFont().deriveFont(pt));
            narrow.setFont(narrow.getFont().deriveFont(pt));
            if (wide.getPreferredSize().width + narrowWidth + 4 <= TEXT_WIDTH_PX) break;
        }
        narrow.setPreferredSize(new Dimension(narrowWidth, narrow.getPreferredSize().height));
        narrow.setMinimumSize(new Dimension(narrowWidth, narrow.getMinimumSize().height));
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(wide, BorderLayout.CENTER);
        row.add(narrow, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** The lobby's chip: a tight bordered label, bold, coloured border + text. */
    private static JLabel chip(String text, Color color)
    {
        JLabel chip = new JLabel(text);
        chip.setFont(chip.getFont().deriveFont(Font.BOLD, CHIP_PT));
        chip.setForeground(color);
        chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color, 1), BorderFactory.createEmptyBorder(1, 5, 1, 5)));
        chip.setOpaque(false);
        return chip;
    }

    // ---------------------------------------------------------------- pure text builders
    /** {@code "Swiss"} — the format capitalised, Swiss when unset. */
    static String formatLabel(TournamentSummary t)
    {
        return t.format == null || t.format.isEmpty() ? "Swiss" : Character.toUpperCase(t.format.charAt(0)) + t.format.substring(1);
    }

    static String playersLine(TournamentSummary t)
    {
        List<String> parts = new ArrayList<>();
        if (t.roundsAuto && t.isOpenForRegistration()) parts.add(AUTO_ROUNDS_TEXT);
        else if (t.rounds > 0) parts.add(t.rounds + (t.rounds == 1 ? " round" : " rounds"));
        parts.add(t.maxPlayers > 0 ? t.registeredCount + " / " + t.maxPlayers + " players" : t.registeredCount + " registered");
        if (t.pluginRequired) parts.add("plugin required");
        return capitalise(String.join(" · ", parts));
    }

    static String prizeLine(TournamentSummary t)
    {
        List<String> parts = new ArrayList<>();
        if (t.buyInGp > 0) parts.add("buy-in " + TournamentsPanel.gp(t.buyInGp));
        parts.addAll(prizeParts(t));
        return capitalise(String.join(" · ", parts));
    }

    static List<String> prizeParts(TournamentSummary t)
    {
        String mode = t.prizeMode == null ? "" : t.prizeMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.isEmpty()) mode = "top_x";
        List<String> parts = new ArrayList<>();
        if ("none".equals(mode)) return parts;
        int topX = Math.max(1, t.prizeTopX);
        parts.add("top_x_random_y".equals(mode)
            ? "prize: top " + topX + " + " + Math.max(0, t.prizeRandomY) + " random full participants"
            : "prize: top " + topX);
        if (t.prizePoolGp > 0) parts.add("prize pool " + TournamentsPanel.gp(t.prizePoolGp));
        return parts;
    }

    /** {@code "Rank NH Rune 3 – Dragon 1"} / {@code "Min rank NH Rune 3"} / {@code "Max rank DMM Dragon 1"},
     *  one part per bucket ({@code _rank_limit_parts}); {@code ""} when the event has no limits. */
    static String rankLimitsLine(TournamentSummary t)
    {
        List<String> parts = new ArrayList<>();
        for (TournamentSummary.RankLimit l : t.rankLimits)
        {
            String label = TournamentSummary.categoryLabel(l.bucket);
            if (l.minIdx >= 0 && l.maxIdx >= 0) parts.add("rank " + label + " " + rankLabel(l.minIdx) + " – " + rankLabel(l.maxIdx));
            else if (l.minIdx >= 0) parts.add("min rank " + label + " " + rankLabel(l.minIdx));
            else if (l.maxIdx >= 0) parts.add("max rank " + label + " " + rankLabel(l.maxIdx));
        }
        return capitalise(String.join(" · ", parts));
    }

    /** {@code "50 NH matches required"}; {@code ""} when the event has no minimum. */
    static String minGamesLine(TournamentSummary t)
    {
        if (t.minGames <= 0) return "";
        return t.minGames + " " + bucketWord(t) + "matches required";
    }

    /** {@code "You need 50 NH matches to join."} */
    static String minGamesRefusal(TournamentSummary t)
    {
        return "You need " + t.minGames + " " + bucketWord(t) + "matches to join.";
    }

    /** {@code true} when the event has a minimum and the player is below it:
     *  a known count under it, or a registration already refused for it.
     *  An unknown or negative count is not below. */
    static boolean belowMinimum(TournamentSummary t, Integer myCount, boolean refused)
    {
        if (t.minGames <= 0) return false;
        if (refused) return true;
        return myCount != null && myCount >= 0 && myCount < t.minGames;
    }

    /** The event bucket's label and a space ({@code "NH "}), or nothing when the event names no bucket. */
    private static String bucketWord(TournamentSummary t)
    {
        return t.category == null || t.category.trim().isEmpty() ? "" : TournamentSummary.categoryLabel(t.category.trim()) + " ";
    }

    /** {@code "Meet on W578 · PvP Arena entrance"} ({@code "Meet on W578"} without a place); {@code ""} without a world. */
    static String meetingLine(TournamentSummary t)
    {
        if (t.meetingWorld == null) return "";
        return "Meet on " + t.meetingWorld + (t.meetingPlace == null ? "" : " · " + t.meetingPlace);
    }

    static String kitLine(TournamentSummary t)
    {
        List<String> parts = new ArrayList<>();
        parts.add("Kit: " + t.gearSet.name);
        parts.add(t.gearSet.buildLabel);
        if (t.gearSet.spellbookLabel != null) parts.add(t.gearSet.spellbookLabel);
        String where = t.location == null ? "" : t.location.trim().toLowerCase(java.util.Locale.ROOT);
        if (where.isEmpty() || where.contains("arena")) parts.add("PvP Arena duels");
        return String.join(" · ", parts);
    }

    /** {@code 18} → {@code "Rune 3"}, {@code 24} → {@code "3rd Age"}, out of range → {@code "?"}
     *  (the backend's {@code rank_label}). */
    static String rankLabel(int idx)
    {
        return idx < 0 || idx >= RANK_LABELS.length ? "?" : RANK_LABELS[idx];
    }

    /** The lobby's {@code buildRankLabels} over {@link RankUtils#THRESHOLDS} (private there). */
    private static String[] buildRankLabels()
    {
        String[] out = new String[RankUtils.THRESHOLDS.length];
        for (int i = 0; i < RankUtils.THRESHOLDS.length; i++)
        {
            String name = RankUtils.THRESHOLDS[i][0];
            String div = RankUtils.THRESHOLDS[i][1];
            out[i] = "0".equals(div) ? name : name + " " + div;
        }
        return out;
    }

    /** Plain text as {@code <html>} lines joined by {@code <br>}, each no
     *  wider than {@code widthPx} at {@code font}; a word wider than that sits
     *  on its own line. Paragraphs start on a new line. */
    static String wrapHtml(Font font, int widthPx, String... paragraphs)
    {
        List<String> escaped = new ArrayList<>();
        for (String p : paragraphs) escaped.add(TournamentsPanel.escape(p == null ? "" : p));
        return "<html>" + wrapLines(font, widthPx, escaped) + "</html>";
    }

    /** The {@code <br>}-joined lines of {@link #wrapEscaped}, without the
     *  {@code <html>} wrapper, for a label that adds its own markup after them. */
    static String wrapInner(Font font, int widthPx, String escaped)
    {
        return wrapLines(font, widthPx, java.util.Collections.singletonList(escaped == null ? "" : escaped));
    }

    /** {@link #wrapHtml} for text that is already escaped (entities, no tags). */
    static String wrapEscaped(Font font, int widthPx, String escaped)
    {
        return "<html>" + wrapLines(font, widthPx, java.util.Collections.singletonList(escaped == null ? "" : escaped)) + "</html>";
    }

    private static String wrapLines(Font font, int widthPx, List<String> paragraphs)
    {
        RowTextFit fit = new RowTextFit();
        int budget = widthPx - WRAP_SLACK_PX;
        StringBuilder out = new StringBuilder();
        for (String paragraph : paragraphs)
        {
            if (out.length() > 0) out.append("<br>");
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" "))
            {
                if (word.isEmpty()) continue;
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && fit.textWidth(unescape(candidate), font) > budget)
                {
                    out.append(line).append("<br>");
                    line.setLength(0);
                    line.append(word);
                }
                else
                {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            out.append(line);
        }
        return out.toString();
    }

    private static String unescape(String s)
    {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String capitalise(String s)
    {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
