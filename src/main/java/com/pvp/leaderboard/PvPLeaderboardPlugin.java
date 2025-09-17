package com.pvp.leaderboard;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.MenuAction;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.game.SpriteManager;
import net.runelite.api.SpriteID;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import net.runelite.client.menus.MenuManager;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

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
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MenuManager menuManager;

	@Inject
	private RankCacheService rankCacheService;

	@Inject
	private RankOverlay rankOverlay;

    @Inject
    private SpriteManager spriteManager;

	// ScoreboardOverlay and NearbyLeaderboardOverlay removed per request

	private DashboardPanel dashboardPanel;
	private NavigationButton navButton;
	private long accountHash;
	private boolean inFight = false;
	private boolean wasInMulti = false;
	private int fightStartSpellbook = -1;
	private int fightEndSpellbook = -1;
	private String opponent = null;
	private String highestRankDefeated = null;
	private String lowestRankLostTo = null;
	private long fightStartTime = 0;
	private long lastCombatTime = 0;
	private MatchResultService matchResultService = new MatchResultService();
	private static final long FIGHT_TIMEOUT_MS = 30000; // 30 seconds

	@Override
	protected void startUp() throws Exception
	{
        dashboardPanel = new DashboardPanel(config, configManager, this);

		// Add right-click player menu item once on startup
		menuManager.addPlayerMenuItem("pvp lookup");
		
        // Use in-game white PvP skull for the sidebar icon (PLAYER_KILLER_SKULL = 439)
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/util/clue_arrow.png");
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
		// Warm caches synchronously
		forcePrewarmRankCaches();
		overlayManager.add(rankOverlay);
		log.info("PvP Leaderboard started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		menuManager.removePlayerMenuItem("pvp lookup");
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(rankOverlay);
		log.info("PvP Leaderboard stopped!");
	}
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        // no-op: we add the menu on startup to avoid duplicates
    }

    // Removed sprite preview tooling per request

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
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
        String playerName = Text.toJagexName(cleaned).replace('\u00A0',' ').trim().replaceAll("\\s+"," ");

        if (dashboardPanel != null) {
            dashboardPanel.lookupPlayerFromRightClick(playerName);
        }

        // Open plugin side panel
        if (clientToolbar != null && navButton != null) {
            SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
        }
    }


	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			accountHash = client.getAccountHash();
			log.info("PvP Leaderboard ready! Account hash: " + accountHash);
			if (config.onlyFetchOnLogin())
			{
				// Clear caches to allow one-time fetch after login
				// RankOverlay will attempt once per player when seen
			}
			// Default lookup to local player and populate panel
			try
			{
				if (client.getLocalPlayer() != null && dashboardPanel != null)
				{
					String self = client.getLocalPlayer().getName();
					dashboardPanel.lookupPlayerFromRightClick(self);
				}
			}
			catch (Exception ignore) {}
		}
	}

	private void forcePrewarmRankCaches()
	{
		String[] buckets = {"overall", "nh", "veng", "multi", "dmm"};
		for (String b : buckets)
		{
			try
			{
				rankCacheService.forceRefreshBucket(b);
			}
			catch (Exception ignore) {}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied hitsplatApplied)
	{
		// Only process Player vs Player combat
		if (hitsplatApplied.getActor() instanceof Player)
		{
			Player player = (Player) hitsplatApplied.getActor();
			Player localPlayer = client.getLocalPlayer();
			
			// Only process if hitsplat is from a player (not NPC)
			if (localPlayer != null && hitsplatApplied.getHitsplat().isMine() && (player == localPlayer || player != localPlayer))
			{
				// For player vs player combat, ensure both source and target are players
				String opponentName = null;
				if (player == localPlayer)
				{
					// Local player took damage, find who dealt it
					opponentName = getPlayerAttacker();
				}
				else
				{
					// Local player dealt damage to another player
					opponentName = player.getName();
				}
				
				// Only proceed if we have a valid player opponent
				if (opponentName != null && isPlayerOpponent(opponentName))
				{
					// Update last combat time
					lastCombatTime = System.currentTimeMillis();
					
					if (!inFight)
					{
						startFight(opponentName);
					}
					
					if (client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1)
					{
						wasInMulti = true;
					}
				}
			}
		}
		
		// Check for fight timeout
		if (inFight && System.currentTimeMillis() - lastCombatTime > FIGHT_TIMEOUT_MS)
		{
			log.info("Fight timed out after 30 seconds of no combat");
			endFightTimeout();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath)
	{
		if (actorDeath.getActor() instanceof Player && inFight)
		{
			Player player = (Player) actorDeath.getActor();
			Player localPlayer = client.getLocalPlayer();
			
			if (player == localPlayer)
			{
				// Find who actually killed the local player
				String actualKiller = findActualKiller();
				if (actualKiller != null)
				{
					opponent = actualKiller;
				}
				// Local player died = loss
				endFight("loss");
			}
			else if (opponent != null && player.getName().equals(opponent))
			{
				// Opponent died = win
				endFight("win");
			}
		}
	}

	private void startFight(String opponentName)
	{
		inFight = true;
		opponent = opponentName;
		wasInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
		fightStartSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
		fightStartTime = System.currentTimeMillis() / 1000;
		lastCombatTime = System.currentTimeMillis();
		log.info("Fight started against: " + opponent + ", Multi: " + wasInMulti + ", Spellbook: " + fightStartSpellbook);
	}

	public String getCurrentOpponent()
	{
		return opponent;
	}

	public String getDisplayedRankFor(String name)
	{
		if (name == null) return null;
		return rankOverlay != null ? rankOverlay.getCachedRankFor(name) : null;
	}

	private void endFight(String result)
	{
		fightEndSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
		long fightEndTime = System.currentTimeMillis() / 1000;
		
		if (opponent != null)
		{
            String bucket = determineBucket();
            String opponentRank = resolvePlayerRank(opponent, bucket);
			double currentMMR = estimateCurrentMMR();
			
            if ("win".equals(result) && opponentRank != null)
			{
				updateHighestRankDefeated(opponentRank);
			}
            else if ("loss".equals(result) && opponentRank != null)
			{
				updateLowestRankLostTo(opponentRank);
			}
			
			// Update tier graph with real-time data
			if (dashboardPanel != null)
			{
				dashboardPanel.updateTierGraphRealTime(bucket, currentMMR);
			}

			// Optionally show fight rank box overlay via existing rankOverlay drawing (toggle from config)
			// The rankOverlay renders icons and can be extended later; for now, rely on icon above name.
			
            // Submit match result to API and optimistically update UI on success
            submitMatchResult(result, fightEndTime);
            try {
                if (dashboardPanel != null) {
                    dashboardPanel.updateAdditionalStatsFromPlugin("win".equals(result) ? opponentRank : null,
                        "loss".equals(result) ? opponentRank : null);
                }
            } catch (Exception ignore) {}
		}
		
		log.info("Fight ended with result: " + result + ", Multi during fight: " + wasInMulti + ", Start spellbook: " + fightStartSpellbook + ", End spellbook: " + fightEndSpellbook);
		
		resetFightState();
	}
	
	private void endFightTimeout()
	{
		log.info("Fight timed out - no result submitted");
		resetFightState();
	}
	
	private void resetFightState()
	{
		inFight = false;
		wasInMulti = false;
		fightStartSpellbook = -1;
		fightEndSpellbook = -1;
		opponent = null;
		fightStartTime = 0;
		lastCombatTime = 0;
	}

	private String getPlayerAttacker()
	{
		// Find a player who is attacking the local player
		for (Player player : client.getPlayers())
		{
			if (player != client.getLocalPlayer() && player.getInteracting() == client.getLocalPlayer())
			{
				return player.getName();
			}
		}
		return null;
	}
	
	private String findActualKiller()
	{
		// Find the player who is currently attacking the local player at time of death
		Player localPlayer = client.getLocalPlayer();
		for (Player player : client.getPlayers())
		{
			if (player != localPlayer && player.getInteracting() == localPlayer)
			{
				return player.getName();
			}
		}
		return null;
	}

	private String getPlayerRank(String playerName)
	{
		// Simplified rank estimation based on combat level or other factors
		// In a real implementation, this would query the leaderboard API
		return "Bronze 3"; // Placeholder
	}

    private String resolvePlayerRank(String playerName, String bucket)
    {
        try
        {
            // Use shard number to compute tier/division text when available
            int rankIndex = -1;
            if (dashboardPanel != null)
            {
                rankIndex = dashboardPanel.getRankNumberFromLeaderboard(playerName, bucket);
            }
            if (rankIndex >= 0)
            {
                // Map index to tier/division roughly by thresholds used in DashboardPanel
                String[] tiers = {"Bronze 3","Bronze 2","Bronze 1",
                    "Iron 3","Iron 2","Iron 1",
                    "Steel 3","Steel 2","Steel 1",
                    "Black 3","Black 2","Black 1",
                    "Mithril 3","Mithril 2","Mithril 1",
                    "Adamant 3","Adamant 2","Adamant 1",
                    "Rune 3","Rune 2","Rune 1",
                    "Dragon 3","Dragon 2","Dragon 1","3rd Age"};
                if (rankIndex >= 0 && rankIndex < tiers.length)
                {
                    return tiers[rankIndex];
                }
            }
        }
        catch (Exception ignore) {}
        return null;
    }
	
	private void updateHighestRankDefeated(String rank)
	{
		if (highestRankDefeated == null || isHigherRank(rank, highestRankDefeated))
		{
			highestRankDefeated = rank;
			log.info("New highest rank defeated: " + rank);
			if (dashboardPanel != null)
			{
				dashboardPanel.updateAdditionalStatsFromPlugin(highestRankDefeated, lowestRankLostTo);
			}
		}
	}
	
	private void updateLowestRankLostTo(String rank)
	{
		if (lowestRankLostTo == null || isLowerRank(rank, lowestRankLostTo))
		{
			lowestRankLostTo = rank;
			log.info("New lowest rank lost to: " + rank);
			if (dashboardPanel != null)
			{
				dashboardPanel.updateAdditionalStatsFromPlugin(highestRankDefeated, lowestRankLostTo);
			}
		}
	}
	
	private boolean isHigherRank(String rank1, String rank2)
	{
		return getRankOrder(rank1) > getRankOrder(rank2);
	}
	
	private boolean isLowerRank(String rank1, String rank2)
	{
		return getRankOrder(rank1) < getRankOrder(rank2);
	}
	
	private int getRankOrder(String rank)
	{
		String[] parts = rank.split(" ");
		String baseName = parts[0];
		int division = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
		
		int baseOrder;
		switch (baseName) {
			case "Bronze":
				baseOrder = 0;
				break;
			case "Iron":
				baseOrder = 1;
				break;
			case "Steel":
				baseOrder = 2;
				break;
			case "Black":
				baseOrder = 3;
				break;
			case "Mithril":
				baseOrder = 4;
				break;
			case "Adamant":
				baseOrder = 5;
				break;
			case "Rune":
				baseOrder = 6;
				break;
			case "Dragon":
				baseOrder = 7;
				break;
			case "3rd Age":
				baseOrder = 8;
				break;
			default:
				baseOrder = -1;
				break;
		}
		
		return baseOrder * 10 + (4 - division); // Higher division = higher order
	}
	
	public long getAccountHash()
	{
		return accountHash;
	}
	
	public String getHighestRankDefeated()
	{
		return highestRankDefeated;
	}
	
	public String getLowestRankLostTo()
	{
		return lowestRankLostTo;
	}
	
	private String determineBucket()
	{
		// Determine bucket based on spellbook and multi area
		if (wasInMulti)
		{
			return "multi";
		}
		
		if (fightStartSpellbook == 1) // Lunar spellbook
		{
			return "veng";
		}
		
		return "nh"; // Default to NH
	}
	
	private double estimateCurrentMMR()
	{
		// Simplified MMR estimation - in real implementation would track actual MMR
		return 1000.0; // Placeholder
	}
	
	private void submitMatchResult(String result, long fightEndTime)
	{
		String playerId = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
		int world = client.getWorld();
		String startSpellbook = getSpellbookName(fightStartSpellbook);
		String endSpellbook = getSpellbookName(fightEndSpellbook);
		String idToken = dashboardPanel != null ? dashboardPanel.getIdToken() : null;
		
		matchResultService.submitMatchResult(
			playerId,
			opponent,
			result,
			world,
			fightStartTime,
			fightEndTime,
			startSpellbook,
			endSpellbook,
			wasInMulti,
			accountHash,
			idToken
		).thenAccept(success -> {
			if (success) {
				log.info("Match result submitted successfully");
			} else {
				log.warn("Failed to submit match result");
			}
		}).exceptionally(ex -> {
			log.error("Error submitting match result", ex);
			return null;
		});
	}
	
	private boolean isPlayerOpponent(String name)
	{
		if (name == null || "Unknown".equals(name)) return false;
		
		// Check if the name exists in the players list
		for (Player player : client.getPlayers())
		{
			if (player.getName() != null && player.getName().equals(name))
			{
				return true;
			}
		}
		return false;
	}
	
	private String getSpellbookName(int spellbook)
	{
		switch (spellbook) {
			case 0:
				return "Standard";
			case 1:
				return "Ancient";
			case 2:
				return "Lunar";
			case 3:
				return "Arceuus";
			default:
				return "Unknown";
		}
	}

	@Provides
	PvPLeaderboardConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvPLeaderboardConfig.class);
	}

	@Provides
	RankCacheService provideRankCacheService()
	{
		return new RankCacheService();
	}
}
