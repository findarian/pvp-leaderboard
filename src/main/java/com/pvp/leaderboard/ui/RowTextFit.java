package com.pvp.leaderboard.ui;

import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Font;
import java.util.function.IntPredicate;

/**
 * Width-driven font fit for a column of uniform rows, shared by
 * {@link RankTierPanel} and {@link TopPlayersPanel}.
 *
 * <p>Both views face the same problem: a fixed point size either wastes
 * a wide sidepanel or ellipsizes text in a narrow one. Each panel knows
 * its own row geometry and decides what "fits" means; this class owns
 * the two parts that must not differ between them — the downward search
 * for the largest fitting size, and how text is measured.
 *
 * <p>Not thread-safe, and not meant to be: the measuring label is
 * mutated per query, so use one instance per panel from the EDT.
 */
final class RowTextFit
{
    /**
     * Off-tree label used to measure candidate sizes. Deliberately
     * parentless: it inherits the same Look-and-Feel text metrics as the
     * real labels, but mutating it can't revalidate anything mid-layout.
     */
    private final JLabel measurer = new JLabel();

    /**
     * Largest point size in {@code [minPt, maxPt]} for which
     * {@code fitsAt} holds, searching downward from the cap and stopping
     * at the first fit. Falls back to {@code minPt} when nothing fits —
     * the floor is a legibility limit, so below it we'd rather clip than
     * render text no one can read.
     */
    int largestFitting(int minPt, int maxPt, IntPredicate fitsAt)
    {
        for (int pt = maxPt; pt > minPt; pt--)
        {
            if (fitsAt.test(pt))
            {
                return pt;
            }
        }
        return minPt;
    }

    /**
     * Width the layout will actually demand for {@code text} at
     * {@code font}.
     *
     * <p>Measured through a JLabel's own preferred size rather than
     * {@link java.awt.FontMetrics#stringWidth}, because that is what the
     * row's layout manager asks for when it sizes the cell. The two
     * disagree whenever the Look-and-Feel enables fractional metrics or
     * text antialiasing — RuneLite's does — and measuring the wrong one
     * lets the fit pick a size the layout can't honour, which Swing
     * renders as ellipsized text ("Adamant 1" → "Adaman...").
     */
    int textWidth(String text, Font font)
    {
        measurer.setFont(font);
        measurer.setText(text);
        return measurer.getPreferredSize().width;
    }

    /** Row height the layout will demand for {@code font}, measured the
     *  same way as {@link #textWidth} so a row can't cap its own text. */
    int textHeight(Font font)
    {
        measurer.setFont(font);
        measurer.setText("Ag");
        return measurer.getPreferredSize().height;
    }

    /** The Look-and-Feel label font every fitted size derives from. */
    static Font baseFont()
    {
        Font base = UIManager.getFont("Label.font");
        return base != null ? base : new Font("SansSerif", Font.PLAIN, 12);
    }
}
