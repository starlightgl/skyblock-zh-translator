package com.npctranslator;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Only known cycling-menu tooltips; item names and lore keep their normal matching path. */
public final class FilterMenuText {
    private FilterMenuText() {}
    private static final Pattern CODE = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern COUNT = Pattern.compile("And ([0-9][0-9,]*) more options");
    private static final Pattern PREFIX = Pattern.compile("^((?:(?i:§[0-9a-fk-or])|\\s|▶)*)(.*?)(\\s*)$");
    private static final JsonArray GROUPS = load();
    private static JsonArray load() {
        try (var stream = FilterMenuText.class.getResourceAsStream(
                "/assets/hypixel-npc-and-items-translate/defaults/filter_menu_text.json")) {
            if (stream == null) return new JsonArray();
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (Exception e) { return new JsonArray(); }
    }
    private static String plain(String s) { return CODE.matcher(s).replaceAll("").trim(); }
    private static String key(String s) { return plain(s).replaceFirst("^▶\\s*", ""); }
    private static String line(String s, JsonObject options) {
        String k = key(s);
        if (options.has(k)) {
            Matcher m = PREFIX.matcher(s);
            if (!m.matches()) return null;
            // Retain the original indentation, marker, colours and final reset codes.
            String suffix = "";
            Matcher tail = Pattern.compile("((?:(?i:§[0-9a-fk-or]))+)$").matcher(s.stripTrailing());
            if (tail.find()) suffix = tail.group(1);
            return m.group(1) + options.get(k).getAsString() + suffix + m.group(3);
        }
        Matcher count = COUNT.matcher(plain(s));
        if (count.matches()) {
            Matcher m = PREFIX.matcher(s);
            return m.matches() ? m.group(1) + "另有 " + count.group(1) + " 个选项" : null;
        }
        return null;
    }
    public static String[] overrides(List<String> lines) {
        if (lines.size() < 3) return null;
        String title = plain(lines.get(0));
        JsonObject best = null;
        int bestScore = 1;
        for (JsonElement element : GROUPS) {
            JsonObject group = element.getAsJsonObject();
            boolean allowed = false;
            for (JsonElement t : group.getAsJsonArray("titles")) if (title.equals(t.getAsString())) allowed = true;
            if (!allowed) continue;
            JsonObject options = group.getAsJsonObject("options");
            Set<String> matched = new HashSet<>();
            for (int i = 1; i < lines.size(); i++) if (options.has(key(lines.get(i)))) matched.add(key(lines.get(i)));
            if (matched.size() > bestScore) { best = group; bestScore = matched.size(); }
        }
        if (best == null) return null;
        String[] out = new String[lines.size()];
        JsonObject options = best.getAsJsonObject("options").deepCopy();
        if (best.has("texts")) for (var entry : best.getAsJsonObject("texts").entrySet())
            options.add(entry.getKey(), entry.getValue());
        for (int i = 1; i < lines.size(); i++) out[i] = line(lines.get(i), options);
        return out;
    }
    /** Known option rows are barriers: no whole-sentence template may absorb their neighbours. */
    public static String[] translateLines(List<String> lines, String[] overrides) {
        if (overrides == null) return PhraseBook.translateLines(lines);
        String[] out = overrides.clone();
        for (int i = 0; i < lines.size();) {
            if (overrides[i] != null) { i++; continue; }
            int end = i + 1;
            while (end < lines.size() && overrides[end] == null) end++;
            List<String> segment = new ArrayList<>();
            if (i > 0) segment.add(""); // Preserve the original title-only no-join rule.
            segment.addAll(lines.subList(i, end));
            String[] translated = PhraseBook.translateLines(segment);
            System.arraycopy(translated, i > 0 ? 1 : 0, out, i, end - i);
            i = end;
        }
        return out;
    }
}
