package com.pvp.leaderboard.tournament;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.*;

@RequiredArgsConstructor
public final class GearReporter implements TournamentEventListener
{
    public static final long SEND_MS = 1_000L;
    public static final long RESEND_MS = 10_000L;
    static final long RATE_MIN_MS = 1_000L;
    static final long RATE_MAX_MS = 10_000L;
    static final long JITTER_MS = 1_000L;
    static final long ERROR_RETRY_MS = 10_000L;
    static final long ERROR_MAX_MS = 300_000L;
    static final long STALE_MIN_MS = 1_000L;
    static final long STALE_MAX_MS = 30_000L;
    static final String STATUS_CMD = "tournament/gear_status";

    @AllArgsConstructor
    public static final class View
    {
        public static final View EMPTY = new View(null, null, null, false, false, 0L);

        public final GearTracker.GearEvent event;
        public final GearKit kit;
        public final GearDiff diff;
        public final boolean inCombat;
        public final boolean atArena;
        public final long nowMs;

        public boolean hasEvent()
        {
            return event != null;
        }

        public boolean neverRead()
        {
            return event != null && kit == null;
        }

        public boolean stale()
        {
            return kit != null && kit.stale;
        }

        public boolean verifiedOk()
        {
            return diff != null && diff.ok && !stale();
        }

        public List<Integer> missingIds()
        {
            if (diff == null) return Collections.emptyList();
            List<Integer> out = new ArrayList<>();
            for (GearDiff.Row r : diff.missing)
            {
                out.add(r.itemId);
                out.addAll(r.altIds);
            }
            return out;
        }
    }

    private final TourneySvc service;
    private final GearTracker events;
    private final KitSource source;
    private final GearMatcher matcher;
    private final BooleanSupplier inCombat;
    private final LongSupplier nowMs;
    /** 0 to 1: scales the up-to-{@link #JITTER_MS} added to each {@code RATE_LIMITED} retry. */
    private final DoubleSupplier jitter;
    private final List<Consumer<View>> listeners = new CopyOnWriteArrayList<>();
    private volatile View view = View.EMPTY;

    private String sentKey;
    private String sentSig;
    private boolean sentOk;
    private boolean awaitingAck;
    private long lastSentAtMs;
    private long forceAtMs;
    private long changeAtMs;
    private long staleBackoff = STALE_MIN_MS;
    private long errorBackoff = ERROR_RETRY_MS;
    private long rateBackoff = RATE_MIN_MS;

    /** Without jitter. */
    public GearReporter(TourneySvc service, GearTracker events, KitSource source, GearMatcher matcher,
                              BooleanSupplier inCombat, LongSupplier nowMs)
    {
        this(service, events, source, matcher, inCombat, nowMs, () -> 0.0);
    }

    public View view()
    {
        return view;
    }

    public void addViewListener(Consumer<View> listener)
    {
        listeners.add(listener);
    }

    public void tick()
    {
        long now = nowMs.getAsLong();
        GearTracker.GearEvent ev = events.current();
        View v;
        try
        {
            v = evaluate(ev, now);
        }
        catch (RuntimeException e)
        {
            v = new View(ev, null, null, false, false, now);
        }
        view = v;
        if (ev == null)
        {
            resetSend();
        }
        else if (v.kit != null && v.diff != null)
        {
            sendIfDue(ev, v, now);
        }
        for (Consumer<View> l : listeners)
        {
            try
            {
                l.accept(v);
            }
            catch (RuntimeException e)
            {
            }
        }
    }

    private View evaluate(GearTracker.GearEvent ev, long now)
    {
        boolean fighting = GearKit.safe(inCombat);
        if (ev == null) return new View(null, null, null, fighting, source.atArena(), now);
        GearKit kit = source.kit(ev.set, ev.arena);
        GearDiff diff = matcher.match(ev.set, kit, source.buildInUse(ev.arena));
        return new View(ev, kit, diff, fighting, source.atArena(), now);
    }

    private void sendIfDue(GearTracker.GearEvent ev, View v, long now)
    {
        String key = ev.tournamentId + "|" + ev.set.digest;
        boolean ok = v.verifiedOk();
        String signature = key + "|" + ok + "|" + v.diff.buildOk + "|" + v.diff.spellbookOk + "|" + v.diff.missing.size() + "|"
            + v.diff.extra.size() + "|" + v.kit.source + "|" + v.diff.wireDiff();
        if (!key.equals(sentKey))
        {
            if (forceAtMs == 0 || forceAtMs > now) forceAtMs = now;
        }
        if (signature.equals(sentSig)) changeAtMs = 0;
        else if (changeAtMs == 0) changeAtMs = now + SEND_MS;
        boolean due = (forceAtMs > 0 && now >= forceAtMs) || (changeAtMs > 0 && now >= changeAtMs)
            || (awaitingAck && now - lastSentAtMs >= RESEND_MS);
        if (!due) return;
        if (!ev.arena && source.frozen(false)) return;
        service.gearStatus(ev.tournamentId, ev.set.digest, v.diff, v.kit.source, ok);
        sentKey = key;
        sentSig = signature;
        sentOk = ok;
        awaitingAck = true;
        lastSentAtMs = now;
        forceAtMs = 0;
        changeAtMs = 0;
    }

    private void resetSend()
    {
        sentKey = null;
        sentSig = null;
        awaitingAck = false;
        forceAtMs = 0;
        changeAtMs = 0;
    }

    private long jitterMs()
    {
        return Math.round(jitter.getAsDouble() * JITTER_MS);
    }

    // ---------------------------------------------------------------- listener (EDT)

    @Override
    public void onGearCheck(String tournamentId, int round, long untilEpochS)
    {
        forceAtMs = nowMs.getAsLong();
    }

    @Override
    public void onGearAck(String tournamentId, boolean ok)
    {
        if (!awaitingAck || sentKey == null || tournamentId == null) return;
        if (!sentKey.startsWith(tournamentId + "|") || (!ok && sentOk)) return;
        awaitingAck = false;
        staleBackoff = STALE_MIN_MS;
        errorBackoff = ERROR_RETRY_MS;
        rateBackoff = RATE_MIN_MS;
    }

    @Override
    public void onConnected()
    {
        forceAtMs = nowMs.getAsLong();
    }

    @Override
    public void onTournamentError(String code, String message, String cmd)
    {
        if (!STATUS_CMD.equals(cmd)) return;
        long now = nowMs.getAsLong();
        awaitingAck = false;
        switch (code == null ? "" : code)
        {
            case "RATE_LIMITED":
                forceAtMs = now + rateBackoff + jitterMs();
                rateBackoff = Math.min(rateBackoff * 2, RATE_MAX_MS);
                break;
            case "TOURNAMENT_GEAR_STALE":
                service.status();
                forceAtMs = now + staleBackoff;
                staleBackoff = Math.min(staleBackoff * 2, STALE_MAX_MS);
                break;
            case "TOURNAMENT_NOT_REGISTERED":
                forceAtMs = 0;
                changeAtMs = 0;
                break;
            default:
                forceAtMs = now + errorBackoff;
                errorBackoff = Math.min(errorBackoff * 2, ERROR_MAX_MS);
        }
    }
}
