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
        String priceLevel = OrderBookText.translate(formatted);
        if (priceLevel != null) return priceLevel;
        String cached = cache.get(formatted);
        if (cached != null) return cached == MISS ? null : cached;
        String dynamic = RuntimeText.event(formatted);
        if (dynamic == null) dynamic = ContextText.requirement(formatted);
        if (dynamic == null && (formatted.contains(" ➜ ") || formatted.matches(".*Garden ➜ Mutations.*"))) dynamic = RuntimeText.menu(formatted);
        if (dynamic == null) dynamic = TableNames.translateReforgedName(formatted);
        if (dynamic != null) { if (cache.size() > 8000) cache.clear(); cache.put(formatted, dynamic); return dynamic; }
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
        // Countdown suffixes are units to translate, not immutable stat symbols.
        String field = m.group(2).trim();
        if (field.equals("Starts In") || field.equals("Ends In") || field.equals("Time Left")) {
            String countdown = HudText.translate(formatted, "TAB");
            if (countdown != null) return countdown;
        }
        // only values, symbols and colour codes after the label: no words to translate
        if (CODE.matcher(rest).replaceAll("").matches(".*[A-Za-z]{2,}.*")) return null;
        String zh = labels.get(m.group(2).trim());
        if (zh == null) return null;
        return m.group(1) + zh + m.group(3) + ":" + rest;
    }

    private String doTranslate(String formatted) {
        String key = normalize(formatted);
        if (key.length() < 2) return null;
        String segments = translateSegments(formatted);
        return segments != null ? segments : translateWhole(formatted);
    }

    private String translateWhole(String formatted) {
        String key = normalize(formatted);
        if (key.length() < 2) return null;
        String stat = statLine(formatted);
        if (stat != null) return stat;
        String baseCodes = textCodes(formatted);
        String zh = exact.get(key);
        if (zh == null) {
            zh = matchTemplates(buckets.get(bucketKey(key)), key, formatted, baseCodes);
            if (zh == null) zh = matchTemplates(buckets.get("*"), key, formatted, baseCodes);
        }
        if (zh == null) {
            String sole = soleTerm(formatted);
            if (sole != null) return sole;
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
        return recolour(formatted, leadingSpace + baseCodes + zh, baseCodes);
    }

    /** The colour of the line's own text: codes of the first run that holds a letter (else of the first visible character). */
    static String textCodes(String formatted) {
        String act = "", first = null;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') act = "";
                if (code != 'r') act += "§" + formatted.charAt(i + 1);
                i++;
                continue;
            }
            if (c == ' ' || c == ' ') continue;
            if (first == null) first = act;
            if (Character.isLetter(c)) return act;
        }
        return first == null ? "" : first;
    }

    /** Characters of a §-formatted string with the codes active at each of them. */
    private static String charsWithCodes(String formatted, List<String> codes) {
        StringBuilder chars = new StringBuilder();
        String act = "";
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') act = "";
                if (code != 'r') act += "§" + formatted.charAt(i + 1);
                i++;
                continue;
            }
            chars.append(c);
            codes.add(act);
        }
        return chars.toString();
    }

    /**
     * Put back colours of the original that the translation lost: every coloured run of the original whose
     * translation (or the run itself: numbers, icons, "???") is found in the output, still in the base colour,
     * gets its colour back ("§6??? §3Sea Creature" -> "§6??? §3海怪", "§a✌ §7Guests" -> "§a✌ §7访客").
     */
    private String recolour(String original, String result, String base) {
        List<String> oc = new ArrayList<>();
        String op = charsWithCodes(original, oc);
        List<String> runCodes = new ArrayList<>(), runText = new ArrayList<>();
        for (int i = 0; i < op.length(); ) {
            int j = i;
            while (j < op.length() && oc.get(j).equals(oc.get(i))) j++;
            runCodes.add(oc.get(i));
            runText.add(op.substring(i, j));
            i = j;
        }
        boolean any = false;
        for (int k = 0; k < runCodes.size(); k++) if (!runCodes.get(k).equals(base) && !runText.get(k).trim().isEmpty()) { any = true; break; }
        if (!any) return result;
        List<String> codes = new ArrayList<>();
        String plain = charsWithCodes(result, codes);
        int from = 0;
        boolean changed = false;
        for (int k = 0; k < runCodes.size(); k++) {
            String rc = runCodes.get(k);
            if (rc.equals(base)) continue;
            String t = runText.get(k).trim();
            if (t.matches(".*[A-Za-z].*")) t = t.replaceAll("^[.,!?:;(]+|[.,!?:;)]+$", "").trim();
            if (t.isEmpty()) continue;
            String z = t;
            boolean sign = false;
            if (t.matches(".*[A-Za-z].*")) {
                String tr = valueTranslator.apply(t);
                if (tr != null && !tr.equals(t)) z = tr;
            } else if (t.chars().allMatch(ch -> ch < 0x80 && !Character.isLetterOrDigit(ch))) {
                // bullets, brackets and signs ("- ", "(", ")", "*", "+"): the next one in order, never searched again
                if (t.matches("[.,!?:;]+")) continue;
                sign = true;
            }
            int at = -1;
            if (sign) {
                boolean firstRun = runText.subList(0, k).stream().allMatch(r -> r.trim().isEmpty());
                if ((z.equals("+") || z.equals("-")) && !(firstRun && plain.startsWith(z, leadingSpaces(plain)))) {
                    // a sign belongs to the number after it
                    for (int j = Math.max(0, from); j + 1 < plain.length(); j++)
                        if (plain.startsWith(z, j) && Character.isDigit(plain.charAt(j + z.length()))) { at = j; break; }
                } else at = plain.indexOf(z, from);
            } else {
                at = plain.indexOf(z, from);
                if (at < 0) at = plain.indexOf(z);
            }
            if (at < 0) continue;
            boolean free = true;
            for (int j = at; j < at + z.length(); j++) if (!codes.get(j).equals(base) && !codes.get(j).equals(rc)) { free = false; break; }
            if (!free) continue;
            for (int j = at; j < at + z.length(); j++) codes.set(j, rc);
            from = at + z.length();
            changed = true;
        }
        if (!changed) return result;
        StringBuilder out = new StringBuilder();
        String shown = null;
        for (int j = 0; j < plain.length(); j++) {
            String c = codes.get(j);
            if (!c.equals(shown)) {
                if (!c.isEmpty()) out.append(c);
                else if (shown != null) out.append("§r");
                shown = c;
            }
            out.append(plain.charAt(j));
        }
        return out.toString();
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\u00A0')) i++;
        return i;
    }

    private static final Pattern WORDS = Pattern.compile("[A-Za-z][A-Za-z'\\-]*(?: [A-Za-z][A-Za-z'\\-]*)*");

    /**
     * A line that is only numbers, icons and one name ("§8+§c4❤ Health", "§f⚡ Speed §f400", "+200,000 Coins"):
     * the name is translated in place, everything else (colours included) stays as it is.
     */
    private String soleTerm(String formatted) {
        StringBuilder plain = new StringBuilder();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) { i++; continue; }
            plain.append(c);
            at.add(i);
        }
        Matcher w = WORDS.matcher(plain);
        int ps = -1, pe = -1;
        while (w.find()) {
            // unit letters on a number ("1.5k", "3x", "20s") belong to the number
            if (w.start() > 0 && Character.isDigit(plain.charAt(w.start() - 1)) && w.group().length() <= 2) continue;
            if (ps >= 0) return null;
            ps = w.start();
            pe = w.end();
        }
        if (ps < 0 || pe - ps < 3) return null;
        String rest = plain.substring(0, ps) + plain.substring(pe);
        if (rest.matches(".*[A-Za-z]{3,}.*")) return null;
        if (!rest.matches(".*[\\d\\p{So}\\uE000-\\uF8FF].*")) return null;   // a number or an icon beside the name
        String phrase = plain.substring(ps, pe);
        String zh = valueTranslator.apply(phrase);
        if (zh == null || zh.equals(phrase) || zh.matches(".*[A-Za-z]{3,}.*")) return null;
        int fs = at.get(ps), fe = at.get(pe - 1) + 1;
        // codes inside the name move to its front
        StringBuilder codesInside = new StringBuilder();
        Matcher cm = CODE.matcher(formatted.substring(fs, fe));
        while (cm.find()) codesInside.append(cm.group());
        return formatted.substring(0, fs) + codesInside + zh + formatted.substring(fe);
    }


    /** Two or more spaces between visible text: the server lays out columns ("Ability: Soulcry  RIGHT CLICK"). */
    private static final Pattern GAP = Pattern.compile("\\S((?:§[0-9a-fk-orA-FK-OR])*[ \u00A0]{2,})(?=\\S)");

    /**
     * A line made of parts separated by wide gaps ("§6Ability: Splash  §e§lSNEAK",
     * "§b+100❤ Intelligence  §b+3✯ Magic Find"): each part is translated on its own and the gaps are kept,
     * so the columns and each part's colour stay as they were. Null when a part with words has no translation.
     */
    private String translateSegments(String formatted) {
        // cut points in the formatted string: [gapStart, gapEnd) contains only codes and spaces
        List<int[]> gaps = new ArrayList<>();
        int i = 0, n = formatted.length();
        int lastVisible = -1;
        while (i < n) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < n) { i += 2; continue; }
            if (c == ' ' || c == '\u00A0') {
                int j = i, spaces = 0, firstSpace = i;
                while (j < n) {
                    char d = formatted.charAt(j);
                    if (d == '§' && j + 1 < n) { j += 2; continue; }
                    if (d == ' ' || d == '\u00A0') { spaces++; j++; continue; }
                    break;
                }
                if (spaces >= 2 && lastVisible >= 0 && j < n) {
                    // the codes right before the next part belong to it
                    int end = j;
                    while (end - 2 >= firstSpace && formatted.charAt(end - 2) == '§') end -= 2;
                    int trimEnd = end;
                    while (trimEnd > firstSpace && (formatted.charAt(trimEnd - 1) == ' ' || formatted.charAt(trimEnd - 1) == '\u00A0')) trimEnd--;
                    gaps.add(new int[]{firstSpace, end});
                }
                i = j;
                continue;
            }
            lastVisible = i;
            i++;
        }
        if (gaps.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        int from = 0;
        String active = "";
        int parts = 0;
        for (int k = 0; k <= gaps.size(); k++) {
            int to = k < gaps.size() ? gaps.get(k)[0] : n;
            String part = formatted.substring(from, to);
            String withCodes = (k == 0 ? "" : active) + part;
            String plain = CODE.matcher(part).replaceAll("").trim();
            String tr;
            if (!plain.matches(".*[A-Za-z]{2,}.*")) tr = part;                // numbers, icons, "(0/1k)"
            else {
                tr = translate(withCodes.trim().isEmpty() ? part : withCodes);
                if (tr == null) return k == gaps.size() && k > 0 ? lastPartOnly(formatted, gaps.get(k - 1), null) : lastPartFallback(formatted, gaps);
                parts++;
            }
            out.append(tr);
            active = activeCodes(formatted.substring(0, to), active);
            if (k < gaps.size()) {
                String gap = formatted.substring(gaps.get(k)[0], gaps.get(k)[1]);
                out.append(gap);
                active = activeCodes(gap, active);
                from = gaps.get(k)[1];
            }
        }
        return parts > 0 ? out.toString() : null;
    }


    /**
     * Some part has no translation of its own, but the whole line has one ("Ability: Deploy  §e§lRIGHT CLICK"
     * → "技能: 部署 右键"): use it, and if it ends with the last part's translation put that part back after
     * the original gap with its own colour ("技能: 部署  §e§l右键").
     */
    private String lastPartFallback(String formatted, List<int[]> gaps) {
        return lastPartOnly(formatted, gaps.get(gaps.size() - 1), null);
    }

    private String lastPartOnly(String formatted, int[] gap, String unused) {
        String whole = translateWhole(formatted);
        if (whole == null) return null;
        String lastPart = formatted.substring(gap[1]);
        String lastPlain = CODE.matcher(lastPart).replaceAll("").trim();
        if (!lastPlain.matches(".*[A-Za-z]{2,}.*")) return whole;
        String lastZh = translate(activeCodes(formatted.substring(0, gap[1]), "") + lastPart);
        if (lastZh == null) return whole;
        String lastZhPlain = CODE.matcher(lastZh).replaceAll("").trim();
        String wholePlain = CODE.matcher(whole).replaceAll("");
        if (lastZhPlain.isEmpty() || !wholePlain.replaceAll("\\s+$", "").endsWith(" " + lastZhPlain)) return whole;
        // cut the whole translation before its last part (codes included) and append the original gap + part
        int keep = wholePlain.replaceAll("\\s+$", "").length() - lastZhPlain.length() - 1;
        StringBuilder head = new StringBuilder();
        int seen = 0;
        for (int i = 0; i < whole.length() && seen < keep; i++) {
            char c = whole.charAt(i);
            if (c == '§' && i + 1 < whole.length()) { head.append(c).append(whole.charAt(++i)); continue; }
            head.append(c);
            seen++;
        }
        return head + formatted.substring(gap[0], gap[1]).replaceAll("§.", "") + lastZh;
    }
    /** Colour / format codes in effect after {@code text}, starting from {@code start}. */
    private static String activeCodes(String text, String start) {
        String act = start;
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '§') continue;
            char code = Character.toLowerCase(text.charAt(i + 1));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') act = "";
            if (code != 'r') act += "§" + text.charAt(i + 1);
            i++;
        }
        return act;
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
                    // Zh templates without their own colours: keep the value's original colour (marked here,
                    // coloured after filling so the colour can also cover the sign, icon and stat name)
                    if (t.zh.indexOf('§') < 0) tv = MARK_START + String.valueOf((char) ('0' + i)) + tv + MARK_END;
                    values.add(tv);
                }
                if (ok) return t.zh.indexOf('§') < 0 ? colourValues(fill(t.zh, values), formatted, m, baseCodes) : fill(t.zh, values);
            }
        }
        return null;
    }


    private static final char MARK_START = '\u0010', MARK_END = '\u0012';

    /**
     * Give each filled value the colour it had in the original line. When the original coloured the whole
     * stat ("§c+8❁ Strength"), the colour also covers the sign, icon and stat name in the translation
     * ("§c+8❁ 力量§7") instead of only the number.
     */
    private String colourValues(String filled, String formatted, Matcher m, String baseCodes) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        String back = baseCodes.isEmpty() ? "§r" : baseCodes;
        while (i < filled.length()) {
            int ms = filled.indexOf(MARK_START, i);
            if (ms < 0) { out.append(filled.substring(i)); break; }
            int me = filled.indexOf(MARK_END, ms);
            if (me < 0) { out.append(filled.substring(i).replace(String.valueOf(MARK_START), "")); break; }
            int group = filled.charAt(ms + 1) - '0';
            String value = filled.substring(ms + 2, me);
            String[] run = group <= m.groupCount() ? colourRun(formatted, m.group(group)) : null;
            String before = filled.substring(i, ms);
            String codes = run == null ? "" : run[0];
            if (codes.isEmpty() || codes.equals(baseCodes)) {
                out.append(before).append(value);
                i = me + 1;
                continue;
            }
            // sign right before the value ("+", "-")
            String sign = run[1];
            int cut = before.length();
            if (!sign.isEmpty() && before.endsWith(sign)) cut -= sign.length();
            out.append(before, 0, cut).append(codes).append(before.substring(cut)).append(value);
            // icon and stat name right after it
            String rest = filled.substring(me + 1);
            int take = suffixLength(rest, run[2]);
            // back to the colour the original used after the value, or the line's own colour
            out.append(rest, 0, take).append(back);
            i = me + 1 + take;
        }
        return out.toString();
    }

    /** {codes, sign before the value, text after it} of the coloured run that holds {@code value}. */
    private static String[] colourRun(String formatted, String value) {
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
        // the value as it appears in the normalised key (spaces collapsed)
        String text = plain.toString();
        int at = text.indexOf(value);
        if (at < 0) return null;
        String codes = codesAt.get(at);
        int a = at, b = at + value.length();
        while (a > 0 && codesAt.get(a - 1).equals(codes)) a--;
        while (b < text.length() && codesAt.get(b).equals(codes)) b++;
        // a value whose colour starts inside it ("+§c8") only colours what the original coloured
        for (int k = at; k < at + value.length() && k < codesAt.size(); k++) if (!codesAt.get(k).equals(codes)) return new String[]{codes, "", "", ""};
        String sign = text.substring(a, at).trim();
        if (!sign.matches("[+\\-x#]?")) sign = "";
        String after = b < codesAt.size() ? codesAt.get(b) : "";
        return new String[]{codes, sign, text.substring(at + value.length(), b).replaceAll("\\s+", " "), after};
    }

    /** How much of {@code rest} (the translation after a value) corresponds to the original coloured {@code suffix}. */
    private int suffixLength(String rest, String suffix) {
        if (suffix == null || suffix.isEmpty() || suffix.trim().isEmpty()) return 0;
        Matcher sm = Pattern.compile("^(\\s*[^A-Za-z\\s]*)\\s*(.*?)[.,!?:;]?\\s*$").matcher(suffix);
        if (!sm.matches()) return 0;
        String sym = sm.group(1).trim(), words = sm.group(2).trim();
        // only a percent sign, a multiplier or stat icons travel with the value, never brackets or punctuation
        if (!sym.matches("[%x]?[^\\sA-Za-z0-9()\\[\\]{}<>（）「」『』【】，。！？：；、,.!?:;\\p{IsHan}]*")) return 0;
        int pos = 0;
        if (!sym.isEmpty()) {
            if (!fold(rest).startsWith(fold(sym))) return 0;
            pos += sym.length();
        }
        if (words.isEmpty()) return pos;
        String wordsZh = valueTranslator.apply(words);
        if (wordsZh == null || wordsZh.equals(words)) wordsZh = words;
        int p2 = pos;
        while (p2 < rest.length() && rest.charAt(p2) == ' ') p2++;
        if (rest.startsWith(wordsZh, p2)) return p2 + wordsZh.length();
        return pos;
    }

    private static String fold(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) { Character g = GLYPHS.get(s.charAt(i)); sb.append(g != null ? g : s.charAt(i)); }
        return sb.toString();
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
        int seq = 0, last = 0;
        while (m.find()) {
            int idx = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : seq++;
            String v = idx >= 0 && idx < values.size() ? values.get(idx) : "";
            // "5th" + "日" -> "5日"
            int after = m.end();
            while (after < zh.length() && zh.charAt(after) == ' ') after++;
            if (after < zh.length() && (zh.charAt(after) == '日' || zh.charAt(after) == '号')) {
                v = v.replaceAll("(\\d+)(st|nd|rd|th)(?=$|§|\u0012)", "$1");
            }
            // literal text before the placeholder (a space dropped after a Chinese value is skipped)
            sb.append(zh, last, m.start());
            // spacing between Chinese and the value: "获得 5 次", "与凯莉交谈", "Partshade 的宠物"
            char vFirst = firstVisible(v, true), vLast = firstVisible(v, false);
            char before = lastVisible(sb, sb.length());
            if (before != 0 && vFirst != 0) {
                if (han(before) && latinStart(vFirst)) sb.append(' ');
                else if (han(vFirst) && sb.length() > 0 && sb.charAt(sb.length() - 1) == ' '
                        && han(lastVisible(sb, sb.length() - 1))) sb.setLength(sb.length() - 1);
            }
            sb.append(v);
            last = m.end();
            char nextChar = last < zh.length() ? firstVisible(zh.substring(last), true) : 0;
            if (vLast != 0 && nextChar != 0) {
                if (latinEnd(vLast) && han(nextChar) && zh.charAt(last) != ' ') sb.append(' ');
                else if (han(vLast) && zh.charAt(last) == ' ' && last + 1 < zh.length() && han(zh.charAt(last + 1))) last++;
            }
        }
        sb.append(zh.substring(last));
        return sb.toString();
    }

    /** A Chinese character (not punctuation, not an icon). */
    private static boolean han(char c) { return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF); }

    private static boolean latinStart(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '#'; }
    private static boolean latinEnd(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '%'; }

    /** First (or last) visible character of a filled value, skipping colour codes and colour markers. */
    private static char firstVisible(String v, boolean first) {
        if (first) {
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '§') { i++; continue; }
                if (c == MARK_START) { i++; continue; }
                if (c == MARK_END) continue;
                return c;
            }
        } else {
            for (int i = v.length() - 1; i >= 0; i--) {
                char c = v.charAt(i);
                if (c == MARK_END || c == MARK_START) continue;
                if (i > 0 && v.charAt(i - 1) == '§') { i--; continue; }
                if (i > 0 && v.charAt(i - 1) == MARK_START) { i--; continue; }
                return c;
            }
        }
        return 0;
    }

    private static char lastVisible(CharSequence sb, int end) {
        for (int i = end - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if (c == MARK_END || c == MARK_START) continue;
            if (i > 0 && sb.charAt(i - 1) == '§') { i--; continue; }
            if (i > 0 && sb.charAt(i - 1) == MARK_START) { i--; continue; }
            return c;
        }
        return 0;
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
        String dynamic=RuntimeText.event(formatted);
        if(dynamic!=null)return dynamic;
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
        if (OrderBookText.isBoundary(prev) || OrderBookText.isBoundary(next)) return false;
        if (isBar(prev) || isBar(next) || rowBoundary(prev, next)) return false;
        return !isBlank(prev) && !isBlank(next) && sameParagraph(prev, next) && !STARTS_FIELD.matcher(next).find()
                && !STARTS_BULLET.matcher(next).find();
    }

    /** A list item ("▶ Growth VI", "- Bazaar (I-V)", "■ Talk to Kelly", "✔ Tier II") never continues the line above. */
    static final Pattern STARTS_BULLET = Pattern.compile("^[\\s  ]*(?:§[0-9a-fk-or])*(?:[▶•‣›◆∙➜→■✔✖✘✓☐☑★❖◇◈❣☠⚠]|[-–](?:§[0-9a-fk-or])* (?:§[0-9a-fk-or])*[A-Z0-9+(\\[])");

    /** Visible leading spaces of a line. */
    static int indent(String formatted) {
        int n = 0;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§' && i + 1 < formatted.length()) { i++; continue; }
            if (c == ' ' || c == ' ') { n++; continue; }
            break;
        }
        return n;
    }

    /**
     * Rows of a list never run on into each other: lines indented differently, and indented rows that start
     * with a value or an icon ("  +200,000 Coins", " ⚡ Speed 400") or follow a row ending with a number.
     */
    static boolean rowBoundary(String prev, String next) {
        int a = indent(prev), b = indent(next);
        if (a != b) return true;
        if (b == 0) return false;
        String np = CODE.matcher(next).replaceAll("").trim(), pp = CODE.matcher(prev).replaceAll("").trim();
        if (np.isEmpty() || pp.isEmpty()) return false;
        char f = np.charAt(0);
        if (f == '+' || f == '-' || Character.isDigit(f) || (!Character.isLetter(f) && f != '(' && f != '"' && f != '\'')) return true;
        return pp.matches(".*[\\d%kKM]$");
    }

    private static final Pattern BAR_CHARS = Pattern.compile("[━▬■▌▊█░▒▓|=\\-]{5,}");

    /**
     * A progress bar line ("§2§l§m          §f§l§m     §r §e1,021§6/§e3k", "§a■■■■■§7■■■■■"): it is drawn with
     * struck-through spaces or bar characters and must stay on its own line, exactly as it is.
     */
    public static boolean isBar(String formatted) {
        if (formatted == null) return false;
        if (formatted.contains("§m") || formatted.contains("§M")) {
            // the struck-through part is blank: a bar, not struck-through words
            Matcher m = Pattern.compile("§[mM]((?:§[0-9a-fk-orA-FK-OR])*)([^§]*)").matcher(formatted);
            while (m.find()) if (!m.group(2).isEmpty() && m.group(2).trim().isEmpty()) return true;
        }
        return BAR_CHARS.matcher(CODE.matcher(formatted).replaceAll("")).find();
    }

    /** "0→+5", "§80%§8§l→§a+2%": a value before and after the next level. */
    private static final Pattern PROGRESSION = Pattern.compile(
            "(?:(?<=§[0-9a-fk-or])|(?<![§\\d.,]))([-+]?\\d[\\d,.]*%?)((?:§[0-9a-fk-or])*)→((?:§[0-9a-fk-or])*)([-+]?\\d[\\d,.]*%?)");

    /**
     * Translate tooltip lines. Level progressions ("Grants 0→+5 Block Fortune.") are matched as the
     * plain next-level line ("Grants +5 Block Fortune.") and the "0→" is put back in front of the value.
     */
    public String[] translateLines(List<String> lines, int maxJoin) {
        List<String> collapsed = null;
        List<List<String[]>> progressions = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = PROGRESSION.matcher(lines.get(i));
            if (!m.find()) continue;
            if (collapsed == null) {
                collapsed = new ArrayList<>(lines);
                progressions = new ArrayList<>();
                for (int k = 0; k < lines.size(); k++) progressions.add(new ArrayList<>());
            }
            m.reset();
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                progressions.get(i).add(new String[]{m.group(1), m.group(4)});
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(3) + m.group(4)));
            }
            m.appendTail(sb);
            collapsed.set(i, sb.toString());
        }
        if (collapsed == null) return translateLines0(lines, maxJoin);
        String[] out = translateLines0(collapsed, maxJoin);
        int head = -1;
        for (int i = 0; i < out.length; i++) {
            if (out[i] != null && out[i] != MERGED) head = i;
            if (out[i] == null || progressions.get(i).isEmpty() || head < 0) continue;
            String t = out[head];
            for (String[] p : progressions.get(i)) {
                // put "0→" in front of the value it belongs to: the first matching number without one yet
                String num = p[1].replaceAll("[^\\d.,]", "");
                Matcher v = Pattern.compile("(?:(?<=§[0-9a-fk-or])|(?<![\\d.,]))" + Pattern.quote(num) + "(?![\\d.,])").matcher(t);
                int at = -1;
                while (v.find()) {
                    int b = v.start();
                    while (b >= 2 && t.charAt(b - 2) == '§') b -= 2;   // colour codes in front of the number
                    if (b >= 1 && (t.charAt(b - 1) == '+' || t.charAt(b - 1) == '-')) b--;
                    if (b >= 1 && t.charAt(b - 1) == '→') continue;
                    at = b;
                    break;
                }
                if (at >= 0) t = t.substring(0, at) + p[0] + "→" + t.substring(at);
            }
            out[head] = t;
        }
        return out;
    }

    private String[] translateLines0(List<String> lines, int maxJoin) {
        int n = lines.size();
        String[] out = new String[n];
        int i = 0;
        while (i < n) {
            String priceLevel = OrderBookText.translate(lines.get(i));
            if (priceLevel != null) { out[i++] = priceLevel; continue; }
            boolean done = false;
            // the first line is the item name: never merge it with the description below it
            if (i > 0 && !isBlank(lines.get(i)) && !isBar(lines.get(i))) {
                int maxJ = Math.min(n - 1, i + Math.max(0, maxJoin - 1));
                // stop at a blank line (paragraph boundary) or where the colour changes
                // ("§fSnow Block" + "§8Common Commodity" are two different things); a progress bar or
                // a list item is always a line of its own
                for (int k = i + 1; k <= maxJ; k++) {
                    if (OrderBookText.isBoundary(lines.get(k)) || isBlank(lines.get(k)) || !sameParagraph(lines.get(k - 1), lines.get(k))
                            || STARTS_FIELD.matcher(lines.get(k)).find() || STARTS_BULLET.matcher(lines.get(k)).find()
                            || isBar(lines.get(k)) || rowBoundary(lines.get(k - 1), lines.get(k))) { maxJ = k - 1; break; }
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
     * Soft-wrap a §-formatted line. Width units: CJK = 2, everything else = 1 (about 4.5 px per unit).
     * Kept for callers that have no tooltip to measure; see {@link #wrapPx}.
     */
    public static List<String> wrap(String formatted, int maxUnits) {
        return wrapPx(formatted, maxUnits * 9 / 2);
    }

    /** Approximate advance of a character in the default Minecraft font, in GUI pixels (1 px spacing included). */
    static int charPx(char c) {
        if (c >= 0x2E80 || (c >= 0xE000 && c <= 0xF8FF)) return 9;   // CJK, full-width punctuation, server icons
        switch (c) {
            case 'i': case '!': case '.': case ',': case ':': case ';': case '|': case '\'': return 2;
            case 'l': case '`': return 3;
            case 'I': case 't': case '[': case ']': case ' ': case '(': case ')': case '"': case '*': return 4;
            case 'f': case 'k': case '<': case '>': case '{': case '}': return 5;
            case '@': case '~': return 7;
            default: return c < 0x80 ? 6 : 8;
        }
    }

    /** Visible width of a §-formatted line in GUI pixels (bold adds 1 px per character). */
    public static int widthPx(String formatted) {
        if (formatted == null) return 0;
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00A7' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(++i));
                if (code == 'l') bold = true;
                else if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') bold = false;
                continue;
            }
            w += charPx(c) + (bold ? 1 : 0);
        }
        return w;
    }

    private static boolean cjk(char c) { return c >= 0x2E80 && c != '\u3000'; }
    /** Characters a line must not start with (they belong to the text before them). */
    private static final String CLOSING = "，。！？；、：）」』】》〉…%,.!?;:)]}";
    /** Characters a line must not end with (they belong to the text after them). */
    private static final String OPENING = "（「『【《〈([{";
    private static final String CJK_BREAK_AFTER = "，。！？；、：）」』】》…";
    /** Function words: breaking after (or before) them keeps the words around them whole. */
    private static final String SOFT_AFTER = "的和与或及并而但在把被将对给从向以于时后中上内了着过个次即可就也都";
    private static final String SOFT_BEFORE = "的和与或及并而但在把被将对给从向以于即可就也都";

    /**
     * Soft-wrap a §-formatted line to at most {@code maxPx} pixels per line. Lines are balanced (no short
     * last line), never split inside a word or a number ("1,000", "1.5", "+20%"), never start with closing
     * punctuation, and repeat the active colour codes on the next line.
     */
    public static List<String> wrapPx(String formatted, int maxPx) {
        List<String> out = new ArrayList<>();
        if (formatted == null) return out;
        // visible characters with the codes active before each of them
        StringBuilder vis = new StringBuilder();
        List<String> codesBefore = new ArrayList<>();   // raw text (codes) that precedes visible char i
        List<String> active = new ArrayList<>();        // codes active at visible char i
        List<Integer> px = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        String act = "";
        boolean bold = false;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00A7' && i + 1 < formatted.length()) {
                char raw = formatted.charAt(i + 1), code = Character.toLowerCase(raw);
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') { act = ""; bold = false; }
                if (code == 'l') bold = true;
                act += "\u00A7" + raw;
                pending.append(c).append(raw);
                i++;
                continue;
            }
            vis.append(c);
            codesBefore.add(pending.toString());
            pending.setLength(0);
            active.add(act);
            px.add(charPx(c) + (bold ? 1 : 0));
        }
        String tail = pending.toString();
        int n = vis.length();
        int total = 0;
        for (int w : px) total += w;
        if (total <= maxPx || n == 0) { out.add(formatted); return out; }
        // break opportunities: before visible char i (the text is cut between i-1 and i); 0 = none
        int[] prio = new int[n + 1];
        for (int i = 1; i < n; i++) {
            char p = vis.charAt(i - 1), c = vis.charAt(i);
            if (CLOSING.indexOf(c) >= 0 || OPENING.indexOf(p) >= 0) continue;
            if (c == ' ' || p == ' ') {                                 // cut at the space (it is dropped)
                // "60,000 - 225,000", "+5 ❤": a range or a value with its icon stays together
                String around = vis.substring(Math.max(0, i - 2), Math.min(n, i + 2));
                prio[i] = around.matches(".*\\d ?[-~/] ?\\d.*|.*[-~/] .*|.* [-~/].*") ? 0 : 2;
                continue;
            }
            if (CJK_BREAK_AFTER.indexOf(p) >= 0) { prio[i] = 3; continue; }
            if (cjk(p) && cjk(c)) { prio[i] = SOFT_AFTER.indexOf(p) >= 0 || SOFT_BEFORE.indexOf(c) >= 0 ? 2 : 1; continue; }
            if (cjk(p) != cjk(c) && (cjk(p) ? isWordStart(c) : isWordEnd(p))) prio[i] = 1; // 生命值 / +20
        }
        int[] best = null;
        int bestScore = Integer.MAX_VALUE;
        int lines0 = -1;
        int k0 = (total + maxPx - 1) / maxPx;
        for (int target = Math.max(40, total / (k0 + 2)); target <= maxPx; target += 3) {
            int[] cuts = greedy(px, prio, target, maxPx);
            int count = cuts.length + 1;
            if (lines0 < 0) lines0 = count;
            if (count > lines0) continue;
            if (count < lines0) { lines0 = count; bestScore = Integer.MAX_VALUE; }
            int score = score(px, cuts, prio);
            if (score < bestScore) { bestScore = score; best = cuts; }
        }
        if (best == null) best = greedy(px, prio, maxPx, maxPx);
        int from = 0;
        for (int k = 0; k <= best.length; k++) {
            int to = k < best.length ? best[k] : n;
            StringBuilder line = new StringBuilder();
            if (k > 0) line.append(active.get(from));
            for (int i = from; i < to; i++) {
                if (i > from || k == 0) line.append(codesBefore.get(i));
                line.append(vis.charAt(i));
            }
            if (k == best.length) line.append(tail);
            String l = line.toString();
            if (k > 0) l = l.replaceFirst("^((?:\u00A7.)*)[ \u00A0]+", "$1");
            out.add(l.replaceAll("[ \u00A0]+((?:\u00A7.)*)$", "$1"));
            from = to;
        }
        return out;
    }

    /**
     * Lines for one translated tooltip line: {@code source} are the original lines it replaces (one line, or
     * a wrapped paragraph), {@code tooltipPx} is the width of the widest original line. A paragraph is
     * wrapped no wider than the tooltip already is; a single line only when it would make the tooltip
     * clearly wider.
     */
    public static List<String> layout(String zh, List<String> source, int tooltipPx) {
        int src = 0;
        for (String l : source) src = Math.max(src, widthPx(l));
        int limit = source.size() > 1 ? Math.max(140, Math.max(src, Math.min(tooltipPx, 300)))
                : Math.max(160, Math.max(src, tooltipPx) * 11 / 10 + 10);
        return wrapPx(zh, limit);
    }

    private static boolean isWordStart(char c) { return Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '#' || c == '/'; }
    private static boolean isWordEnd(char c) { return Character.isLetterOrDigit(c) || c == '%' || c == ')' ; }

    /** Greedy line breaking at the best opportunity once a line would exceed {@code target}. */
    private static int[] greedy(List<Integer> px, int[] prio, int target, int maxPx) {
        List<Integer> cuts = new ArrayList<>();
        int n = px.size(), start = 0, w = 0;
        for (int i = 0; i < n; i++) {
            if (w + px.get(i) > target && i > start) {
                int cut = -1, cutPrio = 0, run = 0;
                // the best place in the last 40% of the line; failing that, anywhere past its first third
                for (int pass = 0; pass < 2 && cut < 0; pass++) {
                    run = 0;
                    for (int j = start + 1; j <= i; j++) {
                        run += px.get(j - 1);
                        boolean far = pass == 0 ? run * 5 >= target * 3 : run * 3 >= target;
                        if (prio[j] > 0 && far && prio[j] >= cutPrio) { cut = j; cutPrio = prio[j]; }
                    }
                }
                if (cut < 0 && w + px.get(i) <= maxPx) { w += px.get(i); continue; }   // no place to break yet
                if (cut < 0) cut = i;                                                       // hard cut
                cuts.add(cut);
                w = 0;
                for (int j = cut; j <= i; j++) w += px.get(j);
                start = cut;
                continue;
            }
            w += px.get(i);
        }
        int[] r = new int[cuts.size()];
        for (int k = 0; k < r.length; k++) r[k] = cuts.get(k);
        return r;
    }

    /** Lower is better: balanced line widths, breaks at punctuation, no near-empty last line. */
    private static int score(List<Integer> px, int[] cuts, int[] prio) {
        int n = px.size(), from = 0, max = 0, min = Integer.MAX_VALUE, s = 0;
        for (int k = 0; k <= cuts.length; k++) {
            int to = k < cuts.length ? cuts[k] : n, w = 0;
            for (int i = from; i < to; i++) w += px.get(i);
            max = Math.max(max, w);
            min = Math.min(min, w);
            if (k < cuts.length) s += prio[cuts[k]] >= 3 ? 0 : prio[cuts[k]] == 2 ? 10 : 30;
            if (k == cuts.length && w * 3 < max) s += 120;   // a short last line
            from = to;
        }
        return s + (max - min);
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
