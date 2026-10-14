package com.pvp.leaderboard.service.socket;

import com.google.gson.*;

/**
 * Immutable wire-envelope value object for socket-lobby traffic. Both
 * directions (client→server cmds and server→client pushes) share the
 * same shape:
 *
 * <pre>{ "cmd": "&lt;namespace&gt;/&lt;verb&gt;", "data": { ... } }</pre>
 *
 * <p>Serialised via {@link com.google.gson.Gson} — the field names match
 * the wire keys directly so no {@code @SerializedName} annotations are
 * needed.
 *
 * <p>Fields are package-private finals; the type is constructed via
 * factory methods in {@link SocketProtocol} (encode side) or parsed
 * straight off the wire by {@link com.google.gson.Gson#fromJson} (decode
 * side). The {@link #data} object is never null — empty payloads use
 * {@code new JsonObject()}.
 */
public final class SocketCommand
{
    public final String cmd;
    public final JsonObject data;

    /** {@code cmd} is non-empty: both callers ({@link SocketProtocol#encode}
     *  after its allowlist test, {@link SocketProtocol#decode} after its own
     *  test) never pass anything else. */
    public SocketCommand(String cmd, JsonObject data)
    {
        this.cmd = cmd;
        // Defensive: tolerate callers passing null by substituting an
        // empty object. The wire spec disallows null data but a
        // misbehaving listener shouldn't trip a NullPointerException
        // downstream — fail soft, log nothing, render an empty payload.
        this.data = data == null ? new JsonObject() : data;
    }
}
