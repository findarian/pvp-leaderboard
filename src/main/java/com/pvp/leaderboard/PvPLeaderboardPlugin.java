package com.pvp.leaderboard;

import com.google.inject.Provides;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.game.FightMonitor;
import com.pvp.leaderboard.game.MenuHandler;
import com.pvp.leaderboard.overlay.LobbyInviteNotificationOverlay;
import com.pvp.leaderboard.overlay.MatchFoundNotificationOverlay;
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
	private LobbyInviteNotificationOverlay lobbyInviteNotificationOverlay;

	@Inject
	private MatchFoundNotificationOverlay matchFoundNotificationOverlay;

	@Inject
	private EventBus eventBus;

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

	@Inject
	private com.pvp.leaderboard.service.socket.WebSocketManager webSocketManager;

	@Inject
	private com.pvp.leaderboard.lobby.WebSocketLobbyService webSocketLobbyService;

	@Inject
	private com.pvp.leaderboard.lobby.UserProfileLobbyJoinGate lobbyJoinGate;

	@Inject
	private com.pvp.leaderboard.lobby.LobbyPreferences lobbyPreferences;

	private DashboardPanel dashboardPanel;
	private NavigationButton navButton;
	private int pendingSelfRankLookupTicks = -1;
	private boolean pendingHeartbeatStart = false;

	public String getClientUniqueId()
	{
		return clientIdentityService.getClientUniqueId();
	}

	/** True the moment {@link GameState#LOGGED_IN} fires, i.e. eagerly —
	 *  before the 10-tick name-resolve delay that gates {@code
	 *  lobbyJoinGate.onLogin()}. Lobby UI uses this to distinguish
	 *  "truly logged out (Please log into the game)" from "logged in
	 *  but the player name + match counts haven't resolved yet
	 *  (Loading\u2026)" so the brief startup window doesn't flash a
	 *  misleading "log in" prompt to a user who already did. */
	public boolean isGameLoggedIn()
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
		// Identity must be loaded BEFORE the socket service starts so
		// the reconnect-replay handler has a UUID to send with
		// lobby/join. The socket itself only opens on LOGGED_IN below
		// — but webSocketLobbyService.start() subscribes to push
		// events and the connect-listener now so they're ready when
		// the first frame arrives.
		clientIdentityService.loadOrGenerateId();
		webSocketLobbyService.start();
		// Wire the anti-smurf gate's identity suppliers BEFORE the
		// dashboard ctor so the first listener fire (still empty counts)
		// triggers the panel's "Loading your match count…" state. The
		// suppliers resolve at refresh time, not now, so it's fine that
		// the local player isn't loaded yet.
		lobbyJoinGate.configure(this::getLocalPlayerName, this::getClientUniqueId);
		dashboardPanel = new DashboardPanel(this, pvpDataService, cognitoAuthService,
			webSocketLobbyService, lobbyJoinGate, lobbyPreferences);

		navButton = NavigationButton.builder()
			.tooltip("PvP Leaderboard")
			.icon(PANEL_ICON)
			.priority(5)
			.panel(dashboardPanel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Register overlay. Snapshot the injected field to a local so
		// Eclipse's null analysis can carry the null-check across both
		// calls (field reads otherwise lose narrowing between statements).
		final RankOverlay overlay = rankOverlay;
		if (overlay != null)
		{
			overlayManager.add(overlay);
			eventBus.register(overlay);
		}

		// Lobby invite popup overlay — drawn into the game viewport
		// when another player invites the user to a fight. Config-gated
		// inside the overlay itself; safe to register unconditionally.
		final LobbyInviteNotificationOverlay invitePopup = lobbyInviteNotificationOverlay;
		if (invitePopup != null)
		{
			overlayManager.add(invitePopup);
			// Wire the in-combat probe so the suppress-in-combat
			// config toggle has a signal source. FightMonitor::isInCombat
			// reads !activeFights.isEmpty() filtered by the
			// COMBAT_WINDOW_MS recency window — see FightMonitor for
			// the contract. Safe to call before fightMonitor.init() —
			// isInCombat short-circuits to false on an empty map.
			invitePopup.setInCombatProvider(fightMonitor::isInCombat);
			// Hand the overlay reference to the panel as a lambda so
			// the panel doesn't carry an Overlay-typed field (keeps
			// unit tests free of RuneLite-client dependencies).
			dashboardPanel.setLobbyInviteNotifier(invitePopup::showInvite);
		}

		// Match-found popup overlay — drawn the moment a matchmaking
		// fight locks in (lobby/fight_proposed) so the user notices
		// even when they're not watching the sidepanel. Same lifecycle
		// and config-gated-internally contract as the invite popup.
		final MatchFoundNotificationOverlay matchFoundPopup = matchFoundNotificationOverlay;
		if (matchFoundPopup != null)
		{
			overlayManager.add(matchFoundPopup);
			matchFoundPopup.setInCombatProvider(fightMonitor::isInCombat);
			dashboardPanel.setMatchFoundNotifier(matchFoundPopup::showMatch);
		}

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
		log.debug("[Plugin] popup suppression wired - suppressInCombat={} invitePopupRegistered={}"
				+ " inviteProviderWired={} matchPopupRegistered={} matchProviderWired={}",
			config.suppressNotificationsInCombat(),
			invitePopup != null,
			invitePopup != null,
			matchFoundPopup != null,
			matchFoundPopup != null);

		// Init menu handler with RankOverlay
		menuHandler.init(dashboardPanel, navButton);

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
				log.debug("[Plugin] Already logged in on startUp, resuming heartbeat for: {}", self);
				whitelistService.onLogin(self);
			}
			// Resume the socket too — the player is past $connect's
			// prerequisites (UUID stamped, MMR snapshot taken) so the
			// server already has its trusted dict for SMURF_GUARD.
			// Pass the active in-game name so the server's conn row
			// pins to the current session instead of falling back to
			// the alphabetical default from the MMR row's player_names.
			String uuid = getClientUniqueId();
			if (uuid != null) webSocketManager.connect(uuid, self);
			// Player is logged in already (plugin toggled off/on while
			// in-game) — kick the gate so the dashboard shows real
			// counts from the get-go instead of "Not yet refreshed".
			lobbyJoinGate.onLogin();
		}

		log.debug("PvP Leaderboard started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		menuHandler.shutdown();
		if (rankOverlay != null)
		{
			eventBus.unregister(rankOverlay);
			overlayManager.remove(rankOverlay);
		}
		if (lobbyInviteNotificationOverlay != null)
		{
			overlayManager.remove(lobbyInviteNotificationOverlay);
			lobbyInviteNotificationOverlay.clear();
		}
		if (matchFoundNotificationOverlay != null)
		{
			overlayManager.remove(matchFoundNotificationOverlay);
			matchFoundNotificationOverlay.clear();
		}
		clientToolbar.removeNavigation(navButton);
		whitelistService.onLogout();
		// Send `lobby/leave` BEFORE closing the socket so the server can
		// remove our OSRS-LobbyMembers row + broadcast a fresh roster
		// to peers. AWS API Gateway $disconnect doesn't currently
		// cascade to leave_lobby, so without
		// this explicit leave the row sticks for the 30-min sliding
		// TTL and other plugins keep seeing us as a "live" target.
		// Best-effort: the service no-ops if no join was ever made,
		// and any send-failure is swallowed so we still proceed to
		// the hard socket teardown below.
		try { webSocketLobbyService.leaveLobby(); } catch (Exception ignored) { /* hard shutdown */ }
		// Tear down the lobby service's periodic rank-retry task so it
		// doesn't keep firing on RuneLite's shared scheduler after the
		// plugin is gone. Best-effort: never let teardown abort the
		// rest of the shutdown sequence.
		try { webSocketLobbyService.stop(); } catch (Exception ignored) { /* hard shutdown */ }
		// Hard-close the socket and forbid future reconnects — the
		// plugin is going away. WebSocketManager.shutdown() is
		// idempotent + safe to call without ever having connected.
		webSocketManager.shutdown();
		// Cancel the gate's hourly auto-refresh + clear cached counts
		// so a re-toggle of the plugin starts fresh.
		lobbyJoinGate.onLogout();
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

			// Ensure self rank refreshes when bucket changes
			if ("rankBucket".equals(event.getKey()))
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
				// Eagerly refresh the lobby gate notice so the user
				// sees "Loading…" immediately instead of the
				// pre-login "Please log into the game" copy during
				// the 10-tick name-resolve window. The gate listener
				// itself only fires after lobbyJoinGate.onLogin()
				// (called once the name resolves), so without this
				// poke the "Loading…" phase would be invisible.
				if (dashboardPanel != null) dashboardPanel.refreshLobbyLoginView();
				// Open the socket immediately — UUID is available on
				// startUp() via clientIdentityService and the server
				// resolves the trusted MMR snapshot at $connect time
				// (no need to wait for the player to be fully loaded
				// in-game like the heartbeat path does).
				//
				// Only fire connect() here if the local-player name
				// is ALREADY resolved. LOGGED_IN can fire a few ticks
				// before client.getLocalPlayer() populates, in which
				// case getLocalPlayerName() returns null. If we
				// connect with null now, WebSocketManager opens the
				// socket without a {@code &name=} query parameter and
				// the server's $connect handler falls back to
				// sorted(player_names)[0] (typically the wrong alt) —
				// then the 10-tick Init-complete branch below fires a
				// SECOND connect() with the real name, which the
				// (uuid, name) no-op guard rejects and triggers a
				// name_change close + reopen. That's the
				// double-reconnect tax surfaced in the 21:01:51-57 QA
				// log. Deferring to Init-complete when the name isn't
				// ready collapses the two-reconnect cycle into one.
				//
				// startUp() already fires connect() if the player was
				// logged in before the plugin toggled on, so the
				// "plugin reload mid-session" path still gets the
				// socket up without waiting for a LOGGED_IN event.
				String uuid = getClientUniqueId();
				String selfName = getLocalPlayerName();
				if (uuid != null && selfName != null && !selfName.trim().isEmpty())
				{
					webSocketManager.connect(uuid, selfName);
				}
			}
			else if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
			{
				// Fully clear fight state on logout
				fightMonitor.resetFightState();
				// Symmetric refresh: GameState dropped to LOGIN_SCREEN,
				// flip the lobby gate notice back to the pre-login
				// copy without waiting for lobbyJoinGate.onLogout()
				// to broadcast (it does fire, but this poke makes
				// the transition feel snappy).
				if (dashboardPanel != null) dashboardPanel.refreshLobbyLoginView();
				// Stop heartbeats
				whitelistService.onLogout();
				pendingHeartbeatStart = false;
				// Send `lobby/leave` BEFORE the socket close so the
				// server removes our OSRS-LobbyMembers row + broadcasts
				// a fresh roster to peers. $disconnect alone doesn't
				// cascade to leave_lobby today, so without this our row
				// sticks for the 30-min sliding TTL and remote plugins
				// keep us in their roster as a dead invite target.
				//
				// preserveReplayState=true: the user is about to log
				// back in. The reconnect-on-LOGGED_IN path runs
				// replayJoinOnReconnect() from WebSocketLobbyService's
				// connect listener, which needs lastJoinArgs to be
				// non-null to actually re-issue lobby/join. Clearing it
				// here (the pre-Nov 2026 behaviour) caused the silent
				// "ghost-joined" state where the panel shows CARD_LOBBY
				// but the server has no OSRS-LobbyMembers row, surfacing
				// as the "we can't see each other / PEER_NOT_IN_LOBBY"
				// QA report.
				try { webSocketLobbyService.leaveLobby(true); } catch (Exception ignored) { /* clean logout best-effort */ }
				// Close the socket cleanly so the server frees the
				// OSRS-Connections row + cascades any outstanding
				// invites/sessions. CLOSE_GOING_AWAY tells the server
				// "intentional logout, no reconnect".
				webSocketManager.disconnect();
				// Stop the hourly auto-refresh + clear cached counts so
				// the next login (potentially a different character)
				// doesn't see stale stats.
				lobbyJoinGate.onLogout();
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

							// Re-issue connect(uuid, name) now that the
							// local player name has resolved. WebSocketManager
							// no-ops if the (uuid, name) tuple matches the
							// current connection; otherwise it tears down
							// and reopens with the correct name in the
							// query string. This is what pins the server's
							// conn row + lobby member row to the active
							// in-game character instead of the alphabetical
							// fallback.
							String uuid = getClientUniqueId();
							if (uuid != null) webSocketManager.connect(uuid, self);

							// Force refresh DMM worlds on every login so they're cached before any fights occur
							// This prevents a race condition where the first DMM fight would be
							// incorrectly classified because the async fetch hadn't completed yet
							pvpDataService.refreshDmmWorlds();
							
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

							// Kick the anti-smurf gate now that the local
							// player name resolves. We delay this 10 ticks
							// instead of firing on LOGGED_IN directly so
							// the name supplier (client.getLocalPlayer().
							// getName()) has actually populated — firing
							// at LOGGED_IN would bail with the empty-name
							// branch.
							lobbyJoinGate.onLogin();
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
