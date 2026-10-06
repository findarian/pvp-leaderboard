package com.pvp.leaderboard.ui;

import lombok.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import java.util.List;

/**
 * Single-track double-handled range slider. Drop-in replacement for two
 * {@link JSlider}s where you want a min and a max bound on the same scale.
 *
 * <p>Why a custom widget instead of {@link JSlider} × 2:
 * <ul>
 *   <li>Two stacked sliders take ~2× the vertical real estate of one
 *       range slider — the lobby panel is height-starved (community box +
 *       nav + filters + roster + chat all share ~700px).</li>
 *   <li>{@link JSlider} reserves a "thumb-radius" inset on each side of
 *       its track which makes the track visually offset from the panel's
 *       left edge — that was the source of the "left-side gap" the user
 *       called out. This widget paints the track edge-to-edge.</li>
 *   <li>The Substance L&F draws JSlider in a way that ignores opacity
 *       overrides, making it impossible to colour-match the lobby palette.</li>
 * </ul>
 *
 * <p>Values are integers in {@code [min, max]} (inclusive); high &ge; low is
 * always enforced. {@link ChangeListener}s fire on every drag tick so
 * callers should defer expensive work until {@link #getValueIsAdjusting()}
 * returns {@code false}.
 */
public class RangeSlider extends JComponent
{
    /** Track thickness in pixels. */
    private static final int TRACK_HEIGHT = 6;
    /** Thumb diameter — also dictates the left/right margin reserved so the
     *  thumbs can sit half-off the track at the extremes. */
    private static final int THUMB_SIZE = 14;
    /** Reserve room above + below the track so the thumbs aren't clipped. */
    private static final int PREF_HEIGHT = 24;

    private static final Color TRACK_COLOR = Ui.DIVIDER;
    private static final Color RANGE_COLOR = new Color(0xff6b00);
    private static final Color THUMB_COLOR = new Color(0xeeeeee);
    private static final Color THUMB_BORDER = new Color(0x222222);

    private final int min;
    private final int max;
    @Getter private int low;
    @Getter private int high;
    /** {@code 0} = low handle, {@code 1} = high handle, {@code -1} = no drag. */
    private int draggingHandle = -1;
    private boolean adjusting;
    private final List<ChangeListener> listeners = new ArrayList<>();

    public RangeSlider(int min, int max, int low, int high)
    {
        if (max <= min) throw new IllegalArgumentException("max must be > min");
        this.min = min;
        this.max = max;
        this.low = clamp(low, min, max);
        this.high = clamp(high, this.low, max);
        setPreferredSize(new Dimension(120, PREF_HEIGHT));
        setMinimumSize(new Dimension(60, PREF_HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, PREF_HEIGHT));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installMouse();
    }
    public boolean getValueIsAdjusting() { return adjusting; }

    public void setLow(int v)
    {
        int nv = clamp(v, min, high);
        if (nv == low) return;
        low = nv;
        fireChange();
        repaint();
    }

    public void setHigh(int v)
    {
        int nv = clamp(v, low, max);
        if (nv == high) return;
        high = nv;
        fireChange();
        repaint();
    }

    public void addChangeListener(ChangeListener l)
    {
        listeners.add(l);
    }

    private void fireChange()
    {
        var ev = new ChangeEvent(this);
        for (ChangeListener l : listeners) l.stateChanged(ev);
    }

    // -------------------- Mouse handling --------------------

    private void installMouse()
    {
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                draggingHandle = pickHandleAt(e.getX());
                adjusting = true;
                dragTo(e.getX());
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                draggingHandle = -1;
                adjusting = false;
                fireChange();
            }
        });
        addMouseMotionListener(new MouseMotionAdapter()
        {
            @Override
            public void mouseDragged(MouseEvent e)
            {
                if (draggingHandle < 0) return;
                dragTo(e.getX());
            }
        });
    }

    /** Picks the handle nearest the click x. If the click lands inside the
     *  active range we pick the closer of low/high; outside the range we pick
     *  whichever handle the click is nearer to. */
    private int pickHandleAt(int xPx)
    {
        int xLow = valueToX(low);
        int xHigh = valueToX(high);
        return Math.abs(xPx - xLow) <= Math.abs(xPx - xHigh) ? 0 : 1;
    }

    /** Moves the dragged handle (0 = low, 1 = high; the only values while a
     *  drag runs) to the value under {@code xPx}, which is always within
     *  {@code [min, max]}, so the setters' clamp is the drag's own bound. */
    private void dragTo(int xPx)
    {
        int v = xToValue(xPx);
        if (draggingHandle == 0) setLow(v);
        else setHigh(v);
    }

    // -------------------- Geometry --------------------

    /** Effective track width. The track reserves THUMB_SIZE/2 on each side so
     *  a thumb centered on the extreme value sits flush with the panel edge. */
    private int trackWidth() { return Math.max(1, getWidth() - THUMB_SIZE); }

    private int valueToX(int v)
    {
        double t = (double) (v - min) / (max - min);
        return THUMB_SIZE / 2 + (int) Math.round(t * trackWidth());
    }

    private int xToValue(int x)
    {
        double t = (double) (x - THUMB_SIZE / 2) / trackWidth();
        t = Math.max(0.0, Math.min(1.0, t));
        return min + (int) Math.round(t * (max - min));
    }

    private static int clamp(int v, int lo, int hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }

    // -------------------- Painting --------------------

    @Override
    protected void paintComponent(Graphics g)
    {
        var g2 = (Graphics2D) g.create();
        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int trackY = getHeight() / 2 - TRACK_HEIGHT / 2;

            g2.setColor(TRACK_COLOR);
            g2.fillRoundRect(THUMB_SIZE / 2, trackY, trackWidth(), TRACK_HEIGHT, 4, 4);

            int xLow = valueToX(low);
            int xHigh = valueToX(high);
            g2.setColor(RANGE_COLOR);
            g2.fillRoundRect(xLow, trackY, Math.max(1, xHigh - xLow), TRACK_HEIGHT, 4, 4);

            paintThumb(g2, xLow);
            paintThumb(g2, xHigh);
        }
        finally
        {
            g2.dispose();
        }
    }

    private void paintThumb(Graphics2D g2, int xCenter)
    {
        int yCenter = getHeight() / 2;
        int r = THUMB_SIZE / 2;
        g2.setColor(THUMB_COLOR);
        g2.fillOval(xCenter - r, yCenter - r, THUMB_SIZE, THUMB_SIZE);
        g2.setColor(THUMB_BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.drawOval(xCenter - r, yCenter - r, THUMB_SIZE - 1, THUMB_SIZE - 1);
    }
}
