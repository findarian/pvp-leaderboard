package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.outline.*;
import java.util.List;

@Singleton
public class TournamentOpponentOverlay extends Overlay
{
    /** The outline colour. */
    static final Color OUTLINE = new Color(0xffd700);
    private static final int OUTLINE_WIDTH = 2;
    private static final int OUTLINE_FEATHER = 0;

    private final Client client;
    private final ModelOutlineRenderer renderer;
    private final ScenePlayers scene;
    private volatile Supplier<Set<String>> oppsSupplier = () -> null;

    private Set<String> lookupKeys;
    private List<Player> lookupResult = Collections.emptyList();
    private int lookupTick;

    @Inject
    public TournamentOpponentOverlay(Client client, ModelOutlineRenderer renderer, ScenePlayers scene)
    {
        this.client = client;
        this.renderer = renderer;
        this.scene = scene;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    /** The canonical keys of every name to outline; {@code null} or empty for none. Wired by the plugin to the session tracker. */
    public void setOpponentKeysSupplier(Supplier<Set<String>> supplier)
    {
        oppsSupplier = supplier;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        try
        {
            for (Player opponent : opponentsFor(oppsSupplier.get()))
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

    private List<Player> opponentsFor(Set<String> keys)
    {
        if (keys == null || keys.isEmpty()) return Collections.emptyList();
        int tick = client.getTickCount();
        if (!keys.equals(lookupKeys) || tick != lookupTick)
        {
            lookupKeys = keys;
            lookupTick = tick;
            lookupResult = Collections.emptyList();
            lookupResult = findByKeys(keys);
        }
        return lookupResult;
    }

    /** Every scene player whose canonical name is one of {@code wanted}, never the local player. */
    private List<Player> findByKeys(Set<String> wanted)
    {
        Player local = client.getLocalPlayer();
        List<Player> found = new ArrayList<>(1);
        for (Player p : scene.all())
        {
            if (p == null || p == local) continue;
            String name = p.getName();
            if (name == null) continue;
            if (wanted.contains(NameUtils.canonicalKey(name))) found.add(p);
        }
        return found;
    }
}
