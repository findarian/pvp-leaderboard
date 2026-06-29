package com.pvp.leaderboard.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class MatchResultService
{
    private static final String API_URL = "https://l5xya0wf0d.execute-api.us-east-1.amazonaws.com/prod/matchresult";
    private static final String CLIENT_ID = "runelite";
    private static final String PLUGIN_VERSION = "1.0.0";
    // This is meant to be hardcoded and be this value for everyone. New versions of the plugin will update this on the backend so that it doesn't take matches from old clients if there is an incompatibility added.
    private static final String RUNELITE_CLIENT_SECRET = "7f2f6a0e-2c6b-4b1d-9a39-6f2b2a8a1f3c"; 
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;

    @Inject
    public MatchResultService(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    // Fallback constructor for tests: use RuneLite injector (no fresh clients)
    public MatchResultService()
    {
        this(
            net.runelite.client.RuneLite.getInjector().getInstance(OkHttpClient.class),
            net.runelite.client.RuneLite.getInjector().getInstance(Gson.class)
        );
    }

    /**
     * Validates a RuneScape username format.
     * Valid: alphanumeric, spaces, underscores, hyphens, 1-12 characters
     */
    private boolean isValidRunescapeName(String name)
    {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 12) return false;
        return trimmed.matches("^[a-zA-Z0-9 _-]+$");
    }

    public CompletableFuture<Boolean> submitMatchResult(MatchResult match)
    {
        CompletableFuture<Boolean> overall = new CompletableFuture<>();
        try
        {
            // Validate player and opponent names match RuneScape format
            if (!isValidRunescapeName(match.getPlayerId()) || !isValidRunescapeName(match.getOpponentId()))
            {
                log.debug("[Submit] Invalid name format: player='{}' opponent='{}'", match.getPlayerId(), match.getOpponentId());
                overall.complete(false);
                return overall;
            }

            JsonObject body = new JsonObject();
            body.addProperty("player_id", match.getPlayerId());
            body.addProperty("opponent_id", match.getOpponentId());
            body.addProperty("result", match.getResult());
            body.addProperty("world", match.getWorld());
            body.addProperty("fight_start_ts", match.getFightStartTs());
            body.addProperty("fight_end_ts", match.getFightEndTs());
            body.addProperty("fightStartSpellbook", match.getFightStartSpellbook());
            body.addProperty("fightEndSpellbook", match.getFightEndSpellbook());
            body.addProperty("wasInMulti", match.isWasInMulti());
            body.addProperty("client_id", CLIENT_ID);
            body.addProperty("plugin_version", PLUGIN_VERSION);
            body.addProperty("damage_to_opponent", match.getDamageToOpponent());

            String bodyJson = gson.toJson(body);
            
            log.debug("[MatchAPI] Request body: {}", bodyJson);

            // HMAC-signed submission is the sole path. The plugin holds no
            // user token (Discord login does not mint one for the client),
            // so every match is authenticated by the shared client secret.
            submitSignedFightAsync(bodyJson, match.getClientUniqueId()).whenComplete((ok, ex) -> {
                if (ex != null) overall.complete(false); else overall.complete(ok);
            });
        }
        catch (Exception e)
        {
            log.debug("[MatchAPI] EXCEPTION during submit: {}", e.getMessage(), e);
            overall.complete(false);
        }
        return overall;
    }

    private CompletableFuture<Boolean> submitSignedFightAsync(String body, String clientUniqueId)
    {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        long timestamp = System.currentTimeMillis() / 1000;
        String signature;
        try
        {
            signature = generateSignature(body, timestamp);
        }
        catch (Exception e)
        {
            log.debug("[MatchAPI] Unauth signature generation FAILED: {}", e.getMessage());
            future.completeExceptionally(e);
            return future;
        }

        Request request = new Request.Builder()
            .url(API_URL)
            .post(RequestBody.create(JSON, body))
            .addHeader("x-client-id", CLIENT_ID)
            .addHeader("x-timestamp", String.valueOf(timestamp))
            .addHeader("x-signature", signature)
            .addHeader("X-Client-Unique-Id", clientUniqueId)
            .build();

        log.debug("[MatchAPI] Unauth request to: {} timestamp={}", API_URL, timestamp);
        final long reqStart = System.nanoTime();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                long ms = (System.nanoTime() - reqStart) / 1_000_000;
                log.debug("[MatchAPI] Unauth request NETWORK FAILURE after {}ms: {}", ms, e.getMessage());
                future.complete(false);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                long ms = (System.nanoTime() - reqStart) / 1_000_000;
                try (Response res = response)
                {
                    int code = res.code();
                    String reqId = null; try { reqId = res.header("x-amzn-RequestId"); } catch (Exception ignore) {}
                    
                    if ((code >= 200 && code < 300) || code == 202)
                    {
                        log.debug("[MatchAPI] Unauth response SUCCESS in {}ms: code={} requestId={}", ms, code, reqId);
                        future.complete(true);
                    }
                    else
                    {
                        String errBody = null;
                        try { okhttp3.ResponseBody err = res.body(); errBody = err != null ? err.string() : null; } catch (Exception ignore) {}
                        log.debug("[MatchAPI] Unauth response FAILED in {}ms: code={} requestId={} body={}", ms, code, reqId, errBody);
                        future.complete(false);
                    }
                }
            }
        });

        return future;
    }

    private String generateSignature(String body, long timestamp) throws Exception
    {
        String message = "POST\n/matchresult\n" + body + "\n" + timestamp;
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(RUNELITE_CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKey);
        byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

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
