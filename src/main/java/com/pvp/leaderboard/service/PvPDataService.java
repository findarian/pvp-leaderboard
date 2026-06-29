package com.pvp.leaderboard.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.pvp.leaderboard.PvPLeaderboardConstants;
import com.pvp.leaderboard.cache.MatchesCacheEntry;
import com.pvp.leaderboard.cache.ShardEntry;
import com.pvp.leaderboard.cache.UserStatsCache;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.util.RankUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
public class PvPDataService
{
	private static final String API_BASE_URL = "https://l5xya0wf0d.execute-api.us-east-1.amazonaws.com/prod";
    private static final String SHARD_BASE_URL = PvPLeaderboardConstants.PUBLIC_SITE_BASE_URL + "/rank_idx";
    /** Per-bucket MMR→rank histogram published hourly by the infra side
     *  ({@code OSRS-MMR/lambda_code/distribution_cache_writer.py} →
     *  {@code rank_hist/<bucket>.json}). Powers the "What are the ranks"
     *  Top-X%-per-tier view. */
    private static final String RANK_HIST_BASE_URL = PvPLeaderboardConstants.PUBLIC_SITE_BASE_URL + "/rank_hist";

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ClientIdentityService clientIdentityService;

	// Shard lookup caching
    // Per spec: Shard Files = 60m TTL
	private static final long SHARD_CACHE_EXPIRY_MS = 60L * 60L * 1000L; 
    // "Not Found" State = 1 hour TTL
	private static final long MISSING_PLAYER_BACKOFF_MS = 60L * 60L * 1000L;
    // Failed fetch backoff
    private static final long SHARD_FAIL_BACKOFF_MS = 60L * 1000L;

	// In-flight request deduplication for getShardRankByName
	private final ConcurrentHashMap<String, CompletableFuture<ShardRank>> inFlightLookups = new ConcurrentHashMap<>();

