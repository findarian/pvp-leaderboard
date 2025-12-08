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
    private static final String API_URL = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/matchresult";
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

    public CompletableFuture<Boolean> submitMatchResult(MatchResult match)
    {
        CompletableFuture<Boolean> overall = new CompletableFuture<>();
        try
        {
            // Preflight diagnostics to understand why server might reject
            try {
                boolean namesOk = (match.getPlayerId() != null && !match.getPlayerId().trim().isEmpty()) && (match.getOpponentId() != null && !match.getOpponentId().trim().isEmpty());
                String pLower = (match.getPlayerId() != null) ? match.getPlayerId().toLowerCase(java.util.Locale.ROOT) : null;
                String oLower = (match.getOpponentId() != null) ? match.getOpponentId().toLowerCase(java.util.Locale.ROOT) : null;
                boolean notSelf = (pLower != null && oLower != null) && !pLower.equals(oLower);
                boolean timeOk = match.getFightStartTs() > 0 && match.getFightEndTs() > 0 && match.getFightEndTs() >= match.getFightStartTs();
                boolean worldOk = match.getWorld() > 0;
                // log.debug("[Submit] preflight namesOk={} notSelf={} timeOk={} worldOk={} player='{}' opponent='{}' startTs={} endTs={} world={} multi={} dmgOut={}",
                //     namesOk, notSelf, timeOk, worldOk, match.getPlayerId(), match.getOpponentId(), match.getFightStartTs(), match.getFightEndTs(), match.getWorld(), match.isWasInMulti(), match.getDamageToOpponent());
                // if (!namesOk) { log.debug("[Submit][why] Missing player/opponent name"); }
                // else if (!notSelf) { log.debug("[Submit][why] Opponent equals self; likely mis-attribution"); }
                // if (!timeOk) { log.debug("[Submit][why] Invalid timestamps startTs={} endTs={}", match.getFightStartTs(), match.getFightEndTs()); }
                // if (!worldOk) { log.debug("[Submit][why] Invalid world={}", match.getWorld()); }
            } catch (Exception ignore) {}
            
            String dbgPlayer = (match.getPlayerId() != null ? match.getPlayerId() : "<null>");
            String dbgOpponent = (match.getOpponentId() != null ? match.getOpponentId() : "<null>");
            // log.debug("[Submit] begin playerId={} opponentId={} result={} world={} startTs={} endTs={} startSpell={} endSpell={} multi={} authed={}",
            //     dbgPlayer, dbgOpponent, match.getResult(), match.getWorld(), match.getFightStartTs(), match.getFightEndTs(), match.getFightStartSpellbook(), match.getFightEndSpellbook(), match.isWasInMulti(), (match.getIdToken() != null && !match.getIdToken().isEmpty()));

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

            if (match.getIdToken() != null && !match.getIdToken().isEmpty())
            {
                log.debug("[MatchAPI] Using authenticated path (has idToken)");
                submitAuthenticatedFightAsync(bodyJson, match.getIdToken(), match.getClientUniqueId()).whenComplete((ok, ex) -> {
                    if (ex != null)
                    {
                        log.debug("[MatchAPI] Authenticated path exception: {}, falling back to unauth", ex.getMessage());
                        submitUnauthenticatedFightAsync(bodyJson, match.getClientUniqueId()).whenComplete((ok2, ex2) -> {
                            if (ex2 != null) overall.complete(false); else overall.complete(ok2);
                        });
                        return;
                    }
                    if (Boolean.TRUE.equals(ok))
                    {
                        log.debug("[MatchAPI] Authenticated path SUCCESS");
                        overall.complete(true);
                    }
                    else
                    {
                        log.debug("[MatchAPI] Authenticated path failed, falling back to unauth");
                        submitUnauthenticatedFightAsync(bodyJson, match.getClientUniqueId()).whenComplete((ok2, ex2) -> {
                            if (ex2 != null) overall.complete(false); else overall.complete(ok2);
                        });
                    }
                });
            }
            else
            {
                log.debug("[MatchAPI] Using unauthenticated path (no idToken)");
                submitUnauthenticatedFightAsync(bodyJson, match.getClientUniqueId()).whenComplete((ok, ex) -> {
                    if (ex != null) overall.complete(false); else overall.complete(ok);
                });
            }
        }
        catch (Exception e)
        {
            log.debug("[MatchAPI] EXCEPTION during submit: {}", e.getMessage(), e);
            overall.complete(false);
        }
        return overall;
    }

    private CompletableFuture<Boolean> submitAuthenticatedFightAsync(String body, String idToken, String clientUniqueId)
    {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Request request = new Request.Builder()
            .url(API_URL)
            .post(RequestBody.create(JSON, body))
            .addHeader("Authorization", "Bearer " + idToken)
            .addHeader("X-Client-Unique-Id", clientUniqueId)
            .build();

        log.debug("[MatchAPI] Auth request to: {}", API_URL);

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[MatchAPI] Auth request NETWORK FAILURE: {}", e.getMessage());
                future.complete(false);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    int code = res.code();
                    String reqId = null; try { reqId = res.header("x-amzn-RequestId"); } catch (Exception ignore) {}
                    
                    if (code >= 200 && code < 300)
                    {
                        log.debug("[MatchAPI] Auth response SUCCESS: code={} requestId={}", code, reqId);
                        future.complete(true);
                    }
                    else
                    {
                        String errBody = null;
                        try { okhttp3.ResponseBody err = res.body(); errBody = err != null ? err.string() : null; } catch (Exception ignore) {}
                        log.debug("[MatchAPI] Auth response FAILED: code={} requestId={} body={}", code, reqId, errBody);
                        future.complete(false);
                    }
                }
            }
        });

        return future;
    }

    private CompletableFuture<Boolean> submitUnauthenticatedFightAsync(String body, String clientUniqueId)
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

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[MatchAPI] Unauth request NETWORK FAILURE: {}", e.getMessage());
                future.complete(false);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    int code = res.code();
                    String reqId = null; try { reqId = res.header("x-amzn-RequestId"); } catch (Exception ignore) {}
                    
                    if ((code >= 200 && code < 300) || code == 202)
                    {
                        log.debug("[MatchAPI] Unauth response SUCCESS: code={} requestId={}", code, reqId);
                        future.complete(true);
                    }
                    else
                    {
                        String errBody = null;
                        try { okhttp3.ResponseBody err = res.body(); errBody = err != null ? err.string() : null; } catch (Exception ignore) {}
                        log.debug("[MatchAPI] Unauth response FAILED: code={} requestId={} body={}", code, reqId, errBody);
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
