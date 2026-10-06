package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import com.pvp.leaderboard.service.socket.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;
import javax.swing.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

@Singleton
public class TourneySvc
{
    private final SocketMgr socket;
    private final SocketBus bus;
    private final CopyOnWriteArrayList<TournamentEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean started = false;
    /** The tournament whose standings the sub-tab is watching (re-subscribed on reconnect). */
    private volatile String subscribedTo;

    @Inject
    public TourneySvc(SocketMgr socket, SocketBus bus)
    {
        this.socket = socket;
        this.bus = bus;
    }

    public void addListener(TournamentEventListener listener)
    {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(TournamentEventListener listener)
    {
        listeners.remove(listener);
    }

    public synchronized void start()
    {
        if (started) return;
        started = true;
        bus.register("tournament/list_response", this::handleList);
        bus.register("tournament/registered", this::handleRegistered);
        bus.register("tournament/withdrawn", this::handleWithdrawn);
        bus.register("tournament/state", this::handleState);
        bus.register("tournament/standings", this::handleStandings);
        bus.register("tournament/match_assigned", this::handleMatchAssigned);
        bus.register("tournament/opponent_highlight", this::handleHighlight);
        bus.register("tournament/opponent_highlight_clear", this::handleHighlightClear);
        bus.register("tournament/bye", this::handleBye);
        bus.register("tournament/round_end_check", this::handleRoundEndCheck);
        bus.register("tournament/removed", this::handleRemoved);
        bus.register("tournament/cancelled", this::handleCancelled);
        bus.register("tournament/finished", this::handleFinished);
        bus.register("tournament/problem_ack", d -> fire(TournamentEventListener::onProblemAck));
        bus.register("tournament/gear_check", this::handleGearCheck);
        bus.register("tournament/gear_ack", this::handleGearAck);
        bus.register("error/tournament", this::handleError);
        socket.addResyncListener(this::onReconnect);
    }

    private void onReconnect()
    {
        status();
        String sub = subscribedTo;
        if (sub != null) subscribe(sub);
        // Set 7: the sub-tab re-asks for the list it could not send while
        // the socket was down (SocketMgr.send drops without a socket).
        fire(TournamentEventListener::onConnected);
    }

    /** {@code true} while the socket is open (set 7): the sub-tab waits
     *  with "Connecting…" instead of sending frames the manager would drop,
     *  and re-asks from {@link TournamentEventListener#onConnected()}. */
    public boolean isConnected()
    {
        return socket.isConnected();
    }

    // ---- outbound ----
    private static JsonObject withId(String tournamentId)
    {
        var d = new JsonObject();
        d.addProperty("tournament_id", tournamentId);
        return d;
    }

    private static String tid(JsonObject d)
    {
        return optString(d, "tournament_id");
    }

    private static boolean blank(String s)
    {
        return s == null || s.trim().isEmpty();
    }

    public void list()
    {
        socket.send("tournament/list", new JsonObject());
    }

    private static JsonArray caps()
    {
        var a = new JsonArray();
        a.add("gear1");
        return a;
    }

    public void register(String tournamentId, String region)
    {
        JsonObject d = withId(tournamentId);
        if (!blank(region)) d.addProperty("region", region);
        d.add("caps", caps());
        socket.send("tournament/register", d);
    }

    public void withdraw(String tournamentId)
    {
        socket.send("tournament/withdraw", withId(tournamentId));
    }

    public void status()
    {
        var d = new JsonObject();
        d.add("caps", caps());
        socket.send("tournament/status", d);
    }

    public void subscribe(String tournamentId)
    {
        subscribedTo = tournamentId;
        socket.send("tournament/subscribe", withId(tournamentId));
    }

    public void unsubscribe(String tournamentId)
    {
        if (tournamentId.equals(subscribedTo)) subscribedTo = null;
        socket.send("tournament/unsubscribe", withId(tournamentId));
    }

    public void inCombat(String tournamentId, String seriesId)
    {
        JsonObject d = withId(tournamentId);
        d.addProperty("series_id", seriesId);
        socket.send("tournament/in_combat", d);
    }

    /** {@code tournament/report_problem}: the text trimmed and cut to the 280 characters the server accepts
     *  (socket_protocol.PROBLEM_TEXT_MAX); blank text sends nothing. */
    public void reportProblem(String tournamentId, String text)
    {
        if (blank(text)) return;
        String trimmed = text.trim();
        if (trimmed.length() > 280) trimmed = trimmed.substring(0, 280);
        JsonObject d = withId(tournamentId);
        d.addProperty("text", trimmed);
        socket.send("tournament/report_problem", d);
    }

    private static void putVerdict(JsonObject d, GearDiff diff, boolean ok)
    {
        d.addProperty("ok", ok);
        d.addProperty("build_ok", diff.buildOk);
        d.addProperty("missing", diff.missing.size());
        d.addProperty("extra", diff.extra.size());
        d.addProperty("spellbook_ok", diff.spellbookOk);
        JsonArray triples = diff.wireDiff();
        if (triples.size() > 0) d.add("diff", triples);
    }

    public void gearStatus(String tournamentId, String digest, GearDiff diff, String source, boolean ok)
    {
        if (blank(tournamentId) || blank(digest) || diff == null) return;
        JsonObject d = withId(tournamentId);
        d.addProperty("digest", digest);
        putVerdict(d, diff, ok);
        d.addProperty("source", blank(source) ? GearKit.SOURCE_CONTAINERS : source);
        socket.send("tournament/gear_status", d);
    }

    // ---- inbound ----
    private void handleList(JsonObject d)
    {
        List<Tourney> rows = Tourney.listOf(optArray(d, "tournaments"));
        fire(l -> l.onTournamentList(rows));
    }

    private void handleRegistered(JsonObject d)
    {
        // the meeting rides beside the event on this push
        JsonObject o = optObject(d, "tournament");
        JsonObject meeting = optObject(d, "meeting");
        if (o != null && meeting != null && Tourney.meetingWorld(o) == null) o.add("meeting", meeting);
        Tourney t = Tourney.fromJson(o);
        if (t == null) return;
        String status = optString(optObject(d, "registration"), "status", "registered");
        fire(l -> l.onRegistered(t, status));
    }

    private void handleWithdrawn(JsonObject d)
    {
        String tid = tid(d);
        if (tid.isEmpty()) return;
        fire(l -> l.onWithdrawn(tid));
    }

    private void handleState(JsonObject d)
    {
        List<Tourney> regs = Tourney.listOf(optArray(d, "registrations"));
        LiveTourney active = LiveTourney.fromJson(optObject(d, "active"));
        fire(l -> l.onTournamentState(regs, active));
    }

    private void handleStandings(JsonObject d)
    {
        TourneyBoard s = TourneyBoard.fromJson(d);
        if (s == null) return;
        fire(l -> l.onStandings(s));
    }

    private void handleMatchAssigned(JsonObject d)
    {
        String tid = tid(d);
        MatchSeries s = MatchSeries.fromJson(d, tid, optLong(d, "deadline_at", 0L));
        if (s == null || tid.isEmpty())
        {
            return;
        }
        fire(l -> l.onMatchAssigned(s));
    }

    private void handleHighlight(JsonObject d)
    {
        String name = optString(d, "opponent_name");
        if (name.isEmpty()) return;
        String tid = tid(d);
        List<String> listed = MatchSeries.namesOf(d, "opponent_names");
        List<String> names = listed.isEmpty() ? Collections.singletonList(name) : listed;
        fire(l -> l.onOpponentHighlight(tid, name, names));
    }

    private void handleHighlightClear(JsonObject d)
    {
        String tid = tid(d);
        String sid = optString(d, "series_id", null);
        fire(l -> l.onOpponentHighlightClear(tid, sid));
    }

    private void handleBye(JsonObject d)
    {
        String tid = tid(d);
        int round = optInt(d, "round", 0);
        fire(l -> l.onBye(tid, round));
    }

    private void handleRoundEndCheck(JsonObject d)
    {
        String tid = tid(d);
        String sid = optString(d, "series_id");
        if (tid.isEmpty() || sid.isEmpty()) return;
        int round = optInt(d, "round", 0);
        String opp = optString(d, "opponent_name", "your opponent");
        long respondBy = optLong(d, "respond_by", 0L);
        String message = Tourney.textOf(d, "message");
        fire(l -> l.onRoundEndCheck(tid, round, opp, respondBy, message));
    }

    private void handleRemoved(JsonObject d)
    {
        String tid = tid(d);
        String status = optString(d, "status", "removed");
        String reason = optString(d, "reason");
        int round = optInt(d, "round", 0);
        fire(l -> l.onRemoved(tid, status, reason, round));
    }

    private void handleCancelled(JsonObject d)
    {
        String tid = tid(d);
        String reason = optString(d, "reason");
        fire(l -> l.onCancelled(tid, reason));
    }

    private void handleFinished(JsonObject d)
    {
        String tid = tid(d);
        JsonObject winners = optObject(d, "winners");
        List<StandingsRow> rows = StandingsRow.fromArray(optArray(d, "standings"), StandingsRow.tierLabels(d));
        fire(l -> l.onFinished(tid, winners, rows));
    }

    private void handleGearCheck(JsonObject d)
    {
        String tid = tid(d);
        if (tid.isEmpty()) return;
        int round = optInt(d, "round", 0);
        long until = optLong(d, "until", 0L);
        fire(l -> l.onGearCheck(tid, round, until));
    }

    private void handleGearAck(JsonObject d)
    {
        String tid = tid(d);
        if (tid.isEmpty()) return;
        boolean ok = optBool(d, "ok", false);
        fire(l -> l.onGearAck(tid, ok));
    }

    private void handleError(JsonObject d)
    {
        String code = optString(d, "code", "UNKNOWN");
        String message = optString(d, "message");
        String cmd = optString(d, "cmd");
        fire(l -> l.onTournamentError(code, message, cmd));
    }

    private void fire(Consumer<TournamentEventListener> fn)
    {
        SwingUtilities.invokeLater(() ->
        {
            for (TournamentEventListener l : listeners)
            {
                try
                {
                    fn.accept(l);
                }
                catch (Exception e)
                {
                }
            }
        });
    }
}
