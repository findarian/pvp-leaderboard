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
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.game.SpriteManager;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import net.runelite.client.menus.MenuManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@PluginDescriptor(
	name = "PvP Leaderboard"
)
@SuppressWarnings("deprecation")
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
	private RankOverlay rankOverlay;

    @Inject
    private TopNearbyOverlay topNearbyOverlay;

    @Inject
    private BottomNearbyOverlay bottomNearbyOverlay;

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
    private MatchResultService matchResultService = new MatchResultService();
    private ScheduledExecutorService fightScheduler;
    private volatile long selfDeathMs = 0L;
    private volatile long opponentDeathMs = 0L;
    private volatile boolean fightFinalized = false;
private volatile boolean shardReady = false;

	@Override
	protected void startUp() throws Exception
	{
        shardReady = false;
        dashboardPanel = new DashboardPanel(config, configManager, this);

        // Initialize scheduler for fight event checks (double-KO detection)
        try {
            fightScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pvp-fight-events");
                t.setDaemon(true);
                return t;
            });
        } catch (Exception ignore) {}

        // Add right-click player menu item based on config (default ON)
        try {
            if (config.enablePvpLookupMenu()) {
                menuManager.addPlayerMenuItem("pvp lookup");
            } else {
                menuManager.removePlayerMenuItem("pvp lookup");
            }
        } catch (Exception ignore) {}
		
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
        // Prewarming removed per request; overlay/shard fetch will occur on demand
        shardReady = true; // allow on-demand lookups immediately
		overlayManager.add(rankOverlay);
        overlayManager.add(topNearbyOverlay);
        overlayManager.add(bottomNearbyOverlay);
		log.info("PvP Leaderboard started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		menuManager.removePlayerMenuItem("pvp lookup");
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(rankOverlay);
        overlayManager.remove(topNearbyOverlay);
        overlayManager.remove(bottomNearbyOverlay);
        try { if (fightScheduler != null) { fightScheduler.shutdownNow(); } } catch (Exception ignore) {}
		log.info("PvP Leaderboard stopped!");
	}
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        // no-op: menu item managed on startup and config change
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event == null) return;
        if (!"PvPLeaderboard".equals(event.getGroup())) return;
        if ("enablePvpLookupMenu".equals(event.getKey()))
        {
            try {
                if (config.enablePvpLookupMenu()) {
                    menuManager.addPlayerMenuItem("pvp lookup");
                } else {
                    menuManager.removePlayerMenuItem("pvp lookup");
                }
            } catch (Exception ignore) {}
            return;
        }

        // Ensure self rank refreshes when the selected bucket changes
        if ("rankBucket".equals(event.getKey()))
        {
            try { if (rankOverlay != null) rankOverlay.scheduleSelfRankRefresh(0L); } catch (Exception ignore) {}
            try {
                if (dashboardPanel != null && client != null && client.getLocalPlayer() != null)
                {
                    final String selfName = client.getLocalPlayer().getName();
                    final String currentBucket = bucketKey(config.rankBucket());
                    // Fast-follow: push API-derived tier into overlay immediately
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try { return dashboardPanel.fetchSelfTierFromApi(selfName, currentBucket); } catch (Exception e) { return null; }
                    }).thenAccept(tier -> {
                        if (tier != null && !tier.isEmpty() && rankOverlay != null) {
                            rankOverlay.setRankFromApi(selfName, tier);
                        }
                    });
                    // Update sidebar rank numbers for the new bucket only
                    try { dashboardPanel.updateAllRankNumbers(selfName); } catch (Exception ignore) {}
                    // Update Additional Stats bucket selector (graph/table)
                    try { dashboardPanel.setStatsBucketFromConfig(config.rankBucket()); } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
        }
    }

    // Removed sprite preview tooling per request

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
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
            dashboardPanel.lookupPlayerFromRightClick(playerName);
        }

        // Show rank above the player's head via API (fallback path, bypass sharding for this user)
        try {
            if (rankOverlay != null && dashboardPanel != null && playerName != null && !playerName.isEmpty())
            {
                String bucket = bucketKey(config.rankBucket());
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return dashboardPanel.fetchTierFromApi(playerName, bucket); } catch (Exception e) { return null; }
                }).thenAccept(tier -> {
                    if (tier != null && !tier.isEmpty()) {
                        rankOverlay.setRankFromApi(playerName, tier);
                    }
                });
            }
        } catch (Exception ignore) {}

        // Force overlay to fetch and display the player's rank via shards
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


	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			accountHash = client.getAccountHash();
			log.info("PvP Leaderboard ready! Account hash: " + accountHash);
            shardReady = true; // on-demand shard fetch; no prewarm
			// Default lookup to local player and populate panel
			try
			{
				if (client.getLocalPlayer() != null && dashboardPanel != null)
				{
                    String self = client.getLocalPlayer().getName();
					dashboardPanel.lookupPlayerFromRightClick(self);
                    // Preload account hash linkage for self so overall lookups use account shard immediately
                    try { dashboardPanel.preloadSelfRankNumbers(self); } catch (Exception ignore) {}
                    // Ensure overlay fetches self rank immediately for the currently selected bucket
                    try { if (rankOverlay != null) rankOverlay.scheduleSelfRankRefresh(0L); } catch (Exception ignore) {}
				}
			}
			catch (Exception ignore) {}
		}
        else if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			// If combat was in progress, immediately treat as tie on logout
			try {
				if (inFight) {
					endFightTimeout();
				}
			} catch (Exception ignore) {}
		}
		else if (gameStateChanged.getGameState() == GameState.HOPPING || gameStateChanged.getGameState() == GameState.LOADING)
		{
			try {
				if (rankOverlay != null) {
					rankOverlay.resetLookupStateOnWorldHop();
				}
			} catch (Exception ignore) {}
		}
	}

    // Prewarm helpers removed

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied hitsplatApplied)
	{
		// Only process Player vs Player combat
		if (hitsplatApplied.getActor() instanceof Player)
		{
			Player player = (Player) hitsplatApplied.getActor();
			Player localPlayer = client.getLocalPlayer();
			
            // Process both directions: when we hit another player or get hit by another player
            if (localPlayer != null)
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
                    // Combat activity marker (no-op)
					
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
		
		// Fight timeout is handled by scheduled checker
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
                // Local player died
                selfDeathMs = System.currentTimeMillis();
                // Find who actually killed the local player
                String actualKiller = findActualKiller();
                if (actualKiller != null)
                {
                    opponent = actualKiller;
                }
                // Immediate result unless double-KO window applies
                if (!fightFinalized)
                {
                    if (opponentDeathMs > 0L && Math.abs(selfDeathMs - opponentDeathMs) <= 1500L)
                    {
                        fightFinalized = true;
                        endFight("tie");
                    }
                    else
                    {
                        fightFinalized = true;
                        endFight("loss");
                    }
                }
            }
            else
            {
                String name = player.getName();
                if (name != null)
                {
                    // If we didn't have the opponent set, assume this is the opponent we fought
                    if (opponent == null)
                    {
                        opponent = name;
                    }
                    if (name.equals(opponent))
                    {
                        // Opponent died
                        opponentDeathMs = System.currentTimeMillis();
                        if (!fightFinalized)
                        {
                            if (selfDeathMs > 0L && Math.abs(selfDeathMs - opponentDeathMs) <= 1500L)
                            {
                                fightFinalized = true;
                                endFight("tie");
                            }
                            else
                            {
                                fightFinalized = true;
                                endFight("win");
                            }
                        }
                    }
                }
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

    public void refreshSelfRankNow()
    {
        try
        {
            if (rankOverlay != null)
            {
                rankOverlay.scheduleSelfRankRefresh(0L);
            }
            if (dashboardPanel != null && client != null && client.getLocalPlayer() != null)
            {
                final String selfName = client.getLocalPlayer().getName();
                final String currentBucket = bucketKey(config.rankBucket());
                // Kick API fast-follow to update overlay immediately
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return dashboardPanel.fetchSelfTierFromApi(selfName, currentBucket); } catch (Exception e) { return null; }
                }).thenAccept(tier -> {
                    if (tier != null && !tier.isEmpty() && rankOverlay != null) {
                        rankOverlay.setRankFromApi(selfName, tier);
                    }
                });
                try { dashboardPanel.preloadSelfRankNumbers(selfName); } catch (Exception ignore) {}
            }
        }
        catch (Exception ignore) {}
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
            // After submission, immediately refresh self rank and push API tier as a fast-follow
            try {
                if (rankOverlay != null) {
                    rankOverlay.scheduleSelfRankRefresh(0L);
                }
            } catch (Exception ignore) {}
            try {
                if (dashboardPanel != null && client != null && client.getLocalPlayer() != null)
                {
                    final String selfName = client.getLocalPlayer().getName();
                    final String currentBucket = bucketKey(config.rankBucket());
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try { return dashboardPanel.fetchSelfTierFromApi(selfName, currentBucket); } catch (Exception e) { return null; }
                    }).thenAccept(tier -> {
                        if (tier != null && !tier.isEmpty() && rankOverlay != null) {
                            rankOverlay.setRankFromApi(selfName, tier);
                        }
                    });
                    // Also refresh side panel rank numbers/account linkage
                    try { dashboardPanel.preloadSelfRankNumbers(selfName); } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
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
        if (!inFight) return;
        // Do not auto-submit ties on inactivity per latest instructions; just reset.
        log.info("Fight timed out - no result submitted");
        resetFightState();
    }

	private void scheduleDoubleKoCheck(String fallbackResult)
	{
		try
		{
			if (fightScheduler == null) return;
			fightScheduler.schedule(() -> {
				try
				{
					if (!inFight || fightFinalized) return;
					long a = selfDeathMs;
					long b = opponentDeathMs;
					if (a > 0L && b > 0L && Math.abs(a - b) <= 1500L)
					{
						fightFinalized = true;
						endFight("tie");
						return;
					}
					if (fallbackResult != null && !fightFinalized)
					{
						fightFinalized = true;
						endFight(fallbackResult);
					}
				}
				catch (Exception ignore) {}
			}, 600L, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		catch (Exception ignore) {}
	}
	
	private void resetFightState()
	{
		inFight = false;
		wasInMulti = false;
		fightStartSpellbook = -1;
		fightEndSpellbook = -1;
		opponent = null;
        fightStartTime = 0;
	}

	private String getPlayerAttacker()
	{
		// Find a player who is attacking the local player
        java.util.List<Player> players = client.getPlayers();
        if (players == null) return null;
        for (Player player : players)
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
        java.util.List<Player> players = client.getPlayers();
        if (players == null) return null;
        for (Player player : players)
		{
			if (player != localPlayer && player.getInteracting() == localPlayer)
			{
				return player.getName();
			}
		}
		return null;
	}

    

    public String resolvePlayerRank(String playerName, String bucket)
    {
        try
        {
            boolean isSelf = false;
            try {
                isSelf = client != null && client.getLocalPlayer() != null && playerName != null && playerName.equals(client.getLocalPlayer().getName());
            } catch (Exception ignore) {}
            int rankIndex = -1;
            if (dashboardPanel != null)
            {
                rankIndex = isSelf ? dashboardPanel.getRankNumberFromLeaderboard(playerName, bucket)
                                    : dashboardPanel.getRankNumberByName(playerName, bucket);
            }
            if (rankIndex > 0)
            {
                String tier = null;
                try {
                    tier = dashboardPanel != null
                        ? (isSelf ? dashboardPanel.getTierLabelFromLeaderboard(playerName, bucket)
                                  : dashboardPanel.getTierLabelByName(playerName, bucket))
                        : null;
                } catch (Exception ignore) {}
                if (tier != null && !tier.isEmpty()) return tier;
                return "Rank " + rankIndex;
            }

            // Fallback for self only when shard misses: derive tier from API
            try
            {
                if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
                {
                    String self = client.getLocalPlayer().getName();
                    if (self != null && self.equals(playerName) && dashboardPanel != null)
                    {
                        String tier = dashboardPanel.fetchSelfTierFromApi(playerName, bucket);
                        if (tier != null && !tier.isEmpty()) return tier;
                    }
                }
            }
            catch (Exception ignore) {}
        }
        catch (Exception ignore) {}
        return null;
    }

    public int getWorldRankIndex(String playerName, String bucket)
    {
        try
        {
            boolean isSelf = false;
            try {
                isSelf = client != null && client.getLocalPlayer() != null && playerName != null && playerName.equals(client.getLocalPlayer().getName());
            } catch (Exception ignore) {}
            if (dashboardPanel == null) return -1;
            return isSelf ? dashboardPanel.getRankNumberFromLeaderboard(playerName, bucket)
                          : dashboardPanel.getRankNumberByName(playerName, bucket);
        }
        catch (Exception ignore) { return -1; }
    }

    private static String bucketKey(PvPLeaderboardConfig.RankBucket bucket)
    {
        if (bucket == null) return "overall";
        switch (bucket)
        {
            case OVERALL: return "overall";
            case NH: return "nh";
            case VENG: return "veng";
            case MULTI: return "multi";
            case DMM: return "dmm";
            default: return "overall";
        }
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
        java.util.List<Player> players = client.getPlayers();
        if (players == null) return false;
        for (Player player : players)
		{
            String pn = player.getName();
            if (pn != null && pn.equals(name))
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

    public boolean isShardReady()
    {
        return shardReady;
    }
}
