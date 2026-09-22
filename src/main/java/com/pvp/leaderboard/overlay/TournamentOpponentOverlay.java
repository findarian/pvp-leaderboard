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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.List;
import java.util.function.Supplier;

/**
 * Yellow outline around the local player's current tournament opponent
 * (Plan 10 F.4, 2026-09-21). Driven only by the server: the
 * {@link Supplier} the plugin wires returns the opponent's name while
 * {@code tournament/opponent_highlight} is in force (the
 * {@code TournamentSessionTracker} clears it on {@code _clear}, removal,
 * cancel, finish or the {@code until} deadline) and {@code null} otherwise.
 * Nothing is drawn for the local player or when the opponent is not in the
 * scene. Player-model outline only — no minimap dot (Q-15).
 */
@Singleton
public class TournamentOpponentOverlay extends Overlay
{
    /** The operator's "yellow outline" (mockup: dashed #ffd700). */
    static final Color OUTLINE = new Color(0xFF, 0xD7, 0x00, 220);

    private final Client client;
    private volatile Supplier<String> opponentSupplier = () -> null;

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
            String target = opponentSupplier.get();
            if (target == null || target.trim().isEmpty()) return null;
            Player opponent = findOpponent(target);
            if (opponent == null) return null;
            Shape hull = opponent.getConvexHull();
            if (hull == null) return null;
            OverlayUtil.renderPolygon(graphics, hull, OUTLINE);
        }
        catch (Exception ignored)
        {
            // A transient scene state must never feed RuneLite's renderer an exception per frame.
        }
        return null;
    }

    /** The scene player whose canonical name matches, never the local player. */
    Player findOpponent(String targetName)
    {
        List<Player> players = client.getPlayers();
        if (players == null) return null;
        Player local = client.getLocalPlayer();
        String wanted = NameUtils.canonicalKey(targetName);
        if (wanted == null || wanted.isEmpty()) return null;
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
