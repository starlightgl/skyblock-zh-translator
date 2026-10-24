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
        String[] filter = usePhrasebook ? FilterMenuText.overrides(lines) : null;
        if (usePhrasebook) {
            // right after start-up the phrase book is still loading: wait for it (we run off the render thread)
            for (int w = 0; w < 80 && !PhraseBook.isReady(); w++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            String[] pb = FilterMenuText.translateLines(lines, filter);
            System.arraycopy(pb, 0, p.fixed, 0, n);
            // Shard-only canonical paragraphs override partial generic matches. Other tooltips keep their current path.
            String[] shards = ShardTooltipTranslator.translateLines(lines);
            for (int k = 0; k < n; k++) if (shards[k] != null) p.fixed[k] = shards[k];
        }
        if (usePhrasebook) {
            String rabbitName = FactionRabbitNames.tooltipName(lines);
            if (rabbitName != null) p.fixed[0] = rabbitName;
        }
        boolean[] ai = new boolean[n];
        for (int i = 0; i < n; i++) {
            String parsed = lines.get(i);
            String rule = filter != null && filter[i] != null ? filter[i] : (parsed.isEmpty() ? null : PatternRules.translate(parsed));
            if (rule != null && p.fixed[i] != PhraseMatcher.MERGED) p.fixed[i] = rule;
            if (p.fixed[i] == null && !parsed.isEmpty()) p.fixed[i] = EnchantHints.translateList(parsed);
            if (p.fixed[i] == null && !parsed.isEmpty() && i == 0) p.fixed[i] = TableNames.translateName(parsed);
            if (p.fixed[i] == null && !parsed.isEmpty()) p.fixed[i] = glossaryName(parsed);
            // a line that is just a name, maybe with a bullet or a tier: "▶ Enchanted Ink Sac", "Missile IV"
            if (p.fixed[i] == null && !parsed.isEmpty()) p.fixed[i] = bulletName(parsed);
            if (p.fixed[i] == null && !parsed.isEmpty()) p.fixed[i] = looseName(parsed);
            ai[i] = !parsed.isEmpty() && p.fixed[i] == null && TranslationUtil.needsTranslation(parsed);
        }
        // Wrapped sentences: join consecutive AI lines that continue the previous one.
        int i = 0;
        while (i < n) {
            if (!ai[i]) { i++; continue; }
            int j = i;
            int len = plain(lines.get(i)).length();
            // wrapped sentences are only joined for the AI; without it every line is looked up on its own
            while (NPCTranslatorClient.aiAllowed() && j + 1 < n && j - i < 5 && ai[j + 1] && continues(lines.get(j), lines.get(j + 1)) && len < 300) {
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

    private static final java.util.regex.Pattern BULLET =
            java.util.regex.Pattern.compile("^((?:§[0-9a-fk-or])*\\s*(?:§[0-9a-fk-or])*(?:[▶•‣›◆➜→∙●■◇◈★✦✧✔✖\\-]\\s*(?:§[0-9a-fk-or])*)?)(.+)$");

    /** "▶ Enchanted Ink Sac" / "Missile IV": a name from the term table, keeping the bullet. */
    /** "§6Mossy Box §8(Mining)": a name followed by its category in brackets. */
    private static final java.util.regex.Pattern CATEGORY = java.util.regex.Pattern.compile(
            "^(.*?\\S)(\\s*(?:§[0-9a-fk-or])*\\s*\\((?:§[0-9a-fk-or])*)([A-Za-z]+)((?:§[0-9a-fk-or])*\\))\\s*$");
    private static final java.util.Map<String, String> CATEGORIES = java.util.Map.ofEntries(
            java.util.Map.entry("Global", "全局"), java.util.Map.entry("Combat", "战斗"), java.util.Map.entry("Mining", "挖矿"),
            java.util.Map.entry("Foraging", "伐木"), java.util.Map.entry("Farming", "农业"), java.util.Map.entry("Fishing", "钓鱼"),
            java.util.Map.entry("Hunting", "狩猎"), java.util.Map.entry("Enchanting", "附魔"), java.util.Map.entry("Alchemy", "炼药"),
            java.util.Map.entry("Taming", "驯养"), java.util.Map.entry("Carpentry", "木工"), java.util.Map.entry("Runecrafting", "符文"),
            java.util.Map.entry("Social", "社交"), java.util.Map.entry("Dungeoneering", "地牢"), java.util.Map.entry("Forest", "森林"),
            java.util.Map.entry("Water", "水域"));

    private static final java.util.regex.Pattern TO_NAME =
            java.util.regex.Pattern.compile("^((?:§[0-9a-fk-or])*)To ((?:§[0-9a-fk-or])*)(.+)$");
    private static final java.util.regex.Pattern LEVEL_NAME =
            java.util.regex.Pattern.compile("^((?:§[0-9a-fk-or])*)\\[Lv(\\d+)\\] ?(.+)$");

    static String bulletName(String formatted) {
        String plain = TranslationUtil.stripColorCodes(formatted).trim();
        if (plain.length() < 3 || plain.length() > 60) return null;
        java.util.regex.Matcher c = CATEGORY.matcher(formatted);
        if (c.matches() && CATEGORIES.containsKey(c.group(3))) {
            String name = bulletName(c.group(1));
            if (name != null) return name + c.group(2) + CATEGORIES.get(c.group(3)) + c.group(4);
        }
        if (plain.length() > 48) return null;
        // "To Coal" (back button of a bazaar / collection page) and "[Lv70] Rock" (a mob's name tag)
        java.util.regex.Matcher to = TO_NAME.matcher(formatted);
        if (to.matches()) {
            String zh = TableNames.translateName(to.group(3));
            if (zh != null) return to.group(1) + "返回" + to.group(2) + zh.replaceFirst("^(?:§[0-9a-fk-or])*", "");
        }
        java.util.regex.Matcher lv = LEVEL_NAME.matcher(formatted);
        if (lv.matches()) {
            String zh = TableNames.translateName(lv.group(3));
            if (zh != null) return lv.group(1) + "[等级 " + lv.group(2) + "] " + zh;
        }
        // the same with colours inside the brackets: "§8[§7Lv1§8] §fCow"
        if (plain.matches("\\[Lv\\d+\\] .+")) {
            int close = formatted.indexOf("] ");
            String zh = close < 0 ? null : TableNames.translateName(formatted.substring(close + 2));
            if (zh != null) return formatted.substring(0, close + 2).replaceFirst("Lv", "等级 ") + zh;
        }
        java.util.regex.Matcher m = BULLET.matcher(formatted);
        if (!m.matches()) return null;
        String zh = TableNames.translateName(m.group(2));
        return zh == null ? null : m.group(1) + zh;
    }

    private static final java.util.regex.Pattern LOOSE = java.util.regex.Pattern.compile(
            "^([^A-Za-z]*?)([A-Za-z][A-Za-z0-9'’.&\\- ]*?[A-Za-z.])((?:\\s+x[\\d,]+(?:-[\\d,]+)?|\\s*\\(\\d[\\d.,]*%\\)|\\s*[✪➊➋➌➍➎]+)*[^A-Za-z]*)$");

    /**
     * A name with numbers, icons or an amount around it ("+27.5  Soft Shadow Assassin Cloak ✪✪✪✪✪",
     * "■  Rough Ruby Gemstone (40%)", "Enchanted Ice x1,024", "20 North Stars", "Moth Pest"):
     * the name is translated in place, everything around it (colours included) stays as it is.
     */
    static String looseName(String formatted) {
        StringBuilder plain = new StringBuilder();
        java.util.List<Integer> at = new java.util.ArrayList<>();
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) { i++; continue; }
            plain.append(c);
            at.add(i);
        }
        String p = plain.toString();
        if (p.trim().length() < 3 || p.trim().length() > 60) return null;
        java.util.regex.Matcher m = LOOSE.matcher(p);
        if (!m.matches()) return null;
        String core = m.group(2).trim();
        // a sentence's last words ("    Fisherman's Hut.") are not a name
        if (core.endsWith(".") && !core.matches(".*\\b(?:Mk|Jr|Sr|St)\\.$") || m.group(3).replaceAll("x[\\d,]+(?:-[\\d,]+)?|\\(\\d[\\d.,]*%\\)|\\d[\\d,.]*", "").matches(".*[.!?,:;].*")) return null;
        if (core.length() < 3 || core.split(" ").length > 6) return null;
        int cs = m.start(2) + (m.group(2).length() - m.group(2).replaceFirst("^\\s+", "").length());
        int ce = cs + core.length();
        int fs = at.get(cs), fe = at.get(ce - 1) + 1;
        String inner = formatted.substring(fs, fe);
        if (inner.contains("§")) {
            // differently coloured words ("§fEnchanted Bone Meal §7Trade"): each coloured part on its own
            StringBuilder out = new StringBuilder();
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("((?:§[0-9a-fk-or])*)([^§]*)").matcher(inner);
            boolean ok = true;
            while (cm.find()) {
                if (cm.group().isEmpty()) continue;
                String w = cm.group(2);
                String z = w.trim().isEmpty() ? "" : nameOf(w.trim());
                if (z == null) { ok = false; break; }
                out.append(cm.group(1)).append(z);
            }
            if (ok) return formatted.substring(0, fs) + out + formatted.substring(fe);
        }
        String zh = nameOf(core);
        if (zh == null) return null;
        StringBuilder codesInside = new StringBuilder();
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("§[0-9a-fk-or]").matcher(inner);
        while (cm.find()) codesInside.append(cm.group());
        return formatted.substring(0, fs) + codesInside + zh + formatted.substring(fe);
    }

    /** Chinese for a bare name: the item table, then the glossary (with "X Crop", "Y Pest"...), then the singular. */
    static String nameOf(String core) {
        String z = TableNames.translateName(core);
        if (z != null) z = z.replaceFirst("^(?:§[0-9a-fk-or])*", "").trim();
        if (z == null || z.isEmpty()) {
            String v = PhraseBook.translateValue(core);
            z = v.equals(core) ? null : v;
        }
        if (z == null) z = PhraseBook.suffixWord(core);
        if (z == null && core.length() > 4 && core.endsWith("s") && !core.endsWith("ss")) {
            String one = TableNames.translateName(core.endsWith("ies") ? core.substring(0, core.length() - 3) + "y" : core.substring(0, core.length() - 1));
            if (one != null) z = one.replaceFirst("^(?:§[0-9a-fk-or])*", "").trim();
        }
        if (z == null || z.isEmpty() || z.matches(".*[A-Za-z]{3,}.*")) return null;
        return z;
    }

    /** A line that is exactly one glossary term (an item or material name): its fixed translation. */
    static String glossaryName(String formatted) {
        String plain = TranslationUtil.stripColorCodes(formatted).trim();
        if (plain.length() < 3 || plain.length() > 48) return null;
        String zh = Glossary.index().exact(plain);
        if (zh == null || zh.equals(plain) || zh.isEmpty()) return null;
        java.util.regex.Matcher lead = java.util.regex.Pattern.compile("^((?:§[0-9a-fk-or])*)").matcher(formatted.trim());
        return (lead.find() ? lead.group(1) : "") + zh;
    }

    private static String plain(String formatted) {
        return TranslationUtil.stripColorCodes(formatted).trim();
    }

    /** Whether {@code next} is the wrapped continuation of the sentence in {@code prev}. */
    static boolean continues(String prev, String next) {
        if (OrderBookText.isBoundary(prev) || OrderBookText.isBoundary(next)) return false;
        if (PhraseMatcher.isBar(prev) || PhraseMatcher.isBar(next)) return false;
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

    /** Templates the AI returned unchanged this session: not asked again until restart. */
    private static final java.util.Set<String> UNCHANGED = ConcurrentHashMap.newKeySet();

    /** Templates of the plan that have no translation yet (for this menu or globally). */
    public static void collectMissing(Plan p, ModConfig.TranslationProvider provider, String menu, Collection<String> into) {
        for (TranslationUtil.Template t : p.templates) {
            if (t == null || UNCHANGED.contains(t.text)) continue;
            String tr = NPCTranslatorClient.dictGet(t.text, provider, menu);
            if (tr == null || tr.isEmpty()) into.add(t.text);
        }
    }

    /** Same plain text (colour codes and spacing ignored): the AI did not translate the line. */
    private static boolean unchanged(String template, String translated) {
        String a = TranslationUtil.stripColorCodes(template).replaceAll("\\s+", " ").trim();
        String b = TranslationUtil.stripColorCodes(translated).replaceAll("\\s+", " ").trim();
        return a.equalsIgnoreCase(b) && TranslationUtil.needsTranslation(template);
    }

    /**
     * Translate templates with the AI in batches of {@code batchSize}, up to {@code parallel} requests at
     * once; results go into the dictionary. Throws the first error if nothing at all succeeded.
     */
    public static int translateMissing(Collection<String> missing, ModConfig.TranslationProvider provider,
                                       int batchSize, int parallel, String menu) throws Exception {
        return translateMissing(missing, provider, batchSize, parallel, null, menu);
    }

    /**
     * Same, reporting the number of lines handled after every finished request. Results are saved
     * under the menu they came from ({@code menu}, null = no menu) and the menu is told to the AI.
     */
    public static int translateMissing(Collection<String> missing, ModConfig.TranslationProvider provider,
                                       int batchSize, int parallel, java.util.function.IntConsumer progress,
                                       String menu) throws Exception {
        List<String> all = new ArrayList<>(new LinkedHashSet<>(missing));
        if (all.isEmpty() || !NPCTranslatorClient.aiAllowed()) return 0;
        String langCode = NPCTranslatorClient.getEffectiveLanguageCode();
        String targetLang = NPCTranslatorClient.targetLanguageName(langCode);
        String ns = NPCTranslatorClient.dictNs(provider, menu);
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
                    NPCTranslatorClient.MENU_CONTEXT.set(menu);
                    try {
                        Map<Integer, String> res = NPCTranslatorClient.translateBatch(chunk, langCode, targetLang, provider);
                        int got = 0;
                        for (Map.Entry<Integer, String> e : res.entrySet()) {
                            String template = chunk.get(e.getKey());
                            if (!TranslationUtil.placeholdersMatch(template, e.getValue())) continue;
                            if (unchanged(template, e.getValue())) {
                                UNCHANGED.add(template);
                                HudTranslator.debugLog("AI left the line untranslated: " + template);
                                continue;
                            }
                            TranslationDictionary.put(template, e.getValue(), ns);
                            got++;
                        }
                        synchronized (ok) { ok[0] += got; }
                    } catch (Exception e) {
                        errors.put(idx, e);
                    } finally {
                        NPCTranslatorClient.MENU_CONTEXT.remove();
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
    public static Result assemble(Plan p, ModConfig.TranslationProvider provider, String[] rawOverride, String menu) {
        int n = p.lines.size();
        List<Component> translated = new ArrayList<>();
        Map<String, List<Component>> lineMap = new ConcurrentHashMap<>();
        boolean changed = false;
        String where = menu != null ? menu : "物品";
        int tooltipPx = 0;
        for (String l : p.lines) tooltipPx = Math.max(tooltipPx, PhraseMatcher.widthPx(l));
        for (int i = 0; i < n; i++) {
            String original = p.lines.get(i);
            if (p.fixed[i] != PhraseMatcher.MERGED && !p.joined[i]) {
                boolean has = p.fixed[i] != null;
                if (!has && p.templates[i] != null) {
                    String tr = rawOverride != null && rawOverride[i] != null ? rawOverride[i]
                            : NPCTranslatorClient.dictGet(p.templates[i].text, provider, menu);
                    has = tr != null && !tr.isEmpty();
                }
                Coverage.record(original, has, i == 0 ? "物品名称" : where);
            }
            if (p.fixed[i] == PhraseMatcher.MERGED || p.joined[i]) {
                lineMap.putIfAbsent(original, List.of());
                changed = true;
                continue;
            }
            if (p.fixed[i] != null) {
                List<String> source = new ArrayList<>();
                source.add(original);
                for (int k = i + 1; k < n && p.fixed[k] == PhraseMatcher.MERGED; k++) source.add(p.lines.get(k));
                List<Component> wrapped = new ArrayList<>();
                for (String w : PhraseMatcher.layout(p.fixed[i], source, tooltipPx)) wrapped.add(NPCTranslatorClient.parseColorCodes(w));
                translated.addAll(wrapped);
                lineMap.put(original, wrapped);
                changed = true;
                continue;
            }
            if (original.isEmpty()) {
                translated.add(Component.empty());
                continue;
            }
            String out = rawOverride != null ? TermFixes.apply(rawOverride[i]) : null;
            TranslationUtil.Template t = p.templates[i];
            if (out == null && t != null) {
                String tr = NPCTranslatorClient.dictGet(t.text, provider, menu);
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
