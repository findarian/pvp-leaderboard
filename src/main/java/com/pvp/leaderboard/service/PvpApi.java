package com.pvp.leaderboard.service;

import com.google.gson.*;
import com.pvp.leaderboard.*;
import com.pvp.leaderboard.cache.*;
import com.pvp.leaderboard.util.*;
import java.io.*;
import java.math.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import okhttp3.*;
import static com.pvp.leaderboard.util.JsonLenient.*;
import static java.lang.System.*;

@Slf4j
@Singleton
public class PvpApi
{
    private static final String SHARD_URL = PvpConsts.SITE_URL + "/rank_idx";
    /** Per-bucket MMR→rank histogram published hourly by the infra side
     *  ({@code OSRS-MMR/lambda_code/distribution_cache_writer.py} →
     *  {@code rank_hist/<bucket>.json}). Powers the "What are the ranks"
     *  Top-X%-per-tier view. */
    private static final String HIST_URL = PvpConsts.SITE_URL + "/rank_hist";

    /** The cache and backoff clocks. One minute: the {@code /user} and
     *  {@code /matches} caches, the CDN failure backoff and the {@code /user}
     *  throttle backoff (so both halves of a rank lookup back off on the same
     *  clock). One hour: a player missing from the shards ("Not Found"), and
     *  the Top Players file (mirrors its hourly refresh). The shard cache is
     *  six hours (see {@link #getShard}). Pinned by the TTL and backoff tests. */
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;

    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final IdentitySvc identitySvc;

    // In-flight request deduplication for getShardRank
    private final ConcurrentHashMap<String, CompletableFuture<ShardRank>> busyLookups = new ConcurrentHashMap<>();

    private void enqueue(Request request, Callback callback)
    {
        okHttpClient.newCall(request).enqueue(callback);
    }

