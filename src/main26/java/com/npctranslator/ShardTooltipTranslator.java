package com.npctranslator;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/** Table-driven shard paragraphs, scoped to the exact known shard title. */
public final class ShardTooltipTranslator {
    private static final Set<String> NAMES = new HashSet<>();
    private static final ShardPhraseMatcher MATCHER;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    static {
        List<String[]> entries = new ArrayList<>();
        try (InputStream in = ShardTooltipTranslator.class.getResourceAsStream(
                "/assets/hypixel-npc-and-items-translate/defaults/table_shards.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : root.getAsJsonObject("names").entrySet()) {
                    NAMES.add(PhraseMatcher.normalize(e.getKey()));
                    entries.add(new String[]{e.getKey(), e.getValue().getAsString()});
                }
                for (JsonElement e : root.getAsJsonArray("entries")) {
                    JsonObject r = e.getAsJsonObject();
                    entries.add(new String[]{r.get("en").getAsString(),r.get("zh").getAsString()});
                }
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] Failed to load shard table: " + e.getMessage());
        }
        MATCHER = new ShardPhraseMatcher(entries, true);
    }
    private ShardTooltipTranslator() {}
    public static boolean isShard(List<String> lines) {
        return !lines.isEmpty() && isShardName(lines.get(0));
    }
    public static boolean isShardName(String name) {
        return NAMES.contains(PhraseMatcher.normalize(name));
    }
    static String cacheText(String name, String visibleText) {
        return isShardName(name) ? visibleText : TranslationUtil.normalize(visibleText).text;
    }
    /** Preserve numerical shard levels in the existing item cache; other items keep their current key. */
    public static TranslationUtil.Template cacheTemplate(String visibleText) {
        int end = visibleText.indexOf('\n');
        String name = end < 0 ? visibleText : visibleText.substring(0, end);
        return isShardName(name) ? TranslationUtil.Template.raw(visibleText) : TranslationUtil.normalize(visibleText);
    }
    public static String[] translateLines(List<String> lines) {
        if (!isShard(lines)) return new String[lines.size()];
        // Long shard effects are one paragraph. Blank lines and field labels still
        // delimit them; this limit never applies to ordinary item translation.
        String[] out = MATCHER.translateLines(lines, 24);
        // Uncatalogued flavour is game text, not player chat. Collect whole
        // paragraphs once, with the known shard title, without requiring F8.
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).contains("§o")) continue;
            int end = i + 1;
            while (end < lines.size() && !PhraseMatcher.normalize(lines.get(end)).isEmpty()
                    && lines.get(end).contains("§o")) end++;
            boolean missing = false;
            for (int k=i;k<end;k++) if(out[k]==null) missing=true;
            if(!missing){i=end-1;continue;}
            String paragraph = String.join(" ", lines.subList(i,end));
            String original = paragraph.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
            String title = lines.get(0).replaceAll("§.", "");
            if (original.matches(".*[A-Za-z]{3,}.*") && REPORTED.add(title + "\n" + original)) {
                try {
                    JsonObject record = new JsonObject();
                    record.addProperty("shard",title);record.addProperty("original",original);
                    Path file = FabricLoader.getInstance().getConfigDir().resolve("npc_translator_shards_missing.jsonl");
                    synchronized (REPORTED) {
                        Files.writeString(file, record + "\n", StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    }
                } catch (IOException ignored) {}
            }
            i=end-1;
        }
        return out;
    }
}
