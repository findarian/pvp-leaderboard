package com.pvp.leaderboard.game;

import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.ui.*;
import com.pvp.leaderboard.util.*;
import javax.inject.*;
import javax.swing.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.menus.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

@Singleton
public class MenuHandler
{
    private final PvPLeaderboardConfig config;
    private final MenuManager menuManager;
    private final ClientToolbar clientToolbar;

    // Dependencies set after startup via init/update
    private Dashboard dashPanel;
    private NavigationButton navButton;

    @Inject
    public MenuHandler(PvPLeaderboardConfig config, MenuManager menuManager, ClientToolbar clientToolbar)
    {
        this.config = config;
        this.menuManager = menuManager;
        this.clientToolbar = clientToolbar;
    }

    public void init(Dashboard dashPanel, NavigationButton navButton)
    {
        this.dashPanel = dashPanel;
        this.navButton = navButton;
        
        refreshMenu();
    }

    public void refreshMenu()
    {
        try {
            if (config.enablePvpLookupMenu()) {
                menuManager.addPlayerMenuItem("PvP lookup");
            } else {
                menuManager.removePlayerMenuItem("PvP lookup");
            }
        } catch (Exception ignore) {}
    }

    public void shutdown()
    {
        menuManager.removePlayerMenuItem("PvP lookup");
    }

    public void onMenuClick(MenuOptionClicked event)
    {
        try
        {
            if (!config.enablePvpLookupMenu()) {
                return;
            }
            if (event.getMenuAction() != MenuAction.RUNELITE_PLAYER) {
                return;
            }
            if (!"PvP lookup".equals(event.getMenuOption())) {
                return;
            }

            String target = event.getMenuTarget();
            if (target == null) {
                return;
            }

            String cleaned = Text.removeTags(target);
            // Remove trailing (level-xxx)
            cleaned = cleaned.replaceAll("\\s*\\(level-\\d+\\)$", "");
            // Remove any parenthetical annotation like (Skill 1234)
            cleaned = cleaned.replaceAll("\\([^)]*\\)", "");
            // Normalize without converting underscores/hyphens to spaces
            // (RuneScape treats space, underscore, hyphen as equivalent, but we preserve original format)
            String playerName = NameUtils.normalizeDisplayName(cleaned);

            // Open plugin side panel first so the panel exists/visible by the time
            // we ask it to switch tabs. openLookup() handles its own EDT
            // marshalling + forces the Player Lookup tab to the foreground (the
            // 2-tab revamp introduced the Matchmaking Lobby as the default; the
            // old single-view code didn't need to switch).
            if (navButton != null) {
                SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
            }

            if (dashPanel != null) {
                dashPanel.openLookup(playerName);
            }
        }
        catch (Exception e)
        {
             // log.debug("Uncaught exception in onMenuClick", e);
        }
    }
}
