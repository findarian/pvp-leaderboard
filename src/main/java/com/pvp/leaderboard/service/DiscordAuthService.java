package com.pvp.leaderboard.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.PvPLeaderboardConstants;
import net.runelite.api.Client;
import net.runelite.client.util.LinkBrowser;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import javax.inject.Inject;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
public class DiscordAuthService
{
    /** Overall wall-clock budget for a login (browser auth + polling). */
    private static final long LOGIN_TIMEOUT_MS = 120_000L;
    /** Poll cadence while waiting for the browser side to complete. */
    private static final long POLL_INTERVAL_MS = 2_000L;

    /** Fallback session lifetime when the session omits {@code expires_at}. */
    private static final long DEFAULT_SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ScheduledExecutorService scheduler;
    private final Client client;

    // Minimal session (no tokens, no secrets).
    private volatile String discordUserId;
    private volatile String displayName;
    private volatile long sessionExpiry;

    // In-flight handshake state (one login at a time).
    private final AtomicBoolean loginActive = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> pollFuture;
    private volatile okhttp3.Call inFlightCall;

    @Inject
    public DiscordAuthService(OkHttpClient httpClient, Gson gson,
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
            Request initReq = new Request.Builder()
                .url(buildInitUrl(PvPLeaderboardConstants.DISCORD_PLUGIN_INIT_URL, currentRsn()))
                .get()
                .addHeader("Accept", "application/json")
                .build();

            okhttp3.Call call = httpClient.newCall(initReq);
            inFlightCall = call;
            call.enqueue(new okhttp3.Callback()
            {
                @Override public void onFailure(okhttp3.Call c, IOException e)
                {
                    finish(result, false);
                }

                @Override public void onResponse(okhttp3.Call c, okhttp3.Response response)
                {
                    try (okhttp3.Response res = response)
                    {
                        okhttp3.ResponseBody rb = res.body();
                        String body = rb != null ? rb.string() : "";
                        if (!res.isSuccessful())
                        {
                            finish(result, false);
                            return;
                        }
                        String loginId = parseLoginId(gson, body);
                        String authorizeUrl = parseAuthorizeUrl(gson, body);
                        if (loginId == null || authorizeUrl == null)
                        {
                            finish(result, false);
                            return;
                        }
                        LinkBrowser.browse(authorizeUrl);
                        startPolling(loginId, result, System.currentTimeMillis() + LOGIN_TIMEOUT_MS);
                    }
                    catch (Exception e)
                    {
                        finish(result, false);
                    }
                }
            });
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
            stopHandshake();
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
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollOnce(String loginId, CompletableFuture<Boolean> result)
    {
        try
        {
            Request req = new Request.Builder()
                .url(buildPollUrl(PvPLeaderboardConstants.DISCORD_PLUGIN_POLL_URL, loginId))
                .get()
                .addHeader("Accept", "application/json")
                .build();

            okhttp3.Call call = httpClient.newCall(req);
            inFlightCall = call;
            call.enqueue(new okhttp3.Callback()
            {
                @Override public void onFailure(okhttp3.Call c, IOException e)
                {
                    // Transient network blip — keep polling until the deadline.
                }

                @Override public void onResponse(okhttp3.Call c, okhttp3.Response response)
                {
                    try (okhttp3.Response res = response)
                    {
                        if (result.isDone() || !loginActive.get())
                        {
                            return;
                        }
                        okhttp3.ResponseBody rb = res.body();
                        String body = rb != null ? rb.string() : "";
                        String status = parsePollStatus(gson, body);
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
                }
            });
        }
        catch (Exception ignore)
        {
            // Keep polling; the deadline guard ends it eventually.
        }
    }

    /** Adopt the minimal-session JSON object into local state. */
    boolean adoptSession(JsonObject session)
    {
        try
        {
            if (session == null) return false;
            String id = session.has("discord_user_id") && !session.get("discord_user_id").isJsonNull()
                ? session.get("discord_user_id").getAsString() : null;
            if (id == null || id.isEmpty()) return false;

            String name = session.has("display_name") && !session.get("display_name").isJsonNull()
                ? session.get("display_name").getAsString() : id;

            long expiry;
            if (session.has("expires_at") && !session.get("expires_at").isJsonNull())
            {
                // expires_at is the Discord token expiry (epoch seconds).
                expiry = session.get("expires_at").getAsLong() * 1000L;
            }
            else
            {
                expiry = System.currentTimeMillis() + DEFAULT_SESSION_TTL_MS;
            }

            this.discordUserId = id;
            this.displayName = name;
            this.sessionExpiry = expiry;
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public boolean isLoggedIn()
    {
        return displayName != null && System.currentTimeMillis() < sessionExpiry;
    }

    public void logout()
    {
        clearSession();
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public String getDiscordUserId()
    {
        return discordUserId;
    }

    /** Stop polling + cancel any in-flight call and mark the handshake done. */
    private void stopHandshake()
    {
        loginActive.set(false);
        try { if (pollFuture != null) pollFuture.cancel(false); } catch (Exception ignore) {}
        pollFuture = null;
        try { if (inFlightCall != null) inFlightCall.cancel(); } catch (Exception ignore) {}
        inFlightCall = null;
    }

    /** Resolve the login future exactly once and tear down the handshake. */
    private void finish(CompletableFuture<Boolean> result, boolean success)
    {
        stopHandshake();
        if (!result.isDone())
        {
            result.complete(success);
        }
    }

    private void clearSession()
    {
        discordUserId = null;
        displayName = null;
        sessionExpiry = 0L;
    }

    // ---- Pure helpers (package-private for unit tests) ----

    /** Build the plugin-init URL, appending the current RSN when known. */
    static String buildInitUrl(String base, String playerName)
    {
        if (playerName == null || playerName.trim().isEmpty())
        {
            return base;
        }
        return base + "?player_name=" + urlEncode(playerName.trim());
    }

    /** Build the plugin-poll URL for a given handshake id. */
    static String buildPollUrl(String base, String loginId)
    {
        return base + "?login_id=" + urlEncode(loginId);
    }

    /** Extract {@code login_id} from a plugin-init response, or {@code null}. */
    static String parseLoginId(Gson gson, String body)
    {
        return parseString(gson, body, "login_id");
    }

    /** Extract {@code authorize_url} from a plugin-init response, or {@code null}. */
    static String parseAuthorizeUrl(Gson gson, String body)
    {
        return parseString(gson, body, "authorize_url");
    }

    /** Extract {@code status} from a plugin-poll response, or {@code null}. */
    static String parsePollStatus(Gson gson, String body)
    {
        return parseString(gson, body, "status");
    }

    /**
     * Extract the nested {@code session} object from a {@code complete}
     * plugin-poll response, or {@code null} when absent / not complete.
     */
    static JsonObject parsePollSession(Gson gson, String body)
    {
        try
        {
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            if (obj == null || !obj.has("session") || obj.get("session").isJsonNull())
            {
                return null;
            }
            return obj.getAsJsonObject("session");
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String parseString(Gson gson, String body, String key)
    {
        try
        {
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull())
            {
                return null;
            }
            String v = obj.get(key).getAsString();
            return (v == null || v.isEmpty()) ? null : v;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String urlEncode(String s)
    {
        try
        {
            return URLEncoder.encode(s, "UTF-8");
        }
        catch (UnsupportedEncodingException e)
        {
            // UTF-8 is always available; unreachable.
            throw new IllegalStateException(e);
        }
    }

    /** Current in-game RSN, or {@code null} if not logged into the game. */
    private String currentRsn()
    {
        try
        {
            if (client == null) return null;
            net.runelite.api.Player local = client.getLocalPlayer();
            return local != null ? local.getName() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
