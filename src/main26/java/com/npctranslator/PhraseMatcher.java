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
        /** Literal characters of the template: more specific templates are tried first. */
        int literal;
        Template(Pattern pattern, String zh) { this.pattern = pattern; this.zh = zh; }
    }

    private final Map<String, String> exact = new HashMap<>();
    /** "Speed" -> "速度": labels of "Label: value" lines, taken from the entries. */
    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, Map<String, Integer>> labelVotes = new HashMap<>();
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
        for (Map.Entry<String, Map<String, Integer>> v : labelVotes.entrySet()) {
            String best = null;
            int n = -1;
            for (Map.Entry<String, Integer> c : v.getValue().entrySet()) if (c.getValue() > n) { best = c.getKey(); n = c.getValue(); }
            if (best != null) labels.put(v.getKey(), best);
        }
        labelVotes.clear();
        // "Grants +%s ❤ Health." before "Grants %1$s %2$s %3$s." (stable: earlier user entries still win ties)
        for (List<Template> list : buckets.values()) list.sort((x, y) -> Integer.compare(y.literal, x.literal));
    }

    /** Optional: translate captured values (e.g. location names) before they are put back. */
    public PhraseMatcher withValueTranslator(UnaryOperator<String> fn) {
        this.valueTranslator = fn == null ? UnaryOperator.identity() : fn;
        return this;
    }

    public int size() { return size; }

    private static final Pattern EN_LABEL_ENTRY = Pattern.compile("^([A-Z][A-Za-z' \\-]{0,30}):(.*)$");
    private static final Pattern ZH_LABEL_ENTRY = Pattern.compile("^((?:§.)*)([^:：]{1,20}?)\\s*[:：](.*)$");

    /** Collect "Label: value" → "标签: value" so stat lines can be translated label-only. */
    private void voteLabel(String key, String zh) {
        Matcher e = EN_LABEL_ENTRY.matcher(key);
        if (!e.matches()) return;
        // the value part must be a value, not words ("Damage: 5 per second" is not a stat line)
        if (e.group(2).replaceAll("%(?:\\d+\\$)?s", "").matches(".*[A-Za-z]{2,}.*")) return;
        Matcher z = ZH_LABEL_ENTRY.matcher(zh.trim());
        if (!z.matches()) return;
        String label = CODE.matcher(z.group(2)).replaceAll("").trim();
        if (label.isEmpty() || label.matches(".*[A-Za-z]{2,}.*")) return;
        labelVotes.computeIfAbsent(e.group(1).trim(), k -> new HashMap<>()).merge(label, 1, Integer::sum);
    }

    private void add(String en, String zh) {
        String key = normalize(en);
        if (key.length() < 2 || zh.isEmpty()) return;
        voteLabel(key, zh);
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
        // "Speed: +%s", "Fear: %s": a template that starts with its own label is specific enough
        boolean label = literal.matches("[A-Za-z][A-Za-z '\\-]*:[\\s+\\-%x]*");
        int letters = literal.replaceAll("[^A-Za-z]", "").length();
        if (!symbol && !label && letters < 6) return;
        Template t = new Template(Pattern.compile(re.toString()), renumber(key, zh));
        t.literal = PH.matcher(key).replaceAll("").length();
        buckets.computeIfAbsent(bucketKey(key), k -> new ArrayList<>()).add(t);
        size++;
    }

    /**
     * Fragments cut from longer corpus sentences keep their original numbers ("for %2$s seconds."
     * with only one value): number the placeholders by their order in the English text instead.
     */
    private static String renumber(String en, String zh) {
        Matcher m = PH.matcher(en);
        Map<String, Integer> order = new HashMap<>();
        int seq = 0;
        boolean shifted = false;
        while (m.find()) {
            seq++;
            if (m.group(1) != null) {
                order.putIfAbsent(m.group(1), seq);
                if (Integer.parseInt(m.group(1)) != seq) shifted = true;
            }
        }
        if (!shifted || order.isEmpty()) return zh;
        Matcher z = PH.matcher(zh);
        StringBuilder sb = new StringBuilder();
        while (z.find()) {
            Integer to = z.group(1) != null ? order.get(z.group(1)) : null;
            z.appendReplacement(sb, Matcher.quoteReplacement(to != null ? "%" + to + "$s" : z.group()));
        }
        z.appendTail(sb);
        return sb.toString();
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

    /** A stat line "§7Speed: §f+1 §9(+1)": its label, translated, with the value part left exactly as it is. */
    private static final Pattern STAT_LINE = Pattern.compile("^([\\s  ]*(?:§[0-9a-fk-or])*)([A-Z][A-Za-z' \\-]{0,30}?)((?:§[0-9a-fk-or])*):(.*)$");

    private String statLine(String formatted) {
        Matcher m = STAT_LINE.matcher(formatted);
        if (!m.matches()) return null;
        String rest = m.group(4);
        // only values, symbols and colour codes after the label: no words to translate
        if (CODE.matcher(rest).replaceAll("").matches(".*[A-Za-z]{2,}.*")) return null;
        String zh = labels.get(m.group(2).trim());
        if (zh == null) return null;
        return m.group(1) + zh + m.group(3) + ":" + rest;
    }

    private String doTranslate(String formatted) {
        String key = normalize(formatted);
        if (key.length() < 2) return null;
        String stat = statLine(formatted);
        if (stat != null) return stat;
        Matcher lead = LEADING_CODES.matcher(formatted.replaceFirst("^[\\s ]+", ""));
        String baseCodes = lead.find() ? lead.group(1) : "";
        String zh = exact.get(key);
        if (zh == null) {
            zh = matchTemplates(buckets.get(bucketKey(key)), key, formatted, baseCodes);
            if (zh == null) zh = matchTemplates(buckets.get("*"), key, formatted, baseCodes);
        }
        if (zh == null) {
            String split = splitFields(formatted);
            if (split != null) return split;
            // a single "Label: value" whose label is known (e.g. "Mana Cost: 150")
            String body = formatted.replaceFirst("^[\\s  ]+", "");
            return LABEL_VALUE.matcher(body).matches() ? translateField(body) : null;
        }
        zh = restoreGlyphs(formatted, zh);
        String leadingSpace = leadingWhitespace(CODE.matcher(formatted).replaceAll(""));
        if (zh.startsWith("§")) {
            return leadingSpace + zh;
        }
        return leadingSpace + baseCodes + zh;
    }

    private static final Pattern LABEL_INSIDE = Pattern.compile("[A-Za-z]{2,}:(\\s|$)");
    /** Whitespace followed by a "Label:" (1–4 words, first one capitalised), colour codes allowed. */
    private static final Pattern FIELD_START = Pattern.compile(
            "(\\s+)((?:§.)*[A-Z][A-Za-z']*(?: (?:§.)*[A-Za-z][A-Za-z']*){0,3}:)");
    private static final Pattern LABEL_VALUE = Pattern.compile("^((?:§.)*[^:]+:)(\\s*)((?:§.|[^A-Za-z])*)$");

    /**
     * Several fields on one line ("Soulflow Cost: 1 Mana Cost: 90"): translate each "Label: value"
     * part on its own. Returns null unless every part could be translated.
     */
    private String splitFields(String formatted) {
        Matcher m = FIELD_START.matcher(formatted);
        List<Integer> cuts = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            // cut only after a complete "Label: value" (the part since the last cut already has its colon
            // and some value), so "Soulflow Cost:" is never split into "Soulflow" + "Cost:"
            String prev = CODE.matcher(formatted.substring(last, m.start(1))).replaceAll("");
            int colon = prev.indexOf(':');
            if (m.start(2) > 0 && colon >= 0 && prev.substring(colon + 1).trim().length() > 0) {
                cuts.add(m.start(2));
                last = m.start(2);
            }
        }
        if (cuts.isEmpty()) return null;
        cuts.add(formatted.length());
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int cut : cuts) {
            String seg = formatted.substring(from, cut);
            from = cut;
            String body = seg.replaceAll("[\\s  ]+$", "");
            String tail = seg.substring(body.length());
            String tr = translateField(body);
            if (tr == null) return null;
            out.append(tr).append(tail);
        }
        return out.toString();
    }

    /** One "Label: value" part: phrase book first, otherwise a known label followed by a plain value. */
    private String translateField(String seg) {
        String key = normalize(seg);
        String zh = exact.get(key);
        if (zh == null) {
            zh = matchTemplates(buckets.get(bucketKey(key)), key, seg, leadCodes(seg));
            if (zh == null) zh = matchTemplates(buckets.get("*"), key, seg, leadCodes(seg));
        }
        if (zh != null) {
            zh = restoreGlyphs(seg, zh);
            return zh.startsWith("§") ? zh : leadCodes(seg) + zh;
        }
        Matcher lv = LABEL_VALUE.matcher(seg);
        if (!lv.matches()) return null;
        String label = exact.get(normalize(lv.group(1)));
        if (label == null) return null;
        String lab = label.replaceAll("[\\s  ]+$", "");
        return (lab.startsWith("§") ? lab : leadCodes(seg) + lab) + " " + lv.group(3).trim();
    }

    private static String leadCodes(String formatted) {
        Matcher lead = LEADING_CODES.matcher(formatted);
        return lead.find() ? lead.group(1) : "";
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
                            || v.matches(".*[.!?。！？]$")
                            // a value never spans two sentences ("+75 ❤ Health. Protection V Grants +20 ❤ Defense")
                            || v.matches(".*[.!?。！？]\\s+\\S.*")
                            // "Soulflow Cost: %s" must not swallow "1 Mana Cost: 90": a value never holds another "Label:"
                            || LABEL_INSIDE.matcher(v).find()) { ok = false; break; }
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
    /** A whole-entry match only (no field-by-field fallback): used when joining wrapped lines. */
    private String translateStrict(String formatted) {
        String key = normalize(formatted);
        if (key.length() < 2) return null;
        if (exact.containsKey(key)) return translate(formatted);
        Matcher lead = LEADING_CODES.matcher(formatted.replaceFirst("^[\\s ]+", ""));
        String baseCodes = lead.find() ? lead.group(1) : "";
        String zh = matchTemplates(buckets.get(bucketKey(key)), key, formatted, baseCodes);
        if (zh == null) zh = matchTemplates(buckets.get("*"), key, formatted, baseCodes);
        return zh == null ? null : translate(formatted);
    }

    /** A line that starts with its own "Label:" is a new field, never the continuation of the line above. */
    private static final Pattern STARTS_FIELD = Pattern.compile("^[\\s  ]*(?:§[0-9a-fk-or])*[A-Z][A-Za-z' \\-]{0,30}(?:§[0-9a-fk-or])*:(?:\\s|$)");

    /** Whether {@code next} continues the sentence of {@code prev} (same rules as the line joining). */
    public static boolean continuesLine(String prev, String next) {
        return !isBlank(prev) && !isBlank(next) && sameParagraph(prev, next) && !STARTS_FIELD.matcher(next).find();
    }

    public String[] translateLines(List<String> lines, int maxJoin) {
        int n = lines.size();
        String[] out = new String[n];
        int i = 0;
        while (i < n) {
            boolean done = false;
            // the first line is the item name: never merge it with the description below it
            if (i > 0 && !isBlank(lines.get(i))) {
                int maxJ = Math.min(n - 1, i + Math.max(0, maxJoin - 1));
                // stop at a blank line (paragraph boundary) or where the colour changes
                // ("§fSnow Block" + "§8Common Commodity" are two different things)
                for (int k = i + 1; k <= maxJ; k++) {
                    if (isBlank(lines.get(k)) || !sameParagraph(lines.get(k - 1), lines.get(k))
                            || STARTS_FIELD.matcher(lines.get(k)).find()) { maxJ = k - 1; break; }
                }
                for (int j = maxJ; j > i; j--) {
                    StringBuilder joined = new StringBuilder(lines.get(i));
                    for (int k = i + 1; k <= j; k++) joined.append(' ').append(lines.get(k));
                    String hit = translateStrict(joined.toString());
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

    private static final Pattern COLOUR = Pattern.compile("(?i)§[0-9a-fr]");

    /** Consecutive lore lines of one paragraph keep their base colour (or the next line has none). */
    static boolean sameParagraph(String prev, String next) {
        String lead = leadColour(next);
        if (lead.isEmpty()) return true;
        String prevLead = leadColour(prev);
        boolean prevBody = prevLead.isEmpty() || prevLead.equalsIgnoreCase("§7");
        // a coloured heading ("§9Growth V", "§6Full Set Bonus: …", "§9Renowned Bonus") is followed by its
        // grey description: that is a new paragraph
        if (!prevBody && lead.equalsIgnoreCase("§7")) return false;
        // a grey sentence that has not ended yet wraps onto the next line even when that line starts with a
        // highlighted word ("…traps it for" + "§c5s §7and deals …")
        String p = normalize(prev);
        if (prevBody && !p.isEmpty() && !p.matches(".*[.!?。！？:：)\\]]$") && !p.matches(".*\\d$")) return true;
        if (lead.equalsIgnoreCase(prevLead)) return true;
        Matcher m = COLOUR.matcher(prev);
        String last = "";
        while (m.find()) last = m.group();
        return lead.equalsIgnoreCase(last);
    }

    private static String leadColour(String s) {
        String t = s.replaceFirst("^[\\s\u00a0]+", "");
        String col = "";
        int i = 0;
        while (i + 1 < t.length() && t.charAt(i) == '§') {
            String code = t.substring(i, i + 2);
            if (COLOUR.matcher(code).matches()) col = code;
            i += 2;
        }
        return col;
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
