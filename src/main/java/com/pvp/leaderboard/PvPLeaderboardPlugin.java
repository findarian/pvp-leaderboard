package com.pvp.leaderboard;

import com.google.inject.Provides;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.game.FightMonitor;
import com.pvp.leaderboard.game.MenuHandler;
import com.pvp.leaderboard.overlay.RankOverlay;
import com.pvp.leaderboard.service.ClientIdentityService;
import com.pvp.leaderboard.service.CognitoAuthService;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.service.WhitelistService;
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
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
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
	private OverlayManager overlayManager;

	@Inject
	private RankOverlay rankOverlay;

	@Inject
	private EventBus eventBus;

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

	@Inject
	private WhitelistService whitelistService;

	private DashboardPanel dashboardPanel;
	private NavigationButton navButton;
	private int pendingSelfRankLookupTicks = -1;
	private boolean pendingHeartbeatStart = false;

	public String getClientUniqueId()
	{
		return clientIdentityService.getClientUniqueId();
	}

	// Accessor for DashboardPanel to get local player name for debug logs
	public String getLocalPlayerName()
	{
		if (client == null)
		{
			return null;
		}

		try
		{
			var localPlayer = client.getLocalPlayer();
			if (localPlayer == null)
			{
				return null;
			}
			return localPlayer.getName();
		}
		catch (Exception e)
		{
			log.debug("Failed to get local player name", e);
			return null;
		}
	}

	// Helper for overlays to get displayed rank from overlay cache
	public String getDisplayedRankFor(String playerName)
	{
		return rankOverlay != null ? rankOverlay.getCachedRankFor(playerName) : null;
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

		// Register overlay
		overlayManager.add(rankOverlay);

		// Register RankOverlay with EventBus to receive PlayerRankEvent
		eventBus.register(rankOverlay);

		// Init menu handler with RankOverlay
		menuHandler.init(dashboardPanel, navButton);

		// Init fight monitor with RankOverlay for MMR notifications
		fightMonitor.init(rankOverlay);

		log.debug("PvP Leaderboard started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		menuHandler.shutdown();
		eventBus.unregister(rankOverlay);
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(rankOverlay);
		log.debug("PvP Leaderboard stopped!");
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
				return;
			}

			// Ensure self rank refreshes when bucket or display mode changes
			if ("rankBucket".equals(event.getKey()) || "rankDisplayMode".equals(event.getKey()))
			{
				if (rankOverlay != null)
				{
					rankOverlay.scheduleSelfRankRefresh(0L);
				}
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
				// Use tick-based scheduling: wait 10 ticks (approx 6.0s) for player to fully load
				// This handles the delay between LOGGED_IN state and player actually being ready
				pendingSelfRankLookupTicks = 10;
				pendingHeartbeatStart = true;
				log.debug("[Plugin] LOGGED_IN - scheduling delayed init in 10 ticks");
			}
			else if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
			{
				// Fully clear fight state on logout
				fightMonitor.resetFightState();
				// Stop heartbeats
				whitelistService.onLogout();
				pendingHeartbeatStart = false;
			}
			else if (gameStateChanged.getGameState() == GameState.HOPPING || gameStateChanged.getGameState() == GameState.LOADING)
			{
				try
				{
					if (rankOverlay != null)
					{
						rankOverlay.resetLookupStateOnWorldHop();
					}
					// Schedule self rank overlay refresh only - don't refresh panel
					pendingSelfRankLookupTicks = 8;
				}
				catch (Exception ignore) {}
			}
		}
		catch (Exception e)
		{
			log.debug("Uncaught exception in onGameStateChanged", e);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		try
		{
			// Delegate logic to FightMonitor
			fightMonitor.handleGameTick(tick);

			// Handle pending init after login (tick-based delay for player to be ready)
			if (pendingSelfRankLookupTicks > 0)
			{
				pendingSelfRankLookupTicks--;
				if (pendingSelfRankLookupTicks == 0)
				{
					pendingSelfRankLookupTicks = -1;
					
					if (client.getLocalPlayer() != null)
					{
						String self = client.getLocalPlayer().getName();
						
						if (self != null && !self.trim().isEmpty())
						{
							log.debug("[Plugin] Init complete for: {}", self);
							
							// Load dashboard data
							if (dashboardPanel != null)
							{
								dashboardPanel.loadMatchHistoryIfNotViewing(self);
							}
							
							// Schedule self rank refresh for overlay
							if (rankOverlay != null)
							{
								rankOverlay.scheduleSelfRankRefresh(0L);
							}
							
							// Start heartbeat (fires now, then every 5 mins)
							if (pendingHeartbeatStart)
							{
								log.debug("[Plugin] Starting heartbeat for: {}", self);
								whitelistService.onLogin(self);
								pendingHeartbeatStart = false;
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Uncaught exception in onGameTick", e);
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
