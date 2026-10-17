package com.npctranslator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixed-translation phrase book (pure Java, no Minecraft classes).
 *
 * Entries are English templates with printf-style placeholders ({@code %s}, {@code %1$s}) and a
 * Chinese template that may carry its own § colour codes. A line matches when its colour-stripped,
 * whitespace-collapsed text equals the template with each placeholder capturing a non-empty value.
 */
public final class PhraseMatcher {

    private static final Pattern PH = Pattern.compile("%(?:(\\d+)\\$)?s");
    private static final Pattern CODE = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern LEADING_CODES = Pattern.compile("^((?:§[0-9a-fk-orA-FK-OR])+)");
    private static final Pattern WS = Pattern.compile("[\\s  ]+");

    /** Hypixel's resource-pack private-use stat icons -> the generic symbols used in the corpus. */
    private static final Map<Character, Character> GLYPHS = new HashMap<>();
    static {
        char[][] g = {
            {'', '⚔'}, {'', '๑'}, {'', '✎'}, {'', '⚡'}, {'', '❄'},
            {'', '☠'}, {'', '❈'}, {'', '⫽'}, {'', '☂'}, {'', '❁'},
            {'', '▚'}, {'', '❤'}, {'', '❣'}, {'', '♨'}, {'', '♣'},
            {'', '☄'}, {'', '⸕'}, {'', '▚'}, {'', '✯'}, {'', '✧'},
            {'', 'ф'}, {'', 'α'}, {'', '✦'}, {'', '❂'}, {'', '♨'},
            {'', '☣'}, {'', '☘'}, {'', '☘'}, {'', '☘'}, {'', '☘'},
            {'', '⏣'},
        };
        for (char[] p : g) GLYPHS.put(p[0], p[1]);
    }

    private static final class Template {
        final Pattern pattern;
        final String zh;
        Template(Pattern pattern, String zh) { this.pattern = pattern; this.zh = zh; }
    }

    private final Map<String, String> exact = new HashMap<>();
    private final Map<String, List<Template>> buckets = new HashMap<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final String MISS = "\u0000";
    private UnaryOperator<String> valueTranslator = UnaryOperator.identity();
    private int size;

    public PhraseMatcher(List<String[]> entries) {
        for (String[] e : entries) {
            if (e == null || e.length < 2 || e[0] == null || e[1] == null) continue;
            add(e[0], e[1]);
        }
    }

    /** Optional: translate captured values (e.g. location names) before they are put back. */
    public PhraseMatcher withValueTranslator(UnaryOperator<String> fn) {
        this.valueTranslator = fn == null ? UnaryOperator.identity() : fn;
        return this;
    }

    public int size() { return size; }

    private void add(String en, String zh) {
        String key = normalize(en);
        if (key.length() < 2 || zh.isEmpty()) return;
        Matcher m = PH.matcher(key);
        if (!m.find()) {
            exact.putIfAbsent(key, zh);
            size++;
            return;
        }
        // Build an anchored regex: literals quoted, each placeholder captures a non-empty value.
        StringBuilder re = new StringBuilder("^");
        int last = 0;
        m.reset();
        while (m.find()) {
            re.append(Pattern.quote(key.substring(last, m.start())));
            re.append("(\\S(?:.*?\\S)?)");
            last = m.end();
        }
        re.append(Pattern.quote(key.substring(last))).append('$');
        String literal = PH.matcher(key).replaceAll(" ").trim();
        // "%s: %s" would match everything; a literal needs a real word or a non-ASCII symbol ("⏣ %s").
        // Too-generic templates ("You %s", "Your %s") would swallow unrelated sentences.
        boolean symbol = literal.chars().anyMatch(ch -> ch > 0x7F);
        boolean label = literal.matches("[A-Za-z][A-Za-z '\\-]*:");
        int letters = literal.replaceAll("[^A-Za-z]", "").length();
        if (!symbol && !label && letters < 6) return;
        Template t = new Template(Pattern.compile(re.toString()), zh);
        buckets.computeIfAbsent(bucketKey(key), k -> new ArrayList<>()).add(t);
        size++;
    }

