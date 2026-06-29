package com.pvp.leaderboard.service;

import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends presence heartbeats to the backend.
 *
 * <h2>Scope (p15-membership-feed)</h2>
 * This service used to ALSO fetch {@code whitelist.json} to drive the rank
 * overlay. That responsibility moved to {@link MembershipService}, which
 * consumes the names-only snapshot/delta feed ({@code plugin-users/snapshot.json}
 * + {@code delta.json}) — see PLAN_PRESENCE_FRESHNESS.md. The plugin no longer
 * downloads {@code whitelist.json}; the backend keeps generating it only as a
 * server-side fallback. This class is now heartbeat-only.
 *
 * <h2>Heartbeat behaviour</h2>
 * <ul>
 *   <li>Sends a POST on login, then every 5 minutes.</li>
 *   <li>Only sends when a username is available AND "Show your rank to others"
 *       is enabled.</li>
 *   <li>Auth: {@code X-Client-Unique-Id} (UUID) + {@code User-Agent} headers.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class WhitelistService
{
    private static final String HEARTBEAT_URL = "https://l5xya0wf0d.execute-api.us-east-1.amazonaws.com/prod/heartbeat";
    private static final String USER_AGENT = "RuneLite/" + RuneLiteProperties.getVersion();

    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L; // 5 minutes

    private final OkHttpClient okHttpClient;
    private final PvPLeaderboardConfig config;
    private final ScheduledExecutorService scheduler;
    private final ClientIdentityService clientIdentityService;

    // Heartbeat state
    private volatile String currentUsername = null;
    private volatile ScheduledFuture<?> scheduledHeartbeat = null;

    @Inject
    public WhitelistService(OkHttpClient okHttpClient, PvPLeaderboardConfig config,
                            ScheduledExecutorService scheduler,
                            ClientIdentityService clientIdentityService)
    {
        this.okHttpClient = okHttpClient;
        this.config = config;
        this.scheduler = scheduler;
        this.clientIdentityService = clientIdentityService;
    }

    /**
     * Called when player logs in. Starts the heartbeat cycle.
     *
     * @param username The player's username (required for heartbeat)
     */
    public void onLogin(String username)
    {
        if (username == null || username.trim().isEmpty())
        {
            log.debug("[Heartbeat] No username provided, skipping login tasks");
            return;
        }

        this.currentUsername = username.trim();
        log.debug("[Heartbeat] Player logged in: {} - starting heartbeat cycle", currentUsername);

        // Cancel any existing heartbeat schedule to prevent chain accumulation
        cancelScheduledHeartbeat();

        // Send immediate heartbeat on login
        log.debug("[Heartbeat] Sending initial heartbeat on login");
        sendHeartbeat();

        // Schedule recurring heartbeat every 5 minutes
        scheduleHeartbeat();
    }

    /**
     * Called when player logs out.
     */
    public void onLogout()
    {
        log.debug("[Heartbeat] Player logged out - stopping heartbeat cycle");
        currentUsername = null;
        cancelScheduledHeartbeat();
    }

    /**
     * Whether a heartbeat schedule is currently active.
     */
    public boolean isHeartbeatActive()
    {
        ScheduledFuture<?> scheduled = scheduledHeartbeat;
        return scheduled != null && !scheduled.isDone() && currentUsername != null;
    }

    /**
     * Cancel any scheduled heartbeat to prevent chain accumulation.
     */
    private void cancelScheduledHeartbeat()
    {
        ScheduledFuture<?> scheduled = scheduledHeartbeat;
        if (scheduled != null && !scheduled.isDone())
        {
            scheduled.cancel(false);
            log.debug("[Heartbeat] Cancelled existing scheduled heartbeat");
        }
        scheduledHeartbeat = null;
    }

    /**
     * Schedule the next heartbeat in 5 minutes.
     */
    private void scheduleHeartbeat()
    {
        log.debug("[Heartbeat] Scheduling next heartbeat in {} minutes", HEARTBEAT_INTERVAL_MS / 60000);
        scheduledHeartbeat = scheduler.schedule(() -> {
            if (currentUsername != null)
            {
                log.debug("[Heartbeat] Scheduled heartbeat triggered for user: {}", currentUsername);
                sendHeartbeat();
                scheduleHeartbeat(); // Schedule next one
            }
            else
            {
                log.debug("[Heartbeat] Scheduled heartbeat skipped - no user logged in");
            }
        }, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Send a heartbeat to the server.
     * Only sends if "Show your rank to others" is enabled.
     */
    private void sendHeartbeat()
    {
        if (!config.showRankToOthers())
        {
            log.debug("[Heartbeat] Skipped - 'Show your rank to others' is disabled");
            return;
        }

        String username = currentUsername;
        if (username == null || username.isEmpty())
        {
            log.debug("[Heartbeat] No username available, skipping heartbeat");
            return;
        }

        String clientUuid = clientIdentityService.getClientUniqueId();
        if (clientUuid == null || clientUuid.isEmpty())
        {
            log.debug("[Heartbeat] No client UUID available, skipping heartbeat");
            return;
        }

        // Build JSON body with username
        String jsonBody = "{\"username\":\"" + username.replace("\"", "\\\"") + "\"}";

        log.debug("[Heartbeat] Sending heartbeat - URL: {} | UUID: {} | User-Agent: {} | Body: {}",
            HEARTBEAT_URL, clientUuid, USER_AGENT, jsonBody);

        okhttp3.RequestBody body = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json"),
            jsonBody
        );

        Request request = new Request.Builder()
            .url(HEARTBEAT_URL)
            .header("X-Client-Unique-Id", clientUuid)
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[Heartbeat] Network failure - Error: {} | Type: {}", e.getMessage(), e.getClass().getSimpleName());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    okhttp3.ResponseBody rb = res.body();
                    String responseBody = rb != null ? rb.string() : "(empty)";

                    if (res.isSuccessful())
                    {
                        log.debug("[Heartbeat] Success - HTTP {} | User: {} | Response: {}",
                            res.code(), username, responseBody);
                    }
                    else
                    {
                        log.debug("[Heartbeat] HTTP error - Code: {} | Message: {} | Response: {}",
                            res.code(), res.message(), responseBody);
                    }
                }
            }
        });
    }
}
