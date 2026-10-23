package com.npctranslator;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** A proper rabbit name is resolved only inside a verified faction-rabbit tooltip. */
public final class FactionRabbitNames {
    private static final Map<String,String> NAMES = new HashMap<>();
    private static final Pattern FACTION = Pattern.compile("\\b(?:City|Mountain|Country|Beach|Forest|Water) Faction Rabbit\\b");
    static {
        try (InputStream in=FactionRabbitNames.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/faction_rabbit_names.json")) {
            if(in!=null) for(var e:JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("names").entrySet()) NAMES.put(e.getKey(),e.getValue().getAsString());
        } catch(Exception e) { System.err.println("[NPCTranslator] faction rabbit names: "+e.getMessage()); }
    }
    private FactionRabbitNames() {}
    public static String tooltipName(List<String> lines) {
        if(lines.isEmpty()) return null;
        String title=lines.get(0);String plain=title.replaceAll("§.","").trim();
        String zh=NAMES.get(plain);if(zh==null) return null;
        String body=String.join(" ",lines.subList(1,lines.size())).replaceAll("§.","").replaceAll("\\s+"," ");
        if(!FACTION.matcher(body).find()) return null;
        var lead=Pattern.compile("^(?:§.)*").matcher(title.trim());
        return (lead.find()?lead.group():"")+zh;
    }
}
