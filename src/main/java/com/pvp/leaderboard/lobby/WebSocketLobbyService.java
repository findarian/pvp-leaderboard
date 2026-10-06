package com.pvp.leaderboard.lobby;

import com.google.gson.*;
import com.pvp.leaderboard.service.*;
import com.pvp.leaderboard.service.socket.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.inject.*;
import javax.swing.*;
import lombok.extern.slf4j.*;
import static com.pvp.leaderboard.util.JsonLenient.*;
import static javax.swing.SwingUtilities.*;

/**
 * Production {@link LobbyService} that adapts the wire protocol to the
 * panel's domain types.
 *
 * <p>Two key concerns this class owns:
 * <ol>
 *   <li><b>Wire ↔ domain marshalling</b>: server pushes are flat (e.g.
 *   {@code lobby/invite_received} carries
 *   {@code from_player_id + from_name + sender_styles + ...}, not a
 *   nested {@code sender} object). This service holds an in-memory
 *   roster cache keyed by canonical {@code player_id} and reconstructs
 *   {@link LobbyMember} instances for the panel's nested
 *   {@link IncomingInvite#sender} / {@link FightSession#opponent} /
 *   {@link MatchInfo#opponent} fields. If a flat payload references a
 *   player id not in the cache (rare — only if the push arrives before
 *   the {@code lobby/roster} that introduced the member), the service
 *   falls back to a synthetic {@link LobbyMember} built from whatever
 *   fields the push carried.</li>
 *
 *   <li><b>Reconnect re-join</b>: caches the last {@link #joinLobby}
 *   args and re-sends them via {@link SocketMgr#addConnectListener}
 *   on every socket open so server-side state rebuilds without the
 *   panel knowing about reconnects.</li>
 * </ol>
 *
 * <p><b>Threading</b>: server pushes arrive on OkHttp's dispatcher
 * thread via {@link SocketBus}; this service marshals every
 * {@link LobbyEventListener} callback through
 * {@link SwingUtilities#invokeLater} per the {@link LobbyService}
 * contract. The injected {@link SocketMgr} is thread-safe and
 * {@link #send(String, JsonObject)} just hands off to its internal
 * {@code WebSocket.send(String)} which is thread-safe.
 *
 * <p><b>Stale roster</b>: a {@code lobby/roster} with {@code stale: true}
 * or without {@code members} re-issues {@code lobby/join} after a
 * growing, jittered delay ({@link #STALE_REJOIN_BASE_MS} doubling up to
 * {@link #STALE_REJOIN_MAX_MS}), one at a time.
 */
@Slf4j
@Singleton
public class WebSocketLobbyService implements LobbyService
{
    private final SocketMgr socket;
    private final SocketBus bus;

    /** Buffered {@code fight_session_id} awaiting a healthy socket so its
     *  {@code lobby/confirm} can finally land. Populated by
     *  {@link #confirmFight()} when the socket is closed at send time
     *  (e.g. the user clicked Confirm Fight during the 1-3 s window
     *  between {@code onClose} and the next {@code onOpen}); flushed
     *  by {@link #flushConfirm()} from the connect listener and
     *  cleared by {@link #handleFightProposed}, {@link #handleMatchFound},
     *  and {@link #handleSessionExpired} when the session it refers to
     *  is no longer current. {@code volatile} since
     *  {@code SocketMgr} invokes the connect listener on the
     *  OkHttp dispatcher thread while {@code confirmFight()} is called
     *  from the Swing EDT. */
    private volatile String heldConfirm;

    private volatile LobbyEventListener listener;
    private volatile boolean started = false;

    @Inject
    public WebSocketLobbyService(SocketMgr socket, SocketBus bus)
    {
        this.socket = socket;
        this.bus = bus;
    }

    static final int QUEUE_MAX = 16;
    /** {@code queue/matched} opponent by {@code fight_session_id}. */
    private final Map<String, LobbyMember> queueOpps = new ConcurrentHashMap<>();

