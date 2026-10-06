package com.pvp.leaderboard.cache;

import lombok.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.*;

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
@Singleton
public class MemberCache
{
    /** Last snapshot's full member set (canonicalized). Source for delta recompute. */
    private final Set<String> baseline = new HashSet<>();

    /** Effective set the overlay checks = (baseline ∪ added) \ removed. */
    private final Set<String> members = ConcurrentHashMap.newKeySet();

    @Getter private volatile long epoch = -1L;
    private volatile long refreshMs = 0L;

    @Inject
    public MemberCache()
    {
    }

    /**
     * Replace the entire membership set from a fresh snapshot.
     *
     * @param names         member display names (canonicalized internally)
     * @param snapshotEpoch the snapshot's epoch (deltas must match this)
     */
    public synchronized void loadSnapshot(Collection<String> names, long snapshotEpoch)
    {
        baseline.clear();
        baseline.addAll(canon(names));
        members.clear();
        members.addAll(baseline);
        epoch = snapshotEpoch;
        refreshMs = System.currentTimeMillis();
    }

    /**
     * Apply an absolute delta computed against the snapshot of {@code deltaEpoch};
     * ignored as stale when its epoch does not match the cached snapshot epoch.
     */
    public synchronized void applyDelta(Collection<String> added, Collection<String> removed,
                                        long deltaEpoch)
    {
        if (deltaEpoch != epoch)
        {
            return;
        }

        members.clear();
        members.addAll(baseline);
        members.addAll(canon(added));
        members.removeAll(canon(removed));
        refreshMs = System.currentTimeMillis();
    }

    /** Whether {@code playerName} is an opted-in plugin user (a blank or
     *  null name canonicalizes to "", which is never stored). */
    public boolean isMember(String playerName)
    {
        return members.contains(NameUtils.canonicalKey(playerName));
    }

    public int size()
    {
        return members.size();
    }

    /** The names canonicalized like the backend {@code canon_name}: trim, collapse spaces,
     *  lowercase; blank and null names are dropped.
     *  Uses {@link NameUtils#canonicalKey} — the single plugin-side
     *  canonicalization authority — so nameplate forms with non-breaking
     *  spaces ({@code \u00A0}) match the feed's backend-canonical names. */
    private static Set<String> canon(Collection<String> names)
    {
        Set<String> out = new HashSet<>();
        for (String n : names)
        {
            String c = NameUtils.canonicalKey(n);
            if (!c.isEmpty())
            {
                out.add(c);
            }
        }
        return out;
    }
}
