package au.com.uulabs.doomscroller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The five supply surfaces, all keyless and serverless.
 *
 * Each returns candidate items for one randomly chosen subject. Nothing here
 * keeps a list of content; every call is generated from a word.
 *
 * Where a source only offers a provider-hosted player, [Item.kind] is "web"
 * and the WebView loads the provider's own documented embed. Where a source
 * hands over a real stream, kind is "mp4" and it plays natively.
 */
public final class Sources {

    public static final String YOUTUBE = "YouTube";
    public static final String TIKTOK = "TikTok";
    public static final String X = "X";
    public static final String PEERTUBE = "PeerTube";
    public static final String INSTAGRAM = "Instagram";

    public static final String[] ALL = {YOUTUBE, TIKTOK, X, PEERTUBE, INSTAGRAM};

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public static final class Item {
        public final String source;
        public final String id;
        public final String title;
        public final String kind;   // "web" or "mp4"
        public final String url;
        public final int w;
        public final int h;

        Item(String source, String id, String title, String kind, String url, int w, int h) {
            this.source = source;
            this.id = id;
            this.title = title == null ? "" : title;
            this.kind = kind;
            this.url = url;
            this.w = w;
            this.h = h;
        }

        /** Unknown dimensions are allowed through; a known landscape item is not. */
        public boolean portraitOk() {
            return w <= 0 || h <= 0 || h >= w;
        }

        @Override
        public String toString() {
            return source + ":" + id;
        }
    }

    private Sources() {
    }

    // ------------------------------------------------------------------ http

