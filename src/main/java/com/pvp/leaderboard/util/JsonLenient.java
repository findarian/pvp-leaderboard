package com.pvp.leaderboard.util;

import com.google.gson.*;
import java.util.function.*;

/**
 * Null- and type-tolerant readers for server-pushed JSON (Plan 10,
 * 2026-09-21). Every socket push is parsed on OkHttp's read-loop thread,
 * where a {@code ClassCastException} from a missing or re-typed key would
 * be swallowed by the event bus and silently drop the frame; these
 * helpers make a partial payload degrade to a neutral value instead.
 * Shared by the queue and tournament wire adapters.
 */
public final class JsonLenient
{
    private static JsonElement get(JsonObject o, String key)
    {
        return o == null ? null : o.get(key);
    }

    /** {@code read} of the primitive at {@code key}; {@code def} when there is
     *  no object, no such key, JSON null, an object or array, or {@code read}
     *  throws (a string that is not a number). */
    private static <T> T opt(JsonObject o, String key, T def, Function<JsonPrimitive, T> read)
    {
        JsonElement e = get(o, key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try { return read.apply(e.getAsJsonPrimitive()); } catch (RuntimeException ex) { return def; }
    }

    public static String optString(JsonObject o, String key, String def)
    {
        return opt(o, key, def, JsonPrimitive::getAsString);
    }

    /** {@link #optString(JsonObject, String, String)} with {@code ""} for none. */
    public static String optString(JsonObject o, String key)
    {
        return optString(o, key, "");
    }

    public static int optInt(JsonObject o, String key, int def)
    {
        Integer v = optInteger(o, key);
        return v == null ? def : v;
    }

    public static Integer optInteger(JsonObject o, String key)
    {
        return opt(o, key, null, p -> (int) p.getAsDouble());
    }

    public static long optLong(JsonObject o, String key, long def)
    {
        return opt(o, key, def, p -> (long) p.getAsDouble());
    }

    public static double optDouble(JsonObject o, String key, double def)
    {
        return opt(o, key, def, JsonPrimitive::getAsDouble);
    }

    /** A whole JSON number of at least 1; -1 for anything else (a numeric string, a fraction, 0, a boolean, absent). */
    public static long optWhole(JsonObject o, String key)
    {
        double v = opt(o, key, 0.0, p -> p.isNumber() ? p.getAsDouble() : 0.0);
        return v >= 1 && v % 1 == 0 ? (long) v : -1;
    }

    /** A real boolean, or the strings {@code "true"} / {@code "false"}; anything
     *  else (numbers, other strings, arrays) is the default — Gson's own
     *  {@code getAsBoolean()} would silently read {@code "yes"} as false. */
    public static boolean optBool(JsonObject o, String key, boolean def)
    {
        return opt(o, key, def, p ->
        {
            if (p.isBoolean()) return p.getAsBoolean();
            String s = p.getAsString().trim();
            return "true".equalsIgnoreCase(s) || !"false".equalsIgnoreCase(s) && def;
        });
    }

    /** The nested object, or {@code null} when absent / not an object. */
    public static JsonObject optObject(JsonObject o, String key)
    {
        JsonElement e = get(o, key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** The nested array, or an empty array when absent / not an array. */
    public static JsonArray optArray(JsonObject o, String key)
    {
        JsonElement e = get(o, key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** The element's text when it is a JSON string, else {@code null} (a number or an object is not text). */
    public static String str(JsonElement e)
    {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
