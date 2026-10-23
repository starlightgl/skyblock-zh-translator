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
    /** A description sentence wrapped over several lines, joined: translated as one entry. */
    public static final String ITEM_SENTENCE = "物品描述（整句）";
    public static final String WORLD = "名称与悬浮文字";
    public static final String MOD_TEXT = "其他模组提示";
    public static final String PRICE_TABLE = "价格表格";

    public static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_text.tsv");
    /** File of 1.5.1-zh.3-beta.5, which only held menu items. Converted on first load. */
    private static final File OLD_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_menu_text.tsv");
    private static final String HEADER = "类别\t位置\t英文（数字为 {0}）\t原文示例\t当前译文\n";
    private static final int LIMIT = 200000;
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /** category + template already written. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    /** category + raw text already handled: lets per-frame callers (Tab list, sidebar) skip the normalising work. */
    private static final Set<String> RAW = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicInteger> TOTAL = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> ADDED = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile boolean loaded = false;
    private static volatile long lastFlush = 0;
    private static final java.util.concurrent.atomic.AtomicLong CAPTURE_GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor CAPTURES = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8192), r -> {
                Thread t = new Thread(r, "NPCTranslator-TextCapture"); t.setDaemon(true); return t;
            });
    private static final java.util.concurrent.ScheduledExecutorService FLUSHER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NPCTranslator-TextFlush"); t.setDaemon(true); return t;
    });
    static {
        // Persist the last few captures even after the player stops hovering new text.
        FLUSHER.scheduleWithFixedDelay(TextCollector::flush, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

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
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; if (line.startsWith("类别")) continue; }
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                // rows written by earlier versions: drop this mod's own Chinese output, hide the player's name
                if (hasChinese(p[2])) { changed = true; continue; }
                if (p[0].equals(TAB) && p.length > 3 && RANDOM_NAME.matcher(p[3]).matches()) { changed = true; continue; }
                String clean = anonymize(line);
                if (!clean.equals(line)) { changed = true; p = clean.split("\t", -1); }
                String level = OrderBookText.collectionTemplate(p[2]);
                String folded = level == null ? foldItems(p[2]) : level;
                if (!folded.equals(p[2])) {
                    changed = true;
                    p[2] = folded;
                    clean = String.join("\t", p);
                }
                if (SEEN.add(p[0] + "\t" + p[2])) {
                    count(TOTAL, p[0]);
                    kept.add(clean);
                } else {
                    changed = true;
                }
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] text file: " + e.getMessage());
            return;
        }
        if (changed) {
            try {
                java.nio.file.Files.copy(FILE.toPath(), new File(FILE.getPath() + ".bak").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                System.err.println("[NPCTranslator] text file backup: " + e.getMessage());
                return;
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
                w.write(HEADER);
                for (String row : kept) w.write(row + "\n");
            } catch (Exception e) {
                System.err.println("[NPCTranslator] text file: " + e.getMessage());
            }
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

    /** Records each wrapped sentence of a tooltip (lines 1..n) as one joined entry. */
    public static void recordSentences(String where, List<String> lines) {
        if (lines == null || lines.size() < 3 || !enabled()) return;
        try {
            int i = 1;
            while (i < lines.size()) {
                int j = i;
                while (j + 1 < lines.size() && j - i < 7 && PhraseMatcher.continuesLine(lines.get(j), lines.get(j + 1))) j++;
                if (j > i) {
                    StringBuilder sb = new StringBuilder(lines.get(i).trim());
                    for (int k = i + 1; k <= j; k++) sb.append(' ').append(lines.get(k).trim());
                    record(ITEM_SENTENCE, where, sb.toString(), null);
                }
                i = j + 1;
            }
        } catch (Throwable ignored) {
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
        if (hasChinese(formatted)) return; // Reject translated output before it can occupy the worker queue.
        String level = OrderBookText.collectionTemplate(formatted);
        String rawKey = type + "\t" + (level == null ? formatted : level);
        if (RAW.size() > 50000) RAW.clear();
        if (!RAW.add(rawKey)) return;
        long generation = CAPTURE_GENERATION.get();
        try {
            CAPTURES.execute(() -> recordCaptured(type, where, formatted, current, generation));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            RAW.remove(rawKey); // Retry on the next display; never block or grow an unbounded queue.
        }
    }

    private static synchronized void recordCaptured(String type, String where, String formatted, String current, long generation) {
        if (generation != CAPTURE_GENERATION.get()) return;

        String plain = anonymize(TranslationUtil.stripColorCodes(formatted).trim());
        if (plain.isEmpty() || !TranslationUtil.needsTranslation(plain)) return;
        // lines that already contain Chinese are this mod's own output (translations, hints), not source text
        if (hasChinese(plain)) return;
        if (type.equals(CHAT) && !plain.startsWith("[NPC]") && PLAYER_CHAT.matcher(plain).find()) return;
        load();
        String level = OrderBookText.collectionTemplate(plain);
        String template = level == null ? foldItems(TranslationUtil.normalize(plain).text) : level;
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
            flush();
        }
    }

    private static String lookup(String type, String formatted) {
        try {
            String priceLevel = OrderBookText.translate(formatted);
            if (priceLevel != null) return priceLevel;
            String out = type.equals(ITEM_NAME) ? TableNames.translateName(formatted) : null;
            if (out == null) out = PhraseBook.translate(formatted);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Profile names Hypixel gives to fake Tab entries (e.g. "7s8ju6w488"). */
    private static final Pattern RANDOM_NAME = Pattern.compile("(?=.*\\d)(?=.*[a-z])[a-z0-9]{8,12}");
    private static final Pattern NAME_END = Pattern.compile("$|[!.,:;)\\]]| (?:for|to|from|at|each|in|and|with|was|has|have|is)\\b");
    private static final Pattern COUNT_X = Pattern.compile("\\{\\d+\\}x ");

    private static boolean hasChinese(String s) {
        return CJK.matcher(s.replace("{玩家}", "").replace("{物品}", "")).find();
    }

    /**
     * "Instant Sell of {1}x Enchanted Raw Salmon" → "Instant Sell of {1}x {物品}" when the words after
     * the count are an item name from the term table, so bazaar / auction lines are recorded once per
     * pattern instead of once per item.
     */
    static String foldItems(String template) {
        if (template == null || template.indexOf("x ") < 0) return template;
        try {
            java.util.regex.Matcher m = COUNT_X.matcher(template);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                if (m.start() < last) continue;
                int start = m.end();
                String rest = template.substring(start);
                String[] words = rest.split(" ", 8);
                int n = Math.min(6, words.length);
                for (int k = n; k >= 1; k--) {
                    String cand = String.join(" ", java.util.Arrays.copyOf(words, k));
                    String trimmed = cand.replaceAll("[!.,:]+$", "");
                    if (trimmed.isEmpty() || TableNames.exact(trimmed) == null) continue;
                    // the name must end the phrase ("… for", "!", end of line), not be a prefix like "Fine"
                    if (!NAME_END.matcher(rest.substring(trimmed.length())).lookingAt()) continue;
                    sb.append(template, last, start).append("{物品}");
                    last = start + trimmed.length();
                    break;
                }
            }
            if (last == 0) return template;
            sb.append(template.substring(last));
            return sb.toString();
        } catch (Throwable t) {
            return template;
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
        CAPTURE_GENERATION.incrementAndGet();
        CAPTURES.getQueue().clear();
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
