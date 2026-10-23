package com.npctranslator;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Complete requirement sentences and explicitly scoped NPC dialogue. */
public final class ContextText {
    private ContextText() {}
    private static final Map<String,String> CROPS = new HashMap<>();
    private static final PhraseMatcher RYAN;
    private static final Pattern CONTEST = Pattern.compile("^Requires (Bronze|Silver|Gold) in a (.+) Contest!$");
    private static final Pattern LEVEL = Pattern.compile("^Requires tool level ([0-9,]+)\\+ to apply\\.$");
    private static final Pattern ENCHANT = Pattern.compile("^(Can be applied up to|Requires) (Efficiency|Bane of Arthropods) ([IVXLCDM]+)([.!])$");
    static {
        List<String[]> entries=new ArrayList<>();
        try(var in=ContextText.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/context_text.json")) {
            if(in!=null){var root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
                for(var e:root.getAsJsonObject("crops").entrySet())CROPS.put(e.getKey(),e.getValue().getAsString());
                for(var e:root.getAsJsonArray("ryan")){var o=e.getAsJsonObject();entries.add(new String[]{o.get("en").getAsString(),o.get("zh").getAsString()});}
            }
        } catch(Exception e){System.err.println("[NPCTranslator] context text: "+e.getMessage());}
        RYAN=new PhraseMatcher(entries).withValueTranslator(ContextText::dialogueValue);
    }
    private static String dialogueValue(String s) {
        String t=TranslationUtil.stripColorCodes(s).trim();
        Matcher badge=Pattern.compile("^(Campfire (?:Initiate|Adept|Cultist|Scion|God) Badge)(?: ([IVXLCDM]+))?$").matcher(t);
        if(badge.matches()){
            String zh=TableNames.exact(badge.group(1));
            return zh==null?s:leading(s)+zh+(badge.group(2)==null?"":" "+badge.group(2));
        }
        if(Set.of("Dark Oak Log","Spruce Log","Enchanted Acacia Log").contains(t)){
            String zh=TableNames.exact(t);return zh==null?s:leading(s)+zh;
        }
        // Player names and numeric/roman values are preserved verbatim.
        return s;
    }
    public static String npcDialogue(String speaker,String body) {
        return "Ryan".equals(speaker)?RYAN.translate(body):null;
    }
    private static String leading(String raw){Matcher m=Pattern.compile("^(?:§.)*").matcher(raw);return m.find()?m.group():"";}
    /** Slice a captured value from the formatted source, retaining its active colour. */
    private static String value(String raw,String plain,int from,int to){
        int n=0,start=-1,end=raw.length();String active="";
        for(int i=0;i<raw.length();i++){
            if(raw.charAt(i)=='§'&&i+1<raw.length()){if(n<=from)active=raw.substring(i,i+2);i++;continue;}
            if(n==from&&start<0)start=i;if(n==to){end=i;break;}n++;
        }
        return start<0?plain.substring(from,to):active+raw.substring(start,end);
    }
    public static String requirement(String raw) {
        if(raw==null)return null;
        String plain=TranslationUtil.stripColorCodes(raw);String t=plain.trim();int offset=plain.indexOf(t);
        Matcher m=CONTEST.matcher(t);
        if(m.matches()){
            String crop=CROPS.get(m.group(2));if(crop==null)return null;
            String medal=Map.of("Bronze","铜","Silver","银","Gold","金").get(m.group(1));
            return leading(raw)+"需要在"+crop+"竞赛中获得"+medal+"牌！";
        }
        m=LEVEL.matcher(t);
        if(m.matches())return leading(raw)+"需要工具等级达到 "+value(raw,plain,offset+m.start(1),offset+m.end(1))+"+"+leading(raw)+" 才能使用。";
        m=ENCHANT.matcher(t);
        if(m.matches()){
            String zh=RuntimeText.enchantment(m.group(2));if(zh==null)return null;
            return leading(raw)+(m.group(1).startsWith("Can")?"最高可应用至":"需要")+zh+" "+value(raw,plain,offset+m.start(3),offset+m.end(3))+leading(raw)+(m.group(4).equals("!")?"！":"。");
        }
        return null;
    }
}
