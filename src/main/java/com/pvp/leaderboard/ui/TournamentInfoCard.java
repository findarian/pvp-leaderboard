package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.tournament.*;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import java.util.List;
import static com.pvp.leaderboard.ui.TourneyPanel.*;
import static com.pvp.leaderboard.ui.Ui.*;
import static java.awt.Font.*;

final class TournamentInfoCard extends CapPanel
{
    static final String REPORT_LABEL = "Report an issue";
    static final String REPORT_TOOLTIP = "Report an issue with this tournament — the message goes to its host on Discord";
    static final String LOGIN_TIP = "Log in with Discord to contact the host";

    /** The lobby's chip palette: region white, style yellow, build cyan. */
    private static final Color CHIP_FORMAT = Color.WHITE;
    private static final Color CHIP_CATEGORY = new Color(0xffc107);
    private static final Color CHIP_BUILD = new Color(0x4fc3f7);
    private static final float LINE_PT = 15f;
    private static final float CHIP_PT = 14f;
    /** Card buttons: the full-width one, and the most / least the Report + Rules row may use. */
    static final float BUTTON_PT = 16f;
    /** The width a card's text wraps to (the side panel's card, inside its border). */
    static final int TEXT_WIDTH = 170;

    private final Tourney t;
    private final ZoneId zone;
    private final LongSupplier nowMs;
    private final JLabel when;
    /** {@code null} when the event has no registration window to show. */
    private final JLabel closes;
    private final JButton report;

    /** {@code myCount} is the player's own match count in the event's bucket
     *  ({@code null} when unknown); {@code refused} marks an event whose
     *  registration was refused for its minimum. */
    TournamentInfoCard(Tourney t, String myStatus, boolean discordLoggedIn, ZoneId zone, LongSupplier nowMs, TourneyPanel p,
                       Integer myCount, boolean refused)
    {
        this.t = t;
        this.zone = zone;
        this.nowMs = nowMs;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setName("tournament-card-" + t.tournamentId);
        cardStyle(this);

        header(myStatus);
        add(vgap(3));
        add(chips());
        add(vgap(3));
        addLine("tournament-players-", playersLine(t), INFO);
        String prize = prizeLine(t);
        if (!prize.isEmpty()) addLine("tournament-prize-", prize, INFO);
        String ranks = limitsLine(t);
        if (!ranks.isEmpty()) addLine("tournament-ranks-", ranks, MUTED);
        String need = minGamesLine(t);
        if (!need.isEmpty()) addLine("tournament-min-games-", need, MUTED);
        if (t.creatorName != null && !t.creatorName.trim().isEmpty()) addLine("tournament-host-", "Host: " + t.creatorName.trim(), MUTED);
        if (t.gearSet != null) addLine("tournament-kit-", kitLine(t), INFO);
        when = label("tournament-when-" + t.tournamentId, "", PLAIN, LINE_PT, null);
        add(when);
        if (closes(t, zone, nowMs.getAsLong()).isEmpty())
        {
            closes = null;
        }
        else
        {
            closes = label("tournament-closes-" + t.tournamentId, "", PLAIN, LINE_PT, MUTED);
            add(closes);
        }
        tick();
        add(vgap(6));
        report = tabButton(REPORT_LABEL, "tournament-report-" + t.tournamentId, () -> p.onReport(t.tournamentId));
        add(pairRow(report, tabButton("Rules", "tournament-rules-" + t.tournamentId, () -> p.openRules(t))));
        JComponent signUp = signUp(myStatus, p, myCount, refused);
        if (signUp != null)
        {
            add(vgap(4));
            add(signUp);
        }
        setReportEnabled(discordLoggedIn);
    }

    /** The Report gate: enabled + the host tooltip while logged in with
     *  Discord, disabled + the login hint otherwise. */
    void setReportEnabled(boolean discordLoggedIn)
    {
        report.setEnabled(discordLoggedIn);
        report.setToolTipText(discordLoggedIn ? REPORT_TOOLTIP : LOGIN_TIP);
    }

