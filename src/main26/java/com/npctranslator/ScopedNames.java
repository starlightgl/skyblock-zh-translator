package com.npctranslator;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Full-name rules bound to display surfaces. Never performs a global word substitution. */
public final class ScopedNames {
    private ScopedNames() {}
    private static final Map<String,String> WORLD=new HashMap<>(), REGIONS=new HashMap<>(), SCREEN=new HashMap<>(), LEVELS=new HashMap<>();
    private static Pattern level=Pattern.compile("(?!)");
    private static final Pattern MUSEUM=Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z0-9_]{1,16}|\\{玩家})'s Museum(?=$|[^A-Za-z])");
    static {
        try(var in=ScopedNames.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/scoped_names.json")) {
            if(in!=null) {
                JsonObject root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
                read(root,"world",WORLD);read(root,"regions",REGIONS);read(root,"screen",SCREEN);read(root,"levels",LEVELS);
                var names=new ArrayList<>(LEVELS.keySet());names.sort(Comparator.comparingInt(String::length).reversed());
                level=Pattern.compile("^\\[Lv(\\d+)] [^A-Za-z0-9]*("+String.join("|",names.stream().map(Pattern::quote).toList())+")(?=$| [0-9,.]+/[0-9,.]+[^A-Za-z]*$)");
            }
        } catch(Exception e) { System.err.println("[NPCTranslator] scoped names: "+e.getMessage()); }
    }
    private static void read(JsonObject root,String key,Map<String,String> out) { for(var e:root.getAsJsonObject(key).entrySet())out.put(e.getKey(),e.getValue().getAsString()); }
    private static final class View {
        final String raw,text;final int[] pos;
        View(String raw) {
            this.raw=raw;StringBuilder b=new StringBuilder();int[] p=new int[raw.length()+1];int n=0;
            for(int i=0;i<raw.length();i++){if(raw.charAt(i)=='§'&&i+1<raw.length()){i++;continue;}p[n++]=i;b.append(raw.charAt(i));}
            p[n]=raw.length();pos=Arrays.copyOf(p,n+1);text=b.toString();
        }
        String replace(int a,int b,String zh){return raw.substring(0,pos[a])+zh+raw.substring(b>a?pos[b-1]+1:pos[b]);}
    }
    private static String exact(String raw,Map<String,String> map) {
        View v=new View(raw);String t=v.text.trim(),zh=map.get(t);return zh==null?null:v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),zh);
    }
    /** Only called for known location fields, title area messages or region-icon segments. */
    public static String museum(String raw,boolean markedOnly) {
        View v=new View(raw);Matcher m=MUSEUM.matcher(v.text);List<int[]> spans=new ArrayList<>();
        while(m.find()) {
            String prefix=v.text.substring(0,m.start());
            if(markedOnly&&!prefix.matches("(?s).*[\\ue067⏣]\\s*"))continue;
            spans.add(new int[]{m.start(1)+m.group(1).length(),m.end()});
        }
        for(int i=spans.size()-1;i>=0;i--){int[] p=spans.get(i);raw=new View(raw).replace(p[0],p[1]," 的博物馆");}
        return spans.isEmpty()?null:raw;
    }
    public static String screenTitle(String raw) {
        String out=exact(raw,SCREEN);if(out!=null)return out;
        View v=new View(raw);if(MUSEUM.matcher(v.text.trim()).matches())return museum(raw,false);
        return null;
    }
    private static String replace(String raw,String regex,String zh) {
        View v=new View(raw);Matcher m=Pattern.compile(regex).matcher(v.text);List<int[]> spans=new ArrayList<>();
        while(m.find())spans.add(new int[]{m.start(),m.end()});
        for(int i=spans.size()-1;i>=0;i--){int[] p=spans.get(i);raw=new View(raw).replace(p[0],p[1],zh);}return raw;
    }
    public static String world(String raw) {
        String out=exact(raw,WORLD);if(out!=null)return out;
        View v=new View(raw);String t=v.text.trim();int offset=v.text.indexOf(t);Matcher m=level.matcher(t);
        if(m.find()) {
            out=v.replace(offset+m.start(2),offset+m.end(2),LEVELS.get(m.group(2)));
            return new View(out).replace(offset+1,offset+3,"等级 ");
        }
        if(t.matches("Cost: [0-9,.]+ Gems"))return replace(replace(raw,"^Cost:","价格："),"Gems$","宝石");
        if(t.matches("Year \\d+"))return replace(raw,"^Year ","年份：");
        if(t.matches("Time left: (?:\\d+[dhms] ?)+")) {
            out=replace(raw,"^Time left:","剩余时间：");
            for(var e:Map.of("d","天","h","小时","m","分钟","s","秒").entrySet())out=replace(out,"(?<=\\d)"+e.getKey()+"(?= |$)",e.getValue());
            return out;
        }
        if(t.matches("Atrium Slot #\\d+"))return replace(raw,"^Atrium Slot","中庭展位");
        if(t.matches("New Year Cake \\(Year \\d+\\)"))return replace(replace(raw,"^New Year Cake","新年蛋糕"),"Year ","年份：");
        m=Pattern.compile("^✦ Warp To (.+)$").matcher(t);
        if(m.matches()&&REGIONS.containsKey(m.group(1)))return v.replace(offset,offset+t.length(),"✦ 传送至"+REGIONS.get(m.group(1)));
        return null;
    }
}
