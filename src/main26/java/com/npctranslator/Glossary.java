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
    private static volatile long loadedModified = -1L;

    private Glossary() {}

    public static synchronized void ensureFile() {
        if (FILE.exists()) return;
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("_说明", "左边写英文原文（不区分大小写），右边写你想要的固定译名。想保留英文就左右写一样。改完保存即可，游戏内自动生效。以下划线开头的键是注释。");
        sample.put("_示例1", "Hyperion => Hyperion");
        sample.put("Hyperion", "Hyperion");
        sample.put("Skyblock Level", "空岛等级");
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
        Map<String, String> loaded = new LinkedHashMap<>();
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
        loadedModified = mod;
    }

    public static int size() {
        return get().size();
    }
}