    /** Re-renders the two time lines (the panel's 1 Hz beat); a label is set only when its text moved. */
    void tick()
    {
        long now = nowMs.getAsLong();
        setWrapped(when, when(t, zone, now));
        if (closes != null) setWrapped(closes, closes(t, zone, now));
    }

    // ---------------------------------------------------------------- rows
    /** The event's name at the card's full width, then the player's status
     *  marker (if any) on its own line. */
    private void header(String myStatus)
    {
        JLabel name = label("tournament-name-" + t.tournamentId, "", BOLD, BUTTON_PT, Color.WHITE);
        wrap(name, t.name);
        add(name);
        if (myStatus != null && !myStatus.isEmpty())
        {
            boolean registered = "registered".equals(myStatus);
            add(label("tournament-my-" + t.tournamentId, registered ? "Registered" : "You: " + myStatus, BOLD, CHIP_PT, registered ? GREEN : MUTED));
            String meeting = meetingLine(t);
            if (registered && !meeting.isEmpty())
            {
                JLabel meet = label("tournament-meeting-" + t.tournamentId, "", PLAIN, CHIP_PT, MUTED);
                wrap(meet, meeting);
                add(meet);
            }
        }
    }

    /** [Swiss] [NH] [Main] — the lobby's [Region] [style] / [build] rows collapsed to one. */
    private JPanel chips()
    {
        var chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        chips.setOpaque(false);
        chips.setName("tournament-chips-" + t.tournamentId);
        chips.add(chip(formatLabel(t), CHIP_FORMAT));
        chips.add(chip(t.categoryLabel(), CHIP_CATEGORY));
        chips.add(chip(t.buildLabel(), CHIP_BUILD));
        return pin(left(chips));
    }

    private void addLine(String namePrefix, String text, Color fg)
    {
        JLabel l = label(namePrefix + t.tournamentId, "", PLAIN, LINE_PT, fg);
        wrap(l, text);
        add(l);
    }

    /** Register or Withdraw, whichever the event and the player's status
     *  allow, as the card's full-width button; in Register's place the
     *  minimum's sentence when the player is below it; {@code null} for
     *  neither. */
    private JComponent signUp(String myStatus, TourneyPanel p, Integer myCount, boolean refused)
    {
        boolean registered = "registered".equals(myStatus);
        if (registered && (t.isRegOpen() || t.isRunning()))
        {
            JButton withdraw = tabButton("Withdraw", "tournament-withdraw-" + t.tournamentId, () -> p.service.withdraw(t.tournamentId));
            withdraw.setBackground(RED);
            withdraw.setForeground(RED_FG);
            return withdraw;
        }
        if (!registered && (t.isRegOpen() || (t.isRunning() && t.lateJoins)))
        {
            if (belowMinimum(t, myCount, refused))
            {
                JLabel blocked = label("tournament-min-games-blocked-" + t.tournamentId, "", PLAIN, LINE_PT, INFO);
                wrap(blocked, gamesRefusal(t));
                return blocked;
            }
            JButton register = tabButton("Register", "tournament-register-" + t.tournamentId, () -> p.register(t));
            register.setBackground(GREEN);
            register.setForeground(Color.BLACK);
            return register;
        }
        return null;
    }

    /** The pair's side margins: the narrow button takes 4 px a side from the wide one. */
    static final Insets WIDE_MARGIN = new Insets(6, 4, 6, 4);
    static final Insets SLIM_MARGIN = new Insets(6, 12, 6, 12);

