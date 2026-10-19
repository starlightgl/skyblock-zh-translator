package com.npctranslator;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lines with a fixed structure that would otherwise become one dictionary entry per item,
 * e.g. the Bazaar history: "5m ago Instant Sell of 64x Enchanted Diamond by [MVP+] Name".
 * They are built locally from their parts; the item name goes through the glossary / phrase book.
 */
public final class PatternRules {

    private PatternRules() {}

    private static final Map<String, String> UNIT = Map.of("s", "秒", "m", "分钟", "h", "小时", "d", "天");
    private static final Map<String, String> ACTION = Map.of(
            "Instant Sell", "立即卖出",
            "Instant Buy", "立即买入",
            "Sell Offer", "挂单出售",
            "Buy Order", "挂单收购",
            "Claimed Sell Offer", "领取挂单出售",
            "Claimed Buy Order", "领取挂单收购",
            "Cancelled Sell Offer", "取消挂单出售",
            "Cancelled Buy Order", "取消挂单收购");

    /** Bazaar history entry, matched on the text without colour codes. */
    private static final Pattern HISTORY = Pattern.compile(
            "^(\\d+)([smhd]) ago (Instant Sell|Instant Buy|Claimed Sell Offer|Claimed Buy Order|Cancelled Sell Offer|Cancelled Buy Order|Sell Offer|Buy Order) of ([\\d,]+)x\\s+(.+?)(?:\\s+by\\s+(.+))?$");

    /** §-formatted Chinese line, or null when no rule applies. */
    public static String translate(String formatted) {
        if (formatted == null || formatted.length() < 10 || !NPCTranslatorClient.corpusAllowed()) return null;
        String plain = TranslationUtil.stripColorCodes(formatted).replaceAll("\\s+", " ").trim();
        Matcher m = HISTORY.matcher(plain);
        if (!m.matches()) return null;
        String item = m.group(5).trim();
        String itemZh = itemName(item);
        StringBuilder out = new StringBuilder()
                .append("§8").append(m.group(1)).append(UNIT.get(m.group(2))).append("前 ")
                .append("§6").append(ACTION.get(m.group(3))).append(" ")
                .append("§a").append(m.group(4)).append("§7x ")
                .append(itemColour(formatted, item)).append(itemZh);
        if (m.group(6) != null) {
            // keep the player's rank colours: take the formatted text after the last "by "
            int by = formatted.lastIndexOf("by ");
            String who = by >= 0 ? formatted.substring(by + 3).trim() : m.group(6);
            out.append(" §7由 ").append(who);
        }
        return TermFixes.apply(out.toString());
    }

    private static String itemName(String item) {
        try {
            String g = Glossary.index().exact(item);
            if (g != null) return g;
            String pb = PhraseBook.translate(item);
            if (pb != null) return TranslationUtil.stripColorCodes(pb).trim();
        } catch (Throwable ignored) {
            // data not loaded yet: keep the English name
        }
        return item;
    }

    /** Colour codes active where the item name starts in the original line (grey when none). */
    private static String itemColour(String formatted, String item) {
        String codes = PhraseMatcher.codesBefore(formatted, item);
        return codes == null || codes.isEmpty() ? "§7" : codes;
    }
}
