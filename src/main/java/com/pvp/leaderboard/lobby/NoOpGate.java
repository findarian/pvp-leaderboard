package com.pvp.leaderboard.lobby;

import java.util.*;

/**
 * Inert {@link JoinGate} used in two contexts:
 *
 * <ol>
 *   <li><b>Tests</b> — {@code DashboardPanelTest} and other panel tests
 *   pass this when they don't care about the gate's behaviour, just that
 *   the panel constructs without an NPE.</li>
 *   <li><b>Defensive fallback</b> — if {@code Dashboard} is ever
 *   constructed without a gate (e.g. a future refactor that drops the
 *   ctor arg by accident), the panel substitutes this rather than crash.
 *   It reports every style as unlocked so the user isn't locked out by
 *   a wiring bug.</li>
 * </ol>
 *
 * <p>"Unlocked" is conveyed by returning {@link JoinGate#THRESHOLD}
 * (i.e. exactly the threshold) for every style — that's the safest
 * default because the server-side {@code SMURF_GUARD} is the authoritative
 * check. A misbehaving local gate that says "unlocked" just means the
 * server gets the final word.
 */
public final class NoOpGate implements JoinGate
{
    private final Map<Style, Integer> counts;

    public NoOpGate()
    {
        EnumMap<Style, Integer> m = new EnumMap<>(Style.class);
        for (Style s : Style.values()) m.put(s, THRESHOLD);
        counts = Collections.unmodifiableMap(m);
    }

    @Override public Map<Style, Integer> getMatchCounts() { return counts; }
    /** Always {@code true} — the NoOp is used in tests + as the
     *  fallback when no real gate was wired in; in both contexts we
     *  don't want to hide the panel behind "Please log into the game".
     *  Tests render the lobby UI deterministically regardless of
     *  game-state, and the fallback path implies a wiring bug already
     *  (no reason to make it worse by hiding the panel). */
    @Override public boolean isLoggedIn() { return true; }
    @Override public void refresh() { /* no-op */ }
    @Override public void addListener(Runnable listener) { /* no-op */ }
}
