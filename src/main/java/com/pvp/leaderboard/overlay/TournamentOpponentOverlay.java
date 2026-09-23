package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.util.NameUtils;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.List;
import java.util.function.Supplier;

@Singleton
public class TournamentOpponentOverlay extends Overlay
{
    /** The operator's "yellow outline" (mockup: dashed #ffd700). */
    static final Color OUTLINE = new Color(0xFF, 0xD7, 0x00, 220);
    private static final Color FILL = new Color(0, 0, 0, 50);
    private static final Stroke BORDER = new BasicStroke(2);

    private final Client client;
    private volatile Supplier<String> opponentSupplier = () -> null;

    private String lookupTarget;
    private String lookupKey;
    private Player lookupResult;
    private int lookupTick;
    private boolean lookedUp;

    @Inject
    public TournamentOpponentOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    /** Wired by the plugin to {@code TournamentSessionTracker::getHighlightedOpponentName}. */
    public void setOpponentSupplier(Supplier<String> supplier)
    {
        this.opponentSupplier = supplier == null ? () -> null : supplier;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        try
        {
            Player opponent = opponentFor(opponentSupplier.get());
            if (opponent == null) return null;
            Shape hull = opponent.getConvexHull();
            if (hull == null) return null;
            OverlayUtil.renderPolygon(graphics, hull, OUTLINE, FILL, BORDER);
        }
        catch (Exception ignored)
        {
            // A transient scene state must never feed RuneLite's renderer an exception per frame.
        }
        return null;
    }

    private Player opponentFor(String target)
    {
        if (target == null)
        {
            forget();
            return null;
        }
        if (!target.equals(lookupTarget))
        {
            forget();
            lookupTarget = target;
            lookupKey = NameUtils.canonicalKey(target);
        }
        if (lookupKey.isEmpty()) return null;
        int tick = client.getTickCount();
        if (!lookedUp || tick != lookupTick)
        {
            lookedUp = true;
            lookupTick = tick;
            lookupResult = null;
            lookupResult = findByKey(lookupKey);
        }
        return lookupResult;
    }

    private void forget()
    {
        lookupTarget = null;
        lookupKey = null;
        lookupResult = null;
        lookedUp = false;
    }

    /** The scene player whose canonical name matches, never the local player. */
    Player findOpponent(String targetName)
    {
        return findByKey(NameUtils.canonicalKey(targetName));
    }

    private Player findByKey(String wanted)
    {
        if (wanted == null || wanted.isEmpty()) return null;
        List<Player> players = client.getPlayers();
        if (players == null) return null;
        Player local = client.getLocalPlayer();
        for (Player p : players)
        {
            if (p == null || p == local) continue;
            String name = p.getName();
            if (name == null) continue;
            if (wanted.equals(NameUtils.canonicalKey(name))) return p;
        }
        return null;
    }
}
