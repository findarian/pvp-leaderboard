package com.pvp.leaderboard;

import com.google.inject.Provides;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.game.FightMonitor;
import com.pvp.leaderboard.game.MenuHandler;
import com.pvp.leaderboard.service.ClientIdentityService;
import com.pvp.leaderboard.service.CognitoAuthService;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.ui.DashboardPanel;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import java.awt.image.BufferedImage;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "PvP Leaderboard"
)
public class PvPLeaderboardPlugin extends Plugin
{
	private static final BufferedImage PANEL_ICON = ImageUtil.loadImageResource(PvPLeaderboardPlugin.class, "panel-icon.png");

	@Inject
	private Client client;

	@Inject
	private PvPLeaderboardConfig config;

	@Inject
	private ClientToolbar clientToolbar;

    @Inject
    private SpriteManager spriteManager;

    @Inject
    private PvPDataService pvpDataService;

    @Inject
    private CognitoAuthService cognitoAuthService;
    
    @Inject
    private ClientIdentityService clientIdentityService;

    @Inject
    private MenuHandler menuHandler;
    
    @Inject
    private FightMonitor fightMonitor;

	private DashboardPanel dashboardPanel;
	private NavigationButton navButton;

    public String getClientUniqueId()
    {
        return clientIdentityService.getClientUniqueId();
    }
    
    // Accessor for DashboardPanel to get local player name for debug logs
    public String getLocalPlayerName()
    {
        try { return client != null && client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null; } catch (Exception ignore) { return null; }
    }

	@Override
	protected void startUp() throws Exception
	{
        dashboardPanel = new DashboardPanel(config, this, pvpDataService, cognitoAuthService);
        
        // Initialize identity
        clientIdentityService.loadOrGenerateId();

		navButton = NavigationButton.builder()
			.tooltip("PvP Leaderboard")
			.icon(PANEL_ICON)
			.priority(5)
			.panel(dashboardPanel)
			.build();
		clientToolbar.addNavigation(navButton);
        
        // Init menu handler
        menuHandler.init(dashboardPanel, navButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
		menuHandler.shutdown();
		clientToolbar.removeNavigation(navButton);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        try
        {
            if (event == null) return;
            if (!"PvPLeaderboard".equals(event.getGroup())) return;
            if ("enablePvpLookupMenu".equals(event.getKey()))
            {
                menuHandler.refreshMenuOption();
            }
        }
        catch (Exception e)
        {
            log.error("Uncaught exception in onConfigChanged", e);
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        menuHandler.handleMenuOptionClicked(event);
    }

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		try
		{
			if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
			{
				// Default lookup to local player and populate panel
				try
				{
					if (client.getLocalPlayer() != null && dashboardPanel != null)
					{
						String self = client.getLocalPlayer().getName();
						if (self != null && !self.trim().isEmpty()) {
							dashboardPanel.loadMatchHistory(self);
						}
					}
				}
				catch (Exception ignore) {}
			}
			else if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
			{
				// Fully clear fight state on logout
				fightMonitor.resetFightState();
			}
		}
		catch (Exception e)
		{
			// log.debug("Uncaught exception in onGameStateChanged", e);
		}
	}

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        try
        {
            // Delegate logic to FightMonitor
            fightMonitor.handleGameTick(tick);
        }
        catch (Exception e)
        {
            // log.error("Uncaught exception in onGameTick", e);
        }
    }

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied hitsplatApplied)
	{
        fightMonitor.handleHitsplatApplied(hitsplatApplied);
	}

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath)
	{
        fightMonitor.handleActorDeath(actorDeath);
	}

	@Provides
	PvPLeaderboardConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvPLeaderboardConfig.class);
	}

    public Client getClient()
    {
        return client;
    }
}
