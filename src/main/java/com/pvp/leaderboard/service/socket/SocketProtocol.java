package com.pvp.leaderboard.service.socket;

import com.google.gson.*;
import java.util.*;

/**
 * Pure helper for socket-lobby wire encoding/decoding + the
 * client-side outgoing-cmd allowlist.
 *
 * <p>Any client code that wants to send a cmd MUST route through
 * {@link #encode(Gson, String, JsonObject)} so the
 * {@link #ALLOWED_OUTGOING} guard fires before bytes leave the process.
 *
 * <p>Keepalive is RFC 6455 native ping/pong (handled by
 * {@code OkHttpClient.Builder.pingInterval(8 min)} in
 * {@link SocketMgr}); there is no {@code system/ping} cmd.
 */
public final class SocketProtocol
{
    /**
     * The cmds the plugin is allowed to send (the lobby's confirm, block and
     * unblock). Anything outside this set throws from
     * {@link #encode(Gson, String, JsonObject)}.
     *
     * <p>The {@code queue/*} cmds are sent only by {@code WebSocketQueueService}
     * and the {@code tournament/*} cmds only by {@code TourneySvc}.
     *
     * <p>Server-only outbound cmds ({@code lobby/roster},
     * {@code lobby/invite_received}, etc.) are NEVER in this set —
     * they're inbound-only on the client. Listening for them is via
     * {@link SocketBus#register(String, java.util.function.Consumer)}.
     */
    public static final Set<String> ALLOWED_OUTGOING = Set.of(
        "lobby/confirm", "lobby/block", "lobby/unblock",
        // The matchmaking queue (WebSocketQueueService) and the Swiss
        // tournaments (TourneySvc).
        "queue/join", "queue/leave", "queue/expand_range", "queue/set_prefs", "queue/status", "queue/recent",
        "tournament/list", "tournament/register", "tournament/withdraw", "tournament/status",
        "tournament/subscribe", "tournament/unsubscribe", "tournament/in_combat",
        "tournament/round_end_reply", "tournament/report_problem", "tournament/gear_status", "tournament/world",
        "tournament/no_show", "tournament/here");

    /**
     * Encodes a cmd + payload pair into the wire envelope string.
     * Throws if {@code cmd} is not in {@link #ALLOWED_OUTGOING}.
     *
     * @param gson the injected client {@link Gson}
     * @param cmd  the cmd name (e.g. {@code "lobby/invite"})
     * @param data the payload object; {@code null} renders as
     *             {@code "data": {}}
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
            // string. The data must always be an object; anything
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
