package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.cache.UserStatsCache;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.config.StreakBucket;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.service.WinLossPeak;
import com.pvp.leaderboard.service.WinStreakTracker;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Singleton
public class WinStreakOverlay extends OverlayPanel
{
	/** Minimum gap between two cache peeks (a map lookup + a key
	 *  canonicalisation): the render loop runs ~50 times a second, the
	 *  profile changes a few times a session. */
	static final long PROFILE_PEEK_INTERVAL_MS = 1_000L;
	static final int BORDER_PX = 2;
	static final int GAP_PX = 1;
	private static final int SHADOW_PX = 1;

	private final Client client;
	private final PvPLeaderboardConfig config;
	private final PvPDataService pvpDataService;
	private final WinStreakTracker tracker;
	private final LongSupplier nowMs;

	/** Wired by the plugin to {@code FightMonitor::getAutoSwitchTarget}. */
	private volatile Supplier<PvPLeaderboardConfig.RankBucket> autoSwitchTarget = () -> null;

	private volatile BooleanSupplier insideFfaPortal = () -> false;

	// ---- profile-peek state: client thread only ----
	private String peekedSelf;
	private long nextPeekAtMs;
	private long peekedProfileTs = Long.MIN_VALUE;
	private WinLossPeak winLossPeak;
	private volatile List<String> lastLines = Collections.emptyList();

	@Inject
	public WinStreakOverlay(Client client, PvPLeaderboardConfig config, PvPDataService pvpDataService,
		WinStreakTracker tracker)
	{
		this(client, config, pvpDataService, tracker, System::currentTimeMillis);
	}

	WinStreakOverlay(Client client, PvPLeaderboardConfig config, PvPDataService pvpDataService,
		WinStreakTracker tracker, LongSupplier nowMs)
	{
		this.client = client;
		this.config = config;
		this.pvpDataService = pvpDataService;
		this.tracker = tracker;
		this.nowMs = nowMs;
		// A positioned (non-DYNAMIC) overlay is movable + snappable through
		// RuneLite's standard Alt+drag; TOP_LEFT is only where it starts.
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(Overlay.PRIORITY_LOW);
		setResizable(false);
		panelComponent.setBorder(new Rectangle(BORDER_PX, BORDER_PX, BORDER_PX, BORDER_PX));
		panelComponent.setGap(new Point(0, GAP_PX));
	}

	public void setAutoSwitchTargetSupplier(Supplier<PvPLeaderboardConfig.RankBucket> supplier)
	{
		this.autoSwitchTarget = supplier == null ? () -> null : supplier;
	}

	public void setInsideFfaPortalSupplier(BooleanSupplier supplier)
	{
		this.insideFfaPortal = supplier == null ? () -> false : supplier;
	}

	public static boolean isShown(boolean alwaysShow, boolean showInFfaPortal, boolean insideFfaPortal)
	{
		return alwaysShow || (showInFfaPortal && insideFfaPortal);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return null;
	}

	/** Forget the peeked profile and the last frame (plugin shutdown) so a
	 *  re-enable re-reads the cache at once. */
	public void clear()
	{
		peekedSelf = null;
		nextPeekAtMs = 0L;
		peekedProfileTs = Long.MIN_VALUE;
		winLossPeak = null;
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
			if (!isShown(config.showKillStreakBox(), config.killStreakBoxInFfaPortal(), insideFfaPortal.getAsBoolean())) return null;
			String self = localPlayerName();
			if (self == null) return null;
			WinLossPeak profile = profileFor(self);
			if (!tracker.isKnown()) return null;
			List<String> lines = linesFor(resolveBucket(), tracker, profile, config.killStreakBoxShowLongest(),
				config.killStreakBoxShowWinLoss(), config.killStreakBoxShowPeak());
			Font font = FontManager.getRunescapeSmallFont();
			FontMetrics fm = graphics.getFontMetrics(font);
			int widest = 0;
			for (String line : lines)
			{
				panelComponent.getChildren().add(LineComponent.builder().left(line).leftFont(font).rightFont(font).build());
				widest = Math.max(widest, fm.stringWidth(line));
			}
			panelComponent.setPreferredSize(new Dimension(widest + 2 * BORDER_PX + SHADOW_PX, 0));
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

	static List<String> linesFor(StreakBucket bucket, WinStreakTracker tracker, WinLossPeak profile,
		boolean showLongest, boolean showWinLoss, boolean showPeak)
	{
		String key = bucket.bucketKey;
		List<String> lines = new ArrayList<>(4);
		lines.add(bucket.label + " Kill Streak: " + tracker.text(key));
		if (showLongest) lines.add("Longest: " + tracker.longest(key));
		if (showWinLoss && profile != null) lines.add("W/L: " + profile.wins(key) + "-" + profile.losses(key));
		String peak = showPeak && profile != null ? profile.peak(key) : null;
		if (peak != null) lines.add("Peak: " + peak);
		return lines;
	}

	WinLossPeak profileFor(String self)
	{
		if (!self.equals(peekedSelf))
		{
			peekedSelf = self;
			nextPeekAtMs = 0L;
			peekedProfileTs = Long.MIN_VALUE;
			winLossPeak = null;
		}
		long now = nowMs.getAsLong();
		if (now >= nextPeekAtMs)
		{
			nextPeekAtMs = now + PROFILE_PEEK_INTERVAL_MS;
			UserStatsCache cached = pvpDataService.peekUserProfile(self);
			if (cached != null && cached.getTimestamp() != peekedProfileTs)
			{
				peekedProfileTs = cached.getTimestamp();
				tracker.observeProfile(cached);
				winLossPeak = WinLossPeak.fromProfile(cached.getStats());
			}
		}
		return winLossPeak;
	}

	private String localPlayerName()
	{
		Player local = client.getLocalPlayer();
		if (local == null) return null;
		String name = local.getName();
		return name == null || name.trim().isEmpty() ? null : name;
	}
}
