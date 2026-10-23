package com.npctranslator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names from the proofread term table (defaults/table_names.json): items, pets, mobs, areas,
 * reforges and enchantments, plus the community aliases taken from the table's notes.
 * Item names on a tooltip may carry a reforge in front and stars behind them; those are handled here.
 */
public final class TableNames {

    private static final String RESOURCE = "/assets/hypixel-npc-and-items-translate/defaults/table_names.json";
    private static final String FIXES = "/assets/hypixel-npc-and-items-translate/defaults/npc_translator_fixes.json";
    /** "Big Brain III", "Absorb 1-4": a name followed by a tier. */
    private static final Pattern TIER = Pattern.compile("^(.+?) ([IVXLCDM]+|\\d+(?:-\\d+)?)$");
    /** "2x Fire Spiral Rune I": a count in front of the name. */
    private static final Pattern COUNT = Pattern.compile("^((?:§[0-9a-fk-or])*)(\\d[\\d,]*x )(.+)$");
    private static final String VANILLA = "/assets/hypixel-npc-and-items-translate/defaults/vanilla_names.json";
    private static volatile boolean loaded = false;
    private static Map<String, String> names = Collections.emptyMap();
    private static Map<String, List<String>> aliases = Collections.emptyMap();
    /** Reforge names that also appear as a prefix of item names ("Heroic Hyperion"). */
    private static Map<String, String> reforges = Collections.emptyMap();

    private static final Pattern PET = Pattern.compile("^\\[Lvl (\\d+)\\] (.+)$");
    /** Stars, master stars and other decorations after an item name. */
    private static final Pattern SUFFIX = Pattern.compile("^(.*?[^\\s✪➊➋➌➍➎⚚✦✿◆♦●])((?:\\s*[✪➊➋➌➍➎⚚✦✿◆♦●]+)+.*)$");
    private static final Pattern LEAD = Pattern.compile("^((?:§[0-9a-fk-or])*)");