    // Shard lookup caching (payload + fetch time, keyed by URL)
    private final Map<String, UserStats> shardCache = Collections.synchronizedMap(
        new LinkedHashMap<>(128, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UserStats> eldest)
            {
                return size() > 512; // LRU cap
            }
        }
    );

    private final ConcurrentHashMap<String, Long> shardFailUntil = new ConcurrentHashMap<>();

    /** In-flight GETs keyed by URL, so concurrent readers of one
     *  artifact share a single request instead of each issuing their own.
     *
     *  <p>The positive cache in {@link #fetchCached} can only help
     *  callers that arrive AFTER a response lands. Rank shards are
     *  published per 3-char name prefix, so a scene (or, before 2.0, a
     *  lobby roster) resolved in one pass has many players resolving out
     *  of the same file at the same instant — all of them miss the
     *  not-yet-populated cache and each issues its own GET. That made CDN
     *  request volume scale with the number of players rather than with
     *  the distinct shard count, which is how a large roster reached the
     *  CloudFront firewall's per-IP rate limit. Collapsing here keeps the
     *  fan-out proportional to the number of distinct shards.
     *
     *  <p>Keyed by URL rather than by player: the name+bucket dedup in
     *  {@link #getShardRank} does nothing for two different players
     *  who happen to share a prefix, which is the entire population this
     *  collapses. */
    private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> jsonFetches =
        new ConcurrentHashMap<>();

    // Negative cache for specific players/accounts to avoid re-checking shards
    private final ConcurrentHashMap<String, Long> absentUntil = new ConcurrentHashMap<>();

    // Top Players leaderboard caching — kept SEPARATE from the rank_idx shard
    // cache above on purpose. The leaderboard is a different artifact: the
    // pregenerated top-550 list (/leaderboard[_<bucket>].json) the website
    // reads, refreshed hourly by the infra leaderboard_cache_writer.py — NOT a
    // name→rank shard. Sharing shardCache would let shard churn (its 512-entry
    // LRU) evict the leaderboard (and vice versa) and conflate two unrelated
    // resources. Only ~5 buckets, so a plain map (no LRU) is plenty.
    private final ConcurrentHashMap<String, UserStats> topCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> topFailUntil = new ConcurrentHashMap<>();

    /**
     * Clears the shard negative cache for a player. Call this when API confirms player exists.
     */
    public void clearMisses(String playerName)
    {
        absentUntil.remove(NameUtils.canonicalKey(playerName));
    }

    // User profile caching, keyed by the canonical name (NameUtils handles the
    // nameplate non-breaking space, so a scene-sourced name and a feed/JSON-
    // sourced name share one entry).
    private final ConcurrentHashMap<String, UserStats> statsCache = new ConcurrentHashMap<>();

    /** In-flight dedupe for the scene / player-card {@code /user} fallback
     *  after a shard miss — at most one network roundtrip per (player,
     *  bucket) at a time. */
    private final ConcurrentHashMap<String, CompletableFuture<String>> rankLookups =
        new ConcurrentHashMap<>();

    /** Epoch ms until which {@code /user} calls are answered locally
     *  instead of hitting the network; {@code 0} when open.
     *
     *  <p>Endpoint-wide rather than per-player on purpose: a WAF block
     *  is issued against the client, so retrying under a different
     *  {@code player_id} is the same violation and would keep the
     *  block alive.
     *
     *  <p>This is the API-side counterpart to the CDN's
     *  {@code shardFailUntil}. Without it a rate-limited client kept
     *  calling at its callers' full tick rate — the lobby's roster
     *  enrichment re-asked on every roster push (~8 s) and the join
     *  gate every 60 s — which turns a decaying soft limit into a
     *  sustained one. Only 403 / 429 / 503 ("you are sending too much /
     *  you are blocked") arm it: arming on a 404 ("that player doesn't
     *  exist") would let one lookup of an unknown name stall the endpoint
     *  for every caller. */
    private final AtomicLong apiBlockMs = new AtomicLong(0L);

    // Matches caching (first pages only)
    private final ConcurrentHashMap<String, UserStats> matchesCache = new ConcurrentHashMap<>();

    // DMM Worlds Caching — commented out while DMM is inactive, re-enable with API fetch
    // private static final long DMM_WORLDS_CACHE_TTL_MS = 60L * 60L * 1000L;
    // private volatile Set<Integer> dmmWorldsCache = new HashSet<>();
    // private volatile long dmmWorldsCacheTimestamp = 0L;
    // private volatile boolean dmmWorldsFetchInProgress = false;

    @Inject
    public PvpApi(OkHttpClient okHttpClient, Gson gson, IdentitySvc identitySvc)
    {
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.identitySvc = identitySvc;
    }

    /** A builder for the API route {@code path}. */
    private static HttpUrl.Builder api(String path)
    {
        return HttpUrl.get(PvpConsts.API_BASE_URL + path).newBuilder();
    }

    /** A GET of {@code url} carrying the client identifier header (API
     *  authentication/tracking); {@code force} skips every HTTP cache. */
    private Request req(HttpUrl url, boolean force)
    {
        var b = new Request.Builder().url(url).get();
        if (force)
        {
            b.cacheControl(CacheControl.FORCE_NETWORK);
        }
        String id = identitySvc != null ? identitySvc.getClientUniqueId() : null;
        if (id != null && !id.isEmpty())
        {
            b.addHeader("X-Client-Unique-Id", id);
        }
        return b.build();
    }

    /** Completes {@code future} with a copy of the cached {@code key} when the
     *  entry is at most {@code ttl} old (use {@code Long.MAX_VALUE} for "any
     *  stale copy"); true when it did. */
    private static boolean serve(Map<String, UserStats> cache, String key, long ttl, CompletableFuture<JsonObject> future)
    {
        UserStats c = cache.get(key);
        return c != null && currentTimeMillis() - c.getTimestamp() <= ttl && future.complete(c.getStats().deepCopy());
    }

    /**
     * Fetch match history: by account SHA256 hash (derived from the UUID) when
     * {@code acctSha} is set — ALL matches across ALL account names for this
     * identity, accurate even after name changes — else by player name.
     *
     * <p>The first page (no {@code nextToken}) is cached for a minute and served
     * stale when the request fails. {@code bypassCache} skips that cache and
     * forces the network (use it for MMR delta lookups); the response still
     * refreshes the cache.
     */
    public CompletableFuture<JsonObject> getMatches(String acctSha, String playerName, String nextToken, int limit, boolean bypassCache)
    {
        boolean acct = acctSha != null && !acctSha.isEmpty();
        String id = acct ? acctSha.toLowerCase() : playerName;
        boolean first = nextToken == null || nextToken.isEmpty();
        String key = (acct ? "matches:acct:" : "matches:") + id + ":" + limit;
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        if (!bypassCache && first && serve(matchesCache, key, MINUTE_MS, future))
        {
            return future;
        }
        HttpUrl.Builder b = api("/matches")
            .addQueryParameter(acct ? "acct" : "player_id", id)
            .addQueryParameter("limit", String.valueOf(limit));
        if (!first)
        {
            b.addQueryParameter("next_token", nextToken);
        }
        HttpUrl url = b.build();

        enqueue(req(url, bypassCache), new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                // Try serving the stale first page if there is one
                if (!first || !serve(matchesCache, key, Long.MAX_VALUE, future))
                {
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        if (!first || !serve(matchesCache, key, Long.MAX_VALUE, future))
                        {
                            future.completeExceptionally(new IOException("API call failed with status: " + res.code()));
                        }
                        return;
                    }
                    String body;
                    try
                    {
                        body = res.body().string();
                    }
                    catch (IOException cacheEx)
                    {
                        future.complete(new JsonObject());
                        return;
                    }
                    JsonObject json = gson.fromJson(body, JsonObject.class);
                    if (first)
                    {
                        matchesCache.put(key, new UserStats(json.deepCopy(), currentTimeMillis()));
                    }
                    future.complete(json);
                }
                catch (JsonSyntaxException e)
                {
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    /** SHA-256 of this client's identifier as 64 lower-case hex characters (the
     *  backend's {@code acct_sha}), or null when there is no identifier. */
    public String getSelfSha()
    {
        try
        {
            String uuid = identitySvc.getClientUniqueId();
            return uuid == null || uuid.isEmpty() ? null : String.format("%064x",
                new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(uuid.getBytes(StandardCharsets.UTF_8))));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Fetch ALL match pages (by account SHA when {@code acctSha} is set — use it
     * for self-lookups to capture every match across name changes — else by
     * name), paginating through every next_token. Returns a single JsonArray
     * containing every match across all pages; caps at 5 pages of 1,000.
     */
    public CompletableFuture<JsonArray> getAllMatches(String acctSha, String playerName)
    {
        CompletableFuture<JsonArray> future = new CompletableFuture<>();
        fetchPages(acctSha, playerName, null, new JsonArray(), future, 0);
        return future;
    }

    private void fetchPages(String acctSha, String playerName, String nextToken,
                               JsonArray accumulator, CompletableFuture<JsonArray> future, int page)
    {
        if (page >= 5)
        {
            future.complete(accumulator);
            return;
        }

        getMatches(acctSha, playerName, nextToken, 1000, true).thenAccept(response -> {
            if (response == null)
            {
                future.complete(accumulator);
                return;
            }
            JsonArray matches = optArray(response, "matches");
            accumulator.addAll(matches);
            String next = optString(response, "next_token", null);
            if (next != null && !next.isEmpty())
            {
                fetchPages(acctSha, playerName, next, accumulator, future, page + 1);
            }
            else
            {
                future.complete(accumulator);
            }
        }).exceptionally(ex -> {
            log.warn("[TierGraph] fetchAllPages: error on page {}, stopping with {} matches", page, accumulator.size(), ex);
            future.complete(accumulator);
            return null;
        });
    }

    /** {@code GET /tournament/history?limit=}: finished events, newest first ({@code tournaments[]}, {@code next_before}). */
    public CompletableFuture<JsonObject> getTournamentHistory(int limit)
    {
        return apiGet(api("/tournament/history").addQueryParameter("limit", String.valueOf(limit)).build());
    }

    /** {@code GET /tournament/standings?tournament_id=}: an event's standings, the live push's payload. */
    public CompletableFuture<JsonObject> getTournamentStandings(String tournamentId)
    {
        if (tournamentId == null || tournamentId.trim().isEmpty())
        {
            return CompletableFuture.failedFuture(new IOException("no tournament id"));
        }
        return apiGet(api("/tournament/standings").addQueryParameter("tournament_id", tournamentId.trim()).build());
    }

    /** One GET of an API route with the client identifier: the JSON body on success, a failure otherwise. */
    private CompletableFuture<JsonObject> apiGet(HttpUrl url)
    {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        enqueue(req(url, false), new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        future.completeExceptionally(new IOException("API call failed with status: " + res.code()));
                        return;
                    }
                    JsonObject json = gson.fromJson(res.body().string(), JsonObject.class);
                    future.complete(json == null ? new JsonObject() : json);
                }
                catch (JsonSyntaxException | IOException e)
                {
                    future.completeExceptionally(e);
                }
            }
        });
        return future;
    }

    /** A player card's rank: the tournament shard, then the nh shard, then the
     *  {@code /user} profile's tournament rank; {@code null} when none knows the player. */
    public CompletableFuture<String> getCardTier(String playerName)
    {
        if (playerName == null || playerName.trim().isEmpty())
        {
            return CompletableFuture.completedFuture(null);
        }
        return shardTier(playerName, "tournament")
            .thenCompose(t -> t != null ? CompletableFuture.completedFuture(t) : shardTier(playerName, "nh"))
            .thenCompose(t -> t != null ? CompletableFuture.completedFuture(t) : getLobbyRank(playerName, "tournament"));
    }

    private CompletableFuture<String> shardTier(String playerName, String bucket)
    {
        return getShardRank(playerName, bucket, true)
            .exceptionally(ex -> null)
            .thenApply(sr -> sr != null ? sr.tier : null);
    }

    /** Kill-streak box (BOARD row 33): the cached {@code /user} profile of
     *  {@code playerName} exactly as the last fetch left it, or {@code null}
     *  when none is cached. A peek only, never a fetch: the box follows the
     *  fetches the login init, the post-fight tier refresh and the lobby gate
     *  already make. The entry's timestamp tells a caller whether it changed. */
    public UserStats peekProfile(String playerName)
    {
        return playerName == null ? null : statsCache.get(NameUtils.canonicalKey(playerName));
    }

    /**
     * The {@code /user} profile: from the one-minute cache unless
     * {@code forceRefresh}, which also forces the network; {@code null} when the
     * player does not exist (404 or the soft 404).
     */
    public CompletableFuture<JsonObject> getProfile(String playerName, boolean forceRefresh)
    {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        String key = NameUtils.canonicalKey(playerName);
        if (!forceRefresh && serve(statsCache, key, MINUTE_MS, future))
        {
            return future;
        }

        // Throttle backoff. Checked after the positive-cache read above
        // so a warm entry still serves — the goal is to stop new
        // traffic, not to blank the UI. Deliberately fails rather than
        // completing null: null is this endpoint's "no such player"
        // answer and callers act on it destructively (the lobby join
        // gate zeroes the user's match counts), so reporting a block
        // that way would lock a legitimate player out of the lobby.
        long blockedUntil = apiBlockMs.get();
        if (currentTimeMillis() < blockedUntil)
        {
            if (!serve(statsCache, key, Long.MAX_VALUE, future))
            {
                future.completeExceptionally(new IOException(
                    "user API backing off for " + (blockedUntil - currentTimeMillis()) + "ms"));
            }
            return future;
        }

        enqueue(req(api("/user").addQueryParameter("player_id", playerName)
            .addQueryParameter("include_world_rank", "1").build(), forceRefresh), new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                if (!serve(statsCache, key, Long.MAX_VALUE, future))
                {
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response res = response)
                {
                    int code = res.code();
                    if (!res.isSuccessful())
                    {
                        if (code == 404)
                        {
                            future.complete(null);
                            return;
                        }
                        if (code == 403 || code == 429 || code == 503)
                        {
                            apiBlockMs.set(currentTimeMillis() + MINUTE_MS);
                            log.warn("[API] /user returned {} - backing off for {}ms", code, MINUTE_MS);
                        }
                        future.completeExceptionally(new IOException("API error: " + code));
                        return;
                    }
                    String body;
                    try
                    {
                        body = res.body().string();
                    }
                    catch (IOException cacheEx)
                    {
                        // Windows cache file locking issue - request succeeded but cache failed
                        // Try to serve stale cache if available
                        if (!serve(statsCache, key, Long.MAX_VALUE, future))
                        {
                            future.complete(null);
                        }
                        return;
                    }
                    JsonObject json = gson.fromJson(body, JsonObject.class);

                    // Detect soft 404: API returns 200 with {"message": "player not found"}
                    if (json.has("message") && !json.has("player_id") && !json.has("player_name") && !json.has("mmr"))
                    {
                        future.complete(null);
                        return;
                    }

                    statsCache.put(key, new UserStats(json.deepCopy(), currentTimeMillis()));
                    future.complete(json);
                }
                catch (JsonSyntaxException e)
                {
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    /**
     * Post-fight rank refresh from the {@code /user} endpoint (fresh data, bypassing
     * the shards): the bucket's rank, else the profile's top-level rank, e.g.
     * "Dragon 3" (see {@link #tier}). When the player is not found, the profile
     * cached before this call answers instead.
     */
    public CompletableFuture<String> getTierFromProfile(String playerName, String bucket)
    {
        UserStats cached = statsCache.get(NameUtils.canonicalKey(playerName));
        return getProfile(playerName, true)
            .thenApply(p -> tier(p != null ? p : cached != null ? cached.getStats() : null, bucket));
    }

    /**
     * The {@code /user} fallback after a shard miss (the overlay's scene ranks,
     * the player card): the rank of {@code bucket} from the profile — any cached
     * profile first, so repeated misses don't spam the API, else one in-flight
     * {@code /user} fetch per (name, bucket). {@code null} when no profile has a
     * rank for the player.
     */
    public CompletableFuture<String> getLobbyRank(String playerName, String bucket)
    {
        if (playerName == null || playerName.trim().isEmpty())
        {
            return CompletableFuture.completedFuture(null);
        }
        String key = NameUtils.canonicalKey(playerName);
        String b = bucketPath(bucket);
        UserStats c = statsCache.get(key);
        String cached = c != null ? tier(c.getStats(), b) : null;
        if (cached != null)
        {
            return CompletableFuture.completedFuture(cached);
        }
        String lookupKey = key + ":" + b;
        // Checked before the fetch below starts, so a second caller never issues one.
        CompletableFuture<String> existing = rankLookups.get(lookupKey);
        if (existing != null)
        {
            return existing;
        }
        CompletableFuture<String> future = getProfile(playerName, false).thenApply(p -> tier(p, b));
        future.whenComplete((result, ex) -> rankLookups.remove(lookupKey, future));
        CompletableFuture<String> raced = rankLookups.putIfAbsent(lookupKey, future);
        return raced != null ? raced : future;
    }

    /** The rank of {@code bucket} in a {@code /user} profile: the bucket
     *  object's rank when it has one, else the top-level rank; null without a
     *  profile or a rank. ({@code buckets.overall} and the top level are built
     *  from the same MMR by the backend.) */
    private static String tier(JsonObject profile, String bucket)
    {
        if (profile == null)
        {
            return null;
        }
        JsonObject b = optObject(optObject(profile, "buckets"), bucket);
        String t = b != null ? rankText(b) : null;
        return t != null ? t : rankText(profile);
    }

    /** A {@code /user} rank object's label: "rank" is the tier name ("Dragon",
     *  "3rd Age"), "division" 1-3 — combined as "Dragon 3", the name alone for
     *  division 0; null without a rank. (No "tier" field: that is only in the
     *  S3 shards.) */
    private static String rankText(JsonObject o)
    {
        String rank = optString(o, "rank", null);
        int division = optInt(o, "division", 0);
        return rank == null || rank.isEmpty() ? null : division > 0 ? rank + " " + division : rank;
    }

    /** A bucket's key in URLs and profiles: trimmed and lower case; null or blank is "overall". */
    private static String bucketPath(String bucket)
    {
        return bucket == null || bucket.trim().isEmpty() ? "overall" : bucket.trim().toLowerCase();
    }

    /**
     * Primary Entry Point: Get Rank by Name using the SHA256 Shard Logic.
     * Uses in-flight deduplication to prevent multiple concurrent lookups for
     * the same (player, bucket, bypass).
     *
     * <p>{@code bypassCache=false} is the passive default — overlay refresh
     * tickers, background fight-monitor lookups, etc. It hits the in-memory
     * positive shard cache eagerly to control CDN cost.
     *
     * <p>{@code bypassCache=true} is for paths that need the freshest shard
     * data the backend's DynamoDB-stream-driven incremental writer has produced
     * (≈30 s propagation, per the 2026-05-24 backend handoff). It flips three
     * behaviours vs the passive default:
     * <ul>
     *   <li>Skip the in-memory positive shard cache read in
     *       {@link #getShard(String, boolean)} so the next call hits the
     *       CDN even when a cached payload is still within its TTL.</li>
     *   <li>Skip the {@link #absentUntil} per-player negative
     *       cache read so a player whose rank just landed in the shard
     *       (but who was previously marked "missing") is re-discovered
     *       on the next explicit refresh.</li>
     *   <li>De-duplicate against other in-flight bypass requests
     *       (same name+bucket+bypass) but NOT against passive in-flight
     *       requests — a passive request's cached behaviour would
     *       silently degrade the explicit-refresh contract.</li>
     * </ul>
     *
     * <p>{@code bypassCache=true} still respects the {@link #shardFailUntil}
     * negative-cache (protective backoff against the CDN returning 5xx) and
     * still writes successful payloads into the positive shard cache so
     * subsequent passive readers benefit from the fresher data. Use it for
     * explicit user actions (Player Lookup tab open, [Refresh] button, the
     * player card); passive overlays and auto-triggered post-fight tier checks
     * stay passive to keep CDN cost bounded.
     */
    public CompletableFuture<ShardRank> getShardRank(String playerName, String bucket, boolean bypassCache)
    {
        if (playerName == null || playerName.trim().isEmpty())
        {
            return CompletableFuture.completedFuture(null);
        }

        // 1. Canonicalize Name and bucket (normalize spaces for consistency;
        // NameUtils also maps nameplate   separators to regular spaces
        // so scene names match the backend-canonical shard keys).
        String name = NameUtils.canonicalKey(playerName);
        String b = bucketPath(bucket);

        // Dedupe key includes the bypass flag so a passive cached
        // request and a bypass request never share a future. Two
        // concurrent bypass requests for the same (name,bucket) still
        // share — that's just dedupe and they'd land on the same
        // network result anyway.
        String lookupKey = name + ":" + b + (bypassCache ? ":bypass" : "");
        CompletableFuture<ShardRank> future = new CompletableFuture<>();
        CompletableFuture<ShardRank> running = busyLookups.putIfAbsent(lookupKey, future);
        if (running != null)
        {
            return running;
        }
        // We own this lookup - make sure to clean up when done
        future.whenComplete((result, ex) -> busyLookups.remove(lookupKey, future));

        try
        {
            // Negative cache only applies to passive callers. Explicit
            // refresh paths bypass it intentionally — the 1-h backoff
            // was the root cause of the "rank stuck on Waiting" QA
            // report fixed at 2026-05-24, and the new
            // DynamoDB-stream writer means a "missing" player can flip
            // to "present" within ~30 s of their first match landing.
            Long missingUntil = bypassCache ? null : absentUntil.get(name);
            if (missingUntil != null && currentTimeMillis() < missingUntil)
            {
                future.complete(null);
                return future;
            }

            // 2. Shard Key = first 3 chars of the canonical (lower case) name
            // (e.g., "toyco" -> "toy"); a shorter name is its own key (the
            // server publishes those edge-case shards under their literal
            // name). The server publishes 3-char and 2-char shards; the 3-char
            // ones distribute better (~17576 buckets vs ~676).
            // 3. Fetch/Get Cached Shard — bypass flag propagated so the
            // network call lands when bypassCache=true even if a cached
            // payload sits within TTL.
            getShard(SHARD_URL + "/" + b + "/" + name.substring(0, Math.min(3, name.length())) + ".json", bypassCache)
                .thenAccept(shard -> {
                    // 4. Look for the name in name_rank_info_map; no shard or
                    // no entry marks the player missing (1 hour)
                    JsonObject names = shard == null ? null : shard.getAsJsonObject("name_rank_info_map");
                    if (names == null || !names.has(name))
                    {
                        absentUntil.put(name, currentTimeMillis() + HOUR_MS);
                        future.complete(null);
                        return;
                    }
                    JsonObject entry = names.getAsJsonObject(name);
                    // Scenario B: Redirect — propagate bypass through the
                    // SHA → shard step so a "fresh data please" call doesn't
                    // half-bypass and pick up a stale account_rank_info_map.
                    if (entry.has("redirect"))
                    {
                        redirect(entry.get("redirect").getAsString(), b, 0, bypassCache, future);
                    }
                    else
                    {
                        // Scenario A: Direct Hit
                        future.complete(parseRank(entry));
                    }
                })
                .exceptionally(ex -> {
                    future.complete(null);
                    return null;
                });
        }
        catch (Exception e)
        {
            future.complete(null);
        }
        return future;
    }

    /** Follows an account redirect (at most 10 hops) and completes
     *  {@code future} with the rank it ends on, or null. */
    private void redirect(String sha, String bucket, int depth, boolean bypassCache, CompletableFuture<ShardRank> future)
    {
        // Account SHAs are 64-char hex; the length guard is defensive
        // against a non-SHA identifier.
        if (depth >= 10 || sha == null || sha.length() < 3)
        {
            future.complete(null);
            return;
        }
        // The account's shard (first 3 chars of the SHA) — bypass propagated
        // for redirect chains so an explicit refresh reads fresh at every hop.
        getShard(SHARD_URL + "/" + bucket + "/" + sha.substring(0, 3) + ".json", bypassCache)
            .thenAccept(shard -> {
                JsonObject accounts = shard == null ? null : shard.getAsJsonObject("account_rank_info_map");
                if (accounts == null || !accounts.has(sha))
                {
                    future.complete(null);
                    return;
                }
                JsonObject entry = accounts.getAsJsonObject(sha);
                if (entry.has("redirect"))
                {
                    // Chained redirect
                    redirect(entry.get("redirect").getAsString(), bucket, depth + 1, bypassCache, future);
                }
                else
                {
                    future.complete(parseRank(entry));
                }
            })
            .exceptionally(ex -> {
                future.complete(null);
                return null;
            });
    }

    /** A shard entry's rank: its "tier" (formatted) or else its "rank", with
     *  its world rank (0 when absent or not positive); null when unranked. */
    private ShardRank parseRank(JsonObject o)
    {
        if (RankUtils.isUnranked(o))
        {
            return null;
        }
        int idx = o.has("world_rank") && !o.get("world_rank").isJsonNull() ? o.get("world_rank").getAsInt() : -1;
        String tier = optString(o, "tier", null);
        tier = tier != null ? RankUtils.formatTier(tier) : optString(o, "rank", null);
        return tier == null ? null : new ShardRank(tier, Math.max(idx, 0));
    }

    /**
     * Low-level shard fetch with a 6-hour positive cache and the 60 s failure backoff.
     *
     * <p>The 6-hour TTL: the rank overlay resolves peer ranks through the
     * passive (cached) shard path, so this TTL directly bounds overlay CDN
     * egress at scale — a busy scene re-reads from memory for 6h instead of
     * re-hitting the CDN. Overhead ranks tolerate up to 6h of staleness; a
     * peer's own post-fight rank still updates immediately via the API-set
     * path. Pinned by PvPDataServiceShardCacheTtlTest.
     *
     * <p>When {@code bypassCache=true} the positive cache read is skipped so the
     * call always hits the CDN; the {@link #shardFailUntil} negative backoff is
     * still honoured (we don't want explicit refreshes to thunder-stampede a
     * 5xx). Successful responses still populate {@link #shardCache} so any
     * concurrent passive reader benefits from the fresher payload. Pair with
     * {@link #getShardRank}, which threads the same flag through the
     * redirect chain.
     */
    public CompletableFuture<JsonObject> getShard(String url, boolean bypassCache)
    {
        return fetchCached(url, shardCache, shardFailUntil, 6 * HOUR_MS, bypassCache);
    }

    /**
     * Generic cached-JSON GET shared by the rank_idx shard reader
     * ({@link #getShard(String, boolean)}) and the leaderboard reader
     * ({@link #getLeaderboard(String)}). Each caller passes its OWN
     * {@code cache} + {@code failUntil} maps and TTL so the two artifacts
     * never evict or shadow one another. Positive cache hits short-circuit
     * before any HTTP work; the failure-backoff is honoured even on
     * {@code bypassCache} (it guards the CDN against a 5xx stampede rather
     * than serving fresh data).
     */
    private CompletableFuture<JsonObject> fetchCached(String url,
        Map<String, UserStats> cache, ConcurrentHashMap<String, Long> failUntil,
        long ttlMs, boolean bypassCache)
    {
        long now = currentTimeMillis();

        // 1. Check Memory Cache — bypassed when bypassCache=true so an
        // explicit refresh picks up the latest write instead of serving a
        // payload that might be up to ttlMs stale.
        if (!bypassCache)
        {
            UserStats cached = cache.get(url);
            if (cached != null && now - cached.getTimestamp() < ttlMs)
            {
                return CompletableFuture.completedFuture(cached.getStats());
            }
        }

        // 2. Check Negative Cache (Fail Until). Still honoured even
        // on bypass — this is a protective backoff against the CDN
        // returning 5xx, not a freshness cache, and an explicit
        // refresh shouldn't be a thundering-herd vector.
        Long failAt = failUntil.get(url);
        if (failAt != null && now < failAt)
        {
            return CompletableFuture.completedFuture(null);
        }

        // 3. Join an in-flight fetch for this URL if one exists. A
        // bypassing caller may join too: the request it would join is
        // already on its way to the origin, so its result is exactly as
        // fresh as one this caller issued itself. See
        // {@link #jsonFetches} for why this matters at scale.
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        CompletableFuture<JsonObject> alreadyRunning = jsonFetches.putIfAbsent(url, future);
        if (alreadyRunning != null)
        {
            return alreadyRunning;
        }
        // The owner gets a future that only settles once the in-flight
        // entry has been cleared, so a caller acting on the result can
        // immediately issue a follow-up fetch without racing our cleanup.
        CompletableFuture<JsonObject> owned =
            future.whenComplete((v, ex) -> jsonFetches.remove(url, future));

        // 4. Download
        enqueue(new Request.Builder().url(url).get().build(), new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                failUntil.put(url, currentTimeMillis() + MINUTE_MS);
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        failUntil.put(url, currentTimeMillis() + MINUTE_MS);
                        future.complete(null);
                        return;
                    }
                    String body;
                    try
                    {
                        body = res.body().string();
                    }
                    catch (IOException cacheEx)
                    {
                        // Windows cache file locking issue - request succeeded but cache failed
                        // Try to serve stale cache if available
                        UserStats stale = cache.get(url);
                        future.complete(stale != null ? stale.getStats() : null);
                        return;
                    }
                    JsonObject json = gson.fromJson(body, JsonObject.class);
                    cache.put(url, new UserStats(json, currentTimeMillis()));
                    failUntil.remove(url);
                    future.complete(json);
                }
                catch (Exception e)
                {
                    future.complete(null);
                }
            }
        });
        return owned;
    }

    /**
     * Fetch the per-bucket MMR→rank histogram used by the "What are the
     * ranks" view to derive a live "Top X%" for each tier.
     *
     * <p>Reads {@code rank_hist/<bucket>.json} from the static site (the
     * same CDN origin as {@code /rank_idx} shards) via the shared
     * {@link #getShard(String, boolean)} fetch, so it inherits the positive
     * cache and the 60-second failure backoff. The payload shape is
     * {@code {"bin_width", "total", "bins":[[floor,count,count_above],...]}}
     * — see {@code backend/core/rank_histogram.py}.
     *
     * @param bucket one of {@code overall|nh|veng|multi|dmm}; null/blank
     *               defaults to {@code overall}.
     * @return the parsed histogram, or {@code null} if it could not be
     *         fetched/parsed (callers render "No one yet").
     */
    public CompletableFuture<JsonObject> getHistogram(String bucket)
    {
        return getShard(HIST_URL + "/" + bucketPath(bucket) + ".json", false).exceptionally(ex -> null);
    }

    /**
     * Fetch the cached Top Players leaderboard for a bucket — the exact same
     * static S3 artifact the website reads, so a single CDN object serves
     * both surfaces and is cached ~1 hour.
     *
     * <p>This is the exact pregenerated top-550 S3 artifact the website's
     * {@code fetchLeaderboard()} reads — overall at {@code /leaderboard.json},
     * per-bucket at {@code /leaderboard_<bucket>.json} (see the website's
     * {@code BUCKET_S3_KEYS} + the infra {@code leaderboard_cache_writer.py}).
     * It is fetched on its OWN {@link #topCache} (NOT the rank_idx
     * shard cache — a leaderboard is a ranked list, not a name→rank shard),
     * with a 60-minute positive cache that mirrors the file's hourly refresh
     * and a 60-second failure backoff. The caller (TopPlayers) parses,
     * de-dupes by account, sorts by MMR and displays only the top 100 — same
     * pipeline as the site. Payload shape:
     * {@code {"players":[{"account","player_names":[..],"mmr","rank","division","icon"}],"bucket"}}.
     *
     * @param bucket one of {@code overall|nh|veng|multi|dmm}; null/blank → {@code overall}.
     * @return the parsed leaderboard, or {@code null} if it couldn't be fetched/parsed.
     */
    public CompletableFuture<JsonObject> getLeaderboard(String bucket)
    {
        String b = bucketPath(bucket);
        String url = PvpConsts.SITE_URL + ("overall".equals(b) ? "/leaderboard.json" : "/leaderboard_" + b + ".json");
        return fetchCached(url, topCache, topFailUntil, HOUR_MS, false)
            .exceptionally(ex -> null);
    }

    /**
     * Check if a world is a DMM world.
     * DMM is currently inactive — hardcoded to world 345 only.
     * Re-enable the API fetch below when the next DMM season starts.
     */
    public boolean isDmmWorld(int world)
    {
        return world == 345;
    }

    /*
     * ====================================================================
     * DMM API fetch code — commented out while DMM is inactive.
     * Un-comment and restore isDmmWorld/getDmmWorlds/refreshDmmWorlds
     * (refreshDmmWorlds was called on every login, before any fight)
     * when the next DMM season starts.
     * ====================================================================
     *
     * private void refreshDmmWorldsIfNeeded()
     * {
     *     long now = System.currentTimeMillis();
     *     if (now - dmmWorldsCacheTimestamp < DMM_WORLDS_CACHE_TTL_MS && !dmmWorldsCache.isEmpty())
     *     {
     *         return;
     *     }
     *
     *     if (dmmWorldsFetchInProgress)
     *     {
     *         return;
     *     }
     *
     *     dmmWorldsFetchInProgress = true;
     *     fetchDmmWorlds().whenComplete((worlds, ex) -> {
     *         dmmWorldsFetchInProgress = false;
     *         if (worlds != null && !worlds.isEmpty())
     *         {
     *             dmmWorldsCache = worlds;
     *             dmmWorldsCacheTimestamp = System.currentTimeMillis();
     *             log.debug("[DMM] Updated DMM worlds cache: {}", worlds);
     *         }
     *         else if (ex != null)
     *         {
     *             log.debug("[DMM] Failed to fetch DMM worlds: {}", ex.getMessage());
     *         }
     *     });
     * }
     *
     * private CompletableFuture<Set<Integer>> fetchDmmWorlds()
     * {
     *     CompletableFuture<Set<Integer>> future = new CompletableFuture<>();
     *
     *     String url = API_BASE_URL + "/config/worlds";
     *     Request request = new Request.Builder().url(url).get().build();
     *
     *     log.debug("[DMM] Fetching DMM worlds from {}", url);
     *
     *     enqueue(request, new Callback()
     *     {
     *         @Override
     *         public void onFailure(Call call, IOException e)
     *         {
     *             log.debug("[DMM] Network failure fetching DMM worlds: {}", e.getMessage());
     *             future.complete(new HashSet<>());
     *         }
     *
     *         @Override
     *         public void onResponse(Call call, Response response) throws IOException
     *         {
     *             try (Response res = response)
     *             {
     *                 if (!res.isSuccessful())
     *                 {
     *                     log.debug("[DMM] HTTP error {} fetching DMM worlds", res.code());
     *                     future.complete(new HashSet<>());
     *                     return;
     *                 }
     *
     *                 ResponseBody body = res.body();
     *                 if (body == null)
     *                 {
     *                     future.complete(new HashSet<>());
     *                     return;
     *                 }
     *
     *                 String bodyString = body.string();
     *                 log.debug("[DMM] Response: {}", bodyString);
     *
     *                 Set<Integer> worlds = new HashSet<>();
     *                 JsonObject json = gson.fromJson(bodyString, JsonObject.class);
     *
     *                 if (json.has("dmm") && json.get("dmm").isJsonArray())
     *                 {
     *                     for (var element : json.getAsJsonArray("dmm"))
     *                     {
     *                         worlds.add(element.getAsInt());
     *                     }
     *                 }
     *
     *                 future.complete(worlds);
     *             }
     *             catch (Exception e)
     *             {
     *                 log.debug("[DMM] Error parsing DMM worlds response: {}", e.getMessage());
     *                 future.complete(new HashSet<>());
     *             }
     *         }
     *     });
     *
     *     return future;
     * }
     */

}
