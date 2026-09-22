package com.pvp.leaderboard.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
    private JsonLenient() {}

    public static String optString(JsonObject o, String key, String def)
    {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try { return e.getAsString(); } catch (RuntimeException ex) { return def; }
    }

    public static int optInt(JsonObject o, String key, int def)
    {
        Integer v = optInteger(o, key);
        return v == null ? def : v;
    }

    public static Integer optInteger(JsonObject o, String key)
    {
        if (o == null) return null;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        try { return (int) e.getAsDouble(); } catch (RuntimeException ex) { return null; }
    }

    public static long optLong(JsonObject o, String key, long def)
    {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try { return (long) e.getAsDouble(); } catch (RuntimeException ex) { return def; }
    }

    public static double optDouble(JsonObject o, String key, double def)
    {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try { return e.getAsDouble(); } catch (RuntimeException ex) { return def; }
    }

    /** A real boolean, or the strings {@code "true"} / {@code "false"}; anything
     *  else (numbers, other strings, arrays) is the default — Gson's own
     *  {@code getAsBoolean()} would silently read {@code "yes"} as false. */
    public static boolean optBool(JsonObject o, String key, boolean def)
    {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try
        {
            if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            if (e.getAsJsonPrimitive().isString())
            {
                String s = e.getAsString().trim();
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
            }
            return def;
        }
        catch (RuntimeException ex)
        {
            return def;
        }
    }

    /** The nested object, or {@code null} when absent / not an object. */
    public static JsonObject optObject(JsonObject o, String key)
    {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** The nested array, or an empty array when absent / not an array. */
    public static JsonArray optArray(JsonObject o, String key)
    {
        if (o == null) return new JsonArray();
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
