package com.pvp.leaderboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.service.PvPDataService;
import okhttp3.*;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class PvPDataServiceTest {

    private OkHttpClient okHttpClient;
    private TestInterceptor testInterceptor;
    private PvPLeaderboardConfig config;
    private PvPDataService dataService;
    private final Gson gson = new Gson();

    // Fake data for testing
    private static final String FAKE_PLAYER_ID = "TestPlayer_123";
    private static final String FAKE_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String FAKE_OPPONENT = "BadGuy_999";

    @Before
    public void setUp() {
        testInterceptor = new TestInterceptor();
        okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(testInterceptor)
                .build();
        config = new MockConfig();
        // authService is unused in PvPDataService, passing null
        dataService = new PvPDataService(okHttpClient, gson, null, config);
    }

    @Test
    public void testGetPlayerMatches_Success() throws ExecutionException, InterruptedException, IOException {
        // Prepare fake response
        JsonObject fakeResponseJson = new JsonObject();
        fakeResponseJson.addProperty("player_id", FAKE_PLAYER_ID);
        // Add a fake match
        JsonObject match = new JsonObject();
        match.addProperty("opponent", FAKE_OPPONENT);
        match.addProperty("result", "win");
        match.addProperty("match_id", FAKE_UUID);
        
        com.google.gson.JsonArray matchesArray = new com.google.gson.JsonArray();
        matchesArray.add(match);
        fakeResponseJson.add("matches", matchesArray);

        testInterceptor.setNextResponse(200, gson.toJson(fakeResponseJson));

        // Execute
        CompletableFuture<JsonObject> future = dataService.getPlayerMatches(FAKE_PLAYER_ID, null, 10);
        JsonObject result = future.get();

        // Verify
        assertNotNull(result);
        assertEquals(FAKE_PLAYER_ID, result.get("player_id").getAsString());
        assertEquals(1, result.getAsJsonArray("matches").size());
        assertEquals(FAKE_OPPONENT, result.getAsJsonArray("matches").get(0).getAsJsonObject().get("opponent").getAsString());
        
        // Verify URL parameters
        assertNotNull(testInterceptor.getLastRequest());
        assertTrue(testInterceptor.getLastRequest().url().toString().contains("player_id=" + FAKE_PLAYER_ID));
    }

    @Test
    public void testGetPlayerTier_Success() throws ExecutionException, InterruptedException, IOException {
        // Prepare fake response
        JsonObject fakeTierJson = new JsonObject();
        fakeTierJson.addProperty("player_id", FAKE_PLAYER_ID);
        fakeTierJson.addProperty("tier", "Diamond I");
        fakeTierJson.addProperty("bucket", "nh");

        testInterceptor.setNextResponse(200, gson.toJson(fakeTierJson));

        // Execute
        CompletableFuture<String> future = dataService.getPlayerTier(FAKE_PLAYER_ID, "nh");
        String tier = future.get();

        // Verify
        assertEquals("Diamond I", tier);
    }

    @Test
    public void testGetPlayerTier_NotFound() throws ExecutionException, InterruptedException, IOException {
        testInterceptor.setNextResponse(404, "");

        // Execute
        CompletableFuture<String> future = dataService.getPlayerTier(FAKE_PLAYER_ID, "nh");
        String tier = future.get();

        // Verify
        assertNull(tier);
    }

    @Test
    public void testGetRankIndex_Success() throws ExecutionException, InterruptedException, IOException {
        JsonObject fakeRankJson = new JsonObject();
        fakeRankJson.addProperty("rank", 42);

        testInterceptor.setNextResponse(200, gson.toJson(fakeRankJson));

        // Execute
        CompletableFuture<Integer> future = dataService.getRankIndex(FAKE_PLAYER_ID, "nh");
        Integer rank = future.get();

        assertEquals(42, rank.intValue());
    }

    @Test
    public void testGetUserProfile_Success() throws ExecutionException, InterruptedException, IOException {
        JsonObject fakeProfile = new JsonObject();
        fakeProfile.addProperty("player_id", FAKE_PLAYER_ID);
        fakeProfile.addProperty("mmr", 1500.5);

        testInterceptor.setNextResponse(200, gson.toJson(fakeProfile));

        // Execute
        CompletableFuture<JsonObject> future = dataService.getUserProfile(FAKE_PLAYER_ID, "unique-id");
        JsonObject result = future.get();

        assertNotNull(result);
        assertEquals(FAKE_PLAYER_ID, result.get("player_id").getAsString());
        assertEquals(1500.5, result.get("mmr").getAsDouble(), 0.001);
    }

    @Test
    public void testGenerateAcctSha_ValidFormat() throws Exception {
        // Test SHA256 hash generation for UUID -> acct_sha conversion
        String hash = dataService.generateAcctSha("550e8400-e29b-41d4-a716-446655440000");
        assertNotNull(hash);
        assertEquals(64, hash.length()); // SHA256 produces 64 hex chars
        assertTrue(hash.matches("[0-9a-f]{64}")); // All lowercase hex
        
        // Verify same input produces same hash (deterministic)
        String hash2 = dataService.generateAcctSha("550e8400-e29b-41d4-a716-446655440000");
        assertEquals(hash, hash2);
        
        // Verify different inputs produce different hashes
        String otherHash = dataService.generateAcctSha("660e8400-e29b-41d4-a716-446655440001");
        assertNotEquals(hash, otherHash);
    }

    @Test
    public void testShardKeyFromName_FirstTwoChars() {
        // Verify shard key is first 2 chars of lowercase name, NOT SHA256
        // These match the expected behavior after the shard key change
        assertEquals("to", getShardKeyForName("Toyco"));
        assertEquals("to", getShardKeyForName("toyco"));
        assertEquals("mo", getShardKeyForName("MOH JO JOJO"));
        assertEquals("te", getShardKeyForName("test_account1"));
        assertEquals("a", getShardKeyForName("A")); // Single char edge case
    }
    
    private String getShardKeyForName(String name) {
        // Replicate the shard key logic from PvPDataService.getShardRankByName()
        String canonicalName = name.toLowerCase().trim().replaceAll("\\s+", " ");
        return canonicalName.length() >= 2 
            ? canonicalName.substring(0, 2).toLowerCase() 
            : canonicalName.toLowerCase();
    }

    // -- Mock Classes --

    private class TestInterceptor implements Interceptor {
        private int code;
        private String body;
        private Request lastRequest;

        public void setNextResponse(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public Request getLastRequest() {
            return lastRequest;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            lastRequest = chain.request();
            if (code == 0) throw new IOException("No mock response configured");
            
            return new Response.Builder()
                    .request(lastRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(code == 200 ? "OK" : "Error")
                    .body(ResponseBody.create(MediaType.parse("application/json"), body != null ? body : ""))
                    .build();
        }
    }
    
    private class MockConfig implements PvPLeaderboardConfig {
        @Override
        public boolean enablePvpLookupMenu() { return false; }
        
        @Override
        public RankBucket rankBucket() { return RankBucket.OVERALL; }
        
        @Override
        public boolean debugMode() { return false; }
    }
}
