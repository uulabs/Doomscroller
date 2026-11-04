package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Resolves a YouTube video id to a directly playable stream URL.
 *
 * The work is done by youtubei.js inside a hidden WebView: it holds the client
 * session and the signature/attestation logic that a stream URL needs. Every
 * request that library makes is handed back here through a JavaScript bridge
 * and performed natively, so the page itself never has a network route.
 *
 * The app then plays the returned URL with ExoPlayer. No YouTube page is ever
 * shown, which is what keeps the player, the ads and the social overlay out of
 * the feed.
 */
public class YoutubeResolver {

    /** A resolved stream and the size of the video it carries. */
    public static final class Resolved {
        public final String url;
        public final int width;
        public final int height;
        public final String title;
        public final String channel;

        Resolved(String url, int width, int height, String title, String channel) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.title = title == null ? "" : title;
            this.channel = channel == null ? "" : channel;
        }

        public boolean portrait() {
            return width <= 0 || height <= 0 || height >= width;
        }
    }

    public interface Callback {
        void onStream(String url, int width, int height);

        void onFail(String reason);
    }

    private final Activity host;

    private void trace(String line) {
        if (!BuildConfig.DEBUG) {
            return;   // a release build must not grow a log file forever
        }
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(host.getFilesDir(), "resolve.log"), true);
            f.write((System.currentTimeMillis() % 1000000 + " " + line + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
    }
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private WebView web;
    private boolean booted;
    private boolean busy;
    private int generation;
    private String lastTitle = "";
    private String lastChannel = "";
    private String queuedId;
    private CountDownLatch latch;
    private Callback callback;

    public YoutubeResolver(Activity host) {
        this.host = host;
    }

    /** Blocking for a worker thread: returns the stream URL, or null. */
    public Resolved resolveBlocking(final String videoId, long timeoutMs) {
        latch = new CountDownLatch(1);
        final Resolved[] out = new Resolved[1];
        ui.post(new Runnable() {
            @Override
            public void run() {
                resolve(videoId, new Callback() {
                    @Override
                    public void onStream(String url, int width, int height) {
                        out[0] = new Resolved(url, width, height, lastTitle, lastChannel);
                        if (latch != null) {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFail(String reason) {
                        if (latch != null) {
                            latch.countDown();
                        }
                    }
                });
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private void resolve(final String videoId, Callback cb) {
        callback = cb;
        if (busy) {
            callback.onFail("BUSY");
            callback = null;
            return;
        }
        busy = true;
        generation++;
        trace("RESOLVE " + videoId);
        if (web == null) {
            build();
        }
        queuedId = videoId;
        if (booted) {
            dispatchQueued();
        }
    }

    private void dispatchQueued() {
        if (queuedId == null) {
            return;
        }
        String id = queuedId;
        queuedId = null;
        JSONObject message = new JSONObject();
        try {
            message.put("k", "resolve");
            message.put("id", id);
        } catch (Exception ignored) {
        }
        callPage(message.toString());
        final int gen = generation;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                // A timer from an earlier request must never cancel this one.
                if (busy && gen == generation) {
                    finish(null, 0, 0, "TIMEOUT");
                }
            }
        }, 40000);
    }

    private void build() {
        web = new WebView(host);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/131.0.0.0 Safari/537.36");
        web.addJavascriptInterface(new Bridge(), "UUChatResolver");
        web.loadUrl("file:///android_asset/externalmedia/uu-resolver.html");
    }

    private void callPage(String json) {
        if (web == null) {
            return;
        }
        final String script = "window.__uuOnMessage(" + JSONObject.quote(json) + ")";
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript(script, null);
                }
            }
        });
    }

    private void finish(String url, int width, int height, String reason) {
        trace("FINISH " + (url == null ? "fail:" + reason : "url"));
        Callback cb = callback;
        callback = null;
        busy = false;
        if (cb == null) {
            return;
        }
        if (url != null) {
            cb.onStream(url, width, height);
        } else {
            cb.onFail(reason == null ? "FAILED" : reason);
        }
    }

    /** The page's only route to the network. */
    private class Bridge {

        @JavascriptInterface
        public void postMessage(String json) {
            JSONObject message;
            try {
                message = new JSONObject(json);
            } catch (Exception e) {
                return;
            }
            String kind = message.optString("k");
            if ("note".equals(kind)) {
                trace("NOTE " + message.optString("v"));
                if ("BOOTED".equals(message.optString("v"))) {
                    booted = true;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            dispatchQueued();
                        }
                    });
                }
                return;
            }
            if ("result".equals(kind)) {
                trace("RESULT ok=" + message.optBoolean("ok") + " reason=" + message.optString("reason") + " itag=" + message.optInt("itag"));
                if (message.optBoolean("ok")) {
                    lastTitle = message.optString("title");
                    lastChannel = message.optString("channel");
                    finish(message.optString("url"), message.optInt("width"),
                            message.optInt("height"), null);
                } else {
                    finish(null, 0, 0, message.optString("reason"));
                }
                return;
            }
            if ("fetch".equals(kind)) {
                final int n = message.optInt("n");
                trace("FETCH " + n);
                try {
                    final JSONObject request = new JSONObject(message.optString("request"));
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            replyFetch(n, perform(request));
                        }
                    });
                } catch (Exception e) {
                    replyFetch(n, null);
                }
            }
        }
    }

    private static final class Reply {
        int status;
        String type;
        String bodyBase64;
    }

    private Reply perform(JSONObject request) {
        HttpURLConnection connection = null;
        try {
            String url = request.optString("url");
            if (!url.startsWith("https://")) {
                return null;   // the page's only network route is HTTPS
            }
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            String method = request.optString("method", "GET");
            connection.setRequestMethod(method);
            JSONObject headers = request.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headers.optString(key);
                    if (value != null && !"host".equalsIgnoreCase(key)
                            && !"content-length".equalsIgnoreCase(key)
                            && !"accept-encoding".equalsIgnoreCase(key)) {
                        connection.setRequestProperty(key, value);
                    }
                }
            }
            connection.setRequestProperty("Accept-Encoding", "gzip");
            String body = request.optString("body", "");
            if ("POST".equals(method) && !body.isEmpty()) {
                connection.setDoOutput(true);
                OutputStream out = connection.getOutputStream();
                out.write(body.getBytes("UTF-8"));
                out.close();
            }
            Reply reply = new Reply();
            reply.status = connection.getResponseCode();
            String encoding = connection.getContentEncoding();
            InputStream in = reply.status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (in != null) {
                if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                    in = new GZIPInputStream(in);
                }
                byte[] chunk = new byte[16384];
                int read;
                int total = 0;
                while ((read = in.read(chunk)) != -1) {
                    total += read;
                    if (total > 16 * 1024 * 1024) {
                        break;
                    }
                    buffer.write(chunk, 0, read);
                }
                in.close();
            }
            reply.type = connection.getContentType();
            reply.bodyBase64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
            return reply;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void replyFetch(int n, Reply reply) {
        JSONObject message = new JSONObject();
        try {
            message.put("k", "fetched");
            message.put("n", n);
            if (reply == null) {
                message.put("ok", false);
            } else {
                message.put("ok", true);
                message.put("status", reply.status);
                message.put("type", reply.type == null ? "application/json" : reply.type);
                message.put("body", reply.bodyBase64);
            }
        } catch (Exception ignored) {
        }
        callPage(message.toString());
    }

    /** Channel of the most recent successful resolve, to avoid one creator. */
    public String lastChannel() {
        return lastChannel;
    }

    /** Title of the most recent successful resolve, for language filtering. */
    public String lastTitle() {
        return lastTitle;
    }

    public void destroy() {
        io.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
    }
}
