package com.pvp.leaderboard;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.Player;
import net.runelite.api.HitsplatID;
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
import net.runelite.client.callback.ClientThread;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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

    @Inject
    private ClientThread clientThread;

	// ScoreboardOverlay and NearbyLeaderboardOverlay removed per request

	private DashboardPanel dashboardPanel;
	private NavigationButton navButton;
	private long accountHash;
	private boolean inFight = false;
	private boolean wasInMulti = false;
	private int fightStartSpellbook = -1;
	private int fightEndSpellbook = -1;
    private String opponent = null;
    private volatile String lastEngagedOpponentName = null; // legacy fallback (may be normalized)
    private volatile String lastExactOpponentName = null;   // exact name as reported by client
	private String highestRankDefeated = null;
	private String lowestRankLostTo = null;
	private long fightStartTime = 0;
    @Inject
    private MatchResultService matchResultService;
    @Inject
    private ScheduledExecutorService scheduler;
    // Tracks multiple simultaneous fights (per-opponent) with 10s inactivity expiry
    private final java.util.concurrent.ConcurrentHashMap<String, FightEntry> activeFights = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledFuture<?> fightGcTask;
    private volatile long selfDeathMs = 0L;
    private volatile long opponentDeathMs = 0L;
    private volatile boolean fightFinalized = false;
private volatile boolean shardReady = false;
    // Guard to prevent immediate false restarts caused by post-death hitsplats
