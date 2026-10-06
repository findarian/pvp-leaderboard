package com.pvp.leaderboard.overlay;

import com.pvp.leaderboard.cache.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.config.PvPLeaderboardConfig.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.*;
import java.util.List;
import net.runelite.api.Point;
import static java.lang.System.*;

@Slf4j
@SuppressWarnings("deprecation")
public class RankOverlay extends Overlay
{
    private final Client client;
    private final PvPLeaderboardConfig config;
    private final PvpApi pvpApi;
    /** Opt-in membership set from the snapshot/delta feed (names only). The
     *  overlay gates rendering on this; rank itself comes from the name-keyed
     *  shards (see {@link #fetchSceneRankIfNeeded}). Replaces the
     *  whitelist.json membership blob (see PLAN_PRESENCE_FRESHNESS.md). */
    private final MemberCache memberCache;

    // Resolved rank labels keyed by canonical name — populated by the
    // API-set path (post-fight) and by scene/self shard lookups; read
    // every frame by the overlay render loop.
    private final ConcurrentHashMap<String, String> shownRanks = new ConcurrentHashMap<>();

    /** When each {@link #shownRanks} entry was last written or
     *  re-checked, in millis since epoch. Drives {@link #PEER_RANK_MS}.
     *  A missing timestamp means the label came from a path that didn't stamp
     *  it, so its age is unknown; it is treated as due so the map self-heals. */
    private final ConcurrentHashMap<String, Long> rankShownMs = new ConcurrentHashMap<>();

    /** How long a peer's rank label may go without a re-resolution
     *  attempt (exactly one interval old counts as due). Without this, a
     *  label resolved once was pinned for the whole session: the scene loop
     *  only looks a player up when it has NO label, so an existing entry
     *  short-circuited every later read and
     *  {@code PvpApi}'s 6h shard TTL was never reached for overhead
     *  ranks. Deliberately far below that TTL so a refresh lands on the next
     *  shard generation shortly after it expires; refreshes inside the TTL
     *  are served from the in-memory shard cache, so the added CDN cost is
     *  one read per player per 6h. Pinned by RankOverlayPeerRankRefreshTest. */
    static final long PEER_RANK_MS = 15L * 60L * 1000L;

    // API-set ranks that should persist until shard cache refreshes with matching data
    private final ConcurrentHashMap<String, String> apiSetRanks = new ConcurrentHashMap<>();

    // Combat tracking for "hide rank out of combat" feature
    // Tracks when self was last in combat (milliseconds)
    private volatile long selfCombatMs = 0L;

    // Config change tracking
    private String lastBucketKey = null;

    // Self-rank scheduling
    private volatile long selfRefresh = 0L;
    private volatile boolean selfTried = false;
    private volatile long selfRankDue = 0L;

    /** Scene shard lookup state for opted-in players visible in the
     *  world who don't yet have a rank label above their head. Retry
     *  throttle so render() (~60 fps) issues at most one shard
     *  resolution attempt per player per interval, and none while one is
     *  in flight (pinned by RankOverlaySceneShardTest). Uses the passive
     *  ({@code bypassCache=false}) lookup of
     *  {@link PvpApi#getShardRank(String, String, boolean)}
     *  so repeat attempts are served from the 6h positive cache (once
     *  resolved) or the missing-player negative cache (when unranked),
     *  keeping CDN cost bounded even in a crowded scene. Bounded by
     *  membership + in-scene visibility + this per-player backoff —
     *  never a whole-world shard poll. */
    private static final long SHARD_GAP_MS = 30_000L;
    private final ConcurrentHashMap<String, Long> shardTriedMs = new ConcurrentHashMap<>();
    private final Set<String> shardBusy = ConcurrentHashMap.newKeySet();

    /** Negative backoff for the profile-API fallback used when a scene
     *  player misses the CDN shard. The shard GET is cheap +
     *  CDN/negative-cached, but the {@code /user} profile fetch is a
     *  Lambda+DynamoDB call, so a player who resolves in NEITHER shard nor
     *  profile must not re-hit /user on every {@link #SHARD_GAP_MS}
     *  tick. Positive resolutions are cached permanently in
     *  {@link #shownRanks}, so this only rate limits
     *  genuinely-unresolvable players. */
    private static final long MISS_WAIT_MS = 10L * 60L * 1000L;
    private final ConcurrentHashMap<String, Long> missUntilMs = new ConcurrentHashMap<>();

