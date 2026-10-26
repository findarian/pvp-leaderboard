package com.pvp.leaderboard.tournament;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.*;

/**
 * The tournament box's pairings and start reminders, each also a chat line: each round's pairing, and 15 and 5 minutes
 * before each listed event starts, whether the player is registered. The event list is asked once per login.
 */
@RequiredArgsConstructor
public final class TourneyAlerts implements TournamentEventListener
{
    /** Shows a box: a pairing or a reminder, its first line the title. */
    public interface Boxes
    {
        void show(boolean pairing, String... lines);
    }

    static final String JOIN = "Click the white skull on the right of RuneLite, then click Events and register if you wish to join";
    private static final int[] MARKS = {15, 5};

    private final TourneySvc service;
    private final Boxes boxes;
    private final Consumer<String> chat;
    private final LongSupplier nowMs;
    private volatile List<Tourney> events = Collections.emptyList(), regs = Collections.emptyList();
    /** The list was asked since the last login. */
    private volatile boolean listed;
    /** The series of the last pairing shown. */
    private String series;
    /** The reminders given or never due, "id minutes". */
    private final Set<String> done = ConcurrentHashMap.newKeySet();

    @Override
    public void onConnected()
    {
        if (listed) return;
        listed = true;
        service.list();
    }

    /** The player logged out: the next login asks for the list again. */
    public void loggedOut()
    {
        listed = false;
    }

    @Override
    public void onTournamentList(List<Tourney> tournaments)
    {
        events = tournaments;
    }

    @Override
    public void onTournamentState(List<Tourney> registrations, LiveTourney state)
    {
        regs = registrations;
    }

    @Override
    public void onCancelled(String tournamentId, String reason)
    {
        for (int m : MARKS) done.add(tournamentId + " " + m);
    }

    @Override
    public void onMatchAssigned(MatchSeries s)
    {
        if (s.seriesId.equals(series)) return;
        series = s.seriesId;
        alert(true, "Round " + s.round, "Opponent: " + s.opponentName, "World " + s.worldLabel().replace("W", ""));
    }

    /** Each game tick: the reminders due, within a minute of their mark. */
    public void tick()
    {
        long now = nowMs.getAsLong();
        for (Tourney t : events)
        {
            for (int m : MARKS)
            {
                long due = t.startsAt * 1000L - m * 60_000L;
                if (t.startsAt <= 0 || t.isRunning() || now < due || now >= due + 60_000L || !done.add(t.tournamentId + " " + m)) continue;
                String head = "Tournament starts in " + m + " minutes, you are ";
                if ("registered".equals(Tourney.statusIn(t, regs))) alert(false, head + "registered");
                else alert(false, head + "NOT registered", JOIN);
            }
        }
    }

    /** The box and its chat line. */
    private void alert(boolean pairing, String... lines)
    {
        String text = String.join(". ", lines);
        boxes.show(pairing, lines);
        chat.accept(text);
    }
}
