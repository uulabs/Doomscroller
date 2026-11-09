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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** Where discovery reports what it did; null outside trace builds. */
    public static volatile java.util.function.Consumer<String> trace;

    private static void note(String line) {
        java.util.function.Consumer<String> t = trace;
        if (t != null) t.accept(line);
    }

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
            Set<String> facebook = new HashSet<>(), instagram = new HashSet<>(), x = new HashSet<>();
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null && rows < 20000) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\\t", 2);
                if (fields.length != 2) continue;
                if (FACEBOOK.equals(fields[0]) && fields[1].matches("[0-9]{6,}")) facebook.add(fields[1]);
                else if (INSTAGRAM.equals(fields[0]) && fields[1].matches("[A-Za-z0-9_-]{5,}")) instagram.add(fields[1]);
                else if (X.equals(fields[0]) && fields[1].matches("[0-9]{15,20}")) x.add(fields[1]);
                rows++;
            }
            List<String> f = new ArrayList<>(facebook), i = new ArrayList<>(instagram), p = new ArrayList<>(x);
            Collections.shuffle(f, PICK); Collections.shuffle(i, PICK); Collections.shuffle(p, PICK);
            FB.seed(f); IG.seed(i); XS.seed(p);
            reelCacheLoaded = true;
        }
    }

    /**
     * What a search-backed source has to hand: reels or posts found live for
     * the subjects drawn this session, and behind them the bundled list. The
     * live set is always drawn first, so the feed is as random as the web
     * allows; the list is reached only when every index has refused or come
     * back empty, so a phone the search engines turn away still has a feed.
     */
    static final class Supply {
        final java.util.ArrayDeque<String> live = new java.util.ArrayDeque<String>();
        final java.util.ArrayDeque<String> list = new java.util.ArrayDeque<String>();
        final Set<String> known = new HashSet<String>();
        volatile long freshAt;
        volatile long blockedUntil;
        /** How many were handed out from each side, for the audit trail. */
        volatile int servedLive;
        volatile int servedList;

        synchronized void seed(java.util.Collection<String> ids) {
            for (String id : ids) if (known.add(id)) list.addLast(id);
        }

        synchronized int addLive(java.util.Collection<String> ids) {
            int added = 0;
            for (String id : ids) if (known.add(id)) { live.addLast(id); added++; }
            return added;
        }

        /** What can be drawn now: the bundled list counts only while lists are allowed. */
        synchronized int available() {
            return live.size() + (bundledLists ? list.size() : 0);
        }

        synchronized boolean liveEmpty() { return live.isEmpty(); }

        synchronized int liveSize() { return live.size(); }

        /** Unscreened live candidates only, for reading; never the bundled list. */
        synchronized List<String> drawLive(int n) {
            List<String> out = new ArrayList<String>();
            while (out.size() < n && !live.isEmpty()) {
                int skip = PICK.nextInt(live.size());
                java.util.Iterator<String> it = live.iterator();
                for (int k = 0; it.hasNext(); k++) {
                    String candidate = it.next();
                    if (k == skip) { out.add(candidate); it.remove(); break; }
                }
            }
            return out;
        }

        /** The bundled list only, when nothing live passed; nothing while lists are off. */
        synchronized List<String> drawList(int n) {
            List<String> out = new ArrayList<String>();
            while (bundledLists && out.size() < n && !list.isEmpty()) {
                int skip = PICK.nextInt(list.size());
                java.util.Iterator<String> it = list.iterator();
                for (int k = 0; it.hasNext(); k++) {
                    String candidate = it.next();
                    if (k == skip) { out.add(candidate); it.remove(); break; }
                }
                servedList++;
            }
            return out;
        }

        /** Live first; the bundled list only when nothing live is waiting and lists are allowed. */
        synchronized List<String> draw(int n) {
            List<String> out = new ArrayList<String>();
            while (out.size() < n) {
                java.util.ArrayDeque<String> from = live.isEmpty() ? list : live;
                if (from == list && !bundledLists) break;
                if (from.isEmpty()) break;
                int skip = PICK.nextInt(from.size());
                java.util.Iterator<String> it = from.iterator();
                for (int k = 0; it.hasNext(); k++) {
                    String candidate = it.next();
                    if (k == skip) { out.add(candidate); it.remove(); break; }
                }
                if (from == live) servedLive++; else servedList++;
            }
            return out;
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
        long started = System.currentTimeMillis();
        try {
            String body = fetch(url, cookie, requireSuccess);
            note("get " + url.substring(0, Math.min(70, url.length())) + " bytes=" + body.length()
                    + " in " + (System.currentTimeMillis() - started) + "ms");
            return body;
        } catch (IOException e) {
            note("get " + url.substring(0, Math.min(70, url.length())) + " failed " + e
                    + " after " + (System.currentTimeMillis() - started) + "ms");
            throw e;
        }
    }

    private static String fetch(String url, String cookie, boolean requireSuccess) throws IOException {
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
                if (FB.available() > 0) return 0;
                readyAt = Math.max(FB.blockedUntil, FB.freshAt + 5000L);
            } else if (INSTAGRAM.equals(source)) {
                if (IG.available() > 0) return 0;
                readyAt = Math.max(IG.blockedUntil, IG.freshAt + 5000L);
            } else if (X.equals(source)) {
                if (XS.available() > 0) return 0;
                readyAt = Math.max(XS.blockedUntil, XS.freshAt + 5000L);
                // X waits for whichever answers first: a search index or Wikipedia's citation table.
                long index = Math.min(indexBlockedUntil.get(0), indexBlockedUntil.get(1));
                readyAt = Math.max(readyAt, Math.min(index, wikiBlockedUntil));
                earliest = Math.min(earliest, readyAt);
                continue;
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
        rememberIndexTitles(Pattern.compile(Pattern.quote(host) + "/reel/([A-Za-z0-9_-]{5,})"), 1, decoded);
    }

    private static synchronized void rememberIndexTitles(Pattern link, int idGroup, CharSequence decoded) {
        Matcher m = link.matcher(decoded);
        while (m.find()) {
            String id = m.group(idGroup);
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

    static final Supply FB = new Supply();

    /**
     * Reels found the same way as Instagram's: permalinks from a search index
     * for the subject drawn, searched afresh whenever nothing live is waiting.
     */
    private static List<Item> facebook(String word) throws IOException {
        searchReels(FB, "facebook.com", word, Pattern.compile("facebook\\.com/reel/([0-9]{6,})"));
        List<Item> out = new ArrayList<Item>();
        // produce() consumes one resolved item; retain the other links.
        for (String id : FB.draw(1)) {
            out.add(new Item(FACEBOOK, id, indexTitle(id), "fbprobe", "", 0, 0));
        }
        return out;
    }

    /**
     * One live search per subject while nothing live is waiting, paced so a
     * refused index is not hammered. Failure or an empty answer blocks only the
     * search; drawing then falls through to the bundled list.
     */
    private static void searchReels(Supply supply, String host, String word, Pattern link) {
        long now = System.currentTimeMillis();
        if (!supply.liveEmpty() || now <= supply.blockedUntil || now - supply.freshAt <= 5000L) {
            return;
        }
        supply.freshAt = now;
        try {
            String html = reelSearch(host, word);
            Matcher m = link.matcher(html);
            List<String> found = new ArrayList<String>();
            while (m.find()) found.add(m.group(1));
            int added = supply.addLive(found);
            note(host + " live search for " + word + ": " + found.size() + " links, " + added + " new");
            if (added == 0) {
                supply.blockedUntil = now + 5000L;
            }
        } catch (IOException e) {
            note(host + " live search failed: " + e);
            supply.blockedUntil = now + 10 * 60_000L;
        }
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
    static final Supply IG = new Supply();

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

    /** Where the last TikTok verdict came from: asr, caption, country or none. */
    public static volatile String lastTiktokSignal = "none";

    /**
     * Three signals, strongest first. TikTok's own speech recognition names
     * the spoken language when the clip has auto-captions; the caption's
     * words are next; and when both are silent the country the clip was made
     * in stands in for the language, which skips some English clips made
     * abroad but stops far more foreign ones. A clip whose language is not
     * one the app offers is reported as OTHER so it is never chosen.
     */
    public static String tiktokCaptionLanguage(String id) {
        lastCaption = "";
        lastTiktokSignal = "none";
        try {
            String html = getAs("https://www.tiktok.com/@x/video/" + id, UI_MOBILE_UA);
            lastCaptionNote = "bytes=" + html.length();
            // The page escapes its own JSON, so quotes arrive as \" and the
            // slashes as \/. Normalise first, then read the fields directly.
            String h = html.replace("\\/", "/").replace("\\\"", "\"");
            // The clip's own record starts at its id; everything before it is
            // page furniture (hashtag blurbs, comments) with captions of its own.
            int anchor = h.indexOf("\"id\":\"" + id + "\"");
            int at = h.indexOf("\"desc\":\"", anchor < 0 ? 0 : anchor);
            if (at < 0) {
                lastCaptionNote = "no desc in " + html.length();
            } else {
                int from = at + 8;
                int to = h.indexOf('"', from);
                if (to > from) {
                    lastCaption = jsonText(h.substring(from, Math.min(to, from + 400)));
                }
            }
            String asr = tiktokAsrLanguage(h, anchor < 0 ? 0 : anchor);
            if (asr != null) {
                lastTiktokSignal = "asr";
                return asr;
            }
            String spoken = Vocab.detect(lastCaption);
            if (spoken == null) spoken = Vocab.detectLatin(lastCaption);
            if (spoken != null) {
                lastTiktokSignal = "caption";
                return spoken;
            }
            String creator = tiktokCreatorLanguage(h, anchor < 0 ? 0 : anchor);
            if (creator != null) {
                lastTiktokSignal = "creator";
                return creator;
            }
            String country = tiktokCountryLanguage(h, anchor < 0 ? 0 : anchor);
            if (country != null) {
                lastTiktokSignal = "country";
                return country;
            }
            return null;
        } catch (Exception e) {
            lastCaptionNote = "err " + e.getClass().getSimpleName();
            return null;
        }
    }

    private static final Pattern ASR_ENTRY = Pattern.compile(
            "\\{[^{}]*\"LanguageCodeName\":\"([A-Za-z]{2,3})[^\"]*\"[^{}]*\"Source\":\"ASR\"[^{}]*\\}");

    /**
     * The language the clip's creator writes their own name and bio in. A
     * music-only clip with a wordless caption says nothing itself, but a
     * creator whose profile is written in Japanese or Spanish is making clips
     * for that audience. Read only when speech and caption were silent.
     */
    static String tiktokCreatorLanguage(String h, int anchor) {
        StringBuilder words = new StringBuilder();
        for (String key : new String[]{"\"nickname\":\"", "\"signature\":\""}) {
            int at = h.indexOf(key, anchor);
            if (at < 0) continue;
            int from = at + key.length();
            int to = h.indexOf('"', from);
            if (to > from) {
                words.append(jsonText(h.substring(from, Math.min(to, from + 300)))).append(' ');
            }
        }
        String text = words.toString().trim();
        if (text.isEmpty()) return null;
        String spoken = Vocab.detect(text);
        return spoken != null ? spoken : Vocab.detectLatin(text);
    }

    /** The language TikTok's speech recognition heard, or null without auto-captions. */
    static String tiktokAsrLanguage(String h, int anchor) {
        int at = h.indexOf("\"subtitleInfos\":[", anchor);
        if (at < 0) return null;
        int end = h.indexOf("]", at);
        if (end < 0) return null;
        Matcher m = ASR_ENTRY.matcher(h.substring(at, end + 1));
        if (!m.find()) return null;
        return isoLanguage(m.group(1).toLowerCase(java.util.Locale.ROOT));
    }

    /** Maps a subtitle language code to an offered language, or OTHER. */
    static String isoLanguage(String code) {
        switch (code) {
            case "eng": case "en": return "en";
            case "jpn": case "ja": return "ja";
            case "zho": case "cmn": case "yue": case "zh": return "zh";
            case "kor": case "ko": return "ko";
            case "spa": case "es": return "es";
            case "por": case "pt": return "pt";
            case "fra": case "fre": case "fr": return "fr";
            case "deu": case "ger": case "de": return "de";
            case "ita": case "it": return "it";
            case "rus": case "ru": return "ru";
            case "ara": case "ar": return "ar";
            case "hin": case "hi": return "hi";
            case "ind": case "id": case "in": return "id";
            case "tur": case "tr": return "tr";
            case "tha": case "th": return "th";
            default: return Vocab.OTHER;
        }
    }

    /**
     * The main language of the country a clip was made in, for countries with
     * one clear language. Mixed or English-speaking countries give null.
     */
    static String tiktokCountryLanguage(String h, int anchor) {
        Matcher m = Pattern.compile("\"locationCreated\":\"([A-Z]{2})\"").matcher(h);
        if (!m.find(anchor)) return null;
        return countryLanguage(m.group(1));
    }

    static String countryLanguage(String cc) {
        switch (cc) {
            case "BR": case "PT": case "AO": case "MZ": return "pt";
            case "ES": case "MX": case "AR": case "CO": case "CL": case "PE": case "VE":
            case "EC": case "GT": case "CU": case "BO": case "DO": case "HN": case "PY":
            case "SV": case "NI": case "CR": case "PA": case "UY": return "es";
            case "IT": return "it";
            case "FR": case "SN": case "CI": return "fr";
            case "DE": case "AT": return "de";
            case "ID": return "id";
            case "TR": return "tr";
            case "JP": return "ja";
            case "KR": return "ko";
            case "CN": case "TW": case "HK": case "MO": return "zh";
            case "RU": case "BY": case "KZ": return "ru";
            case "TH": return "th";
            case "SA": case "EG": case "AE": case "IQ": case "MA": case "DZ": case "TN":
            case "JO": case "KW": case "QA": case "OM": case "LB": case "SY": case "YE":
            case "LY": case "BH": return "ar";
            // One clear language the app does not offer.
            case "VN": case "PL": case "NL": case "BE": case "GR": case "IL": case "BD":
            case "LK": case "NP": case "MM": case "KH": case "LA": case "ET": case "GE":
            case "AM": case "MN": case "HU": case "CZ": case "SK": case "RO": case "BG":
            case "UA": case "SE": case "NO": case "DK": case "FI": case "IR": case "AF":
            case "UZ": case "AZ": case "RS": case "HR": case "SI": case "LT": case "LV":
            case "EE": case "IS": return Vocab.OTHER;
            default: return null;   // English-speaking or genuinely mixed
        }
    }

    /** Decodes the escapes left in a JSON string once the quotes and slashes are normalised. */
    static String jsonText(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                if (n == 'u' && i + 5 < raw.length()) {
                    try {
                        out.append((char) Integer.parseInt(raw.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                } else if (n == 'n' || n == 't' || n == 'r') {
                    out.append(' ');
                    i++;
                    continue;
                } else if (n == '\\' || n == '"' || n == '/') {
                    out.append(n);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static List<Item> instagram(String word) throws IOException {
        searchReels(IG, "instagram.com", word, Pattern.compile("instagram\\.com/reel/([A-Za-z0-9_-]{5,})"));
        List<Item> out = new ArrayList<Item>();
        // One resolved item per pass; the other live links wait their turn.
        for (String id : IG.draw(1)) {
            out.add(new Item(INSTAGRAM, id, indexTitle(id), "igprobe",
                    "https://www.instagram.com/reel/" + id + "/embed/", 0, 0));
        }
        return out;
    }

    // --------------------------------------------------------------- youtube

    /** Set from the settings; landscape results are only asked for when allowed. */
    private static boolean allowLandscape;

    /**
     * Whether the bundled lists may be drawn at all. Off, every clip comes from
     * a live search or Wikipedia's citations, and a phone the indexes refuse
     * waits and retries rather than showing a list the app shipped with.
     */
    private static volatile boolean bundledLists = true;

    public static void setBundledLists(boolean allowed) {
        bundledLists = allowed;
    }

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
        // Each clip's record on the tag page opens with its id and caption.
        Map<String, String> captions = new HashMap<String, String>();
        String normalised = html.replace("\\/", "/").replace("\\\"", "\"");
        Matcher c = Pattern.compile("\"id\":\"(\\d{15,25})\",\"desc\":\"([^\"]{0,400})").matcher(normalised);
        while (c.find()) {
            captions.put(c.group(1), jsonText(c.group(2)));
        }
        Matcher m = Pattern.compile("tiktok\\.com/@[\\w.\\-]+/video/(\\d{15,25})").matcher(html);
        Set<String> seen = new HashSet<String>();
        while (m.find() && out.size() < 12) {
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            String caption = captions.get(id);
            out.add(new Item(TIKTOK, id, caption == null ? "" : caption, "frame",
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
     * Posts found for the current subject but not yet played. The timeline
     * widget this source used to read now answers "rate limit exceeded" to
     * every request, which is why X never produced a clip; posts are found
     * through the same public search indexes as reels instead, and each is
     * read from the syndication post endpoint, which still answers with the
     * post's text, language and mp4 renditions.
     */
    static final Supply XS = new Supply();
    private static final Pattern STATUS_LINK = Pattern.compile(
            "(?:x|twitter)\\.com/([A-Za-z0-9_]{1,15})/status/([0-9]{15,20})");

    /** Candidates kept waiting before another index or Wikipedia call is made. */
    private static final int X_LOW_WATER = 12;
    /** Posts read in one screening pass, four at a time. */
    private static final int X_SCREEN_BATCH = 24;
    private static volatile long wikiBlockedUntil;
    /** Languages the viewer allows, null for any; set by the app alongside the landscape rule. */
    private static volatile Set<String> xLanguages;
    private static volatile boolean xKnownLanguageOnly;

    public static void setLanguageFilter(Set<String> languages, boolean knownOnly) {
        xLanguages = languages == null ? null : new HashSet<String>(languages);
        xKnownLanguageOnly = knownOnly;
    }

    /**
     * Candidates come from a search index for the subject while one answers,
     * otherwise from a random run of posts cited on Wikipedia. Only some posts
     * carry a video, so candidates are read and screened here, several at a
     * time, and only playable ones are handed on. The bundled list, screened
     * when it was built, is the last resort.
     */
    private static List<Item> x(String word) throws IOException {
        long now = System.currentTimeMillis();
        if (XS.liveSize() < X_LOW_WATER && now > XS.blockedUntil && now - XS.freshAt > 5000L) {
            XS.freshAt = now;
            int added = 0;
            try {
                String html = statusSearch(word);
                Matcher m = STATUS_LINK.matcher(html);
                List<String> found = new ArrayList<String>();
                while (m.find()) found.add(m.group(2));
                added = XS.addLive(found);
                note("x live search for " + word + ": " + found.size() + " links, " + added + " new");
            } catch (IOException e) {
                note("x search failed: " + e);
            }
            if (added == 0 && now > wikiBlockedUntil) {
                try {
                    List<String> cited = wikipediaPosts();
                    added = XS.addLive(cited);
                    note("x wikipedia slice: " + cited.size() + " posts, " + added + " new");
                    if (added == 0) wikiBlockedUntil = now + 15_000L;
                } catch (IOException e) {
                    note("x wikipedia failed: " + e);
                    wikiBlockedUntil = now + 5 * 60_000L;
                }
            }
            if (added == 0) XS.blockedUntil = now + 5000L;
        }
        List<Item> out = new ArrayList<Item>();
        // Cited posts are mostly text: a few passes may be needed before one plays.
        for (int pass = 0; pass < 3 && out.isEmpty(); pass++) {
            List<String> batch = XS.drawLive(X_SCREEN_BATCH);
            if (batch.isEmpty()) break;
            List<String> passed = screenPosts(batch, 3);
            note("x screened " + batch.size() + " live posts: " + passed.size() + " playable");
            for (String id : passed) {
                XS.servedLive++;
                out.add(new Item(X, id, indexTitle(id), "xprobe", "", 0, 0));
            }
        }
        if (!out.isEmpty()) return out;
        for (String id : XS.drawList(6)) {
            out.add(new Item(X, id, indexTitle(id), "xprobe", "", 0, 0));
        }
        return out;
    }

    private static final java.util.concurrent.ExecutorService X_READERS =
            java.util.concurrent.Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "x-reader");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** Reads posts four at a time; keeps those with a video the viewer's rules allow. */
    private static List<String> screenPosts(List<String> ids, int wanted) {
        List<String> passed = new ArrayList<String>();
        for (int from = 0; from < ids.size() && passed.size() < wanted; from += 8) {
            final List<String> group = ids.subList(from, Math.min(ids.size(), from + 8));
            List<java.util.concurrent.Future<XPost>> reads = new ArrayList<java.util.concurrent.Future<XPost>>();
            for (final String id : group) {
                reads.add(X_READERS.submit(new java.util.concurrent.Callable<XPost>() {
                    @Override public XPost call() { return xPost(id); }
                }));
            }
            for (int i = 0; i < reads.size(); i++) {
                XPost post;
                try {
                    post = reads.get(i).get(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return passed;
                } catch (Exception failed) {
                    continue;
                }
                if (post == null || post.mp4 == null) continue;   // unreadable, gone, or no video
                if (!allowLandscape && post.width > 0 && post.height > 0 && post.width > post.height) continue;
                Set<String> languages = xLanguages;
                if (post.language != null && languages != null && !languages.contains(post.language)) continue;
                if (post.language == null && xKnownLanguageOnly) continue;
                passed.add(group.get(i));
            }
        }
        return passed;
    }

    /** Where Wikipedia's link table ends; a slice above it comes back empty and lowers this. */
    private static volatile long wikiCeiling = 1_150_000_000L;
    private static final String APP_UA = "UUDoomscroller/" + BuildConfig.VERSION_NAME
            + " (https://github.com/uulabs/Doomscroller)";

    /**
     * Posts cited by English Wikipedia articles. The encyclopaedia publishes its
     * table of external links, numbered; a random number picks a random run of
     * citations, with no search engine involved. About a tenth carry a video.
     */
    private static List<String> wikipediaPosts() throws IOException {
        long start = (long) (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * wikiCeiling);
        String json = getAs("https://en.wikipedia.org/w/api.php?action=query&list=exturlusage"
                + "&euquery=twitter.com&euprotocol=https&eulimit=500&euprop=url&format=json"
                + "&eunamespace=0&eucontinue=" + start, APP_UA);
        List<String> ids = new ArrayList<String>();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject query = root.optJSONObject("query");
            JSONArray rows = query == null ? null : query.optJSONArray("exturlusage");
            Pattern status = Pattern.compile("/status(?:es)?/([0-9]{15,20})");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Matcher m = status.matcher(row.optString("url", ""));
                if (m.find() && !ids.contains(m.group(1))) ids.add(m.group(1));
            }
            if ((rows == null || rows.length() == 0) && start > 100_000_000L) {
                wikiCeiling = start;
            }
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid Wikipedia answer", invalid);
        }
        return ids;
    }

    /** How many Instagram, Facebook and X clips came from live search versus the bundled list. */
    public static String supplyAudit() {
        return "live/list ig=" + IG.servedLive + "/" + IG.servedList
                + " fb=" + FB.servedLive + "/" + FB.servedList
                + " x=" + XS.servedLive + "/" + XS.servedList;
    }

    /** Search results naming posts on X about the subject, with their snippets. */
    private static String statusSearch(String word) throws IOException {
        String query = enc("site:x.com /status/ " + word + " video");
        String[] indexes = {"https://search.brave.com/search?q=",
                "https://html.duckduckgo.com/html/?q="};
        for (int i = 0; i < indexes.length; i++) {
            long now = System.currentTimeMillis();
            if (now < indexBlockedUntil.get(i)) {
                note("x search index " + i + " blocked for " + (indexBlockedUntil.get(i) - now) + "ms");
                continue;
            }
            try {
                long started = System.currentTimeMillis();
                String html = get(indexes[i] + query, null, true);
                StringBuilder decoded = new StringBuilder(html);
                Matcher links = Pattern.compile("uddg=([^&\"<> ]+)").matcher(html);
                while (links.find()) {
                    try { decoded.append(' ').append(java.net.URLDecoder.decode(links.group(1), "UTF-8")); }
                    catch (IllegalArgumentException ignored) { }
                }
                boolean any = STATUS_LINK.matcher(decoded).find();
                note("x search index " + i + " bytes=" + html.length() + " links=" + any
                        + " in " + (System.currentTimeMillis() - started) + "ms");
                if (any) {
                    rememberIndexTitles(STATUS_LINK, 2, decoded);
                    return decoded.toString();
                }
            } catch (IOException unavailable) {
                note("x search index " + i + " failed: " + unavailable);
                indexBlockedUntil.set(i, now + 10 * 60_000L);
            }
        }
        return "";
    }

    /** Language X recorded for the post last read, or null when it gave none. */
    public static volatile String lastXLanguage;
    /** Geometry of the rendition xMedia() chose, 0 when unknown. */
    public static volatile int lastXWidth;
    public static volatile int lastXHeight;

    /**
     * Reads one post. Null means the endpoint could not be read; an empty
     * string means the post exists but has no video of its own, so the
     * caller should move on without blaming the source.
     */
    public static String xMedia(String id) {
        lastCaption = "";
        lastXLanguage = null;
        lastXWidth = 0;
        lastXHeight = 0;
        XPost post = xPost(id);
        if (post == null) {
            return null;
        }
        lastCaption = post.text;
        lastXLanguage = post.language;
        lastXWidth = post.width;
        lastXHeight = post.height;
        return post.mp4 == null ? "" : post.mp4;
    }

    /** One post as the syndication endpoint describes it; mp4 is null when it has no video. */
    static final class XPost {
        String text = "";
        String language;
        String mp4;
        int width;
        int height;
    }

    /** Reads one post; null when the endpoint could not be read. Safe to call from any thread. */
    static XPost xPost(String id) {
        try {
            String json = get("https://cdn.syndication.twimg.com/tweet-result?id=" + id
                    + "&token=" + xToken(id), null);
            lastCaptionNote = "bytes=" + json.length();
            if (!json.trim().startsWith("{")) {
                return null;
            }
            JSONObject post = new JSONObject(json);
            XPost out = new XPost();
            if (!"Tweet".equals(post.optString("__typename"))) {
                return out;   // deleted, protected or age-gated: a tombstone, no video
            }
            out.text = post.optString("text", "");
            out.language = xLanguage(post.optString("lang", ""), out.text);
            JSONObject video = post.optJSONObject("video");
            if (video == null) {
                return out;
            }
            JSONArray aspect = video.optJSONArray("aspectRatio");
            if (aspect != null && aspect.length() == 2) {
                out.width = aspect.optInt(0);
                out.height = aspect.optInt(1);
            }
            JSONArray variants = video.optJSONArray("variants");
            String best = null;
            int bestWidth = -1;
            for (int i = 0; variants != null && i < variants.length(); i++) {
                JSONObject v = variants.optJSONObject(i);
                if (v == null || !"video/mp4".equals(v.optString("type"))) continue;
                String src = v.optString("src", "");
                if (!src.startsWith("https://")) continue;
                int width = 0;
                Matcher dim = Pattern.compile("/(\\d{2,4})x(\\d{2,4})/").matcher(src);
                if (dim.find()) {
                    width = Integer.parseInt(dim.group(1));
                    if (out.width == 0) {
                        out.width = width;
                        out.height = Integer.parseInt(dim.group(2));
                    }
                }
                // The largest rendition that is not wider than a phone screen.
                if (best == null || (width > bestWidth && width <= 720) || (bestWidth > 720 && width < bestWidth)) {
                    best = src;
                    bestWidth = width;
                }
            }
            out.mp4 = best;
            return out;
        } catch (Exception e) {
            lastCaptionNote = "err " + e.getClass().getSimpleName();
            return null;
        }
    }

    /**
     * X labels every post with a language; its placeholder codes (media
     * only, hashtags only, undetermined) fall back to reading the text.
     */
    static String xLanguage(String lang, String text) {
        switch (lang) {
            case "": case "und": case "qme": case "zxx": case "art": case "qht": case "qam":
            case "qst": case "qct":
                String spoken = Vocab.detect(text);
                return spoken != null ? spoken : Vocab.detectLatin(text);
            case "in": return "id";   // X keeps the old code for Indonesian
            default: return isoLanguage(lang);
        }
    }

    /** The syndication endpoint's request token, derived from the id the way its embed does. */
    static String xToken(String id) {
        double x = (Double.parseDouble(id) / 1e15) * Math.PI;
        long whole = (long) Math.floor(x);
        StringBuilder out = new StringBuilder(Long.toString(whole, 36));
        double fraction = x - whole;
        for (int i = 0; i < 14 && fraction > 0; i++) {
            fraction *= 36;
            int digit = (int) fraction;
            out.append(Character.forDigit(digit, 36));
            fraction -= digit;
        }
        return out.toString().replace("0", "");
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
