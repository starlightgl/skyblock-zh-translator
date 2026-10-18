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
        try (java.io.InputStream in = PhraseBook.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/npc_translator_phrasebook.json")) {
            if (in != null) readEntries(new InputStreamReader(in, StandardCharsets.UTF_8), entries);
        } catch (Exception e) {
            System.err.println("[NPCTranslator] Failed to read built-in phrasebook: " + e.getMessage());
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
        return m == null ? null : m.translate(formatted);
    }

    /** See {@link PhraseMatcher#translateLines}; returns all-null when not loaded. */
    public static String[] translateLines(List<String> formattedLines) {
        PhraseMatcher m = current();
        if (m == null) return new String[formattedLines.size()];
        return m.translateLines(formattedLines, 6);
    }
}
