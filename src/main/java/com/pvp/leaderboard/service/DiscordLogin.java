package com.pvp.leaderboard.service;

import com.google.gson.*;
import com.pvp.leaderboard.*;
import com.pvp.leaderboard.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.client.util.*;
import okhttp3.*;

/**
 * "Login with Discord" service — loopback-free, server-brokered handshake.
 *
 * <p>Discord is a <b>confidential</b> OAuth client, so the plugin cannot
 * exchange the authorization {@code code} itself (the token endpoint needs the
 * {@code client_secret}). The old flow ran a {@code http://127.0.0.1:49215}
 * loopback HTTP server and surfaced a raw localhost page in the browser. This
 * flow removes the loopback entirely:
 *
 * <ol>
 *   <li>{@code GET /auth/discord/plugin-init} — the backend mints a
 *       {@code login_id} (== OAuth {@code state}) + PKCE (verifier held
 *       server-side) and returns {@code {login_id, authorize_url}}.</li>
 *   <li>Open {@code authorize_url} in the browser. The OAuth redirect lands on
 *       a <b>hosted</b> page on {@code pvp-leaderboard.com} (NOT
 *       {@code 127.0.0.1}); that page relays {@code {code, state}} to the
 *       backend, which performs the secret-bearing exchange and stores a
 *       minimal session.</li>
 *   <li>The plugin polls {@code GET /auth/discord/plugin-poll?login_id=} until
 *       {@code status=complete} and adopts the returned minimal session.</li>
 * </ol>
 *
 * <p>The plugin never sees the Discord access token or the client secret —
 * only the minimal session (Discord id + display name). {@link #isLoggedIn()}
 * reflects "a Discord identity was linked this session".
 */
public class DiscordLogin
{
    /** Overall wall-clock budget for a login (browser auth + polling). */
    private static final long TIMEOUT_MS = 120_000L;
    /** Poll cadence while waiting for the browser side to complete. */
    private static final long POLL_MS = 2_000L;

    /** Fallback session lifetime when the session omits {@code expires_at}. */
    private static final long LOGIN_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ScheduledExecutorService scheduler;
    private final Client client;

    // Minimal session (no tokens, no secrets): the linked identity's name
    // (its Discord id when it has none) and when the session ends.
    private volatile String displayName;
    private volatile long sessionExpiry;

    // In-flight handshake state (one login at a time).
    private final AtomicBoolean loginActive = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> pollFuture;
    private volatile Call inFlightCall;

    @Inject
    public DiscordLogin(OkHttpClient httpClient, Gson gson,
                              ScheduledExecutorService scheduler, Client client)
    {
        this.httpClient = httpClient;
        this.gson = gson;
        this.scheduler = scheduler;
        this.client = client;
    }

    /**
     * Start a loopback-free Discord login. Resolves {@code true} once a minimal
     * session has been adopted, {@code false} on cancel/timeout/error.
     */
    public CompletableFuture<Boolean> login()
    {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!loginActive.compareAndSet(false, true))
        {
            // A login is already in progress; don't start a second handshake.
            result.complete(false);
            return result;
        }

