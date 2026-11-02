package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Reads a Reel's media URL out of Instagram's embed page.
 *
 * The embed is a whole post: account header, the clip, a like and comment
 * footer and a link back. Playing that page means playing all of it. The clip's
 * own media URL is in the page all the same, so it is read here and handed to
 * our player, which knows nothing about Instagram's furniture.
 *
 * The embed draws a poster and only builds a video element once it is played, so
 * three routes are tried: an existing video element, the page data where the
 * media URL is stored, and a nudge to whatever starts playback.
 */
final class InstagramResolver {

    private static final String PROBE_JS =
            "(function(){try{"
                    + "var bs=String.fromCharCode(92);"
                    + "var v=document.querySelector('video');"
                    + "if(v){var s=v.currentSrc||v.src||'';"
                    + "if(s)return s;"
                    + "var src=v.querySelector('source');"
                    + "if(src){var s2=src.src||src.getAttribute('src')||'';"
                    + "if(s2)return s2;}}"
                    + "var h=document.documentElement.innerHTML;"
                    + "var k=h.indexOf('video_url');"
                    + "if(k>0){var c=h.indexOf(':',k);"
                    + "var q1=h.indexOf('\"',c);var q2=h.indexOf('\"',q1+1);"
                    + "if(q1>0&&q2>q1){var raw=h.substring(q1+1,q2);"
                    // The page stores the URL as an escaped JSON string. Strip the
                    // escapes directly instead of parsing, which avoids a second
                    // layer of quoting.
                    + "raw=raw.split(bs+'u0026').join('&');"
                    + "raw=raw.split(bs).join('');"
                    + "return raw;}}"
                    + "var btn=document.querySelector('button');"
                    + "if(btn&&!window.__uuTapped){window.__uuTapped=1;btn.click();}"
                    + "return '';}catch(e){return '';}})()";

    private final Activity activity;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean destroyed;
    private volatile Request current;

    // Each asynchronous callback belongs to one request, never to shared result state.
    private static final class Request {
        volatile boolean done;
        String found;
        boolean polling;
        WebView web; // main thread only
    }

    InstagramResolver(Activity activity) {
        this.activity = activity;
    }

    /** Blocking, so it must not be called on the main thread. */
    String resolveBlocking(final String reelId, long timeoutMs) {
        if (destroyed) return null;
        final Request request = new Request();
        current = request;
        handler.post(() -> {
            if (destroyed || request.done || activity.isFinishing()) {
                finish(request, null);
                return;
            }
            WebView w = new WebView(activity);
            request.web = w;
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUserAgentString("Mozilla/5.0 (Linux; Android 15; Pixel 8) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/155.0.0.0 Mobile Safari/537.36");
            w.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    if (request.done || destroyed || request.polling) return;
                    request.polling = true;
                    handler.removeCallbacksAndMessages(request);
                    poll(request, 0);
                }
            });
            w.loadUrl("https://www.instagram.com/reel/" + reelId + "/embed/");
        });
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        String result;
        synchronized (request) {
            while (!request.done && !destroyed) {
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) break;
                try { request.wait(left); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            request.done = true;
            result = request.found;
        }
        // Captures this request's WebView. Cleanup cannot destroy the next one.
        handler.post(() -> close(request));
        if (current == request) current = null;
        return result;
    }

    private void finish(Request request, String value) {
        synchronized (request) {
            if (request.done) return;
            request.found = value;
            request.done = true;
            request.notifyAll();
        }
    }

    private void close(Request request) {
        handler.removeCallbacksAndMessages(request);
        if (request.web != null) {
            request.web.stopLoading();
            request.web.destroy();
            request.web = null;
        }
    }

    private void poll(final Request request, final int attempt) {
        if (request.done || destroyed || request.web == null) return;
        if (attempt > 40) { finish(request, null); return; }
        request.web.evaluateJavascript(PROBE_JS, value -> {
            if (request.done || destroyed) return;
            String candidate = "";
            try {
                Object decoded = new org.json.JSONTokener(value == null ? "null" : value).nextValue();
                if (decoded instanceof String) candidate = ((String) decoded).trim();
            } catch (org.json.JSONException ignored) { }
            if (candidate.startsWith("https://") || candidate.startsWith("http://")) {
                finish(request, candidate);
                return;
            }
            handler.postAtTime(() -> poll(request, attempt + 1), request,
                    android.os.SystemClock.uptimeMillis() + 500);
        });
    }

    /** Called on the main thread when the owning Activity is destroyed. */
    void destroy() {
        destroyed = true;
        Request request = current;
        if (request != null) {
            finish(request, null);
            close(request);
        }
    }
}