    @Override
    public void setListener(LobbyEventListener listener)
    {
        this.listener = listener;
    }

    @Override
    public synchronized void start()
    {
        if (started) return;
        started = true;
        bus.register("lobby/block_list_snapshot",     this::handleBlockListSnapshot);
        bus.register("lobby/block_added",             this::handleBlockAdded);
        bus.register("lobby/block_removed",           this::handleBlockRemoved);
        bus.register("lobby/fight_proposed",          this::handleFightProposed);
        bus.register("lobby/fight_confirmed_by_peer", this::handleFightConfirmedByPeer);
        bus.register("lobby/match_found",             this::handleMatchFound);
        bus.register("lobby/session_expired",         this::handleSessionExpired);
        bus.register("error/lobby",                   this::handleError);
        bus.register("queue/matched",                 this::handleQueueMatched);
        socket.addConnectListener(this::flushConfirm);
    }

    @Override
    public synchronized void stop()
    {
    }

    @Override
    public void confirmFight()
    {
        // The panel works against a "one fight at a time" model — there's
        // no concept of overlapping confirm windows in the UI — so the
        // service caches the most recently proposed session id and uses
        // it here. The server-side state machine enforces the same rule:
        // joining a fight removes you from the lobby, so overlapping
        // sessions for one user are impossible.
        String sid = liveFightId;
        if (sid == null)
        {
            return;
        }
        var d = new JsonObject();
        d.addProperty("fight_session_id", sid);
        boolean sent = socket.send("lobby/confirm", d);
        if (sent)
        {
            // Successful send wins over any stale buffer entry — if the
            // user double-clicked Confirm during a brief reconnect,
            // only the surviving send carries forward.
            heldConfirm = null;
        }
        else
        {
            // Socket was closed at send time (the SocketMgr
            // backoff ladder runs 1-2-4-8 s; if Confirm is clicked
            // during that window the frame is silently dropped today,
            // which surfaced as the "30-second confirm window expired"
            // QA report). Buffer the session id so the connect
            // listener can flush it on the next onOpen — bounded by
            // the server's confirm window via
            // handleSessionExpired clearing the buffer.
            heldConfirm = sid;
        }
    }

    /** Connect-listener callback: drains any {@link
     *  #heldConfirm} buffered while the socket was
     *  closed. Runs on OkHttp's dispatcher thread alongside
     *  {@link #replayJoinOnReconnect()}; safe because the buffer is
     *  volatile and the failure mode (re-buffer + retry on next open)
     *  is idempotent. */
    private void flushConfirm()
    {
        String sid = heldConfirm;
        if (sid == null) return;
        // Drop the buffer first to avoid re-entry if the send below
        // races a fresh confirmFight() call (e.g. user clicked Confirm
        // again during the flush). The buffer is repopulated below if
        // the send fails — single-writer semantics.
        heldConfirm = null;
        var d = new JsonObject();
        d.addProperty("fight_session_id", sid);
        if (socket.send("lobby/confirm", d))
        {
        }
        else
        {
            // The socket closed again between onOpen firing and our
            // send. Re-buffer so the next open retries. Bounded by
            // the server's confirm window — handleSessionExpired
            // clears the buffer once the server gives up.
            heldConfirm = sid;
        }
    }

    @Override
    public void blockById(String playerId)
    {
        if (playerId == null) return;
        String trimmed = playerId.trim();
        if (trimmed.isEmpty()) return;
        var d = new JsonObject();
        d.addProperty("blocked_player_id", trimmed);
        socket.send("lobby/block", d);
    }

    @Override
    public void unblockById(String playerId)
    {
        if (playerId == null) return;
        String trimmed = playerId.trim();
        if (trimmed.isEmpty()) return;
        var d = new JsonObject();
        d.addProperty("blocked_player_id", trimmed);
        socket.send("lobby/unblock", d);
    }

