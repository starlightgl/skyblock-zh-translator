package com.npctranslator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.concurrent.ConcurrentHashMap;

public class TranslationDictionary {
    private static final File DICT_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "npc_translator_dict.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Original Text -> Translated Text
    private static ConcurrentHashMap<String, String> dictionary = new ConcurrentHashMap<>();

    public static void load() {
        if (DICT_FILE.exists()) {
            try (FileReader reader = new FileReader(DICT_FILE)) {
                Type type = new TypeToken<ConcurrentHashMap<String, String>>() {}.getType();
                ConcurrentHashMap<String, String> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    dictionary = loaded;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static synchronized void save() {
        try (FileWriter writer = new FileWriter(DICT_FILE)) {
            GSON.toJson(dictionary, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String get(String original, String provider) {
        return TermFixes.apply(dictionary.get(provider + ":" + original));
    }

    public static void put(String original, String translated, String provider) {
        if (original == null || translated == null || original.trim().isEmpty() || translated.trim().isEmpty()) return;
        dictionary.put(provider + ":" + original, translated);
    }

    public static boolean has(String original, String provider) {
        return dictionary.containsKey(provider + ":" + original);
    }

    public static void clear() {
        dictionary.clear();
        if (DICT_FILE.exists()) {
            DICT_FILE.delete();
        }
    }

    public static void remove(String original, String provider) {
        if (original != null) dictionary.remove(provider + ":" + original);
    }

    /** Entries whose original or translation contains the keyword (colour codes ignored, case-insensitive). */
    public static java.util.List<java.util.Map.Entry<String, String>> find(String keyword, int limit) {
        java.util.List<java.util.Map.Entry<String, String>> out = new java.util.ArrayList<>();
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        for (java.util.Map.Entry<String, String> e : dictionary.entrySet()) {
            if (matches(e, kw)) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    public static int removeMatching(String keyword) {
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        int n = 0;
        for (java.util.Map.Entry<String, String> e : new java.util.ArrayList<>(dictionary.entrySet())) {
            if (matches(e, kw) && dictionary.remove(e.getKey()) != null) n++;
        }
        if (n > 0) save();
        return n;
    }

    private static boolean matches(java.util.Map.Entry<String, String> e, String kw) {
        String key = e.getKey();
        if (key.startsWith("ITEM_SEEN:")) return false;
        int colon = key.indexOf(':');
        String original = colon >= 0 ? key.substring(colon + 1) : key;
        return TranslationUtil.stripColorCodes(original).toLowerCase(java.util.Locale.ROOT).contains(kw)
                || TranslationUtil.stripColorCodes(e.getValue()).toLowerCase(java.util.Locale.ROOT).contains(kw);
    }

    /** Menu of an entry key ("CUSTOM@Bazaar ➜ Mining:..." → "Bazaar ➜ Mining"), or null for shared entries. */
    public static String menuOf(String key) {
        int colon = key.indexOf(':');
        String ns = colon >= 0 ? key.substring(0, colon) : key;
        int at = ns.indexOf('@');
        return at >= 0 ? ns.substring(at + 1) : null;
    }

    /** Number of saved entries per menu, most first. */
    public static java.util.List<java.util.Map.Entry<String, Integer>> menus() {
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (String key : dictionary.keySet()) {
            String menu = menuOf(key);
            if (menu != null) count.merge(menu, 1, Integer::sum);
        }
        java.util.List<java.util.Map.Entry<String, Integer>> out = new java.util.ArrayList<>(count.entrySet());
        out.sort((a, b) -> b.getValue() - a.getValue());
        return out;
    }

    /** Removes every entry saved under a menu whose name contains the keyword (case-insensitive). */
    public static int removeMenu(String keyword) {
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        int n = 0;
        for (String key : new java.util.ArrayList<>(dictionary.keySet())) {
            String menu = menuOf(key);
            if (menu != null && menu.toLowerCase(java.util.Locale.ROOT).contains(kw) && dictionary.remove(key) != null) n++;
        }
        if (n > 0) save();
        return n;
    }

    /** Removes the entries saved under exactly this menu. */
    public static int removeMenuExact(String menu) {
        int n = 0;
        for (String key : new java.util.ArrayList<>(dictionary.keySet())) {
            if (menu.equals(menuOf(key)) && dictionary.remove(key) != null) n++;
        }
        if (n > 0) save();
        return n;
    }

    public static int size() {
        return dictionary.size();
    }
}
