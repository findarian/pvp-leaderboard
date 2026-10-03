package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.lobby.BuildType;
import com.pvp.leaderboard.lobby.LobbyMember;
import com.pvp.leaderboard.lobby.OutgoingInvite;
import com.pvp.leaderboard.lobby.Style;
import com.pvp.leaderboard.util.RankUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
class PlayerCard extends JPanel
{
    /** Single fixed border used in both idle and hover states. The white
     *  hover outline is drawn manually in {@link #paintBorder(Graphics)}
     *  rather than via setBorder-swap so:
     *    1. The component's preferred size never changes on hover (the
     *       bottom matte and the hover outline both occupy the same 1px
     *       gutter at the row edge), so the inner scrollbar doesn't
     *       flicker between hidden/shown when the cursor moves between
     *       rows.
     *    2. We avoid a setBorder() call per hover transition. setBorder
     *       fires a PropertyChangeEvent + repaint per call; mouse-wheel
     *       scrolling can fire dozens of those per second as rows pass
     *       under the cursor, which was the residual scroll stutter. */
    private static final Border ROW_BORDER_FIXED = BorderFactory.createCompoundBorder(
        new MatteBorder(0, 0, 1, 0, new Color(0x40, 0x40, 0x40)),
        BorderFactory.createEmptyBorder(4, 6, 4, 6));

    private static final Color CHIP_BORDER_COLOR = new Color(0x88, 0x88, 0x88);
    /** Yellow for [Invited M:SS] — same family as Confirm-fight emphasis. */
    private static final Color CHIP_INVITED_COLOR = new Color(0xE5, 0xC0, 0x6B);
    /** Muted foreground used everywhere the row is greyed (blocked
     *  state). Matches the JLabel default-disabled tone so it reads
     *  as "inactive" without changing the panel's overall colour
     *  palette. The rank label is recoloured to the same grey so it
     *  doesn't pop against the muted name. */
    private static final Color BLOCKED_FG = new Color(0x70, 0x70, 0x70);
    private static final Color BLOCKED_BORDER = new Color(0x50, 0x50, 0x50);

    final LobbyMember p; // package-private so the lobby's tick refresher can key on it
    private final int fontBase;
    /** The chips' size. */
    private final int chipPt;
    /** Only the member's own style and build chips, lit (no dimmed chips for the others). */
    private final boolean litChipsOnly;
    /** Re-queried on every {@link #render()} so the chip stays live. */
    private final Predicate<LobbyMember> isInvited;
    private final Function<LobbyMember, OutgoingInvite> getOutgoing;
    private final Consumer<String> onOpenProfile;
    private final MatchmakingLobbyPanel.FightStartCallback onFight;
    private final MatchmakingLobbyPanel.InviteCancelCallback onCancelInvite;
    /** Re-queried at construction so the next renderRoster picks up
     *  block changes immediately (block listener overrides call
     *  renderRoster directly). */
    private final boolean blocked;
    /** Re-queried at construction — true when the viewer's own rank
     *  sits outside this member's accept-invite slider band. The
     *  member would server-reject any {@code lobby/invite} from us
     *  with {@code RANK_OUT_OF_RANGE}, so the row renders with the
     *  whole-row grey treatment (matches Blocking visually) and a
     *  greyed-disabled [Fight] chip. Differs from {@link #blocked}
     *  in chip text + tooltip + click handler; both flags fold
     *  into {@link #greyed} for the body greying. */
    private final boolean outOfTheirRange;
    /** Operator matchmaking-suspend stamp ({@link LobbyMember#isSuspended},
     *  from {@code lobby/roster}'s {@code is_suspended}). When true the
     *  member stays visible but their [Fight] is greyed for every viewer
     *  (the server rejects invite/accept with {@code MATCHMAKING_SUSPENDED}).
     *  Folds into {@link #greyed} like the other two flags but keeps its
     *  own chip tooltip. */
    private final boolean suspended;
    /** Unified "render this row as muted / inactive" flag — the union
     *  of {@link #blocked}, {@link #outOfTheirRange}, and
     *  {@link #suspended} (see {@link #rowGreyed}). Drives the
     *  name colour, rank-chip hue, and every region/style/build
     *  chip's active/inactive palette so the whole row reads as
     *  inactive when any condition holds. The source flags
     *  stay separate for chip text + click semantics. */
    private final boolean greyed;
    /** Re-queried at construction — false when style/build
     *  advertisement doesn't overlap the local user's gate picks;
     *  [Fight] renders greyed and is a no-op. */
    private final boolean fightEnabled;
    /** {@code true} for the "Your profile displayed to others"
     *  row above the slider — suppresses the right-side action
     *  chip (no Fight / no Lookup chip — clicking the row body
     *  still opens the lookup) and any rank-text rendering when
     *  {@link LobbyMember#peakRankIdx} is sentinel-negative. */
    private final boolean selfPreview;