    /** Bucket by the first word when the template starts with literal text, else "*". */
    private static String bucketKey(String normalizedText) {
        if (normalizedText.startsWith("%")) return "*";
        int sp = normalizedText.indexOf(' ');
        String w = sp < 0 ? normalizedText : normalizedText.substring(0, sp);
        if (PH.matcher(w).find()) return "*";
        return w.toLowerCase(Locale.ROOT);
    }

    /** Strip colours, fold private-use glyphs, collapse whitespace, trim. */
    public static String normalize(String s) {
        if (s == null) return "";
        String plain = CODE.matcher(s).replaceAll("");
        StringBuilder sb = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            Character g = GLYPHS.get(c);
            sb.append(g != null ? g : c);
        }
        return WS.matcher(sb.toString()).replaceAll(" ").trim();
    }

    /**
     * Translate one line given as a §-formatted string. Returns a §-formatted Chinese line,
     * or null when no entry matches.
     */
    public String translate(String formatted) {
        if (formatted == null || formatted.isEmpty()) return null;
        String cached = cache.get(formatted);
        if (cached != null) return cached == MISS ? null : cached;
        String result = doTranslate(formatted);
        if (cache.size() > 8000) cache.clear();
        cache.put(formatted, result == null ? MISS : result);
        return result;
    }

    private String doTranslate(String formatted) {
        String key = normalize(formatted);
        if (key.length() < 2) return null;
        Matcher lead = LEADING_CODES.matcher(formatted.replaceFirst("^[\\s ]+", ""));
        String baseCodes = lead.find() ? lead.group(1) : "";
        String zh = exact.get(key);
        if (zh == null) {
            zh = matchTemplates(buckets.get(bucketKey(key)), key, formatted, baseCodes);
            if (zh == null) zh = matchTemplates(buckets.get("*"), key, formatted, baseCodes);
        }
        if (zh == null) return null;
        zh = restoreGlyphs(formatted, zh);
        String leadingSpace = leadingWhitespace(CODE.matcher(formatted).replaceAll(""));
        if (zh.startsWith("§")) {
            return leadingSpace + zh;
        }
        return leadingSpace + baseCodes + zh;
    }

    private String matchTemplates(List<Template> list, String key, String formatted, String baseCodes) {
        if (list == null) return null;
        for (Template t : list) {
            Matcher m = t.pattern.matcher(key);
            if (m.matches()) {
                List<String> values = new ArrayList<>();
                boolean ok = true;
                for (int i = 1; i <= m.groupCount(); i++) {
                    String v = m.group(i);
                    // A placeholder holds a value (number, name, item), never half a sentence.
                    if (v.length() > 40 || v.split(" ").length > 6 || v.contains(", ")
                            || v.matches(".*[!?。！？]$")) { ok = false; break; }
                    String tv = valueTranslator.apply(v);
                    // Zh templates without their own colours: keep the value's original colour.
                    if (t.zh.indexOf('§') < 0) {
                        String vc = codesBefore(formatted, v);
                        if (!vc.isEmpty() && !vc.equals(baseCodes)) {
                            tv = vc + tv + (baseCodes.isEmpty() ? "§r" : baseCodes);
                        }
                    }
                    values.add(tv);
                }
                if (ok) return fill(t.zh, values);
            }
        }
        return null;
    }

    /** Colour/format codes active right before the first occurrence of {@code value} in a formatted line. */
    static String codesBefore(String formatted, String value) {
        StringBuilder plain = new StringBuilder();
        List<String> codesAt = new ArrayList<>();
        String active = "";
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') active = "";
                if (code != 'r') active += "§" + formatted.charAt(i + 1);
                i++;
                continue;
            }
            Character g = GLYPHS.get(c);
            plain.append(g != null ? g : c);
            codesAt.add(active);
        }
        int at = plain.indexOf(value);
        return at >= 0 ? codesAt.get(at) : "";
    }

    /** Fill %s (sequential) and %n$s (indexed) placeholders. */
    static String fill(String zh, List<String> values) {
        Matcher m = PH.matcher(zh);
        StringBuilder sb = new StringBuilder();
        int seq = 0;
        while (m.find()) {
            int idx = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : seq++;
            String v = idx >= 0 && idx < values.size() ? values.get(idx) : "";
            // "5th" + "日" -> "5日"
            int after = m.end();
            while (after < zh.length() && zh.charAt(after) == ' ') after++;
            if (after < zh.length() && (zh.charAt(after) == '日' || zh.charAt(after) == '号')) {
                v = v.replaceAll("(\\d+)(st|nd|rd|th)(?=$|§)", "$1");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Put back the server's private-use icon where the corpus used the generic symbol. */
    private static String restoreGlyphs(String original, String zh) {
        Map<Character, Character> back = new HashMap<>();
        for (int i = 0; i < original.length(); i++) {
            Character g = GLYPHS.get(original.charAt(i));
            if (g != null) back.putIfAbsent(g, original.charAt(i));
        }
        if (back.isEmpty()) return zh;
        StringBuilder sb = new StringBuilder(zh.length());
        for (int i = 0; i < zh.length(); i++) {
            Character r = back.get(zh.charAt(i));
            sb.append(r != null ? r : zh.charAt(i));
        }
        return sb.toString();
    }

    /** Marker for a tooltip line that was merged into the line above it. */
    public static final String MERGED = "\u0000merged";

    /**
     * Translate tooltip lines, also trying to merge up to {@code maxJoin} consecutive lines into one
     * logical sentence (the corpus stores wrapped lore sentences as one record).
     * Result[i] is the translated line, {@link #MERGED} for a line absorbed by the line above,
     * or null when nothing matched.
     */
    public String[] translateLines(List<String> lines, int maxJoin) {
        int n = lines.size();
        String[] out = new String[n];
        int i = 0;
        while (i < n) {
            boolean done = false;
            if (!isBlank(lines.get(i))) {
                int maxJ = Math.min(n - 1, i + Math.max(0, maxJoin - 1));
                // stop at a blank line: that is a paragraph boundary
                for (int k = i + 1; k <= maxJ; k++) {
                    if (isBlank(lines.get(k))) { maxJ = k - 1; break; }
                }
                for (int j = maxJ; j > i; j--) {
                    StringBuilder joined = new StringBuilder(lines.get(i));
                    for (int k = i + 1; k <= j; k++) joined.append(' ').append(lines.get(k));
                    String hit = translate(joined.toString());
                    if (hit != null) {
                        out[i] = hit;
                        for (int k = i + 1; k <= j; k++) out[k] = MERGED;
                        i = j + 1;
                        done = true;
                        break;
                    }
                }
            }
            if (!done) {
                out[i] = translate(lines.get(i));
                i++;
            }
        }
        return out;
    }

    private static boolean isBlank(String formatted) {
        return normalize(formatted).isEmpty();
    }

    /**
     * Soft-wrap a §-formatted line. Width units: CJK = 2, everything else = 1. Breaks after
     * Chinese punctuation when possible and repeats the active colour codes on the next line.
     */
    public static List<String> wrap(String formatted, int maxUnits) {
        List<String> lines = new ArrayList<>();
        if (formatted == null) return lines;
        StringBuilder cur = new StringBuilder();
        String activeCodes = "";
        int units = 0, lastBreak = -1;
        String codesAtBreak = "";
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00A7' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') activeCodes = "";
                activeCodes += "\u00A7" + formatted.charAt(i + 1);
                cur.append(c).append(formatted.charAt(i + 1));
                i++;
                continue;
            }
            cur.append(c);
            units += (c >= 0x2E80) ? 2 : 1;
            if ("，。！？；、,.!?; ".indexOf(c) >= 0) { lastBreak = cur.length(); codesAtBreak = activeCodes; }
            if (units > maxUnits) {
                int cut = lastBreak > 0 ? lastBreak : cur.length();
                String head = cur.substring(0, cut);
                String tail = cur.substring(cut);
                lines.add(head.replaceAll("\\s+$", ""));
                String carry = lastBreak > 0 ? codesAtBreak : activeCodes;
                cur = new StringBuilder(carry).append(tail.replaceAll("^\\s+", ""));
                units = visibleUnits(cur.toString());
                lastBreak = -1;
            }
        }
        if (normalize(cur.toString()).length() > 0 || lines.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    private static int visibleUnits(String formatted) {
        String plain = CODE.matcher(formatted).replaceAll("");
        int u = 0;
        for (int i = 0; i < plain.length(); i++) u += plain.charAt(i) >= 0x2E80 ? 2 : 1;
        return u;
    }

    private static String leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ' ')) i++;
        return s.substring(0, i);
    }
}
