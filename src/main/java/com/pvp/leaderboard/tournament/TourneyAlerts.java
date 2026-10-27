package com.pvp.leaderboard.tournament;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.*;

/**
 * The tournament box's pairings and start reminders, each also a chat line: each round's pairing, and 15 and 5 minutes
 * before each listed event starts, whether the player is registered and the world to meet on. While registration is open
 * the not-registered box offers Register; the server's answer to a press turns that box into the registered text, a
 * refusal closes it. The event list is asked once per login.
 */
@RequiredArgsConstructor
public final class TourneyAlerts implements TournamentEventListener
{
    /** Shows a box: a pairing or a reminder, its first line the title. */
    public interface Boxes
    {
        void show(boolean pairing, String... lines);

        /** A reminder with Register for {@code tournamentId}; a press runs {@code register}. */
        default void offer(String tournamentId, Runnable register, String... lines)
        {
            show(false, lines);
        }

        /** The box whose Register was pressed for {@code tournamentId} shows {@code lines}; with none it closes. */
        default void answer(String tournamentId, String... lines)
        {
        }
    }

    static final String JOIN = "Click the white skull on the right of RuneLite, then click Events and register if you wish to join";
    static final String JOIN_HERE = "Click Register to join.";
    private static final int[] MARKS = {15, 5};

    private final TourneySvc service;
    private final Boxes boxes;
    private final Consumer<String> chat;
    private final LongSupplier nowMs;
    /** Signs the player up for an event, as the Events tab's Register does. */
    private final Consumer<Tourney> register;
    private volatile List<Tourney> events = Collections.emptyList(), regs = Collections.emptyList();
    /** The list was asked since the last login. */
    private volatile boolean listed;
    /** The series of the last pairing shown. */
    private String series;
    /** The reminders given or never due, "id minutes". */
    private final Set<String> done = ConcurrentHashMap.newKeySet();
    /** The event a box's Register was pressed for and that box's mark, until the server answers. */
    private volatile Tourney pressed;
    private volatile int pressedMark;

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

    /** The server registered the player: the box pressed for that event says so. */
    @Override
    public void onRegistered(Tourney tournament, String regStatus)
    {
        Tourney t = pressed;
        if (t == null || !t.tournamentId.equals(tournament.tournamentId)) return;
        pressed = null;
        boxes.answer(t.tournamentId, head(pressedMark) + "registered", meet(t));
    }

    /** The server refused the pressed registration: its box closes. */
    @Override
    public void onTournamentError(String code, String message, String cmd)
    {
        Tourney t = pressed;
        if (t == null || !"tournament/register".equals(cmd)) return;
        pressed = null;
        boxes.answer(t.tournamentId);
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
                if ("registered".equals(Tourney.statusIn(t, regs))) alert(false, head(m) + "registered", meet(t));
                else if (t.isRegOpen()) offer(t, m);
                else alert(false, head(m) + "NOT registered", JOIN);
            }
        }
    }

    /** "Tournament starts in 15 minutes, you are ". */
    private static String head(int m)
    {
        return "Tournament starts in " + m + " minutes, you are ";
    }

    /** The world everyone meets on before the start: 578, or 345 for a DMM event. */
    static String meet(Tourney t)
    {
        return "Meet world " + ("dmm".equals(t.category) ? 345 : 578) + " for the tournament";
    }

    /** The box and its chat line. */
    private void alert(boolean pairing, String... lines)
    {
        String text = String.join(". ", lines);
        boxes.show(pairing, lines);
        chat.accept(text);
    }

    /** The not-registered box with Register, and its chat line with the way to join. */
    private void offer(Tourney t, int m)
    {
        String title = head(m) + "NOT registered";
        boxes.offer(t.tournamentId, () -> press(t, m), title, JOIN_HERE);
        chat.accept(title + ". " + JOIN);
    }

    /** A box's Register: that event's sign-up, its answer awaited. */
    private void press(Tourney t, int m)
    {
        pressedMark = m;
        pressed = t;
        register.accept(t);
    }
}
