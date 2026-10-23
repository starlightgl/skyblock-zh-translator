package com.npctranslator;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Deterministic HUD translations. Never uses the saved AI dictionary or changes a player profile. */
public final class HudText {
    private HudText() {}
    private static final Pattern CODES = Pattern.compile("§.");
    private static final Pattern LEGAL = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern NUMBER = Pattern.compile("\\{(\\d+)}");
    private static final Map<String,String> REGIONS = new LinkedHashMap<>(), LABELS = new HashMap<>(), VALUES = new HashMap<>();
    private static final List<Rule> RULES = new ArrayList<>(), BOSS_RULES = new ArrayList<>();
    private static final Map<String,String> OBJECTIVES = new HashMap<>(), BOSS_EVENTS = new HashMap<>();
    private static Pattern REGION_PATTERN = Pattern.compile("(?!)");
    private static final class Rule {
        Pattern pattern; String zh; List<Integer> numbers = new ArrayList<>(); int literal;
        Rule(String en, String zh) {
            this.zh=zh; Matcher m=NUMBER.matcher(en); StringBuilder b=new StringBuilder("^"); int last=0;
            while(m.find()) { b.append(Pattern.quote(en.substring(last,m.start()))).append("([+-]?[0-9][0-9,.]*)"); numbers.add(Integer.valueOf(m.group(1))); last=m.end(); }
            b.append(Pattern.quote(en.substring(last))).append('$'); pattern=Pattern.compile(b.toString()); literal=NUMBER.matcher(en).replaceAll("").length();
        }
    }
    static {
        try(var in=HudText.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/hud_text.json")) {
            if(in!=null) {
                JsonObject root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
                read(root,"regions",REGIONS); read(root,"labels",LABELS); read(root,"values",VALUES);
                for(var e:root.getAsJsonArray("entries")) { var r=e.getAsJsonObject(); RULES.add(new Rule(r.get("en").getAsString(),r.get("zh").getAsString())); }
                RULES.sort(Comparator.comparingInt((Rule r)->r.literal).reversed());
                if(root.has("objectives")) read(root,"objectives",OBJECTIVES);
                if(root.has("boss_events")) read(root,"boss_events",BOSS_EVENTS);
                if(root.has("boss")) for(var e:root.getAsJsonArray("boss")) { var r=e.getAsJsonObject(); BOSS_RULES.add(new Rule(r.get("en").getAsString(),r.get("zh").getAsString())); }
                BOSS_RULES.sort(Comparator.comparingInt((Rule r)->r.literal).reversed());
                List<String> names=new ArrayList<>(REGIONS.keySet()); names.sort(Comparator.comparingInt(String::length).reversed());
                REGION_PATTERN=Pattern.compile("[\ue067⏣]\\s*("+String.join("|",names.stream().map(Pattern::quote).toList())+")(?=$|[^A-Za-z'])");
            }
        } catch(Exception e) { System.err.println("[NPCTranslator] HUD rules: "+e.getMessage()); }
    }
    private static void read(JsonObject root,String key,Map<String,String> out) { for(var e:root.getAsJsonObject(key).entrySet()) out.put(e.getKey(),e.getValue().getAsString()); }
    private static String plain(String s) { return CODES.matcher(s).replaceAll(""); }
    /** Only scoreboard-only illegal codes are removed; valid colours, icons and numbers remain. */
    public static String cleanScoreboard(String s) { return s.replaceAll("(?i)§[^0-9a-fk-or]", ""); }
    private static final class View {
        final String raw,text; final int[] positions;
        View(String raw) {
            this.raw=raw; StringBuilder s=new StringBuilder(); int[] pos=new int[raw.length()+1]; int n=0;
            for(int i=0;i<raw.length();i++) { if(raw.charAt(i)=='§' && i+1<raw.length()) {i++;continue;} pos[n++]=i; s.append(raw.charAt(i)); }
            pos[n]=raw.length(); text=s.toString(); positions=Arrays.copyOf(pos,n+1);
        }
        String replace(int a,int b,String zh) { return raw.substring(0,positions[a])+zh+raw.substring(b>a?positions[b-1]+1:positions[b]); }
        String part(int a,int b) { return raw.substring(positions[a],b>a?positions[b-1]+1:positions[b]); }
        String style(int at) { StringBuilder s=new StringBuilder(); Matcher m=LEGAL.matcher(raw.substring(0,positions[at])); while(m.find())s.append(m.group()); return s.toString(); }
    }
    private static String value(String text) {
        String direct=VALUES.get(text); if(direct!=null) return direct;
        Matcher tier=Pattern.compile("^(.*?)( [IVXLCDM]+)$").matcher(text);
        if(tier.matches() && VALUES.containsKey(tier.group(1))) return VALUES.get(tier.group(1))+tier.group(2);
        return null;
    }
    private static String durations(String s) {
        return s.replaceAll("(%(?:\\d+\\$)?s)d\\b","$1 天").replaceAll("(%(?:\\d+\\$)?s)h\\b","$1 小时")
                .replaceAll("(%(?:\\d+\\$)?s)m\\b","$1 分钟").replaceAll("(%(?:\\d+\\$)?s)s\\b","$1 秒")
                .replaceAll("(?<=\\d)\\s*days?\\b"," 天").replaceAll("(?<=\\d)\\s*hours?\\b"," 小时")
                .replaceAll("(?<=\\d)\\s*Hours?\\b"," 小时").replaceAll("(?<=\\d)d\\b"," 天")
                .replaceAll("(?<=\\d)h\\b"," 小时").replaceAll("(?<=\\d)m\\b"," 分钟").replaceAll("(?<=\\d)s\\b"," 秒")
                .replace(" XP"," 经验");
    }
    private static String fieldValue(String s) {
        String museum=ScopedNames.museum(s,false);if(museum!=null)return museum;
        String v=value(s); if(v!=null)return v;
        if(s.startsWith("in "))return durations(s.substring(3))+"后";
        if(s.startsWith("for "))return "持续 "+durations(s.substring(4));
        if(s.endsWith(" left"))return "剩余 "+durations(s.substring(0,s.length()-5));
        return durations(s);
    }
    private static String regions(String raw) {
        String museum=ScopedNames.museum(raw,true);String out=museum==null?raw:museum;
        View v=new View(out); Matcher m=REGION_PATTERN.matcher(v.text);
        List<int[]> spans=new ArrayList<>(); List<String> translations=new ArrayList<>();
        while(m.find()) {spans.add(new int[]{m.start(1),m.end(1)});translations.add(REGIONS.get(m.group(1)));}
        for(int i=spans.size()-1;i>=0;i--) {int[] p=spans.get(i);out=new View(out).replace(p[0],p[1],translations.get(i));}
        return out;
    }
    private static String replaceLiteral(String raw,String en,String zh) {
        View v=new View(raw); Matcher m=Pattern.compile("(?<![A-Za-z])"+Pattern.quote(en)+"(?![A-Za-z])").matcher(v.text);List<int[]> spans=new ArrayList<>();
        while(m.find())spans.add(new int[]{m.start(),m.end()});
        for(int i=spans.size()-1;i>=0;i--) {int[] p=spans.get(i);raw=new View(raw).replace(p[0],p[1],zh);}return raw;
    }
    // ---- boss bar: quest objectives and timed events ----
    /** Direction arrows and "(3/128)" progress that SkyBlock appends to a boss bar text; kept as they are. */
    private static final Pattern BOSS_TAIL = Pattern.compile("^(\\s*)(.*?)((?:\\s*[⬉⬆⬈➡⬊⬇⬋⬅])?(?:\\s*\\(\\d[\\d,]*/\\d[\\d,]*\\))?(?:\\s*[⬉⬆⬈➡⬊⬇⬋⬅])?\\s*)$");
    private static final Pattern BOSS_EVENT = Pattern.compile("^(PASSIVE )?EVENT (.+?) (?:ACTIVE IN (.+?) |RUNNING )FOR (\\d+(?::\\d+)+)$", Pattern.CASE_INSENSITIVE);
    private static String region(String upper) {
        for(var e:REGIONS.entrySet()) if(e.getKey().equalsIgnoreCase(upper)) return e.getValue();
        return null;
    }
    private static String bossEvent(String name) {
        String zh=BOSS_EVENTS.get(name.toUpperCase(Locale.ROOT)); if(zh!=null) return zh;
        Matcher x=Pattern.compile("^(\\d+)X (.+)$").matcher(name);
        if(x.matches()) { String rest=BOSS_EVENTS.get(x.group(2).toUpperCase(Locale.ROOT)); if(rest!=null) return x.group(1)+" 倍"+rest; }
        return null;
    }
    private static String rule(List<Rule> rules,View v,int a,int b) {
        String text=v.text.substring(a,b);
        for(Rule rule:rules) {
            Matcher m=rule.pattern.matcher(text);if(!m.matches())continue;
            Map<Integer,String> captured=new HashMap<>();
            for(int n=0;n<rule.numbers.size();n++) captured.put(rule.numbers.get(n),v.part(a+m.start(n+1),a+m.end(n+1)));
            Matcher p=NUMBER.matcher(rule.zh);StringBuilder rendered=new StringBuilder();
            while(p.find())p.appendReplacement(rendered,Matcher.quoteReplacement(captured.getOrDefault(Integer.valueOf(p.group(1)),p.group())));
            p.appendTail(rendered);return rendered.toString();
        }
        return null;
    }
    /** "Objective: Find Kelly ⬉", "EVENT GOBLIN RAID ACTIVE IN FAR RESERVE for 12:34": only these shapes. */
    public static String boss(String formatted) {
        if(formatted==null || formatted.isEmpty()) return null;
        View v=new View(formatted); Matcher t=BOSS_TAIL.matcher(v.text); if(!t.matches()) return null;
        int a=t.start(2), b=t.end(2); String core=t.group(2); if(core.isEmpty()) return null;
        String zh=null;
        if(core.startsWith("Objective: ")) {
            String goal=OBJECTIVES.get(core.substring(11).trim());
            if(goal!=null) zh="目标: "+v.style(a+11)+goal;
        }
        if(zh==null) {
            Matcher e=BOSS_EVENT.matcher(core);
            if(e.matches()) {
                String name=bossEvent(e.group(2)), area=e.group(3)==null?null:region(e.group(3));
                if(name!=null && (e.group(3)==null || area!=null)) {
                    String time=v.style(a+e.start(4))+v.part(a+e.start(4),a+e.end(4));
                    zh=(e.group(1)!=null?"被动活动 ":"活动 ")+v.style(a+e.start(2))+name+(area!=null?" 正在"+area+"进行":"")+v.style(a)+"，剩余 "+time;
                }
            }
        }
        if(zh==null) zh=rule(BOSS_RULES,v,a,b);
        if(zh==null) return null;
        return v.replace(a,b,zh);
    }