private volatile long suppressFightStartUntilMs = 0L;
    // Per-opponent suppression after ending a fight with them; allows multi-combat with others (using ticks)
    private final ConcurrentHashMap<String, Integer> perOpponentSuppressUntilTicks = new ConcurrentHashMap<>();
    // Legacy field removed
    private final ConcurrentHashMap<String, Long> perOpponentSuppressUntilMs = new ConcurrentHashMap<>();
    // Damage accounting since last out-of-combat window
    private final java.util.concurrent.ConcurrentHashMap<String, Long> damageFromOpponent = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int OUT_OF_COMBAT_TICKS = 16; // 16 ticks ≈ 9.6s
    private volatile int lastCombatActivityTick = 0;
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> shardPresence = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> apiFallbackActive = new java.util.concurrent.ConcurrentHashMap<>();
	// Damage dealt by the local player to each opponent during the current combat window
	private final java.util.concurrent.ConcurrentHashMap<String, Long> damageToOpponent = new java.util.concurrent.ConcurrentHashMap<>();
	private static final boolean DEBUG_HIT_SPLAT_LOGS = false;
    private String clientUniqueId = null;

    public String getClientUniqueId()
    {
        return clientUniqueId;
    }
    
    // Accessor for DashboardPanel to get local player name for debug logs
    public String getLocalPlayerName()
    {
        try { return client != null && client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null; } catch (Exception ignore) { return null; }
    }

	@Override
	protected void startUp() throws Exception
	{
        shardReady = false;
        // Inject OkHttp/Gson via RuneLite injector
        okhttp3.OkHttpClient ok = net.runelite.client.RuneLite.getInjector().getInstance(okhttp3.OkHttpClient.class);
        com.google.gson.Gson gs = net.runelite.client.RuneLite.getInjector().getInstance(com.google.gson.Gson.class);
        dashboardPanel = new DashboardPanel(config, configManager, this, ok, gs);

        // Ensure unique client ID exists (Global -> Profile)
        String globalId = null;
        java.io.File globalFile = new java.io.File(System.getProperty("user.home"), ".runelite/pvp-leaderboard.id");
        try {
            if (globalFile.exists()) {
                java.nio.file.Path path = globalFile.toPath();
                byte[] bytes = java.nio.file.Files.readAllBytes(path);
                globalId = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignore) {}

        // Logic: Prefer global file. If missing, generate new.
        String finalId = globalId;
        if (finalId == null || finalId.isEmpty()) {
            finalId = java.util.UUID.randomUUID().toString();
            // Save back to global file
            try {
                // Ensure parent directory exists
                java.io.File parent = globalFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                java.nio.file.Files.write(globalFile.toPath(), finalId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignore) {}
        }
        
        // Sync to profile
        String profileId = configManager.getConfiguration("PvPLeaderboard", "clientUniqueId");
        if (!finalId.equals(profileId)) {
            configManager.setConfiguration("PvPLeaderboard", "clientUniqueId", finalId);
        }
        
        this.clientUniqueId = finalId;

        // GC task is now handled in onGameTick; no separate scheduler required
        // fightGcTask logic moved to onGameTick with 33-tick interval
        
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
		log.debug("PvP Leaderboard started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		menuManager.removePlayerMenuItem("pvp lookup");
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(rankOverlay);
        overlayManager.remove(topNearbyOverlay);
        overlayManager.remove(bottomNearbyOverlay);
        try { if (fightGcTask != null) { fightGcTask.cancel(true); } } catch (Exception ignore) {}
        // No long-lived OkHttp calls created by this class directly; DashboardPanel uses enqueue
        // If needed, we could tag calls and cancel here.
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
        try
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
                    }, scheduler).thenAccept(tier -> {
                        if (tier != null && !tier.isEmpty() && rankOverlay != null) {
                            rankOverlay.setRankFromApi(selfName, tier);
                            // Mirror pvp lookup behavior: also force a shard lookup (won't overwrite during override window)
                            try { rankOverlay.forceLookupAndDisplay(selfName); } catch (Exception ignore) {}
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
        catch (Exception e)
        {
            log.error("Uncaught exception in onConfigChanged", e);
        }
    }

    // Removed sprite preview tooling per request

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
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
            dashboardPanel.lookupPlayerFromRightClick(playerName);
        }

        // Show rank above the player's head via API (fallback path, bypass sharding for this user)
        try {
            if (rankOverlay != null && dashboardPanel != null && playerName != null && !playerName.isEmpty())
            {
                String bucket = bucketKey(config.rankBucket());
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return dashboardPanel.fetchTierFromApi(playerName, bucket); } catch (Exception e) { return null; }
                }, scheduler).thenAccept(tier -> {
                    if (tier != null && !tier.isEmpty()) {
                        // Force override from API immediately
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
        catch (Exception e)
        {
            log.error("Uncaught exception in onMenuOptionClicked", e);
        }
    }


    // State for pending self-rank lookups using tick timing instead of system clock
    private int pendingSelfRankLookupTicks = -1;

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		try
		{
			if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
			{
			accountHash = client.getAccountHash();
			log.debug("PvP Leaderboard ready! Account hash: {}", accountHash);
            shardReady = true; // on-demand shard fetch; no prewarm
			// Default lookup to local player and populate panel
			try
			{
				if (client.getLocalPlayer() != null && dashboardPanel != null)
				{
					String self = client.getLocalPlayer().getName();
					// try { log.debug("[Login] Logged in as '{}' world={}", (self != null ? self : "Unknown"), client.getWorld()); } catch (Exception ignore) {}
					if (self != null && !self.trim().isEmpty()) {
						dashboardPanel.lookupPlayerFromRightClick(self);
					}
                    // Preload account hash linkage for self so overall lookups use account shard immediately
                    try { dashboardPanel.preloadSelfRankNumbers(self); } catch (Exception ignore) {}
					// Schedule a slight delay to avoid null-name timing on rapid hops
                    // Use tick-based scheduling: wait 10 ticks (approx 6.0s) for login
					pendingSelfRankLookupTicks = 10;
				}
				else
				{
					// LocalPlayer not ready yet; defer initialization slightly
					try {
						scheduler.schedule(() -> {
							try {
								if (client != null && client.getLocalPlayer() != null) {
									String selfLater = client.getLocalPlayer().getName();
									if (selfLater != null && !selfLater.trim().isEmpty()) {
										try { if (dashboardPanel != null) dashboardPanel.lookupPlayerFromRightClick(selfLater); } catch (Exception ignore2) {}
                                        // Defer logic triggers tick countdown
                                        pendingSelfRankLookupTicks = 10;
									}
								}
							} catch (Exception ignore2) {}
						}, 500L, java.util.concurrent.TimeUnit.MILLISECONDS);
					} catch (Exception ignore) {}
				}
			}
			catch (Exception ignore) {}
		}
		else if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
        {
			// Fully clear fight state on logout
            try { log.debug("[Fight] state reset on LOGIN_SCREEN"); } catch (Exception ignore) {}
			resetFightState();
        }
		else if (gameStateChanged.getGameState() == GameState.HOPPING || gameStateChanged.getGameState() == GameState.LOADING)
		{
			try {
				if (rankOverlay != null) {
					rankOverlay.resetLookupStateOnWorldHop();
				}
				try { log.debug("[Fight] scene change: {} (fight preserved)", gameStateChanged.getGameState()); } catch (Exception ignore) {}
				// Nudge self rank refresh after a short delay so overlay repopulates post-hop
                // Use tick-based scheduling: wait 3 ticks (approx 1.8s)
                pendingSelfRankLookupTicks = 3;
			} catch (Exception ignore) {}
		}
		}
		catch (Exception e)
		{
			log.error("Uncaught exception in onGameStateChanged", e);
		}
	}

    // Prewarm helpers removed

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        try
        {
        // Handle pending self-rank lookups (3-tick delay logic)
        if (pendingSelfRankLookupTicks > 0)
        {
            pendingSelfRankLookupTicks--;
            if (pendingSelfRankLookupTicks == 0)
            {
                pendingSelfRankLookupTicks = -1;
                try { if (rankOverlay != null) rankOverlay.scheduleSelfRankRefresh(0L); } catch (Exception ignore) {}
                try { log.debug("[Plugin] executing delayed self-rank lookup (10-tick delay)"); } catch (Exception ignore) {}
            }
        }
        
        // Handle pending finalizations (3-tick delay)
        if (pendingFinalizeTicks > 0)
        {
            pendingFinalizeTicks--;
            if (pendingFinalizeTicks == 0)
            {
                pendingFinalizeTicks = -1;
                try
                {
                    String fallbackResult = pendingFinalizeFallback;
                    try { log.info("[Fight] finalize task fired (tick); fightFinalized={} a={} b={} inFight={}", fightFinalized, selfDeathMs, opponentDeathMs, inFight); } catch (Exception ignore) {}
                    if (fightFinalized) { try { log.info("[Fight] finalize task exit: already finalized"); } catch (Exception ignore) {} }
                    else
                    {
                        long a = selfDeathMs;
                        long b = opponentDeathMs;
                        if (a > 0L && b > 0L && Math.abs(a - b) <= 1500L)
                        {
                            fightFinalized = true;
                            try { log.info("[Fight] finalize tie (Δ={} ms)", Math.abs(a - b)); } catch (Exception ignore) {}
                            Runnable fin = () -> { try { endFight("tie"); } catch (Exception e) { try { log.error("[Fight] endFight(tie) error", e); } catch (Exception ignore) {} } };
                            try { if (clientThread != null) clientThread.invokeLater(fin); else fin.run(); } catch (Exception e) { fin.run(); }
                        }
                        else if (fallbackResult != null)
                        {
                            fightFinalized = true;
                            try { log.info("[Fight] finalize {} after wait; a={}, b={}", fallbackResult, a, b); } catch (Exception ignore) {}
                            final String outcome = fallbackResult;
                            Runnable fin = () -> {
                                try {
                                    // Guard opponent presence; if lost due to relog, keep last known
                                    if (opponent == null || opponent.isEmpty()) {
                                        try { log.warn("[Fight] opponent missing at finalize; outcome={}, a={}, b={}", outcome, a, b); } catch (Exception ignore) {}
                                    }
                                    
                                    endFight(outcome);
                                } catch (Exception e) {
                                    try { log.error("[Fight] endFight({}) error", outcome, e); } catch (Exception ignore) {}
                                }
                            };
                            try { if (clientThread != null) clientThread.invokeLater(fin); else fin.run(); } catch (Exception e) { fin.run(); }
                        }
                    }
                }
                catch (Exception ignore) {}
            }
        }
        
        // Handle post-match refresh (25 ticks = 15s)
        if (postMatchRefreshTicks > 0)
        {
            postMatchRefreshTicks--;
            if (postMatchRefreshTicks == 0)
            {
                postMatchRefreshTicks = -1;
                final String sName = postMatchRefreshSelf;
                final String oName = postMatchRefreshOpp;
                final String res = postMatchRefreshRes;
                final String oRank = postMatchRefreshOppRank;
                
                // Self refresh
                if (sName != null && !sName.isEmpty())
                {
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            String bucketLater = bucketKey(config.rankBucket());
                            String selfTier = dashboardPanel.fetchSelfTierFromApi(sName, bucketLater);
                            if (selfTier != null && !selfTier.isEmpty() && rankOverlay != null) {
                                rankOverlay.setRankFromApi(sName, selfTier);
                                // Force a shard lookup as in pvp lookup; override prevents clobbering
                                try { rankOverlay.forceLookupAndDisplay(sName); } catch (Exception ignore) {}
                            }
                        } catch (Exception ignore) {}
                    }, scheduler);
                    try { dashboardPanel.preloadSelfRankNumbers(sName); } catch (Exception ignore) {}
                }
                
                // Opponent refresh
                if (oName != null && !oName.isEmpty())
                {
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            String bucketLater = bucketKey(config.rankBucket());
                            String oppTier = dashboardPanel.fetchTierFromApi(oName, bucketLater);
                            if (oppTier != null && !oppTier.isEmpty() && rankOverlay != null) {
                                rankOverlay.setRankFromApi(oName, oppTier);
                                try { rankOverlay.forceLookupAndDisplay(oName); } catch (Exception ignore) {}
                            }
                        } catch (Exception ignore) {}
                    }, scheduler);
                }
                
                try { dashboardPanel.updateAdditionalStatsFromPlugin("win".equals(res) ? oRank : null,
                            "loss".equals(res) ? oRank : null); } catch (Exception ignore) {}
            }
        }
        
        // Handle fight suppression ticks
        if (suppressFightStartTicks > 0)
        {
            suppressFightStartTicks--;
        }
        
        // Handle per-opponent suppression ticks
        if (!perOpponentSuppressUntilTicks.isEmpty())
        {
            java.util.Iterator<java.util.Map.Entry<String, Integer>> it = perOpponentSuppressUntilTicks.entrySet().iterator();
            while (it.hasNext())
            {
                java.util.Map.Entry<String, Integer> e = it.next();
                int val = e.getValue();
                if (val <= 1) it.remove();
                else e.setValue(val - 1);
            }
        }
        
        // Handle GC (every 33 ticks approx 20s)
        gcTicksCounter++;
        if (gcTicksCounter >= 33)
        {
            gcTicksCounter = 0;
            try {
                long now = System.currentTimeMillis();
                for (java.util.Map.Entry<String, FightEntry> e : activeFights.entrySet())
                {
                    FightEntry fe = e.getValue();
                    if (fe == null) continue;
                    // Keep existing 10s logic for now, but check on tick cycle
                    if (!fe.finalized && now - fe.lastActivityMs > 10_000L)
                    {
                        // Expire inactive fights without submission (combat lock ends after 10s)
                        activeFights.remove(e.getKey());
                        try { log.debug("[Fight] expire inactive vs={} lastActivity={}msAgo", e.getKey(), now - fe.lastActivityMs); } catch (Exception ignore) {}
                    }
                }
                // Update global flag for compatibility
                inFight = !activeFights.isEmpty();
            } catch (Exception ignore) {}
        }

        // If idle beyond the combat window, clear damage and active fights window cleanly
        int tickNow = 0; try { tickNow = client.getTickCount(); } catch (Exception ignore) {}
		if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS && (!damageFromOpponent.isEmpty() || !damageToOpponent.isEmpty()))
        {
            damageFromOpponent.clear();
			damageToOpponent.clear();
            try { log.debug("[Fight] window cleared on tick={} (idle>{} ticks)", tickNow, OUT_OF_COMBAT_TICKS); } catch (Exception ignore) {}
        }
        }
        catch (Exception e)
        {
            log.error("Uncaught exception in onGameTick", e);
        }
    }

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied hitsplatApplied)
	{
		try
		{
			// Only process Player vs Player combat
			if (hitsplatApplied.getActor() instanceof Player)
			{
            if (client == null || config == null) return;
            Player player = (Player) hitsplatApplied.getActor();
            Player localPlayer = client.getLocalPlayer();

			// Process only outbound damage as fight-start signal. Inbound damage updates attribution but will NOT start fights.
			if (localPlayer != null)
			{
				String opponentName = null;
				boolean startNow = false;
				int amt = 0;
				boolean startFromThisHit = false;
				boolean isMine = false;
				int hitsplatType = -1;
				try {
					net.runelite.api.Hitsplat hs = hitsplatApplied.getHitsplat();
					if (hs != null)
					{
						isMine = hs.isMine();
						hitsplatType = hs.getHitsplatType();
						int t = hitsplatType;
						int a = hs.getAmount();
						// Only consider true player-damage hitsplats; ignore poison/venom/heal/block/etc.
						if (t == HitsplatID.DAMAGE_ME || t == HitsplatID.DAMAGE_OTHER)
						{
							amt = a;
							startFromThisHit = (a > 0);
						}
						else if (t == HitsplatID.POISON || t == HitsplatID.VENOM)
						{
							amt = a; // include in totals
							startFromThisHit = false; // don't start fights from poison/venom ticks
						}
						else
						{
							amt = 0;
							startFromThisHit = false;
						}
					}
				} catch (Exception ignore) {}
				try {
					if (DEBUG_HIT_SPLAT_LOGS) {
						String actorName = null; try { actorName = player != null ? player.getName() : null; } catch (Exception ignore) {}
						String lpInteracting = null; try { lpInteracting = (localPlayer.getInteracting() instanceof Player) ? ((Player)localPlayer.getInteracting()).getName() : null; } catch (Exception ignore) {}
						String actorInteracting = null; try { actorInteracting = (player != null && player.getInteracting() instanceof Player) ? ((Player)player.getInteracting()).getName() : null; } catch (Exception ignore) {}
						log.debug("[Hitsplat] actor='{}' amt={} actorIsLocal={} lp->={} actor->={}", actorName, amt, (player == localPlayer), lpInteracting, actorInteracting);
					}
				} catch (Exception ignore) {}
				if (player == localPlayer)
				{
					// Inbound damage: resolve attacker across multiple simultaneous hitsplats
					opponentName = resolveInboundAttacker(localPlayer);
					if (opponentName != null)
					{
						lastEngagedOpponentName = opponentName;
						lastExactOpponentName = opponentName;
						if (amt > 0)
						{
							try { damageFromOpponent.merge(opponentName, (long) amt, (a, b) -> a + b); } catch (Exception ignore) {}
						}
						if (startFromThisHit)
						{
							startNow = true;
						}
						try { if (DEBUG_HIT_SPLAT_LOGS) { log.debug("[Hitsplat] inbound dmg={} opp='{}' dmgFromOpp={}", amt, opponentName, damageFromOpponent); } } catch (Exception ignore) {}
					}
				}
				else
				{
					// Outbound damage: only start when we are actually attacking this player
					boolean weAreAttacking = isMine;
					// Fallback if isMine is false but it is poison/venom (ownership not tracked on hitsplat)
					if (!weAreAttacking && (hitsplatType == HitsplatID.POISON || hitsplatType == HitsplatID.VENOM)) {
						try { weAreAttacking = (localPlayer.getInteracting() == player); } catch (Exception ignore) {}
					}

					if (weAreAttacking)
					{
						opponentName = (player != null ? player.getName() : null);
						if (opponentName != null) lastEngagedOpponentName = opponentName;
						if (amt > 0)
						{
							try { damageToOpponent.merge(opponentName, (long) amt, (a, b) -> a + b); } catch (Exception ignore) {}
						}
						if (startFromThisHit)
						{
							startNow = true;
						}
					}
					try { if (DEBUG_HIT_SPLAT_LOGS) { log.debug("[Hitsplat] outbound dmg={} weAreAttacking={} opp='{}' dmgToOpp={} ", amt, weAreAttacking, opponentName, damageToOpponent.get(opponentName)); } } catch (Exception ignore) {}
				}

				// Start/update fights if this hitsplat indicates combat (inbound from attacker or outbound while we are attacking)
				boolean validOpp = (opponentName != null && isPlayerOpponent(opponentName));
				if (startNow && opponentName != null && validOpp)
				{
					int tickNow = 0; try { tickNow = client.getTickCount(); } catch (Exception ignore) {}
					if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS)
					{
						activeFights.clear();
						damageFromOpponent.clear();
						try { log.debug("[Fight] combat window reset after {} ticks idle", tickNow - lastCombatActivityTick); } catch (Exception ignore) {}
					}
					lastCombatActivityTick = tickNow;
					try {
						if (client.getLocalPlayer() != null && opponentName.equals(client.getLocalPlayer().getName())) {
							try { log.warn("[Fight] suppress start: opponent resolved as self ({}); waiting for better signal", opponentName); } catch (Exception ignore) {}
							return;
						}
					} catch (Exception ignore) {}
					// Check global suppression
					if (suppressFightStartTicks > 0)
					{
						try { log.info("[Fight] start suppressed ({} ticks remaining)", suppressFightStartTicks); } catch (Exception ignore) {}
						return;
					}
					// Check per-opponent suppression
					if (perOpponentSuppressUntilTicks.containsKey(opponentName))
					{
						try { log.info("[Fight] start suppressed for opponent={} ({} ticks remaining)", opponentName, perOpponentSuppressUntilTicks.get(opponentName)); } catch (Exception ignore) {}
						return;
					}
					touchFight(opponentName);
					inFight = true;
					if (!inFight)
					{
						startFight(opponentName);
					}
					if (client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1)
					{
						wasInMulti = true;
					}
				}
				else if (startNow)
				{
					try {
						if (DEBUG_HIT_SPLAT_LOGS) {
							StringBuilder sb = new StringBuilder();
							java.util.List<Player> players = client.getPlayers();
							if (players != null) {
								for (Player p : players) { String pn = null; try { pn = p != null ? p.getName() : null; } catch (Exception ignore) {} if (pn != null) { if (sb.length() > 0) sb.append(", "); sb.append(pn); } }
							}
							log.debug("[Fight] start rejected opp='{}' validOpp={} playersNow=[{}]", opponentName, validOpp, sb.toString());
						}
					} catch (Exception ignore) {}
				}
			}
		}
		
		// Fight timeout is handled by scheduled checker
	}
		catch (Exception e)
		{
			log.error("Uncaught exception in onHitsplatApplied", e);
		}
	}

    // Removed: InteractingChanged-driven opponent assignment; we rely on hitsplats only for fight start

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath)
	{
		try
		{
			if (actorDeath.getActor() instanceof Player)
			{
			Player player = (Player) actorDeath.getActor();
			Player localPlayer = client.getLocalPlayer();
			try {
				String nameDbg = null; try { nameDbg = player != null ? player.getName() : null; } catch (Exception ignore) {}
				boolean engagedA = false; boolean engagedB = false;
				try { engagedA = (player != null && player.getInteracting() == localPlayer); } catch (Exception ignore) {}
				try { engagedB = (localPlayer != null && localPlayer.getInteracting() == player); } catch (Exception ignore) {}
				log.debug("[Death] actor='{}' isLocal={} actor->local={} local->actor={}", nameDbg, (player == localPlayer), engagedA, engagedB);
			} catch (Exception ignore) {}
			
            // try { log.info("[Fight] onActorDeath actor={}, inFight={}, opponent={} ", player != null ? player.getName() : "null", inFight, opponent); } catch (Exception ignore) {}

            if (player == localPlayer)
            {
                // Local player died → record, infer killer, and submit immediate loss
                selfDeathMs = System.currentTimeMillis();
                try {
                    String actualKiller = findActualKiller();
                    if (actualKiller != null) {
                        opponent = actualKiller;
                    }
                } catch (Exception ignore) {}
                // try { log.info("[Fight] self death at {} ms; opponent={}", selfDeathMs, opponent); } catch (Exception ignore) {}
                // Pick killer as the opponent who did the most damage within the current combat window
                String killer = null; long bestDmg = -1L;
                try {
                    for (java.util.Map.Entry<String, Long> e : damageFromOpponent.entrySet()) {
                        long v = (e.getValue() != null ? e.getValue() : 0L);
                        if (v > bestDmg) { bestDmg = v; killer = e.getKey(); }
                    }
                } catch (Exception ignore) {}
                if (killer == null) killer = opponent;
                if (killer == null) killer = mostRecentActiveOpponent();
				try { log.debug("[Death] self died; resolvedKiller='{}' dmgFromOpp={} activeFights={} lastEngaged='{}'", killer, damageFromOpponent, activeFights.keySet(), lastEngagedOpponentName); } catch (Exception ignore) {}
                if (killer != null) endFightFor(killer, "loss");
                else {
                    // No known opponent; clear all fights
					try { log.debug("[Death] no opponent resolved on self death; clearing fights (no submit)"); } catch (Exception ignore) {}
                    activeFights.clear(); inFight = false;
                }
            }
            else
            {
                String name = null;
                try { name = player != null ? player.getName() : null; } catch (Exception ignore) {}
                if (name != null)
                {
                    boolean isEngagedWithLocal = false;
                    try {
                        Player pi = player; Player lp = localPlayer;
                        if (pi != null && lp != null) {
                            isEngagedWithLocal = (pi.getInteracting() == lp) || (lp.getInteracting() == pi);
                        }
                    } catch (Exception ignore) {}
                    if (activeFights.containsKey(name) || isEngagedWithLocal)
                    {
						try { log.debug("[Death] opponent died name='{}' activeFight={} engagedWithLocal={}", name, activeFights.containsKey(name), isEngagedWithLocal); } catch (Exception ignore) {}
                        opponentDeathMs = System.currentTimeMillis();
                        // try { log.info("[Fight] opponent death at {} ms; opponent={}", opponentDeathMs, opponent); } catch (Exception ignore) {}
                        // try { log.info("[Fight] end snapshot: opponent={} world={} startTs={} spellStart={} spellEnd={} multi={}", opponent, client.getWorld(), fightStartTime, fightStartSpellbook, client.getVarbitValue(Varbits.SPELLBOOK), wasInMulti); } catch (Exception ignore) {}
                        endFightFor(name, "win");
                    }
					else
					{
						try { log.debug("[Death] ignoring other player's death name='{}' (no active fight, not engaged)"); } catch (Exception ignore) {}
					}
                }
            }
		}
		}
		catch (Exception e)
		{
			log.error("Uncaught exception in onActorDeath", e);
		}
	}

