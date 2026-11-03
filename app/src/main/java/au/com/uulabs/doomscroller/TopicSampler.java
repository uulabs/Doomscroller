package au.com.uulabs.doomscroller;

import java.util.*;

/** Balanced discovery attempts, never a profile of what the viewer watches. */
public final class TopicSampler {
    public interface State { String get(String key); void put(String key, String value); }
    public static final class Topic {
        public final String language, domain, id, query;
        public Topic(String language, String domain, String id, String query) {
            this.language=language; this.domain=domain; this.id=id; this.query=query;
        }
    }
    private final Map<String, Map<String, List<Topic>>> catalog = new TreeMap<>();
    private final Random random;
    private final State state;
    public TopicSampler(List<Topic> topics, Random random, State state) {
        this.random=random; this.state=state;
        Set<String> seen=new HashSet<>();
        for (Topic t:topics) {
            if (!seen.add(t.language+"|"+t.query.toLowerCase(Locale.ROOT))) continue;
            catalog.computeIfAbsent(t.language,k->new TreeMap<>())
                    .computeIfAbsent(t.domain,k->new ArrayList<>()).add(t);
        }
    }
    // Store shuffled indices, not thousands of full strings. A changed vocabulary
    // or language selection invalidates only its own bag, immediately.
    private int draw(String key, List<String> ids) {
        String signature=Integer.toHexString(ids.toString().hashCode())+":"+ids.size();
        String stored=state.get(key), prefix=signature+"|";
        List<Integer> bag=new ArrayList<>();
        if (stored.startsWith(prefix)) {
            try { for(String s:stored.substring(prefix.length()).split(",")) {
                if(!s.isEmpty()) { int i=Integer.parseInt(s); if(i>=0 && i<ids.size())bag.add(i); }
            }} catch(NumberFormatException e) { bag.clear(); }
        }
        if(bag.isEmpty()) {for(int i=0;i<ids.size();i++)bag.add(i);Collections.shuffle(bag,random);}
        int result=bag.remove(bag.size()-1);
        StringBuilder saved=new StringBuilder(prefix);
        for(int i:bag)saved.append(i).append(',');
        state.put(key,saved.toString()); return result;
    }
    public synchronized Topic next(Set<String> enabled) {
        List<String> languages=new ArrayList<>();
        for(String l:catalog.keySet())if(enabled.contains(l))languages.add(l);
        if(languages.isEmpty())languages.add("en");
        String lang=languages.get(draw("topics-v1.languages",languages));
        Map<String,List<Topic>> domains=catalog.get(lang);
        if(domains==null || domains.isEmpty())throw new IllegalStateException("No topic catalogue");
        List<String> ds=new ArrayList<>(domains.keySet());
        String domain=ds.get(draw("topics-v1.domains."+lang,ds));
        // Two subjects from one domain in a row reads as a rut, especially when
        // the domain is small. Draw once more rather than accept a repeat.
        String lastDomain=state.get("topics-v1.lastdomain");
        for(int tries=0; tries<3 && ds.size()>1 && domain.equals(lastDomain); tries++) {
            domain=ds.get(draw("topics-v1.domains."+lang,ds));
        }
        state.put("topics-v1.lastdomain",domain);
        List<Topic> topics=domains.get(domain);List<String> ids=new ArrayList<>();
        for(Topic t:topics)ids.add(t.id+":"+t.query);
        // Related concepts in another language should not immediately repeat.
        String recent=state.get("topics-v1.recent");
        Set<String> held=new HashSet<>(Arrays.asList(recent.split(",")));
        Topic chosen=null;
        for(int n=0;n<Math.min(8,topics.size());n++) {
            chosen=topics.get(draw("topics-v1.words."+lang+"."+domain,ids));
            if(!held.contains(chosen.id))break;
        }
        List<String> history=new ArrayList<>(Arrays.asList(recent.split(",")));
        history.remove("");history.add(chosen.id);while(history.size()>24)history.remove(0);
        state.put("topics-v1.recent",String.join(",",history));return chosen;
    }
}
