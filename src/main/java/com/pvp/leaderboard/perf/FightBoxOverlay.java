package com.pvp.leaderboard.perf;

import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.overlay.Footer;
import com.pvp.leaderboard.ui.Ui;
import java.awt.*;
import java.util.List;
import java.util.function.LongSupplier;
import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;
import net.runelite.client.ui.overlay.components.TextComponent;

/** Each live fight in a box of its own below the kill streak box: both names, then the chosen rows with the values and
 *  colours of the fight's card; a row's full name where it fits the box, else its short one. */
@Singleton
public class FightBoxOverlay extends OverlayPanel
{
    static final int MAX = 10, WIDTH = 160, BORDER = 2, GAP = 3;
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final PerfTracker tracker;
    private final LongSupplier nowMs;
    private final PanelComponent[] boxes = new PanelComponent[MAX];
    final Footer footer = new Footer();

    @Inject
    public FightBoxOverlay(Client client, PvPLeaderboardConfig config, PerfTracker tracker)
    {
        this(client, config, tracker, System::currentTimeMillis);
    }

    FightBoxOverlay(Client client, PvPLeaderboardConfig config, PerfTracker tracker, LongSupplier nowMs)
    {
        this.client = client;
        this.config = config;
        this.tracker = tracker;
        this.nowMs = nowMs;
        setPosition(OverlayPosition.TOP_LEFT);
        setPriority(Overlay.PRIORITY_LOW);
        setResizable(false);
        panelComponent.setBorder(new Rectangle());
        panelComponent.setGap(new Point(0, 2));
        panelComponent.setBackgroundColor(null);
        for (int i = 0; i < MAX; i++)
        {
            boxes[i] = new PanelComponent();
            boxes[i].setBorder(new Rectangle(BORDER, BORDER, BORDER, BORDER));
            boxes[i].setGap(new Point(0, 1));
        }
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        try
        {
            panelComponent.getChildren().clear();
            Player me = client.getLocalPlayer();
            String self = me == null ? null : me.getName();
            if (!config.showFightBox() || self == null) return null;
            List<FightLog.Fight> fights = tracker.live(Math.max(1, Math.min(MAX, config.fightBoxMax())));
            if (fights.isEmpty()) return null;
            boolean[] on = {config.fightBoxOffPray(), config.fightBoxExpectedDamage(), config.fightBoxDamage(),
                config.fightBoxMagicLuck(), config.fightBoxOffensivePrayer(), config.fightBoxHpHealed(),
                config.fightBoxRobeHits(), config.fightBoxKoChances(), config.fightBoxGhostBarrages()};
            g.setFont(FontManager.getRunescapeSmallFont());
            FontMetrics fm = g.getFontMetrics();
            boolean foot = footer.shown(nowMs.getAsLong(), config.alwaysShowPvpLeaderboard());
            int w = foot ? fm.stringWidth(Footer.TEXT) : 0;
            for (FightLog.Fight f : fights)
            {
                w = Math.max(w, fm.stringWidth(self) + GAP + fm.stringWidth(f.opponent));
                for (int i = 0; i < 9; i++)
                {
                    if (on[i]) w = Math.max(w, wide(fm, f, i, FightsPanel.LABELS[i]));
                }
            }
            w = Math.min(WIDTH, w);
            Color bg = getPreferredColor() == null ? ComponentConstants.STANDARD_BACKGROUND_COLOR : getPreferredColor();
            PanelComponent box = null;
            for (int k = 0; k < fights.size(); k++)
            {
                FightLog.Fight f = fights.get(k);
                box = boxes[k];
                box.getChildren().clear();
                box.setBackgroundColor(bg);
                box.getChildren().add(new Row(self, "", f.opponent, new Color[2]));
                for (int i = 0; i < 9; i++)
                {
                    if (!on[i]) continue;
                    String label = wide(fm, f, i, FightsPanel.LABELS[i]) <= w ? FightsPanel.LABELS[i] : FightsPanel.SHORT[i];
                    box.getChildren().add(new Row(FightsPanel.shown(i, f.mine[i]), label, FightsPanel.shownTheirs(i, f.theirs[i]),
                        FightsPanel.tones(i, f.mine[i], f.theirs[i])));
                }
                panelComponent.getChildren().add(box);
            }
            if (foot) box.getChildren().add(LineComponent.builder().left(Footer.TEXT).build());
            panelComponent.setPreferredSize(new Dimension(w + 2 * BORDER + 1, 0));
            return super.render(g);
        }
        catch (Exception e)
        {
            panelComponent.getChildren().clear();
            return null;
        }
    }

    /** Row {@code i}'s width with {@code label}: both values as shown, the label and the gaps between them. */
    private static int wide(FontMetrics fm, FightLog.Fight f, int i, String label)
    {
        return fm.stringWidth(FightsPanel.shown(i, f.mine[i])) + fm.stringWidth(label)
            + fm.stringWidth(FightsPanel.shownTheirs(i, f.theirs[i])) + 2 * GAP;
    }

    /** One line: a value at each edge in its colour, the label between them in grey, centred or moved just enough to
     *  clear a long value. */
    static final class Row implements LayoutableRenderableEntity
    {
        final String[] text;
        final Color[] color;
        final int[] x = new int[3];
        private final Rectangle bounds = new Rectangle();
        private Point at = new Point();
        private int width;

        Row(String left, String mid, String right, Color[] tone)
        {
            text = new String[]{left, mid, right};
            color = new Color[]{tone[0] == null ? Color.WHITE : tone[0], Ui.MUTED, tone[1] == null ? Color.WHITE : tone[1]};
        }

        @Override
        public Dimension render(Graphics2D g)
        {
            FontMetrics fm = g.getFontMetrics();
            int l = fm.stringWidth(text[0]), m = fm.stringWidth(text[1]), r = fm.stringWidth(text[2]), h = fm.getHeight();
            x[1] = Math.max(l + GAP, Math.min((width - m) / 2, width - r - GAP - m));
            x[2] = width - r;
            for (int i = 0; i < 3; i++)
            {
                TextComponent t = new TextComponent();
                t.setText(text[i]);
                t.setColor(color[i]);
                t.setPosition(new Point(at.x + x[i], at.y + h));
                t.render(g);
            }
            bounds.setBounds(at.x, at.y, width, h);
            return bounds.getSize();
        }

        @Override
        public Rectangle getBounds()
        {
            return bounds;
        }

        @Override
        public void setPreferredLocation(Point p)
        {
            at = p;
        }

        @Override
        public void setPreferredSize(Dimension d)
        {
            width = d.width;
        }
    }
}
