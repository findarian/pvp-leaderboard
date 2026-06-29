package com.pvp.leaderboard.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.PvPLeaderboardConstants;
import com.pvp.leaderboard.cache.MembershipCache;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches the rank-overlay MEMBERSHIP feed (names only) and maintains
 * {@link MembershipCache}.
 *
 * <p>Replaces the per-poll {@code whitelist.json} download (which bundled
 * membership + rank into one O(N) blob every client re-pulled every poll →
 * O(N^2) egress). Here:</p>
 * <ul>
 *   <li>{@code plugin-users/snapshot.json} — full member set, fetched once on
 *       login + ETag-revalidated (304 the rest of the day).</li>
 *   <li>{@code plugin-users/delta.json} — absolute diff vs the day's snapshot,
 *       polled every 10 minutes (tiny payload, mostly unchanged).</li>
 * </ul>
 *
 * <p>Rank is NOT in this feed — the overlay resolves rank per on-screen name
 * via the name-keyed {@code rank_idx} shards. See PLAN_PRESENCE_FRESHNESS.md.</p>
 *
 * <p>The plugin uses ONLY this feed for membership; the legacy
 * {@code whitelist.json} path is retired client-side (the backend keeps it as
 * a fallback). Heartbeats still flow via {@link WhitelistService}.</p>
 */
@Slf4j
@Singleton
public class MembershipService
{
    private static final String SNAPSHOT_URL =
        PvPLeaderboardConstants.PUBLIC_SITE_BASE_URL + "/plugin-users/snapshot.json";
    private static final String DELTA_URL =
        PvPLeaderboardConstants.PUBLIC_SITE_BASE_URL + "/plugin-users/delta.json";
    private static final String USER_AGENT = "RuneLite/" + RuneLiteProperties.getVersion();

    /** Matches the backend membership_generator 10-min delta cadence. */
    private static final long DELTA_POLL_MS = 10L * 60L * 1000L;

    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final PvPLeaderboardConfig config;
    private final MembershipCache cache;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> scheduledPoll = null;
    private volatile String snapshotEtag = null;

    @Inject
    public MembershipService(OkHttpClient okHttpClient, Gson gson, PvPLeaderboardConfig config,
                             MembershipCache cache, ScheduledExecutorService scheduler)
    {
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.config = config;
        this.cache = cache;
        this.scheduler = scheduler;
    }

    /**
     * Start the membership sync (snapshot now, then delta every 10 min).
     * No-op when "Display other players ranks" is disabled.
     */
    public void onLogin()
    {
        if (!config.enableWhitelistRanks())
        {
            log.debug("[Membership] Display-others-ranks disabled — not starting sync");
            return;
        }
        if (!active.compareAndSet(false, true))
        {
            return; // already running
        }
        log.debug("[Membership] Starting membership sync");
        fetchSnapshot();
        scheduleDeltaPoll();
    }

    /**
     * Stop the membership sync. Safe to call when not running.
     */
    public void onLogout()
    {
        active.set(false);
        ScheduledFuture<?> scheduled = scheduledPoll;
        if (scheduled != null && !scheduled.isDone())
        {
            scheduled.cancel(false);
        }
        scheduledPoll = null;
    }

    private void scheduleDeltaPoll()
    {
        scheduledPoll = scheduler.schedule(() ->
        {
            if (active.get())
            {
                pollDelta();
                scheduleDeltaPoll();
            }
        }, DELTA_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void fetchSnapshot()
    {
        Request.Builder builder = new Request.Builder()
            .url(SNAPSHOT_URL)
            .header("User-Agent", USER_AGENT)
            .get();
        String etag = snapshotEtag;
        if (etag != null)
        {
            builder.header("If-None-Match", etag);
        }

        okHttpClient.newCall(builder.build()).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[Membership] Snapshot fetch failed: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    if (res.code() == 304)
                    {
                        log.debug("[Membership] Snapshot 304 (unchanged)");
                        return;
                    }
                    if (!res.isSuccessful())
                    {
                        log.debug("[Membership] Snapshot HTTP {}", res.code());
                        return;
                    }
                    ResponseBody body = res.body();
                    if (body == null)
                    {
                        return;
                    }
                    String json = body.string();
                    String tag = res.header("ETag");
                    if (tag != null)
                    {
                        snapshotEtag = tag;
                    }
                    applySnapshotJson(json);
                }
                catch (Exception e)
                {
                    log.debug("[Membership] Snapshot parse error: {}", e.getMessage());
                }
            }
        });
    }

    private void pollDelta()
    {
        Request request = new Request.Builder()
            .url(DELTA_URL)
            .header("User-Agent", USER_AGENT)
            .get()
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[Membership] Delta fetch failed: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        log.debug("[Membership] Delta HTTP {}", res.code());
                        return;
                    }
                    ResponseBody body = res.body();
                    if (body == null)
                    {
                        return;
                    }
                    applyDeltaJson(body.string());
                }
                catch (Exception e)
                {
                    log.debug("[Membership] Delta parse error: {}", e.getMessage());
                }
            }
        });
    }

    private void applySnapshotJson(String json)
    {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null)
        {
            return;
        }
        long epoch = root.has("epoch") && !root.get("epoch").isJsonNull()
            ? root.get("epoch").getAsLong() : 0L;
        List<String> names = parseStringArray(root, "names");
        cache.replaceFromSnapshot(names, epoch);
    }

    private void applyDeltaJson(String json)
    {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null)
        {
            return;
        }
        long epoch = root.has("epoch") && !root.get("epoch").isJsonNull()
            ? root.get("epoch").getAsLong() : 0L;

        // Epoch mismatch = the daily snapshot rolled over since our last
        // snapshot fetch. Re-fetch the snapshot (its new epoch becomes the
        // baseline); the next delta poll will then line up.
        if (epoch != cache.getEpoch())
        {
            log.debug("[Membership] Delta epoch {} != cached {} — refetching snapshot",
                epoch, cache.getEpoch());
            fetchSnapshot();
            return;
        }

        List<String> added = parseStringArray(root, "added");
        List<String> removed = parseStringArray(root, "removed");
        cache.applyDelta(added, removed, epoch);
    }

    private List<String> parseStringArray(JsonObject obj, String field)
    {
        List<String> out = new ArrayList<>();
        if (obj.has(field) && obj.get(field).isJsonArray())
        {
            JsonArray arr = obj.getAsJsonArray(field);
            for (var el : arr)
            {
                if (el != null && !el.isJsonNull())
                {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }
}
