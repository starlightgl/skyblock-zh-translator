package com.npctranslator;

import com.npctranslator.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hover text of chat and other text components ("show text" hover events: NPC dialogue options, reward
 * details, "Click to …" hints): collected ("悬停提示") and translated line by line, wrapped sentences joined.
 */
public final class HoverText {

    private HoverText() {}

    private static final Map<String, FormattedText> CACHE = new ConcurrentHashMap<>();

    public static FormattedText text(FormattedText value) {
        if (!(value instanceof Component c)) return value;
        String raw;
        try {
            raw = NPCTranslatorClient.toFormattedString(c);
        } catch (Throwable t) {
            return value;
        }
        if (raw.trim().isEmpty()) return value;
        FormattedText cached = CACHE.get(raw);
        if (cached != null) return cached;
        List<String> lines = new ArrayList<>(Arrays.asList(raw.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (!l.isEmpty()) TextCollector.record(TextCollector.HOVER, "", l, null);
        }
        TextCollector.recordSentences(TextCollector.HOVER, prepend(lines));
        FormattedText shown = value;
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg != null && cfg.enabled && cfg.usePhrasebook) {
            // line 0 of a tooltip is never joined (item name): put an empty line in front
            String[] out = PhraseBook.translateLines(prepend(lines));
            StringBuilder sb = new StringBuilder();
            boolean any = false, first = true;
            for (int i = 0; i < lines.size(); i++) {
                String t = out[i + 1];
                if (t == PhraseMatcher.MERGED) continue;
                if (!first) sb.append('\n');
                first = false;
                if (t != null) { sb.append(t); any = true; } else sb.append(lines.get(i));
            }
            if (any) shown = NPCTranslatorClient.parseColorCodes(sb.toString());
        }
        if (CACHE.size() > 2000) CACHE.clear();
        CACHE.put(raw, shown);
        return shown;
    }

    /** recordSentences skips line 0 (an item name); hover text has none, so give it an empty first line. */
    private static List<String> prepend(List<String> lines) {
        List<String> l = new ArrayList<>(lines.size() + 1);
        l.add("");
        l.addAll(lines);
        return l;
    }
}
