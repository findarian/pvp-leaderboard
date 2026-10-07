package com.pvp.leaderboard;

import com.google.inject.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.lobby.*;
import com.pvp.leaderboard.overlay.*;
import com.pvp.leaderboard.queue.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.service.socket.*;
import com.pvp.leaderboard.tournament.*;
import com.pvp.leaderboard.ui.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.*;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.*;
import javax.swing.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.input.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.*;
import javax.inject.Inject;
import javax.swing.Timer;
import java.util.List;

@Slf4j
@PluginDescriptor(
	name = "PvP Leaderboard",
	internalName = "pvp-leaderboard",
	legacyDataDirectory = "pvp-leaderboard.id"
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
	private MatchFoundNotificationOverlay matchPopup;

	@Inject
	private PluginDisableWarningOverlay warnOverlay;

	@Inject
	private WinStreakOverlay winStreakOverlay;

	@Inject
	private WinStreakTracker winStreakTracker;

	@Inject
	private ConfigManager configManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScenePlayers scenePlayers;

	@Inject
	private PvpApi pvpApi;

	@Inject
	private DiscordLogin discordLogin;

	@Inject
	private IdentitySvc identitySvc;

	@Inject
	private MenuHandler menuHandler;

	@Inject
	private FightMonitor fightMonitor;

	@Inject
	private WhitelistService whitelistService;

	@Inject
	private MemberFeed memberFeed;

	@Inject
	private SocketMgr socketMgr;

	@Inject
	private WebSocketLobbyService lobbySvc;

	@Inject
	private ProfileGate profileGate;

	@Inject
	private LobbyPrefs lobbyPrefs;

	// Plan 10 step 7: the matchmaking queue + Swiss tournaments (2026-09-21).
	@Inject
	private WebSocketQueueService queueSvc;

	@Inject
	private TourneySvc tourneySvc;

	@Inject
	private OppTracker oppTracker;

	@Inject
	private TournamentOpponentOverlay oppOverlay;

	/** Plan 10 F.2: flips to the Tournament bucket when a round opens
	 *  (config-gated); the switch back is the next ordinary fight. */
	private final BucketSwitch bucketSwitch =
		new BucketSwitch(() -> config.autoSwitchTournamentBucket(), this::pinBucket);

	@Inject
	private ItemManager itemManager;

	@Inject
	private KitStore kitStore;

	@Inject
	private KitReader kitReader;

	@Inject
	private GearWatcher gearWatcher;

	@Inject
	private ArenaLocator arenaLocator;

	@Inject
	private GearSearch gearSearch;

	@Inject
	private ArenaGearOverlay gearOverlay;

	private GearTracker gearTracker;
	private GearReporter gearReporter;
	private Timer gearTicker;

	private Dashboard dashPanel;
	private NavigationButton navButton;
	private int selfRankWait = -1;
	private boolean heartbeatDue = false;

	/** Gates the once-per-session half of the delayed init below. The
	 *  same countdown is armed by LOGGED_IN and by HOPPING/LOADING,
	 *  and LOADING fires on every map region load — see
	 *  {@link InitTracker} for why re-running the profile
	 *  fetch and heartbeat restart on those is both pointless and
	 *  expensive. */
	private final InitTracker initTracker = new InitTracker();

	/** Set true when RuneLite fires {@link ClientShutdown}: {@link #onClientShutdown} has then submitted any
	 *  freeze-log, so a {@link #shutDown()} after it submits nothing more. Volatile: ClientShutdown and
	 *  shutDown() can run on different threads. */
	private volatile boolean shutdownSeen = false;

	public String getClientUniqueId()
	{
		return identitySvc.getClientUniqueId();
	}

	/** True the moment {@link GameState#LOGGED_IN} fires, i.e. eagerly —
	 *  before the 10-tick name-resolve delay that gates {@code
	 *  profileGate.onLogin()}. Lobby UI uses this to distinguish
	 *  "truly logged out (Please log into the game)" from "logged in
	 *  but the player name + match counts haven't resolved yet
	 *  (Loading\u2026)" so the brief startup window doesn't flash a
	 *  misleading "log in" prompt to a user who already did. */
	public boolean isInGame()
	{
		if (client == null) return false;
		try
		{
			return client.getGameState() == GameState.LOGGED_IN;
		}
		catch (Exception e)
		{
			return false;
		}
	}

	// Accessor for Dashboard to get local player name for debug logs
	public String getLocalName()
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
			return null;
		}
	}

	@Override
	protected void startUp() throws Exception
	{
		// Identity must be loaded BEFORE the socket service starts so
		// the reconnect-replay handler has a UUID to send with
		// lobby/join. The socket itself only opens on LOGGED_IN below
		// — but lobbySvc.start() subscribes to push
		// events and the connect-listener now so they're ready when
		// the first frame arrives.
		identitySvc.load(this::getPluginDirectory);
		eventBus.register(scenePlayers);
		clientThread.invokeLater(() -> scenePlayers.seed(client));
		lobbySvc.start();
		// Plan 10 step 7: the queue + tournament transports subscribe to
		// their pushes (and the reconnect re-sync) the same way. The session
		// tracker feeds the bucket pin + the opponent outline; the auto-switch
		// listener flips the bucket when a round opens.
		queueSvc.start();
		tourneySvc.start();
		tourneySvc.addListener(oppTracker);
		tourneySvc.addListener(bucketSwitch);
		// Wire the anti-smurf gate's identity suppliers BEFORE the
		// dashboard ctor so the first listener fire (still empty counts)
		// triggers the panel's "Loading your match count…" state. The
		// suppliers resolve at refresh time, not now, so it's fine that
		// the local player isn't loaded yet.
		profileGate.configure(this::getLocalName);
		dashPanel = new Dashboard(this, pvpApi, discordLogin,
			lobbySvc, profileGate, lobbyPrefs);
		// Plan 10 step 7: queue section + Tournaments sub-tab, per the two
		// config flags; the in-combat probe drives tournament/in_combat.
		applyPlan10Services();
		dashPanel.setTournamentInCombatProvider(fightMonitor::isInCombat);

		navButton = NavigationButton.builder()
			.tooltip("PvP Leaderboard")
			.icon(PANEL_ICON)
			.priority(5)
			.panel(dashPanel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Register overlay. Guice injects every @Inject field (none is
		// @Nullable) or the plugin does not load, so none is null here.
		overlayManager.add(rankOverlay);

		// Match-found popup overlay — drawn the moment a matchmaking
		// fight locks in (lobby/fight_proposed) so the user notices
		// even when they're not watching the sidepanel. Config-gated
		// internally.
		overlayManager.add(matchPopup);
		dashPanel.setMatchFoundNotifier(matchPopup::showMatch);

		// LMS plugin-disable ban warning popup. Its mouse adapter is
		// registered so its OK button is clickable; the dismiss callback
		// clears the persisted pending-warning marker so it shows exactly
		// once per offense (the overlay logs and swallows anything the
		// callback throws). Not config-gated — a ban warning must always
		// surface.
		overlayManager.add(warnOverlay);
		warnOverlay.setOnDismiss(() -> configManager.unsetConfiguration(
			FightMonitor.CONFIG_GROUP, FightMonitor.LMS_WARN_KEY));
		mouseManager.registerMouseListener(warnOverlay.mouse);

		// The outline around the current tournament opponent: the tracker
		// answers the canonical keys of every name the opponent is logged in
		// with while tournament/opponent_highlight is in force and null otherwise.
		oppOverlay.setOpponentKeysSupplier(oppTracker::getHighlightedOpponentKeys);
		overlayManager.add(oppOverlay);
		fightMonitor.setCombatSink(this::onOwnHit);
		// Plan 10 F.2 (AS-72): while the player is in a RUNNING tournament the
		// fight auto-switch lands on the Tournament bucket instead of the
		// fight's style; once they leave the bracket the next fight switches
		// back like every bucket.
		fightMonitor.setTournamentBucketPin(() -> config.autoSwitchTournamentBucket() && oppTracker.isPlaying());

		// One-shot startup diagnostic — pins the in-combat suppression
		// config toggle state + whether each overlay's provider got
		// wired. The popup-mid-combat bug class has been recurrent
		// (2026-05-25 series of QA cycles); without this log we can't
		// disambiguate "user toggled it off" from "wiring race" from
		// "FightMonitor.isInCombat returned false at popup time" when
		// reading a captured client log. Pairs with the per-popup
		// DEBUG lines emitted by the overlay {@code showInvite} /
		// {@code showMatch} entry points and the rate-limited
		// {@code FightMonitor.isInCombat} decision log.

		// BOARD row 33: the movable kill-streak box. Config-gated inside the
		// overlay. Each fight feeds the streak tracker, then the side panel's
		// streak line; the panel exists from here on and is never nulled.
		streakBoxOnOnce();
		overlayManager.add(winStreakOverlay);
		dashPanel.setWinStreakTracker(winStreakTracker);
		fightMonitor.setStreakSink((bucketKey, result) ->
		{
			winStreakTracker.onFight(bucketKey, result);
			dashPanel.refreshLine(bucketKey);
		});
		// The side panel's own rating rows follow the post-fight profile refresh.
		fightMonitor.setProfileRefreshSink(dashPanel::refreshOwn);

		// Init menu handler with RankOverlay
		menuHandler.init(dashPanel, navButton);

		// Init fight monitor with RankOverlay for MMR notifications
		fightMonitor.init(rankOverlay);

		// If player is already logged in (plugin was toggled off/on), resume heartbeats
		if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null)
		{
			String self = client.getLocalPlayer().getName();
			if (self != null && !self.trim().isEmpty()
				&& config.showRankToOthers()
				&& !whitelistService.isHeartbeatActive())
			{
				whitelistService.onLogin(self);
			}
			// Resume the rank-overlay membership feed (names-only snapshot +
			// delta). Self-gates on enableWhitelistRanks(); independent of
			// showRankToOthers (that only governs whether OTHERS see us).
			memberFeed.onLogin();
			// Resume the socket too — the player is past $connect's
			// prerequisites (UUID stamped, MMR snapshot taken) so the
			// server already has its trusted dict for SMURF_GUARD.
			// Pass the active in-game name so the server's conn row
			// pins to the current session instead of falling back to
			// the alphabetical default from the MMR row's player_names.
			connectSocket(self);
			// Player is logged in already (plugin toggled off/on while
			// in-game) — kick the gate so the dashboard shows real
			// counts from the get-go instead of "Not yet refreshed".
			profileGate.onLogin();
			// If the plugin was just re-enabled after a mid-fight disable
			// offense, the pending-warning marker is set — surface it now.
			maybeWarn();
			// Same for a pending freeze-log MMR delta: replay it as the
			// normal -XX.XX MMR overlay now the session is back.
			fightMonitor.showLmsMmr();
		}

		startCheck();
	}

	@Override
	protected void shutDown() throws Exception
	{
		// FIRST, before any teardown: the plugin turned off mid-fight in an
		// LMS arena is a plugin_disabled freeze-log. Not waited for: the
		// submit runs on RuneLite's shared OkHttpClient and finishes on its
		// own. A client exit was already submitted by onClientShutdown.
		if (!shutdownSeen)
		{
			try
			{
				fightMonitor.handleFreeze("plugin_disabled");
			}
			catch (Exception e)
			{
			}
		}

		stopCheck();
		menuHandler.shutdown();
		overlayManager.remove(rankOverlay);
		eventBus.unregister(scenePlayers);
		clientThread.invokeLater(scenePlayers::clear);
		overlayManager.remove(matchPopup);
		matchPopup.clear();
		mouseManager.unregisterMouseListener(warnOverlay.mouse);
		overlayManager.remove(warnOverlay);
		warnOverlay.clear();
		// Plan 10 step 7: drop the opponent outline, forget the tournament
		// session (bucket pin + highlight) and stop the sub-tab's ticker.
		overlayManager.remove(oppOverlay);
		fightMonitor.setCombatSink(null);
		oppTracker.clear();
		// Null only when a start-up threw before the panel existed (RuneLite
		// then stops the plugin too).
		if (dashPanel != null) dashPanel.shutdown();
		clientToolbar.removeNavigation(navButton);
		whitelistService.onLogout();
		memberFeed.onLogout();
		// Tear down the lobby service's periodic rank-retry task so it
		// doesn't keep firing on RuneLite's shared scheduler after the
		// plugin is gone. Best-effort: never let teardown abort the
		// rest of the shutdown sequence.
		try { lobbySvc.stop(); } catch (Exception ignored) { /* hard shutdown */ }
		overlayManager.remove(winStreakOverlay);
		winStreakOverlay.clear();
		fightMonitor.setStreakSink(null);
		fightMonitor.setProfileRefreshSink(null);
		fightMonitor.cancelPending();
		winStreakTracker.clear();
		// Hard-close the socket and forbid future reconnects — the
		// plugin is going away. SocketMgr.shutdown() is
		// idempotent + safe to call without ever having connected.
		socketMgr.shutdown();
		// Cancel the gate's hourly auto-refresh + clear cached counts
		// so a re-toggle of the plugin starts fresh.
		profileGate.onLogout();
	}

	/** RuneLite fires this when the whole client is closing, and never calls
	 *  {@link #shutDown()} after it. A client exit mid-fight in an LMS arena is
	 *  a {@code logout} freeze-log; RuneLite waits for its submit before
	 *  exiting. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		shutdownSeen = true;
		try
		{
			CompletableFuture<Boolean> freezeLog = fightMonitor.handleFreeze("logout");
			if (freezeLog != null) event.waitFor(freezeLog);
		}
		catch (Exception e)
		{
		}
	}

	/** Turns "Always show kill streak box" on once per profile; a later choice is kept. */
	private void streakBoxOnOnce()
	{
		if (configManager.getConfiguration(FightMonitor.CONFIG_GROUP, "killStreakBoxOnDone") == null)
		{
			configManager.setConfiguration(FightMonitor.CONFIG_GROUP, "showKillStreakBox", "true");
			configManager.setConfiguration(FightMonitor.CONFIG_GROUP, "killStreakBoxOnDone", "true");
		}
	}

	/** Show the LMS plugin-disable ban warning if the persisted marker is
	 *  set. The marker is cleared only when the popup is dismissed (OK
	 *  click or the 10 s window elapses), so it shows exactly once per
	 *  offense even across client restarts. */
	private void maybeWarn()
	{
		try
		{
			String pending = configManager.getConfiguration(
				FightMonitor.CONFIG_GROUP,
				FightMonitor.LMS_WARN_KEY);
			if ("true".equals(pending))
			{
				warnOverlay.showWarning();
			}
		}
		catch (Exception e)
		{
		}
	}

	/** Plan 10 step 7: hands the queue + tournament transports to the
	 *  dashboard according to the two config flags — the inert queue service
	 *  and no tournament service ({@code null}) when a flag is off (the gate
	 *  hides the queue block, the Tournaments sub-tab greys). EDT-only: the
	 *  dashboard mutates Swing. */
	private void applyPlan10Services()
	{
		dashPanel.setQueue(config.enableQuickMatch() ? queueSvc : new NoOpQueue());
		dashPanel.setTournamentService(config.enableTournaments() ? tourneySvc : null);
	}

	/** Plan 10 F.2: the auto-switch's action — pins the side panel + overlay
	 *  to the Tournament bucket when a round opens with an opponent. The
	 *  switch back happens at the next ordinary fight through
	 *  {@link FightMonitor}, like every bucket (AS-72). Already on Tournament,
	 *  RuneLite's setConfiguration stores nothing and fires no ConfigChanged. */
	private void pinBucket()
	{
		try
		{
			configManager.setConfiguration("PvPLeaderboard", "rankBucket", PvPLeaderboardConfig.RankBucket.TOURNAMENT.name());
		}
		catch (Exception e)
		{
		}
	}

	private void onOwnHit(String playerName, int world)
	{
		if (!oppTracker.isAwaiting()) return;
		SwingUtilities.invokeLater(() -> oppTracker.onCombatWith(playerName, world));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		try
		{
			if (event == null || !"PvPLeaderboard".equals(event.getGroup()) || event.getKey() == null) return;
			switch (event.getKey())
			{
				case "enablePvpLookupMenu":
					menuHandler.refreshMenu();
					break;
				// Ensure self rank refreshes when bucket changes
				case "rankBucket":
					rankOverlay.scheduleSelf();
					break;
				// "Show your rank to others" toggled: the opt-out flag is
				// captured server-side at $connect (like is_mod), so reconnect
				// the socket to re-send the show_rank flag promptly instead of
				// waiting for the next natural reconnect.
				case "showRankToOthers":
					socketMgr.reconnectNow();
					break;
				// Plan 10 step 7: the queue / tournaments flags re-wire the
				// dashboard (inert services when off). Marshalled to the EDT —
				// config events arrive off it and the setters touch Swing.
				case "enableQuickMatch":
				case "enableTournaments":
					SwingUtilities.invokeLater(this::applyPlan10Services);
					break;
				// "Display other players ranks" toggled: start/stop the
				// membership feed sync (it self-gates, but stop releases the
				// 10-min poll immediately when disabled).
				case "enableWhitelistRanks":
					if (config.enableWhitelistRanks()) memberFeed.onLogin();
					else memberFeed.onLogout();
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
		menuHandler.onMenuClick(event);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged stateChange)
	{
		try
		{
			switch (stateChange.getGameState())
			{
				case LOGGED_IN:
					// Use tick-based scheduling: wait 10 ticks (approx 6.0s) for player to fully load
					// This handles the delay between LOGGED_IN state and player actually being ready
					selfRankWait = 10;
					heartbeatDue = true;
					// Eagerly refresh the lobby gate notice so the user
					// sees "Loading…" immediately instead of the
					// pre-login "Please log into the game" copy during
					// the 10-tick name-resolve window. The gate listener
					// itself only fires after profileGate.onLogin()
					// (called once the name resolves), so without this
					// poke the "Loading…" phase would be invisible.
					dashPanel.syncLogins();
					// Open the socket immediately — UUID is available on
					// startUp() via identitySvc and the server
					// resolves the trusted MMR snapshot at $connect time
					// (no need to wait for the player to be fully loaded
					// in-game like the heartbeat path does).
					//
					// Only connect here if the local-player name is ALREADY
					// resolved. LOGGED_IN can fire a few ticks before
					// client.getLocalPlayer() populates; connecting with
					// null opens the socket without a {@code &name=}
					// parameter, the server falls back to
					// sorted(player_names)[0] (typically the wrong alt), and
					// the 10-tick Init-complete branch below then has to
					// close and reopen with the real name (the
					// double-reconnect tax surfaced in the 21:01:51-57 QA
					// log). Deferring to Init-complete when the name isn't
					// ready collapses the two-reconnect cycle into one.
					//
					// startUp() already connects if the player was logged in
					// before the plugin toggled on, so the "plugin reload
					// mid-session" path still gets the socket up without
					// waiting for a LOGGED_IN event.
					String selfName = getLocalName();
					if (selfName != null && !selfName.trim().isEmpty()) connectSocket(selfName);
					// Surface a pending LMS plugin-disable ban warning now
					// that the viewport is available again (offense happened
					// on a prior session that was disabled mid-fight).
					maybeWarn();
					// Replay the doubled freeze-log MMR loss as the normal
					// -XX.XX MMR overlay: a freeze-log submitted at last
					// logout couldn't poll for its delta, so we deferred the
					// notification to this login.
					fightMonitor.showLmsMmr();
					break;
				case LOGIN_SCREEN:
					// LMS freeze-log detection MUST run before resetFight
					// wipes the active fights: a logout / connection-lost
					// mid-fight inside an LMS arena is submitted as a doubled
					// loss (reason=logout, no ban).
					fightMonitor.handleFreeze("logout");
					// Fully clear fight state on logout
					fightMonitor.resetFight();
					kitStore.clearSession();
					// Symmetric refresh: GameState dropped to LOGIN_SCREEN,
					// flip the lobby gate notice back to the pre-login
					// copy without waiting for profileGate.onLogout()
					// to broadcast (it does fire, but this poke makes
					// the transition feel snappy).
					dashPanel.syncLogins();
					// Stop heartbeats
					whitelistService.onLogout();
					memberFeed.onLogout();
					heartbeatDue = false;
					// Close the socket (CLOSE_GOING_AWAY: intentional
					// logout, no reconnect).
					socketMgr.disconnect();
					// Stop the hourly auto-refresh + clear cached counts so
					// the next login (potentially a different character)
					// doesn't see stale stats.
					profileGate.onLogout();
					// End the init session — this is the boundary a world
					// hop deliberately doesn't cross, so logging back in
					// re-runs the full init while hopping doesn't.
					initTracker.onLogout();
					// Plan 10: forget the tournament session (bucket pin +
					// opponent outline); tournament/status re-syncs it on the
					// next login.
					oppTracker.clear();
					break;
				case HOPPING:
				case LOADING:
					rankOverlay.onWorldHop();
					// Schedule self rank overlay refresh only - don't refresh panel
					selfRankWait = 8;
			}
		}
		catch (Exception e)
		{
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		try
		{
			// Delegate logic to FightMonitor
			fightMonitor.handleTick(tick);

			// Handle pending init after login (tick-based delay for player to be ready)
			if (selfRankWait > 0 && --selfRankWait == 0)
			{
				selfRankWait = -1;
				String self = getLocalName();
				if (self != null && !self.trim().isEmpty())
				{
					// Everything from here to the fullInit branch is
					// safe to repeat: the socket connect no-ops on an
					// unchanged (uuid, name) tuple, and the overlay's
					// self-rank genuinely does need re-fetching after
					// onWorldHop() cleared it.
					boolean fullInit = initTracker.shouldInit(self);

					// Re-issue connect(uuid, name) now that the
					// local player name has resolved. SocketMgr
					// no-ops if the (uuid, name) tuple matches the
					// current connection; otherwise it tears down
					// and reopens with the correct name in the
					// query string. This is what pins the server's
					// conn row to the active in-game character instead
					// of the alphabetical fallback.
					connectSocket(self);

					// Schedule self rank refresh for overlay. Outside
					// the fullInit guard on purpose — a world hop
					// runs onWorldHop(), so the
					// overlay has nothing to draw until this reruns.
					rankOverlay.scheduleSelf();

					// Everything below re-fetches data that can't
					// change within a session, so it runs on a
					// genuine login only — not on the region loads
					// and world hops that arm the same countdown.
					if (fullInit)
					{
						// Load dashboard data
						dashPanel.loadIfIdle(self);

						// Start heartbeat (fires now, then every 5 mins)
						if (heartbeatDue)
						{
							whitelistService.onLogin(self);
							heartbeatDue = false;
						}

						// Start the rank-overlay membership feed (idempotent;
						// self-gates on enableWhitelistRanks()).
						memberFeed.onLogin();

						// Kick the anti-smurf gate now that the local
						// player name resolves. We delay this 10 ticks
						// instead of firing on LOGGED_IN directly so
						// the name supplier (client.getLocalPlayer().
						// getName()) has actually populated — firing
						// at LOGGED_IN would bail with the empty-name
						// branch.
						profileGate.onLogin();
					}
				}
			}
		}
		catch (Exception e)
		{
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied hitsplatApplied)
	{
		fightMonitor.handleHit(hitsplatApplied);
	}

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath)
	{
		fightMonitor.handleDeath(actorDeath);
	}

	@Provides
	PvPLeaderboardConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvPLeaderboardConfig.class);
	}

	private void startCheck()
	{
		final var tracker = new GearTracker(tourneySvc::status);
		final var reporter = new GearReporter(tourneySvc, tracker,
			new KitRouter(kitStore, kitReader, gearWatcher, arenaLocator),
			new GearMatcher(ItemVariationMapping::map, gearWatcher::isRune), fightMonitor::isInCombat,
			System::currentTimeMillis, () -> ThreadLocalRandom.current().nextDouble());
		gearTracker = tracker;
		gearReporter = reporter;
		tourneySvc.addListener(tracker);
		tourneySvc.addListener(reporter);
		gearWatcher.setActive(() ->
		{
			GearTracker.GearEvent e = tracker.current();
			return e != null && !e.arena;
		});
		kitReader.setActive(() ->
		{
			GearTracker.GearEvent e = tracker.current();
			return e != null && e.arena;
		});
		gearSearch.setMissing(this::missingGear);
		gearParts().forEach(eventBus::register);
		gearOverlay.setView(reporter::view);
		overlayManager.add(gearOverlay);
		var card = new GearCard(this::applyIcon, new GearCard.Actions()
		{
			@Override
			public void findItem(int itemId, List<Integer> altIds, String name)
			{
				gearSearch.onItemClicked(itemId, altIds, name);
			}

			@Override
			public void copySetup(GearKit kit)
			{
				copyText(GearCapture.toCatalog(kit));
			}

			@Override
			public void showMissing(List<Integer> itemIds)
			{
				gearSearch.showMissing(itemIds);
			}

			@Override
			public void openPanel()
			{
				openTourneys();
			}
		}, () -> config.gearAutoOpenPanel());
		reporter.addViewListener(card::render);
		dashPanel.setTournamentGearCard(card);
		// A Swing Timer repeats by default.
		gearTicker = new Timer(1000, e -> reporter.tick());
		gearTicker.start();
	}

	/** The kit check's event subscribers, registered and unregistered in this order. */
	private List<Object> gearParts()
	{
		return List.of(kitReader, gearWatcher, arenaLocator, gearSearch, gearOverlay);
	}

	private void stopCheck()
	{
		if (gearTicker != null) gearTicker.stop();
		gearTicker = null;
		// Both null after a start-up that threw first: removing null is a no-op.
		tourneySvc.removeListener(gearReporter);
		tourneySvc.removeListener(gearTracker);
		gearParts().forEach(eventBus::unregister);
		overlayManager.remove(gearOverlay);
		gearOverlay.setView(null);
		kitReader.setActive(null);
		gearWatcher.setActive(null);
		gearSearch.setMissing(null);
		// Null only when a start-up threw before the panel existed.
		if (dashPanel != null) dashPanel.setTournamentGearCard(null);
		kitStore.clearSession();
		gearReporter = null;
		gearTracker = null;
	}

	private Collection<Integer> missingGear()
	{
		GearReporter reporter = gearReporter;
		if (reporter == null) return Collections.emptyList();
		GearReporter.View v = reporter.view();
		return v.event != null && !v.event.arena ? v.missingIds() : Collections.<Integer>emptyList();
	}

	private void applyIcon(JLabel label, int itemId, int qty, boolean stackable)
	{
		try
		{
			AsyncBufferedImage image = itemManager.getImage(itemId, qty, stackable);
			if (image != null) image.addTo(label);
		}
		catch (RuntimeException e)
		{
		}
	}

	private void copyText(String text)
	{
		if (text == null) return;
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (RuntimeException e)
		{
		}
	}

	/** Opens (or keeps) the socket as {@code name}, once the client UUID is known. */
	private void connectSocket(String name)
	{
		String uuid = getClientUniqueId();
		if (uuid != null) socketMgr.connect(uuid, name);
	}

	/** The gear card's action; the card exists only after start-up set the
	 *  button and the panel, which are never nulled. */
	private void openTourneys()
	{
		if (!config.enableTournaments()) return;
		try
		{
			clientToolbar.openPanel(navButton);
		}
		catch (RuntimeException | AssertionError e)
		{
		}
		dashPanel.showTourneys();
	}
}
