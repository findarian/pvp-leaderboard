package com.pvp.leaderboard.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

/**
 * The queue's rank range: a caption, the lowest and highest rank in their
 * rank colours, and one two-handle slider over every rank. The full span
 * means no rank limit.
 */
public class QueueRangeSlider extends JPanel
{
    static final String CAPTION = "Only match players in this rank range";

    private final int last;
    private final RangeSlider slider;
    private final JLabel minValue;
    private final JLabel maxValue;
    private Runnable onCommit;
    private boolean applying;

    QueueRangeSlider(int low, int high)
    {
        last = MatchmakingLobbyPanel.rankLabels().length - 1;
        int[] range = normalise(low, high);
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

        minValue = MatchmakingLobbyPanel.newRankLabel(range[0]);
        minValue.setName("matchmaking-queue-range-min");
        minValue.setHorizontalAlignment(SwingConstants.LEFT);
        maxValue = MatchmakingLobbyPanel.newRankLabel(range[1]);
        maxValue.setName("matchmaking-queue-range-max");
        maxValue.setHorizontalAlignment(SwingConstants.RIGHT);

        var bounds = new JPanel(new BorderLayout());
        bounds.setOpaque(false);
        Ui.left(Ui.maxH(bounds, 22));
        bounds.setBorder(Ui.pad(0, 2, 0, 2));
        bounds.add(minValue, BorderLayout.WEST);
        bounds.add(maxValue, BorderLayout.EAST);

        slider = new RangeSlider(0, last, range[0], range[1]);
        slider.setName("matchmaking-queue-range-slider");
        slider.setToolTipText("Drag the handles to set the lowest and highest rank to match with");
        Ui.left(slider);
        slider.addChangeListener(e ->
        {
            MatchmakingLobbyPanel.setRankLabel(minValue, slider.getLow());
            MatchmakingLobbyPanel.setRankLabel(maxValue, slider.getHigh());
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

    public int low()
    {
        return slider.getLow();
    }

    public int high()
    {
        return slider.getHigh();
    }

    /** {@code true} unless the slider spans every rank. */
    public boolean narrowed()
    {
        return low() > 0 || high() < last;
    }

    /** Moves both handles without reporting a user pick. A negative bound
     *  means unset: the low end starts at the first rank, the high end at
     *  the last. */
    void setRange(int low, int high)
    {
        int[] range = normalise(low, high);
        applying = true;
        try
        {
            slider.setHigh(last);
            slider.setLow(range[0]);
            slider.setHigh(range[1]);
        }
        finally
        {
            applying = false;
        }
    }

    private int[] normalise(int low, int high)
    {
        int lo = low < 0 ? 0 : Math.min(low, last);
        int hi = high < 0 ? last : Math.min(high, last);
        return lo <= hi ? new int[] {lo, hi} : new int[] {hi, lo};
    }
}
