package com.npctranslator;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Records every piece of English text the game shows (item names and descriptions, menu titles,
 * sidebar, Tab list, titles, action bar, boss bars, chat, name tags, other mods' tooltips) into
 * config/npc_translator_text.tsv, whether it is translated or not. Numbers are folded to {0} and each
 * category + template is written once, so the file can be imported into the term table.
 */
public final class TextCollector {

    public static final String ITEM_NAME = "物品名称";
    public static final String ITEM_LINE = "物品描述";
    public static final String MENU_TITLE = "菜单标题";
    public static final String SIDEBAR = "侧边栏";
    public static final String TAB = "Tab列表";
    public static final String TITLE = "屏幕标题";
    public static final String SUBTITLE = "屏幕副标题";
    public static final String ACTION_BAR = "动作栏";
    public static final String BOSS_BAR = "Boss血条";
    public static final String CHAT = "聊天";

    public static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_text.tsv");
    /** File of 1.5.1-zh.3-beta.5, which only held menu items. Converted on first load. */
    private static final File OLD_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_menu_text.tsv");
    private static final String HEADER = "类别\t位置\t英文（数字为 {0}）\t原文示例\t当前译文\n";
    private static final int LIMIT = 200000;

    /** category + template already written. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    /** category + raw text already handled: lets per-frame callers (Tab list, sidebar) skip the normalising work. */
    private static final Set<String> RAW = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicInteger> TOTAL = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> ADDED = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile boolean loaded = false;
    private static volatile long lastFlush = 0;

    /** Player chat, private messages and channel messages are not game text and are not recorded. */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Guild|Party|Officer|Co-op|Coop) > |(?:From|To) (?:\\[[A-Z+]+\\] )?[A-Za-z0-9_]{1,16}: "
            + "|(?:\\[\\d+\\] (?:\\S{1,3} )?)?\\[(?:VIP\\+?|MVP\\+{0,2}|YOUTUBE|ADMIN|GM|OWNER|MOD|HELPER|STAFF|PIG\\+{3}|INNIT|MCP|EVENTS)\\] [A-Za-z0-9_]{1,16}(?: \\S{1,4})?: "
            + "|\\[\\d+\\] (?:\\S{1,3} )?[A-Za-z0-9_]{1,16}(?: \\S{1,4})?: )");

    private TextCollector() {}

    public static boolean enabled() {
        try {
            return NPCTranslatorClient.collectEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!FILE.exists() && OLD_FILE.exists()) migrate();
        if (!FILE.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; if (line.startsWith("类别")) continue; }
                String[] p = line.split("\t", -1);
                if (p.length >= 3 && SEEN.add(p[0] + "\t" + p[2])) count(TOTAL, p[0]);
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] text file: " + e.getMessage());
        }
    }

    /** Old columns: 菜单, 类型 (名称/描述), 英文, 原文示例, 当前译文. */
    private static void migrate() {
        List<String> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(OLD_FILE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5 || p[0].equals("菜单")) continue;
                String type = p[1].equals("名称") ? ITEM_NAME : ITEM_LINE;
                rows.add(anonymize(type + "\t" + p[0] + "\t" + p[2] + "\t" + p[3] + "\t" + p[4]));
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] old menu text file: " + e.getMessage());
            return;
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
            w.write(HEADER);
            for (String row : rows) w.write(row + "\n");
        } catch (Exception e) {
            System.err.println("[NPCTranslator] text file: " + e.getMessage());
        }
    }

    /** A component that may hold several lines (Tab header, multi-line name tags). */
    public static void record(String type, String where, Component text) {
        if (text == null || !enabled()) return;
        try {
            String f = NPCTranslatorClient.toFormattedString(text);
            if (f.indexOf('\n') < 0) {
                record(type, where, f, null);
            } else {
                for (String line : f.split("\n")) record(type, where, line, null);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * One line of text. {@code current} is the translation shown now; when null it is looked up in
     * the local data (only for text not seen before).
     */
    public static void record(String type, String where, String formatted, String current) {
        if (formatted == null || type == null || !enabled()) return;
        String rawKey = type + "\t" + formatted;
        if (RAW.contains(rawKey)) return;
        if (RAW.size() > 50000) RAW.clear();
        RAW.add(rawKey);

        String plain = anonymize(TranslationUtil.stripColorCodes(formatted).trim());
        if (plain.isEmpty() || !TranslationUtil.needsTranslation(plain)) return;
        if (type.equals(CHAT) && !plain.startsWith("[NPC]") && PLAYER_CHAT.matcher(plain).find()) return;
        load();
        String template = TranslationUtil.normalize(plain).text;
        if (SEEN.size() > LIMIT || !SEEN.add(type + "\t" + template)) return;
        String cur = current;
        if (cur == null) cur = lookup(type, formatted.trim());
        cur = cur == null ? "" : anonymize(TranslationUtil.stripColorCodes(cur).trim());
        if (cur.equals(plain)) cur = "";
        PENDING.add(type + "\t" + clean(anonymize(where)) + "\t" + clean(template) + "\t" + clean(plain) + "\t" + clean(cur));
        count(TOTAL, type);
        count(ADDED, type);
        long now = System.currentTimeMillis();
        if (now - lastFlush > 5000) {
            lastFlush = now;
            Thread t = new Thread(TextCollector::flush, "NPCTranslator-TextCollector");
            t.setDaemon(true);
            t.start();
        }
    }

    private static String lookup(String type, String formatted) {
        try {
            String out = type.equals(ITEM_NAME) ? TableNames.translateName(formatted) : null;
            if (out == null) out = PhraseBook.translate(formatted);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Pattern selfName;
    private static volatile boolean selfNameLooked = false;

    /** The player's own name is written as {玩家}, so files can be shared without it. */
    static String anonymize(String text) {
        if (!selfNameLooked) {
            try {
                String name = net.minecraft.client.Minecraft.getInstance().getUser().getName();
                if (name != null && name.length() >= 2) {
                    selfName = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
                }
            } catch (Throwable ignored) {
            }
            selfNameLooked = true;
        }
        Pattern p = selfName;
        if (p == null || text == null || text.isEmpty()) return text;
        return p.matcher(text).replaceAll("{玩家}");
    }

    private static void count(Map<String, AtomicInteger> map, String type) {
        map.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public static synchronized void flush() {
        if (PENDING.isEmpty()) return;
        boolean header = !FILE.exists();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE, true), StandardCharsets.UTF_8)) {
            if (header) w.write(HEADER);
            String row;
            while ((row = PENDING.poll()) != null) w.write(row + "\n");
        } catch (Exception e) {
            System.err.println("[NPCTranslator] text file: " + e.getMessage());
        }
    }

    /** Deletes the file and starts over. */
    public static synchronized void reset() {
        load();
        PENDING.clear();
        SEEN.clear();
        RAW.clear();
        TOTAL.clear();
        ADDED.clear();
        if (FILE.exists() && !FILE.delete()) System.err.println("[NPCTranslator] could not delete " + FILE);
    }

    /** Category → [total in file, added this session], in a stable order. */
    public static Map<String, int[]> summary() {
        load();
        Map<String, int[]> out = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(TOTAL.keySet());
        keys.sort((a, b) -> TOTAL.get(b).get() - TOTAL.get(a).get());
        for (String k : keys) {
            AtomicInteger a = ADDED.get(k);
            out.put(k, new int[]{TOTAL.get(k).get(), a == null ? 0 : a.get()});
        }
        return out;
    }

    public static int total() {
        load();
        return SEEN.size();
    }
}
