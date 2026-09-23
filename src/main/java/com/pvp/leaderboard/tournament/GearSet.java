package com.pvp.leaderboard.tournament;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvp.leaderboard.util.JsonLenient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    public final String spellbookLabel;
    public final String match;
    public final String variants;
    public final String digest;
    public final String imageUrl;
    public final List<GearItem> items;

    public GearSet(String setId, String name, int version, boolean placeholder, String build, String buildLabel, String spellbook,
                   String spellbookLabel, String match, String variants, String digest, String imageUrl, List<GearItem> items)
    {
        this.setId = setId;
        this.name = name;
        this.version = version;
        this.placeholder = placeholder;
        this.build = build;
        this.buildLabel = buildLabel;
        this.spellbook = spellbook;
        this.spellbookLabel = spellbookLabel;
        this.match = match;
        this.variants = variants;
        this.digest = digest;
        this.imageUrl = imageUrl;
        this.items = items == null ? Collections.<GearItem>emptyList() : Collections.unmodifiableList(new ArrayList<>(items));
    }

    public static GearSet fromJson(JsonElement e)
    {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        String setId = JsonLenient.optString(o, "set_id", "").trim();
        String digest = JsonLenient.optString(o, "digest", "").trim();
        String build = JsonLenient.optString(o, "build", "");
        if (setId.isEmpty() || digest.isEmpty() || !BUILDS.contains(build)) return null;
        List<GearItem> items = new ArrayList<>();
        for (JsonElement item : JsonLenient.optArray(o, "items"))
        {
            GearItem parsed = GearItem.fromJson(item);
            if (parsed != null) items.add(parsed);
        }
        if (items.isEmpty()) return null;
        String spellbook = JsonLenient.optString(o, "spellbook", null);
        if (!SPELLBOOKS.contains(spellbook)) spellbook = null;
        String buildLabel = JsonLenient.optString(o, "build_label", "").trim();
        String spellbookLabel = JsonLenient.optString(o, "spellbook_label", "").trim();
        String variants = JsonLenient.optString(o, "variants", VARIANTS_ANY);
        Integer version = JsonLenient.optInteger(o, "version");
        String name = JsonLenient.optString(o, "name", "").trim();
        return new GearSet(
            setId,
            name.isEmpty() ? setId : name,
            version == null || version < 1 ? 1 : version,
            JsonLenient.optBool(o, "placeholder", false),
            build,
            buildLabel.isEmpty() ? buildLabel(build) : buildLabel,
            spellbook,
            spellbook == null ? null : spellbookLabel.isEmpty() ? spellbookLabel(spellbook) : spellbookLabel,
            "exact",
            VARIANTS_EXACT.equals(variants) ? VARIANTS_EXACT : VARIANTS_ANY,
            digest,
            JsonLenient.optString(o, "image_url", null),
            items);
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

    public static String spellbookLabel(String key)
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

    public static String spellbookFromText(String text)
    {
        String t = plain(text);
        if (t.contains("ancient")) return "ancient";
        if (t.contains("lunar")) return "lunar";
        if (t.contains("arceuus")) return "arceuus";
        if (t.contains("standard") || t.contains("normal") || t.contains("modern")) return "standard";
        return null;
    }

    public static String spellbookFromVarbit(int value)
    {
        return value >= 0 && value < SPELLBOOKS.size() ? SPELLBOOKS.get(value) : null;
    }

    static String plain(String text)
    {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", "").replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
    }
}