    /** Pass-through to {@link SocketMgr#isConnected()}. The
     *  panel's reconnect banner reads this to decide whether to show
     *  the countdown — the wire state is the single source of truth
     *  for "are we online", not anything cached in this service. */
    @Override
    public boolean isConnected()
    {
        return socket.isConnected();
    }

    /** Pass-through to {@link SocketMgr#getRetryAtMs()}.
     *  Drives the 1Hz countdown on the panel's reconnect banner.
     *  Returning {@code 0} suppresses the banner; any non-zero value
     *  is interpreted as "retry scheduled at that wall-clock time". */
    @Override
    public long getRetryAtMs()
    {
        return socket.getRetryAtMs();
    }

    // ---------------------------------------------------------------
    // Inbound: server -> panel (all dispatched on EDT)
    // ---------------------------------------------------------------

    /** Most recently proposed fight session id; used by
     *  {@link #confirmFight()} since the panel's API doesn't take an
     *  explicit id. Set on {@code lobby/fight_proposed}, cleared on
     *  {@code lobby/match_found} / {@code lobby/session_expired}. */
    private volatile String liveFightId;

    private void handleBlockListSnapshot(JsonObject data)
    {
        // Backend pushes data={"blocked": [{player_id, name}, ...]} per
        // get_block_list (backend/core/lobby.py). Older builds emitted a
        // flat string array under "blocked_player_ids" — accept either
        // shape so a partial deploy doesn't drop blocks silently.
        Set<String> ids = new HashSet<>();
        for (JsonElement el : optArray(data, "blocked"))
        {
            if (el == null || el.isJsonNull()) continue;
            String pid = el.isJsonObject() ? optString(el.getAsJsonObject(), "player_id") : str(el);
            if (pid != null && !pid.isEmpty()) ids.add(pid);
        }
        if (ids.isEmpty())
        {
            ids.addAll(parseStrings(data, "blocked_player_ids"));
        }
        LobbyEventListener l = listener;
        if (l == null) return;
        Set<String> snapshot = Collections.unmodifiableSet(ids);
        invokeLater(() -> l.onBlockListSnapshot(snapshot));
    }

    private void handleBlockAdded(JsonObject data)
    {
        String pid = optString(data, "blocked_player_id");
        if (pid.isEmpty()) return;
        LobbyEventListener l = listener;
        if (l == null) return;
        invokeLater(() -> l.onBlockAdded(pid));
    }

    private void handleBlockRemoved(JsonObject data)
    {
        String pid = optString(data, "blocked_player_id");
        if (pid.isEmpty()) return;
        LobbyEventListener l = listener;
        if (l == null) return;
        invokeLater(() -> l.onBlockRemoved(pid));
    }

    private void handleFightProposed(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        // A new fight is being proposed — any confirm buffered against
        // a *previous* (now-defunct) session is stale. Drop it so a
        // belated flush doesn't try to confirm a session the server
        // has already torn down.
        if (heldConfirm != null
            && !heldConfirm.equals(sid))
        {
            heldConfirm = null;
        }
        liveFightId = sid;
        Style style = parseStyle(optString(data, "style"));
        BuildType build = parseBuild(optString(data, "build"));
        if (style == null || build == null) return;
        // Inbound wire-protocol boundary — see handleInviteReceived.
        String location = PlaceCodec.toDisplay(optString(data, "location"));
        // Canonical wire field per BACKEND_HANDOFF_LOBBY.md §FightSession
        // is `confirm_expires_at_epoch_ms`. The plugin previously read
        // the wrong key (`expires_at_epoch_ms`, the IncomingInvite
        // field), which silently defaulted the deadline to 0L and made
        // the panel's 1-Hz fight tick exit the Confirm-Fight card on
        // its very first tick — the user saw the popup vanish before
        // they could click anything (QA bug 2026-05-25). Read the
        // canonical key first; fall back to the legacy name so a
        // partial backend deploy doesn't strand every fight; and if
        // BOTH are missing/zero, synthesise now + the confirm window
        // keyed to the local clock so the user still gets a usable popup.
        //
        // NOTE: the panel no longer trusts this absolute deadline for
        // the confirm countdown — MatchmakingLobbyPanel.LocalFightState
        // counts the confirm window off a monotonic clock so a wrong /
        // skewed client wall clock can't cut the window short. This
        // value is retained only as the server's advisory deadline.
        long windowMs = confirmMs(data);
        long expiresAt = optLong(data, "confirm_expires_at_epoch_ms", 0);
        if (expiresAt <= 0L) expiresAt = optLong(data, "expires_at_epoch_ms", 0);
        if (expiresAt <= 0L)
        {
            long synth = System.currentTimeMillis() + windowMs;
            log.warn("WebSocketLobbyService.handleFightProposed: missing confirm_expires_at_epoch_ms (and legacy expires_at_epoch_ms) on lobby/fight_proposed sid={} - synthesising local-clock deadline now+{}ms={} so the Confirm-Fight card doesn't insta-exit",
                sid, windowMs, synth);
            expiresAt = synth;
        }
        LobbyMember opponent = resolveOpponent(data);
        if (opponent == null) return;
        var session = new FightSession(sid, opponent, style, build, location, expiresAt, windowMs);
        LobbyEventListener l = listener;
        if (l == null) return;
        invokeLater(() -> l.onFightProposed(session));
    }

