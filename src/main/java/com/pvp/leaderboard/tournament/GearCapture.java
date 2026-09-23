package com.pvp.leaderboard.tournament;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GearCapture
{
    private GearCapture() {}

    private static final String NAME_CHARS = "[^A-Za-z0-9 .,!'()+\\-/&]";

    private static final class Row
    {
        final int id;
        final String name;
        long qty;
        String slot;
        boolean stackable;
        boolean alsoCarried;

        Row(int id, String name)
        {
            this.id = id;
            this.name = name;
        }
    }

    public static String toCatalogJson(GearKit kit)
    {
        if (kit == null) return null;
        Map<Integer, Row> rows = new LinkedHashMap<>();
        for (GearKit.Item i : kit.worn) add(rows, i, true);
        for (GearKit.Item i : kit.carried) add(rows, i, false);
        if (kit.pouchKnown) for (GearKit.Item i : kit.pouch) add(rows, i, false);
        if (rows.isEmpty()) return null;

        String build = GearSet.BUILDS.contains(kit.build) ? kit.build : "main";
        StringBuilder sb = new StringBuilder("{\n");
        field(sb, "set_id", quote("capture-" + build));
        field(sb, "name", quote("Captured " + GearSet.buildLabel(build).replace('/', '-') + " kit"));
        field(sb, "version", "1");
        field(sb, "placeholder", "false");
        field(sb, "build", quote(build));
        field(sb, "spellbook", kit.spellbook == null ? "null" : quote(kit.spellbook));
        field(sb, "match", quote("exact"));
        field(sb, "variants", quote("any"));
        sb.append("  \"items\": [\n");
        List<String> lines = new ArrayList<>();
        for (Row r : rows.values())
        {
            StringBuilder line = new StringBuilder("    {\"id\": ").append(r.id)
                .append(", \"name\": ").append(quote(r.name))
                .append(", \"qty\": ").append(r.qty);
            boolean keepSlot = r.slot != null && (r.stackable || (!r.alsoCarried && r.qty == 1));
            if (keepSlot) line.append(", \"slot\": ").append(quote(r.slot));
            if (r.stackable) line.append(", \"stackable\": true");
            lines.add(line.append('}').toString());
        }
        sb.append(String.join(",\n", lines)).append("\n  ]\n}");
        return sb.toString();
    }

    private static void add(Map<Integer, Row> rows, GearKit.Item i, boolean worn)
    {
        if (i == null || i.noted || i.id <= 0 || i.qty <= 0) return;
        Row r = rows.computeIfAbsent(i.id, k -> new Row(i.id, cleanName(i.name, i.id)));
        r.qty += i.qty;
        r.stackable |= i.stackable;
        if (worn && i.slot != null && r.slot == null) r.slot = i.slot;
        if (!worn) r.alsoCarried = true;
    }

    static String cleanName(String name, int id)
    {
        String n = name == null ? "" : name.replaceAll("<[^>]*>", "").replace(' ', ' ').replaceAll(NAME_CHARS, "").replaceAll(" +", " ").trim();
        if (n.length() > 60) n = n.substring(0, 60).trim();
        return n.isEmpty() ? "Item " + id : n;
    }

    private static void field(StringBuilder sb, String key, String jsonValue)
    {
        sb.append("  ").append(quote(key)).append(": ").append(jsonValue).append(",\n");
    }

    private static String quote(String s)
    {
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray())
        {
            if (c == '"' || c == '\\') q.append('\\');
            q.append(c);
        }
        return q.append('"').toString();
    }
}
