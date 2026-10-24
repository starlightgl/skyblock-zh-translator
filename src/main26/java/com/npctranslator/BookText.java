package com.npctranslator;

import com.npctranslator.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Written books (quest log stories, letters): every paragraph of a page is collected ("书本"), and paragraphs
 * with a translation in the term table are shown in Chinese. Pages with clickable or hoverable text are only
 * collected, so their links keep working.
 */
public final class BookText {

    private BookText() {}

    private static final Map<String, Component> CACHE = new ConcurrentHashMap<>();

    public static Component page(Component page) {
        if (page == null) return null;
        String raw;
        try {
            raw = NPCTranslatorClient.toFormattedString(page);
        } catch (Throwable t) {
            return page;
        }
        if (raw.trim().isEmpty()) return page;
        Component cached = CACHE.get(raw);
        if (cached != null) return cached;
        String[] paragraphs = raw.split("\n", -1);
        for (String p : paragraphs) if (!p.trim().isEmpty()) TextCollector.record(TextCollector.BOOK, "书本", p.trim(), null);
        ModConfig c = ModConfig.INSTANCE;
        Component shown = page;
        if (c != null && c.enabled && c.usePhrasebook && !hasEvents(page)) {
            StringBuilder out = new StringBuilder();
            boolean any = false;
            for (int i = 0; i < paragraphs.length; i++) {
                if (i > 0) out.append('\n');
                String p = paragraphs[i];
                String zh = p.trim().isEmpty() ? null : PhraseBook.translate(p);
                if (zh != null) { out.append(zh); any = true; } else out.append(p);
            }
            if (any) shown = NPCTranslatorClient.parseColorCodes(out.toString());
        }
        if (CACHE.size() > 500) CACHE.clear();
        CACHE.put(raw, shown);
        return shown;
    }

    private static boolean hasEvents(Component page) {
        boolean[] events = {false};
        page.visit((style, text) -> {
            if (style.getClickEvent() != null || style.getHoverEvent() != null) events[0] = true;
            return Optional.empty();
        }, Style.EMPTY);
        return events[0];
    }
}
