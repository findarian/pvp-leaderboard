package com.pvp.leaderboard.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/**
 * The queue's rank range: a caption, the lowest and highest rank in their
 * rank colours, and one two-handle slider over every rank. The full span
 * means no rank limit.
 */
public class QueueRangeSlider extends JPanel
{
    public static final String NAME = "matchmaking-queue-range";
    public static final String NAME_SLIDER = "matchmaking-queue-range-slider";
    public static final String NAME_CAPTION = "matchmaking-queue-range-caption";
    public static final String NAME_MIN = "matchmaking-queue-range-min";
    public static final String NAME_MAX = "matchmaking-queue-range-max";

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
        setName(NAME);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(1, 0, 1, 0, new Color(60, 60, 60)),
            BorderFactory.createEmptyBorder(6, 2, 6, 2)));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));

        JLabel caption = new JLabel();
        caption.setName(NAME_CAPTION);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 15f));
        caption.setText(TournamentInfoCard.wrapHtml(caption.getFont(), TournamentInfoCard.TEXT_WIDTH_PX, CAPTION));
        caption.setForeground(new Color(0xaa, 0xaa, 0xaa));
        caption.setAlignmentX(LEFT_ALIGNMENT);
        caption.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 2));

        minValue = MatchmakingLobbyPanel.makeRankValueLabel(range[0]);
        minValue.setName(NAME_MIN);
        minValue.setHorizontalAlignment(SwingConstants.LEFT);
        maxValue = MatchmakingLobbyPanel.makeRankValueLabel(range[1]);
        maxValue.setName(NAME_MAX);
        maxValue.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel bounds = new JPanel(new BorderLayout());
        bounds.setOpaque(false);
        bounds.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        bounds.setAlignmentX(LEFT_ALIGNMENT);
        bounds.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        bounds.add(minValue, BorderLayout.WEST);
        bounds.add(maxValue, BorderLayout.EAST);

        slider = new RangeSlider(0, last, range[0], range[1]);
        slider.setName(NAME_SLIDER);
        slider.setToolTipText("Drag the handles to set the lowest and highest rank to match with");
        slider.setAlignmentX(LEFT_ALIGNMENT);
        slider.addChangeListener(e ->
        {
            MatchmakingLobbyPanel.updateRankValueLabel(minValue, slider.getLow());
            MatchmakingLobbyPanel.updateRankValueLabel(maxValue, slider.getHigh());
            if (!applying && !slider.getValueIsAdjusting() && onCommit != null) onCommit.run();
        });

        add(caption);
        add(bounds);
        add(slider);
    }

    /** Runs after the user releases a handle; never for {@link #setRange}. */
    void setOnCommit(Runnable listener)
    {
        this.onCommit = listener;
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
