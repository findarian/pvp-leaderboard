package com.pvp.leaderboard.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.pvp.leaderboard.cache.MatchesCacheEntry;
import com.pvp.leaderboard.cache.ShardEntry;
import com.pvp.leaderboard.cache.UserStatsCache;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.util.RankUtils;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
public class PvPDataService
{
	private static final String API_BASE_URL = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod";
    private static final String SHARD_BASE_URL = "https://devsecopsautomated.com/rank_idx";

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final PvPLeaderboardConfig config;

	// Shard lookup caching
    // Per spec: Shard Files = 60s TTL
	private static final long SHARD_CACHE_EXPIRY_MS = 60L * 1000L; 
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

    /**
     * Clears the shard negative cache for a player. Call this when API confirms player exists.
     */
    public void clearShardNegativeCache(String playerName) {
        if (playerName == null) return;
        String canonicalName = playerName.trim().toLowerCase();
        missingPlayerUntilMs.remove(canonicalName);
        // debug("[Cache] Cleared shard negative cache for {}", canonicalName);
    }

    // User Profile Caching
    private static final long USER_CACHE_TTL_MS = 1L * 60L * 1000L; // 1 minute
    private final ConcurrentHashMap<String, UserStatsCache> userStatsCache = new ConcurrentHashMap<>();

    // Matches Caching
    private static final long MATCHES_CACHE_TTL_MS = 6L * 60L * 60L * 1000L; // 6 hours
    private final ConcurrentHashMap<String, MatchesCacheEntry> matchesCache = new ConcurrentHashMap<>();