private void startFight(String opponentName)
	{
        // Honor suppression window to avoid mis-starts immediately after a fight ends
        long nowMsCheck = System.currentTimeMillis();
        if (nowMsCheck < suppressFightStartUntilMs)
        {
            try { log.info("[Fight] startFight suppressed at {}ms (until {}ms) for opponent={}", nowMsCheck, suppressFightStartUntilMs, opponentName); } catch (Exception ignore) {}
            return;
        }
		inFight = true;
		opponent = opponentName;
		wasInMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
		fightStartSpellbook = client.getVarbitValue(Varbits.SPELLBOOK);
		fightStartTime = System.currentTimeMillis() / 1000;
        // Reset per-fight state to ensure finalizer runs
        fightFinalized = false;
        selfDeathMs = 0L;
        opponentDeathMs = 0L;
		try { log.debug("[Fight] startFight opp='{}' world={} startSpell={} multi={}", opponentName, client.getWorld(), fightStartSpellbook, wasInMulti); } catch (Exception ignore) {}
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
                }, scheduler).thenAccept(tier -> {
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
        // RUN ON CLIENT THREAD: capture only client-bound values, then offload heavy work
        final int endSpellbookSafe = client.getVarbitValue(Varbits.SPELLBOOK);
        final long endTimeSafe = System.currentTimeMillis() / 1000;
        final String opponentSafe = (opponent != null ? opponent : (lastExactOpponentName != null ? lastExactOpponentName : lastEngagedOpponentName));
        final boolean wasInMultiSafe = wasInMulti;
        final int startSpellbookSafe = fightStartSpellbook;
        final long startTimeSafe = fightStartTime;
        final long accountHashSafe = accountHash;
        final String selfNameSafe = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
        final String idTokenSafe;
        try { idTokenSafe = dashboardPanel != null ? dashboardPanel.getIdToken() : null; } catch (Exception e) { throw e; }
        final int worldSafe; try { worldSafe = client.getWorld(); } catch (Exception e) { throw e; }

        // Offload network and UI updates to background where appropriate
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try
            {
                try { log.debug("[Fight] submitting outcome={} vs={} world={} startTs={} endTs={} startSpell={} endSpell={} multi={} acctHash={} idTokenPresent={}",
                    result, opponentSafe, worldSafe, startTimeSafe, endTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, (idTokenSafe != null && !idTokenSafe.isEmpty())); } catch (Exception ignore) {}
                String bucket = wasInMultiSafe ? "multi" : (startSpellbookSafe == 1 ? "veng" : "nh");

                // try { boolean authLoggedIn = dashboardPanel != null && dashboardPanel.isAuthLoggedIn(); boolean tokenPresent = idTokenSafe != null && !idTokenSafe.isEmpty(); log.info("[Fight] submit snapshot authLoggedIn={} tokenPresent={} opponent={} world={} startTs={} endTs={}", authLoggedIn, tokenPresent, opponentSafe, worldSafe, startTimeSafe, endTimeSafe); } catch (Exception ignore) {}
                // Fire submission first for immediacy; do other UI work afterwards
				long dmgOut = 0L; try { Long v = damageToOpponent.get(opponentSafe); if (v != null) dmgOut = v; } catch (Exception ignore) {}
				submitMatchResultSnapshot(result, endTimeSafe, selfNameSafe, opponentSafe, worldSafe,
					startTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, idTokenSafe, dmgOut);

                // Do not clear self rank immediately; request a refresh shortly after to avoid flicker
                try {
                    if (rankOverlay != null) {
                        rankOverlay.scheduleSelfRankRefresh(750L);
                    }
                } catch (Exception ignore) {}
                if (dashboardPanel != null)
                {
                    double currentMMR = estimateCurrentMMR();
                    String opponentRank = null;
                    try {
                        if (opponentSafe != null)
                        {
                            opponentRank = dashboardPanel.getTierLabelByName(opponentSafe, bucket);
                        }
                    } catch (Exception ignore) {}
                    final String oppRankFinal = opponentRank;
                    if ("win".equals(result) && oppRankFinal != null)
                    {
                        updateHighestRankDefeated(oppRankFinal);
                    }
                    else if ("loss".equals(result) && oppRankFinal != null)
                    {
                        updateLowestRankLostTo(oppRankFinal);
                    }
                    try { dashboardPanel.updateTierGraphRealTime(bucket, currentMMR); } catch (Exception ignore) {}
                    try {
                        // Set post-match refresh countdown (15s = 25 ticks)
                        postMatchRefreshTicks = 25;
                        postMatchRefreshSelf = selfNameSafe;
                        postMatchRefreshOpp = opponentSafe;
                        postMatchRefreshRes = result;
                        postMatchRefreshOppRank = opponentRank;
                        
                        try { dashboardPanel.preloadSelfRankNumbers(selfNameSafe); } catch (Exception ignore) {}
                    } catch (Exception ignore) {}
                    try { dashboardPanel.updateAdditionalStatsFromPlugin("win".equals(result) ? opponentRank : null,
                            "loss".equals(result) ? opponentRank : null); } catch (Exception ignore) {}
                }
            }
            catch (Exception e)
            {
                try { log.error("[Fight] async finalize error", e); } catch (Exception ignore) {}
            }
        }, scheduler);

        log.info("Fight ended with result: " + result + ", Multi during fight: " + wasInMulti + ", Start spellbook: " + startSpellbookSafe + ", End spellbook: " + endSpellbookSafe);
        // Add per-opponent suppression for 5 ticks (3 seconds) so trailing hitsplats from that opponent don't re-start a fight,
        // but allow combat with other players to start immediately.
        try {
            if (opponentSafe != null && !opponentSafe.isEmpty()) {
                perOpponentSuppressUntilTicks.put(opponentSafe, 5);
            }
        } catch (Exception ignore) {}
        resetFightState();
    }

    // Submit using captured snapshot; no client access in this method
    private void submitMatchResultSnapshot(String result, long fightEndTime,
                                           String playerId, String opponentId, int world,
                                           long fightStartTs, int fightStartSpellbookLocal, int fightEndSpellbookLocal,
                                           boolean wasInMultiLocal, long accountHashLocal, String idTokenLocal, long damageToOpponentLocal)
    {
        try
        {
			try { log.debug("[Submit] snapshot outcome={} self='{}' opp='{}' world={} startTs={} endTs={} startSpell={} endSpell={} multi={} acctHash={} idTokenPresent={} dmgOut={}",
				result, playerId, opponentId, world, fightStartTs, fightEndTime, getSpellbookName(fightStartSpellbookLocal), getSpellbookName(fightEndSpellbookLocal), wasInMultiLocal, accountHashLocal, (idTokenLocal != null && !idTokenLocal.isEmpty()), damageToOpponentLocal); } catch (Exception ignore) {}
            matchResultService.submitMatchResult(
                playerId,
                opponentId,
                result,
                world,
                fightStartTs,
                fightEndTime,
                getSpellbookName(fightStartSpellbookLocal),
                getSpellbookName(fightEndSpellbookLocal),
                wasInMultiLocal,
                accountHashLocal,
                idTokenLocal,
                damageToOpponentLocal,
                clientUniqueId
            ).thenAccept(success -> {
                if (success) {
                    log.debug("Match result submitted successfully");
                } else {
                    log.warn("Failed to submit match result");
                }
            }).exceptionally(ex -> {
                try { log.error("Error submitting match result", ex); } catch (Exception ignore) {}
                return null;
            });
        }
        catch (Exception e)
        {
            try { log.error("[Fight] submitMatchResultSnapshot error", e); } catch (Exception ignore) {}
        }
    }
	
    @SuppressWarnings("unused")
    private void endFightTimeout()
    {
        if (!inFight) return;
        // Do not auto-submit ties on inactivity per latest instructions; just reset.
        log.info("Fight timed out - no result submitted");
        resetFightState();
    }

    // State for pending finalizations using tick timing
    private int pendingFinalizeTicks = -1;
    private String pendingFinalizeFallback = null;
    
    // State for post-match refresh ticks
    private int postMatchRefreshTicks = -1;
    private String postMatchRefreshSelf = null;
    private String postMatchRefreshOpp = null;
    private String postMatchRefreshRes = null;
    private String postMatchRefreshOppRank = null;
    
    // State for fight suppression using ticks
    private int suppressFightStartTicks = 0;
    
    // State for GC ticks
    private int gcTicksCounter = 0;

    @SuppressWarnings("unused")
    private void scheduleDoubleKoCheck(String fallbackResult)
	{
		try
		{
            try { log.info("[Fight] scheduling finalize in 3 ticks (fallback={}), a={}, b={}", fallbackResult, selfDeathMs, opponentDeathMs); } catch (Exception ignore) {}
            // Use tick-based scheduling: wait 3 ticks (approx 1.8s)
            pendingFinalizeTicks = 3;
            pendingFinalizeFallback = fallbackResult;
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
        // After finishing a fight, briefly suppress new starts to ignore trailing hitsplats (approx 1.3 ticks -> 2 ticks)
        suppressFightStartTicks = 2;
        try { suppressFightStartUntilMs = 0L; } catch (Exception ignore) {} // Clear legacy ms field
        activeFights.clear();
        damageFromOpponent.clear();
        damageToOpponent.clear();
        lastCombatActivityTick = 0;
        try { log.debug("[Fight] state reset; suppressTicks={} activeFightsCleared" , suppressFightStartTicks); } catch (Exception ignore) {}
	}

	private String resolveInboundAttacker(Player localPlayer)
	{
		try
		{
			Set<String> candidates = new LinkedHashSet<>();
			java.util.List<Player> players = client.getPlayers();
			if (players != null)
			{
				for (Player other : players)
				{
					if (other == null || other == localPlayer) continue;
					String name = null;
					try { if (other.getInteracting() == localPlayer) { name = other.getName(); } } catch (Exception ignore) {}
					if (name != null && !name.trim().isEmpty())
					{
						candidates.add(name);
					}
				}
			}

			// Prefer the most recently engaged opponents when available
			if (lastExactOpponentName != null && candidates.contains(lastExactOpponentName))
			{
				return lastExactOpponentName;
			}
			if (lastEngagedOpponentName != null && candidates.contains(lastEngagedOpponentName))
			{
				return lastEngagedOpponentName;
			}

			String mostRecent = pickMostRecentlyActive(new ArrayList<>(candidates));
			if (mostRecent != null)
			{
				return mostRecent;
			}

			if (!candidates.isEmpty())
			{
				return pickRandom(new ArrayList<>(candidates));
			}

			// Fallback: use active fights even if not currently interacting
			List<String> active = new ArrayList<>(activeFights.keySet());
			if (!active.isEmpty())
			{
				if (lastEngagedOpponentName != null && active.contains(lastEngagedOpponentName))
				{
					return lastEngagedOpponentName;
				}
				String recent = pickMostRecentlyActive(active);
				if (recent != null)
				{
					return recent;
				}
				return pickRandom(active);
			}

			// Final fallback: nothing known
			return null;
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	private String pickMostRecentlyActive(List<String> names)
	{
		if (names == null || names.isEmpty()) return null;
		long best = Long.MIN_VALUE;
		String bestName = null;
		for (String name : names)
		{
			FightEntry entry = activeFights.get(name);
			long last = entry != null ? entry.lastActivityMs : Long.MIN_VALUE;
			if (last > best)
			{
				best = last;
				bestName = name;
			}
		}
		return bestName;
	}

	private String pickRandom(List<String> names)
	{
		if (names == null || names.isEmpty()) return null;
		if (names.size() == 1) return names.get(0);
		int idx = ThreadLocalRandom.current().nextInt(names.size());
		return names.get(idx);
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

    // === Multi-fight helpers ===
    @SuppressWarnings("unused")
    private static class FightEntry {
        final String opponent; // kept for clarity
        final long startTs;
        final int startSpellbook;
        final boolean wasInMulti;
        final int startWorld; // kept for diagnostics
        volatile long lastActivityMs;
        volatile boolean finalized = false;
        FightEntry(String op, long ts, int sb, boolean multi, int world)
        {
            opponent = op;
            startTs = ts;
            startSpellbook = sb;
            wasInMulti = multi;
            startWorld = world;
            lastActivityMs = System.currentTimeMillis();
        }
    }

    private void touchFight(String opponentName)
    {
        if (opponentName == null || opponentName.isEmpty()) return;
        long ts = System.currentTimeMillis() / 1000;
        int sb = client.getVarbitValue(Varbits.SPELLBOOK);
        boolean multi = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1;
        int world = client.getWorld();
        activeFights.compute(opponentName, (k, v) -> {
            if (v == null) {
                try { log.debug("[Fight] start (multi-track) vs={} world={} spellbook={} multi={}", opponentName, world, sb, multi); } catch (Exception ignore) {}
                return new FightEntry(opponentName, ts, sb, multi, world);
            }
            v.lastActivityMs = System.currentTimeMillis();
			try { if (DEBUG_HIT_SPLAT_LOGS) { log.debug("[Fight] touch vs={} lastActivityMs={} activeCount={}", opponentName, v.lastActivityMs, activeFights.size()); } } catch (Exception ignore) {}
            return v;
        });
        
        // Probe shard presence once per opponent during combat (async to avoid network IO on client thread)
        if (!shardPresence.containsKey(opponentName))
        {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    String bucket = bucketKey(config.rankBucket());
                    String tier = resolvePlayerRank(opponentName, bucket);
                    if (tier != null && !tier.isEmpty()) {
                        shardPresence.put(opponentName, Boolean.TRUE);
                        try { log.debug("[Fight] shard presence hit for {} bucket={} tier={}", opponentName, bucket, tier); } catch (Exception ignore) {}
                    } else {
                        // No shard hit; mark as absent for now
                        shardPresence.putIfAbsent(opponentName, Boolean.FALSE);
                        try { log.debug("[Fight] shard presence miss for {} bucket={}", opponentName, bucket); } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
            }, scheduler);
        }
    }

    private String mostRecentActiveOpponent()
    {
        long best = -1L; String bestName = null;
        for (java.util.Map.Entry<String, FightEntry> e : activeFights.entrySet())
        {
            if (e.getValue() == null) continue;
            long la = e.getValue().lastActivityMs;
            if (la > best) { best = la; bestName = e.getKey(); }
        }
        return bestName;
    }

    private void endFightFor(String opponentName, String result)
    {
        FightEntry fe = activeFights.remove(opponentName);
        if (fe != null) fe.finalized = true;
        final int endSpellbookSafe = client.getVarbitValue(Varbits.SPELLBOOK);
        final long endTimeSafe = System.currentTimeMillis() / 1000;
        final String opponentSafe = opponentName;
        final boolean wasInMultiSafe = fe != null ? fe.wasInMulti : wasInMulti;
        final int startSpellbookSafe = fe != null ? fe.startSpellbook : fightStartSpellbook;
        final long startTimeSafe = fe != null ? fe.startTs : (endTimeSafe - 10);
        final long accountHashSafe = accountHash;
        final String selfNameSafe = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
        final String idTokenSafe = dashboardPanel != null ? dashboardPanel.getIdToken() : null;
        final int worldSafe = client.getWorld();
		final long damageToOpponentSafe; { Long v = damageToOpponent.get(opponentSafe); damageToOpponentSafe = v != null ? v : 0L; }
		try { log.debug("[Fight] endFightFor outcome={} opp='{}' world={} startTs={} endTs={} startSpell={} endSpell={} multi={} dmgOut={} idTokenPresent={}",
			result, opponentSafe, worldSafe, startTimeSafe, endTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, damageToOpponentSafe, (idTokenSafe != null && !idTokenSafe.isEmpty())); } catch (Exception ignore) {}

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try
            {
                // try { log.info("[Fight] submitting outcome={} vs={} world={} startTs={} endTs={} startSpell={} endSpell={} multi={} acctHash={} idTokenPresent={}",
                //         result, opponentSafe, worldSafe, startTimeSafe, endTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, (idTokenSafe != null && !idTokenSafe.isEmpty())); } catch (Exception ignore) {}
				submitMatchResultSnapshot(result, endTimeSafe, selfNameSafe, opponentSafe, worldSafe,
						startTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, idTokenSafe, damageToOpponentSafe);
                // If opponent wasn't found in shard, schedule API fallback rank update after 15s
                try {
                    boolean inShard = Boolean.TRUE.equals(shardPresence.get(opponentSafe));
                    if (!inShard && !Boolean.TRUE.equals(apiFallbackActive.get(opponentSafe)) && dashboardPanel != null && rankOverlay != null)
                    {
                        apiFallbackActive.put(opponentSafe, Boolean.TRUE);
                        scheduler.schedule(() -> {
                            try {
                                String bucket = bucketKey(config.rankBucket());
                                String tier = dashboardPanel.fetchTierFromApi(opponentSafe, bucket);
                                if (tier != null && !tier.isEmpty()) {
                                    rankOverlay.setRankFromApi(opponentSafe, tier);
                                }
                            } catch (Exception ignore) {}
                        }, 15L, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Exception ignore) {}

                // Unconditional +15s API refresh for self and opponent, and force shard lookup like pvp lookup
                try {
                    scheduler.schedule(() -> {
                        try {
                            String bucketLater = bucketKey(config.rankBucket());
                            String selfTierLater = dashboardPanel != null ? dashboardPanel.fetchSelfTierFromApi(selfNameSafe, bucketLater) : null;
                            if (selfTierLater != null && !selfTierLater.isEmpty() && rankOverlay != null) {
                                rankOverlay.setRankFromApi(selfNameSafe, selfTierLater);
                                try { rankOverlay.forceLookupAndDisplay(selfNameSafe); } catch (Exception ignore) {}
                            }
                        } catch (Exception ignore) {}
                    }, 15L, java.util.concurrent.TimeUnit.SECONDS);
                    if (opponentSafe != null && !opponentSafe.isEmpty()) {
                        scheduler.schedule(() -> {
                            try {
                                String bucketLater = bucketKey(config.rankBucket());
                                String oppTierLater = dashboardPanel != null ? dashboardPanel.fetchTierFromApi(opponentSafe, bucketLater) : null;
                                if (oppTierLater != null && !oppTierLater.isEmpty() && rankOverlay != null) {
                                    rankOverlay.setRankFromApi(opponentSafe, oppTierLater);
                                    try { rankOverlay.forceLookupAndDisplay(opponentSafe); } catch (Exception ignore) {}
                                }
                            } catch (Exception ignore) {}
                        }, 15L, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Exception ignore) {}
            } catch (Exception e) {
                try { log.error("[Fight] async finalize error (multi-track)", e); } catch (Exception ignore) {}
            }
        }, scheduler);
        // Suppress restarts only for this opponent
        try { if (opponentSafe != null) perOpponentSuppressUntilMs.put(opponentSafe, System.currentTimeMillis() + 3000L); } catch (Exception ignore) {}
        inFight = !activeFights.isEmpty();
    }

    

    public String resolvePlayerRank(String playerName, String bucket)
    {
        try
        {
            boolean isSelf = false;
            try {
                isSelf = client != null && client.getLocalPlayer() != null && playerName != null && playerName.equals(client.getLocalPlayer().getName());
            } catch (Exception ignore) {}
            return resolvePlayerRankNoClient(playerName, bucket, isSelf);
        }
        catch (Exception ignore) {}
        return null;
    }

    // Variant that avoids accessing client; caller determines isSelf on the client thread
    public String resolvePlayerRankNoClient(String playerName, String bucket, boolean isSelf)
    {
        try
        {
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
                if (!isSelf)
                {
                    try {
                        String selfName = client != null && client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
                        if (selfName != null && dashboardPanel != null)
                        {
                            String fallbackTier = dashboardPanel.getTierLabelByName(playerName, bucket);
                            if (fallbackTier != null && !fallbackTier.isEmpty()) return fallbackTier;
                        }
                    } catch (Exception ignore) {}
                }
                return null;
            }

            // Fallback for self only when shard misses: derive tier from API
            try
            {
                if (isSelf && client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
                {
                    String self = client.getLocalPlayer().getName();
                    if (self != null && dashboardPanel != null)
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
            if (dashboardPanel == null) return -1;
            // Use cached-only accessor to avoid blocking the client thread (especially for overlay render)
            return dashboardPanel.getCachedRankNumberByName(playerName, bucket);
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
	
@SuppressWarnings("unused")
private String determineBucket()
	{
        // Determine bucket based on spellbook and multi area
        if (wasInMulti) return "multi";
        if (fightStartSpellbook == 1) return "veng"; // Lunar
        return "nh"; // Default to NH
	}
	
	private double estimateCurrentMMR()
	{
		// Simplified MMR estimation - in real implementation would track actual MMR
		return 1000.0; // Placeholder
	}
	
@SuppressWarnings("unused")
private void submitMatchResult(String result, long fightEndTime)
	{
        String playerId;
        try { playerId = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown"; } catch (Exception e) { playerId = "Unknown"; }
        int world; try { world = client.getWorld(); } catch (Exception e) { world = -1; }
        String startSpellbook = getSpellbookName(fightStartSpellbook);
        String endSpellbook = getSpellbookName(fightEndSpellbook);
        String idToken = null;
        try { idToken = dashboardPanel != null ? dashboardPanel.getIdToken() : null; } catch (Exception ignore) {}
        long dmgOut = 0L;
        
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
            idToken,
            dmgOut,
            clientUniqueId
        ).thenAccept(success -> {
            if (success) {
                log.debug("Match result submitted successfully");
            } else {
                log.warn("Failed to submit match result");
            }
        }).exceptionally(ex -> {
            try { log.error("Error submitting match result", ex); } catch (Exception ignore) {}
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
		try {
			StringBuilder sb = new StringBuilder();
			if (players != null) {
				for (Player p : players) { String s = null; try { s = p != null ? p.getName() : null; } catch (Exception ignore) {} if (s != null) { if (sb.length() > 0) sb.append(", "); sb.append(s); } }
			}
			log.debug("[Fight] isPlayerOpponent: '{}' not found in players=[{}]", name, sb.toString());
		} catch (Exception ignore) {}
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

    public Client getClient()
    {
        return client;
    }

    public boolean isShardReady()
    {
        return shardReady;
    }
}
