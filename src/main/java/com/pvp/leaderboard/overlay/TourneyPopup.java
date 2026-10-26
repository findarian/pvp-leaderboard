package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.config.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.Deque;
import java.util.List;
import java.util.function.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.client.config.*;
import net.runelite.client.ui.*;
import static java.awt.RenderingHints.*;

/**
 * The tournament box centred at the top of the game view: a round's pairing or a start reminder, one at a time, its
 * first line the title. The frame is 80% opaque and the text solid. A box waits while a fight is in progress, and its
 * time starts once it shows. Don't show this again (bottom left) turns that kind's setting off; Dismiss (bottom right)
 * closes the box.
 */
@Slf4j
public final class TourneyPopup extends Popup
{
    static final int W = 310, PAD = 8, TOP = 22, BTN_H = 22;
    static final String DONT = "Don't show this again", DISMISS = "Dismiss";

    /** A pairing or a reminder and its lines; once shown, when it showed, its wrapped lines and its size. */
    static final class Box
    {
        final boolean pairing;
        final String[] lines;
        long since;
        List<String> title, body;
        int titleH, h;

        Box(boolean pairing, String[] lines)
        {
            this.pairing = pairing;
            this.lines = lines;
        }
    }

    private final Deque<Box> queue = new ArrayDeque<>();
    private final PvPLeaderboardConfig config;
    private final ConfigManager configManager;
    private final BooleanSupplier inCombat;
    private final LongSupplier now;
    /** The box on screen and its two buttons, from the last frame; {@code null} while none shows. */
    volatile Box shown;
    volatile Rectangle dontAt, dismissAt;

    public TourneyPopup(Client client, PvPLeaderboardConfig config, ConfigManager configManager, BooleanSupplier inCombat, LongSupplier now)
    {
        super(client, log, "TourneyPopup", W, TOP);
        this.config = config;
        this.configManager = configManager;
        this.inCombat = inCombat;
        this.now = now;
    }

    /** Queues a box: a round's pairing ({@code pairing}) or a start reminder. */
    public synchronized void show(boolean pairing, String... lines)
    {
        queue.add(new Box(pairing, lines));
    }

    /** Drops every box. */
    public synchronized void clear()
    {
        queue.clear();
        hide();
    }

    private void hide()
    {
        shown = null;
        dontAt = dismissAt = null;
    }

    /** The first box whose setting is on and whose 1-30 s have not run out; the boxes before it are dropped. */
    private synchronized Box head(long t)
    {
        for (Box b; (b = queue.peek()) != null; queue.poll())
        {
            boolean on = b.pairing ? config.showPairingPopups() : config.showTournamentReminders();
            int s = Math.max(1, Math.min(30, b.pairing ? config.pairingPopupSeconds() : config.reminderPopupSeconds()));
            if (on && (b.since == 0 || t - b.since < s * 1000L)) return b;
        }
        return null;
    }

    @Override
    Dimension draw(Graphics2D g)
    {
        long t = now.getAsLong();
        Box b = head(t);
        if (b != null && inCombat.getAsBoolean())
        {
            b.since = 0;
            b = null;
        }
        if (b == null)
        {
            hide();
            return null;
        }
        if (b.since == 0) b.since = t;
        if (b.title == null) layout(b, g);
        shown = b;
        return paint(g, 0.8f, null, b.h, b.titleH);
    }

    /** A press on Don't show this again or Dismiss closes the box and is consumed; any other press passes through. */
    public MouseEvent press(MouseEvent e)
    {
        Rectangle dont = dontAt, dismiss = dismissAt;
        boolean off = dont != null && dont.contains(e.getPoint());
        if (off || dismiss != null && dismiss.contains(e.getPoint()))
        {
            Box b = close();
            if (off && b != null) configManager.setConfiguration("PvPLeaderboard", b.pairing ? "showPairingPopups" : "showTournamentReminders", false);
            e.consume();
        }
        return e;
    }

    /** The box on screen leaves the queue; {@code null} when it already left. */
    private synchronized Box close()
    {
        Box b = shown;
        if (b == null || queue.peek() != b) return null;
        queue.poll();
        hide();
        return b;
    }

    /** The box's lines wrapped to its width, and its height: the title bar, the lines, the buttons. */
    private static void layout(Box b, Graphics2D g)
    {
        FontMetrics tf = g.getFontMetrics(FontManager.getRunescapeBoldFont()), bf = g.getFontMetrics(FontManager.getRunescapeFont());
        b.title = wrap(b.lines[0], tf);
        b.body = new ArrayList<>();
        for (int i = 1; i < b.lines.length; i++) b.body.addAll(wrap(b.lines[i], bf));
        b.titleH = b.title.size() * tf.getHeight() + PAD;
        b.h = b.titleH + b.body.size() * bf.getHeight() + PAD * 5 / 2 + BTN_H;
    }

    /** {@code text} broken at spaces into lines that fit inside the box. */
    private static List<String> wrap(String text, FontMetrics fm)
    {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String w : text.split(" "))
        {
            String next = line.isEmpty() ? w : line + " " + w;
            if (!line.isEmpty() && fm.stringWidth(next) > W - 2 * PAD)
            {
                out.add(line);
                line = w;
            }
            else line = next;
        }
        out.add(line);
        return out;
    }

    /** The title, the lines and the two buttons, solid over the frame. */
    @Override
    void paintBody(Graphics2D g, int x, int y, String text)
    {
        Box b = shown;
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
        lines(g, b.title, FontManager.getRunescapeBoldFont(), TITLE_FG, x, y + PAD / 2);
        lines(g, b.body, FontManager.getRunescapeFont(), BODY_FG, x, y + b.titleH + PAD / 2);
        FontMetrics fm = g.getFontMetrics(FontManager.getRunescapeBoldFont());
        int by = y + b.h - PAD - BTN_H, dw = fm.stringWidth(DONT) + 2 * PAD, sw = fm.stringWidth(DISMISS) + 2 * PAD;
        dontAt = button(g, x + PAD, by, dw, BTN_H, DONT);
        dismissAt = button(g, x + W - PAD - sw, by, sw, BTN_H, DISMISS);
    }

    /** {@code lines} centred across the box from {@code top}, at {@code f} in {@code c}. */
    private static void lines(Graphics2D g, List<String> lines, Font f, Color c, int x, int top)
    {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        for (String l : lines)
        {
            g.drawString(l, x + (W - fm.stringWidth(l)) / 2, top + fm.getAscent());
            top += fm.getHeight();
        }
    }
}