	@Inject
	public PvPDataService(OkHttpClient okHttpClient, Gson gson, CognitoAuthService authService, PvPLeaderboardConfig config)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.config = config;
	}


	public CompletableFuture<JsonObject> getPlayerMatches(String playerName, String nextToken, int limit)
	{
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

        // Check cache (only for first page, i.e. no nextToken)
        if (nextToken == null || nextToken.isEmpty()) {
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

		Request request = new Request.Builder()
			.url(urlBuilder.build())
			.get()
			.build();

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
							// Windows cache file locking issue - request succeeded but cache failed
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

	public CompletableFuture<JsonObject> getUserProfile(String playerName, String clientUniqueId)
	{
		return getUserProfile(playerName, clientUniqueId, false);
	}

	public CompletableFuture<JsonObject> getUserProfile(String playerName, String clientUniqueId, boolean forceRefresh)
	{
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		log.debug("[API] getUserProfile called: player={} forceRefresh={}", playerName, forceRefresh);

		String cacheKey = "user:" + playerName;
		UserStatsCache cached = userStatsCache.get(cacheKey);
		if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.getTimestamp() <= USER_CACHE_TTL_MS) {
			log.debug("[API] getUserProfile: returning cached data for player={}", playerName);
			future.complete(cached.getStats().deepCopy());
			return future;
		}

		HttpUrl urlObj = HttpUrl.parse(API_BASE_URL + "/user");
		if (urlObj == null) {
			log.debug("[API] getUserProfile: invalid base URL");
			future.completeExceptionally(new IOException("Invalid base URL"));
			return future;
		}
		HttpUrl url = urlObj.newBuilder()
			.addQueryParameter("player_id", playerName)
			.build();

		Request request = new Request.Builder().url(url).get().build();
		log.debug("[API] getUserProfile: making HTTP request to {}", url);

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("[API] getUserProfile onFailure: player={} error={}", playerName, e.getMessage());
				UserStatsCache stale = userStatsCache.get(cacheKey);
				if (stale != null) {
					log.debug("[API] getUserProfile: using stale cache on failure for player={}", playerName);
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
					log.debug("[API] getUserProfile onResponse: player={} code={}", playerName, res.code());
					if (!res.isSuccessful())
					{
						if (res.code() == 404) {
							log.debug("[API] getUserProfile: 404 not found for player={}", playerName);
							future.complete(null);
							return;
						}
						log.debug("[API] getUserProfile: HTTP error {} for player={}", res.code(), playerName);
						future.completeExceptionally(new IOException("API error: " + res.code()));
						return;
					}
					ResponseBody body = res.body();
					String bodyString;
					try {
						bodyString = body != null ? body.string() : "{}";
					} catch (IOException cacheEx) {
						log.debug("[API] getUserProfile: cache error during body read for player={}: {}", playerName, cacheEx.getMessage());
						// Windows cache file locking issue - request succeeded but cache failed
						// Try to serve stale cache if available
						UserStatsCache stale = userStatsCache.get(cacheKey);
						if (stale != null) {
							log.debug("[API] getUserProfile: using stale cache after cache error for player={}", playerName);
							future.complete(stale.getStats().deepCopy());
							return;
						}
						log.debug("[API] getUserProfile: no stale cache, returning null for player={}", playerName);
						future.complete(null);
						return;
					}
					log.debug("[API] getUserProfile SUCCESS: player={} bodyLength={}", playerName, bodyString.length());
					log.debug("[API] getUserProfile RESPONSE: player={} body={}", playerName, bodyString);
					JsonObject json = gson.fromJson(bodyString, JsonObject.class);
					userStatsCache.put(cacheKey, new UserStatsCache(json.deepCopy(), System.currentTimeMillis()));
					future.complete(json);
				}
				catch (JsonSyntaxException e)
				{
					log.debug("[API] getUserProfile: JSON parse error for player={}: {}", playerName, e.getMessage());
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
        log.debug("[API] getTierFromProfile called: player={} bucket={}", playerName, bucket);
        
        // Force refresh to get fresh data after a fight, but keep cached data as fallback
        String cacheKey = "user:" + playerName;
        UserStatsCache cachedData = userStatsCache.get(cacheKey);
        log.debug("[API] getTierFromProfile: cachedData exists={}", cachedData != null);
        
        return getUserProfile(playerName, null, true).thenApply(profile -> {
            if (profile == null) {
                log.debug("[API] getTierFromProfile: profile is null for player={}", playerName);
                // Try fallback to cached data if available
                if (cachedData != null) {
                    JsonObject cachedProfile = cachedData.getStats();
                    if (cachedProfile != null) {
                        log.debug("[API] getTierFromProfile: using cached fallback for player={}", playerName);
                        // Try bucket-specific data first
                        if (cachedProfile.has("buckets") && cachedProfile.get("buckets").isJsonObject()) {
                            JsonObject buckets = cachedProfile.getAsJsonObject("buckets");
                            if (buckets.has(bucket) && buckets.get(bucket).isJsonObject()) {
                                JsonObject bucketData = buckets.getAsJsonObject(bucket);
                                String tier = extractTierFromUserResponse(bucketData, playerName, bucket);
                                if (tier != null) {
                                    log.debug("[API] getTierFromProfile: cached bucket tier={} for player={}", tier, playerName);
                                    return tier;
                                }
                            }
                        }
                        // Fallback to top-level
                        String tier = extractTierFromUserResponse(cachedProfile, playerName, "overall");
                        log.debug("[API] getTierFromProfile: cached top-level tier={} for player={}", tier, playerName);
                        return tier;
                    }
                }
                log.debug("[API] getTierFromProfile: no cached data, returning null for player={}", playerName);
                return null;
            }
            
            log.debug("[API] getTierFromProfile: got profile for player={}, keys={}", playerName, profile.keySet());
            
            // Try bucket-specific data first (in "buckets" object)
            if (profile.has("buckets") && profile.get("buckets").isJsonObject()) {
                JsonObject buckets = profile.getAsJsonObject("buckets");
                if (buckets.has(bucket) && buckets.get(bucket).isJsonObject()) {
                    JsonObject bucketData = buckets.getAsJsonObject(bucket);
                    String tier = extractTierFromUserResponse(bucketData, playerName, bucket);
                    if (tier != null) {
                        log.debug("[API] getTierFromProfile SUCCESS: bucket tier={} for player={}", tier, playerName);
                        return tier;
                    }
                }
            }
            
            // Fallback to top-level rank/division fields (for overall bucket)
            String tier = extractTierFromUserResponse(profile, playerName, "overall");
            if (tier != null) {
                log.debug("[API] getTierFromProfile SUCCESS: top-level tier={} for player={}", tier, playerName);
                return tier;
            }
            
            log.debug("[API] getTierFromProfile: no rank found in profile for player={} bucket={}", playerName, bucket);
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
            // debug("[API] extractTierFromUserResponse SUCCESS player={} bucket={} tier={}", playerName, bucket, tier);
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
		// debug("[API] getPlayerTier called for player={} bucket={}", playerName, bucket);
        // Delegate to getTierFromProfile which uses /user endpoint correctly
        return getTierFromProfile(playerName, bucket);
	}

	/**
     * Primary Entry Point: Get Rank by Name using the SHA256 Shard Logic
     * Uses in-flight deduplication to prevent multiple concurrent lookups for the same (player, bucket).
     */
	public CompletableFuture<ShardRank> getShardRankByName(String playerName, String bucket)
	{
        if (playerName == null || playerName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // 1. Canonicalize Name and bucket
        String canonicalName = playerName.trim().toLowerCase();
        String bucketPath = (bucket == null || bucket.isEmpty()) ? "overall" : bucket.toLowerCase();
        
        // Create lookup key for deduplication
        String lookupKey = canonicalName + ":" + bucketPath;
        
        // Check for existing in-flight lookup - if one exists, return it instead of starting a new one
        CompletableFuture<ShardRank> existingLookup = inFlightLookups.get(lookupKey);
        if (existingLookup != null) {
            // debug("[Lookup] Dedup: reusing in-flight lookup for '{}' bucket={}", canonicalName, bucketPath);
            return existingLookup;
        }
        
        // Create new future for this lookup
        CompletableFuture<ShardRank> future = new CompletableFuture<>();
        
        // Try to register as the in-flight lookup (atomic operation)
        CompletableFuture<ShardRank> previousLookup = inFlightLookups.putIfAbsent(lookupKey, future);
        if (previousLookup != null) {
            // Another thread beat us to it - use their future instead
            // debug("[Lookup] Dedup: race detected, reusing in-flight lookup for '{}' bucket={}", canonicalName, bucketPath);
            return previousLookup;
        }
        
        // We own this lookup - make sure to clean up when done
        future.whenComplete((result, ex) -> inFlightLookups.remove(lookupKey, future));
        
        try {
            // Check Negative Cache first (1 hour block)
            Long missingUntil = missingPlayerUntilMs.get(canonicalName);
            if (missingUntil != null && System.currentTimeMillis() < missingUntil) {
                // debug("[Lookup] Negative cache hit for {} (blocked until {})", canonicalName, missingUntil);
                future.complete(null);
                return future;
            }

            // 2. Shard Key = first 2 chars of lowercase name (e.g., "toyco" -> "to")
            String shardKey = canonicalName.length() >= 2 
                ? canonicalName.substring(0, 2).toLowerCase() 
                : canonicalName.toLowerCase();
            String url = SHARD_BASE_URL + "/" + bucketPath + "/" + shardKey + ".json";

            // debug("[Lookup] Player: {} -> Shard: {} -> URL: {}", canonicalName, shardKey, url);

            // 4. Fetch/Get Cached Shard
            getShard(url).thenCompose(shardJson -> {
                if (shardJson == null) {
                    // debug("[Lookup] Shard download failed/empty for {}", url);
                    // THIS: Also mark player as missing for 1 hour when shard doesn't exist
                    missingPlayerUntilMs.put(canonicalName, System.currentTimeMillis() + MISSING_PLAYER_BACKOFF_MS);
                    future.complete(null);
                    return CompletableFuture.completedFuture(null);
                }
                
                // 5. Look for Name in name_rank_info_map
                JsonObject nameMap = shardJson.getAsJsonObject("name_rank_info_map");
                if (nameMap == null || !nameMap.has(canonicalName)) {
                    // debug("[Lookup] Player '{}' NOT FOUND in shard {}", canonicalName, shardKey);
                    // Mark missing (1 Hour TTL)
                    missingPlayerUntilMs.put(canonicalName, System.currentTimeMillis() + MISSING_PLAYER_BACKOFF_MS);
                    future.complete(null);
                    return CompletableFuture.completedFuture(null);
                }
                
                JsonObject entry = nameMap.getAsJsonObject(canonicalName);
                
                // Scenario B: Redirect
                if (entry.has("redirect")) {
                    String accountSha = entry.get("redirect").getAsString();
                    // debug("[Lookup] Redirect found for '{}' -> SHA: {}", canonicalName, accountSha);
                    // FIX: Complete the outer future with the redirect result
                    resolveRedirect(accountSha, bucketPath).thenAccept(result -> {
                        future.complete(result);
                    }).exceptionally(ex -> {
                        future.complete(null);
                        return null;
                    });
                    return CompletableFuture.completedFuture(null);
                }
                
                // Scenario A: Direct Hit
                // debug("[Lookup] Direct hit for '{}'", canonicalName);
                ShardRank rank = parseRankObject(entry);
                future.complete(rank);
                return CompletableFuture.completedFuture(null);
                
            }).exceptionally(ex -> {
                // debug("Exception in getShardRankByName: {}", ex.getMessage());
                future.complete(null);
                return null;
            });
            
        } catch (Exception e) {
            // debug("Error in getShardRankByName: {}", e.getMessage());
            future.complete(null);
        }
		return future;
	}

    /**
     * Helper to resolve Redirect (Account SHA -> Rank Shard)
     * Supports chained redirects with max depth of 10
     */
    private CompletableFuture<ShardRank> resolveRedirect(String accountSha, String bucket) {
        return resolveRedirectWithDepth(accountSha, bucket, 0);
    }

    private CompletableFuture<ShardRank> resolveRedirectWithDepth(String accountSha, String bucket, int depth) {
        CompletableFuture<ShardRank> future = new CompletableFuture<>();
        
        if (depth >= 10) {
            // debug("[Redirect] Max depth (10) reached for {}, aborting", accountSha);
            future.complete(null);
            return future;
        }
        
        if (accountSha == null || accountSha.length() < 2) {
            future.complete(null);
            return future;
        }

        // 1. Calc Shard from SHA (first 2 chars)
        String shardKey = accountSha.substring(0, 2);
        String url = SHARD_BASE_URL + "/" + bucket + "/" + shardKey + ".json";
        
        // debug("[Redirect] depth={} SHA={} -> Shard={}", depth, accountSha, shardKey);

        // 2. Fetch/Get Cached Shard
        getShard(url).thenAccept(shardJson -> {
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
                    // debug("[Redirect] Chain redirect to {}", nextSha);
                    resolveRedirectWithDepth(nextSha, bucket, depth + 1)
                        .thenAccept(future::complete);
                    return;
                }
                
                // debug("[Redirect] Found SHA entry at depth {}", depth);
                future.complete(parseRankObject(entry));
            } else {
                // debug("[Redirect] SHA entry NOT FOUND in shard");
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
	 * Low-level shard fetch with 60s Cache
	 */
	public CompletableFuture<JsonObject> getShard(String url)
	{
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
        
        long now = System.currentTimeMillis();
        
        // 1. Check Memory Cache
        ShardEntry cached = shardCache.get(url);
        if (cached != null && (now - cached.getTimestamp() < SHARD_CACHE_EXPIRY_MS)) {
            // debug("[Cache] HIT for {}", url);
            future.complete(cached.getPayload());
            return future;
        }
        
        // 2. Check Negative Cache (Fail Until)
        Long failUntil = shardFailUntil.get(url);
        if (failUntil != null && now < failUntil) {
            // debug("[Cache] Negative/Fail HIT for {}", url);
            future.complete(null);
            return future;
        }

        // 3. Download
		Request request = new Request.Builder().url(url).get().build();
		// debug("[Network] Downloading shard: {}", url);

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
                // debug("[Network] Fail: {}", e.getMessage());
                shardFailUntil.put(url, System.currentTimeMillis() + SHARD_FAIL_BACKOFF_MS);
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response res = response)
				{
					if (!res.isSuccessful()) {
                        // debug("[Network] HTTP Error: {}", res.code());
                        shardFailUntil.put(url, System.currentTimeMillis() + SHARD_FAIL_BACKOFF_MS);
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
                        ShardEntry stale = shardCache.get(url);
                        if (stale != null) {
                            future.complete(stale.getPayload());
                            return;
                        }
                        future.complete(null);
                        return;
                    }
                    JsonObject json = gson.fromJson(bodyStr, JsonObject.class);
                    
                    // Cache Success (60s)
                    shardCache.put(url, new ShardEntry(json, System.currentTimeMillis()));
                    shardFailUntil.remove(url);
                    
                    future.complete(json);
				} catch (Exception e) {
                    // debug("[Network] Parse Error: {}", e.getMessage());
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

    // Helper for deprecated usage if any
	public CompletableFuture<Integer> getRankIndex(String playerId, String bucket)
	{
        // Redirect to new logic
        return getShardRankByName(playerId, bucket).thenApply(sr -> sr != null ? sr.rank : -1);
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

}
