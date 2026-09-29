package com.pvp.leaderboard.tournament;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

@Slf4j
public final class GearStatusReporter implements TournamentEventListener
{
    public static final long SEND_DELAY_MS = 1_000L;
    public static final long RESEND_MS = 10_000L;
    static final long RATE_LIMIT_RETRY_MS = 1_000L;
    static final long RATE_LIMIT_MAX_MS = 10_000L;
    static final long RATE_LIMIT_JITTER_MS = 1_000L;
    static final long ERROR_RETRY_MS = 10_000L;
    static final long ERROR_BACKOFF_MAX_MS = 300_000L;
    static final long STALE_BACKOFF_START_MS = 1_000L;
    static final long STALE_BACKOFF_MAX_MS = 30_000L;
    static final String GEAR_STATUS_CMD = "tournament/gear_status";

    public static final class View
    {
        public static final View EMPTY = new View(null, null, null, false, false, 0L);

        public final GearEventTracker.GearEvent event;
        public final GearKit kit;
        public final GearDiff diff;
        public final boolean inCombat;
        public final boolean atArena;
        public final long nowMs;

        public View(GearEventTracker.GearEvent event, GearKit kit, GearDiff diff, boolean inCombat, boolean atArena, long nowMs)
        {
            this.event = event;
            this.kit = kit;
            this.diff = diff;
            this.inCombat = inCombat;
            this.atArena = atArena;
            this.nowMs = nowMs;
        }

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

    private final TournamentService service;
    private final GearEventTracker events;
    private final GearKitSource source;
    private final GearMatcher matcher;
    private final BooleanSupplier inCombat;
    private final LongSupplier nowMs;
    private final DoubleSupplier jitter;
    private final List<Consumer<View>> listeners = new CopyOnWriteArrayList<>();
    private volatile View view = View.EMPTY;

    private String sentKey;
    private String sentSignature;
    private boolean sentOk;
    private boolean awaitingAck;
    private long lastSentAtMs;
    private long forceAtMs;
    private long changeAtMs;
    private long staleBackoffMs = STALE_BACKOFF_START_MS;
    private long errorBackoffMs = ERROR_RETRY_MS;
    private long rateLimitBackoffMs = RATE_LIMIT_RETRY_MS;

    /** Without jitter; see the seven-argument constructor. */
    public GearStatusReporter(TournamentService service, GearEventTracker events, GearKitSource source, GearMatcher matcher,
                              BooleanSupplier inCombat, LongSupplier nowMs)
    {
        this(service, events, source, matcher, inCombat, nowMs, null);
    }

    /** {@code jitter} (0 to 1, {@code null} = 0) scales the up-to-{@link #RATE_LIMIT_JITTER_MS}
     *  added to each {@code RATE_LIMITED} retry. */
    public GearStatusReporter(TournamentService service, GearEventTracker events, GearKitSource source, GearMatcher matcher,
                              BooleanSupplier inCombat, LongSupplier nowMs, DoubleSupplier jitter)
    {
        this.service = service;
        this.events = events;
        this.source = source;
        this.matcher = matcher == null ? new GearMatcher(null) : matcher;
        this.inCombat = inCombat == null ? () -> false : inCombat;
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
        this.jitter = jitter == null ? () -> 0.0 : jitter;
    }

    public View view()
    {
        return view;
    }

    public void addViewListener(Consumer<View> listener)
    {
        if (listener != null) listeners.add(listener);
    }

    public void removeViewListener(Consumer<View> listener)
    {
        listeners.remove(listener);
    }

    public void tick()
    {
        long now = nowMs.getAsLong();
        GearEventTracker.GearEvent ev = events.current();
        View v;
        try
        {
            v = evaluate(ev, now);
        }
        catch (RuntimeException e)
        {
            log.debug("[Gear] evaluate failed", e);
            v = new View(ev, null, null, false, false, now);
        }
        view = v;
        if (ev == null)
        {
            resetSendState();
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
                log.debug("[Gear] view listener threw", e);
            }
        }
    }

