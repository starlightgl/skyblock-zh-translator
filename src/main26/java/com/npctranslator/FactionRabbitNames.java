package com.npctranslator;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** A proper rabbit name is resolved only inside a verified faction-rabbit or Hoppity-rabbit tooltip. */
public final class FactionRabbitNames {
    private static final Map<String,String> NAMES = new HashMap<>();
    private static final Map<String,String> HOPPITY = new HashMap<>();
    private static final Pattern FACTION = Pattern.compile("\\b(?:City|Mountain|Country|Beach|Forest|Water) Faction Rabbit\\b");
    /** The rarity line of a Hoppity rabbit ("COMMON RABBIT", "DIVINE RABBIT"). */
    private static final Pattern HOPPITY_RABBIT = Pattern.compile("\\b(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL) RABBIT\\b");
    static {
        try (InputStream in=FactionRabbitNames.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/faction_rabbit_names.json")) {
            if(in!=null) {
                JsonObject root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
                for(var e:root.getAsJsonObject("names").entrySet()) NAMES.put(e.getKey(),e.getValue().getAsString());
                if(root.has("hoppity")) for(var e:root.getAsJsonObject("hoppity").entrySet()) HOPPITY.put(e.getKey(),e.getValue().getAsString());
            }
        } catch(Exception e) { System.err.println("[NPCTranslator] faction rabbit names: "+e.getMessage()); }
    }
    private FactionRabbitNames() {}
    public static String tooltipName(List<String> lines) {
        if(lines.isEmpty()) return null;
        String title=lines.get(0);String plain=title.replaceAll("§.","").trim();
        if(!NAMES.containsKey(plain)&&!HOPPITY.containsKey(plain)) return null;
        String body=String.join(" ",lines.subList(1,lines.size())).replaceAll("§.","").replaceAll("\\s+"," ");
        String zh=null;
        if(NAMES.containsKey(plain)&&FACTION.matcher(body).find()) zh=NAMES.get(plain);
        else if(HOPPITY.containsKey(plain)&&HOPPITY_RABBIT.matcher(body).find()) zh=HOPPITY.get(plain);
        if(zh==null) return null;
        var lead=Pattern.compile("^(?:§.)*").matcher(title.trim());
        return (lead.find()?lead.group():"")+zh;
    }
}
