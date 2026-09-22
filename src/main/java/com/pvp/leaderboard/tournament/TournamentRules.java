package com.pvp.leaderboard.tournament;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The optional {@code rules} block of a {@code tournament/list_response}
 * entry (plugin set 6, operator request 2026-09-22):
 * <pre>{"version": 1, "sections": [{"heading": "...", "lines": ["...", "..."]}, ...]}</pre>
 * rendered by the in-panel Rules dialog as headings + wrapped plain lines.
 * The field is additive — an older backend sends none and the plugin falls
 * back to the entry's {@code rules_url} — so it is parsed defensively:
 * anything that is not an object with at least one usable section reads as
 * <b>absent</b> ({@code null}); a section needs a string heading or at
 * least one string line; non-string lines are skipped, never printed.
 */
public final class TournamentRules
{
    public final int version;
    /** In wire order, never empty. */
    public final List<Section> sections;

    public static final class Section
    {
        /** {@code ""} when the section has no heading. */
        public final String heading;
        public final List<String> lines;

        public Section(String heading, List<String> lines)
        {
            this.heading = heading == null ? "" : heading;
            this.lines = lines == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    public TournamentRules(int version, List<Section> sections)
    {
        this.version = version;
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
    }

    /** {@code null} when {@code e} is missing, not an object, has no
     *  {@code sections} array, or no section survives the checks above. */
    public static TournamentRules fromJson(JsonElement e)
    {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        JsonElement secs = o.get("sections");
        if (secs == null || !secs.isJsonArray()) return null;
        List<Section> out = new ArrayList<>();
        for (JsonElement s : secs.getAsJsonArray())
        {
            if (s == null || !s.isJsonObject()) continue;
            JsonObject so = s.getAsJsonObject();
            String heading = str(so.get("heading")).trim();
            List<String> lines = new ArrayList<>();
            JsonElement ls = so.get("lines");
            if (ls != null && ls.isJsonArray())
            {
                for (JsonElement l : ls.getAsJsonArray())
                {
                    String line = str(l);
                    if (!line.isEmpty()) lines.add(line);
                }
            }
            if (heading.isEmpty() && lines.isEmpty()) continue;
            out.add(new Section(heading, lines));
        }
        if (out.isEmpty()) return null;
        return new TournamentRules(JsonLenient.optInt(o, "version", 1), out);
    }

    /** A string primitive, else {@code ""} — a number or an object in a
     *  heading / line slot is junk, not text. */
    private static String str(JsonElement e)
    {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return "";
        return e.getAsString();
    }
}