    private void handleFightConfirmedByPeer(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        LobbyEventListener l = listener;
        if (l == null) return;
        invokeLater(() -> l.onFightConfirmedByPeer(sid));
    }

    private void handleMatchFound(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        Style style = parseStyle(optString(data, "style"));
        BuildType build = parseBuild(optString(data, "build"));
        if (style == null || build == null) return;
        // Inbound wire-protocol boundary — see handleInviteReceived.
        String location = PlaceCodec.toDisplay(optString(data, "location"));
        String world = optString(data, "world");
        String meetingPlace = optString(data, "meeting_place");
        LobbyMember opponent = resolveOpponent(data);
        queueOpps.remove(sid);
        if (opponent == null) return;
        // Server has already deleted both LobbyMembers rows + the
        // session at this point — the lobby state is consumed, clear
        // our local session-id cache. Also drop any buffered confirm
        // for this session: the match landed, so a late flush would
        // be a no-op at best, an error/lobby at worst.
        liveFightId = null;
        if (sid.equals(heldConfirm)) heldConfirm = null;
        var match = new MatchInfo(sid, opponent, style, build, location, world, meetingPlace);
        LobbyEventListener l = listener;
        if (l == null) return;
        invokeLater(() -> l.onMatchFound(match));
    }

    private void handleSessionExpired(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        if (sid.equals(liveFightId)) liveFightId = null;
        queueOpps.remove(sid);
        // The confirm window elapsed — any buffered confirm against
        // this session is moot, drop it so a future reconnect doesn't
        // ship a confirm for a session that no longer exists.
        if (sid.equals(heldConfirm)) heldConfirm = null;
        LobbyEventListener l = listener;
        if (l == null) return;
        invokeLater(() -> l.onFightSessionExpired(sid));
    }

    private void handleError(JsonObject data)
    {
        String code = optString(data, "code");
        String message = optString(data, "message");
        // Surfaced at WARN because error/lobby pushes are exceptional
        // by definition — the user is going to ask why their fight
        // button didn't work, and the answer is almost always in this
        // single log line (RANK_OUT_OF_RANGE, BLOCKED, SMURF_GUARD…).
        log.warn("WebSocketLobbyService: error/lobby code={} message={}", code, message);
        LobbyEventListener l = listener;
        if (l == null) return;
        invokeLater(() -> l.onError(code, message));
    }

    // ---------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------

