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
 * Keyless discovery for six providers. Meta draws from a bundled public URL
 * cache, then live indexes when exhausted. Only original provider media is
 * resolved and played; archived media and layout dimensions are never used.
 */
public final class Sources {

    public static final String YOUTUBE = "YouTube";
    public static final String TIKTOK = "TikTok";
    public static final String X = "X";
    public static final String PEERTUBE = "PeerTube";
    public static final String INSTAGRAM = "Instagram";
    public static final String FACEBOOK = "Facebook";

    public static final String[] ALL = {YOUTUBE, TIKTOK, X, PEERTUBE, INSTAGRAM, FACEBOOK};

    private static final java.util.Random PICK = new java.util.Random();

    /** Called once at startup so two installs sample different parts of a list. */
    public static void setSalt(long salt) {
        PICK.setSeed(salt ^ System.nanoTime());
    }

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

    private static boolean reelCacheLoaded;

    /** The same broad, auditable cold-start cache is used in every build type. */
    public static synchronized void loadReelCache(InputStream input) throws IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(input, "UTF-8"))) {
            if (reelCacheLoaded) return;
            Set<String> facebook = new HashSet<>(), instagram = new HashSet<>();
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null && rows < 20000) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\\t", 2);
                if (fields.length != 2) continue;
                if (FACEBOOK.equals(fields[0]) && fields[1].matches("[0-9]{6,}")) facebook.add(fields[1]);
                else if (INSTAGRAM.equals(fields[0]) && fields[1].matches("[A-Za-z0-9_-]{5,}")) instagram.add(fields[1]);
                rows++;
            }
            List<String> f = new ArrayList<>(facebook), i = new ArrayList<>(instagram);
            Collections.shuffle(f, PICK); Collections.shuffle(i, PICK);
            FB_POOL.addAll(f); IG_POOL.addAll(i);
            fbAvailable = FB_POOL.size(); igAvailable = IG_POOL.size();
            reelCacheLoaded = true;
        }
    }

    // ------------------------------------------------------------------ http

    private static final String UI_MOBILE_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 8) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36";

    /** Some pages refuse the desktop agent the rest of discovery uses. */
    private static String getAs(String url, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setRequestProperty("Accept-Language", "en-AU,en;q=0.9");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            return "";
        }
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
            in = new java.util.zip.GZIPInputStream(in);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        c.disconnect();
        return out.toString("UTF-8");
    }

    private static String get(String url, String cookie) throws IOException {
        return get(url, cookie, false);
    }

    private static String get(String url, String cookie, boolean requireSuccess) throws IOException {
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
        if (requireSuccess && code != 200) {
            c.disconnect();
            throw new IOException("Search index HTTP " + code);
        }
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
        if (FACEBOOK.equals(source)) {
            return facebook(word);
        }
        if (PEERTUBE.equals(source)) {
            return peertube(word);
        }
        return Collections.emptyList();
    }

    private static final java.util.concurrent.atomic.AtomicLongArray indexBlockedUntil =
            new java.util.concurrent.atomic.AtomicLongArray(3);

    /** Delay only when every selected source is currently waiting to retry. */
    public static long retryDelayMillis(Set<String> enabled) {
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        for (String source : enabled) {
            long readyAt;
            if (FACEBOOK.equals(source)) {
                if (fbAvailable > 0) return 0;
                readyAt = Math.max(fbBlockedUntil, fbFreshAt + 5000L);
            } else if (INSTAGRAM.equals(source)) {
                if (igAvailable > 0) return 0;
                readyAt = Math.max(igBlockedUntil, igFreshAt + 5000L);
            } else return 0;
            readyAt = Math.max(readyAt, Math.min(indexBlockedUntil.get(2), Math.min(indexBlockedUntil.get(0), indexBlockedUntil.get(1))));
            earliest = Math.min(earliest, readyAt);
        }
        return earliest == Long.MAX_VALUE ? 2000 : Math.max(0, earliest - now);
    }

    /**
     * What the index called each reel it listed: the post's own text and the
     * page name, which is the only language signal a reel has before it is
     * fetched. Keyed by reel id; bounded so a long session cannot grow it.
     */
    private static final java.util.Map<String, String> INDEX_TITLES =
            new java.util.LinkedHashMap<String, String>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) {
                    return size() > 2000;
                }
            };

    /** The index's title for a reel, or an empty string. */
    public static synchronized String indexTitle(String id) {
        String title = INDEX_TITLES.get(id);
        return title == null ? "" : title;
    }

    /**
     * Pairs every reel link on an index page with the visible text that
     * follows it. On DuckDuckGo that is the result's title; on Brave the title
     * sits within a few hundred characters too. Only what Vocab can read with
     * confidence is ever acted on, so a stray fragment does no harm.
     */
    private static synchronized void rememberIndexTitles(String host, CharSequence decoded) {
        Matcher m = Pattern.compile(Pattern.quote(host) + "/reel/([A-Za-z0-9_-]{5,})").matcher(decoded);
        while (m.find()) {
            String id = m.group(1);
            if (INDEX_TITLES.containsKey(id)) continue;
            int from = m.end();
            int to = Math.min(decoded.length(), from + 600);
            String text = unescapeHtml(decoded.subSequence(from, to).toString()
                    .replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
            // The run of text begins after the link's own attributes close.
            int gt = text.indexOf('>');
            if (gt >= 0 && gt < 200) text = text.substring(gt + 1).trim();
            if (text.length() > 160) text = text.substring(0, 160);
            if (text.length() >= 6) INDEX_TITLES.put(id, text);
        }
    }

    /** Enough of HTML's entities to read a caption; anything else is left as is. */
    static String unescapeHtml(String raw) {
        if (raw == null || raw.indexOf('&') < 0) return raw == null ? "" : raw;
        StringBuffer out = new StringBuffer();
        Matcher m = Pattern.compile("&(#x[0-9A-Fa-f]{1,6}|#[0-9]{1,7}|amp|quot|apos|lt|gt|nbsp);").matcher(raw);
        while (m.find()) {
            String e = m.group(1);
            String r;
            try {
                if (e.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else if (e.equals("amp")) r = "&";
                else if (e.equals("quot")) r = "\"";
                else if (e.equals("apos")) r = "'";
                else if (e.equals("lt")) r = "<";
                else if (e.equals("gt")) r = ">";
                else r = " ";
            } catch (RuntimeException invalid) {
                r = " ";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Independent public indexes; never retry a blocked index in a tight loop. */
    private static String reelSearch(String host, String word) throws IOException {
        String query = enc("site:" + host + "/reel " + word);
        String[] indexes = {"https://search.brave.com/search?q=",
                "https://html.duckduckgo.com/html/?q="};
        Pattern direct = Pattern.compile(Pattern.quote(host) + "/reel/[A-Za-z0-9_-]{5,}");
        for (int i = 0; i < indexes.length; i++) {
            long now = System.currentTimeMillis();
            if (now < indexBlockedUntil.get(i)) continue;
            try {
                String html = get(indexes[i] + query, null, true);
                // DuckDuckGo wraps destinations in percent-encoded uddg links.
                StringBuilder decoded = new StringBuilder(html);
                Matcher links = Pattern.compile("uddg=([^&\"<> ]+)").matcher(html);
                while (links.find()) {
                    try { decoded.append(' ').append(java.net.URLDecoder.decode(links.group(1), "UTF-8")); }
                    catch (IllegalArgumentException ignored) { }
                }
                if (direct.matcher(decoded).find()) {
                    rememberIndexTitles(host, decoded);
                    return decoded.toString();
                }
            } catch (IOException unavailable) {
                indexBlockedUntil.set(i, now + 10 * 60_000L);
            }
        }
        long now = System.currentTimeMillis();
        if (now >= indexBlockedUntil.get(2)) {
            try {
                String links = archiveReels(host);
                indexBlockedUntil.set(2, now + 15_000L);
                return links;
            } catch (IOException unavailable) {
                if (!Thread.currentThread().isInterrupted())
                    indexBlockedUntil.set(2, now + 10 * 60_000L);
            }
        }
        return "";
    }

    private static final java.util.Map<String, Integer> archivePages = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> archivePagesAt = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> lastArchivePage = new java.util.HashMap<>();

    /** The public CDX API supplies URLs, never archived media. Resolve each URL live. */
    private static String archiveReels(String host) throws IOException {
        String base = "https://web.archive.org/cdx/search/cdx?url="
                + enc("www." + host + "/reel/*") + "&pageSize=1";
        long now = System.currentTimeMillis();
        Integer count = archivePages.get(host);
        if (count == null || now - archivePagesAt.getOrDefault(host, 0L) > 6 * 60 * 60_000L) {
            String number = get(base + "&showNumPages=true", null, true).trim();
            try { count = Integer.parseInt(number); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid CDX page count"); }
            if (count <= 0 || count > 10_000_000) throw new IOException("Empty CDX index");
            archivePages.put(host, count); archivePagesAt.put(host, now);
        }
        int page = java.util.concurrent.ThreadLocalRandom.current().nextInt(count);
        Integer previous = lastArchivePage.get(host);
        if (count > 1 && previous != null && page == previous) page = (page + 1) % count;
        String body = get(base + "&page=" + page
                + "&output=json&filter=statuscode%3A200&collapse=urlkey&fl=original&limit=1000", null, true);
        try {
            JSONArray rows = new JSONArray(body);
            StringBuilder links = new StringBuilder();
            for (int i = 1; i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row != null && row.length() > 0) links.append(row.optString(0)).append('\n');
            }
            lastArchivePage.put(host, page);
            return links.toString();
        } catch (org.json.JSONException invalid) { throw new IOException("Invalid CDX results", invalid); }
    }

    // -------------------------------------------------------------- facebook

    private static final java.util.ArrayDeque<String> FB_POOL =
            new java.util.ArrayDeque<String>();
    private static volatile int fbAvailable;
    private static volatile long fbFreshAt;
    private static volatile long fbBlockedUntil;

    /** Reels found the same way as Instagram's: permalinks from a search index. */
    private static List<Item> facebook(String word) throws IOException {
        long now = System.currentTimeMillis();
        if (FB_POOL.isEmpty() && now > fbBlockedUntil && now - fbFreshAt > 5000L) {
            fbFreshAt = now;
            try {
                String html = reelSearch("facebook.com", word);
                Matcher m = Pattern.compile("facebook\\.com/reel/([0-9]{6,})")
                        .matcher(html);
                Set<String> seen = new HashSet<String>();
                while (m.find()) {
                    if (seen.add(m.group(1))) {
                        FB_POOL.addLast(m.group(1));
                    }
                }
                if (FB_POOL.isEmpty()) {
                    fbBlockedUntil = now + 5000L;
                }
            } catch (IOException e) {
                fbBlockedUntil = now + 10 * 60_000L;
            }
        }
        List<Item> out = new ArrayList<Item>();
        java.util.Random random = new java.util.Random();
        // produce() consumes one resolved item; retain the other pooled links.
        for (int i = 0; i < 1 && !FB_POOL.isEmpty(); i++) {
            int skip = random.nextInt(FB_POOL.size());
            String id = null;
            java.util.Iterator<String> it = FB_POOL.iterator();
            for (int k = 0; it.hasNext(); k++) {
                String candidate = it.next();
                if (k == skip) {
                    id = candidate;
                    it.remove();
                    break;
                }
            }
            if (id != null) {
                out.add(new Item(FACEBOOK, id, indexTitle(id), "fbprobe", "", 0, 0));
            }
        }
        fbAvailable = FB_POOL.size();
        return out;
    }

    /** Language of the reel last passed through facebookMedia(), or null when unclear. */
    public static volatile String lastFacebookLanguage;
    /** Page name of that reel; read for language, kept out of the repeat-title check. */
    public static volatile String lastFacebookOwner = "";

    /**
     * Facebook's video plugin carries the mp4 URL in its own markup, so the
     * stream can be read without a browser at all. Asked with show_text it
     * also renders the post's own words, the page name and a canonical link
     * whose slug is the post text in ASCII; together those are the only
     * language signal a Facebook reel offers, so they are read on the same
     * fetch and kept in lastCaption / lastFacebookLanguage.
     */
    public static String facebookMedia(String id) {
        lastCaption = "";
        lastFacebookOwner = "";
        lastFacebookLanguage = null;
        lastCaptionNote = "";
        try {
            String href = java.net.URLEncoder.encode(
                    "https://www.facebook.com/reel/" + id + "/", "UTF-8");
            String html = get("https://www.facebook.com/plugins/video.php?href="
                    + href + "&show_text=true", null);
            lastCaption = facebookCaption(html);
            lastFacebookOwner = facebookOwner(html);
            String words = (lastCaption + " " + lastFacebookOwner).trim();
            lastCaptionNote = "bytes=" + html.length() + " caption=" + lastCaption.length()
                    + " owner=" + lastFacebookOwner.length();
            if (!words.isEmpty()) {
                String spoken = Vocab.detect(words);
                lastFacebookLanguage = spoken != null ? spoken : Vocab.detectLatin(words);
            }
            Matcher m = Pattern.compile("\"sd_src\":\"([^\"]+)\"")
                    .matcher(html);
            if (m.find()) {
                return unescapeJsonUrl(m.group(1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The page that posted the reel, as the plugin names it. */
    static String facebookOwner(String html) {
        Matcher owner = Pattern.compile("<a title=\"([^\"]{2,120})\"[^>]*ref=embed_video").matcher(html);
        return owner.find() ? unescapeHtml(owner.group(1)).trim() : "";
    }

    /** The post's own text, or failing that the canonical slug; empty when the page has neither. */
    static String facebookCaption(String html) {
        StringBuilder text = new StringBuilder();
        Matcher message = Pattern.compile(
                "data-testid=\"post_message\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(html);
        if (message.find()) {
            text.append(unescapeHtml(message.group(1).replaceAll("<[^>]+>", " ")));
        }
        if (text.length() < 12) {
            Matcher canonical = Pattern.compile(
                    "rel=\"canonical\" href=\"[^\"]*/videos/([^/\"]{4,})/[0-9]+/?\"").matcher(html);
            if (canonical.find()) {
                text.append(' ').append(canonical.group(1).replace('-', ' '));
            }
        }
        String out = text.toString().replaceAll("\\s+", " ").trim();
        return out.length() > 400 ? out.substring(0, 400) : out;
    }

    /**
     * The plugin page escapes its own JSON, sometimes twice over, so a single
     * pass leaves stray backslashes and the CDN rejects the request. Ampersands
     * are restored first, then any remaining backslash is dropped.
     */
    private static String unescapeJsonUrl(String raw) {
        String out = raw;
        while (out.indexOf("\\u0026") >= 0) {
            out = out.replace("\\u0026", "&");
        }
        out = out.replace("&amp;", "&");
        out = out.replace("\\", "");
        return out;
    }

    /** Null permits the browser fallback; empty means the public embed offers no playable media. */
    public static String instagramMedia(String id) {
        try {
            String html = get("https://www.instagram.com/reel/" + id + "/embed/", null, true);
            int key = html.indexOf("video_url");
            if (key >= 0) {
                int colon = html.indexOf(':', key);
                int start = html.indexOf('"', colon + 1);
                int end = html.indexOf('"', start + 1);
                if (colon >= 0 && start >= 0 && end > start) {
                    String media = unescapeJsonUrl(html.substring(start + 1, end));
                    if (media.startsWith("https://")) return media;
                }
            }
            // A watch-on-Instagram link is not a playable embed. Do not hold the
            // entire feed for twenty seconds trying to click through that page.
            if (html.contains("WatchOnInstagramContainer") || html.contains("may have been removed")) return "";
        } catch (IOException unavailable) { return ""; }
        return null;
    }

    // ------------------------------------------------------------- instagram

    /**
     * The cold-start cache and public indexes carry permalinks, not streams.
     * Every permalink is resolved again against the current public embed.
     * Dead/private/non-embeddable posts are expected and skipped before admission.
     */
    /**
     * Discovered reels are pooled rather than fetched per draw. The search index
     * throttles quickly, and each candidate is a usable clip for a while, so a
     * pool of a few dozen keeps the feed supplied long after one request.
     */
    private static final java.util.ArrayDeque<String> IG_POOL =
            new java.util.ArrayDeque<String>();
    private static volatile int igAvailable;
    private static volatile long igFreshAt;
    private static volatile long igBlockedUntil;

    /**
     * Instagram's public oEmbed returns the reel's caption, which is the only
     * language signal a reel has: the embed page we play carries no text at all.
     */
    public static String instagramCaptionLanguage(String id) {
        lastCaption = "";
        try {
            String href = java.net.URLEncoder.encode(
                    "https://www.instagram.com/reel/" + id + "/", "UTF-8");
            String json = get("https://www.instagram.com/api/v1/oembed/?url=" + href, null);
            if (!json.trim().startsWith("{")) {
                // Throttled or an error page; one retry, then treat as unknown.
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
                json = get("https://www.instagram.com/api/v1/oembed/?url=" + href, null);
            }
            lastCaptionNote = "bytes=" + json.length();
            String title = new org.json.JSONObject(json).optString("title", "");
            lastCaption = title;
            lastCaptionNote += " title=" + title.length();
            String spoken = Vocab.detect(title);
            return spoken != null ? spoken : Vocab.detectLatin(title);
        } catch (Exception e) {
            lastCaptionNote = "err " + e.getClass().getSimpleName();
            return null;
        }
    }

    /**
     * TikTok's embed shows no caption, but the ordinary video page carries it in
     * its own data. That is the only language signal an anonymous TikTok clip
     * has, so it is fetched once per candidate that is about to be played.
     */
    /** Set by the last caption lookup so the caller can report why it failed. */
    public static String lastCaptionNote = "";
    /** Caption of the candidate last looked at, for duplicate auditing. */
    public static String lastCaption = "";

    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 8) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36";

    public static String tiktokCaptionLanguage(String id) {
        lastCaption = "";
        try {
            String html = getAs("https://www.tiktok.com/@x/video/" + id, UI_MOBILE_UA);
            lastCaptionNote = "bytes=" + html.length();
            // The page escapes its own JSON, so quotes arrive as \" and the
            // slashes as \/. Normalise first, then read the field directly.
            String h = html.replace("\\/", "/").replace("\\\"", "\"");
            int at = h.indexOf("\"desc\":\"");
            if (at < 0) {
                lastCaptionNote = "no desc in " + html.length();
                return null;
            }
            int from = at + 8;
            int to = h.indexOf('"', from);
            if (to <= from) {
                return null;
            }
            String caption = h.substring(from, Math.min(to, from + 400));
            lastCaption = caption;
            String spoken = Vocab.detect(caption);
            return spoken != null ? spoken : Vocab.detectLatin(caption);
        } catch (Exception e) {
            lastCaptionNote = "err " + e.getClass().getSimpleName();
            return null;
        }
    }

    private static List<Item> instagram(String word) throws IOException {
        long now = System.currentTimeMillis();
        if (IG_POOL.isEmpty() && now > igBlockedUntil
                && now - igFreshAt > 5000L) {
            igFreshAt = now;
            try {
                String html = reelSearch("instagram.com", word);
                Matcher m = Pattern.compile("instagram\\.com/reel/([A-Za-z0-9_-]{5,})")
                        .matcher(html);
                Set<String> seen = new HashSet<String>();
                while (m.find()) {
                    String id = m.group(1);
                    if (seen.add(id)) {
                        IG_POOL.addLast(id);
                    }
                }
                if (IG_POOL.isEmpty()) {
                    // Throttled or nothing indexed for this subject: rest a while.
                    igBlockedUntil = now + 5000L;
                }
            } catch (IOException e) {
                igBlockedUntil = now + 10 * 60_000L;
            }
        }
        List<Item> out = new ArrayList<Item>();
        Set<String> taken = new HashSet<String>();
        java.util.Random random = new java.util.Random();
        // Do not discard five unused discoveries after one successful resolution.
        for (int i = 0; i < 1 && !IG_POOL.isEmpty(); i++) {
            int skip = random.nextInt(IG_POOL.size());
            String id = null;
            java.util.Iterator<String> it = IG_POOL.iterator();
            for (int k = 0; it.hasNext(); k++) {
                String candidate = it.next();
                if (k == skip) {
                    id = candidate;
                    it.remove();
                    break;
                }
            }
            if (id != null && taken.add(id)) {
                out.add(new Item(INSTAGRAM, id, indexTitle(id), "igprobe",
                        "https://www.instagram.com/reel/" + id + "/embed/", 0, 0));
            }
        }
        igAvailable = IG_POOL.size();
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
        // Skipping only the first handful returns the same popular clips on
        // every device. Sampling far deeper, from a per-install offset, spreads
        // the choice out and reaches past what everyone else sees.
        int skip = 8 + Math.abs(PICK.nextInt(40));
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
        String html = getTiktokTag(word);
        if (html.isEmpty()) {
            // One retry: TikTok refuses in short bursts and answers again within
            // about fifteen seconds, so a single immediate failure is not a
            // reason to stand the source down.
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            html = getTiktokTag(word);
        }
        Matcher m = Pattern.compile("tiktok\\.com/@[\\w.\\-]+/video/(\\d{15,25})").matcher(html);
        Set<String> seen = new HashSet<String>();
        while (m.find() && out.size() < 12) {
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            out.add(new Item(TIKTOK, id, "", "frame",
                    "https://www.tiktok.com/player/v1/" + id
                            + "?autoplay=1&loop=0&controls=0&music_info=0&description=0", 0, 0));
        }
        // No sitemap fallback: that list is a fixed set of a few hundred videos,
        // so using it when the tag pages are throttled turns an endless feed into
        // a loop over the same small pool. Returning nothing marks the source as
        // failing, which rests it and lets the feed carry on elsewhere.
        return out;
    }

    /** A tag page, or an empty string when TikTok refused this attempt. */
    private static String getTiktokTag(String word) {
        try {
            String html = getAs("https://www.tiktok.com/embed/tag/" + enc(word), UI_MOBILE_UA);
            return html.contains("/video/") ? html : "";
        } catch (Exception e) {
            return "";
        }
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
                            + "?autoplay=1&loop=0&controls=0&music_info=0&description=0", 0, 0));
        }
        Collections.shuffle(out, PICK);
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
