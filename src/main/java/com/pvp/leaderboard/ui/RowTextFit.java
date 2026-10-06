package com.pvp.leaderboard.ui;

import java.awt.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import lombok.*;
import java.util.List;
import static com.pvp.leaderboard.ui.Ui.*;

/**
 * Width-driven font fit for a column of uniform rows, shared by
 * {@link TierPanel} and {@link TopPlayers}.
 *
 * <p>Both views face the same problem: a fixed point size either wastes
 * a wide sidepanel or ellipsizes text in a narrow one. So each row's text
 * columns are sized together: the largest point size (searching down from
 * the cap) at which every row's columns fit side by side, one size for the
 * whole list because mixed sizes down a column read as a rendering bug.
 * The widest row therefore governs the list. A panel describes its rows
 * once ({@code min} / {@code max} point size, the width a row loses to its
 * padding and gaps, and per text column a font style and how many points
 * below the row size it renders); this class owns the search, the
 * measuring and the applying. The tournament views use only the search and
 * the measuring (the no-argument constructor).
 *
 * <p>Not thread-safe, and not meant to be: the measuring label is
 * mutated per query, so use one instance per panel from the EDT.
 */
@RequiredArgsConstructor
final class RowTextFit
{
    /**
     * Off-tree label used to measure candidate sizes. Deliberately
     * parentless: it inherits the same Look-and-Feel text metrics as the
     * real labels, but mutating it can't revalidate anything mid-layout.
     */
    private final JLabel measurer = new JLabel();

    /** The point-size floor (a legibility limit: below it we'd rather clip
     *  than render text no one can read), the cap (only stops a very wide
     *  panel turning the list into an endless scroll), and the width each
     *  row loses to its padding and the gaps between its columns. */
    private final int min, max, gap;
    /** Text the first column is sized to so a column lines up down the
     *  list (Top players' {@code "#100"}), or {@code null}. */
    private final String fixed;
    /** Per text column: the font style, and how many points below the row size. */
    private final int[] styles, deltas;
    /** Every row: its panel, then its labels by column. */
    final List<JComponent[]> rows = new ArrayList<>();
    /** Point size currently applied to the rows; -1 until the first fit. */
    int applied = -1;

    /** Search and measuring only. */
    RowTextFit()
    {
        this(0, 0, 0, null, null, null);
    }

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

    /** A row named {@code name} holding {@code labels} WEST, CENTER and EAST
     *  in that order (4 px side and 2 px top/bottom padding, 6 px gaps),
     *  registered for the fit. */
    JPanel row(String name, JLabel... labels)
    {
        var row = new JPanel(new BorderLayout(6, 0));
        row.setName(name);
        row.setBorder(pad(2, 4, 2, 4));
        left(row);
        String[] where = {BorderLayout.WEST, BorderLayout.CENTER, BorderLayout.EAST};
        var r = new JComponent[labels.length + 1];
        r[0] = row;
        for (int i = 0; i < labels.length; i++)
        {
            row.add(labels[i], where[i]);
            r[i + 1] = labels[i];
        }
        rows.add(r);
        return row;
    }

    /** Brand-new labels carry no fitted font, so the fit starts again from
     *  the cap — a size "already applied" to discarded rows would otherwise
     *  short-circuit it — and then fits {@code width}. */
    void restart(int width)
    {
        applied = -1;
        apply(max);
        refit(width);
    }

    /**
     * Fits the rows to a panel {@code width} wide; nothing while the panel
     * is not laid out yet or has no rows.
     *
     * <p>Backstop: the search measures a stand-in label, but these are the
     * real components the layout will size. If the installed Look-and-Feel
     * makes them even slightly wider than the stand-in, step down until
     * they genuinely fit — a shortfall is what Swing renders as an
     * ellipsized name. Normally costs zero iterations.
     */
    void refit(int width)
    {
        int usable = width - gap;
        if (rows.isEmpty() || usable <= 0)
        {
            return;
        }
        apply(largestFitting(min, max, pt -> fits(pt, usable, false)));
        while (applied > min && !fits(applied, usable, true))
        {
            apply(applied - 1);
        }
    }

    /** Whether every row's columns fit {@code usable} side by side at
     *  {@code pt}: measured on the stand-in, or ({@code live}) on the labels
     *  at the size currently applied. */
    private boolean fits(int pt, int usable, boolean live)
    {
        Font[] f = fonts(pt);
        for (JComponent[] r : rows)
        {
            int needed = 0;
            for (int i = 1; i < r.length; i++)
            {
                needed += live ? r[i].getPreferredSize().width
                    : textWidth(i == 1 && fixed != null ? fixed : ((JLabel) r[i]).getText(), f[i - 1]);
            }
            if (needed > usable)
            {
                return false;
            }
        }
        return true;
    }

    /** Sets every row to {@code pt}: the column fonts, the fixed column's
     *  width, and the row's maximum height, which BoxLayout caps it at, so
     *  it has to track the font or taller glyphs get clipped. Idempotent —
     *  avoids a setFont/revalidate layout loop. */
    void apply(int pt)
    {
        if (pt == applied)
        {
            return;
        }
        applied = pt;
        Font[] f = fonts(pt);
        int h = 0;
        for (Font x : f)
        {
            h = Math.max(h, textHeight(x) + 4);
        }
        Dimension size = fixed == null ? null : new Dimension(textWidth(fixed, f[0]), h);
        var rowMax = new Dimension(Integer.MAX_VALUE, h);
        for (JComponent[] r : rows)
        {
            for (int i = 1; i < r.length; i++)
            {
                r[i].setFont(f[i - 1]);
                if (i == 1 && size != null)
                {
                    r[i].setPreferredSize(size);
                }
            }
            r[0].setMaximumSize(rowMax);
        }
    }

    private Font[] fonts(int pt)
    {
        var f = new Font[styles.length];
        for (int i = 0; i < f.length; i++)
        {
            f[i] = baseFont().deriveFont(styles[i], (float) (pt - deltas[i]));
        }
        return f;
    }
}