	private final Map<String, ShardEntry> shardCache = Collections.synchronizedMap(
		new LinkedHashMap<String, ShardEntry>(128, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, ShardEntry> eldest)
			{
				return size() > 512; // LRU cap
			}
		}
	);

	private final ConcurrentHashMap<String, Long> shardFailUntil = new ConcurrentHashMap<>();
	
    // Negative cache for specific players/accounts to avoid re-checking shards
	private final ConcurrentHashMap<String, Long> missingPlayerUntilMs = new ConcurrentHashMap<>();

	// Top Players leaderboard caching — kept SEPARATE from the rank_idx shard
	// cache above on purpose. The leaderboard is a different artifact: the
	// pregenerated top-550 list (/leaderboard[_<bucket>].json) the website
	// reads, refreshed hourly by the infra leaderboard_cache_writer.py — NOT a
	// name→rank shard. Sharing shardCache would let shard churn (its 512-entry
	// LRU) evict the leaderboard (and vice versa) and conflate two unrelated
	// resources. Only ~5 buckets, so a plain map (no LRU) is plenty.
	private static final long LEADERBOARD_CACHE_EXPIRY_MS = 60L * 60L * 1000L; // 60m, mirrors the hourly refresh
	private final ConcurrentHashMap<String, ShardEntry> leaderboardCache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> leaderboardFailUntil = new ConcurrentHashMap<>();

    /**
     * Clears the shard negative cache for a player. Call this when API confirms player exists.
     */
    public void clearShardNegativeCache(String playerName) {
        if (playerName == null) return;
        String canonicalName = playerName.trim().replaceAll("\\s+", " ").toLowerCase();
        missingPlayerUntilMs.remove(canonicalName);
    }

    // User Profile Caching
    private static final long USER_CACHE_TTL_MS = 1L * 60L * 1000L; // 1 minute
    private final ConcurrentHashMap<String, UserStatsCache> userStatsCache = new ConcurrentHashMap<>();

    /** In-flight dedupe for lobby roster {@code /user} fallbacks after
     *  a shard miss — at most one network roundtrip per (player, bucket)
     *  at a time. */
    private final ConcurrentHashMap<String, CompletableFuture<ShardRank>> inFlightProfileRankLookups =
        new ConcurrentHashMap<>();

    // Matches Caching
    private static final long MATCHES_CACHE_TTL_MS = 1L * 60L * 1000L; // 1 minute
    private final ConcurrentHashMap<String, MatchesCacheEntry> matchesCache = new ConcurrentHashMap<>();

    // DMM Worlds Caching — commented out while DMM is inactive, re-enable with API fetch
    // private static final long DMM_WORLDS_CACHE_TTL_MS = 60L * 60L * 1000L;
    // private volatile Set<Integer> dmmWorldsCache = new HashSet<>();
    // private volatile long dmmWorldsCacheTimestamp = 0L;
    // private volatile boolean dmmWorldsFetchInProgress = false;

	@Inject
	public PvPDataService(OkHttpClient okHttpClient, Gson gson, PvPLeaderboardConfig config, ClientIdentityService clientIdentityService)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.clientIdentityService = clientIdentityService;
	}


	public CompletableFuture<JsonObject> getPlayerMatches(String playerName, String nextToken, int limit)
	{
		return getPlayerMatches(playerName, nextToken, limit, false);
	}

	/**
	 * Fetch player match history with optional cache bypass.
	 * @param playerName The player to fetch matches for
	 * @param nextToken Pagination token (null for first page)
	 * @param limit Max matches to return
	 * @param bypassCache If true, skip cache and always fetch fresh data (use for MMR delta lookups)
	 */
	public CompletableFuture<JsonObject> getPlayerMatches(String playerName, String nextToken, int limit, boolean bypassCache)
	{
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

        // Check cache (only for first page, i.e. no nextToken, and if not bypassing)
        if (!bypassCache && (nextToken == null || nextToken.isEmpty())) {
            String cacheKey = "matches:" + playerName + ":" + limit;
            MatchesCacheEntry cached = matchesCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() - cached.getTimestamp() <= MATCHES_CACHE_TTL_MS) {
                future.complete(cached.getResponse().deepCopy());
                return future;
            }
        }

		HttpUrl urlObj = HttpUrl.parse(API_BASE_URL + "/matches");
		if (urlObj == null) {
			future.completeExceptionally(new IOException("Invalid base URL"));
			return future;
		}
		HttpUrl.Builder urlBuilder = urlObj.newBuilder()
			.addQueryParameter("player_id", playerName)
			.addQueryParameter("limit", String.valueOf(limit));

		if (nextToken != null && !nextToken.isEmpty())
		{
			urlBuilder.addQueryParameter("next_token", nextToken);
		}

		Request.Builder requestBuilder = new Request.Builder()
			.url(urlBuilder.build())
			.get();

		if (bypassCache) {
			requestBuilder.cacheControl(CacheControl.FORCE_NETWORK);
		}
		
		// Add client UUID header for API authentication/tracking
		String clientUuid = clientIdentityService != null ? clientIdentityService.getClientUniqueId() : null;
		if (clientUuid != null && !clientUuid.isEmpty())
		{
			requestBuilder.addHeader("X-Client-Unique-Id", clientUuid);
		}
		
		Request request = requestBuilder.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
                // Try serving stale cache if available
                if (nextToken == null || nextToken.isEmpty()) {
                    String cacheKey = "matches:" + playerName + ":" + limit;
                    MatchesCacheEntry cached = matchesCache.get(cacheKey);
                    if (cached != null) {
                        future.complete(cached.getResponse().deepCopy());
                        return;
                    }
                }
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response res = response)
				{
					if (!res.isSuccessful())
					{
                        // Try serving stale cache if available
                        if (nextToken == null || nextToken.isEmpty()) {
                            String cacheKey = "matches:" + playerName + ":" + limit;
                            MatchesCacheEntry cached = matchesCache.get(cacheKey);
                            if (cached != null) {
                                future.complete(cached.getResponse().deepCopy());
                                return;
                            }
                        }
						future.completeExceptionally(new IOException("API call failed with status: " + res.code()));
						return;
					}

					ResponseBody body = res.body();
					if (body == null)
					{
						future.complete(new JsonObject());
						return;
					}

					try
					{
						String bodyString;
						try {
							bodyString = body.string();
						} catch (IOException cacheEx) {
							future.complete(new JsonObject());
							return;
						}
						JsonObject json = gson.fromJson(bodyString, JsonObject.class);
						if (nextToken == null || nextToken.isEmpty()) {
                            String cacheKey = "matches:" + playerName + ":" + limit;
                            matchesCache.put(cacheKey, new MatchesCacheEntry(json.deepCopy(), System.currentTimeMillis()));
                        }
						future.complete(json);
					}
					catch (JsonSyntaxException e)
					{
						future.completeExceptionally(e);
					}
				}
			}
		});

		return future;
	}

	/**
	 * Fetch match history by account SHA256 hash (derived from UUID).
	 * This returns ALL matches across ALL account names for this identity.
	 * Use this for accurate MMR delta lookups even after name changes.
	 * 
	 * @param acctSha SHA256 hash of the player's UUID
	 * @param nextToken Pagination token (null for first page)
	 * @param limit Max matches to return
	 * @param bypassCache If true, skip cache and always fetch fresh data
	 */
	public CompletableFuture<JsonObject> getPlayerMatchesByAcct(String acctSha, String nextToken, int limit, boolean bypassCache)
	{
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		if (acctSha == null || acctSha.isEmpty()) {
			future.complete(new JsonObject());
			return future;
		}

		String acctShaLower = acctSha.toLowerCase();

		// Check cache (only for first page, i.e. no nextToken, and if not bypassing)
		if (!bypassCache && (nextToken == null || nextToken.isEmpty())) {
			String cacheKey = "matches:acct:" + acctShaLower + ":" + limit;
			MatchesCacheEntry cached = matchesCache.get(cacheKey);
			if (cached != null && System.currentTimeMillis() - cached.getTimestamp() <= MATCHES_CACHE_TTL_MS) {
				future.complete(cached.getResponse().deepCopy());
				return future;
			}
		}

		HttpUrl urlObj = HttpUrl.parse(API_BASE_URL + "/matches");
		if (urlObj == null) {
			future.completeExceptionally(new IOException("Invalid base URL"));
			return future;
		}
		
		// Use 'acct' parameter instead of 'player_id' for SHA256-based lookup
		HttpUrl.Builder urlBuilder = urlObj.newBuilder()
			.addQueryParameter("acct", acctShaLower)
			.addQueryParameter("limit", String.valueOf(limit));

		if (nextToken != null && !nextToken.isEmpty()) {
			urlBuilder.addQueryParameter("next_token", nextToken);
		}

		Request.Builder requestBuilder = new Request.Builder()
			.url(urlBuilder.build())
			.get();

		if (bypassCache) {
			requestBuilder.cacheControl(CacheControl.FORCE_NETWORK);
		}

		// Add client UUID header for API authentication/tracking
		String clientUuid = clientIdentityService != null ? clientIdentityService.getClientUniqueId() : null;
		if (clientUuid != null && !clientUuid.isEmpty()) {
			requestBuilder.addHeader("X-Client-Unique-Id", clientUuid);
		}

		Request request = requestBuilder.build();
		log.debug("[MatchesAPI] acct request URL: {} bypassCache={}", request.url(), bypassCache);
		final long reqStart = System.nanoTime();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				long ms = (System.nanoTime() - reqStart) / 1_000_000;
				log.debug("[MatchesAPI] acct request FAILED after {}ms: {}", ms, e.getMessage());
				if (nextToken == null || nextToken.isEmpty()) {
					String cacheKey = "matches:acct:" + acctShaLower + ":" + limit;
					MatchesCacheEntry cached = matchesCache.get(cacheKey);
					if (cached != null) {
						future.complete(cached.getResponse().deepCopy());
						return;
					}
				}
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				long ms = (System.nanoTime() - reqStart) / 1_000_000;
				try (Response res = response)
				{
					if (!res.isSuccessful())
					{
						log.debug("[MatchesAPI] acct response HTTP {} after {}ms", res.code(), ms);
						if (nextToken == null || nextToken.isEmpty()) {
							String cacheKey = "matches:acct:" + acctShaLower + ":" + limit;
							MatchesCacheEntry cached = matchesCache.get(cacheKey);
							if (cached != null) {
								future.complete(cached.getResponse().deepCopy());
								return;
							}
						}
						future.completeExceptionally(new IOException("API call failed with status: " + res.code()));
						return;
					}

					ResponseBody body = res.body();
					if (body == null)
					{
						log.debug("[MatchesAPI] acct response empty body after {}ms", ms);
						future.complete(new JsonObject());
						return;
					}

					try
					{
						String bodyString;
						try {
							bodyString = body.string();
						} catch (IOException cacheEx) {
							future.complete(new JsonObject());
							return;
						}
						log.debug("[MatchesAPI] acct response OK in {}ms, bodyLen={}", ms, bodyString.length());
						JsonObject json = gson.fromJson(bodyString, JsonObject.class);
						if (nextToken == null || nextToken.isEmpty()) {
							String cacheKey = "matches:acct:" + acctShaLower + ":" + limit;
							matchesCache.put(cacheKey, new MatchesCacheEntry(json.deepCopy(), System.currentTimeMillis()));
						}
						future.complete(json);
					}
					catch (JsonSyntaxException e)
					{
						future.completeExceptionally(e);
					}
				}
			}
		});

		return future;
	}

	/**
	 * Get the account SHA256 hash for the current client's UUID.
	 * Returns null if UUID is not available.
	 */
	public String getSelfAcctSha()
	{
		try {
			String uuid = clientIdentityService != null ? clientIdentityService.getClientUniqueId() : null;
			if (uuid != null && !uuid.isEmpty()) {
				return generateAcctSha(uuid);
			}
		} catch (Exception e) {
			// ignored
		}
		return null;
	}

	/**
	 * Fetch ALL match pages for a player by name, paginating through every next_token.
	 * Returns a single JsonArray containing every match across all pages.
	 * Caps at 50 pages to avoid runaway requests.
	 */
	public CompletableFuture<JsonArray> getAllPlayerMatches(String playerName) {
		CompletableFuture<JsonArray> future = new CompletableFuture<>();
		JsonArray allMatches = new JsonArray();
		log.debug("[TierGraph] getAllPlayerMatches: starting full fetch by name for '{}'", playerName);
		fetchAllPages(null, playerName, null, allMatches, future, 0, 5);
		return future;
	}

	/**
	 * Fetch ALL match pages for a player by account SHA, paginating through every next_token.
	 * Use this for self-lookups to capture all matches across name changes.
	 */
	public CompletableFuture<JsonArray> getAllPlayerMatchesByAcct(String acctSha) {
		CompletableFuture<JsonArray> future = new CompletableFuture<>();
		if (acctSha == null || acctSha.isEmpty()) {
			future.complete(new JsonArray());
			return future;
		}
		JsonArray allMatches = new JsonArray();
		log.debug("[TierGraph] getAllPlayerMatchesByAcct: starting full fetch by acct SHA");
		fetchAllPages(acctSha, null, null, allMatches, future, 0, 5);
		return future;
	}

	private void fetchAllPages(String acctSha, String playerName, String nextToken,
							   JsonArray accumulator, CompletableFuture<JsonArray> future,
							   int page, int maxPages) {
		if (page >= maxPages) {
			log.debug("[TierGraph] fetchAllPages: hit max {} pages, total matches so far: {}", maxPages, accumulator.size());
			future.complete(accumulator);
			return;
		}
		log.debug("[TierGraph] fetchAllPages: page={}, hasToken={}, accumulated={}, mode={}",
				page, nextToken != null, accumulator.size(), acctSha != null ? "acct" : "name");

		CompletableFuture<JsonObject> pageFuture;
		if (acctSha != null) {
			pageFuture = getPlayerMatchesByAcct(acctSha, nextToken, 1000, true);
		} else {
			pageFuture = getPlayerMatches(playerName, nextToken, 1000, true);
		}

		pageFuture.thenAccept(response -> {
			if (response == null) {
				log.debug("[TierGraph] fetchAllPages: null response on page {}, stopping with {} matches", page, accumulator.size());
				future.complete(accumulator);
				return;
			}
			int pageSize = 0;
			if (response.has("matches") && response.get("matches").isJsonArray()) {
				JsonArray pageMatches = response.getAsJsonArray("matches");
				pageSize = pageMatches.size();
				for (var el : pageMatches) {
					accumulator.add(el);
				}
			}
			log.debug("[TierGraph] fetchAllPages: page {} returned {} matches, total now {}", page, pageSize, accumulator.size());
			String next = response.has("next_token") && !response.get("next_token").isJsonNull()
					? response.get("next_token").getAsString() : null;
			if (next != null && !next.isEmpty()) {
				fetchAllPages(acctSha, playerName, next, accumulator, future, page + 1, maxPages);
			} else {
				log.debug("[TierGraph] fetchAllPages: no more pages, total: {} matches", accumulator.size());
				future.complete(accumulator);
			}
		}).exceptionally(ex -> {
			log.warn("[TierGraph] fetchAllPages: error on page {}, stopping with {} matches", page, accumulator.size(), ex);
			future.complete(accumulator);
			return null;
		});
	}

	public CompletableFuture<JsonObject> getUserProfile(String playerName, String clientUniqueId)
	{
		return getUserProfile(playerName, clientUniqueId, false);
	}

    private static String canonicalUserCacheKey(String playerName)
    {
        if (playerName == null) return "";
        return playerName.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static String userProfileCacheKey(String playerName)
    {
        return "user:" + canonicalUserCacheKey(playerName);
    }

	public CompletableFuture<JsonObject> getUserProfile(String playerName, String clientUniqueId, boolean forceRefresh)
	{
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		String cacheKey = userProfileCacheKey(playerName);
		UserStatsCache cached = userStatsCache.get(cacheKey);
		if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.getTimestamp() <= USER_CACHE_TTL_MS) {
			future.complete(cached.getStats().deepCopy());
			return future;
		}

		HttpUrl urlObj = HttpUrl.parse(API_BASE_URL + "/user");
		if (urlObj == null) {
			future.completeExceptionally(new IOException("Invalid base URL"));
			return future;
		}
		HttpUrl url = urlObj.newBuilder()
			.addQueryParameter("player_id", playerName)
			.addQueryParameter("include_world_rank", "1")
			.build();

		Request.Builder requestBuilder = new Request.Builder().url(url).get();
		
		// Add client UUID header for API authentication/tracking
		String clientUuid = clientIdentityService != null ? clientIdentityService.getClientUniqueId() : null;
		if (clientUuid != null && !clientUuid.isEmpty())
		{
			requestBuilder.addHeader("X-Client-Unique-Id", clientUuid);
		}
		
		Request request = requestBuilder.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				UserStatsCache stale = userStatsCache.get(cacheKey);
				if (stale != null) {
					future.complete(stale.getStats().deepCopy());
					return;
				}
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response res = response)
				{
					if (!res.isSuccessful())
					{
						if (res.code() == 404) {
							future.complete(null);
							return;
						}
						future.completeExceptionally(new IOException("API error: " + res.code()));
						return;
					}
					ResponseBody body = res.body();
					String bodyString;
					try {
						bodyString = body != null ? body.string() : "{}";
					} catch (IOException cacheEx) {
						// Windows cache file locking issue - request succeeded but cache failed
						// Try to serve stale cache if available
						UserStatsCache stale = userStatsCache.get(cacheKey);
						if (stale != null) {
							future.complete(stale.getStats().deepCopy());
							return;
						}
						future.complete(null);
						return;
					}
					JsonObject json = gson.fromJson(bodyString, JsonObject.class);
					
					// Detect soft 404: API returns 200 with {"message": "player not found"}
					if (json.has("message") && !json.has("player_id") && !json.has("player_name") && !json.has("mmr")) {
						future.complete(null);
						return;
					}
					
					userStatsCache.put(cacheKey, new UserStatsCache(json.deepCopy(), System.currentTimeMillis()));
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
     * Get tier from user profile API using /user endpoint.
     * /user returns: rank (tier name like "Dragon"), division (1-3), buckets (per-bucket data)
     * Returns combined tier string like "Dragon 3" or just "Dragon" if no division.
     */
    public CompletableFuture<String> getTierFromProfile(String playerName, String bucket)
    {
        // Force refresh to get fresh data after a fight, but keep cached data as fallback
        String cacheKey = userProfileCacheKey(playerName);
        UserStatsCache cachedData = userStatsCache.get(cacheKey);
        
        return getUserProfile(playerName, null, true).thenApply(profile -> {
            if (profile == null) {
                // Try fallback to cached data if available
                if (cachedData != null) {
                    JsonObject cachedProfile = cachedData.getStats();
                    if (cachedProfile != null) {
                        // Try bucket-specific data first
                        if (cachedProfile.has("buckets") && cachedProfile.get("buckets").isJsonObject()) {
                            JsonObject buckets = cachedProfile.getAsJsonObject("buckets");
                            if (buckets.has(bucket) && buckets.get(bucket).isJsonObject()) {
                                JsonObject bucketData = buckets.getAsJsonObject(bucket);
                                String tier = extractTierFromUserResponse(bucketData, playerName, bucket);
                                if (tier != null) {
                                    return tier;
                                }
                            }
                        }
                        // Fallback to top-level
                        return extractTierFromUserResponse(cachedProfile, playerName, "overall");
                    }
                }
                return null;
            }
            
            // Try bucket-specific data first (in "buckets" object)
            if (profile.has("buckets") && profile.get("buckets").isJsonObject()) {
                JsonObject buckets = profile.getAsJsonObject("buckets");
                if (buckets.has(bucket) && buckets.get(bucket).isJsonObject()) {
                    JsonObject bucketData = buckets.getAsJsonObject(bucket);
                    String tier = extractTierFromUserResponse(bucketData, playerName, bucket);
                    if (tier != null) {
                        return tier;
                    }
                }
            }
            
            // Fallback to top-level rank/division fields (for overall bucket)
            String tier = extractTierFromUserResponse(profile, playerName, "overall");
            if (tier != null) {
                return tier;
            }
            
            return null;
        });
    }
    
    /**
     * Extract tier from /user response object.
     * /user returns "rank" (tier name like "Bronze", "Dragon") and "division" (1, 2, or 3)
     * NOT a "tier" field - that's only in S3 shards.
     */
    private String extractTierFromUserResponse(JsonObject obj, String playerName, String bucket) {
        if (obj == null) return null;
        
        String rank = null;
        int division = 0;
        
        // /user endpoint uses "rank" for tier name (e.g., "Bronze", "Dragon", "3rd Age")
        if (obj.has("rank") && !obj.get("rank").isJsonNull()) {
            rank = obj.get("rank").getAsString();
        }
        // division is 1, 2, or 3
        if (obj.has("division") && !obj.get("division").isJsonNull()) {
            division = obj.get("division").getAsInt();
        }
        
        if (rank != null && !rank.isEmpty()) {
            // Combine rank and division (e.g., "Dragon 3")
            String tier = division > 0 ? rank + " " + division : rank;
            return tier;
        }
        return null;
    }

    /**
     * API lookup for post-fight rank refresh (bypasses shards for fresh data).
     * Uses /user endpoint which returns rank and division fields.
     */
	public CompletableFuture<String> getPlayerTier(String playerName, String bucket)
	{
        // Delegate to getTierFromProfile which uses /user endpoint correctly
        return getTierFromProfile(playerName, bucket);
	}

	/**
	 * Synchronous secondary cache for the lobby's rank enrichment. When
	 * {@link #getShardRankByName(String, String)} returns {@code null}
	 * (player not present in the CDN shard for this bucket, e.g. the
	 * shard generator hasn't picked them up yet, or the bucket is a
	 * style they haven't played enough matches in), the lobby panel
	 * falls back to this method. Returns a {@link ShardRank} synthesised
	 * from the {@code /user} response previously cached by
	 * {@link #getUserProfile(String, String)} — i.e. anything the user
	 * has explicitly opened a Player Lookup on this session.
	 *
	 * <p>Rank position is set to 0 because the {@code /user} endpoint
	 * doesn't return a global leaderboard position; lobby enrichment
	 * keys on tier name alone via
	 * {@link com.pvp.leaderboard.util.RankUtils#rankIndexForTier},
	 * so a 0 position is harmless. Returns {@code null} when there's
	 * no cached profile or the profile has no rank for the requested
	 * bucket.
	 */
	public ShardRank getRankFromCachedProfile(String playerName, String bucket)
	{
		if (playerName == null || playerName.isEmpty()) return null;
		String cacheKey = userProfileCacheKey(playerName);
		UserStatsCache cached = userStatsCache.get(cacheKey);
		if (cached == null) return null;
		JsonObject profile = cached.getStats();
		if (profile == null) return null;
		String tier = tierFromUserProfile(profile, bucket);
		if (tier == null) return null;
		return new ShardRank(tier, 0);
	}

	/**
	 * Lobby roster rank fallback after a shard miss. Whitelist membership
	 * is irrelevant — any lobby member gets at most one in-flight
	 * {@code /user} fetch per (name, bucket). Warm session cache is
	 * checked first so repeated roster pushes don't spam the API.
	 */
	public CompletableFuture<ShardRank> getRankFromProfileForLobby(String playerName, String bucket)
	{
		if (playerName == null || playerName.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}
		ShardRank cached = getRankFromCachedProfile(playerName, bucket);
		if (cached != null)
		{
			return CompletableFuture.completedFuture(cached);
		}
		String canon = canonicalUserCacheKey(playerName);
		String bucketPath = (bucket == null || bucket.isEmpty()) ? "overall" : bucket.toLowerCase();
		String lookupKey = canon + ":" + bucketPath;
		CompletableFuture<ShardRank> existing = inFlightProfileRankLookups.get(lookupKey);
		if (existing != null)
		{
			return existing;
		}
		CompletableFuture<ShardRank> future = getUserProfile(playerName, null, false)
			.thenApply(profile ->
			{
				if (profile == null) return null;
				String tier = tierFromUserProfile(profile, bucketPath);
				return tier != null ? new ShardRank(tier, 0) : null;
			});
		future.whenComplete((result, ex) -> inFlightProfileRankLookups.remove(lookupKey, future));
		CompletableFuture<ShardRank> raced = inFlightProfileRankLookups.putIfAbsent(lookupKey, future);
		return raced != null ? raced : future;
	}

	private String tierFromUserProfile(JsonObject profile, String bucket)
	{
		if (profile == null) return null;
		String bucketPath = (bucket == null || bucket.isEmpty()) ? "overall" : bucket.toLowerCase();
		String resolvedTier = null;
		if (!"overall".equals(bucketPath)
			&& profile.has("buckets")
			&& profile.get("buckets").isJsonObject())
		{
			JsonObject buckets = profile.getAsJsonObject("buckets");
			if (buckets.has(bucketPath) && buckets.get(bucketPath).isJsonObject())
			{
				resolvedTier = extractTierFromUserResponse(
					buckets.getAsJsonObject(bucketPath), null, bucketPath);
			}
		}
		if (resolvedTier == null)
		{
			resolvedTier = extractTierFromUserResponse(profile, null, "overall");
		}
		return resolvedTier;
	}

	/**
     * Primary Entry Point: Get Rank by Name using the SHA256 Shard Logic
     * Uses in-flight deduplication to prevent multiple concurrent lookups for the same (player, bucket).
     *
     * <p>Passive default — overlay refresh tickers, background fight-monitor
     * lookups, etc. should call this variant. Hits the in-memory positive
     * shard cache eagerly to control CDN cost.
     */
	public CompletableFuture<ShardRank> getShardRankByName(String playerName, String bucket)
	{
        return getShardRankByName(playerName, bucket, false);
	}

	/**
     * Cache-aware overload of {@link #getShardRankByName(String, String)} for
     * paths that need the freshest shard data the backend's
     * DynamoDB-stream-driven incremental writer has produced (≈30 s
     * propagation, per the 2026-05-24 backend handoff).
     *
     * <p>{@code bypassCache=true} flips three behaviours vs the passive
     * default:
     * <ul>
     *   <li>Skip the in-memory positive shard cache read in
     *       {@link #getShard(String, boolean)} so the next call hits the
     *       CDN even when a cached payload is still within the 60-min
     *       TTL.</li>
     *   <li>Skip the {@link #missingPlayerUntilMs} per-player negative
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
     * subsequent passive readers benefit from the fresher data.
     *
     * <p>Use {@code bypassCache=true} for:
     * <ul>
     *   <li>Explicit user actions (Player Lookup tab open, [Refresh]
     *       button, etc.).</li>
     *   <li>Lobby roster enrichment in {@code WebSocketLobbyService} —
     *       new joiners + periodic "Waiting"-row retries.</li>
     * </ul>
     * Passive overlays + auto-triggered post-fight tier checks should
     * stay on {@link #getShardRankByName(String, String)} to keep CDN
     * cost bounded.
     */
	public CompletableFuture<ShardRank> getShardRankByName(String playerName, String bucket, boolean bypassCache)
	{
        if (playerName == null || playerName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // 1. Canonicalize Name and bucket (normalize spaces for consistency)
        String canonicalName = playerName.trim().replaceAll("\\s+", " ").toLowerCase();
        String bucketPath = (bucket == null || bucket.isEmpty()) ? "overall" : bucket.toLowerCase();

        // Dedupe key includes the bypass flag so a passive cached
        // request and a bypass request never share a future. Two
        // concurrent bypass requests for the same (name,bucket) still
        // share — that's just dedupe and they'd land on the same
        // network result anyway.
        String lookupKey = canonicalName + ":" + bucketPath + (bypassCache ? ":bypass" : "");
        
        // Check for existing in-flight lookup - if one exists, return it instead of starting a new one
        CompletableFuture<ShardRank> existingLookup = inFlightLookups.get(lookupKey);
        if (existingLookup != null) {
            return existingLookup;
        }
        
        // Create new future for this lookup
        CompletableFuture<ShardRank> future = new CompletableFuture<>();
        
        // Try to register as the in-flight lookup (atomic operation)
        CompletableFuture<ShardRank> previousLookup = inFlightLookups.putIfAbsent(lookupKey, future);
        if (previousLookup != null) {
            // Another thread beat us to it - use their future instead
            return previousLookup;
        }
        
        // We own this lookup - make sure to clean up when done
        future.whenComplete((result, ex) -> inFlightLookups.remove(lookupKey, future));
        
        try {
            // Negative cache only applies to passive callers. Explicit
            // refresh paths bypass it intentionally — the 1-h backoff
            // was the root cause of the "rank stuck on Waiting" QA
            // report fixed at 2026-05-24, and the new
            // DynamoDB-stream writer means a "missing" player can flip
            // to "present" within ~30 s of their first match landing.
            if (!bypassCache) {
                Long missingUntil = missingPlayerUntilMs.get(canonicalName);
                if (missingUntil != null && System.currentTimeMillis() < missingUntil) {
                    future.complete(null);
                    return future;
                }
            }

            // 2. Shard Key = first 3 chars of lowercase name (e.g., "toyco" -> "toy").
            // Server publishes shards at both 3-char and 2-char widths; the
            // plugin uses the 3-char variant for better distribution (~17576
            // buckets vs ~676), reducing per-shard payload size and lookup
            // contention. For names shorter than 3 chars we fall back to
            // the full canonical name as the key (server publishes those
            // edge-case shards under their literal name).
            String shardKey = canonicalName.length() >= 3
                ? canonicalName.substring(0, 3).toLowerCase()
                : canonicalName.toLowerCase();
            String url = SHARD_BASE_URL + "/" + bucketPath + "/" + shardKey + ".json";

            // 4. Fetch/Get Cached Shard — bypass flag propagated so the
            // network call lands when bypassCache=true even if a cached
            // payload sits within TTL.
            getShard(url, bypassCache).thenCompose(shardJson -> {
                if (shardJson == null) {
                    // Also mark player as missing for 1 hour when shard doesn't exist
                    missingPlayerUntilMs.put(canonicalName, System.currentTimeMillis() + MISSING_PLAYER_BACKOFF_MS);
                    future.complete(null);
                    return CompletableFuture.completedFuture(null);
                }
                
                // 5. Look for Name in name_rank_info_map
                JsonObject nameMap = shardJson.getAsJsonObject("name_rank_info_map");
                if (nameMap == null || !nameMap.has(canonicalName)) {
                    // Mark missing (1 Hour TTL)
                    missingPlayerUntilMs.put(canonicalName, System.currentTimeMillis() + MISSING_PLAYER_BACKOFF_MS);
                    future.complete(null);
                    return CompletableFuture.completedFuture(null);
                }
                
                JsonObject entry = nameMap.getAsJsonObject(canonicalName);
                
                // Scenario B: Redirect — propagate bypass through the
                // SHA → shard step so a "fresh data please" call doesn't
                // half-bypass and pick up a stale account_rank_info_map.
                if (entry.has("redirect")) {
                    String accountSha = entry.get("redirect").getAsString();
                    resolveRedirect(accountSha, bucketPath, bypassCache).thenAccept(result -> {
                        future.complete(result);
                    }).exceptionally(ex -> {
                        future.complete(null);
                        return null;
                    });
                    return CompletableFuture.completedFuture(null);
                }
                
                // Scenario A: Direct Hit
                ShardRank rank = parseRankObject(entry);
                future.complete(rank);
                return CompletableFuture.completedFuture(null);
                
            }).exceptionally(ex -> {
                future.complete(null);
                return null;
            });
            
        } catch (Exception e) {
            future.complete(null);
        }
		return future;
	}

    /**
     * Helper to resolve Redirect (Account SHA -> Rank Shard).
     * Supports chained redirects with max depth of 10.
     *
     * <p>Default passive entry — preserved for callers that don't care
     * about freshness. New code should prefer
     * {@link #resolveRedirect(String, String, boolean)} so the bypass
     * decision propagates end-to-end (a half-bypassed lookup would
     * read fresh from the name shard then stale from the account
     * shard, which defeats the whole point of an explicit refresh).
     */
    private CompletableFuture<ShardRank> resolveRedirect(String accountSha, String bucket) {
        return resolveRedirectWithDepth(accountSha, bucket, 0, false);
    }

    private CompletableFuture<ShardRank> resolveRedirect(String accountSha, String bucket, boolean bypassCache) {
        return resolveRedirectWithDepth(accountSha, bucket, 0, bypassCache);
    }

    private CompletableFuture<ShardRank> resolveRedirectWithDepth(String accountSha, String bucket, int depth, boolean bypassCache) {
        CompletableFuture<ShardRank> future = new CompletableFuture<>();
        
        if (depth >= 10) {
            future.complete(null);
            return future;
        }
        
        if (accountSha == null || accountSha.length() < 3) {
            future.complete(null);
            return future;
        }

        // 1. Calc Shard from SHA (first 3 chars). Account SHAs are
        // 64-char hex so the substring is always safe; the length
        // guard above is defensive against a future caller passing
        // a non-SHA identifier.
        String shardKey = accountSha.substring(0, 3);
        String url = SHARD_BASE_URL + "/" + bucket + "/" + shardKey + ".json";

        // 2. Fetch/Get Cached Shard — bypass propagated for redirect
        // chains so an explicit refresh reads fresh at every hop.
        getShard(url, bypassCache).thenAccept(shardJson -> {
            if (shardJson == null) {
                future.complete(null);
                return;
            }
            
            // 3. Look in account_rank_info_map
            JsonObject acctMap = shardJson.getAsJsonObject("account_rank_info_map");
            if (acctMap != null && acctMap.has(accountSha)) {
                JsonObject entry = acctMap.getAsJsonObject(accountSha);
                
                // Check for chained redirect
                if (entry.has("redirect")) {
                    String nextSha = entry.get("redirect").getAsString();
                    resolveRedirectWithDepth(nextSha, bucket, depth + 1, bypassCache)
                        .thenAccept(future::complete);
                    return;
                }
                
                future.complete(parseRankObject(entry));
            } else {
                future.complete(null);
            }
        }).exceptionally(ex -> {
            future.complete(null);
            return null;
        });
        
        return future;
    }
    
    private ShardRank parseRankObject(JsonObject o) {
        if (o == null) return null;
        if (RankUtils.isUnrankedOrDefault(o)) return null;

        int idx = o.has("world_rank") && !o.get("world_rank").isJsonNull() ? o.get("world_rank").getAsInt() : -1;
        
        String tier = null;
        if (o.has("tier") && !o.get("tier").isJsonNull()) {
            tier = RankUtils.formatTierLabel(o.get("tier").getAsString());
        }
        
        if (tier == null && o.has("rank")) {
             tier = o.get("rank").getAsString();
        }
        
        if (tier != null && idx > 0) {
            return new ShardRank(tier, idx);
        }
        if (tier != null) return new ShardRank(tier, idx > 0 ? idx : 0);
        
        return null;
    }

	/**
	 * Low-level shard fetch with 60-min positive cache, 60s failure backoff.
	 *
	 * <p>Passive entry — overlay tickers, post-fight tier lookups, etc.
	 * should call this. The positive cache hit short-circuits before
	 * any HTTP work so CDN cost stays bounded.
	 */
	public CompletableFuture<JsonObject> getShard(String url)
	{
		return getShard(url, false);
	}

	/**
	 * Cache-aware overload of {@link #getShard(String)}. When
	 * {@code bypassCache=true} the positive cache read is skipped so the
	 * call always hits the CDN; the {@link #shardFailUntil} negative
	 * backoff is still honoured (we don't want explicit refreshes to
	 * thunder-stampede a 5xx). Successful responses still populate
	 * {@link #shardCache} so any concurrent passive reader benefits
	 * from the fresher payload.
	 *
	 * <p>Pair with {@link #getShardRankByName(String, String, boolean)}
	 * which threads the same flag through the redirect chain.
	 */
	public CompletableFuture<JsonObject> getShard(String url, boolean bypassCache)
	{
		return fetchCachedJson(url, shardCache, shardFailUntil, SHARD_CACHE_EXPIRY_MS, bypassCache);
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
	private CompletableFuture<JsonObject> fetchCachedJson(String url,
		Map<String, ShardEntry> cache, ConcurrentHashMap<String, Long> failUntil,
		long ttlMs, boolean bypassCache)
	{
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

        long now = System.currentTimeMillis();

        // 1. Check Memory Cache — bypassed when bypassCache=true so an
        // explicit refresh picks up the latest write instead of serving a
        // payload that might be up to ttlMs stale.
        if (!bypassCache) {
            ShardEntry cached = cache.get(url);
            if (cached != null && (now - cached.getTimestamp() < ttlMs)) {
                future.complete(cached.getPayload());
                return future;
            }
        }

        // 2. Check Negative Cache (Fail Until). Still honoured even
        // on bypass — this is a protective backoff against the CDN
        // returning 5xx, not a freshness cache, and an explicit
        // refresh shouldn't be a thundering-herd vector.
        Long failAt = failUntil.get(url);
        if (failAt != null && now < failAt) {
            future.complete(null);
            return future;
        }

        // 3. Download
		Request request = new Request.Builder().url(url).get().build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
                failUntil.put(url, System.currentTimeMillis() + SHARD_FAIL_BACKOFF_MS);
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response res = response)
				{
					if (!res.isSuccessful()) {
                        failUntil.put(url, System.currentTimeMillis() + SHARD_FAIL_BACKOFF_MS);
						future.complete(null);
						return;
					}
                    
                    ResponseBody body = res.body();
                    if (body == null) {
                        future.complete(new JsonObject());
                        return;
                    }
                    
                    String bodyStr;
                    try {
                        bodyStr = body.string();
                    } catch (IOException cacheEx) {
                        // Windows cache file locking issue - request succeeded but cache failed
                        // Try to serve stale cache if available
                        ShardEntry stale = cache.get(url);
                        if (stale != null) {
                            future.complete(stale.getPayload());
                            return;
                        }
                        future.complete(null);
                        return;
                    }
                    JsonObject json = gson.fromJson(bodyStr, JsonObject.class);
                    
                    cache.put(url, new ShardEntry(json, System.currentTimeMillis()));
                    failUntil.remove(url);
                    
                    future.complete(json);
				} catch (Exception e) {
                    future.complete(null);
                }
			}
		});
		return future;
	}

    // Retained for compatibility if needed, but not used by new flow
	public CompletableFuture<ShardRank> getShardRank(String accountHash, String bucket)
	{
		// Map old account-hash based call to the resolveRedirect logic which effectively does SHA->Shard lookup
        // But getShardRank was used when we *only* had account hash.
        // If we want to support that, we can use the resolveRedirect logic.
        return resolveRedirect(accountHash, bucket == null ? "overall" : bucket);
	}

	public CompletableFuture<Integer> getRankNumber(String playerName, String accountHash, String bucket)
	{
		// New logic: Use getShardRankByName which handles everything (name->md5->shard or name->redirect->sha->shard)
        // If we have accountHash, we can try direct SHA lookup (resolveRedirect), but Name lookup is preferred now?
        // Actually, if we have Name, we should use getShardRankByName.
        
        return getShardRankByName(playerName, bucket).thenApply(sr -> {
            if (sr != null) return sr.rank;
            return -1;
        });
	}

	/**
	 * Fetch the per-bucket MMR→rank histogram used by the "What are the
	 * ranks" view to derive a live "Top X%" for each tier.
	 *
	 * <p>Reads {@code rank_hist/<bucket>.json} from the static site (the
	 * same CDN origin as {@code /rank_idx} shards) via the shared
	 * {@link #getShard(String)} fetch, so it inherits the 60-minute
	 * positive cache and 60-second failure backoff. The payload shape is
	 * {@code {"bin_width", "total", "bins":[[floor,count,count_above],...]}}
	 * — see {@code backend/core/rank_histogram.py}.
	 *
	 * @param bucket one of {@code overall|nh|veng|multi|dmm}; null/blank
	 *               defaults to {@code overall}.
	 * @return the parsed histogram, or {@code null} if it could not be
	 *         fetched/parsed (callers render "No one currently here").
	 */
	public CompletableFuture<JsonObject> getRankHistogram(String bucket)
	{
		String bucketPath = (bucket == null || bucket.trim().isEmpty())
			? "overall"
			: bucket.trim().toLowerCase();
		String url = RANK_HIST_BASE_URL + "/" + bucketPath + ".json";
		return getShard(url).exceptionally(ex -> null);
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
	 * It is fetched on its OWN {@link #leaderboardCache} (NOT the rank_idx
	 * shard cache — a leaderboard is a ranked list, not a name→rank shard),
	 * with a 60-minute positive cache that mirrors the file's hourly refresh
	 * and a 60-second failure backoff. The caller (TopPlayersPanel) parses,
	 * de-dupes by account, sorts by MMR and displays only the top 100 — same
	 * pipeline as the site. Payload shape:
	 * {@code {"players":[{"account","player_names":[..],"mmr","rank","division","icon"}],"bucket"}}.
	 *
	 * @param bucket one of {@code overall|nh|veng|multi|dmm}; null/blank → {@code overall}.
	 * @return the parsed leaderboard, or {@code null} if it couldn't be fetched/parsed.
	 */
	public CompletableFuture<JsonObject> getLeaderboard(String bucket)
	{
		String b = (bucket == null || bucket.trim().isEmpty())
			? "overall"
			: bucket.trim().toLowerCase();
		String key = "overall".equals(b) ? "/leaderboard.json" : "/leaderboard_" + b + ".json";
		String url = PvPLeaderboardConstants.PUBLIC_SITE_BASE_URL + key;
		return fetchCachedJson(url, leaderboardCache, leaderboardFailUntil, LEADERBOARD_CACHE_EXPIRY_MS, false)
			.exceptionally(ex -> null);
	}

	public String generateAcctSha(String uuid) throws Exception
	{
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(uuid.getBytes(StandardCharsets.UTF_8));
		StringBuilder hexString = new StringBuilder();
		for (byte b : hash)
		{
			String hex = Integer.toHexString(0xff & b);
			if (hex.length() == 1) hexString.append('0');
			hexString.append(hex);
		}
		return hexString.toString();
	}

    private static final int DMM_WORLD = 345;

    /**
     * Check if a world is a DMM world.
     * DMM is currently inactive — hardcoded to world 345 only.
     * Re-enable the API fetch below when the next DMM season starts.
     */
    public boolean isDmmWorld(int world)
    {
        return world == DMM_WORLD;
    }

    /**
     * Get the set of DMM worlds.
     */
    public Set<Integer> getDmmWorlds()
    {
        Set<Integer> worlds = new HashSet<>();
        worlds.add(DMM_WORLD);
        return worlds;
    }

    /**
     * No-op while DMM is inactive. Re-enable when next DMM season starts.
     */
    public void refreshDmmWorlds()
    {
    }

    /*
     * ====================================================================
     * DMM API fetch code — commented out while DMM is inactive.
     * Un-comment and restore isDmmWorld/getDmmWorlds/refreshDmmWorlds
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
     *     okHttpClient.newCall(request).enqueue(new Callback()
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
