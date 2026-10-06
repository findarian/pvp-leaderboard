package com.pvp.leaderboard.service;

import com.pvp.leaderboard.*;
import com.pvp.leaderboard.config.*;
import java.io.*;
import java.util.concurrent.*;
import javax.inject.*;
import okhttp3.*;

/**
 * Sends presence heartbeats to the backend.
 *
 * <h2>Scope (p15-membership-feed)</h2>
 * This service used to ALSO fetch {@code whitelist.json} to drive the rank
 * overlay. That responsibility moved to {@link MemberFeed}, which
 * consumes the names-only snapshot/delta feed ({@code plugin-users/snapshot.json}
 * + {@code delta.json}) — see PLAN_PRESENCE_FRESHNESS.md. The plugin no longer
 * downloads {@code whitelist.json}; the backend keeps generating it only as a
 * server-side fallback. This class is now heartbeat-only.
 *
 * <h2>Heartbeat behaviour</h2>
 * <ul>
 *   <li>Sends a POST on login, then every 5 minutes (each 5 minutes after
 *       the previous one ran) until logout.</li>
 *   <li>Only sends when a username is available AND "Show your rank to others"
 *       is enabled.</li>
 *   <li>Auth: {@code X-Client-Unique-Id} (UUID) + {@code User-Agent} headers.</li>
 * </ul>
 */
@Singleton
public class WhitelistService
{
    private static final String HEARTBEAT_URL = PvpConsts.API_BASE_URL + "/heartbeat";

    private static final long HEARTBEAT_MS = 5L * 60L * 1000L; // 5 minutes

    private final OkHttpClient okHttpClient;
    private final PvPLeaderboardConfig config;
    private final ScheduledExecutorService scheduler;
    private final IdentitySvc identitySvc;

    // Heartbeat state
    private volatile String currentUsername = null;
    private volatile ScheduledFuture<?> scheduledHeartbeat = null;

    @Inject
    public WhitelistService(OkHttpClient okHttpClient, PvPLeaderboardConfig config,
                            ScheduledExecutorService scheduler,
                            IdentitySvc identitySvc)
    {
        this.okHttpClient = okHttpClient;
        this.config = config;
        this.scheduler = scheduler;
        this.identitySvc = identitySvc;
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
            return;
        }

        this.currentUsername = username.trim();

        // Cancel any existing heartbeat schedule to prevent chain accumulation
        cancelScheduledHeartbeat();

        // Send immediate heartbeat on login
        sendHeartbeat();

        // Then one every 5 minutes, each 5 minutes after the previous one
        // ran; a throw ends the chain, logout cancels it.
        scheduledHeartbeat = scheduler.scheduleWithFixedDelay(this::sendHeartbeat,
            HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Called when player logs out.
     */
    public void onLogout()
    {
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
        // cancel() is false for a future that already finished.
        if (scheduled != null && scheduled.cancel(false))
        {
        }
        scheduledHeartbeat = null;
    }

    /**
     * Send a heartbeat to the server.
     * Only sends if "Show your rank to others" is enabled.
     */
    private void sendHeartbeat()
    {
        if (!config.showRankToOthers())
        {
            return;
        }

        String username = currentUsername;
        if (username == null || username.isEmpty())
        {
            return;
        }

        String clientUuid = identitySvc.getClientUniqueId();
        if (clientUuid == null || clientUuid.isEmpty())
        {
            return;
        }

        // Build JSON body with username
        String jsonBody = "{\"username\":\"" + username.replace("\"", "\\\"") + "\"}";

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);

        Request request = new Request.Builder()
            .url(HEARTBEAT_URL)
            .header("X-Client-Unique-Id", clientUuid)
            .header("User-Agent", PvpConsts.USER_AGENT)
            .post(body)
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
            }

            /** The body is not read: closing discards it and keeps the connection. */
            @Override
            public void onResponse(Call call, Response response)
            {
                response.close();
            }
        });
    }
}
