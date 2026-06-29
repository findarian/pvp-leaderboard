package com.pvp.leaderboard.cache;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin-side membership set for the rank overlay's opt-in gate.
 *
 * <p>Membership comes from the backend snapshot/delta feed
 * ({@code plugin-users/snapshot.json} + {@code plugin-users/delta.json}),
 * which replaces the per-poll {@code whitelist.json} blob. The overlay only
 * needs to know <em>whether</em> an on-screen name is an opted-in plugin user;
 * the rank itself is resolved separately and on-demand via the name-keyed
 * {@code rank_idx} shards. So this cache stores <strong>names only</strong>.</p>
 *
 * <h2>Why a baseline + absolute delta (not a mutating log)</h2>
 * The delta is an <strong>absolute</strong> diff vs the day's snapshot, so the
 * effective set is the pure function {@code (baseline ∪ added) \ removed}.
 * {@link #applyDelta} recomputes the live set from the stored {@code baseline}
 * every time rather than mutating in place. This makes re-applying the same
 * delta idempotent and avoids the "a name that appeared in an earlier delta but
 * is no longer in the current diff lingers forever" bug a running log would hit.
 *
 * <h2>Epoch</h2>
 * The snapshot stamps an {@code epoch}; the delta carries the epoch of the
 * snapshot it was computed against. A delta whose epoch != the cached snapshot
 * epoch is <strong>ignored</strong> (it's stale across a daily rollover — the
 * fresh snapshot is the source of truth until the next delta catches up).
 *
 * <p>Thread-safe: a daily/10-min network callback writes while the ~60fps
 * overlay render reads. All mutation is synchronized on {@code this}; reads use
 * a concurrent view.</p>
 */
@Slf4j
@Singleton
public class MembershipCache
{
    /** Last snapshot's full member set (canonicalized). Source for delta recompute. */
    private final Set<String> baseline = new HashSet<>();

    /** Effective set the overlay checks = (baseline ∪ added) \ removed. */
    private final Set<String> members = ConcurrentHashMap.newKeySet();

    private volatile long epoch = -1L;
    private volatile long lastRefreshMs = 0L;

    @Inject
    public MembershipCache()
    {
    }

    /**
     * Replace the entire membership set from a fresh snapshot.
     *
     * @param names         member display names (canonicalized internally)
     * @param snapshotEpoch the snapshot's epoch (deltas must match this)
     */
    public synchronized void replaceFromSnapshot(Collection<String> names, long snapshotEpoch)
    {
        baseline.clear();
        for (String n : canon(names))
        {
            baseline.add(n);
        }
        rebuildMembers();
        epoch = snapshotEpoch;
        lastRefreshMs = System.currentTimeMillis();
        log.debug("[Membership] snapshot loaded: {} members, epoch={}", members.size(), epoch);
    }

    /**
     * Apply an absolute delta computed against the snapshot of {@code deltaEpoch}.
     *
     * @return {@code true} if applied; {@code false} if ignored as stale
     *         (its epoch does not match the cached snapshot epoch).
     */
    public synchronized boolean applyDelta(Collection<String> added, Collection<String> removed,
                                           long deltaEpoch)
    {
        if (deltaEpoch != epoch)
        {
            log.debug("[Membership] delta ignored (stale epoch {} != {})", deltaEpoch, epoch);
            return false;
        }

        members.clear();
        members.addAll(baseline);
        members.addAll(canon(added));
        members.removeAll(canon(removed));
        lastRefreshMs = System.currentTimeMillis();
        log.debug("[Membership] delta applied: +{} -{} -> {} members",
            added == null ? 0 : added.size(),
            removed == null ? 0 : removed.size(),
            members.size());
        return true;
    }

    private void rebuildMembers()
    {
        members.clear();
        members.addAll(baseline);
    }

    /** Whether {@code playerName} is an opted-in plugin user. */
    public boolean isMember(String playerName)
    {
        if (playerName == null)
        {
            return false;
        }
        String key = canonicalize(playerName);
        return !key.isEmpty() && members.contains(key);
    }

    public int size()
    {
        return members.size();
    }

    public long getEpoch()
    {
        return epoch;
    }

    public long getLastRefreshMs()
    {
        return lastRefreshMs;
    }

    private static Set<String> canon(Collection<String> names)
    {
        Set<String> out = new HashSet<>();
        if (names == null)
        {
            return out;
        }
        for (String n : names)
        {
            if (n == null)
            {
                continue;
            }
            String c = canonicalize(n);
            if (!c.isEmpty())
            {
                out.add(c);
            }
        }
        return out;
    }

    /** Matches the backend {@code canon_name}: trim, collapse spaces, lowercase. */
    private static String canonicalize(String name)
    {
        return name.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
