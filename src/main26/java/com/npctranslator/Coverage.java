package com.npctranslator;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * How much of what the player sees is covered by the translation data. Every distinct line (numbers
 * folded) that needs translating is counted once as covered or not covered; uncovered lines are appended
 * to config/npc_translator_missing.tsv (where, English) so they can be added to the term table.
 */
public final class Coverage {

    public static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_missing.tsv");

    private static final Set<String> COVERED = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> MISSING = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<String[]> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile long lastFlush = 0;

    private Coverage() {}

    /** A line was shown; {@code translated} tells whether a translation was found. */
    public static void record(String formatted, boolean translated, String where) {
        if (formatted == null) return;
        String plain = TranslationUtil.stripColorCodes(formatted).trim();
        if (plain.isEmpty() || !TranslationUtil.needsTranslation(plain)) return;
        String key = TranslationUtil.normalize(plain).text;
        if (translated) {
            if (COVERED.size() < 50000 && COVERED.add(key)) MISSING.remove(key);
            return;
        }
        if (COVERED.contains(key) || MISSING.size() >= 50000) return;
        if (MISSING.putIfAbsent(key, where == null ? "" : where) == null) {
            PENDING.add(new String[]{where == null ? "" : where, key});
            flushSoon();
        }
    }

    private static void flushSoon() {
        long now = System.currentTimeMillis();
        if (now - lastFlush < 5000) return;
        lastFlush = now;
        Thread t = new Thread(Coverage::flush, "NPCTranslator-Coverage");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void flush() {
        if (PENDING.isEmpty()) return;
        boolean header = !FILE.exists();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE, true), StandardCharsets.UTF_8)) {
            if (header) w.write("位置\t英文（数字为 {0}）\n");
            String[] row;
            while ((row = PENDING.poll()) != null) {
                w.write(row[0].replace('\t', ' ') + "\t" + row[1].replace('\t', ' ') + "\n");
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] coverage file: " + e.getMessage());
        }
    }

    public static int covered() { return COVERED.size(); }

    public static int missing() { return MISSING.size(); }

    /** Up to {@code n} uncovered lines grouped by place, most frequent place first. */
    public static List<String> summary(int n) {
        Map<String, Integer> byPlace = new ConcurrentHashMap<>();
        for (String where : MISSING.values()) byPlace.merge(where.isEmpty() ? "其他" : where, 1, Integer::sum);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(byPlace.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) out.add(list.get(i).getKey() + " · " + list.get(i).getValue());
        return out;
    }

    public static void reset() {
        flush();
        COVERED.clear();
        MISSING.clear();
    }

    public static void saveNow() {
        flush();
    }
}