    /** Two buttons on one row: {@code narrow} is as wide as its label at
     *  {@link #BUTTON_PT} with its insets plus 8 px of spare room, at
     *  every size; {@code wide} fills the rest, both at the largest size from
     *  {@link #BUTTON_PT} down that keeps {@code wide}'s label on one line;
     *  {@code narrow} gets the wider side margins. */
    static JPanel pairRow(JButton wide, JButton narrow)
    {
        wide.setMargin(WIDE_MARGIN);
        narrow.setMargin(SLIM_MARGIN);
        narrow.setPreferredSize(null);
        narrow.setFont(narrow.getFont().deriveFont(BUTTON_PT));
        int narrowWidth = narrow.getPreferredSize().width + 8;
        for (float pt = BUTTON_PT; pt >= CHIP_PT; pt--)
        {
            wide.setFont(wide.getFont().deriveFont(pt));
            narrow.setFont(narrow.getFont().deriveFont(pt));
            if (wide.getPreferredSize().width + narrowWidth + 4 <= TEXT_WIDTH) break;
        }
        narrow.setPreferredSize(new Dimension(narrowWidth, narrow.getPreferredSize().height));
        narrow.setMinimumSize(new Dimension(narrowWidth, narrow.getMinimumSize().height));
        var row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(wide, BorderLayout.CENTER);
        row.add(narrow, BorderLayout.EAST);
        return pin(left(row));
    }

