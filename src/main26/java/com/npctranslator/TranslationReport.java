package com.npctranslator;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * "This translation is wrong": writes the last hovered item's original tooltip and the tooltip as
 * shown into config/npc_translator_reports.txt, so line-wrapping and colour problems can be fixed
 * with the full context.
 */
public final class TranslationReport {

    public static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_reports.txt");

    private static volatile List<Component> hoverOriginal;
    private static volatile long hoverAt;
    private static volatile List<Component> lastOriginal;
    private static volatile List<Component> lastShown;
    private static volatile String lastMenu;

    private TranslationReport() {}

    /** The item tooltip as the game built it (before translation). */
    public static void original(List<Component> tooltip) {
        if (tooltip == null || tooltip.isEmpty()) return;
        hoverOriginal = new ArrayList<>(tooltip);
        hoverAt = System.currentTimeMillis();
    }

    /** The tooltip that is about to be drawn. */
    public static void shown(List<Component> tooltip) {
        List<Component> orig = hoverOriginal;
        if (tooltip == null || tooltip.isEmpty() || orig == null || System.currentTimeMillis() - hoverAt > 300) return;
        lastOriginal = orig;
        lastShown = new ArrayList<>(tooltip);
        lastMenu = NPCTranslatorClient.currentMenu();
    }

    /** Name of the item that would be reported, or null when nothing was hovered yet. */
    public static String pendingName() {
        List<Component> o = lastOriginal;
        return o == null ? null : o.get(0).getString();
    }

    /** Appends the last hovered item to the report file; returns its name, or null when there is none. */
    public static synchronized String write(String note) {
        List<Component> orig = lastOriginal, shown = lastShown;
        if (orig == null || shown == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("==== ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sb.append(" | 菜单：").append(lastMenu != null ? TextCollector.anonymize(lastMenu) : "（背包）");
        sb.append(" | 版本：").append(FabricLoader.getInstance().getModContainer("hypixel-npc-and-items-translate")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
        if (note != null && !note.isBlank()) sb.append("备注：").append(note.trim()).append('\n');
        sb.append("[原文]\n");
        for (Component c : orig) sb.append("  ").append(line(c)).append('\n');
        sb.append("[显示]\n");
        for (Component c : shown) sb.append("  ").append(line(c)).append('\n');
        sb.append('\n');
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE, true), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        } catch (Exception e) {
            System.err.println("[NPCTranslator] report: " + e.getMessage());
            return null;
        }
        return orig.get(0).getString();
    }

    /** §-formatted text with the colour codes written as &x so the file stays readable. */
    private static String line(Component c) {
        String f;
        try {
            f = NPCTranslatorClient.toFormattedString(c);
        } catch (Throwable t) {
            f = c.getString();
        }
        return TextCollector.anonymize(f.replace('§', '&'));
    }
}
