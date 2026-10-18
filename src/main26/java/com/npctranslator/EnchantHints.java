package com.npctranslator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enchantment subtitles: for every enchantment found in a tooltip (English or Chinese name followed
 * by a level), a grey line "中文名 English — short function" can be shown under the tooltip.
 * Data: built-in assets/.../enchant_hints.json, overridden by config/npc_translator_enchants.json.
 */
public final class EnchantHints {

    public record Entry(String en, String zh, String hint, boolean ultimate, List<String> aliases) {}

    private static volatile Map<String, Entry> byName = Map.of();
    private static volatile Pattern pattern;
    private static volatile Map<String, Entry> byEnglish = Map.of();
    private static volatile Pattern listPattern;
    /** What may remain on a line that is only a list of enchantments: levels, arrows, separators, bullets. */
    private static final Pattern LIST_LEFTOVER = Pattern.compile("^[\\s,，、;；/|›»•◆▸+\\-→>:：()（）0-9IVXLC]*$");
    private static volatile boolean loaded;

    private EnchantHints() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Map<String, Entry> entries = new LinkedHashMap<>();
        try (InputStream in = EnchantHints.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/npc_translator_enchants.json")) {
            if (in != null) read(new InputStreamReader(in, StandardCharsets.UTF_8), entries);
        } catch (Exception e) {
            System.err.println("[NPCTranslator] enchant hints: " + e.getMessage());
        }
        try {
            Path user = FabricLoader.getInstance().getConfigDir().resolve("npc_translator_enchants.json");
            if (Files.exists(user)) {
                try (Reader r = Files.newBufferedReader(user, StandardCharsets.UTF_8)) {
                    read(r, entries);
                }
            }
        } catch (Throwable e) {
            System.err.println("[NPCTranslator] npc_translator_enchants.json: " + e.getMessage());
        }
        Map<String, Entry> names = new LinkedHashMap<>();
        Map<String, Entry> english = new LinkedHashMap<>();
        for (Entry e : entries.values()) {
            names.put(e.en, e);
            english.put(e.en, e);
            for (String a : e.aliases) { names.putIfAbsent(a, e); english.putIfAbsent(a, e); }
            if (e.zh != null && !e.zh.isEmpty()) names.putIfAbsent(e.zh, e);
        }
        List<String> en = new ArrayList<>(english.keySet());
        en.sort((a, b) -> b.length() - a.length());
        StringBuilder enAlt = new StringBuilder();
        for (String k : en) {
            if (enAlt.length() > 0) enAlt.append('|');
            enAlt.append(Pattern.quote(k));
        }
        byEnglish = english;
        // "not preceded by a letter" — but a letter that is a colour/format code (§l) does not count
        listPattern = en.isEmpty() ? null : Pattern.compile("(?<!(?<!§)[A-Za-z])(" + enAlt + ")(?![A-Za-z])");
        List<String> keys = new ArrayList<>(names.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        StringBuilder alt = new StringBuilder();
        for (String k : keys) {
            if (alt.length() > 0) alt.append('|');
            alt.append(Pattern.quote(k));
        }
        byName = names;
        // a name always followed by its level in SkyBlock lore ("Sharpness VII", "锋利 VII")
        pattern = keys.isEmpty() ? null : Pattern.compile("(?<![A-Za-z])(" + alt + ") ?([IVXL]{1,4})(?![A-Za-z])");
    }

    private static void read(Reader reader, Map<String, Entry> into) {
        JsonElement root = JsonParser.parseReader(reader);
        JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("enchants");
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("en")) continue;
            String en = o.get("en").getAsString();
            String zh = o.has("zh") ? o.get("zh").getAsString() : "";
            String hint = o.has("hint") ? o.get("hint").getAsString() : "";
            boolean ult = o.has("ultimate") && o.get("ultimate").getAsBoolean();
            List<String> aliases = new ArrayList<>();
            if (o.has("aliases") && o.get("aliases").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("aliases")) aliases.add(a.getAsString());
            } else if (into.containsKey(en)) {
                aliases.addAll(into.get(en).aliases()); // a user file without aliases keeps the built-in ones
            }
            into.put(en, new Entry(en, zh, hint, ult, aliases));
        }
    }

    /** Enchantments mentioned in the tooltip, in order of appearance, without duplicates. */
    public static List<Entry> find(List<Component> tooltip) {
        load();
        List<Entry> out = new ArrayList<>();
        if (pattern == null) return out;
        for (Component line : tooltip) {
            Matcher m = pattern.matcher(line.getString().replaceAll("§.", ""));
            while (m.find()) {
                Entry e = byName.get(m.group(1));
                if (e != null && !out.contains(e)) out.add(e);
            }
        }
        return out;
    }

    /**
     * A line that is nothing but enchantment names (with optional levels, arrows, commas), e.g.
     * "Sharpness VII, Critical VII" or Skyblocker's "› Smoldering, Tabasco, Vicious": every name is
     * swapped for its Chinese name, colours kept. Returns null for any other line.
     */
    public static String translateList(String formatted) {
        load();
        if (listPattern == null || formatted == null || formatted.isEmpty()) return null;
        String plain = formatted.replaceAll("§.", "");
        Matcher m = listPattern.matcher(plain);
        StringBuilder rest = new StringBuilder();
        int last = 0, count = 0;
        while (m.find()) {
            rest.append(plain, last, m.start());
            last = m.end();
            count++;
        }
        rest.append(plain.substring(last));
        if (count == 0 || !LIST_LEFTOVER.matcher(rest).matches()) return null;
        // a single bare word ("Power", "Protection") may be a menu label, not an enchantment: needs a level
        if (count == 1 && !rest.toString().matches(".*[IVXLC].*")) return null;
        Matcher f = listPattern.matcher(formatted);
        StringBuilder out = new StringBuilder();
        while (f.find()) {
            Entry e = byEnglish.get(f.group(1));
            String zh = e == null || e.zh == null || e.zh.isEmpty() ? f.group(1) : e.zh;
            f.appendReplacement(out, Matcher.quoteReplacement(zh));
        }
        f.appendTail(out);
        return out.toString();
    }

    /** "  锋利 Sharpness - 近战伤害提升" */
    public static Component line(Entry e) {
        String color = e.ultimate ? "§d" : "§9";
        String zh = e.zh == null || e.zh.isEmpty() ? e.en : e.zh;
        String text = " §8\u25b8 " + color + zh + " §8" + e.en + (e.hint == null || e.hint.isEmpty() ? "" : " §7" + e.hint);
        return NPCTranslatorClient.parseColorCodes(text);
    }
}
