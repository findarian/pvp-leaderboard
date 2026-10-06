package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.border.*;
import static com.pvp.leaderboard.ui.Ui.*;
import static java.awt.RenderingHints.*;
import static javax.swing.SwingConstants.*;

/**
 * Roster entry. EAST chip cycles through three states (precedence top-down):
 * <ul>
 *   <li><b>[Invited M:SS]</b> — outgoing invite pending acceptance.
 *       Click cancels the invite (clears the 10-min block).</li>
 *   <li><b>[Lookup]</b> — outstanding incoming invite from this player
 *       (their card is up top). Click opens Player Lookup.</li>
 *   <li><b>[Fight]</b> (default) — opens the full-screen fight-setup card.</li>
 * </ul>
 *
 * <p>Clicking the profile area also opens Player Lookup — matches the
 * legacy right-click "PvP lookup" muscle memory.
 */
class PlayerCard extends CapPanel
{
    /** Single fixed border used in both idle and hover states; the hover
     *  outline is drawn in {@link #paintBorder(Graphics)}. */
    private static final Border ROW_BORDER = BorderFactory.createCompoundBorder(
        new MatteBorder(0, 0, 1, 0, DIVIDER),
        pad(4, 6, 4, 6));

    private final LobbyMember p;
    private final int fontBase;
    /** The chips' size. */
    private final int chipPt;
    private final Consumer<String> onOpenProfile;

    /** Tracks the cursor across child boundaries so child-to-child crossings
     *  don't false-trigger an unhover on the leaf JLabel. */
    private boolean hovered;

