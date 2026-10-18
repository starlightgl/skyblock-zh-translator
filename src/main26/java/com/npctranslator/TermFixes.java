package com.npctranslator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrections on top of the bundled community data (defaults/npc_translator_fixes.json).
 * <ul>
 *   <li>{@code glossary}: entries that replace bundled glossary terms (e.g. gemstone names);</li>
 *   <li>{@code output}: English proper nouns left in an already translated line are replaced
 *       ("精细 Topaz 宝石" → "精细黄玉"). Only lines that contain Chinese are touched.</li>
 * </ul>
 */
public final class TermFixes {

    private static final String RESOURCE = "/assets/hypixel-npc-and-items-translate/defaults/npc_translator_fixes.json";
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final String QUALITY = "粗糙|有瑕|精细|无瑕|完美";

    private static volatile boolean loaded = false;
    private static Map<String, String> glossary = Collections.emptyMap();
    private static Map<String, String> output = Collections.emptyMap();
    private static Pattern withGem, word, qualitySpace;
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private TermFixes() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Map<String, String> g = new LinkedHashMap<>(), o = new LinkedHashMap<>();
        try (InputStream in = TermFixes.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                read(root, "glossary", g);
                read(root, "output", o);
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] fixes: " + e.getMessage());
        }
        glossary = Collections.unmodifiableMap(g);
        output = Collections.unmodifiableMap(o);
        if (!o.isEmpty()) {
            List<String> names = new ArrayList<>(o.keySet());
            names.sort((a, b) -> b.length() - a.length());
            StringBuilder alt = new StringBuilder();
            for (String n : names) {
                if (alt.length() > 0) alt.append('|');
                alt.append(Pattern.quote(n));
            }
            // a letter right before the name only counts when it is not a colour code (§a)
            String name = "(?<!(?<!§)[A-Za-z])(" + alt + ")(?![A-Za-z])";
            withGem = Pattern.compile(name + "\\s*(?:§[0-9a-fk-or])*\\s*宝石");
            word = Pattern.compile(name);
            StringBuilder zh = new StringBuilder();
            for (String v : o.values()) {
                if (zh.length() > 0) zh.append('|');
                zh.append(Pattern.quote(v));
            }
            qualitySpace = Pattern.compile("(" + QUALITY + ")\\s+((?:§[0-9a-fk-or])*)(" + zh + ")");
        }
    }

    private static void read(JsonObject root, String key, Map<String, String> into) {
        if (!root.has(key) || !root.get(key).isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(key).entrySet()) {
            if (e.getKey().startsWith("_") || !e.getValue().isJsonPrimitive()) continue;
            into.put(e.getKey(), e.getValue().getAsString());
        }
    }

    /** Glossary corrections, applied on top of the bundled glossary. */
    public static Map<String, String> glossary() {
        load();
        return glossary;
    }

    /** A translated line with leftover English proper nouns replaced; other text is returned as is. */
    public static String apply(String translated) {
        if (translated == null || translated.isEmpty()) return translated;
        load();
        if (word == null || !CJK.matcher(translated).find()) return translated;
        String cached = CACHE.get(translated);
        if (cached != null) return cached;
        String out = translated;
        if (word.matcher(out).find()) {
            out = replace(withGem, out);
            out = replace(word, out);
            out = qualitySpace.matcher(out).replaceAll("$1$2$3");
        }
        if (CACHE.size() > 5000) CACHE.clear();
        CACHE.put(translated, out);
        return out;
    }

    private static String replace(Pattern p, String s) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String zh = output.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(zh != null ? zh : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
