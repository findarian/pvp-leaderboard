package com.pvp.leaderboard.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.*;

/** Small Swing helpers shared by the side panel's views. */
public final class Ui
{
    private Ui()
    {
    }

    /** {@code c} aligned left in a BoxLayout column. */
    public static <T extends JComponent> T left(T c)
    {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /** {@code c} with its font derived to {@code style} and {@code pt}. */
    public static <T extends JComponent> T font(T c, int style, float pt)
    {
        c.setFont(c.getFont().deriveFont(style, pt));
        return c;
    }

    public static <T extends JComponent> T bold(T c, float pt)
    {
        return font(c, Font.BOLD, pt);
    }

    public static <T extends JComponent> T plain(T c, float pt)
    {
        return font(c, Font.PLAIN, pt);
    }

    /** {@code c} as wide as its parent allows and at most {@code h} high. */
    public static <T extends JComponent> T maxH(T c, int h)
    {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return c;
    }

    public static Border pad(int top, int left, int bottom, int right)
    {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /** {@code Box.createVerticalStrut(h)}. */
    public static Component vgap(int h)
    {
        return Box.createVerticalStrut(h);
    }

    /** A vertical strut aligned left, for columns whose other children are. */
    public static Component lgap(int h)
    {
        var s = (Box.Filler) Box.createVerticalStrut(h);
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        return s;
    }

    /** Runs {@code r} when {@code c} is clicked. */
    public static void onClick(Component c, Runnable r)
    {
        c.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                r.run();
            }
        });
    }

    /** The bucket buttons' labels in bar order; a label's key is its lower case. */
    public static final String[] BUCKETS = {"Overall", "NH", "Veng", "Multi", "DMM", "Tournament"};

    /** A selected bucket button's background. */
    static final Color SELECTED_BG = new Color(60, 60, 60);

    /** The Look-and-Feel label font one point smaller, never under 10 pt. */
    public static Font smallFont()
    {
        Font f = RowTextFit.baseFont();
        return f.deriveFont(Font.PLAIN, Math.max(10f, f.getSize2D() - 1f));
    }

    /** A {@code rows} x {@code cols} grid of bucket buttons named {@code btn};
     *  a click hands the button's key (its lower-case label) to {@code pick}. */
    public static JPanel bucketBar(String name, String btn, int rows, int cols, String[] labels, Consumer<String> pick)
    {
        var bar = new JPanel(new GridLayout(rows, cols, 2, 2));
        bar.setName(name);
        Font small = smallFont();
        for (String l : labels)
        {
            var b = new JButton(l);
            b.setName(btn);
            b.setFont(small);
            b.setMargin(new Insets(2, 2, 2, 2));
            b.setFocusPainted(false);
            String key = l.toLowerCase();
            b.addActionListener(e -> pick.accept(key));
            bar.add(b);
        }
        return bar;
    }

    /** The {@code active} bucket's button white on dark grey, the rest grey on the theme's button colour. */
    public static void styleBucketBar(Container bar, String active)
    {
        for (Component c : bar.getComponents())
        {
            boolean on = ((JButton) c).getText().equalsIgnoreCase(active);
            c.setForeground(on ? Color.WHITE : Color.GRAY);
            c.setBackground(on ? SELECTED_BG : UIManager.getColor("Button.background"));
        }
    }

    /** A modeless dialog centred on {@code c}'s window, disposed when closed. */
    public static JDialog dialog(Component c, String title, int w, int h)
    {
        Window owner = SwingUtilities.getWindowAncestor(c);
        var d = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        d.setSize(w, h);
        d.setLocationRelativeTo(owner);
        return d;
    }

    /** A panel never taller than its preferred height, so a BoxLayout column
     *  cannot hand it the column's spare height. */
    public static class CapPanel extends JPanel
    {
        @Override
        public Dimension getMaximumSize()
        {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** A column of rows inside a scroll pane: it tracks the viewport's width
     *  (rows never overflow sideways) and scrolls vertically. */
    public static class RowsPanel extends JPanel implements Scrollable
    {
        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d)
        {
            return o == SwingConstants.VERTICAL ? r.height : r.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }

    // ---- Plan 46 S2 (the Tournaments tab): a shared palette and the helpers below.

    /** Muted grey text. */
    public static final Color MUTED = new Color(0x9a9a9a);
    public static final Color GREEN = new Color(0x3ecf8e);
    public static final Color AMBER = new Color(0xffb347);
    /** A red button's background and its text. */
    public static final Color RED = new Color(0x5a2a2a);
    public static final Color RED_FG = new Color(0xffb3b3);
    /** Light grey body text. */
    public static final Color INFO = new Color(0xdddddd);
    /** The lobby row's card background and the divider under it. */
    public static final Color CARD_BG = new Color(0x2b2b2b);
    public static final Color DIVIDER = Color.DARK_GRAY;
    /** The active tab's underline and the green action buttons. */
    public static final Color ACCENT = new Color(0x2e7d32);

    /** A panel that stacks its children top-down. */
    public static JPanel column()
    {
        var p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    /** {@code s} with {@code &}, {@code <} and {@code >} as HTML entities; {@code ""} for null. */
    static String escape(String s)
    {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** {@code "M:SS"} for {@code seconds}; {@code "0:00"} below zero. */
    static String mmss(long seconds)
    {
        long s = Math.max(0L, seconds);
        return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }

    /** {@code view} in a borderless scroll pane that never scrolls sideways. */
    public static JScrollPane scroll(JComponent view)
    {
        var s = new JScrollPane(view);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return s;
    }

    /** A label named {@code name} (unnamed for null) showing {@code text} at {@code style} / {@code pt}, in {@code fg}
     *  (the look and feel's colour for null). */
    public static JLabel label(String name, String text, int style, float pt, Color fg)
    {
        JLabel l = font(new JLabel(text), style, pt);
        if (name != null) l.setName(name);
        if (fg != null) l.setForeground(fg);
        return l;
    }

    /** {@code l}'s text: {@code text} as {@code <html>} lines wrapped to the side panel's text width at its font. */
    public static void wrap(JLabel l, String... text)
    {
        l.setText(TournamentInfoCard.wrapHtml(l.getFont(), TournamentInfoCard.TEXT_WIDTH, text));
    }

    /** The lobby row's card look: the card background, a divider under 4 / 6 / 6 / 6 padding, aligned left. */
    public static void cardStyle(JComponent c)
    {
        c.setBackground(CARD_BG);
        c.setOpaque(true);
        c.setBorder(BorderFactory.createCompoundBorder(new MatteBorder(0, 0, 1, 0, DIVIDER), pad(4, 6, 6, 6)));
        left(c);
    }

    /** {@code c} as wide as its column allows and no taller than it prefers now. */
    public static <T extends JComponent> T pin(T c)
    {
        return maxH(c, c.getPreferredSize().height);
    }
}
