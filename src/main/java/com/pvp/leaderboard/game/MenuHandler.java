package com.pvp.leaderboard.game;

import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.overlay.RankOverlay;
import com.pvp.leaderboard.ui.DashboardPanel;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@Slf4j
@Singleton
public class MenuHandler
{
    private final PvPLeaderboardConfig config;
    private final MenuManager menuManager;
    private final ClientToolbar clientToolbar;

    // Dependencies set after startup via init/update
    private DashboardPanel dashboardPanel;
    private RankOverlay rankOverlay;
    private NavigationButton navButton;

    @Inject
    public MenuHandler(PvPLeaderboardConfig config, MenuManager menuManager, ClientToolbar clientToolbar)
    {
        this.config = config;
        this.menuManager = menuManager;
        this.clientToolbar = clientToolbar;
    }

    public void init(DashboardPanel dashboardPanel, RankOverlay rankOverlay, NavigationButton navButton)
    {
        this.dashboardPanel = dashboardPanel;
        this.rankOverlay = rankOverlay;
        this.navButton = navButton;
        
        refreshMenuOption();
    }

    public void updateNavButton(NavigationButton navButton)
    {
        this.navButton = navButton;
    }

    public void refreshMenuOption()
    {
        try {
            if (config.enablePvpLookupMenu()) {
                menuManager.addPlayerMenuItem("pvp lookup");
            } else {
                menuManager.removePlayerMenuItem("pvp lookup");
            }
        } catch (Exception ignore) {}
    }

    public void shutdown()
    {
        menuManager.removePlayerMenuItem("pvp lookup");
    }

    public void handleMenuOptionClicked(MenuOptionClicked event)
    {
        try
        {
            if (!config.enablePvpLookupMenu()) {
                return;
            }
            if (event.getMenuAction() != MenuAction.RUNELITE_PLAYER) {
                return;
            }
            if (!"pvp lookup".equals(event.getMenuOption())) {
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
            String playerName = Text.toJagexName(cleaned).replace('\u00A0',' ').trim().replaceAll("\\s+"," ");

            if (dashboardPanel != null) {
                dashboardPanel.loadMatchHistory(playerName);
            }

            // Show rank above the player's head (uses sharding)
            try {
                if (rankOverlay != null && playerName != null && !playerName.isEmpty())
                {
                    rankOverlay.forceLookupAndDisplay(playerName);
                }
            } catch (Exception ignore) {}

            // Open plugin side panel
            if (clientToolbar != null && navButton != null) {
                SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
            }
        }
        catch (Exception e)
        {
             log.debug("Uncaught exception in handleMenuOptionClicked", e);
        }
    }
}