    /** The lobby's chip: a tight bordered label, bold, coloured border + text. */
    private static JLabel chip(String text, Color color)
    {
        JLabel chip = label(null, text, BOLD, CHIP_PT, color);
        chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color, 1), pad(1, 5, 1, 5)));
        chip.setOpaque(false);
        return chip;
    }

    // ---------------------------------------------------------------- pure text builders
    /** {@code "Swiss"} — the format capitalised, Swiss when unset. */
    static String formatLabel(Tourney t)
    {
        return t.format.isEmpty() ? "Swiss" : Character.toUpperCase(t.format.charAt(0)) + t.format.substring(1);
    }

    static String playersLine(Tourney t)
    {
        List<String> parts = new ArrayList<>();
        if (t.roundsAuto && t.isRegOpen()) parts.add("rounds: auto (set at the start)");
        else if (t.rounds > 0) parts.add(t.rounds + (t.rounds == 1 ? " round" : " rounds"));
        parts.add(t.maxPlayers > 0 ? t.regCount + " / " + t.maxPlayers + " players" : t.regCount + " registered");
        if (t.pluginRequired) parts.add("plugin required");
        return capitalise(String.join(" · ", parts));
    }

    static String prizeLine(Tourney t)
    {
        List<String> parts = new ArrayList<>();
        if (t.buyInGp > 0) parts.add("buy-in " + gp(t.buyInGp));
        String mode = t.prizeMode.trim().toLowerCase(Locale.ROOT);
        if (!"none".equals(mode))
        {
            int topX = Math.max(1, t.prizeTopX);
            parts.add("top_x_random_y".equals(mode)
                ? "prize: top " + topX + " + " + Math.max(0, t.prizeRandomY) + " random full participants"
                : "prize: top " + topX);
            if (t.prizePoolGp > 0) parts.add("prize pool " + gp(t.prizePoolGp));
        }
        return capitalise(String.join(" · ", parts));
    }

    /** {@code "Rank NH Rune 3 – Dragon 1"} / {@code "Min rank NH Rune 3"} / {@code "Max rank DMM Dragon 1"},
     *  one part per bucket ({@code _rank_limit_parts}); {@code ""} when the event has no limits. */
    static String limitsLine(Tourney t)
    {
        List<String> parts = new ArrayList<>();
        for (Tourney.RankLimit l : t.rankLimits)
        {
            String label = Tourney.categoryLabel(l.bucket);
            if (l.minIdx >= 0 && l.maxIdx >= 0) parts.add("rank " + label + " " + rankLabel(l.minIdx) + " – " + rankLabel(l.maxIdx));
            else if (l.minIdx >= 0) parts.add("min rank " + label + " " + rankLabel(l.minIdx));
            else if (l.maxIdx >= 0) parts.add("max rank " + label + " " + rankLabel(l.maxIdx));
        }
        return capitalise(String.join(" · ", parts));
    }

    /** {@code "50 NH matches required"}; {@code ""} when the event has no minimum. */
    static String minGamesLine(Tourney t)
    {
        if (t.minGames <= 0) return "";
        return t.minGames + " " + bucketWord(t) + "matches required";
    }

    /** {@code "You need 50 NH matches to join."} */
    static String gamesRefusal(Tourney t)
    {
        return "You need " + t.minGames + " " + bucketWord(t) + "matches to join.";
    }

    /** {@code true} when the event has a minimum and the player is below it:
     *  a known count under it, or a registration already refused for it.
     *  An unknown count is not below. */
    static boolean belowMinimum(Tourney t, Integer myCount, boolean refused)
    {
        if (t.minGames <= 0) return false;
        if (refused) return true;
        return myCount != null && myCount < t.minGames;
    }

    /** The event bucket's label and a space ({@code "NH "}), or nothing when the event names no bucket. */
    private static String bucketWord(Tourney t)
    {
        return t.category.trim().isEmpty() ? "" : Tourney.categoryLabel(t.category.trim()) + " ";
    }

    /** {@code "Meet on W578 · PvP Arena entrance"} ({@code "Meet on W578"} without a place); {@code ""} without a world. */
    static String meetingLine(Tourney t)
    {
        if (t.meetingWorld == null) return "";
        return "Meet on " + t.meetingWorld + (t.meetingPlace == null ? "" : " · " + t.meetingPlace);
    }

    static String kitLine(Tourney t)
    {
        List<String> parts = new ArrayList<>();
        parts.add("Kit: " + t.gearSet.name);
        parts.add(t.gearSet.buildLabel);
        if (t.gearSet.bookLabel != null) parts.add(t.gearSet.bookLabel);
        String where = t.location == null ? "" : t.location.trim().toLowerCase(Locale.ROOT);
        if (where.isEmpty() || where.contains("arena")) parts.add("PvP Arena duels");
        return String.join(" · ", parts);
    }

    /** {@code 18} → {@code "Rune 3"}, {@code 24} → {@code "3rd Age"}, out of range → {@code "?"}
     *  (the backend's {@code rank_label}). */
    static String rankLabel(int idx)
    {
        return idx < 0 || idx >= PlayerCard.RANK_LABELS.length ? "?" : PlayerCard.RANK_LABELS[idx];
    }

    /** Plain text as {@code <html>} lines joined by {@code <br>}, each no
     *  wider than {@code widthPx} at {@code font}; a word wider than that sits
     *  on its own line. Paragraphs start on a new line. */
    static String wrapHtml(Font font, int widthPx, String... paragraphs)
    {
        List<String> escaped = new ArrayList<>();
        for (String p : paragraphs) escaped.add(escape(p == null ? "" : p));
        return "<html>" + wrapLines(font, widthPx, escaped) + "</html>";
    }

    /** The {@code <br>}-joined lines of {@link #wrapEscaped}, without the
     *  {@code <html>} wrapper, for a label that adds its own markup after them. */
    static String wrapInner(Font font, int widthPx, String escaped)
    {
        return wrapLines(font, widthPx, Collections.singletonList(escaped == null ? "" : escaped));
    }

    /** {@link #wrapHtml} for text that is already escaped (entities, no tags). */
    static String wrapEscaped(Font font, int widthPx, String escaped)
    {
        return "<html>" + wrapInner(font, widthPx, escaped) + "</html>";
    }

    private static String wrapLines(Font font, int widthPx, List<String> paragraphs)
    {
        var fit = new RowTextFit();
        int budget = widthPx - 4;
        var out = new StringBuilder();
        for (String paragraph : paragraphs)
        {
            if (out.length() > 0) out.append("<br>");
            var line = new StringBuilder();
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
