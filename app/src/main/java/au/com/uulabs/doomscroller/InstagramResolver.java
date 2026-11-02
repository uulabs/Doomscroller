package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.webkit.ValueCallback;
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
                    + "if(v){v.muted=true;v.volume=0;var s=v.currentSrc||v.src||'';"
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
    private final Object lock = new Object();
    private WebView web;
    private String found;
    private boolean done;

    InstagramResolver(Activity activity) {
        this.activity = activity;
    }

    /** Blocking, so it must not be called on the main thread. */
    String resolveBlocking(final String reelId, long timeoutMs) {
        synchronized (lock) {
            found = null;
            done = false;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                WebView w = new WebView(activity);
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setUserAgentString("Mozilla/5.0 (Linux; Android 15; Pixel 8) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/155.0.0.0 Mobile Safari/537.36");
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        poll(view, 0);
                    }
                });
                web = w;
                w.loadUrl("https://www.instagram.com/reel/" + reelId + "/embed/");
            }
        });

        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!done) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(Math.min(left, 250));
                } catch (InterruptedException e) {
                    break;
                }
            }
            String result = found;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web != null) {
                        try {
                            web.destroy();
                        } catch (Exception ignored) {
                        }
                        web = null;
                    }
                }
            });
            return result;
        }
    }

    private void poll(final WebView view, final int attempt) {
        synchronized (lock) {
            if (done) {
                return;
            }
        }
        if (attempt > 40) {
            return;
        }
        view.evaluateJavascript(PROBE_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String candidate = value == null ? "" : value.replace("\"", "").trim();
                // The page data escapes the separators of its own URL.
                candidate = candidate.replace("\\u0026", "&").replace("\\/", "/");
                if (candidate.startsWith("http")) {
                    synchronized (lock) {
                        if (!done) {
                            found = candidate;
                            done = true;
                            lock.notifyAll();
                        }
                    }
                    return;
                }
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        poll(view, attempt + 1);
                    }
                }, 500);
            }
        });
    }
}
