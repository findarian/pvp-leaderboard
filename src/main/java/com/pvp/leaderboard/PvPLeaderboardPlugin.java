package com.pvp.leaderboard;

import com.google.inject.Provides;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.game.FightMonitor;
import com.pvp.leaderboard.game.MenuHandler;
import com.pvp.leaderboard.overlay.BottomNearbyOverlay;
import com.pvp.leaderboard.overlay.RankOverlay;
import com.pvp.leaderboard.overlay.TopNearbyOverlay;
import com.pvp.leaderboard.service.ClientIdentityService;
import com.pvp.leaderboard.service.CognitoAuthService;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.ui.DashboardPanel;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
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

@Slf4j
@PluginDescriptor(
	name = "PvP Leaderboard"
)
public class PvPLeaderboardPlugin extends Plugin
{
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
    private TopNearbyOverlay topNearbyOverlay;

    @Inject
    private BottomNearbyOverlay bottomNearbyOverlay;

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
    private EventBus eventBus;

	// ScoreboardOverlay and NearbyLeaderboardOverlay removed per request

	private DashboardPanel dashboardPanel;
	private NavigationButton navButton;
	private int pendingSelfRankLookupTicks = -1;

    public String getClientUniqueId()
    {
        return clientIdentityService.getClientUniqueId();
    }
    
    // Accessor for DashboardPanel to get local player name for debug logs
    public String getLocalPlayerName()
    {
        try { return client != null && client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null; } catch (Exception ignore) { return null; }
    }
    
    // Helper for overlays to get displayed rank from overlay cache
    public String getDisplayedRankFor(String playerName)
    {
        return rankOverlay != null ? rankOverlay.getCachedRankFor(playerName) : null;
    }

	@Override
	protected void startUp() throws Exception
	{
        // logic for manual OkHttp/Gson fetching removed as we use the injected service now
        dashboardPanel = new DashboardPanel(config, this, pvpDataService, cognitoAuthService);
        
        // Initialize identity
        clientIdentityService.loadOrGenerateId();

        // Menu initialization
        // Note: navButton is created below, so we init menuHandler there
		
        // Use in-game white PvP skull for the sidebar icon (PLAYER_KILLER_SKULL = 439)
        final BufferedImage icon = net.runelite.client.util.ImageUtil.loadImageResource(getClass(), "/util/clue_arrow.png");
        try {
            spriteManager.getSpriteAsync(439, 0, img -> {
                if (img != null && navButton != null) {
                    SwingUtilities.invokeLater(() -> {
                        // Rebuild nav button with new icon because NavigationButton is immutable
                        NavigationButton updated = NavigationButton.builder()
                            .tooltip("PvP Leaderboard")
                            .icon(img)
                            .priority(5)
                            .panel(dashboardPanel)
                            .build();
                        clientToolbar.removeNavigation(navButton);
                        navButton = updated;
                        clientToolbar.addNavigation(navButton);
                        
                        // Update menu handler with new button
                        if (menuHandler != null) menuHandler.updateNavButton(navButton);
                    });
}
            });
        } catch (Exception ignore) {}
		navButton = NavigationButton.builder()
			.tooltip("PvP Leaderboard")
			.icon(icon)
			.priority(5)
			.panel(dashboardPanel)
			.build();

		clientToolbar.addNavigation(navButton);
		overlayManager.add(rankOverlay);
        overlayManager.add(topNearbyOverlay);
        overlayManager.add(bottomNearbyOverlay);
        
        // Register RankOverlay with EventBus to receive PlayerRankEvent
        eventBus.register(rankOverlay);
        
        // Init menu handler
        menuHandler.init(dashboardPanel, rankOverlay, navButton);
        
        // Init fight monitor
        // fightMonitor.init(dashboardPanel, rankOverlay); 
        
		log.debug("PvP Leaderboard started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		menuHandler.shutdown();
		eventBus.unregister(rankOverlay);
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(rankOverlay);
        overlayManager.remove(topNearbyOverlay);
        overlayManager.remove(bottomNearbyOverlay);
		log.info("PvP Leaderboard stopped!");
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

        // Ensure self rank refreshes when the selected bucket changes
        if ("rankBucket".equals(event.getKey()))
        {
            try { if (rankOverlay != null) rankOverlay.scheduleSelfRankRefresh(0L); } catch (Exception ignore) {}
            try {
                if (dashboardPanel != null && client != null && client.getLocalPlayer() != null)
                {
                    // Simplified: just trigger overlay refresh
                    if (rankOverlay != null) rankOverlay.scheduleSelfRankRefresh(0L);
                }
            } catch (Exception ignore) {}
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
                
                // Use tick-based scheduling: wait 10 ticks (approx 6.0s) for initial load/sync
                                        pendingSelfRankLookupTicks = 10;
			}
			catch (Exception ignore) {}
		}
		else if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
        {
			// Fully clear fight state on logout
            log.debug("[Fight] state reset on LOGIN_SCREEN");
			fightMonitor.resetFightState();
        }
		else if (gameStateChanged.getGameState() == GameState.HOPPING || gameStateChanged.getGameState() == GameState.LOADING)
		{
			try {
				if (rankOverlay != null) {
					rankOverlay.resetLookupStateOnWorldHop();
				}
				log.debug("[Fight] scene change: {} (fight preserved)", gameStateChanged.getGameState());
				// Nudge self rank refresh after a short delay so overlay repopulates post-hop
                // Use tick-based scheduling: wait 3 ticks (approx 1.8s)
                pendingSelfRankLookupTicks = 3;
			} catch (Exception ignore) {}
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
            
            // Process pending rank lookups from the overlay queue
            if (rankOverlay != null)
            {
                rankOverlay.processPendingLookups();
                rankOverlay.cleanupOldRequests();
            }
            
            // Handle pending self-rank lookups (3-tick delay logic)
            if (pendingSelfRankLookupTicks > 0)
            {
                pendingSelfRankLookupTicks--;
                if (pendingSelfRankLookupTicks == 0)
                {
                    pendingSelfRankLookupTicks = -1;
                    if (rankOverlay != null)
                    {
                        // Check if local player is ready before scheduling, otherwise retry briefly
                        if (client.getLocalPlayer() != null)
                    {
                        rankOverlay.scheduleSelfRankRefresh(0L);
                            // Also refresh dashboard if needed
                            if (dashboardPanel != null) {
                                String self = client.getLocalPlayer().getName();
                                if (self != null) dashboardPanel.loadMatchHistory(self);
                                    } 
                            log.debug("[Plugin] executing delayed self-rank lookup");
                                }
                                else
                                {
                            // Not ready yet, retry in 5 ticks
                            pendingSelfRankLookupTicks = 5;
                }
            }
                }
            }
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
