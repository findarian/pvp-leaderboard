package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.util.NameUtils;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Singleton
public class TournamentOpponentOverlay extends Overlay
{
    /** The outline colour. */
    static final Color OUTLINE = new Color(0xFF, 0xD7, 0x00);
    private static final int OUTLINE_WIDTH = 2;
    private static final int OUTLINE_FEATHER = 0;

    private final Client client;
    private final ModelOutlineRenderer renderer;
    private volatile Supplier<List<String>> opponentsSupplier = () -> null;

    private Set<String> lookupKeys;
    private List<Player> lookupResult = Collections.emptyList();
    private int lookupTick;
    private boolean lookedUp;

    @Inject
    public TournamentOpponentOverlay(Client client, ModelOutlineRenderer renderer)
    {
        this.client = client;
        this.renderer = renderer;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    /** One name to outline; {@code null} for none. */
    public void setOpponentSupplier(Supplier<String> supplier)
    {
        this.opponentsSupplier = supplier == null ? () -> null : () ->
        {
            String one = supplier.get();
            return one == null ? null : Collections.singletonList(one);
        };
    }

    /** Every name to outline; {@code null} or empty for none. Wired by the plugin to the session tracker. */
    public void setOpponentNamesSupplier(Supplier<List<String>> supplier)
    {
        this.opponentsSupplier = supplier == null ? () -> null : supplier;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        try
        {
            for (Player opponent : opponentsFor(opponentsSupplier.get()))
            {
                renderer.drawOutline(opponent, OUTLINE_WIDTH, OUTLINE, OUTLINE_FEATHER);
            }
        }
        catch (Exception ignored)
        {
            // A transient scene state must never feed RuneLite's renderer an exception per frame.
        }
        return null;
    }

    private List<Player> opponentsFor(List<String> targets)
    {
        Set<String> keys = keysOf(targets);
        if (keys.isEmpty())
        {
            forget();
            return Collections.emptyList();
        }
        if (!keys.equals(lookupKeys))
        {
            forget();
            lookupKeys = keys;
        }
        int tick = client.getTickCount();
        if (!lookedUp || tick != lookupTick)
        {
            lookedUp = true;
            lookupTick = tick;
            lookupResult = Collections.emptyList();
            lookupResult = findByKeys(keys);
        }
        return lookupResult;
    }

    private static Set<String> keysOf(List<String> targets)
    {
        if (targets == null || targets.isEmpty()) return Collections.emptySet();
        Set<String> keys = new LinkedHashSet<>();
        for (String t : targets)
        {
            if (t == null) continue;
            String key = NameUtils.canonicalKey(t);
            if (!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    private void forget()
    {
        lookupKeys = null;
        lookupResult = Collections.emptyList();
        lookedUp = false;
    }

    /** The scene player whose canonical name matches, never the local player. */
    Player findOpponent(String targetName)
    {
        List<Player> found = findByKeys(keysOf(Collections.singletonList(targetName)));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every scene player whose canonical name is one of {@code wanted}, never the local player. */
    private List<Player> findByKeys(Set<String> wanted)
    {
        if (wanted == null || wanted.isEmpty()) return Collections.emptyList();
        List<Player> players = client.getPlayers();
        if (players == null) return Collections.emptyList();
        Player local = client.getLocalPlayer();
        List<Player> found = new ArrayList<>(1);
        for (Player p : players)
        {
            if (p == null || p == local) continue;
            String name = p.getName();
            if (name == null) continue;
            if (wanted.contains(NameUtils.canonicalKey(name))) found.add(p);
        }
        return found;
    }
}
