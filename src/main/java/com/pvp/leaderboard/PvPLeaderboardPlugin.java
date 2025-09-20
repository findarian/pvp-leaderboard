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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ConcurrentHashMap;

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
    private MatchResultService matchResultService = new MatchResultService();
    private ScheduledExecutorService fightScheduler;
    // Tracks multiple simultaneous fights (per-opponent) with 10s inactivity expiry
    private final java.util.concurrent.ConcurrentHashMap<String, FightEntry> activeFights = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledExecutorService fightGcScheduler;
    private volatile long selfDeathMs = 0L;
    private volatile long opponentDeathMs = 0L;
    private volatile boolean fightFinalized = false;
private volatile boolean shardReady = false;
    // Guard to prevent immediate false restarts caused by post-death hitsplats
private volatile long suppressFightStartUntilMs = 0L;
    // Per-opponent suppression after ending a fight with them; allows multi-combat with others
    private final ConcurrentHashMap<String, Long> perOpponentSuppressUntilMs = new ConcurrentHashMap<>();
    // Damage accounting since last out-of-combat window
    private final java.util.concurrent.ConcurrentHashMap<String, Long> damageFromOpponent = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int OUT_OF_COMBAT_TICKS = 16; // 16 ticks ≈ 9.6s
    private volatile int lastCombatActivityTick = 0;

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
        try {
            fightGcScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pvp-fight-gc");
                t.setDaemon(true);
                return t;
            });
            fightGcScheduler.scheduleAtFixedRate(() -> {
                try {
                    long now = System.currentTimeMillis();
                    for (java.util.Map.Entry<String, FightEntry> e : activeFights.entrySet())
                    {
                        FightEntry fe = e.getValue();
                        if (fe == null) continue;
                        if (!fe.finalized && now - fe.lastActivityMs > 10_000L)
                        {
                            // Expire inactive fights without submission (combat lock ends after 10s)
                            activeFights.remove(e.getKey());
                            try { log.info("[Fight] expire inactive vs={} lastActivity={}msAgo", e.getKey(), now - fe.lastActivityMs); } catch (Exception ignore) {}
                        }
                    }
                    // Update global flag for compatibility
                    inFight = !activeFights.isEmpty();
                } catch (Exception ignore) {}
            }, 1L, 1L, java.util.concurrent.TimeUnit.SECONDS);
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
        try { if (fightGcScheduler != null) { fightGcScheduler.shutdownNow(); } } catch (Exception ignore) {}
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
			// Fully clear fight state on logout
			try { log.info("[Fight] state reset on LOGIN_SCREEN"); } catch (Exception ignore) {}
			resetFightState();
        }
		else if (gameStateChanged.getGameState() == GameState.HOPPING || gameStateChanged.getGameState() == GameState.LOADING)
		{
			try {
				if (rankOverlay != null) {
					rankOverlay.resetLookupStateOnWorldHop();
				}
				// Do NOT reset active fights on LOADING/HOPPING. Teleports and instancing trigger these states during fights.
				try { log.info("[Fight] scene change: {} (fight preserved)", gameStateChanged.getGameState()); } catch (Exception ignore) {}
			} catch (Exception ignore) {}
		}
	}

    // Prewarm helpers removed

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // If idle beyond the combat window, clear damage and active fights window cleanly
        int tickNow = 0; try { tickNow = client.getTickCount(); } catch (Exception ignore) {}
        if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS && !damageFromOpponent.isEmpty())
        {
            damageFromOpponent.clear();
            try { log.info("[Fight] window cleared on tick={} (idle>{} ticks)", tickNow, OUT_OF_COMBAT_TICKS); } catch (Exception ignore) {}
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
			
            // Process both directions: when we hit another player or get hit by another player
            if (localPlayer != null)
			{
				// For player vs player combat, ensure both source and target are players
				String opponentName = null;
                if (player == localPlayer)
				{
					// Local player took damage; try to identify attacker
					opponentName = getPlayerAttacker();
					if (opponentName == null)
					{
						// Use who we are currently interacting with (often the attacker in 1v1)
						try {
							if (localPlayer.getInteracting() instanceof Player) {
								opponentName = ((Player) localPlayer.getInteracting()).getName();
							}
						} catch (Exception ignore) {}
					}
					if (opponentName == null && lastEngagedOpponentName != null)
					{
						opponentName = lastEngagedOpponentName;
					}
                    if (opponentName != null) lastEngagedOpponentName = opponentName;
                    // Damage accounting for killer attribution window
                    try {
                        int amt = hitsplatApplied.getHitsplat() != null ? hitsplatApplied.getHitsplat().getAmount() : 0;
                        if (amt > 0 && opponentName != null) {
                            damageFromOpponent.merge(opponentName, (long) amt, Long::sum);
                        }
                    } catch (Exception ignore) {}
				}
				else
				{
					// Local player dealt damage to another player (any damage counts)
					opponentName = player.getName();
                    if (opponentName != null) lastEngagedOpponentName = opponentName;
				}
				
				// Only proceed if we have a valid player opponent
				if (opponentName != null && isPlayerOpponent(opponentName))
				{
                    // Global combat window check: clear if idle > OUT_OF_COMBAT_TICKS
                    int tickNow = 0; try { tickNow = client.getTickCount(); } catch (Exception ignore) {}
                    if (tickNow - lastCombatActivityTick > OUT_OF_COMBAT_TICKS)
                    {
                        activeFights.clear();
                        damageFromOpponent.clear();
                        try { log.info("[Fight] combat window reset after {} ticks idle", tickNow - lastCombatActivityTick); } catch (Exception ignore) {}
                    }
                    lastCombatActivityTick = tickNow;
                    // Avoid starting fights with our own name due to fallback errors
                    try {
                        if (client.getLocalPlayer() != null && opponentName.equals(client.getLocalPlayer().getName())) {
                            try { log.warn("[Fight] suppress start: opponent resolved as self ({}); waiting for better signal", opponentName); } catch (Exception ignore) {}
                            return;
                        }
                    } catch (Exception ignore) {}
                    // Combat activity marker (no-op)
                    // Suppress starts during the guard window right after a fight ends (use ms)
                    long nowMsCheck = System.currentTimeMillis();
                    if (nowMsCheck < suppressFightStartUntilMs)
                    {
                        try { log.info("[Fight] start suppressed at {}ms (until {}ms)", nowMsCheck, suppressFightStartUntilMs); } catch (Exception ignore) {}
                        return;
                    }

					// Per-opponent suppression: after ending with X, ignore X for 3s but allow others
					Long untilMs = perOpponentSuppressUntilMs.get(opponentName);
					if (untilMs != null && System.currentTimeMillis() < untilMs)
					{
						try { log.info("[Fight] start suppressed for opponent={} until {}ms", opponentName, untilMs); } catch (Exception ignore) {}
						return;
					}

					// Start or update per-opponent fight state (multi-fight support)
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
			}
		}
		
		// Fight timeout is handled by scheduled checker
	}

    // Removed: InteractingChanged-driven opponent assignment; we rely on hitsplats only for fight start

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath)
	{
		if (actorDeath.getActor() instanceof Player)
		{
			Player player = (Player) actorDeath.getActor();
			Player localPlayer = client.getLocalPlayer();
			
            try { log.info("[Fight] onActorDeath actor={}, inFight={}, opponent={} ", player != null ? player.getName() : "null", inFight, opponent); } catch (Exception ignore) {}

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
                try { log.info("[Fight] self death at {} ms; opponent={}", selfDeathMs, opponent); } catch (Exception ignore) {}
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
                if (killer != null) endFightFor(killer, "loss");
                else {
                    // No known opponent; clear all fights
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
                        opponentDeathMs = System.currentTimeMillis();
                        try { log.info("[Fight] opponent death at {} ms; opponent={}", opponentDeathMs, opponent); } catch (Exception ignore) {}
                        try {
                            log.info("[Fight] end snapshot: opponent={} world={} startTs={} spellStart={} spellEnd={} multi={}", opponent, client.getWorld(), fightStartTime, fightStartSpellbook, client.getVarbitValue(Varbits.SPELLBOOK), wasInMulti);
                        } catch (Exception ignore) {}
                        endFightFor(name, "win");
                    }
                }
            }
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
        try { log.info("[Fight] start snapshot: opponent={} world={} spellbook={} multi={}", opponentName, client.getWorld(), fightStartSpellbook, wasInMulti); } catch (Exception ignore) {}
        
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
                try { log.info("[Fight] submitting outcome={} vs={} world={} startTs={} endTs={} startSpell={} endSpell={} multi={} acctHash={} idTokenPresent={}",
                        result, opponentSafe, worldSafe, startTimeSafe, endTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, (idTokenSafe != null && !idTokenSafe.isEmpty())); } catch (Exception ignore) {}
                String bucket = wasInMultiSafe ? "multi" : (startSpellbookSafe == 1 ? "veng" : "nh");

                try {
                    boolean authLoggedIn = dashboardPanel != null && dashboardPanel.isAuthLoggedIn();
                    boolean tokenPresent = idTokenSafe != null && !idTokenSafe.isEmpty();
                    log.info("[Fight] submit snapshot authLoggedIn={} tokenPresent={} opponent={} world={} startTs={} endTs={}", authLoggedIn, tokenPresent, opponentSafe, worldSafe, startTimeSafe, endTimeSafe);
                } catch (Exception ignore) {}
                // Fire submission first for immediacy; do other UI work afterwards
                submitMatchResultSnapshot(result, endTimeSafe, selfNameSafe, opponentSafe, worldSafe,
                    startTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, idTokenSafe);

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
                        final String currentBucket = bucketKey(config.rankBucket());
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try { return dashboardPanel.fetchSelfTierFromApi(selfNameSafe, currentBucket); } catch (Exception e) { return null; }
                        }).thenAccept(tier -> {
                            if (tier != null && !tier.isEmpty() && rankOverlay != null) {
                                rankOverlay.setRankFromApi(selfNameSafe, tier);
                            }
                        });
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
        });

        log.info("Fight ended with result: " + result + ", Multi during fight: " + wasInMulti + ", Start spellbook: " + startSpellbookSafe + ", End spellbook: " + endSpellbookSafe);
        // Add per-opponent suppression for 3 seconds so trailing hitsplats from that opponent don't re-start a fight,
        // but allow combat with other players to start immediately.
        try {
            if (opponentSafe != null && !opponentSafe.isEmpty()) {
                perOpponentSuppressUntilMs.put(opponentSafe, System.currentTimeMillis() + 3000L);
            }
        } catch (Exception ignore) {}
        resetFightState();
    }

    // Submit using captured snapshot; no client access in this method
    private void submitMatchResultSnapshot(String result, long fightEndTime,
                                           String playerId, String opponentId, int world,
                                           long fightStartTs, int fightStartSpellbookLocal, int fightEndSpellbookLocal,
                                           boolean wasInMultiLocal, long accountHashLocal, String idTokenLocal)
    {
        try
        {
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
                idTokenLocal
            ).thenAccept(success -> {
                if (success) {
                    log.info("Match result submitted successfully");
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

    @SuppressWarnings("unused")
    private void scheduleDoubleKoCheck(String fallbackResult)
	{
		try
		{
            if (fightScheduler == null || fightScheduler.isShutdown())
            {
                try {
                    fightScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "pvp-fight-events");
                        t.setDaemon(true);
                        return t;
                    });
                } catch (Exception ignore) {}
            }
            final long scheduledAt = System.currentTimeMillis();
            try { log.info("[Fight] scheduling finalize in 1500ms (fallback={}), a={}, b={}", fallbackResult, selfDeathMs, opponentDeathMs); } catch (Exception ignore) {}
            fightScheduler.schedule(() -> {
				try
				{
                    try { log.info("[Fight] finalize task fired; fightFinalized={} a={} b={} inFight={}", fightFinalized, selfDeathMs, opponentDeathMs, inFight); } catch (Exception ignore) {}
                    if (fightFinalized) { try { log.info("[Fight] finalize task exit: already finalized"); } catch (Exception ignore) {} return; }
					long a = selfDeathMs;
					long b = opponentDeathMs;
                    if (a > 0L && b > 0L && Math.abs(a - b) <= 1500L)
					{
						fightFinalized = true;
                        try { log.info("[Fight] finalize tie (Δ={} ms)", Math.abs(a - b)); } catch (Exception ignore) {}
                        Runnable fin = () -> { try { endFight("tie"); } catch (Exception e) { try { log.error("[Fight] endFight(tie) error", e); } catch (Exception ignore) {} } };
                        try { if (clientThread != null) clientThread.invokeLater(fin); else fin.run(); } catch (Exception e) { fin.run(); }
						return;
					}
					if (fallbackResult != null && !fightFinalized)
					{
						fightFinalized = true;
                        try { log.info("[Fight] finalize {} after wait; a={}, b={}, scheduledAt={}", fallbackResult, a, b, scheduledAt); } catch (Exception ignore) {}
                        final String outcome = fallbackResult;
                        Runnable fin = () -> {
                            try {
                                // Guard opponent presence; if lost due to relog, keep last known
                                if (opponent == null || opponent.isEmpty()) {
                                    try { log.warn("[Fight] opponent missing at finalize; outcome={}, a={}, b={}", outcome, a, b); } catch (Exception ignore) {}
                                }
                                // LMS safeguard: small delay before endFight to avoid instance teardown race
                                try { if (client != null && client.getWorld() == 390) { Thread.sleep(250); } } catch (Exception ignored) {}
                                endFight(outcome);
                            } catch (Exception e) {
                                try { log.error("[Fight] endFight({}) error", outcome, e); } catch (Exception ignore) {}
                            }
                        };
                        try { if (clientThread != null) clientThread.invokeLater(fin); else fin.run(); } catch (Exception e) { fin.run(); }
					}
				}
				catch (Exception ignore) {}
            }, 1500L, java.util.concurrent.TimeUnit.MILLISECONDS);

            // Watchdog: force finalize shortly after guard if still not finalized
            fightScheduler.schedule(() -> {
                try
                {
                    if (fightFinalized) { try { log.info("[Fight] watchdog exit: already finalized"); } catch (Exception ignore) {} return; }
                    fightFinalized = true;
                    final String outcome = (fallbackResult != null ? fallbackResult : "loss");
                    try { log.warn("[Fight] watchdog finalize {}; a={} b={} scheduledAt={}", outcome, selfDeathMs, opponentDeathMs, scheduledAt); } catch (Exception ignore) {}
                    Runnable fin = () -> { try { endFight(outcome); } catch (Exception e) { try { log.error("[Fight] watchdog endFight({}) error", outcome, e); } catch (Exception ignore) {} } };
                    try { if (clientThread != null) clientThread.invokeLater(fin); else fin.run(); } catch (Exception e) { fin.run(); }
                }
                catch (Exception ignore) {}
            }, 2000L, java.util.concurrent.TimeUnit.MILLISECONDS);
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
        // After finishing a fight, briefly suppress new starts to ignore trailing hitsplats
        try { suppressFightStartUntilMs = System.currentTimeMillis() + 800L; } catch (Exception ignore) { suppressFightStartUntilMs = 0L; }
        activeFights.clear();
        damageFromOpponent.clear();
        lastCombatActivityTick = 0;
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
        FightEntry(String op, long ts, int sb, boolean multi, int world){ opponent=op; startTs=ts; startSpellbook=sb; wasInMulti=multi; startWorld=world; lastActivityMs=System.currentTimeMillis(); }
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
                try { log.info("[Fight] start (multi-track) vs={} world={} spellbook={} multi={}", opponentName, world, sb, multi); } catch (Exception ignore) {}
                return new FightEntry(opponentName, ts, sb, multi, world);
            }
            v.lastActivityMs = System.currentTimeMillis();
            return v;
        });
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

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try
            {
                try { log.info("[Fight] submitting outcome={} vs={} world={} startTs={} endTs={} startSpell={} endSpell={} multi={} acctHash={} idTokenPresent={}",
                        result, opponentSafe, worldSafe, startTimeSafe, endTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, (idTokenSafe != null && !idTokenSafe.isEmpty())); } catch (Exception ignore) {}
                submitMatchResultSnapshot(result, endTimeSafe, selfNameSafe, opponentSafe, worldSafe,
                        startTimeSafe, startSpellbookSafe, endSpellbookSafe, wasInMultiSafe, accountHashSafe, idTokenSafe);
            } catch (Exception e) {
                try { log.error("[Fight] async finalize error (multi-track)", e); } catch (Exception ignore) {}
            }
        });
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

    @SuppressWarnings("unused")
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

    @SuppressWarnings("unused")
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
