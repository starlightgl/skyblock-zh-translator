package com.npctranslator;

import com.npctranslator.config.ModConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Scoreboard sidebar + the item name shown above the hotbar when switching items.
 *
 * Rendering never waits for the network: a line is drawn translated only when a translation is
 * already known (phrase book, dictionary filled by item tooltips, or an earlier background batch).
 * Unknown sidebar lines / item names are queued and translated in the background every 2 s.
 */
public final class HudTranslator {

    private static final Pattern HAS_WORD = Pattern.compile(".*[A-Za-z]{2,}.*");
    private static final Map<String, Component> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static ScheduledExecutorService executor;

    private HudTranslator() {}

    // ── /translate debug ──
    public static volatile boolean debug = false;
    private static final Set<String> DEBUG_SEEN = ConcurrentHashMap.newKeySet();

    /** Log one line of the translation process (each distinct message once). */
    public static void debugLog(String msg) {
        if (!debug) return;
        if (DEBUG_SEEN.size() > 4000) DEBUG_SEEN.clear();
        if (DEBUG_SEEN.add(msg)) System.out.println("[NPCTranslator-debug] " + msg);
    }

    public static synchronized void init() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NPCTranslator-HUD");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(HudTranslator::flushSafely, 3, 2, TimeUnit.SECONDS);
    }

    /** Called when the dictionary is cleared so stale rendered lines disappear. */
    public static void clearCache() {
        CACHE.clear();
        FAILED.clear();
        WORLD_CACHE.clear();
        WORLD_MISS.clear();
    }

    private static ModConfig cfg() {
        return ModConfig.INSTANCE;
    }

    private static ModConfig.TranslationProvider provider() {
        ModConfig c = cfg();
        return c.autoItemProvider != null ? c.autoItemProvider : ModConfig.TranslationProvider.CUSTOM;
    }

    // ── Sidebar ────────────────────────────────────────────────────────────

    public static MutableComponent sidebarRow(Team team, Component name) {
        MutableComponent original = PlayerTeam.formatNameForTeam(team, name);
        TextCollector.record(TextCollector.SIDEBAR, "", original);
        ModConfig c = cfg();
        if (c == null || !c.enabled || !c.translateSidebar) return original;
        try {
            Component t = translateLine(original);
            return t == null ? original : t.copy();
        } catch (Exception e) {
            return original;
        }
    }

    public static Component sidebarTitle(Objective objective) {
        Component original = objective.getDisplayName();
        TextCollector.record(TextCollector.SIDEBAR, "标题", original);
        ModConfig c = cfg();
        if (c == null || !c.enabled || !c.translateSidebar) return original;
        try {
            Component t = translateLine(original);
            return t == null ? original : t;
        } catch (Exception e) {
            return original;
        }
    }

    /** Translation of one HUD line, or null if none is known yet (then it is queued). */
    private static Component translateLine(Component original) {
        String formatted = NPCTranslatorClient.toFormattedString(original);
        if (formatted.isEmpty()) return null;
        String deterministic = cHudTranslation(formatted, "SIDEBAR");
        if (deterministic != null) return remember(formatted, deterministic);
        Component cached = CACHE.get(formatted);
        if (cached != null) return cached;
        if (!TranslationUtil.needsTranslation(formatted)) return null;

        ModConfig c = cfg();
        String out = c.usePhrasebook ? PhraseBook.translate(formatted) : null;
        if (out == null) out = EnchantHints.translateList(formatted);
        if (out == null) {
            TranslationUtil.Template t = c.normalizeNumbers ? TranslationUtil.normalize(formatted) : TranslationUtil.Template.raw(formatted);
            String tr = TranslationDictionary.get(t.text, provider().name());
            if (tr != null && !tr.isEmpty()) {
                out = TranslationUtil.restore(tr, t.numbers);
            } else if (c.hudUseAI) {
                enqueue(t.text);
            }
        }
        Coverage.record(formatted, out != null, "侧边栏");
        return remember(formatted, out);
    }

    private static String cHudTranslation(String formatted, String surface) {
        ModConfig c = cfg();
        return c != null && c.usePhrasebook ? HudText.translate(formatted, surface) : null;
    }

    /** Player identities are excluded by the Tab hook before reaching this method. */
    public static Component tabText(Component original) { return hudText(original, "TAB"); }
    public static Component overlayText(Component original) { return hudText(original, "OVERLAY"); }
    public static Component regionText(Component original) { return hudText(original, "REGION"); }
    public static Component screenTitle(Component original) { return hudText(original, "TITLE"); }
    /** Boss bar text: quest objectives and timed events; anything else is left as it is. */
    public static Component bossBar(Component original) {
        ModConfig c = cfg();
        if (original == null || c == null || !c.enabled || !c.usePhrasebook) return original;
        String formatted = NPCTranslatorClient.toFormattedString(original);
        String key = "BOSS:" + formatted;
        Component cached = WORLD_CACHE.get(key);
        if (cached != null) return cached;
        String out = HudText.boss(formatted);
        if (out == null) out = RuntimeText.event(formatted);
        Coverage.record(formatted, out != null, "Boss血条");
        Component shown = out == null ? original : NPCTranslatorClient.parseColorCodes(out);
        if (WORLD_CACHE.size() > 3000) WORLD_CACHE.clear();
        WORLD_CACHE.put(key, shown);
        return shown;
    }
    public static Component containerTitle(Component original) {
        ModConfig c=cfg();
        if(original==null || c==null || !c.enabled || !c.usePhrasebook)return original;
        String raw=NPCTranslatorClient.toFormattedString(original), key="MENU:"+raw;
        Component cached=WORLD_CACHE.get(key);if(cached!=null)return cached;
        String out=RuntimeText.event(raw);
        if(out==null)out=RuntimeText.menu(raw);
        Component shown=out==null?original:NPCTranslatorClient.parseColorCodes(out);
        if(WORLD_CACHE.size()>3000)WORLD_CACHE.clear();WORLD_CACHE.put(key,shown);return shown;
    }
    private static Component hudText(Component original, String surface) {
        ModConfig c = cfg();
        if (original == null || c == null || !c.enabled || !c.usePhrasebook) return original;
        String formatted = NPCTranslatorClient.toFormattedString(original);
        String key = "HUD:" + surface + ":" + formatted;
        Component cached = WORLD_CACHE.get(key);
        if (cached != null) return cached;
        String out = HudText.translate(formatted, surface);
        Coverage.record(formatted, out != null, surface);
        if (out == null) return original;
        Component translated = NPCTranslatorClient.parseColorCodes(out);
        if (WORLD_CACHE.size() > 3000) WORLD_CACHE.clear();
        WORLD_CACHE.put(key, translated);
        return translated;
    }

    /** Where the text being looked up is shown, for the coverage report. */
    private static final ThreadLocal<String> WHERE = new ThreadLocal<>();

    private static Component remember(String formatted, String out) {
        if (out == null) return null;
        Component comp = NPCTranslatorClient.parseColorCodes(out);
        if (CACHE.size() > 3000) CACHE.clear();
        CACHE.put(formatted, comp);
        return comp;
    }

    // ── Floating text in the world (NPC names/subtitles, holograms, text displays) ──

    private static final Map<String, Component> WORLD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> WORLD_MISS = new ConcurrentHashMap<>();

    /**
     * Translation of a name tag / text display, or null if none is known yet. Multi-line text is
     * translated line by line. Misses are re-checked at most every 2 s (background AI results
     * land in the dictionary and are picked up then).
     */
    public static Component worldText(Component original) {
        TextCollector.record(TextCollector.WORLD, "", original);
        ModConfig c = cfg();
        if (c == null || !c.enabled || !c.translateWorldText || original == null) return null;
        WHERE.set("名称与悬浮文字");
        try {
            return lookupComponent(original);
        } finally {
            WHERE.remove();
        }
    }

    /** Text added by other mods (price lines, button tooltips); same lookup as floating text. */
    public static Component modText(Component original) {
        collectModText(original);
        ModConfig c = cfg();
        if (c == null || !c.enabled || !c.translateModTooltips || original == null) return null;
        WHERE.set("其他模组提示");
        try {
            if (isGrid(original)) {
                WHERE.set("价格表格");
                return gridText(original);
            }
            return lookupComponent(original);
        } finally {
            WHERE.remove();
        }
    }

    // ── Skyblocker 的表格行（价格等）：内容放在 GridComponent$Contents 里，getString() 是空的 ──
    private static volatile Class<?> gridClass;
    private static volatile java.lang.reflect.Method gridGroup, gridCells;
    private static volatile java.lang.reflect.Constructor<?> gridCtor;
    private static volatile boolean gridBroken = false;

    /** Whether a tooltip line is one of Skyblocker's grid (table) lines. */
    public static boolean isGrid(Component line) {
        if (line == null || gridBroken) return false;
        Object contents = line.getContents();
        if (contents == null) return false;
        Class<?> k = contents.getClass();
        if (k == gridClass) return true;
        if (gridClass != null || !k.getName().endsWith("GridComponent$Contents")) return false;
        try {
            gridGroup = k.getMethod("group");
            gridCells = k.getMethod("components");
            gridCtor = k.getConstructor(String.class, List.class);
            gridClass = k;
            return true;
        } catch (Throwable t) {
            gridBroken = true;
            debugLog("grid line not supported: " + t);
            return false;
        }
    }

    /** Records a mod tooltip line; price table rows are recorded cell by cell. */
    @SuppressWarnings("unchecked")
    public static void collectModText(Component line) {
        if (line == null || !TextCollector.enabled()) return;
        try {
            if (isGrid(line)) {
                for (Component cell : (List<Component>) gridCells.invoke(line.getContents())) {
                    if (cell == null) continue;
                    if (isGrid(cell)) collectModText(cell);
                    else TextCollector.record(TextCollector.PRICE_TABLE, "", cell);
                }
            } else {
                TextCollector.record(TextCollector.MOD_TEXT, "", line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** A grid line with each cell translated, or null when no cell has a translation yet. */
    @SuppressWarnings("unchecked")
    private static Component gridText(Component line) {
        try {
            Object contents = line.getContents();
            String group = (String) gridGroup.invoke(contents);
            List<Component> cells = (List<Component>) gridCells.invoke(contents);
            List<Component> out = new ArrayList<>(cells.size());
            boolean any = false;
            for (Component cell : cells) {
                Component t = cell == null ? null : (isGrid(cell) ? gridText(cell) : lookupComponent(cell));
                if (debug && cell != null) debugLog("grid cell: " + NPCTranslatorClient.toFormattedString(cell) + (t != null ? " => " + NPCTranslatorClient.toFormattedString(t) : " (not yet)"));
                if (t != null) any = true;
                out.add(t != null ? t : cell);
            }
            if (!any) return null;
            MutableComponent m = MutableComponent.create((net.minecraft.network.chat.ComponentContents) gridCtor.newInstance(group, List.copyOf(out)));
            m.setStyle(line.getStyle());
            for (Component s : line.getSiblings()) m.append(s);
            return m;
        } catch (Throwable t) {
            debugLog("grid line failed: " + t);
            return null;
        }
    }

    private static Component lookupComponent(Component original) {
        try {
            String formatted = NPCTranslatorClient.toFormattedString(original);
            if (formatted.isEmpty()) return null;
            String contextKey = String.valueOf(WHERE.get()) + ":" + formatted;
            Component cached = WORLD_CACHE.get(contextKey);
            if (cached != null) return cached;
            long now = System.currentTimeMillis();
            Long missAt = WORLD_MISS.get(contextKey);
            if (missAt != null && now - missAt < 2000L) return null;

            String out;
            if (formatted.indexOf('\n') >= 0) {
                String[] lines = formatted.split("\n", -1);
                boolean any = false;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.length; i++) {
                    String tr = lookupLine(lines[i]);
                    if (tr != null) any = true;
                    if (i > 0) sb.append('\n');
                    sb.append(tr != null ? tr : lines[i]);
                }
                out = any ? sb.toString() : null;
            } else {
                out = lookupLine(formatted);
            }
            if (out == null) {
                if (WORLD_MISS.size() > 5000) WORLD_MISS.clear();
                WORLD_MISS.put(contextKey, now);
                return null;
            }
            WORLD_MISS.remove(contextKey);
            Component comp = NPCTranslatorClient.parseColorCodes(out);
            if (WORLD_CACHE.size() > 3000) WORLD_CACHE.clear();
            WORLD_CACHE.put(contextKey, comp);
            return comp;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether worldText would now return a translation (used to refresh cached text displays). */
    public static boolean hasWorldText(Component original) {
        return worldText(original) != null;
    }

    private static String lookupLine(String formatted) {
        String out = lookupLine0(formatted);
        Coverage.record(formatted, out != null, WHERE.get());
        return out;
    }

    /** Phrase book, then dictionary; unknown lines are queued for the background AI batch. */
    private static String lookupLine0(String formatted) {
        if (formatted.isEmpty()) return null;
        if (!TranslationUtil.needsTranslation(formatted)) { debugLog("skip (no English): " + formatted); return null; }
        ModConfig c = cfg();
        if (c.usePhrasebook && "名称与悬浮文字".equals(WHERE.get())) {
            String name = ScopedNames.world(formatted);
            if (name != null) return name;
        }
        String region = cHudTranslation(formatted, "LOCATION");
        if (region != null) return region;
        String rule = PatternRules.translate(formatted);
        if (rule != null) return rule;
        String out = c.usePhrasebook ? PhraseBook.translate(formatted) : null;
        if (out != null) { debugLog("phrasebook: " + formatted + " => " + out); return out; }
        out = EnchantHints.translateList(formatted);
        if (out != null) { debugLog("enchant list: " + formatted + " => " + out); return out; }
        out = ItemLines.bulletName(formatted);
        if (out != null) { debugLog("name: " + formatted + " => " + out); return out; }
        TranslationUtil.Template t = c.normalizeNumbers ? TranslationUtil.normalize(formatted) : TranslationUtil.Template.raw(formatted);
        String tr = TranslationDictionary.get(t.text, provider().name());
        if (tr != null && !tr.isEmpty()) { debugLog("dictionary: " + t.text + " => " + tr); return TranslationUtil.restore(tr, t.numbers); }
        if (c.hudUseAI) {
            enqueue(t.text);
            debugLog("queued for AI (" + provider() + "): " + t.text + (FAILED.containsKey(t.text) ? " [failed recently]" : ""));
        } else {
            debugLog("not translated (hudUseAI off): " + t.text);
        }
        return null;
    }

    // ── Item name above the hotbar ───────────────────────────────────────────

    public static void selectedItemName(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color) {
        Component draw = null;
        ModConfig c = cfg();
        if (c != null && c.enabled && c.translateHeldItemName) {
            try {
                draw = translateItemName(text);
            } catch (Exception ignored) {
                draw = null;
            }
        }
        if (draw == null) {
            graphics.textWithBackdrop(font, text, x, y, width, color);
            return;
        }
        int w = font.width(draw);
        // x was the left edge for the old width; keep the same centre for the new width.
        graphics.textWithBackdrop(font, draw, x + (width - w) / 2, y, w, color);
    }

    /** Reuse what the inventory tooltip translation already stored for this name line. */
    private static Component translateItemName(Component text) {
        String formatted = NPCTranslatorClient.toFormattedString(text).trim();
        if (formatted.isEmpty()) return null;
        Component cached = CACHE.get(formatted);
        if (cached != null) return cached;
        if (!TranslationUtil.needsTranslation(formatted)) return null;

        ModConfig c = cfg();
        // the same order as the name line of the item's tooltip, so both show the same name
        String named = TableNames.translateReforgedName(formatted);
        if (named == null && c.usePhrasebook) named = PhraseBook.translate(formatted);
        if (named == null) named = TableNames.translateName(formatted);
        if (named == null) named = ItemLines.bulletName(formatted);
        if (named == null) named = ItemLines.looseName(formatted);
        if (named != null) return remember(formatted, named);
        TranslationUtil.Template t = c.normalizeNumbers ? TranslationUtil.normalize(formatted) : TranslationUtil.Template.raw(formatted);
        // 1) dictionary written by tooltip translation (preferred provider first, then any provider)
        String tr = TranslationDictionary.get(t.text, provider().name());
        if (tr == null || tr.isEmpty()) {
            for (ModConfig.TranslationProvider p : ModConfig.TranslationProvider.values()) {
                tr = TranslationDictionary.get(t.text, p.name());
                if (tr != null && !tr.isEmpty()) break;
            }
        }
        String out = (tr != null && !tr.isEmpty()) ? TranslationUtil.restore(tr, t.numbers) : null;
        // 2) phrase book, 3) glossary exact name
        if (out == null && c.usePhrasebook) out = PhraseBook.translate(formatted);
        if (out == null && c.useGlossary) {
            String plain = TranslationUtil.stripColorCodes(formatted).trim();
            String g = Glossary.index().exact(plain);
            if (g != null && !g.equals(plain)) {
                String lead = formatted.startsWith("§") ? leadingCodes(formatted) : "";
                out = lead + g;
            }
        }
        if (out == null && c.hudUseAI) enqueue(t.text);
        return remember(formatted, out);
    }

    private static String leadingCodes(String formatted) {
        int i = 0;
        while (i + 1 < formatted.length() && formatted.charAt(i) == '§') i += 2;
        return formatted.substring(0, i);
    }

    // ── Background batch translation ─────────────────────────────────────────

    private static void enqueue(String template) {
        if (!NPCTranslatorClient.aiAllowed()) return;
        Long failedAt = FAILED.get(template);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 120_000L) return;
        if (PENDING.add(template)) QUEUE.add(template);
    }

    private static void flushSafely() {
        List<String> batch = new ArrayList<>();
        try {
            ModConfig c = cfg();
            if (c == null || !c.enabled || QUEUE.isEmpty() || !NPCTranslatorClient.aiAllowed()) return;
            ModConfig.TranslationProvider p = provider();
            if (!NPCTranslatorClient.isProviderUsable(p)) return;
            String s;
            while (batch.size() < 40 && (s = QUEUE.poll()) != null) batch.add(s);
            if (batch.isEmpty()) return;
            String lang = NPCTranslatorClient.getEffectiveLanguageCode();
            Map<Integer, String> res = NPCTranslatorClient.translateBatch(batch, lang, NPCTranslatorClient.targetLanguageName(lang), p);
            boolean changed = false;
            long now = System.currentTimeMillis();
            for (int i = 0; i < batch.size(); i++) {
                String tpl = batch.get(i);
                String tr = res.get(i);
                if (tr != null && TranslationUtil.placeholdersMatch(tpl, tr)) {
                    TranslationDictionary.put(tpl, tr, p.name());
                    changed = true;
                    debugLog("AI ok: " + tpl + " => " + tr);
                } else {
                    FAILED.put(tpl, now);
                    debugLog("AI rejected: " + tpl + " => " + tr);
                }
                PENDING.remove(tpl);
            }
            if (changed) TranslationDictionary.save();
        } catch (Throwable e) {
            long now = System.currentTimeMillis();
            for (String tpl : batch) {
                FAILED.put(tpl, now);
                PENDING.remove(tpl);
            }
            System.err.println("[NPCTranslator] HUD batch failed: " + e.getMessage());
            debugLog("AI batch failed (" + batch.size() + " lines): " + e);
        }
    }
}