    /** Tracks the cursor across child boundaries so child→child crossings
     *  don't false-trigger an unhover on the leaf JLabel. */
    private boolean hovered;

    PlayerCard(LobbyMember p, int fontBase,
              Predicate<LobbyMember> isInvited,
              Function<LobbyMember, OutgoingInvite> getOutgoing,
              Consumer<String> onOpenProfile,
              MatchmakingLobbyPanel.FightStartCallback onFight,
              MatchmakingLobbyPanel.InviteCancelCallback onCancelInvite,
              Predicate<LobbyMember> isBlocked,
              boolean selfPreview)
    {
        this(p, fontBase, chipPtFor(fontBase), false, isInvited, getOutgoing, onOpenProfile, onFight,
            onCancelInvite, isBlocked, m -> true, m -> false, selfPreview);
    }

    /** A card with its own chip size ({@code chipPt}) apart from the name's ({@code fontBase}). */
    PlayerCard(LobbyMember p, int fontBase, int chipPt,
              Predicate<LobbyMember> isInvited,
              Function<LobbyMember, OutgoingInvite> getOutgoing,
              Consumer<String> onOpenProfile,
              MatchmakingLobbyPanel.FightStartCallback onFight,
              MatchmakingLobbyPanel.InviteCancelCallback onCancelInvite,
              Predicate<LobbyMember> isBlocked,
              boolean selfPreview)
    {
        this(p, fontBase, chipPt, false, isInvited, getOutgoing, onOpenProfile, onFight,
            onCancelInvite, isBlocked, m -> true, m -> false, selfPreview);
    }

    /** A card with its own chip size; {@code litChipsOnly} shows the member's own style and build chips alone. */
    PlayerCard(LobbyMember p, int fontBase, int chipPt, boolean litChipsOnly,
              Predicate<LobbyMember> isInvited,
              Function<LobbyMember, OutgoingInvite> getOutgoing,
              Consumer<String> onOpenProfile,
              MatchmakingLobbyPanel.FightStartCallback onFight,
              MatchmakingLobbyPanel.InviteCancelCallback onCancelInvite,
              Predicate<LobbyMember> isBlocked,
              boolean selfPreview)
    {
        this(p, fontBase, chipPt, litChipsOnly, isInvited, getOutgoing, onOpenProfile, onFight,
            onCancelInvite, isBlocked, m -> true, m -> false, selfPreview);
    }

    PlayerCard(LobbyMember p, int fontBase,
              Predicate<LobbyMember> isInvited,
              Function<LobbyMember, OutgoingInvite> getOutgoing,
              Consumer<String> onOpenProfile,
              MatchmakingLobbyPanel.FightStartCallback onFight,
              MatchmakingLobbyPanel.InviteCancelCallback onCancelInvite,
              Predicate<LobbyMember> isBlocked,
              Predicate<LobbyMember> fightEnabled,
              Predicate<LobbyMember> isOutOfTheirRange,
              boolean selfPreview)
    {
        this(p, fontBase, chipPtFor(fontBase), false, isInvited, getOutgoing, onOpenProfile, onFight,
            onCancelInvite, isBlocked, fightEnabled, isOutOfTheirRange, selfPreview);
    }

