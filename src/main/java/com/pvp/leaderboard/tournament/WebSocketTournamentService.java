package com.pvp.leaderboard.tournament;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.service.socket.SocketEventBus;
import com.pvp.leaderboard.service.socket.WebSocketManager;
import com.pvp.leaderboard.util.JsonLenient;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link TournamentService} over the plugin's WebSocket (Plan 10 Part C /
 * F.3, 2026-09-21). Encodes the nine {@code tournament/*} cmds
 * (WEBSOCKET_PROTOCOL.md § 6.3) and turns the fourteen server pushes +
 * {@code error/tournament} into EDT-delivered
 * {@link TournamentEventListener} callbacks, fanned out to every
 * registered listener (the sub-tab, the {@link TournamentSessionTracker},
 * the plugin's bucket auto-switch hook).
 *
 * <p>Reconnect: the server keeps the registration / series rows, so a
 * socket re-open re-asks {@code tournament/status} and re-issues the
 * last {@code tournament/subscribe} (viewer rows are per connection).
 *
 * <p>No UUIDs anywhere on this path (AS-97).
 */
@Slf4j
@Singleton
public class WebSocketTournamentService implements TournamentService
{
    private final WebSocketManager socket;
    private final SocketEventBus bus;
    private final CopyOnWriteArrayList<TournamentEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean started = false;
    /** The tournament whose standings the sub-tab is watching (re-subscribed on reconnect). */
    private volatile String subscribedTournamentId;

    @Inject
    public WebSocketTournamentService(WebSocketManager socket, SocketEventBus bus)
    {
        this.socket = socket;
        this.bus = bus;
    }

    @Override
    public void addListener(TournamentEventListener listener)
    {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(TournamentEventListener listener)
    {
        listeners.remove(listener);
    }

    @Override
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
        bus.register("tournament/problem_ack", this::handleProblemAck);
        bus.register("error/tournament", this::handleError);
        socket.addConnectListener(this::onReconnect);
    }

    private void onReconnect()
    {
        status();
        String sub = subscribedTournamentId;
        if (sub != null) subscribe(sub);
        // Set 7: the sub-tab re-asks for the list it could not send while
        // the socket was down (WebSocketManager.send drops without a socket).
        fire(TournamentEventListener::onSocketConnected);
    }

    @Override
    public boolean isConnected()
    {
        return socket.isConnected();
    }

    // ---- outbound ----
    private static JsonObject withId(String tournamentId)
    {
        JsonObject d = new JsonObject();
        d.addProperty("tournament_id", tournamentId);
        return d;
    }

    private static boolean blank(String s)
    {
        return s == null || s.trim().isEmpty();
    }

    @Override
    public void list()
    {
        socket.send("tournament/list", new JsonObject());
    }

    @Override
    public void register(String tournamentId, String region)
    {
        if (blank(tournamentId)) return;
        JsonObject d = withId(tournamentId);
        if (!blank(region)) d.addProperty("region", region);
        socket.send("tournament/register", d);
    }

    @Override
    public void withdraw(String tournamentId)
    {
        if (blank(tournamentId)) return;
        socket.send("tournament/withdraw", withId(tournamentId));
    }

    @Override
    public void status()
    {
        socket.send("tournament/status", new JsonObject());
    }

    @Override
    public void subscribe(String tournamentId)
    {
        if (blank(tournamentId)) return;
        subscribedTournamentId = tournamentId;
        socket.send("tournament/subscribe", withId(tournamentId));
    }

    @Override
    public void unsubscribe(String tournamentId)
    {
        if (blank(tournamentId)) return;
        if (tournamentId.equals(subscribedTournamentId)) subscribedTournamentId = null;
        socket.send("tournament/unsubscribe", withId(tournamentId));
    }

    @Override
    public void inCombat(String tournamentId, String seriesId)
    {
        if (blank(tournamentId) || blank(seriesId)) return;
        JsonObject d = withId(tournamentId);
        d.addProperty("series_id", seriesId);
        socket.send("tournament/in_combat", d);
    }

    @Override
    public void roundEndReply(String tournamentId, String seriesId)
    {
        if (blank(tournamentId) || blank(seriesId)) return;
        JsonObject d = withId(tournamentId);
        d.addProperty("series_id", seriesId);
        socket.send("tournament/round_end_reply", d);
    }

    @Override
    public void reportProblem(String tournamentId, String text)
    {
        if (blank(tournamentId) || blank(text)) return;
        String trimmed = text.trim();
        if (trimmed.length() > REPORT_MAX_CHARS) trimmed = trimmed.substring(0, REPORT_MAX_CHARS);
        JsonObject d = withId(tournamentId);
        d.addProperty("text", trimmed);
        socket.send("tournament/report_problem", d);
    }

    // ---- inbound ----
    private static List<TournamentSummary> summaries(JsonArray arr)
    {
        List<TournamentSummary> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement e : arr)
        {
            if (e == null || !e.isJsonObject()) continue;
            TournamentSummary t = TournamentSummary.fromJson(e.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    private void handleList(JsonObject d)
    {
        List<TournamentSummary> rows = summaries(JsonLenient.optArray(d, "tournaments"));
        long now = JsonLenient.optLong(d, "now", System.currentTimeMillis() / 1000L);
        fire(l -> l.onTournamentList(rows, now));
    }

    private void handleRegistered(JsonObject d)
    {
        TournamentSummary t = TournamentSummary.fromJson(JsonLenient.optObject(d, "tournament"));
        if (t == null) return;
        JsonObject reg = JsonLenient.optObject(d, "registration");
        String status = JsonLenient.optString(reg, "status", "registered");
        fire(l -> l.onRegistered(t, status));
    }

    private void handleWithdrawn(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        if (tid.isEmpty()) return;
        String status = JsonLenient.optString(d, "status", "withdrawn");
        fire(l -> l.onWithdrawn(tid, status));
    }

    private void handleState(JsonObject d)
    {
        List<TournamentSummary> regs = summaries(JsonLenient.optArray(d, "registrations"));
        TournamentActive active = TournamentActive.fromJson(JsonLenient.optObject(d, "active"));
        fire(l -> l.onTournamentState(regs, active));
    }

    private void handleStandings(JsonObject d)
    {
        TournamentStandings s = TournamentStandings.fromJson(d);
        if (s == null) return;
        fire(l -> l.onStandings(s));
    }

    private void handleMatchAssigned(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        TournamentSeries s = TournamentSeries.fromJson(d, tid, JsonLenient.optLong(d, "deadline_at", 0L));
        if (s == null || tid.isEmpty())
        {
            log.debug("tournament/match_assigned without ids dropped");
            return;
        }
        fire(l -> l.onMatchAssigned(s));
    }

    private void handleHighlight(JsonObject d)
    {
        String name = JsonLenient.optString(d, "opponent_name", "");
        if (name.isEmpty()) return;
        String tid = JsonLenient.optString(d, "tournament_id", "");
        String acct = JsonLenient.optString(d, "opponent_acct_sha", null);
        long until = JsonLenient.optLong(d, "until", 0L);
        fire(l -> l.onOpponentHighlight(tid, name, acct, until));
    }

    private void handleHighlightClear(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        String sid = JsonLenient.optString(d, "series_id", null);
        fire(l -> l.onOpponentHighlightClear(tid, sid));
    }

    private void handleBye(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        int round = JsonLenient.optInt(d, "round", 0);
        fire(l -> l.onBye(tid, round));
    }

    private void handleRoundEndCheck(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        String sid = JsonLenient.optString(d, "series_id", "");
        if (tid.isEmpty() || sid.isEmpty()) return;
        int round = JsonLenient.optInt(d, "round", 0);
        String opp = JsonLenient.optString(d, "opponent_name", "your opponent");
        long respondBy = JsonLenient.optLong(d, "respond_by", 0L);
        fire(l -> l.onRoundEndCheck(tid, round, sid, opp, respondBy));
    }

    private void handleRemoved(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        String status = JsonLenient.optString(d, "status", "removed");
        String reason = JsonLenient.optString(d, "reason", "");
        int round = JsonLenient.optInt(d, "round", 0);
        fire(l -> l.onRemoved(tid, status, reason, round));
    }

    private void handleCancelled(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        String reason = JsonLenient.optString(d, "reason", "");
        fire(l -> l.onCancelled(tid, reason));
    }

    private void handleFinished(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        JsonObject winners = JsonLenient.optObject(d, "winners");
        List<StandingsRow> rows = StandingsRow.fromArray(JsonLenient.optArray(d, "standings"));
        fire(l -> l.onFinished(tid, winners, rows));
    }

    private void handleProblemAck(JsonObject d)
    {
        String tid = JsonLenient.optString(d, "tournament_id", "");
        fire(l -> l.onProblemAck(tid));
    }

    private void handleError(JsonObject d)
    {
        String code = JsonLenient.optString(d, "code", "UNKNOWN");
        String message = JsonLenient.optString(d, "message", "");
        String cmd = JsonLenient.optString(d, "cmd", "");
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
                    log.debug("TournamentEventListener threw", e);
                }
            }
        });
    }
}
