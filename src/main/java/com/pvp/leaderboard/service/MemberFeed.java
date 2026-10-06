package com.pvp.leaderboard.service;

import com.google.gson.*;
import com.pvp.leaderboard.*;
import com.pvp.leaderboard.cache.*;
import com.pvp.leaderboard.config.*;
import com.pvp.leaderboard.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.inject.*;
import okhttp3.*;

/**
 * Fetches the rank-overlay MEMBERSHIP feed (names only) and maintains
 * {@link MemberCache}.
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
@Singleton
public class MemberFeed
{
    private static final String SNAPSHOT_URL =
        PvpConsts.SITE_URL + "/plugin-users/snapshot.json";
    private static final String DELTA_URL =
        PvpConsts.SITE_URL + "/plugin-users/delta.json";

    /** Matches the backend membership_generator 10-min delta cadence. */
    private static final long DELTA_MS = 10L * 60L * 1000L;

    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final PvPLeaderboardConfig config;
    private final MemberCache cache;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> scheduledPoll = null;
    private volatile String snapshotEtag = null;

    @Inject
    public MemberFeed(OkHttpClient okHttpClient, Gson gson, PvPLeaderboardConfig config,
                             MemberCache cache, ScheduledExecutorService scheduler)
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
            return;
        }
        if (!active.compareAndSet(false, true))
        {
            return; // already running
        }
        fetch(true);
        scheduleDeltaPoll();
    }

    /**
     * Stop the membership sync. Safe to call when not running.
     */
    public void onLogout()
    {
        active.set(false);
        ScheduledFuture<?> scheduled = scheduledPoll;
        if (scheduled != null)
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
                fetch(false);
                scheduleDeltaPoll();
            }
        }, DELTA_MS, TimeUnit.MILLISECONDS);
    }

    /** One GET of the snapshot (revalidated with its ETag; a 304 leaves the
     *  cache as it is) or of the delta, applied to the cache. */
    private void fetch(boolean snapshot)
    {
        var builder = new Request.Builder()
            .url(snapshot ? SNAPSHOT_URL : DELTA_URL)
            .header("User-Agent", PvpConsts.USER_AGENT)
            .get();
        String etag = snapshotEtag;
        if (snapshot && etag != null)
        {
            builder.header("If-None-Match", etag);
        }

        okHttpClient.newCall(builder.build()).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        return;
                    }
                    String json = res.body().string();
                    String tag = res.header("ETag");
                    if (snapshot && tag != null)
                    {
                        snapshotEtag = tag;
                    }
                    apply(json, snapshot);
                }
                catch (Exception e)
                {
                }
            }
        });
    }

    private void apply(String json, boolean snapshot)
    {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null)
        {
            return;
        }
        long epoch = JsonLenient.optLong(root, "epoch", 0L);
        if (snapshot)
        {
            cache.loadSnapshot(parseStrings(root, "names"), epoch);
        }
        // Epoch mismatch = the daily snapshot rolled over since our last
        // snapshot fetch. Re-fetch the snapshot (its new epoch becomes the
        // baseline); the next delta poll will then line up.
        else if (epoch != cache.getEpoch())
        {
            fetch(true);
        }
        else
        {
            cache.applyDelta(parseStrings(root, "added"), parseStrings(root, "removed"), epoch);
        }
    }

    private List<String> parseStrings(JsonObject obj, String field)
    {
        List<String> out = new ArrayList<>();
        for (JsonElement el : JsonLenient.optArray(obj, field))
        {
            if (!el.isJsonNull())
            {
                out.add(el.getAsString());
            }
        }
        return out;
    }
}