    /** The chip size the lobby's rows use for a name size: three points under it, never under 10. */
    static int chipPtFor(int fontBase)
    {
        return Math.max(10, fontBase - 3);
    }

    private PlayerCard(LobbyMember p, int fontBase, int chipPt, boolean litChipsOnly,
              Predicate<LobbyMember> isInvited,
              Function<LobbyMember, OutgoingInvite> getOutgoing,
              Consumer<String> onOpenProfile,
              MatchmakingLobbyPanel.FightStartCallback onFight,
              MatchmakingLobbyPanel.InviteCancelCallback onCancelInvite,
              Predicate<LobbyMember> isBlocked,
              Predicate<LobbyMember> fightEnabled,
              Predicate<LobbyMember> isOutOfTheirRange,
              boolean selfPreview)
    {
        this.p = p;
        this.fontBase = fontBase;
        this.chipPt = chipPt;
        this.litChipsOnly = litChipsOnly;
        this.isInvited = isInvited;
        this.getOutgoing = getOutgoing;
        this.onOpenProfile = onOpenProfile;
        this.onFight = onFight;
        this.onCancelInvite = onCancelInvite;
        this.blocked = isBlocked != null && isBlocked.test(p);
        this.outOfTheirRange = isOutOfTheirRange != null && isOutOfTheirRange.test(p);
        this.suspended = p != null && p.isSuspended;
        this.greyed = MatchmakingLobbyPanel.rowGreyed(this.blocked, this.outOfTheirRange, this.suspended);
        this.fightEnabled = fightEnabled == null || fightEnabled.test(p);
        this.selfPreview = selfPreview;
        setLayout(new BorderLayout());
        setBackground(new Color(0x2b, 0x2b, 0x2b));
        setBorder(ROW_BORDER_FIXED);
        setAlignmentX(LEFT_ALIGNMENT);
        render();
    }

    /** Draw the white hover outline manually so swapping it on/off doesn't
     *  fire setBorder()'s PropertyChangeEvent + repaint per row per scroll
     *  tick — that was the residual stutter source. The line is drawn over
     *  the matte's bottom pixel and the row's other 3 edges; total visual
     *  size is unchanged. */
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