    public static String translate(String formatted,String surface) {
        if(formatted==null || formatted.isEmpty())return null;
        if(!"LOCATION".equals(surface) && !"REGION".equals(surface) && !"TITLE".equals(surface)) {
            String event=RuntimeText.event(formatted);if(event!=null)return event;
        }
        if(formatted.indexOf('\n')>=0) {
            String[] parts=formatted.split("\\n",-1);boolean changed=false;
            for(int i=0;i<parts.length;i++) {String t=translate(parts[i],surface);if(t!=null){parts[i]=t;changed=true;}}
            return changed?String.join("\n",parts):null;
        }
        String raw="SIDEBAR".equals(surface)?cleanScoreboard(formatted):formatted; View v=new View(raw);String text=v.text.trim();
        if(text.isEmpty() || text.matches("\\[\\d+] [A-Za-z0-9_]{1,16}(?: [^A-Za-z]*)?"))return null;
        if("TITLE".equals(surface)) {
            String title=ScopedNames.screenTitle(raw);if(title!=null)return title;
            String area=REGIONS.get(text);return area==null?null:v.replace(v.text.indexOf(text),v.text.indexOf(text)+text.length(),area);
        }
        if("REGION".equals(surface)) {String museum=ScopedNames.museum(raw,false);if(museum!=null)return museum;String region=REGIONS.get(text);return region==null?null:v.replace(v.text.indexOf(text),v.text.indexOf(text)+text.length(),region);}
        String region=regions(raw);
        if("LOCATION".equals(surface)) return region.equals(raw)?null:region;
        if(!region.equals(raw)) {
            region=replaceLiteral(region,"Drill Fuel","钻头燃料");region=replaceLiteral(region,"SkyBlock XP","空岛经验");region=replaceLiteral(region,"HOTM Progression","山之心进度");return region;
        }
        // Restrict overlay handling to its own known messages/segments. Health/mana stays untouched.
        for(Rule rule:RULES) {
            Matcher m=rule.pattern.matcher(text);if(!m.matches())continue;
            Map<Integer,String> captured=new HashMap<>(); int offset=v.text.indexOf(text);
            for(int n=0;n<rule.numbers.size();n++) captured.put(rule.numbers.get(n),v.style(offset+m.start(n+1))+v.part(offset+m.start(n+1),offset+m.end(n+1))+v.style(offset));
            Matcher p=NUMBER.matcher(rule.zh);StringBuilder rendered=new StringBuilder();while(p.find())p.appendReplacement(rendered,Matcher.quoteReplacement(captured.getOrDefault(Integer.valueOf(p.group(1)),p.group())));p.appendTail(rendered);
            return v.replace(offset,offset+text.length(),rendered.toString());
        }
        if("OVERLAY".equals(surface)) {
            String out=replaceLiteral(raw,"Drill Fuel","钻头燃料");out=replaceLiteral(out,"SkyBlock XP","空岛经验");out=replaceLiteral(out,"HOTM Progression","山之心进度");return out.equals(raw)?null:out;
        }
        Matcher field=Pattern.compile("^([^A-Za-z]*)([A-Za-z][A-Za-z' ]{0,45}):\\s*(.*)$").matcher(v.text);
        if(field.matches()) {
            String label=LABELS.get(field.group(2)); if(label==null)label=value(field.group(2));
            if(label!=null) {
                String out=raw; String english=field.group(3),translated=field.group(2).equals("Server")?english:fieldValue(english);
                if(!english.equals(translated) && !english.isEmpty()) out=v.replace(field.start(3),field.end(3),translated);
                return new View(out).replace(field.start(2),field.end(2),label);
            }
        }
        Matcher skill=Pattern.compile("^(Combat|Foraging|Fishing|Farming|Mining) (\\d+:.*)$").matcher(text);
        if(skill.matches())return v.replace(v.text.indexOf(text),v.text.indexOf(text)+skill.group(1).length(),LABELS.get(skill.group(1)));
        Matcher clock=Pattern.compile("^(\\d{1,2}):(\\d{2})(am|pm)(.*)$").matcher(text);
        if(clock.matches())return v.style(0)+(clock.group(3).equals("am")?"上午 ":"下午 ")+clock.group(1)+":"+clock.group(2)+clock.group(4);
        Matcher season=Pattern.compile("^(Early |Late )?(Spring|Summer|Autumn|Winter) (\\d+)(?:st|nd|rd|th)$").matcher(text);
        if(season.matches())return v.style(0)+(season.group(1)==null?"":season.group(1).equals("Early ")?"初":"晚")+Map.of("Spring","春","Summer","夏","Autumn","秋","Winter","冬").get(season.group(2))+" "+season.group(3)+"日";
        Matcher decorated=Pattern.compile("^(○ |\\[Lvl \\d+] )(.+?)( NEW!)?$").matcher(text);
        if(decorated.matches()) {String zh=value(decorated.group(2));if(zh!=null)return v.style(0)+decorated.group(1)+zh+(decorated.group(3)==null?"":" 新访客！");}
        if(text.endsWith(" NEW!")) {String zh=value(text.substring(0,text.length()-5));if(zh!=null)return v.style(0)+zh+" 新访客！";}
        String known=value(text);if(known==null)known=LABELS.get(text);
        if(known!=null && !known.equals(text))return v.replace(v.text.indexOf(text),v.text.indexOf(text)+text.length(),known);
        String duration=durations(text);return duration.equals(text)?null:v.replace(v.text.indexOf(text),v.text.indexOf(text)+text.length(),duration);
    }
}
