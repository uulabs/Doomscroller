package au.com.uulabs.doomscroller;

import java.util.*;
import java.util.regex.*;

/** Extract only public permalinks on the requested provider, including JSON-escaped URLs. */
public final class ReelLinks {
    public static List<String> extract(String html, boolean instagram) {
        String clean = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/");
        String host = instagram ? "instagram.com" : "facebook.com";
        String id = instagram ? "([A-Za-z0-9_-]{5,})" : "([0-9]{6,})";
        Pattern pattern = Pattern.compile("https?://(?:www\\.)?" + Pattern.quote(host) + "/reel/" + id);
        Matcher m = pattern.matcher(clean);
        Set<String> found = new LinkedHashSet<>();
        while (m.find()) found.add(m.group(1));
        return new ArrayList<>(found);
    }
}
