package com.pvp.leaderboard.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.service.socket.SocketEventBus;
import com.pvp.leaderboard.service.socket.WebSocketManager;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *   args and re-sends them via {@link WebSocketManager#addConnectListener}
 *   on every socket open so server-side state rebuilds without the
 *   panel knowing about reconnects.</li>
 * </ol>
 *
 * <p><b>Threading</b>: server pushes arrive on OkHttp's dispatcher
 * thread via {@link SocketEventBus}; this service marshals every
 * {@link LobbyEventListener} callback through
 * {@link SwingUtilities#invokeLater} per the {@link LobbyService}
 * contract. The injected {@link WebSocketManager} is thread-safe and
 * {@link #send(String, JsonObject)} just hands off to its internal
 * {@code WebSocket.send(String)} which is thread-safe.
 *
 * <p><b>Leave-cascade fast-path</b>: when another member's leave
 * invalidates this viewer's filtered roster the server pushes
 * {@code lobby/roster {members: null, stale: true}}. This service
 * re-issues {@code lobby/join} so the server rebuilds + repushes.
 */
@Slf4j
@Singleton
public final class WebSocketLobbyService implements LobbyService
{
    private final WebSocketManager socket;
    private final SocketEventBus bus;

    /**
     * Roster cache keyed by canonical {@code player_id}. Updated wholesale
     * on every {@code lobby/roster} (not merged — the server's snapshot
     * is authoritative). Read on every flat-payload push to enrich
     * {@code LobbyMember} fields. {@link ConcurrentHashMap} for the
     * snapshot-on-OkHttp-thread + lookup-on-OkHttp-thread access
     * pattern; lookups never race a snapshot write because both happen
     * on the same dispatcher thread, but the map is shared in case a
     * future refactor moves the snapshot write off-thread.
     */
    private final Map<String, LobbyMember> rosterByPlayerId = new ConcurrentHashMap<>();

    /** Block-list snapshot, mirroring {@code lobby/block_list_snapshot}
     *  + the {@code lobby/block_added}/{@code lobby/block_removed}
     *  deltas. Keys are canonical lowercase player ids. Exposed to the
     *  panel via {@link LobbyEventListener#onBlockListSnapshot}. */
    private final Set<String> blockedPlayerIds = ConcurrentHashMap.newKeySet();

    /** Last successful (sent — not necessarily ACKed) join args, replayed
     *  on every reconnect. {@code null} until the first {@link #joinLobby}. */
    private volatile JoinArgs lastJoinArgs;

    private volatile LobbyEventListener listener;
    private volatile boolean started = false;

    @Inject
    public WebSocketLobbyService(WebSocketManager socket, SocketEventBus bus)
    {
        this.socket = socket;
        this.bus = bus;
    }

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
        bus.register("lobby/joined",                  this::handleJoinedEcho);
        bus.register("lobby/roster",                  this::handleRoster);
        bus.register("lobby/block_list_snapshot",     this::handleBlockListSnapshot);
        bus.register("lobby/block_added",             this::handleBlockAdded);
        bus.register("lobby/block_removed",           this::handleBlockRemoved);
        bus.register("lobby/invite_received",         this::handleInviteReceived);
        bus.register("lobby/invite_cancelled",        this::handleInviteCancelled);
        bus.register("lobby/fight_proposed",          this::handleFightProposed);
        bus.register("lobby/fight_confirmed_by_peer", this::handleFightConfirmedByPeer);
        bus.register("lobby/match_found",             this::handleMatchFound);
        bus.register("lobby/session_expired",         this::handleSessionExpired);
        bus.register("error/lobby",                   this::handleError);
        socket.addConnectListener(this::replayJoinOnReconnect);
    }

    @Override
    public void stop()
    {
        // Singleton lifetime — the plugin shutdown handler closes the
        // socket. No per-call teardown needed; the bus has no
        // unregister API and re-using the service across panel
        // open/close cycles is the desired behaviour (server-side
        // membership persists for 30 min sliding TTL).
    }

    // ---------------------------------------------------------------
    // Outbound: panel -> server
    // ---------------------------------------------------------------

    @Override
    public void joinLobby(String region, Set<Style> styles, Set<BuildType> builds,
                          int minDisplayRankIdx, int maxDisplayRankIdx, String sortBucket)
    {
        // Normalise + default the bucket so a bad/empty caller-supplied
        // value can't reach the wire as a non-string. Allowed buckets
        // mirror backend.core.lobby.ALLOWED_BUCKETS = {"overall", "nh",
        // "veng", "multi", "dmm"}; anything else collapses to
        // "overall" so the server's bucket validator can't trip.
        String normBucket = (sortBucket == null) ? "overall" : sortBucket.trim().toLowerCase();
        switch (normBucket)
        {
            case "overall": case "nh": case "veng": case "multi": case "dmm":
                break;
            default:
                normBucket = "overall";
        }
        lastJoinArgs = new JoinArgs(region, styles, builds, minDisplayRankIdx, maxDisplayRankIdx, normBucket);
        JsonObject d = new JsonObject();
        d.addProperty("region", region == null ? "" : region.toLowerCase());
        d.add("styles", toJsonArray(stylesToWire(styles)));
        d.add("builds", toJsonArray(buildsToWire(builds)));
        d.addProperty("min_rank_idx", minDisplayRankIdx);
        d.addProperty("max_rank_idx", maxDisplayRankIdx);
        d.addProperty("sort_bucket", normBucket);
        socket.send("lobby/join", d);
    }

    @Override
    public void leaveLobby()
    {
        lastJoinArgs = null;
        socket.send("lobby/leave", new JsonObject());
    }

    @Override
    public void sendInvite(LobbyMember opponent, Style style, BuildType build, String location)
    {
        if (opponent == null || style == null || build == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("to_player_id", opponent.playerId);
        d.addProperty("style", style.name().toLowerCase());
        d.addProperty("build", build.name().toLowerCase());
        d.addProperty("location", location == null ? "" : location);
        socket.send("lobby/invite", d);
    }

    @Override
    public void cancelInvite(LobbyMember opponent)
    {
        if (opponent == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("to_player_id", opponent.playerId);
        socket.send("lobby/cancel_invite", d);
    }

    @Override
    public void acceptInvite(IncomingInvite invite)
    {
        if (invite == null || invite.inviteId == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("invite_id", invite.inviteId);
        socket.send("lobby/accept", d);
    }

    @Override
    public void declineInvite(IncomingInvite invite)
    {
        if (invite == null || invite.inviteId == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("invite_id", invite.inviteId);
        socket.send("lobby/decline", d);
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
        String sid = currentFightSessionId;
        if (sid == null)
        {
            log.debug("confirmFight() with no active fight session — dropping");
            return;
        }
        JsonObject d = new JsonObject();
        d.addProperty("fight_session_id", sid);
        socket.send("lobby/confirm", d);
    }

    @Override
    public void block(LobbyMember member)
    {
        if (member == null || member.playerId == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("blocked_player_id", member.playerId);
        socket.send("lobby/block", d);
    }

    @Override
    public void unblock(LobbyMember member)
    {
        if (member == null || member.playerId == null) return;
        JsonObject d = new JsonObject();
        d.addProperty("blocked_player_id", member.playerId);
        socket.send("lobby/unblock", d);
    }

    /** Pass-through to {@link WebSocketManager#isConnected()}. The
     *  panel's reconnect banner reads this to decide whether to show
     *  the countdown — the wire state is the single source of truth
     *  for "are we online", not anything cached in this service. */
    @Override
    public boolean isConnected()
    {
        return socket.isConnected();
    }

    /** Pass-through to {@link WebSocketManager#getNextReconnectAttemptEpochMs()}.
     *  Drives the 1Hz countdown on the panel's reconnect banner.
     *  Returning {@code 0} suppresses the banner; any non-zero value
     *  is interpreted as "retry scheduled at that wall-clock time". */
    @Override
    public long getNextReconnectAttemptEpochMs()
    {
        return socket.getNextReconnectAttemptEpochMs();
    }

    // ---------------------------------------------------------------
    // Inbound: server -> panel (all dispatched on EDT)
    // ---------------------------------------------------------------

    /** Most recently proposed fight session id; used by
     *  {@link #confirmFight()} since the panel's API doesn't take an
     *  explicit id. Set on {@code lobby/fight_proposed}, cleared on
     *  {@code lobby/match_found} / {@code lobby/session_expired}. */
    private volatile String currentFightSessionId;

    private void handleJoinedEcho(JsonObject data)
    {
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(l::onJoinedEcho);
    }

    private void handleRoster(JsonObject data)
    {
        // Leave-cascade fast-path: server tells us its filtered view
        // is stale and a fresh lobby/join will regenerate it.
        boolean stale = data.has("stale") && data.get("stale").getAsBoolean();
        JsonElement membersEl = data.get("members");
        if (stale || membersEl == null || membersEl.isJsonNull())
        {
            replayJoinOnReconnect();
            return;
        }
        JsonArray members = membersEl.getAsJsonArray();
        List<LobbyMember> roster = new ArrayList<>(members.size());
        Map<String, LobbyMember> nextCache = new HashMap<>();
        for (JsonElement el : members)
        {
            if (!el.isJsonObject()) continue;
            LobbyMember m = parseMember(el.getAsJsonObject());
            if (m == null) continue;
            roster.add(m);
            nextCache.put(m.playerId, m);
        }
        // Replace cache wholesale — server's snapshot is authoritative.
        rosterByPlayerId.clear();
        rosterByPlayerId.putAll(nextCache);
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onRosterSnapshot(roster));
    }

    private void handleBlockListSnapshot(JsonObject data)
    {
        // Backend pushes data={"blocked": [{player_id, name}, ...]} per
        // get_block_list (backend/core/lobby.py). Older builds emitted a
        // flat string array under "blocked_player_ids" — accept either
        // shape so a partial deploy doesn't drop blocks silently.
        Set<String> ids = new HashSet<>();
        if (data != null && data.has("blocked") && data.get("blocked").isJsonArray())
        {
            for (JsonElement el : data.getAsJsonArray("blocked"))
            {
                if (el == null || el.isJsonNull()) continue;
                if (el.isJsonObject())
                {
                    String pid = optString(el.getAsJsonObject(), "player_id");
                    if (!pid.isEmpty()) ids.add(pid);
                }
                else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
                {
                    String pid = el.getAsString();
                    if (!pid.isEmpty()) ids.add(pid);
                }
            }
        }
        if (ids.isEmpty())
        {
            ids.addAll(parseStringArray(data, "blocked_player_ids"));
        }
        blockedPlayerIds.clear();
        blockedPlayerIds.addAll(ids);
        LobbyEventListener l = listener;
        if (l == null) return;
        Set<String> snapshot = Collections.unmodifiableSet(new HashSet<>(blockedPlayerIds));
        SwingUtilities.invokeLater(() -> l.onBlockListSnapshot(snapshot));
    }

    private void handleBlockAdded(JsonObject data)
    {
        String pid = optString(data, "blocked_player_id");
        if (pid.isEmpty()) return;
        blockedPlayerIds.add(pid);
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onBlockAdded(pid));
    }

    private void handleBlockRemoved(JsonObject data)
    {
        String pid = optString(data, "blocked_player_id");
        if (pid.isEmpty()) return;
        blockedPlayerIds.remove(pid);
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onBlockRemoved(pid));
    }

    private void handleInviteReceived(JsonObject data)
    {
        String inviteId = optString(data, "invite_id");
        String fromPlayerId = optString(data, "from_player_id");
        if (inviteId.isEmpty() || fromPlayerId.isEmpty()) return;
        Style style = parseStyle(optString(data, "style"));
        BuildType build = parseBuild(optString(data, "build"));
        if (style == null || build == null) return;
        String location = optString(data, "location");
        long expiresAt = optLong(data, "expires_at_epoch_ms");

        LobbyMember sender = rosterByPlayerId.get(fromPlayerId);
        if (sender == null)
        {
            // Reconstruct a synthetic sender from the flat fields when
            // the roster cache doesn't have a row yet. Prefer the
            // display-cased `from_name`; fall back to `sender_name`
            // and finally to the canonical id itself.
            String displayName = optString(data, "from_name");
            if (displayName.isEmpty()) displayName = optString(data, "sender_name");
            if (displayName.isEmpty()) displayName = fromPlayerId;
            sender = new LobbyMember(
                fromPlayerId,
                displayName,
                parseStyleSet(data, "sender_styles"),
                parseBuildSet(data, "sender_builds"),
                /* currentRankIdx */ -1,
                /* peakRankIdx */ -1,
                optString(data, "sender_region"),
                /* isMod */ false
            );
        }
        IncomingInvite invite = new IncomingInvite(inviteId, sender, style, build, location, expiresAt);
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onIncomingInvite(invite));
    }

    /** {@code lobby/invite_cancelled} is dual-purpose: the <b>cancel
     *  branch</b> carries {@code from_player_id} (sender retracted) and
     *  the <b>decline branch</b> carries {@code declined_by_player_id}
     *  (receiver said no). Both close the same invite from the panel's
     *  perspective, so we route on {@code invite_id} alone — the
     *  branching identifier would only matter if a future UI wants to
     *  distinguish "they cancelled" from "they declined". */
    private void handleInviteCancelled(JsonObject data)
    {
        String inviteId = optString(data, "invite_id");
        if (inviteId.isEmpty()) return;
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onIncomingInviteCancelled(inviteId));
    }

    private void handleFightProposed(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        currentFightSessionId = sid;
        Style style = parseStyle(optString(data, "style"));
        BuildType build = parseBuild(optString(data, "build"));
        if (style == null || build == null) return;
        String location = optString(data, "location");
        long expiresAt = optLong(data, "expires_at_epoch_ms");
        LobbyMember opponent = resolveOpponent(data);
        if (opponent == null) return;
        FightSession session = new FightSession(sid, opponent, style, build, location, expiresAt);
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onFightProposed(session));
    }

    private void handleFightConfirmedByPeer(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onFightConfirmedByPeer(sid));
    }

    private void handleMatchFound(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        Style style = parseStyle(optString(data, "style"));
        BuildType build = parseBuild(optString(data, "build"));
        if (style == null || build == null) return;
        String location = optString(data, "location");
        String world = optString(data, "world");
        String meetingPlace = optString(data, "meeting_place");
        LobbyMember opponent = resolveOpponent(data);
        if (opponent == null) return;
        // Server has already deleted both LobbyMembers rows + the
        // session at this point — the lobby state is consumed, clear
        // our local session-id cache.
        currentFightSessionId = null;
        MatchInfo match = new MatchInfo(sid, opponent, style, build, location, world, meetingPlace);
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onMatchFound(match));
    }

    private void handleSessionExpired(JsonObject data)
    {
        String sid = optString(data, "fight_session_id");
        if (sid.isEmpty()) return;
        if (sid.equals(currentFightSessionId)) currentFightSessionId = null;
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onFightSessionExpired(sid));
    }

    private void handleError(JsonObject data)
    {
        String code = optString(data, "code");
        String message = optString(data, "message");
        LobbyEventListener l = listener;
        if (l == null) return;
        SwingUtilities.invokeLater(() -> l.onError(code, message));
    }

    // ---------------------------------------------------------------
    // Reconnect re-join
    // ---------------------------------------------------------------

    /** Re-sends the cached {@code lobby/join} if one was ever made.
     *  Triggered by both {@code WebSocketManager.onOpen} and the
     *  leave-cascade fast-path in {@link #handleRoster}. Safe to call
     *  with no cached args (no-op). */
    private void replayJoinOnReconnect()
    {
        JoinArgs j = lastJoinArgs;
        if (j == null) return;
        joinLobby(j.region, j.styles, j.builds, j.minDisplayRankIdx, j.maxDisplayRankIdx, j.sortBucket);
    }

    // ---------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------

    /** Reconstructs a {@link LobbyMember} for the opponent of a
     *  fight_proposed / match_found push. The wire carries
     *  {@code player_a_player_id} and {@code player_b_player_id}; the
     *  local user is one of them, the opponent is the other. Falls
     *  back to a synthetic minimal member if the roster cache has no
     *  row for the opponent (rare reconnect-race case). */
    private LobbyMember resolveOpponent(JsonObject data)
    {
        String a = optString(data, "player_a_player_id");
        String b = optString(data, "player_b_player_id");
        if (a.isEmpty() || b.isEmpty()) return null;
        // Self is never in the roster cache (server filters the
        // viewer's own row out of every roster push), so the opponent
        // is whichever side IS in the cache.
        LobbyMember aRow = rosterByPlayerId.get(a);
        LobbyMember bRow = rosterByPlayerId.get(b);
        if (aRow != null && bRow == null) return aRow;
        if (bRow != null && aRow == null) return bRow;
        if (aRow == null && bRow == null)
        {
            // Both player ids unknown — synthesise a minimal record
            // for player_a so the panel can still render the row.
            // Reachable only if fight_proposed arrives before the
            // lobby/roster that introduced the opponent.
            log.debug("resolveOpponent: both player ids absent from roster cache (a={}, b={}) — synthesising", a, b);
            return synthesiseMinimalMember(a);
        }
        // Both present (shouldn't happen). Pick player_a as a stable choice.
        return aRow;
    }

    /** Synthesises a minimal {@link LobbyMember} when a flat-payload
     *  push references a player id not in the roster cache. The
     *  canonical id is reused as the display name (no proper-cased
     *  form available) so the panel can still render the row. */
    private LobbyMember synthesiseMinimalMember(String playerId)
    {
        return new LobbyMember(playerId, playerId,
            EnumSet.noneOf(Style.class), EnumSet.noneOf(BuildType.class),
            -1, -1, "", false);
    }

    private LobbyMember parseMember(JsonObject m)
    {
        String name = optString(m, "name");
        String playerId = optString(m, "player_id");
        if (playerId.isEmpty() && !name.isEmpty())
        {
            // Belt-and-braces: roster rows should always carry
            // player_id post scrub, but derive from display name so
            // a partial backend deploy doesn't drop every row.
            playerId = name.trim().toLowerCase();
        }
        if (playerId.isEmpty()) return null;
        Set<Style> styles = parseStyleSet(m, "styles");
        Set<BuildType> builds = parseBuildSet(m, "builds");
        // `rank_idx` = current rank for the viewer's sort_bucket, used
        // by the rank-slider matchmaking gate. `peak_rank_idx` =
        // all-time peak for the same bucket, used by the card label
        // (display only). Server emits both per get_visible_roster
        // (backend/core/lobby.py); on a partial deploy where peak is
        // missing, fall back to the current rank so the card still
        // renders something rather than collapsing to "Bronze 3".
        int currentRankIdx = (int) optLong(m, "rank_idx");
        int peakRankIdx;
        if (m.has("peak_rank_idx") && m.get("peak_rank_idx").isJsonPrimitive()
            && m.get("peak_rank_idx").getAsJsonPrimitive().isNumber())
        {
            peakRankIdx = (int) optLong(m, "peak_rank_idx");
        }
        else
        {
            peakRankIdx = currentRankIdx;
        }
        String region = optString(m, "region");
        boolean isMod = m.has("is_mod") && m.get("is_mod").isJsonPrimitive()
            && m.get("is_mod").getAsJsonPrimitive().isBoolean()
            && m.get("is_mod").getAsBoolean();
        return new LobbyMember(playerId, name, styles, builds, currentRankIdx, peakRankIdx, region, isMod);
    }

    private static String optString(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive()) return "";
        return el.getAsString();
    }

    private static long optLong(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0L;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return 0L;
        return el.getAsLong();
    }

    private static Set<String> parseStringArray(JsonObject o, String key)
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

    private static Set<Style> parseStyleSet(JsonObject o, String key)
    {
        EnumSet<Style> out = EnumSet.noneOf(Style.class);
        for (String s : parseStringArray(o, key))
        {
            Style st = parseStyle(s);
            if (st != null) out.add(st);
        }
        return out;
    }

    private static Set<BuildType> parseBuildSet(JsonObject o, String key)
    {
        EnumSet<BuildType> out = EnumSet.noneOf(BuildType.class);
        for (String s : parseStringArray(o, key))
        {
            BuildType b = parseBuild(s);
            if (b != null) out.add(b);
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

    private static List<String> stylesToWire(Set<Style> styles)
    {
        if (styles == null || styles.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(styles.size());
        for (Style s : styles) out.add(s.name().toLowerCase());
        return out;
    }

    private static List<String> buildsToWire(Set<BuildType> builds)
    {
        if (builds == null || builds.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(builds.size());
        for (BuildType b : builds) out.add(b.name().toLowerCase());
        return out;
    }

    private static JsonArray toJsonArray(List<String> in)
    {
        JsonArray arr = new JsonArray();
        for (String s : in) arr.add(s);
        return arr;
    }

    /** Cached join args used by the reconnect-replay path. Immutable. */
    private static final class JoinArgs
    {
        final String region;
        final Set<Style> styles;
        final Set<BuildType> builds;
        final int minDisplayRankIdx;
        final int maxDisplayRankIdx;
        final String sortBucket;

        JoinArgs(String region, Set<Style> styles, Set<BuildType> builds,
                 int minDisplayRankIdx, int maxDisplayRankIdx, String sortBucket)
        {
            this.region = region;
            this.styles = styles == null ? EnumSet.noneOf(Style.class) : EnumSet.copyOf(styles);
            this.builds = builds == null ? EnumSet.noneOf(BuildType.class) : EnumSet.copyOf(builds);
            this.minDisplayRankIdx = minDisplayRankIdx;
            this.maxDisplayRankIdx = maxDisplayRankIdx;
            this.sortBucket = sortBucket == null ? "overall" : sortBucket;
        }
    }
}
