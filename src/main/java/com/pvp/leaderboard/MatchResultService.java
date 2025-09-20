package com.pvp.leaderboard;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class MatchResultService
{
    private static final String API_URL = "https://kekh0x6kfk.execute-api.us-east-1.amazonaws.com/prod/matchresult";
    private static final String CLIENT_ID = "runelite";
    private static final String PLUGIN_VERSION = "1.0.0";
    private static final String RUNELITE_CLIENT_SECRET = "7f2f6a0e-2c6b-4b1d-9a39-6f2b2a8a1f3c"; // Replace with actual secret
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pvp-http");
        t.setDaemon(true);
        return t;
    });
    
    public CompletableFuture<Boolean> submitMatchResult(
        String playerId, 
        String opponentId, 
        String result, 
        int world,
        long fightStartTs,
        long fightEndTs,
        String fightStartSpellbook,
        String fightEndSpellbook,
        boolean wasInMulti,
        long accountHash,
        String idToken)
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                // log.info("[Submit] begin playerId={} opponentId={} result={} world={} startTs={} endTs={} startSpell={} endSpell={} multi={} acctHash={} authed={}",
                //         playerId, opponentId, result, world, fightStartTs, fightEndTs, fightStartSpellbook, fightEndSpellbook, wasInMulti, accountHash, (idToken != null && !idToken.isEmpty()));
                JsonObject body = new JsonObject();
                body.addProperty("player_id", playerId);
                body.addProperty("opponent_id", opponentId);
                body.addProperty("result", result);
                body.addProperty("world", world);
                body.addProperty("fight_start_ts", fightStartTs);
                body.addProperty("fight_end_ts", fightEndTs);
                body.addProperty("fightStartSpellbook", fightStartSpellbook);
                body.addProperty("fightEndSpellbook", fightEndSpellbook);
                body.addProperty("wasInMulti", wasInMulti);
                body.addProperty("client_id", CLIENT_ID);
                body.addProperty("plugin_version", PLUGIN_VERSION);
                
                String bodyJson = body.toString();
                
                if (idToken != null && !idToken.isEmpty())
                {
                    boolean ok = submitAuthenticatedFight(bodyJson, accountHash, idToken);
                    if (!ok)
                    {
                        // log.warn("[Submit] authenticated failed; fallback to unauth path");
                        return submitUnauthenticatedFight(bodyJson, accountHash);
                    }
                    log.info("[Submit] authenticated path accepted");
                    return true;
                }
                boolean ok = submitUnauthenticatedFight(bodyJson, accountHash);
                if (ok) log.info("[Submit] unauthenticated path accepted");
                return ok;
            }
            catch (Exception e)
            {
                // log.error("[Submit] exception during submit", e);
                return false;
            }
        }, httpExecutor);
    }
    
    private boolean submitAuthenticatedFight(String body, long accountHash, String idToken) throws Exception
    {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("x-account-hash", String.valueOf(accountHash));
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        
        // log.info("=== AUTHENTICATED REQUEST ===");
        // log.info("URL: {}", API_URL);
        // log.info("Method: POST");
        // log.info("Headers:");
        // log.info("  Content-Type: application/json; charset=utf-8");
        // log.info("  Authorization: Bearer {}", idToken);
        // log.info("  x-account-hash: {}", accountHash);
        // log.info("Body: {}", body);
        
        try (OutputStream os = conn.getOutputStream())
        {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        
        int responseCode = conn.getResponseCode();
        /* String resp = */ HttpUtil.readResponseBody(conn);
        // log.info("Response Code: {}", responseCode);
        if (responseCode >= 200 && responseCode < 300) return true;
        // log.warn("[Submit] authenticated failed: {} - {}", responseCode, resp);
        return false;
    }
    
    private boolean submitUnauthenticatedFight(String body, long accountHash) throws Exception
    {
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = generateSignature(body, timestamp);
        
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("x-account-hash", String.valueOf(accountHash));
        conn.setRequestProperty("x-client-id", CLIENT_ID);
        conn.setRequestProperty("x-timestamp", String.valueOf(timestamp));
        conn.setRequestProperty("x-signature", signature);
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        
        // log.info("=== UNAUTHENTICATED REQUEST ===");
        // log.info("URL: {}", API_URL);
        // log.info("Method: POST");
        // log.info("Headers:");
        // log.info("  Content-Type: application/json; charset=utf-8");
        // log.info("  x-account-hash: {}", accountHash);
        // log.info("  x-client-id: {}", CLIENT_ID);
        // log.info("  x-timestamp: {}", timestamp);
        // log.info("  x-signature: {}", signature);
        // log.info("Body: {}", body);
        // log.info("Signature Message: POST\n/matchresult\n{}\n{}", body, timestamp);
        
        try (OutputStream os = conn.getOutputStream())
        {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        
        int responseCode = conn.getResponseCode();
        /* String respBody = */ HttpUtil.readResponseBody(conn);
        // log.info("Response Code: {}", responseCode);
        if (responseCode >= 200 && responseCode < 300) return true;
        if (responseCode == 202)
        {
            // 202 Accepted is considered success; backend processes asynchronously
            return true;
        }
        // log.warn("[Submit] unauthenticated failed: {} - {}", responseCode, respBody);
        return false;
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