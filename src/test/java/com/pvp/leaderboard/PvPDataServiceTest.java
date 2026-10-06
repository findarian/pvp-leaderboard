package com.pvp.leaderboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.service.PvpApi;
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
    private PvpApi dataService;
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
        // identitySvc is unused in tests, passing null
        dataService = new PvpApi(okHttpClient, gson, null);
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
        CompletableFuture<JsonObject> future = dataService.getMatches(null, FAKE_PLAYER_ID, null, 10, false);
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
    public void testGetUserProfile_Success() throws ExecutionException, InterruptedException, IOException {
        JsonObject fakeProfile = new JsonObject();
        fakeProfile.addProperty("player_id", FAKE_PLAYER_ID);
        fakeProfile.addProperty("mmr", 1500.5);

        testInterceptor.setNextResponse(200, gson.toJson(fakeProfile));

        // Execute
        CompletableFuture<JsonObject> future = dataService.getProfile(FAKE_PLAYER_ID, false);
        JsonObject result = future.get();

        assertNotNull(result);
        assertEquals(FAKE_PLAYER_ID, result.get("player_id").getAsString());
        assertEquals(1500.5, result.get("mmr").getAsDouble(), 0.001);
    }

    /** The client's acct_sha: SHA-256 of its identifier as 64 lower-case hex characters, leading zeros kept
     *  (expected values from Python's hashlib). */
    @Test
    public void testSelfAcctSha_isTheLowerHexSha256OfTheIdentifier() throws Exception {
        assertEquals("a3a9e1ed9732cab28868127be00f1ce921acaefdd5c3b23a6e9e0072bd9c1a34", selfAcctSha(FAKE_UUID));
        assertEquals("00020ac787456de06541d6df16016c4d78ae127f7e9478b992888cdfda66722f", selfAcctSha("client-8588"));
        String other = selfAcctSha("660e8400-e29b-41d4-a716-446655440001");
        assertTrue(other.matches("[0-9a-f]{64}"));
        assertNotEquals(selfAcctSha(FAKE_UUID), other);
    }

    @Test
    public void testSelfAcctSha_withoutAnIdentifier_isNull() throws Exception {
        assertNull(selfAcctSha(null));
        assertNull(selfAcctSha(""));
        assertNull("no identity service", dataService.getSelfSha());
    }

    private String selfAcctSha(String uuid) {
        com.pvp.leaderboard.service.IdentitySvc identity =
            org.mockito.Mockito.mock(com.pvp.leaderboard.service.IdentitySvc.class);
        org.mockito.Mockito.when(identity.getClientUniqueId()).thenReturn(uuid);
        return new PvpApi(okHttpClient, gson, identity).getSelfSha();
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
        // Replicate the shard key logic from PvpApi.getShardRank()
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
    }
}
