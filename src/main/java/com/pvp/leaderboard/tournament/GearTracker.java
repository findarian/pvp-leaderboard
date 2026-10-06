package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import lombok.*;

@RequiredArgsConstructor
public final class GearTracker implements TournamentEventListener
{
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class GearEvent
    {
        public final String tournamentId;
        public final GearSet set;
        public final boolean arena;
        public final boolean running;
        public final long checkUntilS;
        public final int checkRound;

        public boolean prepOpen(long nowEpochS)
        {
            return checkUntilS > nowEpochS;
        }

        boolean sameAs(GearEvent o)
        {
            return o != null && tournamentId.equals(o.tournamentId) && set == o.set && arena == o.arena && running == o.running
                && checkUntilS == o.checkUntilS && checkRound == o.checkRound;
        }
    }

    @RequiredArgsConstructor
    private static final class Entry
    {
        final String id;
        GearSet set;
        String location;
        long startsAt;
        boolean registered;
        boolean running;
        long checkUntil;
        int checkRound;
    }

    private final Runnable requestStatus;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile GearEvent current;

    public GearEvent current()
    {
        return current;
    }

    // ---------------------------------------------------------------- listener (EDT)

    @Override
    public void onTournamentList(List<Tourney> tournaments)
    {
        if (tournaments == null) return;
        for (Tourney t : tournaments)
        {
            if (t == null) continue;
            Entry e = entries.get(t.tournamentId);
            if (t.gearSet == null)
            {
                if (e != null && e.set != null) entries.remove(t.tournamentId);
                continue;
            }
            e = learn(t);
            if (t.myStatus != null) e.registered = t.amRegistered();
        }
        publish();
    }

    @Override
    public void onRegistered(Tourney tournament, String regStatus)
    {
        if (tournament == null) return;
        Entry e = tournament.gearSet != null ? learn(tournament) : entries.get(tournament.tournamentId);
        if (e != null) e.registered = "registered".equals(regStatus);
        publish();
    }

    @Override
    public void onWithdrawn(String tournamentId)
    {
        Entry e = tournamentId == null ? null : entries.get(tournamentId);
        if (e != null) e.registered = false;
        publish();
    }

    @Override
    public void onTournamentState(List<Tourney> registrations, LiveTourney active)
    {
        Set<String> mine = new HashSet<>();
        if (registrations != null)
        {
            for (Tourney r : registrations)
            {
                if (r == null) continue;
                if (r.gearSet != null) learn(r);
                if (r.amRegistered()) mine.add(r.tournamentId);
            }
        }
        boolean activeRunning = active != null && active.isRunning();
        if (activeRunning) mine.add(active.tournamentId);
        for (Entry e : entries.values())
        {
            e.registered = mine.contains(e.id);
            e.running = false;
        }
        if (activeRunning)
        {
            Entry e = entries.get(active.tournamentId);
            if (e == null && active.gearSet != null)
            {
                e = new Entry(active.tournamentId);
                entries.put(e.id, e);
            }
            if (e != null)
            {
                e.running = true;
                e.registered = true;
                if (active.gearSet != null) e.set = active.gearSet;
                if (active.location != null) e.location = active.location;
                if (active.gearDeadline > 0) e.checkUntil = active.gearDeadline;
            }
        }
        publish();
    }

    @Override
    public void onStandings(TourneyBoard standings)
    {
        if (standings == null) return;
        Entry e = entries.get(standings.tournamentId);
        if (e != null && standings.gearDeadline > 0)
        {
            e.checkUntil = standings.gearDeadline;
            publish();
        }
    }

    @Override
    public void onMatchAssigned(MatchSeries series)
    {
        if (series == null) return;
        Entry e = entries.get(series.tournamentId);
        if (e == null) return;
        e.checkUntil = 0L;
        e.running = true;
        publish();
    }

    @Override
    public void onGearCheck(String tournamentId, int round, long untilEpochS)
    {
        if (tournamentId == null || tournamentId.isEmpty()) return;
        Entry e = entries.get(tournamentId);
        if (e == null)
        {
            e = new Entry(tournamentId);
            entries.put(tournamentId, e);
        }
        e.checkUntil = untilEpochS;
        e.checkRound = round;
        if (e.set == null) requestStatus.run();
        publish();
    }

    @Override
    public void onRemoved(String tournamentId, String status, String reason, int round)
    {
        forget(tournamentId);
    }

    @Override
    public void onCancelled(String tournamentId, String reason)
    {
        forget(tournamentId);
    }

    @Override
    public void onFinished(String tournamentId, JsonObject winners, List<StandingsRow> standings)
    {
        forget(tournamentId);
    }

    private Entry learn(Tourney t)
    {
        Entry e = entries.computeIfAbsent(t.tournamentId, Entry::new);
        if (t.gearSet != null) e.set = t.gearSet;
        if (t.location != null) e.location = t.location;
        if (t.startsAt > 0) e.startsAt = t.startsAt;
        return e;
    }

    private void forget(String tournamentId)
    {
        if (tournamentId == null || tournamentId.isEmpty()) return;
        if (entries.remove(tournamentId) != null) publish();
    }

    private void publish()
    {
        Entry best = null;
        for (Entry e : entries.values())
        {
            if (e.set == null || !e.registered) continue;
            if (best == null || better(e, best)) best = e;
        }
        GearEvent next = best == null ? null : new GearEvent(best.id, best.set, isArena(best.location), best.running, best.checkUntil,
            best.checkRound);
        GearEvent now = current;
        if (next == null ? now != null : !next.sameAs(now)) current = next;
    }

    private static boolean better(Entry a, Entry b)
    {
        if (a.running != b.running) return a.running;
        long sa = a.startsAt > 0 ? a.startsAt : Long.MAX_VALUE;
        long sb = b.startsAt > 0 ? b.startsAt : Long.MAX_VALUE;
        if (sa != sb) return sa < sb;
        return a.id.compareTo(b.id) < 0;
    }

    static boolean isArena(String location)
    {
        return location == null || location.trim().isEmpty() || location.toLowerCase(Locale.ROOT).contains("arena");
    }
}