    // MMR change notification queue (for multi-kill scenarios)
    private final ConcurrentLinkedQueue<MmrNotice> noticeQueue = new ConcurrentLinkedQueue<>();
    private volatile MmrNotice shownNotice = null;
    private volatile long noticeAtMs = 0L;
    private volatile int noticeTick = -1; // Track tick for 1-per-tick display

    // Legacy fields kept for backwards compatibility
    private volatile Double previousMmr = null;

    /**
     * Represents a single MMR change notification.
     */
    @AllArgsConstructor
    private static class MmrNotice {
        final double delta;
        final String bucketLabel;
        final boolean portalCapped;
    }

    @Inject
    public RankOverlay(Client client, PvPLeaderboardConfig config, PvpApi pvpApi,
                       MemberCache memberCache)
    {
        this.client = client;
        this.config = config;
        this.pvpApi = pvpApi;
        this.memberCache = memberCache;
        // Always use DYNAMIC for snap-to-player rendering
        // Use UNDER_WIDGETS layer (same as player indicators) so it appears above prayers
        // Use PRIORITY_LOW so it renders behind player indicator names
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.UNDER_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
    }

    /** Drop-shadow behind every rank label. Hoisted out of the draw
     *  loop: this ran once per label per frame. */
    private static final Color OUTLINE_COLOR = new Color(0, 0, 0, 180);

    /** 3rd Age glow: concentric white layers, widest and faintest
     *  first (offsets 3, 2, 1 by index), drawn under the solid label. */
    private static final Color[] GLOW_COLORS = {
        new Color(255, 255, 255, 25),
        new Color(255, 255, 255, 50),
        new Color(255, 255, 255, 100),
    };

    /** Label font, rebuilt only when the configured text size changes —
     *  see {@link #rankFont(int)}. Touched only from the render thread. */
    private Font rankFont;

    /**
     * Schedule a self-rank refresh on the next frame.
     */
    public void scheduleSelf()
    {
        selfRankDue = currentTimeMillis();
        selfTried = false;
        selfRefresh = selfRankDue;
    }

    /**
     * Reset lookup state on world hop.
     */
    public void onWorldHop()
    {
        selfTried = false;
        selfRankDue = 0L;
        // Note: Don't clear whitelist cache on world hop - it's fetched from API and valid for 1 hour
    }

    /**
     * Get cached rank for a player.
     */
    public String getCachedRankFor(String playerName)
    {
        return shownRanks.get(NameUtils.canonicalKey(playerName));
    }

    /**
     * Set rank from API response.
     * This rank will persist until the shard cache refreshes with matching data.
     */
    public void setApiRank(String playerName, String rank)
    {
        if (playerName == null || playerName.trim().isEmpty() || rank == null || rank.trim().isEmpty())
        {
            return;
        }
        String key = NameUtils.canonicalKey(playerName);
        putShownRank(key, rank);
        // Track this as an API-set rank - persists until shard cache confirms with same rank
        apiSetRanks.put(key, rank);
        pvpApi.clearMisses(playerName);
    }

    /**
     * Write a resolved rank label and stamp it for the refresh cadence.
     * Every path that populates {@link #shownRanks} must go through
     * here, otherwise the entry looks ageless to the refresh check.
     */
    private void putShownRank(String nameKey, String rank)
    {
        shownRanks.put(nameKey, rank);
        rankShownMs.put(nameKey, currentTimeMillis());
    }

    /**
     * Show MMR delta notification with the actual delta from match history.
     * This is the preferred method as it uses the accurate server-calculated delta.
     * Notifications are queued for multi-kill scenarios.
     *
     * @param mmrDelta The actual MMR change (positive for gain, negative for loss)
     * @param bucketLabel Optional bucket label to display (e.g., "Multi") when different from config bucket
     */
    public void showMmrDelta(double mmrDelta, String bucketLabel)
    {
        queueNotice(new MmrNotice(mmrDelta, bucketLabel, false));
    }

    public void showCapped(String bucketLabel)
    {
        queueNotice(new MmrNotice(0.0, bucketLabel, true));
    }

