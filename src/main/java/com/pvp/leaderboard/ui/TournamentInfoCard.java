package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.tournament.TournamentSummary;
import com.pvp.leaderboard.util.RankUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * One tournament's info card on the Tournaments list (plugin set 6,
 * operator request 2026-09-22: "a card of information, similar to the
 * matchmaking player card"). Modelled on the lobby's roster row /
 * incoming-invite card ({@code MatchmakingLobbyPanel.PlayerRow} /
 * {@code IncomingInvitePanel}): the same card background and bottom
 * divider, a bold name row with the registration marker on the right, a
 * chip row (format · category · build in the lobby's white / yellow / cyan),
 * then the facts a player needs to decide — rounds + players registered /
 * max + "plugin required", buy-in + prize (mode with its numbers, pool),
 * rank limits when the event has any, the host — the set-5 Discord-style
 * "Starts …" / "Registration closes …" lines (re-rendered by the panel's
 * 1 Hz tick through {@link #tick()}), and the actions: Register / Withdraw,
 * <b>Rules</b> (the in-panel dialog, or the site) and <b>Report an issue</b>
 * (the existing {@code tournament/report_problem}, enabled only while the
 * player is logged in with Discord — the backend's gate).
 *
 * <p>The chip painter mirrors the lobby's private {@code makeChip}; it is
 * duplicated rather than extracted because {@code MatchmakingLobbyPanel}
 * belongs to two other uncommitted sets (staging map in
 * {@code docs/PLUGIN_PROGRESS.md}). Long lines wrap the way the lobby's
 * labels do: a width-capped {@code <div>} plus literal {@code <br>} breaks,
 * because an auto-wrapped HTML label mis-measures its preferred height as
 * one line and clips (the lobby's 2026-06-03 finding). Every field comes
 * from the {@code tournament/list_response} entry; an optional field that
 * is missing renders nothing.
 */
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
    /** The lobby's ROW_FONT_PT. */
    private static final float NAME_PT = 15f;
    private static final float LINE_PT = 11f;
    private static final int CHIP_PT = 11;
    /** Greedy wrap budget per line, sized for the ~170 px the card's text has at {@link #LINE_PT}. */
    static final int WRAP_CHARS = 26;
    private static final String[] RANK_LABELS = buildRankLabels();

    private final TournamentSummary t;
    private final ZoneId zone;
    private final LongSupplier nowMs;
    private final JLabel when = new JLabel();
    /** {@code null} when the event has no registration window to show. */
    private final JLabel closes;
    private final JButton report;

    TournamentInfoCard(TournamentSummary t, String myStatus, boolean discordLoggedIn, ZoneId zone, LongSupplier nowMs, Actions actions)
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
        addLine("tournament-prize-", prizeLine(t), INFO);
        String ranks = rankLimitsLine(t);
        if (!ranks.isEmpty()) addLine("tournament-ranks-", ranks, MUTED);
        if (t.creatorName != null && !t.creatorName.trim().isEmpty()) addLine("tournament-host-", "Host: " + t.creatorName.trim(), MUTED);
        when.setName("tournament-when-" + t.tournamentId);
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
            closes.setForeground(MUTED);
            closes.setAlignmentX(LEFT_ALIGNMENT);
            add(closes);
        }
        tick();
        add(Box.createVerticalStrut(4));
        add(actionRow(myStatus, actions));
        add(Box.createVerticalStrut(3));
        report = TournamentsPanel.smallButton(REPORT_LABEL);
        report.setName("tournament-report-" + t.tournamentId);
        report.addActionListener(e -> actions.report(t));
        JPanel reportRow = new JPanel(new BorderLayout());
        reportRow.setOpaque(false);
        reportRow.setAlignmentX(LEFT_ALIGNMENT);
        reportRow.add(report, BorderLayout.CENTER);
        add(reportRow);
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
        if (!w.equals(when.getText())) when.setText(w);
        if (closes != null)
        {
            String c = TournamentsPanel.closes(t, zone, now);
            if (!c.equals(closes.getText())) closes.setText(c);
        }
    }

    // ---------------------------------------------------------------- rows
    private JPanel header(String myStatus)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel name = new JLabel("<html>" + TournamentsPanel.escape(t.name) + "</html>");
        name.setName("tournament-name-" + t.tournamentId);
        name.setFont(name.getFont().deriveFont(Font.BOLD, NAME_PT));
        name.setForeground(Color.WHITE);
        row.add(name, BorderLayout.CENTER);
        if (myStatus != null && !myStatus.isEmpty())
        {
            boolean registered = "registered".equals(myStatus);
            JLabel my = new JLabel(registered ? "✓ Registered" : "You: " + myStatus);
            my.setName("tournament-my-" + t.tournamentId);
            my.setFont(my.getFont().deriveFont(Font.BOLD, 12f));
            my.setForeground(registered ? GREEN : MUTED);
            row.add(my, BorderLayout.EAST);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
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
        JLabel l = new JLabel(wrapHtml(text));
        l.setName(namePrefix + t.tournamentId);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, LINE_PT));
        l.setForeground(fg);
        l.setAlignmentX(LEFT_ALIGNMENT);
        add(l);
    }

    private JPanel actionRow(String myStatus, Actions actions)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        boolean registered = "registered".equals(myStatus);
        if (registered && (t.isOpenForRegistration() || t.isRunning()))
        {
            JButton withdraw = TournamentsPanel.smallButton("Withdraw");
            withdraw.setName("tournament-withdraw-" + t.tournamentId);
            withdraw.setBackground(RED);
            withdraw.setForeground(RED_FG);
            withdraw.addActionListener(e -> actions.withdraw(t));
            row.add(withdraw);
            row.add(Box.createHorizontalStrut(4));
        }
        else if (!registered && (t.isOpenForRegistration() || (t.isRunning() && t.midEventJoins)))
        {
            JButton register = TournamentsPanel.smallButton("Register");
            register.setName("tournament-register-" + t.tournamentId);
            register.setBackground(GREEN);
            register.setForeground(Color.BLACK);
            register.addActionListener(e -> actions.register(t));
            row.add(register);
            row.add(Box.createHorizontalStrut(4));
        }
        JButton rules = TournamentsPanel.smallButton("Rules");
        rules.setName("tournament-rules-" + t.tournamentId);
        rules.addActionListener(e -> actions.rules(t));
        row.add(rules);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /** The lobby's chip: a tight bordered label, bold, coloured border + text. */
    private static JLabel chip(String text, Color color)
    {
        JLabel chip = new JLabel(text);
        chip.setFont(chip.getFont().deriveFont(Font.BOLD, (float) CHIP_PT));
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

    /** {@code "5 rounds · 17 / 64 players · plugin required"} — only the parts the event has
     *  ({@code "17 registered"} without a cap). */
    static String playersLine(TournamentSummary t)
    {
        List<String> parts = new ArrayList<>();
        if (t.rounds > 0) parts.add(t.rounds + (t.rounds == 1 ? " round" : " rounds"));
        parts.add(t.maxPlayers > 0 ? t.registeredCount + " / " + t.maxPlayers + " players" : t.registeredCount + " registered");
        if (t.pluginRequired) parts.add("plugin required");
        return String.join(" · ", parts);
    }

    /** {@code "Buy-in 2M · prize: top 3 + 2 random full participants · prize pool 10M"} —
     *  the prize wording of {@code discord_tournament_messages.describe_tournament};
     *  buy-in and pool only when non-zero. */
    static String prizeLine(TournamentSummary t)
    {
        List<String> parts = new ArrayList<>();
        if (t.buyInGp > 0) parts.add("buy-in " + TournamentsPanel.gp(t.buyInGp));
        int topX = Math.max(1, t.prizeTopX);
        parts.add("top_x_random_y".equals(t.prizeMode)
            ? "prize: top " + topX + " + " + Math.max(0, t.prizeRandomY) + " random full participants"
            : "prize: top " + topX);
        if (t.prizePoolGp > 0) parts.add("prize pool " + TournamentsPanel.gp(t.prizePoolGp));
        return capitalise(String.join(" · ", parts));
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

    /** Escaped text broken with a literal {@code <br>} at a space every
     *  ~{@link #WRAP_CHARS} characters, inside the width-capped {@code <div>}
     *  the lobby's labels use — see the class Javadoc for why not auto-wrap. */
    static String wrapHtml(String text)
    {
        StringBuilder sb = new StringBuilder("<html><div style='width:170px'>");
        int col = 0;
        for (String word : text.split(" "))
        {
            if (word.isEmpty()) continue;
            if (col > 0 && col + 1 + word.length() > WRAP_CHARS)
            {
                sb.append("<br>");
                col = 0;
            }
            else if (col > 0)
            {
                sb.append(' ');
                col++;
            }
            sb.append(TournamentsPanel.escape(word));
            col += word.length();
        }
        return sb.append("</div></html>").toString();
    }

    private static String capitalise(String s)
    {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
