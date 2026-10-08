package com.pvp.leaderboard.service.socket;

import com.google.gson.*;
import java.util.*;

/**
 * Pure helper for socket-lobby wire encoding/decoding + the
 * client-side outgoing-cmd allowlist.
 *
 * <p>Mirrors backend's {@code backend.core.socket_protocol} module —
 * {@link #ALLOWED_OUTGOING} is the plugin-side counterpart of the
 * Python {@code ALLOWED_COMMANDS} env-var-driven allowlist. Any client
 * code that wants to send a cmd MUST route through
 * {@link #encode(Gson, String, JsonObject)} so the allowlist guard fires
 * before bytes leave the process. The server has the same allowlist
 * server-side (defense in depth) but failing fast on the client makes
 * the bug obvious to plugin contributors instead of hidden behind a
 * silent {@code error/invalid_message} push.
 *
 * <p>Max wire size is enforced server-side (4 KB per
 * {@code WEBSOCKET_PROTOCOL.md} §1). The plugin doesn't pre-check size —
 * we'd just be guessing what the JSON serialises to. Server's
 * {@code error/invalid_message MESSAGE_TOO_LARGE} push is the
 * authoritative signal.
 *
 * <p>Keepalive is RFC 6455 native ping/pong (handled by
 * {@code OkHttpClient.Builder.pingInterval(8 min)} in
 * {@link SocketMgr}) — there is no {@code system/ping} cmd in
 * this release per the protocol doc's locked decision §0.1.
 */
public final class SocketProtocol
{
    /**
     * The cmds the plugin is allowed to send (the lobby's confirm, block and
     * unblock). Anything outside this set throws from
     * {@link #encode(Gson, String, JsonObject)}.
     *
     * <p>Plan 10 (2026-09-21) appended the five {@code queue/*} cmds and
     * the nine {@code tournament/*} cmds (WEBSOCKET_PROTOCOL.md 6.2b / 6.3);
     * {@code WebSocketQueueService} / {@code TourneySvc}
     * are the only senders.
     *
     * <p>Server-only outbound cmds ({@code lobby/roster},
     * {@code lobby/invite_received}, etc.) are NEVER in this set —
     * they're inbound-only on the client. Listening for them is via
     * {@link SocketBus#register(String, java.util.function.Consumer)}.
     */
    public static final Set<String> ALLOWED_OUTGOING = Set.of(
        "lobby/confirm", "lobby/block", "lobby/unblock",
        // Plan 10 (2026-09-21): the matchmaking queue (Part B, WebSocketQueueService)
        // and the Swiss tournaments (Part C, TourneySvc). Mirrors the
        // backend additive ALLOWED_COMMANDS_EXTRA / ALLOWED_COMMANDS_TOURNAMENT env lines.
        "queue/join", "queue/leave", "queue/expand_range", "queue/set_prefs", "queue/status", "queue/recent",
        "tournament/list", "tournament/register", "tournament/withdraw", "tournament/status",
        "tournament/subscribe", "tournament/unsubscribe", "tournament/in_combat",
        "tournament/round_end_reply", "tournament/report_problem", "tournament/gear_status");

    /**
     * Encodes a cmd + payload pair into the wire envelope string.
     * Throws if {@code cmd} is not in {@link #ALLOWED_OUTGOING}.
     *
     * @param gson the injected client {@link Gson}
     * @param cmd  the cmd name (e.g. {@code "lobby/invite"})
     * @param data the payload object; {@code null} renders as
     *             {@code "data": {}} per the protocol's "never null"
     *             rule (§2)
     * @return JSON text ready for {@code WebSocket.send(String)}
     * @throws IllegalArgumentException if {@code cmd} is not allowlisted
     */
    public static String encode(Gson gson, String cmd, JsonObject data)
    {
        if (cmd == null || !ALLOWED_OUTGOING.contains(cmd))
        {
            throw new IllegalArgumentException(
                "cmd not in ALLOWED_OUTGOING: " + cmd);
        }
        return gson.toJson(new SocketCommand(cmd, data));
    }

    /**
     * Decodes a server-pushed wire frame into a {@link SocketCommand}.
     * Returns {@code null} for any malformed input (empty string,
     * non-JSON, JSON that isn't an object, missing/empty/non-string
     * {@code cmd}); a missing or non-object {@code data} reads as
     * {@code {}}. Callers treat {@code null} as "drop this frame silently".
     */
    public static SocketCommand decode(Gson gson, String wire)
    {
        try
        {
            // Gson reads null, empty and blank input as null. isJsonPrimitive()
            // is true for numbers + booleans too; the wire spec says cmd is a
            // string. Per protocol §2 data must always be an object; anything
            // else is a server bug and SocketCommand renders it as empty.
            JsonObject root = gson.fromJson(wire, JsonObject.class);
            JsonElement c = root == null ? null : root.get("cmd");
            if (c == null || !c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || c.getAsString().isEmpty()) return null;
            JsonElement d = root.get("data");
            return new SocketCommand(c.getAsString(), d != null && d.isJsonObject() ? d.getAsJsonObject() : null);
        }
        catch (JsonParseException | IllegalStateException | ClassCastException e)
        {
            return null;
        }
    }
}
