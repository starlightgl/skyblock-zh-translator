package com.npctranslator;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native Bazaar price levels: translate words, never cache or recompute market values. */
public final class OrderBookText {
    private OrderBookText() {}
    private static final String NUMBER = "[0-9][0-9,.]*";
    private static final Pattern ROW = Pattern.compile("^\\s*-\\s*(" + NUMBER
            + ")\\s+(coins each)\\s*\\|\\s*(" + NUMBER
            + ")(x)\\s+(in|from)\\s+(" + NUMBER + ")\\s+(orders?|offers?)\\s*$");
    private static final Pattern TEMPLATE = Pattern.compile("^\\s*-\\s*\\{\\d+} coins each\\s*\\|\\s*\\{\\d+}x (in|from) \\{\\d+} (orders?|offers?)\\s*$");
    private static final Map<String, String> HEADINGS = Map.ofEntries(
            Map.entry("Top Orders:", "最高买单："), Map.entry("Top Offers:", "最低卖单："),
            Map.entry("Only Order:", "唯一买单："), Map.entry("Only Offer:", "唯一卖单："),
            Map.entry("Open Order Book", "打开订单簿"));
    private static final class View {
        final String raw, plain;
        final int[] positions;
        View(String raw) {
            this.raw = raw;
            StringBuilder text = new StringBuilder(raw.length());
            positions = new int[raw.length() + 1];
            for (int i = 0; i < raw.length(); i++) {
                if (raw.charAt(i) == '§' && i + 1 < raw.length()) { i++; continue; }
                positions[text.length()] = i;
                char ch = raw.charAt(i);
                text.append(ch == '\u00a0' || ch == '\u202f' ? ' ' : ch);
            }
            positions[text.length()] = raw.length();
            plain = text.toString();
        }
        String replace(int a, int b, String zh) {
            return raw.substring(0, positions[a]) + zh + raw.substring(positions[b - 1] + 1);
        }
    }
    private static boolean candidate(String raw) {
        return raw != null && raw.indexOf('|') >= 0 && raw.contains("coins");
    }
    private static boolean valid(String relation, String noun) {
        return relation.equals("in") ? noun.startsWith("order") : noun.startsWith("offer");
    }
    /** Single and plural are one capture template. Recognises saved templates for migration too. */
    public static String collectionTemplate(String raw) {
        if (!candidate(raw)) return null;
        View v = new View(raw);
        Matcher row = ROW.matcher(v.plain);
        String relation, noun;
        if (row.matches()) { relation = row.group(5); noun = row.group(7); }
        else {
            Matcher template = TEMPLATE.matcher(v.plain);
            if (!template.matches()) return null;
            relation = template.group(1); noun = template.group(2);
        }
        if (!valid(relation, noun)) return null;
        return "- {0} coins each | {1}x " + relation + " {2} "
                + (relation.equals("in") ? "orders" : "offers");
    }
    /** Price levels and headings are table boundaries, never wrapped prose. */
    public static boolean isBoundary(String raw) {
        if (raw == null) return false;
        if (candidate(raw) && collectionTemplate(raw) != null) return true;
        if (!raw.contains("Order") && !raw.contains("Offer")) return false;
        return HEADINGS.containsKey(new View(raw).plain.trim());
    }
    public static String cacheLine(String raw) {
        String shape = collectionTemplate(raw);
        // Placeholders would stop TranslationUtil from normalising the rest of the tooltip.
        return shape == null ? raw : shape.replace("{0}", "0").replace("{1}", "0").replace("{2}", "0");
    }
    public static String translate(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!candidate(raw)) {
            if (!raw.contains("Order") && !raw.contains("Offer")) return null;
            View v = new View(raw); String text = v.plain.trim(), zh = HEADINGS.get(text);
            if (zh == null) return null;
            int at = v.plain.indexOf(text); return v.replace(at, at + text.length(), zh);
        }
        View v = new View(raw); Matcher m = ROW.matcher(v.plain);
        if (!m.matches() || !valid(m.group(5), m.group(7))) return null;
        // Backwards replacements leave every numeric span, separator and numeric style untouched.
        String out = v.replace(m.start(7), m.end(7), m.group(5).equals("in") ? "笔买单" : "笔卖单");
        out = new View(out).replace(m.start(5), m.end(5), m.group(5).equals("in") ? "分布于" : "来自");
        out = new View(out).replace(m.start(4), m.end(4), " 件");
        return new View(out).replace(m.start(2), m.end(2), "硬币/件");
    }
}
