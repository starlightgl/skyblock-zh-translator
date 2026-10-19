package com.npctranslator;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Records the text of every menu the player opens (names and descriptions of the items in the menu,
 * numbers folded to {0}) into config/npc_translator_menu_text.tsv. Menu buttons are not in any item
 * database, so this is how they get into the term table.
 */
public final class MenuCollector {

    public static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_menu_text.tsv");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile boolean loaded = false;
    private static volatile long lastFlush = 0;
    private static volatile int added = 0;

    private MenuCollector() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!FILE.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length >= 3) SEEN.add(p[1] + "\t" + p[2]);
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] menu text file: " + e.getMessage());
        }
    }

    /**
     * One line of a menu item. {@code name} = first line (the item name).
     * {@code current} is the translation shown now (may be null).
     */
    public static void record(String menu, boolean name, String formatted, String current) {
        if (formatted == null) return;
        String plain = TranslationUtil.stripColorCodes(formatted).trim();
        if (plain.isEmpty() || !TranslationUtil.needsTranslation(plain)) return;
        load();
        String type = name ? "名称" : "描述";
        String template = TranslationUtil.normalize(plain).text;
        if (SEEN.size() > 100000 || !SEEN.add(type + "\t" + template)) return;
        String cur = current == null ? "" : TranslationUtil.stripColorCodes(current).trim();
        PENDING.add(clean(menu) + "\t" + type + "\t" + clean(template) + "\t" + clean(plain) + "\t" + clean(cur));
        added++;
        long now = System.currentTimeMillis();
        if (now - lastFlush > 5000) {
            lastFlush = now;
            Thread t = new Thread(MenuCollector::flush, "NPCTranslator-MenuText");
            t.setDaemon(true);
            t.start();
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    public static synchronized void flush() {
        if (PENDING.isEmpty()) return;
        boolean header = !FILE.exists();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE, true), StandardCharsets.UTF_8)) {
            if (header) w.write("菜单\t类型\t英文（数字为 {0}）\t原文示例\t当前译文\n");
            String row;
            while ((row = PENDING.poll()) != null) w.write(row + "\n");
        } catch (Exception e) {
            System.err.println("[NPCTranslator] menu text file: " + e.getMessage());
        }
    }

    public static int total() {
        load();
        return SEEN.size();
    }

    public static int addedThisSession() {
        return added;
    }
}
