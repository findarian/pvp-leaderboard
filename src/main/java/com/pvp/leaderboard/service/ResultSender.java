package com.pvp.leaderboard.service;

import com.google.gson.*;
import com.pvp.leaderboard.*;
import java.io.*;
import java.math.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.inject.*;
import net.runelite.client.*;
import okhttp3.*;

public class ResultSender
{
    private static final String API_URL = PvpConsts.API_BASE_URL + "/matchresult";
    private static final String CLIENT_ID = "runelite";
    // This is meant to be hardcoded and be this value for everyone. New versions of the plugin will update this on the backend so that it doesn't take matches from old clients if there is an incompatibility added.
    private static final String HMAC_SECRET = "7f2f6a0e-2c6b-4b1d-9a39-6f2b2a8a1f3c"; 
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;

    @Inject
    public ResultSender(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    // Fallback constructor for tests: use RuneLite injector (no fresh clients)
    public ResultSender()
    {
        this(
            RuneLite.getInjector().getInstance(OkHttpClient.class),
            RuneLite.getInjector().getInstance(Gson.class)
        );
    }

    /**
     * Validates a RuneScape username format.
     * Valid after trimming: 1-12 letters, digits, spaces, underscores or hyphens
     */
    private boolean isValidName(String name)
    {
        return name != null && name.trim().matches("[a-zA-Z0-9 _-]{1,12}");
    }

    /** Submits the match; the future is {@code true} once the server accepted
     *  it (2xx) and {@code false} on any failure, never exceptional. */
    public CompletableFuture<Boolean> submitResult(MatchResult match)
    {
        try
        {
            // Validate player and opponent names match RuneScape format
            if (!isValidName(match.getPlayerId()) || !isValidName(match.getOpponentId()))
            {
                return CompletableFuture.completedFuture(false);
            }

            String bodyJson = buildBody(match);

            // HMAC-signed submission is the sole path. The plugin holds no
            // user token (Discord login does not mint one for the client),
            // so every match is authenticated by the shared client secret.
            return submitSigned(bodyJson, match.getClientUniqueId()).exceptionally(ex -> false);
        }
        catch (Exception e)
        {
            return CompletableFuture.completedFuture(false);
        }
    }

    String buildBody(MatchResult match)
    {
        var body = new JsonObject();
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
        body.addProperty("plugin_version", PvpConsts.VERSION);
        body.addProperty("damage_to_opponent", match.getDamageToOpponent());

        // Optional trailing LMS freeze-log fields — omitted entirely for
        // normal fights so the common-case payload is unchanged.
        if (match.isLmsFreezeLogout())
        {
            body.addProperty("lms_freeze_logout", true);
            String reason = match.getLmsFreezeReason();
            if (reason != null && !reason.trim().isEmpty())
            {
                body.addProperty("lms_freeze_reason", reason);
            }
        }

        if (match.isFfaPortal())
        {
            body.addProperty("ffa_portal", true);
        }

        if (match.isBountyHunter())
        {
            body.addProperty("bounty_hunter", true);
        }

        return gson.toJson(body);
    }

    private CompletableFuture<Boolean> submitSigned(String body, String clientUniqueId)
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

        long reqStart = System.nanoTime();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                future.complete(false);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    String reqId = res.header("x-amzn-RequestId");
                    if (res.isSuccessful())
                    {
                        future.complete(true);
                    }
                    else
                    {
                        String errBody = null;
                        try { ResponseBody err = res.body(); errBody = err != null ? err.string() : null; } catch (Exception ignore) {}
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
        mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        // The 32-byte HMAC as 64 lowercase hex digits, leading zeros kept.
        return String.format(Locale.ROOT, "%064x", new BigInteger(1, mac.doFinal(message.getBytes(StandardCharsets.UTF_8))));
    }
}