    private View evaluate(GearEventTracker.GearEvent ev, long now)
    {
        boolean fighting = safe(inCombat);
        if (ev == null) return new View(null, null, null, fighting, source != null && source.atArena(), now);
        GearKit kit = source.kit(ev.set, ev.arena);
        GearDiff diff = matcher.match(ev.set, kit, source.buildInUse(ev.arena));
        return new View(ev, kit, diff, fighting, source.atArena(), now);
    }

    private void sendIfDue(GearEventTracker.GearEvent ev, View v, long now)
    {
        String key = ev.tournamentId + "|" + ev.set.digest;
        boolean ok = v.verifiedOk();
        String signature = key + "|" + ok + "|" + v.diff.buildOk + "|" + v.diff.spellbookOk + "|" + v.diff.missingCount() + "|"
            + v.diff.extraCount() + "|" + v.kit.source + "|" + v.diff.wireDiff();
        if (!key.equals(sentKey))
        {
            if (forceAtMs == 0 || forceAtMs > now) forceAtMs = now;
        }
        if (signature.equals(sentSignature)) changeAtMs = 0;
        else if (changeAtMs == 0) changeAtMs = now + SEND_DELAY_MS;
        boolean due = (forceAtMs > 0 && now >= forceAtMs) || (changeAtMs > 0 && now >= changeAtMs)
            || (awaitingAck && now - lastSentAtMs >= RESEND_MS);
        if (!due) return;
        if (!ev.arena && source.frozen(false)) return;
        service.gearStatus(ev.tournamentId, ev.set.digest, v.diff, v.kit.source, ok);
        sentKey = key;
        sentSignature = signature;
        sentOk = ok;
        awaitingAck = true;
        lastSentAtMs = now;
        forceAtMs = 0;
        changeAtMs = 0;
    }

    private void resetSendState()
    {
        sentKey = null;
        sentSignature = null;
        awaitingAck = false;
        forceAtMs = 0;
        changeAtMs = 0;
    }

    private long jitterMs()
    {
        double r;
        try
        {
            r = jitter.getAsDouble();
        }
        catch (RuntimeException e)
        {
            r = 0.0;
        }
        if (Double.isNaN(r)) r = 0.0;
        return Math.round(Math.max(0.0, Math.min(1.0, r)) * RATE_LIMIT_JITTER_MS);
    }

    private static boolean safe(BooleanSupplier s)
    {
        try
        {
            return s.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    // ---------------------------------------------------------------- listener (EDT)

    @Override
    public void onGearCheck(String tournamentId, int round, long untilEpochS)
    {
        forceAtMs = nowMs.getAsLong();
    }

    @Override
    public void onGearAck(String tournamentId, boolean ok, long receivedAt)
    {
        if (!awaitingAck || sentKey == null || tournamentId == null) return;
        if (!sentKey.startsWith(tournamentId + "|") || (!ok && sentOk)) return;
        awaitingAck = false;
        staleBackoffMs = STALE_BACKOFF_START_MS;
        errorBackoffMs = ERROR_RETRY_MS;
        rateLimitBackoffMs = RATE_LIMIT_RETRY_MS;
    }

    @Override
    public void onSocketConnected()
    {
        forceAtMs = nowMs.getAsLong();
    }

    @Override
    public void onTournamentError(String code, String message, String cmd)
    {
        if (!GEAR_STATUS_CMD.equals(cmd)) return;
        long now = nowMs.getAsLong();
        awaitingAck = false;
        switch (code == null ? "" : code)
        {
            case "RATE_LIMITED":
                forceAtMs = now + rateLimitBackoffMs + jitterMs();
                rateLimitBackoffMs = Math.min(rateLimitBackoffMs * 2, RATE_LIMIT_MAX_MS);
                break;
            case "TOURNAMENT_GEAR_STALE":
                service.status();
                forceAtMs = now + staleBackoffMs;
                staleBackoffMs = Math.min(staleBackoffMs * 2, STALE_BACKOFF_MAX_MS);
                break;
            case "TOURNAMENT_NOT_REGISTERED":
                forceAtMs = 0;
                changeAtMs = 0;
                break;
            default:
                forceAtMs = now + errorBackoffMs;
                errorBackoffMs = Math.min(errorBackoffMs * 2, ERROR_BACKOFF_MAX_MS);
        }
    }
}
