package com.npctranslator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * config/npc_translator_phrasebook.json — fixed sentence translations
 * ({"entries":[{"en":"You don't have enough %s!","zh":"你的%s不足!"}, ...]}).
 * Loaded in the background at start-up; until it is ready every lookup simply misses.
 * Re-read automatically when the file changes.
 */
public final class PhraseBook {

    private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_phrasebook.json");
    private static volatile PhraseMatcher matcher = null;
    private static volatile long loadedModified = -1L;
    private static volatile boolean loading = false;
    private static volatile long lastCheck = 0L;

    private PhraseBook() {}

    public static void loadAsync() {
        if (loading) return;
        loading = true;
        Thread t = new Thread(() -> {
            try {
                load();
            } finally {
                loading = false;
            }
        }, "NPCTranslator-PhraseBook");
        t.setDaemon(true);
        t.start();
    }

    private static void load() {
        long mod = FILE.exists() ? FILE.lastModified() : 0L;
        List<String[]> entries = new ArrayList<>();
        // The user's file comes first: the matcher keeps the first entry for a sentence, so user edits win.
        if (mod != 0L) {
            try (Reader r = new InputStreamReader(Files.newInputStream(FILE.toPath()), StandardCharsets.UTF_8)) {
                readEntries(r, entries);
            } catch (Exception e) {
                System.err.println("[NPCTranslator] Failed to read phrasebook: " + e.getMessage());
            }
        }
        // 校对表（物品名等 + 物品描述）排在最前，其次是修正和社区语料
        for (java.util.Map.Entry<String, String> n : TableNames.all().entrySet()) {
            entries.add(new String[]{n.getKey(), n.getValue()});
        }
        try (java.io.InputStream in = PhraseBook.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/table_lines.json")) {
            if (in != null) readEntries(new InputStreamReader(in, StandardCharsets.UTF_8), entries);
        } catch (Exception e) {
            System.err.println("[NPCTranslator] Failed to read table lines: " + e.getMessage());
        }
        if (NPCTranslatorClient.corpusAllowed()) {
            for (java.util.Map.Entry<String, String> f : TermFixes.glossary().entrySet()) {
                entries.add(new String[]{f.getKey(), f.getValue()});
            }
            try (java.io.InputStream in = PhraseBook.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/npc_translator_phrasebook.json")) {
                if (in != null) readEntries(new InputStreamReader(in, StandardCharsets.UTF_8), entries);
            } catch (Exception e) {
                System.err.println("[NPCTranslator] Failed to read built-in phrasebook: " + e.getMessage());
            }
        }
        if (entries.isEmpty()) {
            matcher = null;
            loadedModified = mod;
            return;
        }
        long t0 = System.currentTimeMillis();
        PhraseMatcher m = new PhraseMatcher(entries).withValueTranslator(PhraseBook::translateValue);
        matcher = m;
        loadedModified = mod;
        System.out.println("[NPCTranslator] Phrasebook loaded: " + m.size() + " entries in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static final java.util.regex.Pattern VALUE_CORE =
            java.util.regex.Pattern.compile("^([^A-Za-z]*?)([A-Za-z](?:[A-Za-z' -]*[A-Za-z])?)([^A-Za-z]*)$");

    private static final java.util.Map<String, String> SUFFIXES = java.util.Map.ofEntries(
            java.util.Map.entry("Fortune", "时运"), java.util.Map.entry("Collection", "收集"), java.util.Map.entry("Skill", "技能"),
            java.util.Map.entry("Recipe", "配方"), java.util.Map.entry("Recipes", "配方"), java.util.Map.entry("Crop", "作物"),
            java.util.Map.entry("Minion", "小人"), java.util.Map.entry("Shard", "碎片"), java.util.Map.entry("Potion", "药水"),
            java.util.Map.entry("Enchantment", "附魔"), java.util.Map.entry("Essence", "精粹"), java.util.Map.entry("Experience", "经验"),
            java.util.Map.entry("Level", "等级"), java.util.Map.entry("Milestone", "里程碑"), java.util.Map.entry("Pet", "宠物"),
            java.util.Map.entry("Barn Skin", "谷仓皮肤"), java.util.Map.entry("Skin", "皮肤"), java.util.Map.entry("Dye", "染料"),
            java.util.Map.entry("Sack", "收纳袋"), java.util.Map.entry("Upgrade Stone", "升级石"), java.util.Map.entry("Mobs", "生物"),
            java.util.Map.entry("Pests", "害虫"), java.util.Map.entry("Effects", "效果"), java.util.Map.entry("Trade", "交易"),
            java.util.Map.entry("Pest", "害虫"), java.util.Map.entry("Widget", "组件"), java.util.Map.entry("Quest", "任务"));
    private static final java.util.regex.Pattern SUFFIXED = java.util.regex.Pattern.compile(
            "^(.+?) (Fortune|Collection|Skill|Recipes?|Crop|Minion|Shard|Potion|Enchantment|Essence|Experience|Level|Milestone|Pet|Barn Skin|Skin|Dye|Sack|Upgrade Stone|Mobs|Pests?|Effects|Trade|Widget|Quest)$");
    private static final java.util.Map<String, String> PREFIXES = java.util.Map.of("Enchanted", "附魔", "Tier", "阶级 ", "Requires", "需要");
    private static final java.util.regex.Pattern PREFIXED = java.util.regex.Pattern.compile("^(Enchanted) (.+)$");

    private static final java.util.regex.Pattern LEVELLED = java.util.regex.Pattern.compile("^\\[Lvl ?(\\d+)\\] (.+)$");
    private static final java.util.regex.Pattern TIERED = java.util.regex.Pattern.compile("^(.*[A-Za-z].*?) ([IVXLCDM]+|\\d+)$");

    /** "Trade", "Crop", "Widget": the word that follows a name, alone. */
    static String suffixWord(String w) {
        return SUFFIXES.get(w);
    }

    /** A placeholder value: glossary term as a whole, or its words with numbers/icons around them ("+75 ❤ Health"). */
    static String translateValue(String v) {
        TranslationUtil.GlossaryIndex g = Glossary.index();
        String z = g.exact(v);
        if (z != null) return z;
        java.util.regex.Matcher m = VALUE_CORE.matcher(v);
        if (m.matches()) {
            String core = g.exact(m.group(2));
            if (core != null) return m.group(1) + core + m.group(3);
        }
        // "Coal IX", "Farming 25": a name with its tier or level
        java.util.regex.Matcher t = TIERED.matcher(v);
        if (t.matches()) {
            String head = translateValue(t.group(1));
            if (!head.equals(t.group(1))) return head + " " + t.group(2);
        }
        // "[Lvl 1] Skeleton": a pet with its level
        java.util.regex.Matcher lvl = LEVELLED.matcher(v);
        if (lvl.matches()) {
            String rest = translateValue(lvl.group(2));
            if (!rest.equals(lvl.group(2))) return "[等级 " + lvl.group(1) + "] " + rest;
        }
        // "Wheat Fortune", "Gold Ingot Collection", "Mining Skill", "Enchanted Clock Recipe": a name and a word
        java.util.regex.Matcher sfx = SUFFIXED.matcher(v);
        if (sfx.matches()) {
            String head = translateValue(sfx.group(1));
            if (!head.equals(sfx.group(1)) && !head.matches(".*[A-Za-z]{3,}.*")) return head + SUFFIXES.get(sfx.group(2));
        }
        java.util.regex.Matcher pfx = PREFIXED.matcher(v);
        if (pfx.matches()) {
            String rest = translateValue(pfx.group(2));
            if (!rest.equals(pfx.group(2)) && !rest.matches(".*[A-Za-z]{3,}.*")) return PREFIXES.get(pfx.group(1)) + rest;
        }
        return v;
    }

    private static void readEntries(Reader r, List<String[]> into) {
        JsonElement root = JsonParser.parseReader(r);
        JsonArray arr = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().getAsJsonArray("entries");
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("en") || !o.has("zh")) continue;
            into.add(new String[]{o.get("en").getAsString(), o.get("zh").getAsString()});
        }
    }

    /** Current matcher or null (not loaded yet). Checks for file changes at most every 5 s. */
    private static PhraseMatcher current() {
        long now = System.currentTimeMillis();
        if (now - lastCheck > 5000) {
            lastCheck = now;
            long mod = FILE.exists() ? FILE.lastModified() : 0L;
            if (mod != loadedModified && !loading) loadAsync();
        }
        return matcher;
    }

    public static boolean isReady() {
        return current() != null;
    }

    public static int size() {
        PhraseMatcher m = current();
        return m == null ? 0 : m.size();
    }

    /** §-formatted line in, §-formatted Chinese line out, or null. */
    public static String translate(String formatted) {
        PhraseMatcher m = current();
        return m == null ? null : TermFixes.apply(m.translate(formatted));
    }

    /** See {@link PhraseMatcher#translateLines}; returns all-null when not loaded. */
    public static String[] translateLines(List<String> formattedLines) {
        PhraseMatcher m = current();
        if (m == null) return new String[formattedLines.size()];
        String[] out = m.translateLines(formattedLines, 6);
        for (int i = 0; i < out.length; i++) {
            if (out[i] != null && out[i] != PhraseMatcher.MERGED) out[i] = TermFixes.apply(out[i]);
        }
        return out;
    }
}
