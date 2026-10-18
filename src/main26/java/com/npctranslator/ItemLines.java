package com.npctranslator;

import com.npctranslator.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 中文增强版：物品描述的翻译计划。
 * <ul>
 *   <li>短语库 / 附魔列表先翻；</li>
 *   <li>Hypixel 把一句话折成好几行的，先拼回一整句再交给 AI（"View your Skill progression and" + "rewards."）；</li>
 *   <li>整页翻译时，多个物品的缺失句子合并去重后分批并发请求。</li>
 * </ul>
 */
public final class ItemLines {

    private ItemLines() {}

    /** Translation plan for one tooltip. */
    public static final class Plan {
        public final List<String> lines;
        /** Phrase book / enchant list results ({@link PhraseMatcher#MERGED} for absorbed lines). */
        public final String[] fixed;
        /** Dictionary template to look up for line i, null when the line needs no AI. */
        public final TranslationUtil.Template[] templates;
        /** Line i was joined into an earlier line (its text is part of that line's template). */
        public final boolean[] joined;

        Plan(List<String> lines) {
            this.lines = lines;
            int n = lines.size();
            fixed = new String[n];
            templates = new TranslationUtil.Template[n];
            joined = new boolean[n];
        }
    }

    /** Result for one tooltip: full translated tooltip plus the per-line replacement map. */
    public record Result(List<Component> tooltip, Map<String, List<Component>> lineMap, boolean changed) {}

    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?:。！？：]$");
    private static final Pattern LABEL_LINE = Pattern.compile("^[A-Z][A-Za-z' ]{0,25}:(\\s|$).*");
    private static final Pattern BULLET_START = Pattern.compile("^[•◆▸►»›*✦✧▪■□●○◇◈❖➤➜→\\-+].*");
    private static final Pattern COLOR = Pattern.compile("§[0-9a-fA-Fr]");

    public static Plan plan(List<String> lines, boolean usePhrasebook) {
        Plan p = new Plan(lines);
        ModConfig c = ModConfig.INSTANCE;
        int n = lines.size();
        if (usePhrasebook) {
            // right after start-up the phrase book is still loading: wait for it (we run off the render thread)
            for (int w = 0; w < 80 && !PhraseBook.isReady(); w++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            String[] pb = PhraseBook.translateLines(lines);
            System.arraycopy(pb, 0, p.fixed, 0, n);
        }
        boolean[] ai = new boolean[n];
        for (int i = 0; i < n; i++) {
            String parsed = lines.get(i);
            if (p.fixed[i] == null && !parsed.isEmpty()) p.fixed[i] = EnchantHints.translateList(parsed);
            ai[i] = !parsed.isEmpty() && p.fixed[i] == null && TranslationUtil.needsTranslation(parsed);
        }
        // Wrapped sentences: join consecutive AI lines that continue the previous one.
        int i = 0;
        while (i < n) {
            if (!ai[i]) { i++; continue; }
            int j = i;
            int len = plain(lines.get(i)).length();
            while (j + 1 < n && j - i < 5 && ai[j + 1] && continues(lines.get(j), lines.get(j + 1)) && len < 300) {
                j++;
                len += plain(lines.get(j)).length();
            }
            String text;
            if (j > i) {
                StringBuilder sb = new StringBuilder(lines.get(i));
                for (int k = i + 1; k <= j; k++) {
                    sb.append(' ').append(lines.get(k).replaceFirst("^[\\s  ]+", ""));
                    p.joined[k] = true;
                }
                text = sb.toString();
            } else {
                text = lines.get(i);
            }
            p.templates[i] = c.normalizeNumbers ? TranslationUtil.normalize(text) : TranslationUtil.Template.raw(text);
            i = j + 1;
        }
        return p;
    }

    private static String plain(String formatted) {
        return TranslationUtil.stripColorCodes(formatted).trim();
    }

    /** Whether {@code next} is the wrapped continuation of the sentence in {@code prev}. */
    static boolean continues(String prev, String next) {
        String a = plain(prev), b = plain(next);
        if (a.length() < 18 || b.isEmpty()) return false;
        if (SENTENCE_END.matcher(a).matches()) return false;
        if (LABEL_LINE.matcher(a).matches() || LABEL_LINE.matcher(b).matches()) return false;
        if (BULLET_START.matcher(b).matches()) return false;
        // the previous line must look unfinished: ends with a lowercase letter, comma, or the next starts lowercase
        char last = a.charAt(a.length() - 1);
        char first = b.charAt(0);
        boolean unfinished = Character.isLowerCase(last) || last == ',' || last == '，' || Character.isLowerCase(first);
        if (!unfinished) return false;
        // same colour at the join (lore paragraphs keep one base colour) ...
        String lead = leadColor(next);
        if (lead.isEmpty() || lead.equalsIgnoreCase(lastColor(prev)) || lead.equalsIgnoreCase(leadColor(prev))) return true;
        // ... or a highlighted value/word that clearly continues the sentence ("breaks after" + "§a2,000§7 uses.")
        boolean prevOpen = Character.isLowerCase(last) || last == ',';
        return prevOpen && (Character.isLowerCase(first) || Character.isDigit(first));
    }

    private static String leadColor(String s) {
        String t = s.replaceFirst("^[\\s  ]+", "");
        String col = "";
        int i = 0;
        while (i + 1 < t.length() && t.charAt(i) == '§') {
            String code = t.substring(i, i + 2);
            if (COLOR.matcher(code).matches()) col = code;
            i += 2;
        }
        return col;
    }

    private static String lastColor(String s) {
        java.util.regex.Matcher m = COLOR.matcher(s);
        String col = "";
        while (m.find()) col = m.group();
        return col;
    }

    /** Templates of the plan that are not in the dictionary yet. */
    public static void collectMissing(Plan p, String providerName, Collection<String> into) {
        for (TranslationUtil.Template t : p.templates) {
            if (t != null && !TranslationDictionary.has(t.text, providerName)) into.add(t.text);
        }
    }

    /**
     * Translate templates with the AI in batches of {@code batchSize}, up to {@code parallel} requests at
     * once; results go into the dictionary. Throws the first error if nothing at all succeeded.
     */
    public static int translateMissing(Collection<String> missing, ModConfig.TranslationProvider provider,
                                       int batchSize, int parallel) throws Exception {
        return translateMissing(missing, provider, batchSize, parallel, null);
    }

    /** Same, reporting the number of lines handled after every finished request. */
    public static int translateMissing(Collection<String> missing, ModConfig.TranslationProvider provider,
                                       int batchSize, int parallel, java.util.function.IntConsumer progress) throws Exception {
        List<String> all = new ArrayList<>(new LinkedHashSet<>(missing));
        if (all.isEmpty()) return 0;
        String langCode = NPCTranslatorClient.getEffectiveLanguageCode();
        String targetLang = NPCTranslatorClient.targetLanguageName(langCode);
        String providerName = provider.name();
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += batchSize) chunks.add(all.subList(i, Math.min(all.size(), i + batchSize)));
        Map<Integer, Exception> errors = new ConcurrentHashMap<>();
        int[] ok = {0};
        for (int start = 0; start < chunks.size(); start += parallel) {
            List<CompletableFuture<Void>> wave = new ArrayList<>();
            for (int k = start; k < Math.min(chunks.size(), start + parallel); k++) {
                final int idx = k;
                final List<String> chunk = chunks.get(k);
                wave.add(CompletableFuture.runAsync(() -> {
                    try {
                        Map<Integer, String> res = NPCTranslatorClient.translateBatch(chunk, langCode, targetLang, provider);
                        int got = 0;
                        for (Map.Entry<Integer, String> e : res.entrySet()) {
                            String template = chunk.get(e.getKey());
                            if (TranslationUtil.placeholdersMatch(template, e.getValue())) {
                                TranslationDictionary.put(template, e.getValue(), providerName);
                                got++;
                            }
                        }
                        synchronized (ok) { ok[0] += got; }
                    } catch (Exception e) {
                        errors.put(idx, e);
                    } finally {
                        if (progress != null) progress.accept(chunk.size());
                    }
                }));
            }
            CompletableFuture.allOf(wave.toArray(new CompletableFuture[0])).join();
        }
        if (ok[0] > 0) TranslationDictionary.save();
        if (ok[0] == 0 && !errors.isEmpty()) throw errors.values().iterator().next();
        return ok[0];
    }

    /** Build the translated tooltip from the plan and the dictionary. */
    public static Result assemble(Plan p, ModConfig.TranslationProvider provider, String[] rawOverride) {
        String providerName = provider.name();
        int n = p.lines.size();
        List<Component> translated = new ArrayList<>();
        Map<String, List<Component>> lineMap = new ConcurrentHashMap<>();
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            String original = p.lines.get(i);
            if (p.fixed[i] == PhraseMatcher.MERGED || p.joined[i]) {
                lineMap.putIfAbsent(original, List.of());
                changed = true;
                continue;
            }
            if (p.fixed[i] != null) {
                List<Component> wrapped = wrap(p.fixed[i]);
                translated.addAll(wrapped);
                lineMap.put(original, wrapped);
                changed = true;
                continue;
            }
            if (original.isEmpty()) {
                translated.add(Component.empty());
                continue;
            }
            String out = rawOverride != null ? rawOverride[i] : null;
            TranslationUtil.Template t = p.templates[i];
            if (out == null && t != null) {
                String tr = TranslationDictionary.get(t.text, providerName);
                if (tr != null && !tr.isEmpty()) out = TranslationUtil.restore(tr, t.numbers);
            }
            if (out != null && !out.isEmpty()) {
                boolean multi = t != null && i + 1 < n && p.joined[i + 1];
                List<Component> comps = multi ? wrap(out) : List.of(NPCTranslatorClient.parseColorCodes(out));
                translated.addAll(comps);
                lineMap.put(original, comps);
                changed = true;
            } else {
                translated.add(NPCTranslatorClient.parseColorCodes(original));
                // joined continuation lines whose sentence was not translated: show them again
                for (int k = i + 1; k < n && p.joined[k]; k++) {
                    translated.add(NPCTranslatorClient.parseColorCodes(p.lines.get(k)));
                    lineMap.remove(p.lines.get(k));
                }
            }
        }
        // joined lines of an untranslated sentence must stay visible
        for (int i = 0; i < n; i++) {
            if (!p.joined[i]) continue;
            int head = i;
            while (head > 0 && p.joined[head]) head--;
            if (!lineMap.containsKey(p.lines.get(head))) lineMap.remove(p.lines.get(i));
        }
        return new Result(translated, lineMap, changed);
    }

    private static List<Component> wrap(String formatted) {
        List<Component> out = new ArrayList<>();
        for (String w : PhraseMatcher.wrap(formatted, 64)) out.add(NPCTranslatorClient.parseColorCodes(w));
        return out;
    }
}
