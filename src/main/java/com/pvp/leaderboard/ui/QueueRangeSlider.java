package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.util.*;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

/**
 * The queue's rank range: a caption, the lowest and highest division in their
 * rank colours, and one two-handle slider over the 21 divisions. Bounds are
 * read and set as wire slots ({@link RankUtils#SLOTS}). The full span means no
 * rank limit.
 */
public class QueueRangeSlider extends JPanel
{
    static final String CAPTION = "Only match players in this rank range";

    private static final int LAST = RankUtils.DIVISIONS.length - 1;

    private final RangeSlider slider;
    private final JLabel minValue;
    private final JLabel maxValue;
    private Runnable onCommit;
    private boolean applying;

    QueueRangeSlider(int low, int high)
    {
        int[] range = positions(low, high);
        setName("matchmaking-queue-range");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(1, 0, 1, 0, new Color(60, 60, 60)),
            Ui.pad(6, 2, 6, 2)));
        Ui.maxH(this, 110);

        var caption = new JLabel();
        caption.setName("matchmaking-queue-range-caption");
        Ui.bold(caption, 15f);
        Ui.wrap(caption, CAPTION);
        caption.setForeground(new Color(0xaaaaaa));
        caption.setBorder(Ui.pad(0, 2, 4, 2));

        minValue = rankLabel(range[0]);
        minValue.setName("matchmaking-queue-range-min");
        minValue.setHorizontalAlignment(SwingConstants.LEFT);
        maxValue = rankLabel(range[1]);
        maxValue.setName("matchmaking-queue-range-max");
        maxValue.setHorizontalAlignment(SwingConstants.RIGHT);

        var bounds = new JPanel(new BorderLayout());
        bounds.setOpaque(false);
        Ui.left(Ui.maxH(bounds, 22));
        bounds.setBorder(Ui.pad(0, 2, 0, 2));
        bounds.add(minValue, BorderLayout.WEST);
        bounds.add(maxValue, BorderLayout.EAST);

        slider = new RangeSlider(0, LAST, range[0], range[1]);
        slider.setName("matchmaking-queue-range-slider");
        slider.setToolTipText("Drag the handles to set the lowest and highest rank to match with");
        Ui.left(slider);
        slider.addChangeListener(e ->
        {
            setRank(minValue, slider.getLow());
            setRank(maxValue, slider.getHigh());
            if (!applying && !slider.getValueIsAdjusting() && onCommit != null) onCommit.run();
        });

        add(caption);
        add(bounds);
        add(slider);
    }

    /** Runs after the user releases a handle; never for {@link #setRange}. */
    void setOnCommit(Runnable listener)
    {
        onCommit = listener;
    }

    /** The lowest division's slot. */
    public int low()
    {
        return RankUtils.slotOf(slider.getLow());
    }

    /** The highest division's slot. */
    public int high()
    {
        return RankUtils.slotOf(slider.getHigh());
    }

    /** {@code true} unless the slider spans every division. */
    public boolean narrowed()
    {
        return slider.getLow() > 0 || slider.getHigh() < LAST;
    }

    /** Moves both handles to two slots without reporting a user pick. A negative bound
     *  means unset: the low end starts at the first division, the high end at the last. */
    void setRange(int low, int high)
    {
        int[] range = positions(low, high);
        applying = true;
        try
        {
            slider.setHigh(LAST);
            slider.setLow(range[0]);
            slider.setHigh(range[1]);
        }
        finally
        {
            applying = false;
        }
    }

    /** Two slots as track positions: a Black minimum is Mithril 3, a Black maximum Steel 1 (both ends Black:
     *  Steel 1 to Mithril 3), 3rd Age is Dragon 1. */
    private static int[] positions(int low, int high)
    {
        int lo = low < 0 ? 0 : Math.min(low, 24);
        int hi = high < 0 ? 24 : Math.min(high, 24);
        if (lo > hi)
        {
            int t = lo;
            lo = hi;
            hi = t;
        }
        boolean blackLo = lo >= 9 && lo <= 11;
        boolean blackHi = hi >= 9 && hi <= 11;
        lo = blackLo ? (blackHi ? 8 : 12) : lo;
        hi = blackHi ? (blackLo ? 12 : 8) : hi;
        return new int[] {position(lo), position(hi)};
    }

    private static int position(int slot)
    {
        return slot < 9 ? slot : Math.min(slot, 23) - 3;
    }

    /** A bound's label: bold, 15 pt, in the division's colour; painted by {@link PlayerCard.ChipLabel} so the
     *  look and feel never cuts it short. */
    private static JLabel rankLabel(int position)
    {
        var l = new PlayerCard.ChipLabel("");
        Ui.bold(l, 15f);
        l.setBorder(Ui.pad(0, 0, 0, 0));
        setRank(l, position);
        return l;
    }

    private static void setRank(JLabel l, int position)
    {
        String label = RankUtils.DIVISIONS[position];
        l.setText(label);
        l.setForeground(RankUtils.getRankColor(label));
    }
}