    /** Pin row height to preferred so BoxLayout Y_AXIS doesn't spread
     *  rows to fill the viewport (regression from beta 1). */
    @Override
    public Dimension getMaximumSize()
    {
        Dimension d = super.getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, d.height);
    }

    void render()
    {
        removeAll();
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);
        center.add(buildHeaderRow());
        center.add(Box.createVerticalStrut(3));
        center.add(buildChipsRow());
        // Bottom row: build chips by default ([MOD]? [Main] [Zerker]
        // [Pure]) OR — when the row is greyed because the viewer's
        // rank is outside this member's accept-invite band — the
        // "Accepts fights from <min> to <max> rating" label with
        // both rank labels rank-colour-coded. The swap matches
        // 2026-05-26 user spec: the build chips carry no
        // actionable info when we can't invite them anyway, so we
        // reclaim that row to explain WHY the row is greyed.
        // Blocking-greyed rows keep the build chips because the
        // [Blocking] chip + tooltip already explain that state.
        // Build chips render on a separate row below so [Pure][Zerker]
        // [Main] don't compete with the longer [Region][NH][Veng][Multi]
        // [DMM] strip — 8 chips on one FlowLayout row wraps unpredictably
        // in the 225px sidepanel.
        center.add(Box.createVerticalStrut(2));
        if (outOfTheirRange && !blocked)
        {
            center.add(buildAcceptsRangeRow());
        }
        else
        {
            center.add(buildBuildsRow());
        }

        add(center, BorderLayout.CENTER);

        // Self-preview suppresses the EAST action chip — there's
        // no Fight option (can't fight yourself) and no Lookup
        // chip either (the whole row body is already clickable
        // and routes to routeOpenProfile). Other rows still get
        // their chip per buildActionChip() rules.
        if (!selfPreview)
        {
            // Wrap the chip so the 6px margin lives on the wrapper, not the
            // chip's own border (chip border tracks its text width).
            JComponent action = buildActionChip();
            JPanel actionWrap = new JPanel(new BorderLayout());
            actionWrap.setOpaque(false);
            actionWrap.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            actionWrap.add(action, BorderLayout.CENTER);
            add(actionWrap, BorderLayout.EAST);
        }

        // Profile-click handler walks only the CENTER subtree so the
        // action chip can never double-fire as a profile click.
        installProfileInteraction(center);

        revalidate();
        repaint();
    }

    private JComponent buildActionChip()
    {
        // Resolve the LABEL via the source-of-truth helper so the
        // text decision is unit-tested without instantiating the
        // panel; the COLOUR + tooltip + click handler still
        // branch here because they depend on Swing internals.
        OutgoingInvite oi = getOutgoing != null ? getOutgoing.apply(p) : null;
        boolean invited = isInvited != null && isInvited.test(p);
        String label = MatchmakingLobbyPanel.actionChipLabelFor(blocked, oi, invited);

        if (blocked)
        {
            // Blocked card: chip routes to Player Lookup → Unblock
            // (no dedicated "Unblock" widget on the row). Colour
            // matches the greyed body so the chip reads "muted /
            // inactive". Label is "Blocking" per 2026-05-25 user
            // spec — explicit verb signals the active block state
            // without relying on the tooltip.
            JLabel chip = makeActionChip(label, BLOCKED_FG, BLOCKED_BORDER,
                () -> { if (onOpenProfile != null) onOpenProfile.accept(p.name); });
            chip.setName(MatchmakingLobbyPanel.ROW_ACTION_CHIP_NAME);
            chip.setToolTipText("Blocking " + p.name + " — open their profile to Unblock");
            return chip;
        }
        if (oi != null)
        {
            JLabel chip = makeActionChip(label, CHIP_INVITED_COLOR, CHIP_INVITED_COLOR,
                () -> { if (onCancelInvite != null) onCancelInvite.cancel(p); });
            chip.setName(MatchmakingLobbyPanel.ROW_ACTION_CHIP_NAME);
            chip.setToolTipText("Pending invite to " + p.name + " — click to cancel");
            return chip;
        }
        if (invited)
        {
            JLabel chip = makeActionChip(label, Color.WHITE, CHIP_BORDER_COLOR,
                () -> { if (onOpenProfile != null) onOpenProfile.accept(p.name); });
            chip.setName(MatchmakingLobbyPanel.ROW_ACTION_CHIP_NAME);
            chip.setToolTipText("Open " + p.name + "'s profile in Player Lookup");
            return chip;
        }
        if (outOfTheirRange)
        {
            // Their accept-invite slider excludes the viewer's
            // current rank — server would RANK_OUT_OF_RANGE any
            // lobby/invite we sent, so the chip renders greyed
            // and is a no-op. Body greying is handled separately
            // via the {@link #greyed} flag so the whole row
            // reads as muted (matches the Blocking visual
            // treatment, per 2026-05-26 user spec).
            JLabel chip = makeActionChip(label, BLOCKED_FG, BLOCKED_BORDER, () -> {});
            chip.setName(MatchmakingLobbyPanel.ROW_ACTION_CHIP_NAME);
            chip.setCursor(Cursor.getDefaultCursor());
            chip.setToolTipText(p.name + " only accepts invites within their own rank range — your rank is outside it");
            return chip;
        }
        if (suspended)
        {
            // Operator matchmaking-suspend: the server rejects any
            // invite/accept with MATCHMAKING_SUSPENDED, so the chip
            // renders greyed and is a no-op for every viewer. Body
            // greying is handled via the {@link #greyed} flag.
            JLabel chip = makeActionChip(label, BLOCKED_FG, BLOCKED_BORDER, () -> {});
            chip.setName(MatchmakingLobbyPanel.ROW_ACTION_CHIP_NAME);
            chip.setCursor(Cursor.getDefaultCursor());
            chip.setToolTipText(p.name + " is temporarily suspended from matchmaking");
            return chip;
        }
        if (!fightEnabled)
        {
            JLabel chip = makeActionChip(label, BLOCKED_FG, BLOCKED_BORDER, () -> {});
            chip.setName(MatchmakingLobbyPanel.ROW_ACTION_CHIP_NAME);
            chip.setCursor(Cursor.getDefaultCursor());
            chip.setToolTipText("No matching style/build — click Leave Lobby and re-join with wider filters");
            return chip;
        }
        JLabel chip = makeActionChip(label, Color.WHITE, CHIP_BORDER_COLOR,
            () -> { if (onFight != null) onFight.onFight(p); });
        chip.setName(MatchmakingLobbyPanel.ROW_ACTION_CHIP_NAME);
        chip.setToolTipText("Set up a fight with " + p.name);
        return chip;
    }

    /** Recursively installs the row's hover/click handler on every
     *  component inside {@code root}. We have to walk the tree because
     *  Swing dispatches mouse events to the deepest hit component — a
     *  listener on just the row would never see clicks that land on the
     *  name JLabel or a chip. */
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
                // Child→child crossings fire exit on the leaf — only
                // unhover when the cursor really left the row bounds.
                Point inRow = SwingUtilities.convertPoint(
                    e.getComponent(), e.getPoint(), PlayerCard.this);
                if (!PlayerCard.this.contains(inRow)) setHover(false);
            }
        };
        addMouseListener(ma);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        attachListenerRecursively(root, ma);
    }

    private static void attachListenerRecursively(Container c, MouseListener ml)
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
                attachListenerRecursively((Container) child, ml);
            }
        }
    }

    private void setHover(boolean on)
    {
        if (this.hovered == on) return;
        this.hovered = on;
        // repaint() only — no setBorder() means no PropertyChangeEvent and
        // no parent revalidation cascade during mouse-wheel scroll.
        repaint();
    }

    /** name (base, full row width, no ellipsis) + rank (overlay,
     *  right-anchored, opaque on row bg, drawn on top via z-order).
     *  Long names render their full text and visually clip "under"
     *  the rank chip — the rank's opaque background masks the overflow
     *  and a 4px left padding on the rank gives a minimal readable
     *  gap between the last visible name char and the rank text. */
    private JComponent buildHeaderRow()
    {
        // peakRankIdx < 0 is the "unknown" sentinel. Two
        // sources produce it:
        //   1. Self-preview row — {@code selfPreview == true} —
        //      no authoritative self-rank source yet, so skip
        //      the right-side rank chip entirely and render the
        //      name in plain white (rank-coloured hue would be
        //      meaningless without a real rank).
        //   2. Remote roster row whose server-side rating
        //      hasn't been computed yet (first-ever roster push
        //      after signup, partial backend deploy missing
        //      rank_idx, etc) — {@code selfPreview == false} —
        //      render a muted "Waiting" chip in the rank slot
        //      so the row doesn't mis-render as RANK_LABELS[0]
        //      ("Bronze 3") and the user understands the rating
        //      simply hasn't arrived.
        // Bounds-check also defends against any future
        // out-of-range index from a malformed wire payload.
        boolean rankKnown = p.peakRankIdx >= 0 && p.peakRankIdx < RANK_LABELS.length;
        String rankLabel = rankKnown ? RANK_LABELS[p.peakRankIdx] : "";
        // Greyed rows render in a muted grey across the entire
        // header (name + rank chip) so the row reads as inactive.
        // Covers both Blocking (the local user blocked them) and
        // out-of-their-range (their slider excludes the viewer).
        // The rank label's normal hue would otherwise pop against
        // the greyed name.
        Color rankColor = greyed
            ? BLOCKED_FG
            : (rankKnown ? RankUtils.getRankColor(rankLabel) : Color.WHITE);

        String displayName = displayNameOf(p);
        NonEllipsisLabel name = new NonEllipsisLabel(displayName);
        name.setFont(name.getFont().deriveFont(Font.BOLD, (float) fontBase));
        name.setForeground(rankColor);
        // Blocking and out-of-their-range share the muted body but
        // need different hover text so the user knows WHY the row
        // is dim. Blocking wins precedence (it implies a deliberate
        // user action; out-of-their-range is just a slider state).
        String nameTooltip;
        if (blocked) nameTooltip = displayName + " (blocked — click to Unblock from Player Lookup)";
        else if (outOfTheirRange) nameTooltip = displayName + " (your rank is outside their accept-invite range)";
        else if (suspended) nameTooltip = displayName + " (temporarily suspended from matchmaking)";
        else nameTooltip = displayName;
        name.setToolTipText(nameTooltip);

        if (!rankKnown)
        {
            if (selfPreview)
            {
                // No rank chip on the right — just the name flush-left.
                // OverlayRightRow expects two children; substitute an
                // empty placeholder so the layout invariants hold.
                JLabel placeholder = new JLabel("");
                placeholder.setOpaque(false);
                return new OverlayRightRow(name, placeholder);
            }
            // Remote row with unknown rank — render "Waiting" in
            // the same slot a real rank label would occupy. Muted
            // grey (or greyed-row grey) so it's visually subordinate
            // to real rank labels and never gets confused for a
            // real rank tier. Tooltip explains the state.
            Color waitingColor = greyed ? BLOCKED_FG : new Color(0xaa, 0xaa, 0xaa);
            JLabel waiting = new JLabel("Waiting");
            waiting.setFont(waiting.getFont().deriveFont(Font.BOLD, (float) fontBase));
            waiting.setForeground(waitingColor);
            waiting.setHorizontalAlignment(SwingConstants.RIGHT);
            waiting.setOpaque(true);
            waiting.setBackground(new Color(0x2b, 0x2b, 0x2b));
            waiting.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
            waiting.setToolTipText("Rating not yet available");
            return new OverlayRightRow(name, waiting);
        }

        JLabel rank = new JLabel(rankLabel);
        rank.setFont(rank.getFont().deriveFont(Font.BOLD, (float) fontBase));
        rank.setForeground(rankColor);
        rank.setHorizontalAlignment(SwingConstants.RIGHT);
        // Opaque + matching row bg = masks the overflowing name text
        // wherever the two would otherwise visually overlap. The 4px
        // left inset is the "minimal spacing" gap.
        rank.setOpaque(true);
        rank.setBackground(new Color(0x2b, 0x2b, 0x2b));
        rank.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        return new OverlayRightRow(name, rank);
    }

    /** [Region] [NH] [Veng] [Multi] [DMM] — style chips dim when not
     *  advertised. The MOD chip lives on the build row below, not here.
     *  When the player advertises <i>every</i> style, the four
     *  per-style chips collapse into a single [Any Style] chip so
     *  the row matches the "Style: Any Style" wording from the
     *  pre-lobby gate's current-style bar. */
    private JPanel buildChipsRow()
    {
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        chips.setOpaque(false);
        chips.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        int chipFont = chipPt;
        // Palette: region=white, style=yellow. Greyed rows
        // (Blocking or out-of-their-range) force all chips to the
        // inactive grey palette so the whole card reads as muted.
        if (p.region != null && !p.region.trim().isEmpty()) chips.add(makeChip(p.region, !greyed, Color.WHITE, chipFont));
        Color styleColor = new Color(0xff, 0xc1, 0x07);
        if (litChipsOnly)
        {
            for (Style s : Style.values())
            {
                if (p.styles.contains(s)) chips.add(makeChip(s.label, !greyed, styleColor, chipFont));
            }
        }
        else if (p.styles.size() == Style.values().length)
        {
            chips.add(makeChip("Any Style", !greyed, styleColor, chipFont));
        }
        else
        {
            for (Style s : Style.values())
            {
                boolean advertised = !greyed && p.styles.contains(s);
                chips.add(makeChip(s.label, advertised, styleColor, chipFont));
            }
        }
        return chips;
    }

    /** [MOD]? [Main] [Zerker] [Pure] — build chips dim when the player
     *  doesn't advertise that build. Cyan so they're visually distinct
     *  from the yellow style chips on the row above; MOD stays red.
     *  p.builds is guaranteed ≥1 by LobbyMember's contract, so at least
     *  one build chip is always lit. When the player advertises
     *  <i>every</i> build type, the per-build chips collapse into a
     *  single [Any Build] chip mirroring the {@code buildChipsRow}
     *  [Any Style] collapse. */
    private JPanel buildBuildsRow()
    {
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        chips.setOpaque(false);
        chips.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        int chipFont = chipPt;
        if (p.isMod)
        {
            chips.add(makeChip("MOD", !greyed, new Color(0xff, 0x55, 0x55), chipFont));
        }
        Color buildColor = new Color(0x4f, 0xc3, 0xf7);
        if (litChipsOnly)
        {
            for (BuildType a : BuildType.values())
            {
                if (p.builds.contains(a)) chips.add(makeChip(a.label, !greyed, buildColor, chipFont));
            }
        }
        else if (p.builds.size() == BuildType.values().length)
        {
            chips.add(makeChip("Any Build", !greyed, buildColor, chipFont));
        }
        else
        {
            for (BuildType a : BuildType.values())
            {
                boolean advertised = !greyed && p.builds.contains(a);
                chips.add(makeChip(a.label, advertised, buildColor, chipFont));
            }
        }
        return chips;
    }

    /** Bottom-row swap for out-of-their-range greyed rows:
     *  "Accepts fights from &lt;min&gt; to &lt;max&gt; rating"
     *  with both rank labels coloured per
     *  {@link RankUtils#getRankColor(String)} (same colour
     *  scheme as the panel's own rank-range slider min/max
     *  labels — visual rhyme).
     *
     *  <p>HTML rendering rather than three side-by-side labels
     *  because (a) it keeps the row tight (no internal layout
     *  cascade per render) and (b) the surrounding "Accepts
     *  fights from" / "to" / "rating" text needs to wrap
     *  cleanly inside the ~200 px sidepanel — a FlowLayout of
     *  separately-coloured labels would break to a new line
     *  mid-phrase. */
    private JComponent buildAcceptsRangeRow()
    {
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        int textFont = chipPt;
        JLabel label = new JLabel(MatchmakingLobbyPanel.buildAcceptsRangeHtml(
            p.minRankIdx, p.maxRankIdx, RANK_LABELS));
        label.setFont(label.getFont().deriveFont(Font.BOLD, (float) textFont));
        // Surrounding copy ("Accepts fights from", "to", "rating")
        // stays in the muted-grey palette used by every greyed-row
        // body element so only the rank labels themselves pop.
        label.setForeground(BLOCKED_FG);
        label.setToolTipText(p.name + "'s accept-invite rank range is "
            + MatchmakingLobbyPanel.safeRankLabel(p.minRankIdx, RANK_LABELS)
            + " to " + MatchmakingLobbyPanel.safeRankLabel(p.maxRankIdx, RANK_LABELS));
        wrap.add(label);
        return wrap;
    }

    private static JLabel makeChip(String text, boolean active, Color activeColor, int fontPt)
    {
        // ChipLabel (not a vanilla JLabel) bypasses Substance L&F's
        // LabelUI text painting. Substance treats MouseEntered /
        // MouseExited from the row-level MouseAdapter (installed
        // via attachListenerRecursively, which fans out to every
        // child including these chips) as a label-rollover state
        // change and triggers a delegate repaint that briefly
        // erases the foreground text — visible to the user as the
        // chip's contents disappearing while the cursor is over a
        // row. Painting the text ourselves in paintComponent skips
        // the delegate entirely; the border still paints normally
        // because paintBorder is unaffected by the L&F text path.
        ChipLabel chip = new ChipLabel(text);
        chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, (float) fontPt));
        chip.setForeground(active ? activeColor : new Color(0x55, 0x55, 0x55));
        Color borderColor = active ? activeColor : new Color(0x3a, 0x3a, 0x3a);
        chip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, 1),
            BorderFactory.createEmptyBorder(1, 5, 1, 5)));
        chip.setOpaque(false);
        return chip;
    }

    /** Tight JLabel chip — much smaller than a JButton under Substance L&F.
     *  textColor / borderColor are split so callers can paint white text
     *  inside a quiet grey outline ([Fight] / [Lookup] convention).
     *
     *  <p>ChipLabel (not vanilla JLabel) so the action text doesn't
     *  flicker / disappear on hover under Substance — same fix as
     *  {@link #makeChip}. Otherwise users can lose track of which
     *  chip says [Fight] vs [Invited 9:42] mid-hover. */
    private JLabel makeActionChip(String text, Color textColor, Color borderColor, Runnable onClick)
    {
        ChipLabel chip = new ChipLabel(text);
        chip.setFont(chip.getFont().deriveFont(Font.BOLD, (float) fontBase));
        chip.setForeground(textColor);
        chip.setHorizontalAlignment(SwingConstants.CENTER);
        chip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, 1),
            BorderFactory.createEmptyBorder(1, 3, 1, 3)));
        chip.setOpaque(false);
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        chip.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e) { onClick.run(); }
        });
        return chip;
    }

    /** The lobby's rank labels ("Bronze 3" ... "3rd Age"), indexed like {@link RankUtils#THRESHOLDS}. */
    static final String[] RANK_LABELS = buildRankLabels();

    /** A member's name for the card: the display name, else the id, else "Unknown". */
    static String displayNameOf(LobbyMember m)
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
    private static String[] buildRankLabels()
    {
        String[] out = new String[RankUtils.THRESHOLDS.length];
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
     *  Intended for player-name labels paired with an {@link OverlayRightRow}
     *  that lets the right-side rank chip visually mask the overflowing
     *  characters. Not a drop-in for general JLabels — single-line LTR
     *  text only, no icon, no border insets, vertical-centered baseline. */
    static final class NonEllipsisLabel extends JLabel
    {
        NonEllipsisLabel(String text)
        {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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

    /** Border-aware sibling of {@link NonEllipsisLabel} used for the
     *  per-row style/build/region chips ([Main], [Pure], [Any Build],
     *  [NA-W], etc.). Same rationale as NonEllipsisLabel — bypass the
     *  Substance L&F text-rendering path — but here the motivation is
     *  hover stability, not ellipsis suppression: under Substance, a
     *  vanilla JLabel parented inside a panel that has a row-level
     *  MouseListener fanout (PlayerRow's attachListenerRecursively
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
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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
                if (getHorizontalAlignment() == SwingConstants.CENTER)
                {
                    int innerW = getWidth() - in.left - in.right;
                    x = in.left + Math.max(0, (innerW - textW) / 2);
                }
                else if (getHorizontalAlignment() == SwingConstants.RIGHT
                    || getHorizontalAlignment() == SwingConstants.TRAILING)
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
    static final class OverlayRightRow extends JPanel
    {
        private final JComponent base;
        private final JComponent overlay;

        OverlayRightRow(JComponent base, JComponent overlay)
        {
            super(null); // manual layout — neither BorderLayout nor BoxLayout supports z-stacked overlap
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
        public Dimension getMaximumSize()
        {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
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
