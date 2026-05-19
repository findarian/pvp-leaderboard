package com.pvp.leaderboard.service.socket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure helper for socket-lobby wire encoding/decoding + the
 * client-side outgoing-cmd allowlist.
 *
 * <p>Mirrors backend's {@code backend.core.socket_protocol} module —
 * {@link #ALLOWED_OUTGOING} is the plugin-side counterpart of the
 * Python {@code ALLOWED_COMMANDS} env-var-driven allowlist. Any client
 * code that wants to send a cmd MUST route through
 * {@link #encode(String, JsonObject)} so the allowlist guard fires
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
 * {@link WebSocketManager}) — there is no {@code system/ping} cmd in
 * this release per the protocol doc's locked decision §0.1.
 */
public final class SocketProtocol
{
    /**
     * The 9 lobby cmds the plugin is allowed to send. Anything outside
     * this set throws from {@link #encode(String, JsonObject)}.
     *
     * <p>Tournament cmds ({@code tournament/list},
     * {@code tournament/register}, etc.) land at Phase 4 and will be
     * appended to this set in the same change that wires
     * {@code TournamentService}.
     *
     * <p>Server-only outbound cmds ({@code lobby/roster},
     * {@code lobby/invite_received}, etc.) are NEVER in this set —
     * they're inbound-only on the client. Listening for them is via
     * {@link SocketEventBus#register(String, java.util.function.Consumer)}.
     */
    public static final Set<String> ALLOWED_OUTGOING;
    static
    {
        Set<String> s = new HashSet<>();
        s.add("lobby/join");
        s.add("lobby/leave");
        s.add("lobby/invite");
        s.add("lobby/cancel_invite");
        s.add("lobby/accept");
        s.add("lobby/decline");
        s.add("lobby/confirm");
        s.add("lobby/block");
        s.add("lobby/unblock");
        ALLOWED_OUTGOING = Collections.unmodifiableSet(s);
    }

    /** Single shared Gson; the wire format is plain {@code String}-keyed
     *  JSON objects with no custom serialisers needed. Stateless +
     *  thread-safe per Gson's documented contract. */
    private static final Gson GSON = new Gson();

    /**
     * Encodes a cmd + payload pair into the wire envelope string.
     * Throws if {@code cmd} is not in {@link #ALLOWED_OUTGOING} — that's
     * the plugin-side defense-in-depth allowlist guard.
     *
     * @param cmd  the cmd name (e.g. {@code "lobby/invite"})
     * @param data the payload object; {@code null} renders as
     *             {@code "data": {}} per the protocol's "never null"
     *             rule (§2)
     * @return JSON text ready for {@code WebSocket.send(String)}
     * @throws IllegalArgumentException if {@code cmd} is not allowlisted
     */
    public static String encode(String cmd, JsonObject data)
    {
        if (!ALLOWED_OUTGOING.contains(cmd))
        {
            throw new IllegalArgumentException(
                "cmd not in ALLOWED_OUTGOING: " + cmd);
        }
        return GSON.toJson(new SocketCommand(cmd, data));
    }

    /**
     * Decodes a server-pushed wire frame into a {@link SocketCommand}.
     * Returns {@code null} for any malformed input (empty string,
     * non-JSON, JSON that isn't an object, missing/empty {@code cmd},
     * missing/non-object {@code data}). Callers should treat
     * {@code null} as "drop this frame silently" — mirrors backend's
     * {@code error/invalid_message} tolerance: junk in the pipe is not
     * a fatal client bug.
     */
    public static SocketCommand decode(String wire)
    {
        if (wire == null || wire.isEmpty()) return null;
        try
        {
            // gson.fromJson(..., JsonObject.class) is the pattern used
            // throughout this codebase (see PvPDataService); it tolerates
            // the Gson 2.x split between the deprecated JsonParser API
            // and the static parseString() one without forcing a
            // particular Gson version on the RuneLite client jar.
            JsonObject root = GSON.fromJson(wire, JsonObject.class);
            if (root == null) return null;
            if (!root.has("cmd") || !root.get("cmd").isJsonPrimitive()) return null;
            // isJsonPrimitive() is true for numbers + booleans too; the
            // wire spec says cmd is a string. Reject anything else.
            if (!root.get("cmd").getAsJsonPrimitive().isString()) return null;
            String cmd = root.get("cmd").getAsString();
            if (cmd == null || cmd.isEmpty()) return null;
            JsonObject data;
            if (!root.has("data") || root.get("data").isJsonNull())
            {
                data = new JsonObject();
            }
            else if (root.get("data").isJsonObject())
            {
                data = root.getAsJsonObject("data");
            }
            else
            {
                // Per protocol §2 data must always be an object.
                // Anything else is a server bug; render as empty.
                data = new JsonObject();
            }
            return new SocketCommand(cmd, data);
        }
        catch (JsonParseException | IllegalStateException | ClassCastException e)
        {
            return null;
        }
    }

    private SocketProtocol()
    {
        // utility class — no instantiation
    }
}
