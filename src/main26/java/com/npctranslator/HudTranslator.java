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
    }

    private static ModConfig cfg() {
        return ModConfig.INSTANCE;
    }

    private static ModConfig.TranslationProvider provider() {
        ModConfig c = cfg();
        return c.autoItemProvider != null ? c.autoItemProvider : ModConfig.TranslationProvider.GOOGLE;
    }

    // ── Sidebar ────────────────────────────────────────────────────────────

    public static MutableComponent sidebarRow(Team team, Component name) {
        MutableComponent original = PlayerTeam.formatNameForTeam(team, name);
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
        Component cached = CACHE.get(formatted);
        if (cached != null) return cached;
        if (!HAS_WORD.matcher(TranslationUtil.stripColorCodes(formatted)).matches()) return null;

        ModConfig c = cfg();
        String out = c.usePhrasebook ? PhraseBook.translate(formatted) : null;
        if (out == null) {
            TranslationUtil.Template t = c.normalizeNumbers ? TranslationUtil.normalize(formatted) : TranslationUtil.Template.raw(formatted);
            String tr = TranslationDictionary.get(t.text, provider().name());
            if (tr != null && !tr.isEmpty()) {
                out = TranslationUtil.restore(tr, t.numbers);
            } else if (c.hudUseAI) {
                enqueue(t.text);
            }
        }
        return remember(formatted, out);
    }

    private static Component remember(String formatted, String out) {
        if (out == null) return null;
        Component comp = NPCTranslatorClient.parseColorCodes(out);
        if (CACHE.size() > 3000) CACHE.clear();
        CACHE.put(formatted, comp);
        return comp;
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
        if (!HAS_WORD.matcher(TranslationUtil.stripColorCodes(formatted)).matches()) return null;

        ModConfig c = cfg();
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
        Long failedAt = FAILED.get(template);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 120_000L) return;
        if (PENDING.add(template)) QUEUE.add(template);
    }

    private static void flushSafely() {
        List<String> batch = new ArrayList<>();
        try {
            ModConfig c = cfg();
            if (c == null || !c.enabled || QUEUE.isEmpty()) return;
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
                } else {
                    FAILED.put(tpl, now);
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
        }
    }
}
