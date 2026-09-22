package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.cache.UserStatsCache;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.config.StreakBucket;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.service.WinStreaks;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The movable kill-streak box (BOARD row 33, 2026-09-21): one line reading
 * {@code "<NH|Veng|Multi|DMM|Event> Current Kill Streak: N"}, plus
 * {@code "Longest: M"} when the config asks for it. A standard RuneLite
 * {@link OverlayPanel}: Alt+drag moves it and RuneLite persists the position.
 *
 * <p><b>Off by default</b> ({@code showKillStreakBox}); while off a frame
 * costs one config read and draws nothing.
 *
 * <p><b>Which style:</b> with {@code killStreakBoxAutoSwitch} (default on) the
 * box follows {@code FightMonitor.getAutoSwitchTarget()} — the same
 * resolution the leaderboard auto-switch and the Plan 10 tournament pin use,
 * wired by the plugin as a supplier — so "the style you are in" has one
 * source of truth. Before the first fight of a session it falls back to the
 * leaderboard bucket the config holds (the auto-switch persisted it last
 * session), then NH. With auto-switch off it shows
 * {@code killStreakBoxBucket} and ignores the fights.
 *
 * <p><b>Which numbers:</b> the cached /user profile the plugin already
 * fetches for the local player (login init, the 5-s post-fight tier refresh,
 * the lobby gate), peeked through {@link PvPDataService#peekUserProfile} at
 * most once per {@link #PROFILE_PEEK_INTERVAL_MS} and re-parsed only when the
 * cache entry changes. The box never fetches. Until a profile is cached
 * nothing is drawn — a stale 0 at login would read as a lost streak.
 *
 * <p>Renders on the client thread only; a throwing supplier or config never
 * reaches RuneLite's renderer.
 */
@Singleton
public class WinStreakOverlay extends OverlayPanel
{
	/** Minimum gap between two cache peeks (a map lookup + a key
	 *  canonicalisation): the render loop runs ~50 times a second, the
	 *  profile changes a few times a session. */
	static final long PROFILE_PEEK_INTERVAL_MS = 1_000L;

	private final Client client;
	private final PvPLeaderboardConfig config;
	private final PvPDataService pvpDataService;
	private final LongSupplier nowMs;

	/** Wired by the plugin to {@code FightMonitor::getAutoSwitchTarget}. */
	private volatile Supplier<PvPLeaderboardConfig.RankBucket> autoSwitchTarget = () -> null;

	// ---- profile-peek state: client thread only ----
	private String peekedSelf;
	private long nextPeekAtMs;
	private long peekedProfileTs = Long.MIN_VALUE;
	private WinStreaks streaks;
	private volatile List<String> lastLines = Collections.emptyList();

	@Inject
	public WinStreakOverlay(Client client, PvPLeaderboardConfig config, PvPDataService pvpDataService)
	{
		this(client, config, pvpDataService, System::currentTimeMillis);
	}

	WinStreakOverlay(Client client, PvPLeaderboardConfig config, PvPDataService pvpDataService, LongSupplier nowMs)
	{
		this.client = client;
		this.config = config;
		this.pvpDataService = pvpDataService;
		this.nowMs = nowMs;
		// A positioned (non-DYNAMIC) overlay is movable + snappable through
		// RuneLite's standard Alt+drag; TOP_LEFT is only where it starts.
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(Overlay.PRIORITY_LOW);
	}

	public void setAutoSwitchTargetSupplier(Supplier<PvPLeaderboardConfig.RankBucket> supplier)
	{
		this.autoSwitchTarget = supplier == null ? () -> null : supplier;
	}

	/** Forget the peeked profile and the last frame (plugin shutdown) so a
	 *  re-enable re-reads the cache at once. */
	public void clear()
	{
		peekedSelf = null;
		nextPeekAtMs = 0L;
		peekedProfileTs = Long.MIN_VALUE;
		streaks = null;
		lastLines = Collections.emptyList();
		panelComponent.getChildren().clear();
	}

	/** The lines the last frame drew (empty when it drew nothing). */
	List<String> lastLines()
	{
		return lastLines;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		try
		{
			panelComponent.getChildren().clear();
			lastLines = Collections.emptyList();
			if (!config.showKillStreakBox()) return null;
			String self = localPlayerName();
			if (self == null) return null;
			WinStreaks known = streaksFor(self);
			if (known == null) return null;
			List<String> lines = linesFor(resolveBucket(), known, config.killStreakBoxShowLongest());
			for (String line : lines)
			{
				panelComponent.getChildren().add(LineComponent.builder().left(line).build());
			}
			lastLines = lines;
			return super.render(graphics);
		}
		catch (Exception ignored)
		{
			// A transient client / config state must never feed RuneLite's renderer an exception per frame.
			panelComponent.getChildren().clear();
			lastLines = Collections.emptyList();
			return null;
		}
	}

	/** The style to show: the pinned one while auto-switch is off; else the
	 *  auto-switch target (Tournament reads Event), the leaderboard bucket the
	 *  config holds, then NH. */
	StreakBucket resolveBucket()
	{
		if (!config.killStreakBoxAutoSwitch())
		{
			StreakBucket pinned = config.killStreakBoxBucket();
			return pinned == null ? StreakBucket.NH : pinned;
		}
		StreakBucket live = StreakBucket.fromRankBucket(autoSwitchTarget.get());
		if (live != null) return live;
		StreakBucket persisted = StreakBucket.fromRankBucket(config.rankBucket());
		return persisted == null ? StreakBucket.NH : persisted;
	}

	/** {@code "<label> Current Kill Streak: N"} and, when asked, {@code "Longest: M"}. */
	static List<String> linesFor(StreakBucket bucket, WinStreaks streaks, boolean showLongest)
	{
		List<String> lines = new ArrayList<>(2);
		lines.add(bucket.label + " Current Kill Streak: " + streaks.current(bucket.bucketKey));
		if (showLongest) lines.add("Longest: " + streaks.best(bucket.bucketKey));
		return lines;
	}

	/** The parsed streaks for {@code self}: re-peeked from the profile cache at
	 *  most once per {@link #PROFILE_PEEK_INTERVAL_MS} (at once for a new
	 *  character) and re-parsed only when the cache entry changed;
	 *  {@code null} until a profile has been cached. */
	WinStreaks streaksFor(String self)
	{
		if (!self.equals(peekedSelf))
		{
			peekedSelf = self;
			nextPeekAtMs = 0L;
			peekedProfileTs = Long.MIN_VALUE;
			streaks = null;
		}
		long now = nowMs.getAsLong();
		if (now >= nextPeekAtMs)
		{
			nextPeekAtMs = now + PROFILE_PEEK_INTERVAL_MS;
			UserStatsCache cached = pvpDataService.peekUserProfile(self);
			if (cached != null && cached.getTimestamp() != peekedProfileTs)
			{
				streaks = WinStreaks.fromProfile(cached.getStats());
				peekedProfileTs = cached.getTimestamp();
			}
		}
		return streaks;
	}

	private String localPlayerName()
	{
		Player local = client.getLocalPlayer();
		if (local == null) return null;
		String name = local.getName();
		return name == null || name.trim().isEmpty() ? null : name;
	}
}
