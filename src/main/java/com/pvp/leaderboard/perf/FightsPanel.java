package com.pvp.leaderboard.perf;

import com.google.gson.*;
import com.pvp.leaderboard.config.StreakBucket;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.ui.*;
import com.pvp.leaderboard.util.NameUtils;
import com.pvp.leaderboard.util.RankUtils;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

/** The Performance tab: your fights with the stats PvP Leaderboard stored (Completed fights), or every fight this
 *  computer tracked, finished or not (All fights). */
public class FightsPanel extends JPanel
{
    static final String COMPLETED = "completed fights", ALL = "all fights";
    static final String[] LABELS = {"Off-pray", "Expected damage", "Damage", "Magic luck", "Offensive prayer",
        "HP healed", "Hits on robes", "KO chances", "Ghost barrages"};
    static final String[] SHORT = {"OP", "eD", "D", "M", "P", "HP", "rH", "KO", "GB"};
    private static final String[] KEYS = {"op", "ed", "d", "mh", "pr", "hp", "rh", "ko", "gb"};
    /** Per row: 1 when the higher value is better, -1 when the lower is, 0 when the row is not compared. */
    private static final int[] BETTER = {0, 1, 1, 1, 0, 0, -1, 1, 0};
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT);
    static final Color WIN_BG = new Color(0x2b3a30), LOSS_BG = new Color(0x3c2b2b), WORSE = new Color(0xff6b6b);
    /** The width a card's lines fit in: the side panel's 225 px less its padding and the card's. */
    static final int WIDTH = 190;
    /** The least space between a value and its row's label. */
    private static final int GAP = 3;
    /** How far apart, in seconds, a completed fight and this computer's record of it may end. */
    private static final long SAME_FIGHT = 60;

    private final PvpApi pvpApi;
    private final FightLog fightLog;
    private final Supplier<String> selfName;
    private final JPanel bar;
    private final JPanel list = Ui.column();
    private String view = COMPLETED;
    private int gen;
    /** The name and character the kept cards were built for; others build both views again. */
    private String builtFor;
    /** Each view's built cards; null to build (or load) them again. */
    private List<JComponent> allCards, completedCards;
    /** The end of the newest sent result Completed fights has not shown yet (0 for none), and of the newest that has. */
    private long awaiting, shown;
    /** A sent result's tick asked for one more load that has not run yet. */
    private boolean due;

    public FightsPanel(PvpApi pvpApi, FightLog fightLog, Supplier<String> selfName)
    {
        super(new BorderLayout(0, 6));
        this.pvpApi = pvpApi;
        this.fightLog = fightLog;
        this.selfName = selfName;
        bar = Ui.bucketBar("perf-views", "perf-view", 1, 2, new String[]{"Completed fights", "All fights"}, this::show);
        Ui.styleBucketBar(bar, view);
        list.setName("perf-fights");
        add(bar, BorderLayout.NORTH);
        add(list, BorderLayout.CENTER);
        fightLog.setListener(() -> SwingUtilities.invokeLater(() -> {
            allCards = null;
            if (ALL.equals(view)) refresh();
        }));
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
        });
    }

    /** A tick of a sent result (5, 15 and 45 s after it): Completed fights loads once more, skipping the cache, at once
     *  when the tab is open, else when Completed next shows; nothing for a fight that already shows. */
    public void sent(long endSec)
    {
        SwingUtilities.invokeLater(() -> {
            if (endSec <= shown) return;
            awaiting = Math.max(awaiting, endSec);
            due = true;
            if (COMPLETED.equals(view) && isShowing()) refresh();
        });
    }

    /** A login: the open tab is built again when the character or the name changed. */
    public void characterReady()
    {
        SwingUtilities.invokeLater(() -> {
            if (isShowing()) refresh();
        });
    }

    private void show(String key)
    {
        view = key;
        Ui.styleBucketBar(bar, key);
        refresh();
    }

    /** Shows the chosen view's kept cards, building them first when there are none; Completed fights come from the
     *  server. */
    public void refresh()
    {
        String name = selfName.get(), key = name + "|" + fightLog.character();
        if (!key.equals(builtFor))
        {
            builtFor = key;
            allCards = null;
            completedCards = null;
        }
        if (ALL.equals(view))
        {
            if (allCards == null) allCards = buildAll(name);
            fill(allCards, "No fights tracked yet");
            return;
        }
        if (completedCards != null)
        {
            fill(completedCards, "No fights with stats yet");
            if (!due) return;
        }
        load(name);
    }

    private List<JComponent> buildAll(String name)
    {
        List<JComponent> cards = new ArrayList<>();
        for (FightLog.Fight f : fightLog.all())
        {
            cards.add(card(f.result, when(f.endSec), "W" + f.world, null, f.who == null ? name : f.who, f.myRank, f.opponent,
                f.theirRank, f.mine, f.theirs, null));
        }
        return cards;
    }

    /** Completed fights from the server, skipping its cache for a sent result's tick. */
    private void load(String name)
    {
        int g = ++gen;
        String sha = pvpApi.getSelfSha(), key = builtFor;
        if (sha == null && name == null)
        {
            fill(Collections.emptyList(), "Log in to see your fights");
            return;
        }
        boolean fresh = due;
        due = false;
        if (completedCards == null) fill(Collections.emptyList(), "Loading...");
        pvpApi.getMatches(sha, name, null, 100, fresh).whenComplete((json, ex) -> SwingUtilities.invokeLater(() -> {
            if (g != gen || !key.equals(builtFor)) return;
            JsonArray rows = json == null ? null : optArray(json, "matches");
            if (rows != null)
            {
                completedCards = completed(rows, name);
                if (awaiting != 0 && holds(rows, awaiting))
                {
                    shown = awaiting;
                    awaiting = 0;
                }
            }
            if (COMPLETED.equals(view)) fill(completedCards, "No fights with stats yet");
        }));
    }

    /** Whether {@code rows} hold the fight that ended at {@code end} (within 60 s). */
    private static boolean holds(JsonArray rows, long end)
    {
        for (JsonElement e : rows)
        {
            if (e.isJsonObject() && optDouble(e.getAsJsonObject(), "when", 0) >= end - SAME_FIGHT) return true;
        }
        return false;
    }

    private List<JComponent> completed(JsonArray matches, String name)
    {
        List<JComponent> cards = new ArrayList<>();
        List<FightLog.Fight> tracked = fightLog.all();
        String[] none = new String[9];
        Arrays.fill(none, "-");
        for (JsonElement e : matches == null ? new JsonArray() : matches)
        {
            JsonObject m = e.isJsonObject() ? e.getAsJsonObject() : null;
            JsonObject p = m == null ? null : optObject(m, "perf");
            if (p == null) continue;
            String[] mine = new String[9];
            for (int i = 0; i < 9; i++) mine[i] = optString(p, KEYS[i], "-");
            String as = optString(m, "participant_id", ""), opponent = optString(m, "opponent_id", "?");
            long end = (long) optDouble(m, "when", 0);
            FightLog.Fight local = tracked(tracked, opponent, end);
            cards.add(card(optString(m, "result", ""), when(end), local == null ? null : "W" + local.world, style(m),
                as.isEmpty() ? name : as, rank(m, "v3_to"), opponent, rank(m, "v3_opponent_to"), mine,
                local == null ? none : local.theirs, change(m)));
        }
        return cards;
    }

    /** A match row's style as players see it (NH, Veng, Multi, DMM, Tournament); null for none. */
    private static String style(JsonObject m)
    {
        StreakBucket b = StreakBucket.forKey(optString(m, "bucket", ""));
        return b == null ? null : b.label;
    }

    /** A match row's rating change in the new system as shown: "+7.9", "-9.7", "0.0"; "-" for none. */
    public static String change(JsonObject m)
    {
        return change(optDouble(m, "v3_change", Double.NaN));
    }

    /** A rating change as shown: "+7.9", "-9.7", "0.0"; "-" for none (NaN). */
    public static String change(double v)
    {
        if (Double.isNaN(v)) return "-";
        double r = Math.round(v * 10) / 10.0;
        return r == 0 ? "0.0" : String.format(Locale.ROOT, "%+.1f", r);
    }

    /** A match row's division in the new rating system ("Adamant 2"); null for none. */
    private static String rank(JsonObject m, String key)
    {
        String r = optString(m, key, "");
        return r.isEmpty() ? null : r;
    }

    /** This computer's record of the fight against {@code opponent} that ended at {@code end}: the nearest one within
     *  60 s; null for none. */
    static FightLog.Fight tracked(List<FightLog.Fight> fights, String opponent, long end)
    {
        String key = NameUtils.canonicalKey(opponent);
        FightLog.Fight best = null;
        for (FightLog.Fight f : fights)
        {
            long apart = Math.abs(f.endSec - end);
            if (apart <= SAME_FIGHT && key.equals(NameUtils.canonicalKey(f.opponent))
                && (best == null || apart < Math.abs(best.endSec - end)))
            {
                best = f;
            }
        }
        return best;
    }

    /** The cards, or {@code empty} when there are none; null cards means the load failed. */
    private void fill(List<JComponent> cards, String empty)
    {
        list.removeAll();
        if (cards == null || cards.isEmpty())
        {
            list.add(Ui.label("perf-note", cards == null ? "Could not load fights" : empty, Font.ITALIC, 11f, Ui.MUTED));
        }
        else
        {
            for (JComponent c : cards) list.add(c);
        }
        list.revalidate();
        list.repaint();
    }

    static String when(long sec)
    {
        return WHEN.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()));
    }

    /** One fight: its date, world (when known) and result on the top line, each player's rank above their name in
     *  the rank's colour (yours left, theirs right) with the style between them, the nine rows, then your rating
     *  change ({@code mmr}, no row for null); tinted for a win or a loss. */
    static JComponent card(String result, String date, String world, String style, String myName, String myRank,
        String theirName, String theirRank, String[] mine, String[] theirs, String mmr)
    {
        JPanel c = Ui.column();
        Ui.cardStyle(c);
        c.setName("perf-card");
        String res = result.toLowerCase(Locale.ROOT);
        c.setBackground("win".equals(res) ? WIN_BG : "loss".equals(res) ? LOSS_BG : Ui.CARD_BG);
        String r = res.isEmpty() ? "" : Character.toUpperCase(res.charAt(0)) + res.substring(1);
        c.add(vs(text(date, Font.PLAIN, Ui.MUTED), world == null ? null : text(world, Font.PLAIN, Ui.MUTED),
            text(r, Font.BOLD, "win".equals(res) ? Ui.GREEN : "loss".equals(res) ? WORSE : Ui.MUTED)));
        if (myRank != null || theirRank != null || style != null)
        {
            c.add(vs(text(myRank, Font.PLAIN, tint(myRank)), style == null ? null : text(style, Font.PLAIN, null),
                text(theirRank, Font.PLAIN, tint(theirRank))));
        }
        c.add(vs(text(myName == null ? "You" : myName, Font.BOLD, tint(myRank)), null,
            text(theirName, Font.BOLD, tint(theirRank))));
        for (int i = 0; i < 9; i++)
        {
            Color[] tone = tones(i, mine[i], theirs[i]);
            c.add(vs(text(shown(i, mine[i]), Font.PLAIN, tone[0]), text(LABELS[i], Font.PLAIN, Ui.MUTED),
                text(shown(i, theirs[i]), Font.PLAIN, tone[1])));
        }
        if (mmr != null)
        {
            Color tone = mmr.startsWith("+") ? Ui.GREEN : mmr.length() > 1 && mmr.startsWith("-") ? WORSE : null;
            c.add(vs(text(mmr, Font.PLAIN, tone), text("MMR", Font.PLAIN, Ui.MUTED), text("-", Font.PLAIN, null)));
        }
        return Ui.pin(c);
    }

    private static JLabel text(String s, int style, Color fg)
    {
        return Ui.label(null, s == null ? "" : s, style, Ui.smallFont().getSize2D(), fg);
    }

    private static Color tint(String rank)
    {
        return rank == null ? null : RankUtils.getRankColor(rank);
    }

    /** {@code left}, {@code mid} and {@code right} on one line when they fit the card; else {@code mid} above the other
     *  two, which share a line when they fit and take one each when not. */
    private static JComponent vs(JLabel left, JLabel mid, JLabel right)
    {
        int l = left.getPreferredSize().width, r = right.getPreferredSize().width;
        int m = mid == null ? 0 : mid.getPreferredSize().width;
        if (l + m + r + 2 * GAP <= WIDTH) return line(left, mid, right);
        JPanel lines = Ui.column();
        lines.setOpaque(false);
        if (mid != null) lines.add(line(null, mid, null));
        if (l + r + GAP <= WIDTH)
        {
            lines.add(line(left, null, right));
        }
        else
        {
            lines.add(line(left, null, null));
            lines.add(line(null, null, right));
        }
        return Ui.left(lines);
    }

    private static JPanel line(JLabel left, JLabel mid, JLabel right)
    {
        JPanel p = new JPanel(new Line());
        p.setOpaque(false);
        p.add(left == null ? new JLabel() : left);
        p.add(mid == null ? new JLabel() : mid);
        if (right != null) right.setHorizontalAlignment(SwingConstants.RIGHT);
        p.add(right == null ? new JLabel() : right);
        return Ui.left(p);
    }

    /** One line of three labels: the first at the left edge, the last at the right edge, the middle one centred or
     *  moved just enough to clear a long value beside it. */
    private static final class Line implements LayoutManager
    {
        @Override
        public void addLayoutComponent(String name, Component c)
        {
        }

        @Override
        public void removeLayoutComponent(Component c)
        {
        }

        @Override
        public Dimension preferredLayoutSize(Container p)
        {
            int w = 2 * GAP, h = 0;
            for (Component c : p.getComponents())
            {
                Dimension d = c.getPreferredSize();
                w += d.width;
                h = Math.max(h, d.height);
            }
            return new Dimension(w, h);
        }

        @Override
        public Dimension minimumLayoutSize(Container p)
        {
            return preferredLayoutSize(p);
        }

        @Override
        public void layoutContainer(Container p)
        {
            int w = p.getWidth(), h = p.getHeight();
            Component left = p.getComponent(0), mid = p.getComponent(1), right = p.getComponent(2);
            int l = left.getPreferredSize().width, m = mid.getPreferredSize().width, r = right.getPreferredSize().width;
            left.setBounds(0, 0, l, h);
            right.setBounds(w - r, 0, r, h);
            mid.setBounds(Math.max(l + GAP, Math.min((w - m) / 2, w - r - GAP - m)), 0, m, h);
        }
    }

    /** Row {@code i}'s two value colours: green for the better side, red for the worse; none when the row is not
     *  compared, the two are equal or either cannot be read. */
    static Color[] tones(int i, String mine, String theirs)
    {
        double a = score(i, mine), b = score(i, theirs);
        if (BETTER[i] == 0 || Double.isNaN(a) || Double.isNaN(b) || a == b) return new Color[2];
        return (a > b) == (BETTER[i] > 0) ? new Color[]{Ui.GREEN, WORSE} : new Color[]{WORSE, Ui.GREEN};
    }

    /** Row {@code i}'s value as compared: expected damage and damage as numbers, magic hits as the luck % shown,
     *  hits on robes as a share, KO chances as their total %; NaN when it cannot be read. */
    static double score(int i, String raw)
    {
        String[] p = raw == null ? new String[0] : raw.split("/");
        try
        {
            if ((i == 1 || i == 2) && p.length == 1) return Double.parseDouble(p[0]);
            if (i == 3 && p.length == 3 && Double.parseDouble(p[2]) > 0)
            {
                return Math.round(Integer.parseInt(p[0]) / Double.parseDouble(p[2]) * 100);
            }
            if (i == 6 && p.length == 2 && Double.parseDouble(p[1]) > 0)
            {
                return Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
            }
            if (i == 7 && p.length == 2) return Double.parseDouble(p[1]);
        }
        catch (NumberFormatException e)
        {
            return Double.NaN;
        }
        return Double.NaN;
    }

    /** A stored value as shown: magic hits with their luck, KO chances and ghost barrages with their total. */
    static String shown(int i, String raw)
    {
        String[] p = raw.split("/");
        try
        {
            if (i == 3 && p.length == 3)
            {
                double expected = Double.parseDouble(p[2]);
                return p[0] + "/" + p[1] + (expected > 0 ? " (" + Math.round(Integer.parseInt(p[0]) / expected * 100) + "%)" : "");
            }
            if (i == 7 && p.length == 2) return p[0] + " (" + p[1] + "%)";
            if (i == 8 && p.length == 2) return p[0] + " (" + p[1] + ")";
        }
        catch (NumberFormatException e)
        {
            return raw;
        }
        return raw;
    }
}
