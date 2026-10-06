package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.cache.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.game.*;
import com.pvp.leaderboard.service.*;
import java.awt.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;
import java.util.List;
import java.awt.Point;

@Singleton
public class WinStreakOverlay extends OverlayPanel
{
	/** Minimum gap between two cache peeks (a map lookup + a key
	 *  canonicalisation): the render loop runs ~50 times a second, the
	 *  profile changes a few times a session. */
	static final long PEEK_MS = 1_000L;
	static final int BORDER_PX = 2;
	static final int GAP_PX = 1;
	private static final int SHADOW_PX = 1;

	private final Client client;
	private final PvPLeaderboardConfig config;
	private final PvpApi pvpApi;
	private final WinStreakTracker tracker;
	/** Its auto-switch target is the style shown; its FFA portal flag gates the box. */
	private final FightMonitor fightMonitor;
	private final LongSupplier nowMs;

	// ---- profile-peek state: client thread only ----
	private String peekedSelf;
	private long nextPeekAtMs;
	private long peekedTs = Long.MIN_VALUE;
	private WinLossPeak winLossPeak;

	@Inject
	public WinStreakOverlay(Client client, PvPLeaderboardConfig config, PvpApi pvpApi,
		WinStreakTracker tracker, FightMonitor fightMonitor)
	{
		this(client, config, pvpApi, tracker, fightMonitor, System::currentTimeMillis);
	}

	WinStreakOverlay(Client client, PvPLeaderboardConfig config, PvpApi pvpApi,
		WinStreakTracker tracker, FightMonitor fightMonitor, LongSupplier nowMs)
	{
		this.client = client;
		this.config = config;
		this.pvpApi = pvpApi;
		this.tracker = tracker;
		this.fightMonitor = fightMonitor;
		this.nowMs = nowMs;
		// A positioned (non-DYNAMIC) overlay is movable + snappable through
		// RuneLite's standard Alt+drag; TOP_LEFT is only where it starts.
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(Overlay.PRIORITY_LOW);
		setResizable(false);
		panelComponent.setBorder(new Rectangle(BORDER_PX, BORDER_PX, BORDER_PX, BORDER_PX));
		panelComponent.setGap(new Point(0, GAP_PX));
	}

	public static boolean isShown(boolean alwaysShow, boolean showInFfa, boolean insideFfaPortal)
	{
		return alwaysShow || (showInFfa && insideFfaPortal);
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
		peekedTs = Long.MIN_VALUE;
		winLossPeak = null;
		panelComponent.getChildren().clear();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		try
		{
			panelComponent.getChildren().clear();
			if (!isShown(config.showKillStreakBox(), config.killStreakBoxInFfaPortal(), fightMonitor.isInsideFfaPortal())) return null;
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
			return super.render(graphics);
		}
		catch (Exception ignored)
		{
			// A transient client / config state must never feed RuneLite's renderer an exception per frame.
			panelComponent.getChildren().clear();
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
		StreakBucket live = StreakBucket.forRank(fightMonitor.getAutoSwitchTarget());
		if (live != null) return live;
		StreakBucket persisted = StreakBucket.forRank(config.rankBucket());
		return persisted == null ? StreakBucket.NH : persisted;
	}

	static List<String> linesFor(StreakBucket bucket, WinStreakTracker tracker, WinLossPeak profile,
		boolean showLongest, boolean showWinLoss, boolean showPeak)
	{
		String key = bucket.bucketKey;
		List<String> lines = new ArrayList<>(4);
		lines.add(bucket.label + " Current Kill Streak: " + tracker.text(key));
		if (showLongest) lines.add("Longest Kill Streak: " + tracker.longest(key));
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
			peekedTs = Long.MIN_VALUE;
			winLossPeak = null;
		}
		long now = nowMs.getAsLong();
		if (now >= nextPeekAtMs)
		{
			nextPeekAtMs = now + PEEK_MS;
			UserStats cached = pvpApi.peekProfile(self);
			if (cached != null && cached.getTimestamp() != peekedTs)
			{
				peekedTs = cached.getTimestamp();
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
