package com.npctranslator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config/npc_translator_glossary.json  —  { "English term": "固定译名", ... }
 * Keys starting with "_" are comments. The file is re-read automatically when it changes,
 * so edits take effect without restarting the game.
 */
public final class Glossary {

    private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_glossary.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile Map<String, String> terms = Collections.emptyMap();
    private static volatile TranslationUtil.GlossaryIndex index = new TranslationUtil.GlossaryIndex(Collections.emptyMap());
    private static volatile long loadedModified = -1L;

    private Glossary() {}

    public static synchronized void ensureFile() {
        if (FILE.exists()) return;
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("_说明", "模组已内置一万多条术语。这里写你自己想改或想加的：左边英文原文，右边固定译名，想保留英文就左右写一样。这里的条目优先于内置的。保存后游戏内自动生效。以下划线开头的键是注释。");
        sample.put("_示例", "\"Hyperion\": \"Hyperion\"");
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(FILE.toPath()), StandardCharsets.UTF_8)) {
            GSON.toJson(sample, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Current glossary; reloads from disk if the file was modified. */
    public static Map<String, String> get() {
        long mod = FILE.exists() ? FILE.lastModified() : 0L;
        if (mod != loadedModified) {
            reload(mod);
        }
        return terms;
    }

    private static synchronized void reload(long mod) {
        if (mod == loadedModified) return;
        Map<String, String> loaded = new LinkedHashMap<>(builtIn());
        if (FILE.exists()) {
            try (Reader r = new InputStreamReader(Files.newInputStream(FILE.toPath()), StandardCharsets.UTF_8)) {
                Type type = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
                Map<String, String> m = GSON.fromJson(r, type);
                if (m != null) {
                    for (Map.Entry<String, String> e : m.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        if (e.getKey().startsWith("_")) continue;
                        loaded.put(e.getKey(), e.getValue());
                    }
                }
            } catch (Exception e) {
                System.err.println("[NPCTranslator] Failed to read glossary: " + e.getMessage());
            }
        }
        terms = Collections.unmodifiableMap(loaded);
        index = new TranslationUtil.GlossaryIndex(loaded);
        loadedModified = mod;
    }

    private static Map<String, String> builtIn;

    /** Glossary shipped inside the jar (defaults/npc_translator_glossary.json); the config file overrides it. */
    private static synchronized Map<String, String> builtIn() {
        if (builtIn != null) return builtIn;
        Map<String, String> m = new LinkedHashMap<>();
        if (NPCTranslatorClient.corpusAllowed()) try (java.io.InputStream in = Glossary.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/npc_translator_glossary.json")) {
            if (in != null) {
                Type type = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
                Map<String, String> raw = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
                if (raw != null) {
                    for (Map.Entry<String, String> e : raw.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null || e.getKey().startsWith("_")) continue;
                        m.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] Failed to read built-in glossary: " + e.getMessage());
        }
        if (NPCTranslatorClient.corpusAllowed()) m.putAll(TermFixes.glossary()); // 修正（如宝石名）覆盖社区语料
        m.putAll(TableNames.all()); // 校对表优先
        builtIn = Collections.unmodifiableMap(m);
        return builtIn;
    }

    /** Drops the built-in data so it is rebuilt for the current translation source. */
    public static synchronized void invalidate() {
        builtIn = null;
        loadedModified = Long.MIN_VALUE;
    }

    /** Pre-built index for fast matching (rebuilt when the file changes). */
    public static TranslationUtil.GlossaryIndex index() {
        get();
        return index;
    }

    public static int size() {
        return get().size();
    }
}
