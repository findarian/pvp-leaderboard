package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import java.util.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

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
public final class TourneyRules
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
            this.heading = heading;
            this.lines = lines;
        }
    }

    public TourneyRules(int version, List<Section> sections)
    {
        this.version = version;
        this.sections = sections;
    }

    /** {@code null} when {@code e} is missing, not an object, has no
     *  {@code sections} array, or no section survives the checks above. */
    public static TourneyRules fromJson(JsonElement e)
    {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        List<Section> out = new ArrayList<>();
        for (JsonElement s : optArray(o, "sections"))
        {
            if (s == null || !s.isJsonObject()) continue;
            JsonObject so = s.getAsJsonObject();
            String heading = str(so.get("heading"));
            heading = heading == null ? "" : heading.trim();
            List<String> lines = new ArrayList<>();
            for (JsonElement l : optArray(so, "lines"))
            {
                String line = str(l);
                if (line != null && !line.isEmpty()) lines.add(line);
            }
            if (heading.isEmpty() && lines.isEmpty()) continue;
            out.add(new Section(heading, lines));
        }
        if (out.isEmpty()) return null;
        return new TourneyRules(optInt(o, "version", 1), out);
    }
}