    private void queueNotice(MmrNotice notification)
    {
        if (!config.showMmrChangeNotification())
        {
            return;
        }

        // Add to queue for sequential display
        noticeQueue.offer(notification);

        // If no notification is currently showing, start this one immediately
        if (shownNotice == null && noticeAtMs == 0L)
        {
            startNotice();
        }
    }

    /**
     * Start displaying the next notification from the queue.
     */
    private void startNotice()
    {
        MmrNotice next = noticeQueue.poll();
        if (next != null)
        {
            shownNotice = next;
            noticeAtMs = currentTimeMillis();
        }
        else
        {
            shownNotice = null;
            noticeAtMs = 0L;
        }
    }

    /**
     * Reset the visibility timer when in combat. Called by FightMonitor on
     * every hit. Universal: it keeps all ranks (self and other players)
     * visible for the configured duration.
     */
    public void updateSelfCombatTime()
    {
        selfCombatMs = currentTimeMillis();
    }

    /** Tracks the last time we logged a render-loop throwable, in
     *  millis since epoch. Used to rate-limit the swallow-warn so a
     *  pathological 60-fps NPE storm produces at most one WARN per
     *  minute instead of ~3,600 per minute. */
    private volatile long lastErrorLog = 0L;

    /** Minimum interval between {@code "render threw — swallowing"}
     *  WARN entries when the inner body is failing every frame. One
     *  minute is enough to keep the user's log readable while still
     *  giving prompt feedback if a new failure appears mid-session. */
    private static final long ERROR_LOG_MS = 60_000L;

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Catch-all guard: the rank overlay paints every frame at
        // ~60 FPS and reaches into client/Player APIs that can
        // transiently null out around login / world-hop / scene
        // transitions. Without this wrapper, a single null deref
        // would feed RuneLite's OverlayRenderer a same-bytecode-
        // location throw every frame; HotSpot then strips the
        // stack via OmitStackTraceInFastThrow and the user sees
        // 60 untraceable WARN lines/sec. Logging once with a real
        // stack here + swallowing downstream restores the diagnostic
        // signal. See {@code RankOverlayRenderSafetyTest} for the
        // contract.
        try
        {
            return renderInner(graphics);
        }
        catch (Throwable t)
        {
            // Rate-limit the WARN line so a pathological 60-fps NPE
            // cannot flood the log. The synthetic Throwable allocated
            // at the catch site always carries a real stack — even
            // when HotSpot has stack-stripped {@code t} via
            // {@code -XX:+OmitStackTraceInFastThrow} after thousands
            // of throws from the same bytecode location — so the user
            // can still locate the call path through {@code render()}.
            long now = currentTimeMillis();
            if (now - lastErrorLog >= ERROR_LOG_MS)
            {
                lastErrorLog = now;
                log.warn(
                    "[RankOverlay] render threw {} (msg={}) — swallowing. " +
                        "Diagnostic stack (HotSpot may have stripped the original):",
                    t.getClass().getName(), t.getMessage(),
                    new Throwable("RankOverlay render diagnostic capture"));
            }
            return new Dimension(0, 0);
        }
    }

    private Dimension renderInner(Graphics2D graphics)
    {
        if (config == null || client == null)
        {
            return new Dimension(0, 0);
        }

        // Handle bucket config changes
        String currentBucket = RankBucket.key(config.rankBucket());

        if (lastBucketKey == null || !lastBucketKey.equals(currentBucket))
        {
            shownRanks.clear();
            rankShownMs.clear();
            apiSetRanks.clear();
            shardTriedMs.clear();
            shardBusy.clear();
            missUntilMs.clear();
            lastBucketKey = currentBucket;
            selfTried = false;
            selfRankDue = 0L;
        }

        // Get local player
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            return new Dimension(0, 0);
        }

        String localName = localPlayer.getName();

        // Handle self-rank refresh scheduling
        if (config.showOwnRank() && localName != null && selfRefresh > 0L)
        {
            long now = currentTimeMillis();
            if (now >= selfRefresh && !selfTried && now >= selfRankDue)
            {
                selfTried = true;
                selfRefresh = 0L;
                fetchSelf(localName);
            }
        }

        // Show ranks unless "hide rank out of combat" is on and the
        // configured minutes have passed since the last hit. Universal:
        // applies to both the self rank and other players' ranks.
        boolean showRanks = !config.hideRankOutOfCombat()
            || config.hideRankAfterMinutes() * 60_000L >= currentTimeMillis() - selfCombatMs;

        // Fixed heights rather than player.getLogicalHeight(), so the rank
        // doesn't "jump" when players wear different helmets/hats: feet 0,
        // head level 220 (just above the player name), above head 268. A
        // corrupted/missing persisted setting can return null; that draws
        // above the head like the default instead of throwing every frame.
        RankPosition pos = config.rankPosition();
        int heightOffset = pos == RankPosition.FEET ? 0
            : pos == RankPosition.HEAD ? 220 : 268;

        // Render self rank if enabled. localName == null happens
        // transiently during world hops / login; the label is only placed
        // when there is a rank to show and ranks are shown.
        if (config.showOwnRank() && localName != null && showRanks)
        {
            String cachedRank = shownRanks.get(NameUtils.canonicalKey(localName));
            if (cachedRank != null)
            {
                label(graphics, localPlayer, cachedRank, heightOffset);
            }
        }

        // Render MMR change notification
        renderNotice(graphics, localPlayer);

        // Render opted-in player ranks (if enabled and the membership feed
        // has loaded). Membership now comes from the snapshot/delta feed
        // (MemberCache); rank is resolved per name via shards below.
        if (config.enableWhitelistRanks() && memberCache.size() > 0)
        {
            renderRanks(graphics, localPlayer, currentBucket, heightOffset);
        }

        return new Dimension(0, 0);
    }

    /**
     * Render ranks above opted-in players in the scene.
     *
     * <p>Membership (who is an opted-in plugin user) comes from the
     * snapshot/delta feed ({@link MemberCache}); the rank label
     * itself is resolved per name from the {@code rank_idx} shards and
     * stored in {@link #shownRanks} — either eagerly by a fight's
     * API result ({@link #setApiRank}) or lazily by the scene shard
     * lookup below. Uses the current bucket setting.
     */
    private void renderRanks(Graphics2D graphics, Player localPlayer, String bucket,
                                        int heightOffset)
    {
        String localName = localPlayer.getName();

        // Defensive: client.getPlayers() can return null transiently
        // during scene loads / world hops. The enhanced-for below would
        // NPE on the implicit .iterator() call and feed RuneLite's
        // OverlayRenderer a stack-stripped NPE every frame at 60 fps
        // until the scene settles. Treat null as "no players visible".
        List<Player> scenePlayers = client.getPlayers();
        if (scenePlayers == null)
        {
            return;
        }

        for (Player player : scenePlayers)
        {
            if (player == null || player == localPlayer) continue;

            String playerName = player.getName();
            if (playerName == null || playerName.equals(localName)) continue;

            // Only show ranks for opted-in players (membership feed).
            if (!memberCache.isMember(playerName)) continue;

            String nameKey = NameUtils.canonicalKey(playerName);
            // Rank resolved either from a prior fight (API-set) or a
            // previous scene shard lookup — both land in shownRanks.
            String displayRank = shownRanks.get(nameKey);

            // Opted-in + visible but no label yet — kick a cached shard
            // read to resolve it. Passive (cache-first) so a crowded
            // scene is served from the 6h positive cache / missing-player
            // negative cache instead of hammering the CDN each retry.
            // The profile fallback is allowed here: a blank head has
            // nothing to show, so the expensive path is worth it.
            if (displayRank == null)
            {
                fetchSceneRankIfNeeded(playerName, bucket, nameKey, true);
                continue;
            }

            // Label present but past its refresh interval (or of unknown
            // age) — re-resolve in the background while still rendering
            // the current value, so a peer who ranked up mid-session stops
            // showing a label frozen at whatever it was when we first saw
            // them. Shard only: we already have something to display, so a
            // miss must not escalate to the /user Lambda.
            Long setAt = rankShownMs.get(nameKey);
            if (setAt == null || currentTimeMillis() - setAt >= PEER_RANK_MS)
            {
                fetchSceneRankIfNeeded(playerName, bucket, nameKey, false);
            }

            label(graphics, player, displayRank, heightOffset);
        }
    }

    /** Draws {@code rank} above {@code player}'s head (offset by the
     *  configured position). The font metrics are read before
     *  {@link #renderRank} switches to the rank font, as before. */
    private void label(Graphics2D g, Player player, String rank, int heightOffset)
    {
        Point head = player.getCanvasTextLocation(g, "", heightOffset);
        if (head != null)
        {
            renderRank(g, rank, head.getX() + config.rankOffsetX(),
                head.getY() - g.getFontMetrics().getAscent() - 2 + config.rankOffsetY(), Math.max(10, config.rankTextSize()));
        }
    }

    /**
     * Async two-tier rank resolution for an opted-in, in-scene player who
     * still has no rank label (or whose label is due a refresh):
     * <ol>
     *   <li><b>Shard</b> (passive, cache-first): served from the 6h
     *       positive shard cache, or the missing-player negative cache,
     *       so a crowded scene doesn't re-hit the CDN every retry.</li>
     *   <li><b>Profile API fallback</b> on a shard miss: players the shard
     *       writer hasn't picked up for this bucket only resolve via the
     *       {@code /user} endpoint. Rate-limited by
     *       {@link #MISS_WAIT_MS} so an unresolvable
     *       player doesn't hammer the Lambda+DynamoDB path.</li>
     * </ol>
     * Throttled to one in-flight attempt per player and
     * {@link #SHARD_GAP_MS} between attempts so render() never
     * calls the service every frame. A resolved rank lands in
     * {@link #shownRanks} and renders on the next frame, and is
     * re-resolved every {@link #PEER_RANK_MS}; a peer's own
     * post-fight rank change still updates immediately via the API-set
     * path.
     *
     * @param useProfile whether a shard miss may escalate to
     *        the {@code /user} profile API. True for a player with no
     *        label at all, false for a periodic refresh of a label we can
     *        already render — the fallback is a Lambda+DynamoDB call and
     *        isn't worth paying to confirm a rank we're already showing.
     */
    private void fetchSceneRankIfNeeded(String playerName, String bucket, String nameKey,
                                        boolean useProfile)
    {
        long now = currentTimeMillis();
        // A player never attempted has no stamp. A null last-attempt
        // timestamp must never be unboxed into a primitive here: that NPE,
        // swallowed by the render() wrapper, once silently blocked EVERY
        // scene rank resolution.
        Long lastAttempt = shardTriedMs.get(nameKey);
        if (lastAttempt != null && now - lastAttempt < SHARD_GAP_MS || !shardBusy.add(nameKey))
        {
            return;
        }
        shardTriedMs.put(nameKey, now);
        // Re-arm the refresh cadence at ATTEMPT time, not on completion:
        // a lookup that fails or resolves nothing must still wait a full
        // interval, otherwise an unresolvable player would retry every
        // SHARD_GAP_MS forever.
        rankShownMs.put(nameKey, now);

        pvpApi.getShardRank(playerName, bucket, false)
            .whenComplete((sr, ex) ->
            {
                if (ex != null)
                {
                    shardBusy.remove(nameKey);
                    return;
                }
                if (sr != null && sr.tier != null && !sr.tier.trim().isEmpty())
                {
                    shardBusy.remove(nameKey);
                    applyShard(nameKey, sr.tier);
                    return;
                }
                // Nothing to gain from the profile API when we already
                // have a label to render — keep the /user path cold.
                if (!useProfile)
                {
                    shardBusy.remove(nameKey);
                    return;
                }
                // Shard miss — fall back to the profile API. Players the
                // shard writer hasn't picked up for this bucket (e.g. "RUN
                // PIGGY") only resolve via /user. Rate-limited by a negative
                // backoff so an unresolvable player doesn't re-hit the
                // Lambda+DynamoDB path on every scene-retry tick.
                Long missUntil = missUntilMs.get(nameKey);
                if (missUntil != null && currentTimeMillis() < missUntil)
                {
                    shardBusy.remove(nameKey);
                    return;
                }
                pvpApi.getLobbyRank(playerName, bucket)
                    .whenComplete((tier, pex) ->
                    {
                        shardBusy.remove(nameKey);
                        if (pex != null)
                        {
                            missUntilMs.put(nameKey,
                                currentTimeMillis() + MISS_WAIT_MS);
                            return;
                        }
                        if (tier != null && !tier.trim().isEmpty())
                        {
                            putShownRank(nameKey, tier);
                            return;
                        }
                        // Unresolved in both shard and profile — back off the
                        // profile retry to protect the /user endpoint.
                        missUntilMs.put(nameKey,
                            currentTimeMillis() + MISS_WAIT_MS);
                    });
            });
    }

    /**
     * Adopt a shard-resolved rank (for a scene player or, as the API
     * fallback, for self), deferring to a post-fight API rank that the
     * shard hasn't caught up to yet.
     *
     * <p>An API rank is newer than any shard generation, so a disagreeing
     * shard read is stale data and must not clobber it. Once the shard
     * agrees, the override has served its purpose and is dropped.
     */
    private void applyShard(String nameKey, String shardRank)
    {
        String apiRank = apiSetRanks.get(nameKey);
        if (apiRank != null && !apiRank.equals(shardRank))
        {
            return;
        }
        if (apiRank != null)
        {
            apiSetRanks.remove(nameKey);
        }
        putShownRank(nameKey, shardRank);
    }

    private void fetchSelf(String selfName)
    {
        String bucket = RankBucket.key(config.rankBucket());
        String key = NameUtils.canonicalKey(selfName);

        pvpApi.getTierFromProfile(selfName, bucket)
            .thenAccept(apiTier -> {
                long fetchedAt = currentTimeMillis();

                if (apiTier != null)
                {
                    putShownRank(key, apiTier);
                    apiSetRanks.put(key, apiTier);
                    selfRankDue = fetchedAt + 60_000L;
                }
                else
                {
                    fetchRankForSelfFromShard(selfName, bucket, key);
                }
            })
            .exceptionally(ex -> {
                fetchRankForSelfFromShard(selfName, bucket, key);
                return null;
            });
    }

    /**
     * Fallback method to fetch self rank from shard when API fails.
     */
    private void fetchRankForSelfFromShard(String selfName, String bucket, String key)
    {
        pvpApi.getShardRank(selfName, bucket, false)
            .thenAccept(sr -> {
                if (sr == null)
                {
                    return;
                }
                if (sr.tier != null)
                {
                    long fetchedAt = currentTimeMillis();
                    // An API-set rank persists until the shard has refreshed with matching data
                    applyShard(key, sr.tier);
                    selfRankDue = fetchedAt + 60_000L;
                }
            })
            .exceptionally(ex -> {
                selfRankDue = currentTimeMillis() + 60_000L;
                return null;
            });
    }

    private void renderRank(Graphics2D g, String fullRank, int x, int y, int size)
    {
        String text = rankLabelText(fullRank);
        if (text.isEmpty())
        {
            return;
        }

        g.setFont(rankFont(size));
        FontMetrics fm = g.getFontMetrics();
        int centerX = x - fm.stringWidth(text) / 2;
        int baseY = y + fm.getAscent();

        // 3rd Age: special glow (white layers widest first), then core
        // white text; every other rank: black outline + coloured text
        // (white in colour-blind mode and for a "Rank N" label).
        boolean glow = text.startsWith("3rd") && !config.colorblindMode();
        if (glow)
        {
            for (int i = 0; i < GLOW_COLORS.length; i++)
            {
                g.setColor(GLOW_COLORS[i]);
                ring(g, text, centerX, baseY, 3 - i);
            }
        }
        else
        {
            g.setColor(OUTLINE_COLOR);
            ring(g, text, centerX, baseY, 1);
        }
        g.setColor(glow || config.colorblindMode() || text.startsWith("Rank ") ? Color.WHITE : RankUtils.getRankColor(text));
        g.drawString(text, centerX, baseY);
    }

    /** Draws {@code text} at every offset within {@code r} of (x, y) except
     *  (x, y) itself, rows top to bottom: the outline / glow ring. */
    private static void ring(Graphics2D g, String text, int x, int y, int r)
    {
        for (int dy = -r; dy <= r; dy++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                if (dx != 0 || dy != 0)
                {
                    g.drawString(text, x + dx, y + dy);
                }
            }
        }
    }

    /**
     * The label to draw for {@code fullRank}: the rank and its division,
     * trimmed and single-spaced, or empty when there's nothing to show.
     *
     * <p>Normalised rather than used verbatim because the label is
     * centred on the player's head from its measured width — a stray
     * double space would draw wider than it measured and sit off-centre.
     */
    static String rankLabelText(String fullRank)
    {
        if (fullRank == null) return "";
        String trimmed = fullRank.trim();
        if (trimmed.isEmpty()) return "";
        int space = trimmed.indexOf(' ');
        if (space < 0) return trimmed;
        String rankName = trimmed.substring(0, space);
        String division = trimmed.substring(space + 1).trim();
        return division.isEmpty() ? rankName : (rankName + " " + division);
    }

    /**
     * The bold label font at {@code size}, cached across frames.
     *
     * <p>This is called once per visible opted-in player per frame, so
     * at 50 FPS in a crowded area it ran thousands of times a second —
     * each {@code new Font} also forced a fresh font-metrics lookup.
     * The size only changes when the user moves the rank-text-size
     * slider, so one cached instance serves ~every call.
     */
    private Font rankFont(int size)
    {
        Font cached = rankFont;
        if (cached == null || cached.getSize() != size)
        {
            cached = new Font(Font.DIALOG, Font.BOLD, size);
            rankFont = cached;
        }
        return cached;
    }

    private void renderNotice(Graphics2D g, Player localPlayer)
    {
        int currentTick = client.getTickCount();

        if (shownNotice == null || noticeAtMs == 0L)
        {
            // Try to start next notification if queue has items (rate limit: 1 per tick)
            if (!noticeQueue.isEmpty() && currentTick != noticeTick)
            {
                startNotice();
                noticeTick = currentTick;
            }
            return;
        }
        if (!config.showMmrChangeNotification())
        {
            return;
        }

        // Check if current notification duration has expired
        long durationMs = config.mmrDuration() * 1000L;
        long elapsed = currentTimeMillis() - noticeAtMs;

        if (elapsed > durationMs)
        {
            // Current notification finished - start next (rate limit: 1 per tick)
            if (currentTick != noticeTick)
            {
                startNotice();
                noticeTick = currentTick;
            }
            else
            {
                // Already started one this tick, clear current and wait for next tick
                shownNotice = null;
                noticeAtMs = 0L;
            }
            return;
        }

        // Get position above player
        Point headLoc = localPlayer.getCanvasTextLocation(g, "", localPlayer.getLogicalHeight() + 80);
        if (headLoc == null)
        {
            return;
        }

        MmrNotice notification = shownNotice;

        // Calculate fade progress
        float progress = (float) elapsed / durationMs;
        int alpha = (int) (255 * (1.0f - progress));
        int floatOffset = (int) (progress * 30); // Float up 30 pixels

        String text = noticeText(notification.delta, notification.bucketLabel, notification.portalCapped);
        Color baseColor = noticeColor(notification.delta, notification.portalCapped, config.colorblindMode());
        Color color = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha);

        // Render with black outline
        g.setFont(new Font(Font.DIALOG, Font.BOLD, Math.max(12, config.rankTextSize() + 2)));
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int centerX = headLoc.getX() - textW / 2 + config.mmrOffsetX();
        int drawY = headLoc.getY() - floatOffset + config.mmrOffsetY();

        // Black outline with alpha
        g.setColor(new Color(0, 0, 0, (int) (180 * (1.0f - progress))));
        ring(g, text, centerX, drawY, 1);

        // Main colored text
        g.setColor(color);
        g.drawString(text, centerX, drawY);
    }

    /** "+14.0 MMR" (a zero change reads as a gain), or the portal cap's
     *  label; " (label)" appended when a bucket label is given. */
    static String noticeText(double mmrDelta, String bucketLabel, boolean portalCapped)
    {
        return (portalCapped ? PortalCap.LABEL : String.format("%s%.1f MMR", mmrDelta >= 0 ? "+" : "", mmrDelta))
            + (bucketLabel != null && !bucketLabel.isEmpty() ? " (" + bucketLabel + ")" : "");
    }

    /** White in colour-blind mode; else green for a gain or a capped fight, red for a loss. */
    static Color noticeColor(double mmrDelta, boolean portalCapped, boolean colorblind)
    {
        return colorblind ? Color.WHITE : portalCapped || mmrDelta >= 0 ? new Color(0, 200, 0) : new Color(0xe53935);
    }
}