    private static String get(String url, String cookie) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json");
        c.setRequestProperty("Accept-Language", "en-AU,en;q=0.9");
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (cookie != null) {
            c.setRequestProperty("Cookie", cookie);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            c.disconnect();
            return "";
        }
        String enc = c.getContentEncoding();
        if (enc != null && enc.toLowerCase().contains("gzip")) {
            in = new GZIPInputStream(in);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > 4_000_000) {
                break;
            }
            bos.write(buf, 0, n);
        }
        in.close();
        c.disconnect();
        return bos.toString("UTF-8");
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    // -------------------------------------------------------------- dispatch

    public static List<Item> fetch(String source, String word) throws IOException {
        if (YOUTUBE.equals(source)) {
            return youtube(word);
        }
        if (TIKTOK.equals(source)) {
            return tiktok(word);
        }
        if (X.equals(source)) {
            return x(word);
        }
        if (INSTAGRAM.equals(source)) {
            return instagram(word);
        }
        if (PEERTUBE.equals(source)) {
            return peertube(word);
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------- instagram

    /**
     * Reels have no anonymous discovery of their own: tag and profile pages hit
     * a login wall. A general search index still carries permalinks, so a reel
     * is found by asking for its host and path together with a subject, then
     * played through Instagram's own embed, which renders without an account.
     *
     * The index is not fresh, so dead links are expected; the player treats
     * Instagram's "post may have been removed" page as an ended clip.
     */
    private static List<Item> instagram(String word) throws IOException {
        String query = "site:instagram.com/reel " + word;
        String html = get("https://search.brave.com/search?q=" + enc(query), null);
        Matcher m = Pattern.compile("instagram\\.com/reel/([A-Za-z0-9_-]{5,})")
                .matcher(html);
        List<Item> out = new ArrayList<Item>();
        Set<String> seen = new HashSet<String>();
        int index = 0;
        while (m.find() && out.size() < 12) {
            if (index++ < 2) {
                continue;
            }
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            out.add(new Item(INSTAGRAM, id, "", "frame",
                    "https://www.instagram.com/reel/" + id + "/embed/", 0, 0));
        }
        return out;
    }

    // --------------------------------------------------------------- youtube

    /** Set from the settings; landscape results are only asked for when allowed. */
    private static boolean allowLandscape;

    public static void setAllowLandscape(boolean allowed) {
        allowLandscape = allowed;
    }

    /**
     * The Shorts filter (sp=EgIYAQ%3D%3D) keeps the results vertical. It is
     * dropped when landscape is permitted, because Shorts are portrait by
     * definition and the filter would hide every landscape video. Playback uses
     * YouTube's own stream either way.
     */
    private static List<Item> youtube(String word) throws IOException {
        String html = get("https://www.youtube.com/results?search_query=" + enc(word)
                + (allowLandscape ? "" : "&sp=EgIYAQ%3D%3D"),
                "SOCS=CAI; CONSENT=YES+cb");
        Matcher m = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
        List<Item> out = new ArrayList<Item>();
        Set<String> seen = new HashSet<String>();
        // The first few results for a word are the same every time. Skipping
        // them samples the long tail, which is both less repetitive and more
        // in keeping with "completely random".
        int skip = 3 + (int) (Math.random() * 9);
        int index = 0;
        while (m.find() && out.size() < 20) {
            if (index++ < skip) {
                continue;
            }
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            out.add(new Item(YOUTUBE, id, "", "ytprobe",
                    "https://www.youtube.com/watch?v=" + id, 0, 0));
        }
        return out;
    }

    // ---------------------------------------------------------------- tiktok

    /**
     * The tag embedding endpoint lists the videos for a subject. It is polite
     * only: paced too fast it answers 503, so a miss simply yields nothing and
     * the sampler moves on.
     */
    private static List<Item> tiktok(String word) throws IOException {
        List<Item> out = new ArrayList<Item>();
        String html = get("https://www.tiktok.com/embed/tag/" + enc(word), null);
        Matcher m = Pattern.compile("tiktok\\.com/@[\\w.\\-]+/video/(\\d{15,25})").matcher(html);
        Set<String> seen = new HashSet<String>();
        while (m.find() && out.size() < 12) {
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            out.add(new Item(TIKTOK, id, "", "frame",
                    "https://www.tiktok.com/player/v1/" + id
                            + "?autoplay=1&loop=0&music_info=0&description=0", 0, 0));
        }
        if (!out.isEmpty()) {
            return out;
        }
        return tiktokSitemap();
    }

    /** The sitemap is the stable one: it enumerates video URLs directly. */
    private static List<Item> tiktokSitemap() throws IOException {
        String xml = get("https://www.tiktok.com/sitemap.xml", null);
        Matcher m = Pattern.compile(
                "<loc>https://www\\.tiktok\\.com/@[\\w.\\-]+/video/(\\d{15,25})</loc>").matcher(xml);
        List<Item> out = new ArrayList<Item>();
        Set<String> seen = new HashSet<String>();
        while (m.find()) {
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            out.add(new Item(TIKTOK, id, "", "frame",
                    "https://www.tiktok.com/player/v1/" + id
                            + "?autoplay=1&loop=0&music_info=0&description=0", 0, 0));
        }
        Collections.shuffle(out);
        return out;
    }

    // -------------------------------------------------------------------- x

    /**
     * The syndication profile widget is keyless and gives real tweet media.
     * The word is used as a handle first (people name accounts after things),
     * which is how a random subject reaches a random person's timeline.
     */
    private static List<Item> x(String word) throws IOException {
        String html = get("https://syndication.twitter.com/srv/timeline-profile/screen-name/"
                + enc(word), null);
        String json = html;
        Matcher script = Pattern.compile(
                "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
                Pattern.DOTALL).matcher(html);
        if (script.find()) {
            json = script.group(1);
        }
        // The syndication payload is JSON, so a query separator arrives as the
        // escape \u0026. Unescaping first is what keeps the signed query string
        // on the URL; truncating at the backslash is what caused a 403.
        json = json.replace("\\u0026", "&").replace("\\/", "/");
        Matcher v = Pattern.compile(
                "(https://video\\.twimg\\.com/[^\"\\\\]+?\\.mp4[^\"\\\\]*)").matcher(json);
        List<Item> out = new ArrayList<Item>();
        Set<String> seen = new HashSet<String>();
        while (v.find() && out.size() < 12) {
            String url = v.group(1);
            if (!seen.add(url)) {
                continue;
            }
            Matcher dim = Pattern.compile("/(\\d{2,4})x(\\d{2,4})/").matcher(url);
            int w = 0;
            int h = 0;
            if (dim.find()) {
                w = Integer.parseInt(dim.group(1));
                h = Integer.parseInt(dim.group(2));
            }
            out.add(new Item(X, word, "", "mp4", url, w, h));
        }
        return out;
    }

    // -------------------------------------------------------------- peertube

    /**
     * Sepia Search federates thousands of PeerTube instances, so one query
     * reaches content from many countries and languages.
     */
    private static List<Item> peertube(String word) throws IOException {
        String json = get("https://sepiasearch.org/api/v1/search/videos?search="
                + enc(word) + "&count=12", null);
        List<Item> out = new ArrayList<Item>();
        JSONArray data;
        try {
            data = new JSONObject(json).optJSONArray("data");
        } catch (Exception e) {
            return out;
        }
        if (data == null) {
            return out;
        }
        // The federated search lists videos without their streams, so each
        // candidate is resolved against the instance that hosts it. A handful
        // is enough; more would just add latency to the feed.
        int looked = 0;
        for (int i = 0; i < data.length() && looked < 4; i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null || o.optBoolean("isLive")) {
                continue;
            }
            String page = o.optString("url", "");
            String uuid = o.optString("uuid", "");
            if (page.isEmpty() || uuid.isEmpty() || !page.contains("/videos/")) {
                continue;
            }
            String host = page.substring(0, page.indexOf("/videos/"));
            looked++;
            String details;
            try {
                details = get(host + "/api/v1/videos/" + uuid, null);
            } catch (Exception e) {
                continue;
            }
            Item item = parsePeerTubeVideo(o.optString("name", ""), uuid, details);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** Reads one instance's video record and picks the stream to play. */
    private static Item parsePeerTubeVideo(String name, String uuid, String details) {
        JSONObject video;
        try {
            video = new JSONObject(details);
        } catch (Exception e) {
            return null;
        }
        if (video.optBoolean("isLive")) {
            return null;
        }
        String best = null;
        int bw = 0;
        int bh = 0;
        JSONArray files = video.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                String url = f.optString("fileUrl", "");
                JSONObject res = f.optJSONObject("resolution");
                int rw = res == null ? 0 : res.optInt("width", 0);
                int rh = res == null ? 0 : res.optInt("height", 0);
                if (url.isEmpty()) {
                    continue;
                }
                if (rh > bh) {
                    best = url;
                    bw = rw;
                    bh = rh;
                }
            }
        }
        if (best == null) {
            JSONArray playlists = video.optJSONArray("streamingPlaylists");
            if (playlists != null) {
                for (int i = 0; i < playlists.length() && best == null; i++) {
                    JSONObject pl = playlists.optJSONObject(i);
                    if (pl == null) {
                        continue;
                    }
                    String url = pl.optString("playlistUrl", "");
                    if (url.isEmpty()) {
                        continue;
                    }
                    best = url; // HLS; ExoPlayer plays it natively
                    JSONArray plFiles = pl.optJSONArray("files");
                    if (plFiles != null) {
                        for (int j = 0; j < plFiles.length(); j++) {
                            JSONObject f = plFiles.optJSONObject(j);
                            JSONObject res = f == null ? null : f.optJSONObject("resolution");
                            if (res != null) {
                                bw = res.optInt("width", 0);
                                bh = res.optInt("height", 0);
                            }
                        }
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }
        return new Item(PEERTUBE, uuid, name, "mp4", best, bw, bh);
    }
}
