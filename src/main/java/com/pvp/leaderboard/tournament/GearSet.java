package com.pvp.leaderboard.tournament;

import com.google.gson.*;
import com.pvp.leaderboard.util.*;
import java.util.*;
import lombok.*;
import static com.pvp.leaderboard.util.JsonLenient.*;

@AllArgsConstructor
public final class GearSet
{
    public static final String VARIANTS_ANY = "any";
    public static final String VARIANTS_EXACT = "exact";
    public static final List<String> BUILDS = Collections.unmodifiableList(Arrays.asList("main", "zerker", "pure"));
    static final List<String> SPELLBOOKS = Collections.unmodifiableList(Arrays.asList("standard", "ancient", "lunar", "arceuus"));

    public final String setId;
    public final String name;
    public final int version;
    public final boolean placeholder;
    /** main / zerker / pure. */
    public final String build;
    public final String buildLabel;
    public final String spellbook;
    public final String bookLabel;
    public final String match;
    public final String variants;
    public final String digest;
    public final String imageUrl;
    public final List<GearItem> items;

    public static GearSet fromJson(JsonElement e)
    {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        String setId = optString(o, "set_id").trim();
        String digest = optString(o, "digest").trim();
        String build = optString(o, "build");
        if (setId.isEmpty() || digest.isEmpty() || !BUILDS.contains(build)) return null;
        List<GearItem> items = new ArrayList<>();
        for (JsonElement item : optArray(o, "items"))
        {
            GearItem parsed = GearItem.fromJson(item);
            if (parsed != null) items.add(parsed);
        }
        if (items.isEmpty()) return null;
        String spellbook = optString(o, "spellbook", null);
        if (!SPELLBOOKS.contains(spellbook)) spellbook = null;
        String buildLabel = optString(o, "build_label").trim();
        String bookLabel = optString(o, "spellbook_label").trim();
        String variants = optString(o, "variants", VARIANTS_ANY);
        Integer version = optInteger(o, "version");
        String name = optString(o, "name").trim();
        return new GearSet(
            setId,
            name.isEmpty() ? setId : name,
            version == null || version < 1 ? 1 : version,
            optBool(o, "placeholder", false),
            build,
            buildLabel.isEmpty() ? buildLabel(build) : buildLabel,
            spellbook,
            spellbook == null ? null : bookLabel.isEmpty() ? bookLabel(spellbook) : bookLabel,
            "exact",
            VARIANTS_EXACT.equals(variants) ? VARIANTS_EXACT : VARIANTS_ANY,
            digest,
            optString(o, "image_url", null),
            Collections.unmodifiableList(items));
    }

    public String variantsFor(GearItem item)
    {
        return item != null && item.variants != null ? item.variants : variants;
    }

    public static String buildLabel(String build)
    {
        if ("main".equals(build)) return "Max/Med";
        if ("zerker".equals(build)) return "Zerker";
        if ("pure".equals(build)) return "1 Def Pure";
        return build;
    }

    public static String buildFromText(String text)
    {
        String t = plain(text);
        if (t.contains("max/med") || t.contains("max-med")) return "main";
        if (t.contains("zerker")) return "zerker";
        if (t.contains("1 def pure") || t.contains("1 def")) return "pure";
        return null;
    }

    public static String bookLabel(String key)
    {
        if (key == null) return null;
        switch (key)
        {
            case "standard": return "Standard";
            case "ancient": return "Ancient Magicks";
            case "lunar": return "Lunar";
            case "arceuus": return "Arceuus";
            default: return key;
        }
    }

    public static String bookOfText(String text)
    {
        String t = plain(text);
        if (t.contains("ancient")) return "ancient";
        if (t.contains("lunar")) return "lunar";
        if (t.contains("arceuus")) return "arceuus";
        if (t.contains("standard") || t.contains("normal") || t.contains("modern")) return "standard";
        return null;
    }

    public static String bookOfVarbit(int value)
    {
        return value >= 0 && value < SPELLBOOKS.size() ? SPELLBOOKS.get(value) : null;
    }

    public static String plain(String text)
    {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", "").replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
    }
}
