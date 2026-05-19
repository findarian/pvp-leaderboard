package com.pvp.leaderboard.ui;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
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
    private static final int PREFERRED_HEIGHT = 24;

    private static final Color TRACK_COLOR = new Color(0x40, 0x40, 0x40);
    private static final Color RANGE_COLOR = new Color(0xff, 0x6b, 0x00);
    private static final Color THUMB_COLOR = new Color(0xee, 0xee, 0xee);
    private static final Color THUMB_BORDER = new Color(0x22, 0x22, 0x22);

    private final int min;
    private final int max;
    private int low;
    private int high;
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
        setOpaque(false);
        setPreferredSize(new Dimension(120, PREFERRED_HEIGHT));
        setMinimumSize(new Dimension(60, PREFERRED_HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, PREFERRED_HEIGHT));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installMouseHandlers();
    }

    public int getLow()  { return low; }
    public int getHigh() { return high; }
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
        if (l != null) listeners.add(l);
    }

    private void fireChange()
    {
        ChangeEvent ev = new ChangeEvent(this);
        for (ChangeListener l : listeners) l.stateChanged(ev);
    }

    // -------------------- Mouse handling --------------------

    private void installMouseHandlers()
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

    private void dragTo(int xPx)
    {
        int v = xToValue(xPx);
        if (draggingHandle == 0)
        {
            int nv = Math.min(v, high);
            if (nv != low) { low = nv; fireChange(); repaint(); }
        }
        else if (draggingHandle == 1)
        {
            int nv = Math.max(v, low);
            if (nv != high) { high = nv; fireChange(); repaint(); }
        }
    }

    // -------------------- Geometry --------------------

    /** Effective track region. Reserves THUMB_SIZE/2 on each side so a thumb
     *  centered on the extreme value sits flush with the panel edge. */
    private int trackLeft() { return THUMB_SIZE / 2; }
    private int trackRight() { return getWidth() - THUMB_SIZE / 2; }
    private int trackWidth() { return Math.max(1, trackRight() - trackLeft()); }

    private int valueToX(int v)
    {
        double t = (double) (v - min) / (max - min);
        return trackLeft() + (int) Math.round(t * trackWidth());
    }

    private int xToValue(int x)
    {
        double t = (double) (x - trackLeft()) / trackWidth();
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
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int trackY = getHeight() / 2 - TRACK_HEIGHT / 2;
            int xL = trackLeft();
            int w = trackWidth();

            g2.setColor(TRACK_COLOR);
            g2.fillRoundRect(xL, trackY, w, TRACK_HEIGHT, 4, 4);

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
