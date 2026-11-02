import au.com.uulabs.doomscroller.*;
import java.util.*;
public class CoreChecks {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  check(!VideoGeometry.allowed(VideoGeometry.ratio(1920,1080,1),false),"landscape");
  check(VideoGeometry.allowed(VideoGeometry.ratio(1080,1920,1),false),"portrait");
  check(VideoGeometry.allowed(VideoGeometry.ratio(640,640,1),false),"square");
  check(!VideoGeometry.allowed(0,false),"unknown");
  check(!VideoGeometry.allowed(VideoGeometry.ratio(640,800,2),false),"pixel ratio");
  check(VideoGeometry.ratio(640,800,Float.NaN)==0,"NaN");
  check(ReelLinks.extract("<a href='https://www.instagram.com/reel/ABC123/'>x</a>",true).equals(Arrays.asList("ABC123")),"plain IG link");
  check(ReelLinks.extract("https:\\/\\/www.facebook.com\\/reel\\/123456789/",false).equals(Arrays.asList("123456789")),"escaped FB link");
  check(ReelLinks.extract("https://evilinstagram.com/reel/ABC123/",true).isEmpty(),"wrong host");
  List<TopicSampler.Topic> topics=new ArrayList<>();
  for(String l:Arrays.asList("en","th","ar"))for(String d:Arrays.asList("science","food","craft"))
   for(int i=0;i<(l.equals("en")?100:10);i++)topics.add(new TopicSampler.Topic(l,d,l+d+i,l+d+i));
  Map<String,String> saved=new HashMap<>();
  TopicSampler.State state=new TopicSampler.State(){public String get(String k){return saved.getOrDefault(k,"");}public void put(String k,String v){saved.put(k,v);}};
  TopicSampler sampler=new TopicSampler(topics,new Random(42),state);
  Map<String,Integer> counts=new HashMap<>();
  Set<String> langs=new HashSet<>(Arrays.asList("en","th","ar"));
  for(int i=0;i<900;i++){TopicSampler.Topic t=sampler.next(langs);counts.merge(t.language+"/"+t.domain,1,Integer::sum);}
  for(int count:counts.values())check(count==100,"balanced despite 10x English size");
  for(int i=0;i<6;i++)check(sampler.next(Collections.singleton("ar")).language.equals("ar"),"disabled languages");
  Set<String> got=new HashSet<>();for(int i=0;i<3;i++)got.add(sampler.next(langs).language);
  check(got.size()==3,"new languages immediate");
  TopicSampler restarted=new TopicSampler(topics,new Random(90),state);
  for(int i=0;i<20;i++)check(restarted.next(langs)!=null,"restart");
  System.out.println("PASS: geometry, Reel URL parsing, balanced languages/domains, changed selection, persisted bags");
 }
}