    private TableNames() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Map<String, String> n = new HashMap<>();
        Map<String, List<String>> a = new HashMap<>();
        Map<String, String> r = new HashMap<>();
        try (InputStream in = TableNames.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) {
                    n.put(e.getKey(), e.getValue().getAsString());
                }
                if (root.has("reforges")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("reforges").entrySet()) {
                        r.put(e.getKey(), e.getValue().getAsString());
                    }
                }
                if (root.has("aliases")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("aliases").entrySet()) {
                        List<String> list = new ArrayList<>();
                        for (JsonElement x : e.getValue().getAsJsonArray()) list.add(x.getAsString());
                        a.put(e.getKey(), list);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] table names: " + e.getMessage());
        }
        // gemstone names ("Topaz" alone, as in the Gemstones collection) from the bundled fixes
        try (InputStream in = TableNames.class.getResourceAsStream(FIXES)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("output")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("output").entrySet()) {
                        if (!e.getKey().startsWith("_")) n.putIfAbsent(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] fixes names: " + e.getMessage());
        }
        // official names of vanilla items and blocks take priority over the table
        try (InputStream in = TableNames.class.getResourceAsStream(VANILLA)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) {
                    if (!r.containsKey(e.getKey())) n.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] vanilla names: " + e.getMessage());
        }
        names = n;
        aliases = a;
        reforges = r;
    }

    /** All names, for the glossary and the phrase book. */
    public static Map<String, String> all() {
        load();
        return names;
    }

    public static String exact(String en) {
        load();
        return names.get(en);
    }

    /**
     * Translation of an item name line (§-formatted): exact name, "[Lvl 100] Pet", "Reforge Name ✪✪✪".
     * Returns null when the table has no entry for the base name.
     */
    public static String translateName(String formatted) {
        load();
        if (formatted == null || formatted.isEmpty()) return null;
        Matcher lm = LEAD.matcher(formatted);
        String lead = lm.find() ? lm.group(1) : "";
        String plain = TranslationUtil.stripColorCodes(formatted).trim();
        String zh = names.get(plain);
        if (zh != null) return lead + zh;

        Matcher cnt = COUNT.matcher(formatted.trim());
        if (cnt.matches()) {
            String rest = translateName(cnt.group(1) + cnt.group(3));
            if (rest == null || !rest.startsWith(cnt.group(1))) return rest;
            return cnt.group(1) + cnt.group(2) + rest.substring(cnt.group(1).length());
        }

        Matcher pet = PET.matcher(plain);
        if (pet.matches()) {
            String p = names.get(pet.group(2));
            return p == null ? null : lead + "[Lv." + pet.group(1) + "] " + p;
        }
        String suffix = "";
        String core = plain;
        Matcher sm = SUFFIX.matcher(plain);
        if (sm.matches()) {
            core = sm.group(1).trim();
            // keep the stars with their own colours from the formatted line
            int at = indexAfterPlain(formatted, core.length());
            suffix = at >= 0 ? formatted.substring(at) : sm.group(2);
        }
        String base = names.get(core);
        String reforge = null;
        if (base == null) {
            String prefix = reforgePrefix(core);
            int sp = prefix == null ? -1 : prefix.length();
            if (sp > 0) {
                reforge = reforges.get(core.substring(0, sp));
                if (reforge != null) base = names.get(core.substring(sp + 1));
            }
        }
        if (base == null) {
            reforge = null; // A bare enchantment + level is not a reforge followed by an item.
            Matcher tier = TIER.matcher(core);
            if (tier.matches()) {
                String b = names.get(tier.group(1));
                if (b != null) base = b + " " + tier.group(2);
            }
        }
        if (base == null) return null;
        return lead + (reforge != null ? reforge + " " : "") + base + suffix;
    }

    /** Explicit reforge prefix wins over a stale whole-name sentence cache. */
    public static String translateReforgedName(String formatted) {
        load();
        if (formatted == null) return null;
        String plain = TranslationUtil.stripColorCodes(formatted).trim();
        String prefix = reforgePrefix(plain);
        if (prefix == null) return null;
        int sp = prefix.length();
        String body = translateName(plain.substring(sp + 1));
        if (body == null) return null;
        Matcher lm = LEAD.matcher(formatted);
        String lead = lm.find() ? lm.group(1) : "";
        // The normal name path keeps star colours and suffix decorations intact.
        String regular = translateName(formatted);
        String joined = lead + reforges.get(plain.substring(0, sp)) + " ";
        if (regular != null && regular.startsWith(joined)) return regular;
        return joined + body;
    }

    /** English name without reforge/stars/pet level, as used for the alias lookup. */
    public static String baseName(String plain) {
        load();
        if (plain == null) return null;
        plain = plain.trim();
        if (names.containsKey(plain)) return plain;
        Matcher pet = PET.matcher(plain);
        if (pet.matches()) return names.containsKey(pet.group(2)) ? pet.group(2) : null;
        Matcher sm = SUFFIX.matcher(plain);
        String core = sm.matches() ? sm.group(1).trim() : plain;
        if (names.containsKey(core)) return core;
        String prefix = reforgePrefix(core);
        int sp = prefix == null ? -1 : prefix.length();
        if (sp > 0 && reforges.containsKey(core.substring(0, sp)) && names.containsKey(core.substring(sp + 1))) {
            return core.substring(sp + 1);
        }
        return null;
    }

    public static List<String> aliases(String en) {
        load();
        List<String> a = aliases.get(en);
        return a == null ? List.of() : a;
    }

    /** Longest complete prefix, including multi-word reforges such as Green Thumb. */
    private static String reforgePrefix(String text) {
        for (int at = text.lastIndexOf(' '); at > 0; at = text.lastIndexOf(' ', at - 1)) {
            String prefix = text.substring(0, at);
            if (reforges.containsKey(prefix)) return prefix;
        }
        return null;
    }

    /** Index in the formatted string right after the first {@code plainLen} visible characters (leading spaces ignored). */
    private static int indexAfterPlain(String formatted, int plainLen) {
        int seen = 0;
        boolean started = false;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) { i++; continue; }
            if (!started && Character.isWhitespace(c)) continue;
            started = true;
            seen++;
            if (seen == plainLen) return i + 1;
        }
        return -1;
    }
}
