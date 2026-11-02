package au.com.uulabs.webprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UU Doomscroller - WebView discovery probe.
 *
 * Loads each candidate provider surface in a real Android WebView with a
 * Chrome user agent, JavaScript and storage enabled, waits for the page to
 * settle, then counts the markers that would let a feed find an item.
 *
 * Every result is logged as one line, so `adb logcat -s WEBPROBE` is the
 * entire interface. This is a throwaway evidence tool, not application code.
 */
public class Main extends Activity {

    static final String TAG = "WEBPROBE";

    /** A Chrome mobile UA, not WebView's default: the "wv" marker is detectable. */
    static final String UA =
            "Mozilla/5.0 (Linux; Android 15; Blade A3 Plus) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36";

    static final String[] URLS = {
            "https://www.instagram.com/explore/tags/cake/",
            "https://x.com/search?q=cake%20filter%3Avideos&f=live",
            "https://www.youtube.com/results?search_query=cake&sp=EgIYAQ%3D%3D",
    };

    static final String JS =
            "(function(){var h=document.documentElement.outerHTML||'';"
                    + "function c(s,n){var k=0,i=0;while((i=s.indexOf(n,i))!==-1){k++;i+=n.length;}return k;}"
                    + "var o={url:location.href,title:document.title,len:h.length,"
                    + "videos:document.getElementsByTagName('video').length,"
                    + "slashVideo:c(h,'/video/'),reel:c(h,'/reel/'),igpost:c(h,'instagram.com/p/'),"
                    + "status:c(h,'/status/'),videoId:c(h,'videoId'),playAddr:c(h,'playAddr'),"
                    + "login:c(h,'accounts/login'),captcha:c(h,'captcha'),"
                    + "text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,160)};"
                    + "return JSON.stringify(o);})()";

    WebView web;
    File outFile;
    String[] urls = URLS;
    int index = -1;
    boolean settled = false;
    final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        outFile = new File(getFilesDir(), "result.txt");
        if (getIntent().getBooleanExtra("fresh", false)) {
            try { outFile.delete(); } catch (Exception ignored) { }
        }
        String only = getIntent().getStringExtra("url");
        File target = new File(getFilesDir(), "target.txt");
        if (target.exists()) {
            try {
                byte[] raw = new byte[(int) target.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(target);
                int n = in.read(raw);
                in.close();
                String b64 = new String(raw, 0, Math.max(n, 0), "UTF-8").trim();
                only = new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8");
            } catch (Exception ignored) { }
            target.delete();
        }
        if (only != null) {
            urls = new String[]{only};
        }
        writeLine("BEGIN");
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        evaluate();
                    }
                }, 10000);
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest r,
                                        android.webkit.WebResourceError e) {
                writeLine("ERROR " + index + " " + r.getUrl() + " " + e.getErrorCode());
            }
        });
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        next();
    }

    /** logd on some devices drops app logs; the file is the reliable channel. */
    void writeLine(String line) {
        Log.i(TAG, line);
        try {
            FileOutputStream f = new FileOutputStream(outFile, true);
            f.write((line + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) { }
    }

    void next() {
        index++;
        if (index >= urls.length) {
            writeLine("DONE");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, 1500);
            return;
        }
        settled = false;
        final String url = urls[index];
        writeLine("LOAD " + index + " " + url);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!settled) {
                    settled = true;
                    writeLine("TIMEOUT " + index);
                    next();
                }
            }
        }, 40000);
        web.loadUrl(url);
    }

    void evaluate() {
        final int i = index;
        if (settled) {
            return;
        }
        web.evaluateJavascript(JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (settled) {
                    return;
                }
                settled = true;
                writeLine("RESULT " + i + " " + value);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        next();
                    }
                }, 2500);
            }
        });
    }
}
