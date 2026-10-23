package com.npctranslator;
import com.google.gson.*;import java.io.InputStreamReader;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.regex.*;
/** Known calendar/event structures and complete menu segments; numbers are read at display time. */
public final class RuntimeText {
 private RuntimeText(){}
 private static final Map<String,String> MENU=new LinkedHashMap<>(),EVENTS=new LinkedHashMap<>(),PHRASES=new HashMap<>(),CROPS=new HashMap<>(),ENCHANTS=new HashMap<>();
 private static final Set<String> PRODUCTS=new HashSet<>();
 private static final Map<String,String> CONTEXT=new HashMap<>();
 private static final Pattern DURATION=Pattern.compile("(?:\\d+[dhms](?: |$|!|\\)))+");
 static {try(var in=RuntimeText.class.getResourceAsStream("/assets/hypixel-npc-and-items-translate/defaults/runtime_text.json")){if(in!=null){var r=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();read(r,"menu",MENU);read(r,"events",EVENTS);read(r,"phrases",PHRASES);read(r,"crops",CROPS);read(r,"enchantments",ENCHANTS);for(var x:r.getAsJsonArray("products"))PRODUCTS.add(x.getAsString());}}catch(Exception e){System.err.println("[NPCTranslator] runtime text: "+e.getMessage());}}
 private static void read(JsonObject r,String k,Map<String,String> m){for(var e:r.getAsJsonObject(k).entrySet())m.put(e.getKey(),e.getValue().getAsString());if(k.equals("menu")&&r.has("context"))for(var e:r.getAsJsonObject("context").entrySet())CONTEXT.put(e.getKey(),e.getValue().getAsString());}
 private static final class View {
  final String raw,text;final int[] p;
  View(String raw){this.raw=raw;var b=new StringBuilder();int[] a=new int[raw.length()+1];int n=0;for(int i=0;i<raw.length();i++){if(raw.charAt(i)=='§'&&i+1<raw.length()){i++;continue;}a[n++]=i;b.append(raw.charAt(i));}a[n]=raw.length();p=Arrays.copyOf(a,n+1);text=b.toString();}
  String replace(int a,int b,String zh){return raw.substring(0,p[a])+zh+raw.substring(b>a?p[b-1]+1:p[b]);}
 }
 private static String replace(String raw,String re,java.util.function.Function<Matcher,String> f){var v=new View(raw);var m=Pattern.compile(re).matcher(v.text);var spans=new ArrayList<int[]>();var values=new ArrayList<String>();while(m.find()){String s=f.apply(m);if(s!=null){spans.add(new int[]{m.start(),m.end()});values.add(s);}}for(int i=spans.size()-1;i>=0;i--){int[] p=spans.get(i);raw=new View(raw).replace(p[0],p[1],values.get(i));}return raw;}
 private static String units(String raw){return replace(raw,"(?<=\\d)([dhms])(?=\\b)",m->Map.of("d","天","h","小时","m","分钟","s","秒").get(m.group(1)));}
 private static String known(String s){
  String z=MENU.get(s);if(z!=null)return z;
  Matcher page=Pattern.compile("^(\\(\\d+/\\d+\\) )(.+)$").matcher(s);
  if(page.matches()){z=known(page.group(2));return z==null?null:page.group(1)+z;}
  Matcher slot=Pattern.compile("^(.+) \\((?:Slot #(\\d+)|(\\d+/\\d+))\\)$").matcher(s);
  if(slot.matches()){z=MENU.get(slot.group(1));return z==null?null:z+" ("+(slot.group(2)!=null?"槽位 #"+slot.group(2):slot.group(3))+")";}
  Matcher minion=Pattern.compile("^(.+) Minion ([IVXLCDM]+)$").matcher(s);
  if(minion.matches()){z=TableNames.exact(minion.group(1));return z==null?null:z+"小人 "+minion.group(2);}
  Matcher m=Pattern.compile("^(.+?) ([IVXLCDM]+)$").matcher(s);
  if(m.matches()&&ENCHANTS.containsKey(m.group(1)))return ENCHANTS.get(m.group(1))+" "+m.group(2);
  return null;
 }
 static String enchantment(String name){return ENCHANTS.get(name);}
 public static String menu(String raw){
  var v=new View(raw);String t=v.text.trim();
  if(CONTEXT.containsKey(t))return v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),CONTEXT.get(t));
  Matcher page=Pattern.compile("^\\((\\d+)/(\\d+)\\) Garden ➜ Mutations$").matcher(t);
  if(page.matches())return v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),"("+page.group(1)+"/"+page.group(2)+") 花园 ➜ 突变植物");
  if(!t.contains(" ➜ ")){String z=known(t);return z==null?null:v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),z);}
  String[] parts=t.split(" ➜ ",-1);String[] translated=new String[parts.length];boolean changed=false;
  for(int i=0;i<parts.length;i++){
   String part=parts[i],s=part;boolean quote=s.startsWith("\"");if(quote)s=s.substring(1);boolean close=s.endsWith("\"");if(close)s=s.substring(0,s.length()-1);
   String icon="";Matcher iconMatch=Pattern.compile("^([^A-Za-z]+)(.+)$").matcher(s);if(iconMatch.matches()){icon=iconMatch.group(1);s=iconMatch.group(2);}
   String z=known(s);String root=parts[0].replaceFirst("^\\(\\d+/\\d+\\) ","");
   // The complete parent identifies an enchantment truncated by a native menu title.
   if(z==null&&i==parts.length-1&&t.length()>=29&&s.length()>=5&&ENCHANTS.containsKey(root)&&root.startsWith(s))z=ENCHANTS.get(root);
   if(z==null&&i==parts.length-1&&t.length()>=29&&s.length()>=5){
    Set<String> candidates=new LinkedHashSet<>();for(var e:MENU.entrySet()){
     if(!e.getKey().startsWith(s)||e.getKey().equals(s))continue;
     if(root.contains("Griffin Upgrade")&&!e.getKey().contains("Griffin Upgrade"))continue;
     boolean category=Set.of("Normal Enchantments","Ultimate Enchantments","Mountain Rabbits","Country Rabbits","City Rabbits","Beach Rabbits","Forest Rabbits","Water Rabbits").contains(e.getKey());
     if(root.contains("Enchantments")&&!ENCHANTS.containsKey(e.getKey())&&!category)continue;
     if(!root.contains("Enchantments")&&!PRODUCTS.contains(e.getKey())&&!category)continue;
     if(!e.getValue().replaceAll("\\b[IVXLCDM]+\\b", "").matches(".*[A-Za-z]{2,}.*"))candidates.add(e.getValue());
    }if(candidates.size()==1)z=candidates.iterator().next();
    else if(candidates.size()>1){String common=candidates.iterator().next();for(String c:candidates){int n=0;while(n<common.length()&&n<c.length()&&common.charAt(n)==c.charAt(n))n++;common=common.substring(0,n);}if(common.matches("[\\p{IsHan}]{2,}"))z=common+"…";}
   }
   // Product names can contain an inner quote instead of a pair of outer quotes.
   if(z==null&&i==parts.length-1&&t.length()>=29){Set<String> q=new LinkedHashSet<>();for(String product:PRODUCTS)if(product.startsWith(part)&&MENU.containsKey(product))q.add(MENU.get(product));if(q.size()==1){z=q.iterator().next();quote=false;close=false;icon="";}}
   translated[i]=z==null?part:(quote?"\"":"")+icon+z+(close?"\"":"");changed|=z!=null&&!part.equals(translated[i]);
  }
  return changed?v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),String.join(" ➜ ",translated)):null;
 }
 public static String event(String raw){
  if(raw==null||raw.isEmpty())return null;var v=new View(raw);String t=v.text.trim(),direct=PHRASES.get(t);
  if(direct!=null)return v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),direct);
  Matcher day=Pattern.compile("^Day (\\d+)$").matcher(t);if(day.matches())return replace(raw,"^Day ",m->"第 ")+" 天";
  Matcher month=Pattern.compile("^(Early |Late )?(Spring|Summer|Autumn|Winter), Year (\\d+)$").matcher(t);
  if(month.matches())return v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),(month.group(1)==null?"":month.group(1).equals("Early ")?"初":"晚")+Map.of("Spring","春季","Summer","夏季","Autumn","秋季","Winter","冬季").get(month.group(2))+"，第 "+month.group(3)+" 年");
  String out=raw;boolean supported=false;
  // Whole event labels, optional ordinals, optional duration and crop lists. No arbitrary word replacement.
  for(var e:EVENTS.entrySet()){
   String name=e.getKey();String core="(?:\\d+(?:st|nd|rd|th)? )?"+Pattern.quote(name);
   String guard="^(?:(?:All day: |12:00 am-11:59 pm: |Event: |Next Event: |Current Event: ))?"+core+"(?: \\(.*\\))?(?: ○ .*)?$";
   if(t.matches(guard)){
    out=replace(out,"(?<![A-Za-z])(?:(\\d+)(?:st|nd|rd|th)? )?"+Pattern.quote(name)+"(?![A-Za-z])",m->(m.group(1)==null?"":"第 "+m.group(1)+" 届 ")+e.getValue());
    out=replace(out,"^(?:All day:|12:00 am-11:59 pm:)",m->"全天：");out=replace(out,"^(?:Event:|Next Event:|Current Event:)",m->m.group().startsWith("Next")?"下个活动：":"活动：");supported=true;break;
   }
  }
  if(t.matches("^○ .+")){supported=true;}
  if(supported){
   for(var e:CROPS.entrySet())out=replace(out,"(?<=○ )"+Pattern.quote(e.getKey())+"(?=$| ○ | \\()",m->e.getValue());
   out=units(out);return out.equals(raw)?null:out;
  }
  if(t.startsWith("Starts in: ")||t.startsWith("Event lasts for ")||t.startsWith("Next set of perks in ")){
   // Recognised time fields only; don't reinterpret arbitrary prose containing these letters.
   if(!DURATION.matcher(t).find())return null;
   out=replace(out,"Starts in:",m->"距开始：");out=replace(out,"Event lasts for",m->"活动持续");out=replace(out,"Next set of perks in",m->"下一组天赋切换倒计时：");out=units(out);
  }
  Matcher pet=Pattern.compile("^Pet available: (.+)$").matcher(t);if(pet.matches()){String name=TableNames.exact(pet.group(1));if(name!=null)return v.replace(v.text.indexOf(t),v.text.indexOf(t)+t.length(),"可兑换宠物："+name);}
  if(t.matches("^The mayor has been elected for year \\d+ by the whole SkyBlock community\\.$"))return replace(raw,"^The mayor has been elected for year (\\d+) by the whole SkyBlock community\\.$",m->"这位市长由全体空岛玩家选出，将在第 "+m.group(1)+" 年履职。");
  out=replace(out,"(Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\\d{1,2}) (\\d{4}) (\\d{2}:\\d{2}:\\d{2})$",m->m.group(4)+"年"+(List.of("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec").indexOf(m.group(2))+1)+"月"+m.group(3)+"日 "+Map.of("Mon","周一","Tue","周二","Wed","周三","Thu","周四","Fri","周五","Sat","周六","Sun","周日").get(m.group(1))+" "+m.group(5));
  return out.equals(raw)?null:out;
 }
}