    /** A player's card: name and rank, then the player's own style and build
     *  chips; a click anywhere opens the profile. */
    PlayerCard(LobbyMember p, int fontBase, int chipPt, Consumer<String> onOpenProfile)
    {
        this.p = p;
        this.fontBase = fontBase;
        this.chipPt = chipPt;
        this.onOpenProfile = onOpenProfile;
        setLayout(new BorderLayout());
        setBackground(CARD_BG);
        setBorder(ROW_BORDER);
        setAlignmentX(LEFT_ALIGNMENT);
        var center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);
        center.add(buildHeader());
        center.add(vgap(3));
        JPanel styles = chipRow();
        if (p.region != null && !p.region.trim().isEmpty()) styles.add(makeChip(p.region, Color.WHITE, chipPt));
        for (Style s : Style.values())
        {
            if (p.styles.contains(s)) styles.add(makeChip(s.label, new Color(0xffc107), chipPt));
        }
        center.add(styles);
        center.add(vgap(2));
        JPanel builds = chipRow();
        for (BuildType a : BuildType.values())
        {
            if (p.builds.contains(a)) builds.add(makeChip(a.label, new Color(0x4fc3f7), chipPt));
        }
        center.add(builds);
        add(center, BorderLayout.CENTER);
        installProfileInteraction(center);
    }

    @Override
    protected void paintBorder(Graphics g)
    {
        super.paintBorder(g);
        if (hovered)
        {
            g.setColor(Color.WHITE);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        }
    }

    /** Installs the hover / click handler on every component inside
     *  {@code root}: Swing dispatches mouse events to the deepest hit
     *  component, so the card alone would never see them. */
    private void installProfileInteraction(Container root)
    {
        MouseAdapter ma = new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (onOpenProfile != null) onOpenProfile.accept(p.name);
            }
            @Override
            public void mouseEntered(MouseEvent e)
            {
                setHover(true);
            }
            @Override
            public void mouseExited(MouseEvent e)
            {
                Point inRow = SwingUtilities.convertPoint(
                    e.getComponent(), e.getPoint(), PlayerCard.this);
                if (!PlayerCard.this.contains(inRow)) setHover(false);
            }
        };
        addMouseListener(ma);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        attachAll(root, ma);
    }

    private static void attachAll(Container c, MouseListener ml)
    {
        for (Component child : c.getComponents())
        {
            child.addMouseListener(ml);
            if (child instanceof JComponent)
            {
                ((JComponent) child).setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
            if (child instanceof Container)
            {
                attachAll((Container) child, ml);
            }
        }
    }

    private void setHover(boolean on)
    {
        if (hovered == on) return;
        hovered = on;
        repaint();
    }

    /** The name (full row width, no ellipsis) with the rank drawn over its
     *  right end on the card's background; an unknown rank leaves the name alone. */
    private JComponent buildHeader()
    {
        boolean rankKnown = p.peakRankIdx >= 0 && p.peakRankIdx < RANK_LABELS.length;
        String rankLabel = rankKnown ? RANK_LABELS[p.peakRankIdx] : "";
        Color rankColor = rankKnown ? RankUtils.getRankColor(rankLabel) : Color.WHITE;
        String displayName = nameOf(p);
        var name = new FullLabel(displayName);
        name.setFont(name.getFont().deriveFont(Font.BOLD, (float) fontBase));
        name.setForeground(rankColor);
        name.setToolTipText(displayName);
        if (!rankKnown)
        {
            var placeholder = new JLabel("");
            placeholder.setOpaque(false);
            return new RightRow(name, placeholder);
        }
        var rank = new JLabel(rankLabel);
        rank.setFont(rank.getFont().deriveFont(Font.BOLD, (float) fontBase));
        rank.setForeground(rankColor);
        rank.setHorizontalAlignment(RIGHT);
        rank.setOpaque(true);
        rank.setBackground(CARD_BG);
        rank.setBorder(pad(0, 4, 0, 0));
        return new RightRow(name, rank);
    }

    private static JPanel chipRow()
    {
        var chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        chips.setOpaque(false);
        chips.setBorder(pad(0, 0, 0, 0));
        return chips;
    }

    private static JLabel makeChip(String text, Color color, int fontPt)
    {
        var chip = new ChipLabel(text);
        chip.setFont(chip.getFont().deriveFont(Font.BOLD, (float) fontPt));
        chip.setForeground(color);
        chip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(color, 1),
            pad(1, 5, 1, 5)));
        chip.setOpaque(false);
        return chip;
    }

    /** The lobby's rank labels ("Bronze 3" ... "3rd Age"), indexed like {@link RankUtils#THRESHOLDS}. */
    static final String[] RANK_LABELS = buildLabels();

    /** A member's name for the card: the display name, else the id, else "Unknown". */
    static String nameOf(LobbyMember m)
    {
        if (m == null) return "";
        String n = m.name;
        if (n != null && !n.isEmpty()) return n;
        String pid = m.playerId;
        if (pid != null && !pid.isEmpty()) return pid;
        return "Unknown";
    }

    /** Builds the human-readable rank labels (e.g. "Bronze 3", "3rd Age") in
     *  the same index order as {@link RankUtils#THRESHOLDS}. */
    private static String[] buildLabels()
    {
        var out = new String[RankUtils.THRESHOLDS.length];
        for (int i = 0; i < RankUtils.THRESHOLDS.length; i++)
        {
            String name = RankUtils.THRESHOLDS[i][0];
            String div = RankUtils.THRESHOLDS[i][1];
            out[i] = "0".equals(div) ? name : name + " " + div;
        }
        return out;
    }

    /** JLabel subclass whose {@link #paintComponent} bypasses the L&F UI
     *  delegate's text rendering — under Substance L&F (which RuneLite
     *  ships with), JLabels auto-truncate with {@code "…"} when their
     *  bounds are narrower than the rendered text's preferred width. By
     *  drawing the text ourselves with {@link Graphics2D#drawString}, the
     *  text simply clips at the component's right edge (no ellipsis).
     *
     *  Intended for player-name labels paired with an {@link RightRow}
     *  that lets the right-side rank chip visually mask the overflowing
     *  characters. Not a drop-in for general JLabels — single-line LTR
     *  text only, no icon, no border insets, vertical-centered baseline. */
    static final class FullLabel extends JLabel
    {
        FullLabel(String text)
        {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            var g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(KEY_TEXT_ANTIALIASING,
                    VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getForeground());
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int baselineY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                String t = getText();
                if (t != null) g2.drawString(t, 0, baselineY);
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    /** Border-aware sibling of {@link FullLabel} used for the
     *  per-row style/build/region chips ([Main], [Pure], [Any Build],
     *  [NA-W], etc.). Same rationale as FullLabel — bypass the
     *  Substance L&F text-rendering path — but here the motivation is
     *  hover stability, not ellipsis suppression: under Substance, a
     *  vanilla JLabel parented inside a panel that has a row-level
     *  MouseListener fanout (PlayerRow's attachAll
     *  installs the same adapter on every descendant) repaints with a
     *  blank foreground on hover state changes, making chip text
     *  intermittently disappear while the cursor sits over a row.
     *
     *  <p>Painting the text directly in paintComponent skips
     *  Substance's LabelUI entirely. The chip's CompoundBorder
     *  (line + empty padding) still paints normally because
     *  {@link JComponent#paintBorder(Graphics)} is independent of the
     *  L&F text path. Honours horizontal-alignment + border insets so
     *  centred and left-aligned chip variants both render correctly. */
    static final class ChipLabel extends JLabel
    {
        ChipLabel(String text)
        {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            var g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(KEY_TEXT_ANTIALIASING,
                    VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getForeground());
                g2.setFont(getFont());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                String t = getText();
                if (t == null || t.isEmpty()) return;
                int textW = fm.stringWidth(t);
                // Centre vertically inside the inner content box (height
                // minus border insets). Centre or left-align horizontally
                // based on the JLabel's horizontalAlignment setting so
                // makeActionChip-style centred chips and the default
                // left-aligned chips both render at the right x.
                int innerH = getHeight() - in.top - in.bottom;
                int baselineY = in.top + ((innerH - fm.getHeight()) / 2) + fm.getAscent();
                int x = in.left;
                if (getHorizontalAlignment() == CENTER)
                {
                    int innerW = getWidth() - in.left - in.right;
                    x = in.left + Math.max(0, (innerW - textW) / 2);
                }
                else if (getHorizontalAlignment() == RIGHT
                    || getHorizontalAlignment() == TRAILING)
                {
                    int innerW = getWidth() - in.left - in.right;
                    x = in.left + Math.max(0, innerW - textW);
                }
                g2.drawString(t, x, baselineY);
            }
            finally
            {
                g2.dispose();
            }
        }

        /** Substance L&F's LabelUI computes a preferred width using its
         *  own font metrics that under-report glyph widths for the
         *  derived bold 15pt run we use on the rank slider — the
         *  result is BorderLayout assigning a width slightly less
         *  than the rendered text needs, which Swing then clips at
         *  paint time. Recomputing preferred width from the actual
         *  AWT FontMetrics here gets the layout-time and paint-time
         *  metrics back in sync, so labels like "Bronze 3" /
         *  "3rd Age 1" get exactly the width they paint into.
         *
         *  Pure JLabels (no border, no icon) so the calc is just
         *  insets + text width + tiny safety margin. The +2px
         *  belt-and-braces guards against sub-pixel rounding when
         *  the parent uses a non-integer Graphics2D scale (HiDPI).
         *  Height defers to super so vertical-centring inside
         *  paintComponent agrees with the parent's row height. */
        @Override
        public Dimension getPreferredSize()
        {
            String t = getText();
            if (t == null || t.isEmpty()) return super.getPreferredSize();
            FontMetrics fm = getFontMetrics(getFont());
            Insets in = getInsets();
            int w = fm.stringWidth(t) + in.left + in.right + 2;
            int h = super.getPreferredSize().height;
            return new Dimension(w, h);
        }
    }

    /** Two-child header row where {@code base} spans the row's full width
     *  and {@code overlay} is right-anchored on top of it (in Swing
     *  z-order). The overlay must be opaque with the row's background
     *  colour — that's what visually masks any base content that would
     *  otherwise overflow into the overlay's territory.
     *
     *  Used for player-name rows so long names render their full text and
     *  visually clip under the rank/lookup cluster instead of ellipsizing.
     *  The 4px {@code EmptyBorder(0, 4, 0, 0)} the caller sets on the
     *  overlay provides the "minimal spacing" gap the user requested
     *  between the last visible character of the name and the rank text. */
    static final class RightRow extends CapPanel
    {
        private final JComponent base;
        private final JComponent overlay;

        RightRow(JComponent base, JComponent overlay)
        {
            setLayout(null); // manual layout — neither BorderLayout nor BoxLayout supports z-stacked overlap
            setOpaque(false);
            this.base = base;
            this.overlay = overlay;
            // Swing paints children in REVERSE component-array order, so
            // index 0 is drawn last (= on top). Add overlay first so it
            // wins the z-order against the base.
            add(overlay);
            add(base);
        }

        @Override
        public void doLayout()
        {
            int w = getWidth();
            int h = getHeight();
            Dimension op = overlay.getPreferredSize();
            int ow = Math.min(op.width, w);
            overlay.setBounds(w - ow, 0, ow, h);
            // Base spans the entire row; its right portion is visually
            // masked by the overlay's opaque background where they overlap.
            base.setBounds(0, 0, w, h);
        }

        @Override
        public Dimension getPreferredSize()
        {
            Dimension bp = base.getPreferredSize();
            Dimension op = overlay.getPreferredSize();
            // Preferred = base + overlay so the parent BoxLayout knows the
            // "natural" width. Actual width comes from the parent and may
            // be smaller, in which case base clips under overlay.
            return new Dimension(bp.width + op.width, Math.max(bp.height, op.height));
        }

        @Override
        public Dimension getMinimumSize()
        {
            // Allow the row to shrink horizontally to just the overlay's
            // width — the base happily clips under it.
            Dimension op = overlay.getPreferredSize();
            return new Dimension(op.width, getPreferredSize().height);
        }
    }
}
