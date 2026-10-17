package com.npctranslator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java helpers (no Minecraft classes) so they can be unit tested on their own.
 *
 *  - Number normalisation: "§7Strength: §c+45" -> template "§7Strength: §c+{0}", numbers ["45"].
 *    The template is what gets cached/translated, so the same line with different numbers
 *    only needs one API call.
 *  - Glossary matching for prompt injection.
 *  - Prompt building, <think> stripping, base-URL normalisation, batch parsing.
 */
public final class TranslationUtil {

    private TranslationUtil() {}

    /** A line with its numbers replaced by {0}, {1}, ... */
    public static final class Template {
        public final String text;
        public final List<String> numbers;

        public Template(String text, List<String> numbers) {
            this.text = text;
            this.numbers = numbers;
        }

        public static Template raw(String text) {
            return new Template(text, Collections.emptyList());
        }

        public boolean hasNumbers() {
            return !numbers.isEmpty();
        }
    }

    // A digit right after '§' is a colour code (§6), not a number.
    // 1,234,567.89 | 12.5 | 300
    private static final Pattern NUMBER = Pattern.compile(
            "(?<!§)(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");
    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");

    public static Template normalize(String line) {
        if (line == null || line.isEmpty()) return Template.raw(line == null ? "" : line);
        // Text that already contains {n} would be ambiguous; leave it alone.
        if (PLACEHOLDER.matcher(line).find()) return Template.raw(line);

        Matcher m = NUMBER.matcher(line);
        StringBuilder sb = new StringBuilder();
        List<String> nums = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            sb.append(line, last, m.start());
            sb.append('{').append(nums.size()).append('}');
            nums.add(m.group());
            last = m.end();
        }
        if (nums.isEmpty()) return Template.raw(line);
        sb.append(line.substring(last));
        return new Template(sb.toString(), nums);
    }

    /** Put the real numbers back into a translated template. */
    public static String restore(String translatedTemplate, List<String> numbers) {
        if (translatedTemplate == null) return null;
        if (numbers == null || numbers.isEmpty()) return translatedTemplate;
        Matcher m = PLACEHOLDER.matcher(translatedTemplate);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String rep = idx >= 0 && idx < numbers.size() ? numbers.get(idx) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** True when the translation contains exactly the same set of placeholders as the template. */
    public static boolean placeholdersMatch(String template, String translated) {
        if (translated == null) return false;
        return placeholderSet(template).equals(placeholderSet(translated));
    }

    private static Set<String> placeholderSet(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    public static String stripColorCodes(String s) {
        return s == null ? "" : COLOR_CODE.matcher(s).replaceAll("");
    }

    /** Remove reasoning blocks some models (DeepSeek-R1, Qwen3, ...) put before the answer. */
    public static String stripThink(String s) {
        if (s == null) return null;
        String out = THINK.matcher(s).replaceAll("");
        // An unclosed <think> means the model was cut off mid-thought; drop everything up to </think> if present.
        int close = out.toLowerCase().lastIndexOf("</think>");
        if (close >= 0) out = out.substring(close + "</think>".length());
        return out.trim();
    }

    /** Accepts "https://api.x.com", ".../v1", ".../v1/" or a full ".../chat/completions" URL. */
    public static String chatCompletionsUrl(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    /**
     * Glossary terms that occur in the text (case-insensitive, not inside a longer word).
     * Longer terms win when several overlap ("Aspect of the Dragons" before "Dragon").
     */
    public static Map<String, String> findGlossaryHits(Map<String, String> glossary, String text, int max) {
        Map<String, String> hits = new LinkedHashMap<>();
        if (glossary == null || glossary.isEmpty() || text == null) return hits;
        String plain = stripColorCodes(text);
        List<Map.Entry<String, String>> entries = new ArrayList<>(glossary.entrySet());
        entries.sort((a, b) -> b.getKey().length() - a.getKey().length());
        for (Map.Entry<String, String> e : entries) {
            String term = e.getKey();
            if (term == null || term.trim().length() < 2 || term.startsWith("_")) continue;
            Pattern p = Pattern.compile("(?<![A-Za-z])" + Pattern.quote(term.trim()) + "(?![A-Za-z])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (p.matcher(plain).find()) {
                hits.put(term.trim(), e.getValue());
                if (hits.size() >= max) break;
            }
        }
        return hits;
    }

    public static String buildSystemPrompt(boolean isTooltip, String langName, String customPrompt,
                                           Map<String, String> glossaryHits) {
        StringBuilder sb = new StringBuilder();
        if (isTooltip) {
            sb.append("Translate Minecraft item tooltip lines to ").append(langName).append(".\n")
              .append("RULES:\n")
              .append("1. Keep every Minecraft § colour/format code (e.g. §6, §l) exactly where it is.\n")
              .append("2. Each input line starts with 'ID|'. Return each translated line with the EXACT SAME 'ID|' prefix, one line per input line, same count, same order.\n")
              .append("3. Tokens like {0}, {1} stand for numbers. Keep them unchanged and in the correct position.\n")
              .append("4. Output ONLY the translated lines. No explanations, no code fences.\n");
        } else {
            sb.append("Translate Minecraft NPC chat to ").append(langName).append(".\n")
              .append("RULES:\n")
              .append("1. Keep ALL Minecraft § colour/format codes (like §e, §f, §l, §o) exactly where they are.\n")
              .append("2. Do NOT translate speaker names, player names or prefixes such as [NPC]. Only translate the message content.\n")
              .append("3. Tokens like {0}, {1} stand for numbers. Keep them unchanged.\n")
              .append("4. Output ONLY the final translated line with § codes preserved.\n");
        }
        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            sb.append("\nCONTEXT:\n").append(customPrompt.trim()).append('\n');
        }
        if (glossaryHits != null && !glossaryHits.isEmpty()) {
            sb.append("\nGLOSSARY (always use these exact translations for these terms):\n");
            for (Map.Entry<String, String> e : glossaryHits.entrySet()) {
                sb.append(e.getKey()).append(" => ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    /** "0|foo\n1|bar" -> {0: foo, 1: bar}. Tolerates leading spaces / bullets before the id. */
    public static Map<Integer, String> parseBatch(String translated, int count) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (translated == null) return out;
        for (String raw : translated.split("\\r?\\n")) {
            String tLine = raw.trim();
            if (tLine.startsWith("```")) continue;
            int pipeIdx = tLine.indexOf('|');
            if (pipeIdx <= 0) continue;
            String idPart = tLine.substring(0, pipeIdx).replaceAll("[^0-9]", "");
            if (idPart.isEmpty() || idPart.length() > 4) continue;
            int id = Integer.parseInt(idPart);
            if (id < 0 || id >= count || out.containsKey(id)) continue;
            String text = tLine.substring(pipeIdx + 1).trim();
            if (!text.isEmpty()) out.put(id, text);
        }
        return out;
    }

    public static String buildBatch(List<String> lines) {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            batch.append(i).append('|').append(lines.get(i)).append('\n');
        }
        return batch.toString();
    }
}