        try
        {
            get(buildInitUrl(PvpConsts.INIT_URL, currentRsn()), res ->
            {
                try
                {
                    String body = body(res);
                    String loginId = parseString(gson, body, "login_id");
                    String authorizeUrl = parseString(gson, body, "authorize_url");
                    if (!res.isSuccessful() || loginId == null || authorizeUrl == null)
                    {
                        finish(result, false);
                        return;
                    }
                    LinkBrowser.browse(authorizeUrl);
                    startPolling(loginId, result, System.currentTimeMillis() + TIMEOUT_MS);
                }
                catch (Exception e)
                {
                    finish(result, false);
                }
            }, () -> finish(result, false));
        }
        catch (Exception e)
        {
            finish(result, false);
        }
        return result;
    }

    /** Cancel an in-flight login (user pressed Cancel / closed the panel). */
    public void cancelLogin()
    {
        if (loginActive.get())
        {
            stopLogin();
        }
    }

    private void startPolling(String loginId, CompletableFuture<Boolean> result, long deadlineMs)
    {
        if (scheduler == null)
        {
            // No scheduler (degenerate/test wiring) — can't poll.
            finish(result, false);
            return;
        }
        pollFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (!loginActive.get() || result.isDone())
            {
                return;
            }
            if (System.currentTimeMillis() > deadlineMs)
            {
                finish(result, false);
                return;
            }
            pollOnce(loginId, result);
        }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollOnce(String loginId, CompletableFuture<Boolean> result)
    {
        try
        {
            get(buildPollUrl(PvpConsts.POLL_URL, loginId), res ->
            {
                try
                {
                    if (result.isDone() || !loginActive.get())
                    {
                        return;
                    }
                    String body = body(res);
                    String status = parseString(gson, body, "status");
                    if ("complete".equals(status))
                    {
                        JsonObject session = parsePollSession(gson, body);
                        finish(result, session != null && adoptSession(session));
                    }
                    else if ("error".equals(status) || "expired".equals(status))
                    {
                        finish(result, false);
                    }
                    // "pending" (or unknown) -> keep polling.
                }
                catch (Exception ignore)
                {
                    // Keep polling; the deadline guard ends it eventually.
                }
            }, () -> {
                // Transient network blip — keep polling until the deadline.
            });
        }
        catch (Exception ignore)
        {
            // Keep polling; the deadline guard ends it eventually.
        }
    }

    /** GETs {@code url} as the in-flight call: {@code ok} gets the response
     *  (closed afterwards), {@code failed} runs on a network failure. */
    private void get(String url, Consumer<Response> ok, Runnable failed)
    {
        Call call = httpClient.newCall(new Request.Builder().url(url).addHeader("Accept", "application/json").build());
        inFlightCall = call;
        call.enqueue(new Callback()
        {
            @Override public void onFailure(Call c, IOException e)
            {
                failed.run();
            }

            @Override public void onResponse(Call c, Response response)
            {
                try (Response res = response)
                {
                    ok.accept(res);
                }
            }
        });
    }

    private static String body(Response res) throws IOException
    {
        ResponseBody rb = res.body();
        return rb != null ? rb.string() : "";
    }

    /** Adopt the minimal-session JSON object into local state. */
    boolean adoptSession(JsonObject session)
    {
        String id = JsonLenient.optString(session, "discord_user_id");
        if (id.isEmpty()) return false;
        // expires_at is the Discord token expiry (epoch seconds).
        long expiresAt = JsonLenient.optLong(session, "expires_at", -1);
        displayName = JsonLenient.optString(session, "display_name", id);
        sessionExpiry = expiresAt >= 0 ? expiresAt * 1000L : System.currentTimeMillis() + LOGIN_TTL_MS;
        return true;
    }

    public boolean isLoggedIn()
    {
        return displayName != null && System.currentTimeMillis() < sessionExpiry;
    }

    public void logout()
    {
        displayName = null;
        sessionExpiry = 0L;
    }

    /** Stop polling + cancel any in-flight call and mark the handshake done. */
    private void stopLogin()
    {
        loginActive.set(false);
        ScheduledFuture<?> poll = pollFuture;
        if (poll != null) poll.cancel(false);
        pollFuture = null;
        Call call = inFlightCall;
        if (call != null) call.cancel();
        inFlightCall = null;
    }

    /** Resolve the login future exactly once and tear down the handshake. */
    private void finish(CompletableFuture<Boolean> result, boolean success)
    {
        stopLogin();
        if (!result.isDone())
        {
            result.complete(success);
        }
    }

    // ---- Pure helpers (package-private for unit tests) ----

    /** Build the plugin-init URL, appending the current RSN when known. */
    static String buildInitUrl(String base, String playerName)
    {
        if (playerName == null || playerName.trim().isEmpty())
        {
            return base;
        }
        return base + "?player_name=" + URLEncoder.encode(playerName.trim(), StandardCharsets.UTF_8);
    }

    /** Build the plugin-poll URL for a given handshake id. */
    static String buildPollUrl(String base, String loginId)
    {
        return base + "?login_id=" + URLEncoder.encode(loginId, StandardCharsets.UTF_8);
    }

    /**
     * Extract the nested {@code session} object from a {@code complete}
     * plugin-poll response, or {@code null} when absent / not complete.
     */
    static JsonObject parsePollSession(Gson gson, String body)
    {
        try
        {
            return JsonLenient.optObject(gson.fromJson(body, JsonObject.class), "session");
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** The response's non-empty text at {@code key} ({@code login_id},
     *  {@code authorize_url}, {@code status}), or {@code null}. */
    static String parseString(Gson gson, String body, String key)
    {
        try
        {
            String v = gson.fromJson(body, JsonObject.class).get(key).getAsString();
            return v.isEmpty() ? null : v;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Current in-game RSN, or {@code null} if not logged into the game. */
    private String currentRsn()
    {
        try
        {
            if (client == null) return null;
            Player local = client.getLocalPlayer();
            return local != null ? local.getName() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