    /** Returns the opponent {@link LobbyMember} for a
     *  {@code fight_proposed} / {@code match_found} push: the
     *  {@code queue/matched} opponent of that session when one arrived,
     *  else the side whose {@code player_id} (or, without ids, name) is
     *  not {@link SocketMgr#getActiveName()}, its roster row when
     *  cached. When neither side is recognised: the roster row of either
     *  side, else player_a's id + name on the wire; {@code null} without
     *  ids. */
    private LobbyMember resolveOpponent(JsonObject data)
    {
        LobbyMember queued = queueOpps.get(optString(data, "fight_session_id"));
        if (queued != null) return queued;
        String a = optString(data, "player_a_player_id");
        String b = optString(data, "player_b_player_id");
        String aName = optString(data, "player_a_name");
        String bName = optString(data, "player_b_name");
        String self = NameUtils.canonicalKey(socket.getActiveName());
        if (a.isEmpty() || b.isEmpty())
        {
            if (self.isEmpty() || aName.isEmpty() || bName.isEmpty()) return null;
            String aKey = NameUtils.canonicalKey(aName);
            String bKey = NameUtils.canonicalKey(bName);
            if (self.equals(aKey) && !self.equals(bKey)) return minimalMember(bKey, bName);
            if (self.equals(bKey) && !self.equals(aKey)) return minimalMember(aKey, aName);
            return null;
        }
        if (!self.isEmpty())
        {
            boolean selfIsA = self.equals(NameUtils.canonicalKey(a));
            boolean selfIsB = self.equals(NameUtils.canonicalKey(b));
            if (selfIsA != selfIsB)
            {
                return selfIsA ? minimalMember(b, bName) : minimalMember(a, aName);
            }
        }
        return minimalMember(a, aName);
    }

    private static LobbyMember minimalMember(String playerId, String name)
    {
        return new LobbyMember(playerId, name,
            EnumSet.noneOf(Style.class), EnumSet.noneOf(BuildType.class),
            -1, "");
    }

    /** {@code queue/matched}: remembers the session's opponent for
     *  {@link #resolveOpponent}. */
    private void handleQueueMatched(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        String name = optString(data, "opponent_name");
        String playerId = optString(data, "opponent_player_id");
        if (sid.isEmpty() || (name.isEmpty() && playerId.isEmpty())) return;
        if (playerId.isEmpty()) playerId = NameUtils.canonicalKey(name);
        if (name.isEmpty()) name = playerId;
        Style style = parseStyle(optString(data, "style"));
        BuildType build = parseBuild(optString(data, "opponent_build"));
        var opponent = new LobbyMember(playerId, name,
            style == null ? EnumSet.noneOf(Style.class) : EnumSet.of(style),
            build == null ? EnumSet.noneOf(BuildType.class) : EnumSet.of(build),
            -1, optString(data, "opponent_region"));
        if (queueOpps.size() >= QUEUE_MAX) queueOpps.clear();
        queueOpps.put(sid, opponent);
    }

    /** The longest confirm window a push may set, in seconds. */
    private static final double MAX_WINDOW_S = 900d;

    /** A push's {@code confirm_window_s} in milliseconds: a JSON number from 1 to
     *  {@link #MAX_WINDOW_S} seconds. Anything else (absent, not a number,
     *  under a second, above the maximum) is {@link FightSession#CONFIRM_MS}. */
    private static long confirmMs(JsonObject o)
    {
        JsonElement el = o == null ? null : o.get("confirm_window_s");
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return FightSession.CONFIRM_MS;
        try
        {
            double seconds = el.getAsDouble();
            if (!(seconds >= 1d && seconds <= MAX_WINDOW_S)) return FightSession.CONFIRM_MS;
            return (long) (seconds * 1000d);
        }
        catch (RuntimeException e)
        {
            return FightSession.CONFIRM_MS;
        }
    }

    private static Set<String> parseStrings(JsonObject o, String key)
    {
        Set<String> out = new HashSet<>();
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return out;
        for (JsonElement el : o.getAsJsonArray(key))
        {
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) continue;
            String s = el.getAsString();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static Style parseStyle(String s)
    {
        if (s == null || s.isEmpty()) return null;
        try { return Style.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static BuildType parseBuild(String s)
    {
        if (s == null || s.isEmpty()) return null;
        try { return BuildType.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
