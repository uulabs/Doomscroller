package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * UU Doomscroller - prototype.
 *
 * Discovery happens on the device from a randomly chosen subject; playback is
 * always a direct media stream through ExoPlayer. No provider player is ever
 * loaded, which is what keeps advertising out of the feed entirely.
 *
 * Two player slots are kept: one playing, one already buffering the next
 * video. A swipe swaps to the prepared slot, so the next video is already
 * moving instead of showing a loader.
 */
public class Main extends Activity {

    private static final int PREFETCH = 3;

    /** How long a provider-player clip is given before the feed moves on. */
    private static final long PAGE_DWELL_MILLIS = 60000L;

    private FrameLayout root;
    private FrameLayout stage;
    private TextView status;
    private View settingsButton;
    private View shareButton;
    private TextView debugText;
    private View settingsPanel;
    private View loadingView;
    private boolean videoShown;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rand = new Random();
    private final ArrayDeque<String> recentWords = new ArrayDeque<String>();
    private final ArrayDeque<String> recentChannels = new ArrayDeque<String>();
    private final ArrayDeque<Ready> ready = new ArrayDeque<Ready>();
    private final ArrayDeque<Ready> history = new ArrayDeque<Ready>();
    private final Set<String> seen = new LinkedHashSet<String>();
    private boolean seenDirty;
    private int seenAdds;

    /** How many played ids are remembered. Small enough to keep, large enough to matter. */
    private static final int SEEN_LIMIT = 1500;

    private Slot active;
    private Slot pending;
    private Sources.Item current;
    private String lastSource;
    private boolean filling;
    private boolean paused;
    private boolean wantNext;
    private boolean lastLandscape;
    private GestureDetector gestures;
    private WebView probe;
    private YoutubeResolver ytResolver;

    private static final class Ready {
        final Sources.Item item;
        final String url;
        final boolean page;
        final boolean landscape;

        Ready(Sources.Item item, String url, boolean page, boolean landscape) {
            this.item = item;
            this.url = url;
            this.page = page;
            this.landscape = landscape;
        }
    }

    private static final class Slot {
        String url;
        boolean rendered;
        ExoPlayer player;      // null for page slots
        PlayerView view;       // null for page slots
        WebView web;           // set for page slots
        View rootView;
        Sources.Item item;
    }

    @Override
    protected void onCreate(Bundle state) {
        loadSeen();
        super.onCreate(state);
        buildUi();
        applyInsets();
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) < 110 || Math.abs(vy) < 420) {
                    return false;
                }
                if (dy < 0) {
                    next();
                } else {
                    previous();
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (settingsPanel == null) {
                    togglePause();
                }
                return true;
            }
        });
        pump();
        fill();
        startLoadingWatch();
        showDisclaimerIfNeeded();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestures != null) {
            gestures.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void buildUi() {
        ytResolver = new YoutubeResolver(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        stage = new FrameLayout(this);
        root.addView(stage, match());

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#DDDDDD"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        status.setGravity(Gravity.CENTER);
        root.addView(status, match());

        settingsButton = circleButton(glyph("\u2699"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSettings();
            }
        });
        root.addView(settingsButton, cornerParams(Gravity.TOP | Gravity.LEFT));

        shareButton = circleButton(new ShareIconView(this), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        root.addView(shareButton, cornerParams(Gravity.TOP | Gravity.LEFT));

        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        ImageView face = new ImageView(this);
        face.setImageResource(R.drawable.doomer);
        loading.addView(face, new LinearLayout.LayoutParams(dp(210), dp(210)));
        TextView label = new TextView(this);
        label.setText("loading");
        label.setTextColor(Color.parseColor("#AAFFFFFF"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setLetterSpacing(0.18f);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(18), 0, 0);
        loading.addView(label);
        loadingView = loading;
        root.addView(loadingView, match());
        loadingView.setVisibility(View.VISIBLE);
        videoShown = false;

        setContentView(root);
    }

    private void loadSeen() {
        String csv = Prefs.seenIds(this);
        if (csv == null || csv.isEmpty()) {
            return;
        }
        for (String id : csv.split("\n")) {
            if (!id.isEmpty()) {
                seen.add(id);
            }
        }
    }

    /**
     * Remembers a played id, and writes the list out now and then rather than on every add.
     *
     * The set is what makes the feed stop repeating itself: without it, every launch started
     * from an empty memory and the same videos came back.
     */
    private void remember(String id) {
        boolean added;
        synchronized (seen) {
            added = seen.add(id);
            if (added) {
                seenAdds++;
                seenDirty = true;
                while (seen.size() > SEEN_LIMIT) {
                    Iterator<String> it = seen.iterator();
                    it.next();
                    it.remove();
                }
            }
        }
        if (added && seenAdds % 8 == 0) {
            flushSeen();
        }
    }

    private void flushSeen() {
        String csv;
        synchronized (seen) {
            if (!seenDirty) {
                return;
            }
            StringBuilder b = new StringBuilder();
            for (String id : seen) {
                if (b.length() > 0) {
                    b.append('\n');
                }
                b.append(id);
            }
            csv = b.toString();
            seenDirty = false;
        }
        Prefs.setSeenIds(this, csv);
    }

    /** The doomer face covers the gap before the first frame of a video. */
    /** The face shows only when the feed has nothing on screen to show. */
    private void refreshLoading() {
        setLoading(active == null);
    }

    /**
     * True when there is nothing on screen worth looking at: no slot at all, a
     * player that has not produced its first frame, or a page that never
     * painted. The face is shown exactly then.
     */
    private boolean screenIsBlank() {
        if (active == null) {
            return true;
        }
        if (active.player != null) {
            int state = active.player.getPlaybackState();
            return state != Player.STATE_READY && state != Player.STATE_ENDED;
        }
        return !active.rendered;   // a page player only reports itself
    }

    /** A steady tick keeps the face in step with what is actually visible. */
    /**
     * Shown once, before anything random can appear. The feed is unfiltered by
     * design, so the person has to be told that before they see it.
     */
    private void showDisclaimerIfNeeded() {
        if (Prefs.disclaimerAccepted(this) || settingsPanel != null) {
            return;
        }
        final View gate = new ScrollView(this);
        ((ScrollView) gate).setBackgroundColor(Color.parseColor("#0C0C0D"));
        ((ScrollView) gate).setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(26), dp(60), dp(26), dp(40));
        ((ScrollView) gate).addView(column);

        ImageView face = new ImageView(this);
        face.setImageResource(R.drawable.doomer);
        column.addView(face, new LinearLayout.LayoutParams(dp(150), dp(150)));

        TextView title = new TextView(this);
        title.setText("Before you scroll");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(22), 0, 0);
        column.addView(title);

        TextView body = new TextView(this);
        body.setText("UU Labs takes no responsibility for the content shown in this "
                + "application. It is completely random and unfiltered, and may depict "
                + "adult or otherwise upsetting material.\n\n"
                + "You control which languages and sources are used in settings. "
                + "Nothing about you is stored, and no algorithm is built on you.");
        body.setTextColor(Color.parseColor("#BBBBBB"));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        body.setLineSpacing(dp(5), 1f);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(16), 0, 0);
        column.addView(body);

        TextView accept = new TextView(this);
        accept.setText("I understand");
        accept.setTextColor(Color.parseColor("#04121B"));
        accept.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        accept.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.parseColor("#66CCFF"));
        pill.setCornerRadius(dp(14));
        accept.setBackground(pill);
        accept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setDisclaimerAccepted(Main.this, true);
                root.removeView(gate);
                if (active != null && active.player != null && !paused) {
                    active.player.play();
                }
            }
        });
        LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        acceptParams.topMargin = dp(30);
        column.addView(accept, acceptParams);

        root.addView(gate, match());
        if (active != null && active.player != null) {
            active.player.pause();
        }
    }

    private void startLoadingWatch() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loadingView != null) {
                    boolean blank = screenIsBlank();
                    if (blank != (loadingView.getVisibility() == View.VISIBLE)) {
                        setLoading(blank);
                    }
                }
                handler.postDelayed(this, 900);
            }
        }, 900);
    }

    private void setLoading(boolean show) {
        if (loadingView == null) {
            return;
        }
        if (show) {
            try {
                java.io.FileOutputStream f = new java.io.FileOutputStream(
                        new java.io.File(getFilesDir(), "loading.log"), true);
                f.write("SHOW\n".getBytes("UTF-8"));
                f.close();
            } catch (Exception ignored) {
            }
        }
        loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            status.bringToFront();
            if (settingsButton != null) {
                settingsButton.bringToFront();
            }
            if (shareButton != null) {
                shareButton.bringToFront();
            }
        }
    }

    private static String stateOf(Slot s) {
        if (s == null) {
            return "-";
        }
        if (s.player == null) {
            return "page";
        }
        switch (s.player.getPlaybackState()) {
            case Player.STATE_IDLE:
                return "idle";
            case Player.STATE_BUFFERING:
                return "buf";
            case Player.STATE_READY:
                return "ready";
            case Player.STATE_ENDED:
                return "end";
            default:
                return "?";
        }
    }

    private FrameLayout circleButton(View inner, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(this);
        f.setOnClickListener(click);
        if (inner != null) {
            f.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return f;
    }

    private TextView glyph(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        return t;
    }

    private FrameLayout.LayoutParams cornerParams(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(46), dp(46));
        p.gravity = gravity;
        p.topMargin = dp(12);
        p.leftMargin = dp(14);
        return p;
    }

    /**
     * Settings sits on top and share directly beneath it, both in the top left.
     * Every provider player puts its logo top right and its action buttons down
     * the right edge, so that corner is the one place nothing overlaps.
     */
    private void placeButtons(int topInset) {
        if (settingsButton != null) {
            FrameLayout.LayoutParams p = cornerParams(Gravity.TOP | Gravity.LEFT);
            p.topMargin = topInset + dp(12);
            settingsButton.setLayoutParams(p);
        }
        if (shareButton != null) {
            FrameLayout.LayoutParams p = cornerParams(Gravity.TOP | Gravity.LEFT);
            p.topMargin = topInset + dp(12) + dp(56);
            shareButton.setLayoutParams(p);
        }
    }

    /** Edge to edge drawing means the buttons must clear the system bars. */
    private void applyInsets() {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int bottom;
                int top;
                if (Build.VERSION.SDK_INT >= 30) {
                    bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                } else {
                    bottom = insets.getSystemWindowInsetBottom();
                    top = insets.getSystemWindowInsetTop();
                }
                placeButtons(top);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void fill() {
        if (filling) {
            return;
        }
        filling = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int attempts = 0;
                    while (true) {
                        synchronized (ready) {
                            if (ready.size() >= PREFETCH) {
                                break;
                            }
                        }
                        if (++attempts > 10) {
                            break;   // give up for now rather than spin forever
                        }
                        Ready r = produce();
                        if (r == null) {
                            continue;   // this subject was refused; try another
                        }
                        synchronized (ready) {
                            ready.addLast(r);
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                pump();
                            }
                        });
                    }
                } finally {
                    filling = false;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pump();
                    }
                });
            }
        });
    }

    private Ready produce() {
        Set<String> sources = Prefs.sources(Main.this);
        if (sources.isEmpty()) {
            return null;
        }
        String source = pickSource(sources);
        String word = pickWord();
        List<Sources.Item> candidates;
        try {
            candidates = Sources.fetch(source, word);
        } catch (Exception e) {
            return null;
        }
        lastSource = source;
        List<Sources.Item> filtered = filter(candidates);
        for (Sources.Item item : filtered) {
            String url = resolve(item);
            if (url != null) {
                if (!rememberIfNew(item)) {
                    continue;   // already queued or played by another pass
                }
                return new Ready(item, url, isPage(item), lastLandscape);
            }
            remember(item.toString());
        }
        return null;
    }

    /** Every source must yield a direct stream; anything else is dropped. */
    private String resolve(Sources.Item item) {
        if ("ytprobe".equals(item.kind)) {
            if (ytResolver == null) {
                return null;
            }
            YoutubeResolver.Resolved r = ytResolver.resolveBlocking(item.id, 45000);
            if (r == null) {
                return null;
            }
            lastLandscape = r.width > 0 && r.height > 0 && r.height < r.width;
            if (lastLandscape && !Prefs.landscapeAllowed(this)) {
                return null;
            }
            // The resolver reports the real title, so the language choice can
            // reject a video that is plainly in another language.
            String spoken = Vocab.detect(ytResolver.lastTitle());
            if (spoken != null && !Prefs.languages(this).contains(spoken)) {
                return null;
            }
            // Different videos from one creator is what makes a random feed
            // feel repetitive, so a channel is rested before it appears again.
            String channel = ytResolver.lastChannel();
            if (!channel.isEmpty()) {
                synchronized (recentChannels) {
                    if (recentChannels.contains(channel)) {
                        return null;
                    }
                    recentChannels.addFirst(channel);
                    while (recentChannels.size() > 8) {
                        recentChannels.removeLast();
                    }
                }
            }
            return r.url;
        }
        if ("frame".equals(item.kind)) {
            // Played by the provider's own embeddable player.
            lastLandscape = false;
            return item.url;
        }
        if ("mp4".equals(item.kind)) {
            lastLandscape = !item.portraitOk();
            if (lastLandscape && !Prefs.landscapeAllowed(this)) {
                return null;
            }
            return item.url;
        }
        return null;
    }

    /** YouTube plays in its own page; a bare stream URL is refused with 403. */
    private boolean isPage(Sources.Item item) {
        // A provider's embeddable player is the only route for TikTok, which
        // signs its stream URLs. Everything else plays a resolved stream.
        return "frame".equals(item.kind);
    }

    private String resolveYouTube(final String videoId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        handler.post(new Runnable() {
            @Override
            public void run() {
                startProbe(videoId, new Probe() {
                    @Override
                    public void onStream(String url) {
                        out[0] = url;
                        latch.countDown();
                    }
                });
            }
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private interface Probe {
        void onStream(String url);
    }

    private void startProbe(final String videoId, final Probe cb) {
        final WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        view.evaluateJavascript(ytStartJs(videoId), null);
                        poll(view, 0, cb);
                    }
                }, 1000);
            }
        });
        root.addView(w, new FrameLayout.LayoutParams(dp(2), dp(2)));
        if (probe != null) {
            root.removeView(probe);
            probe.destroy();
        }
        probe = w;
        w.loadUrl("https://www.youtube.com/watch?v=" + videoId);
    }

    private void poll(final WebView view, final int attempt, final Probe cb) {
        view.evaluateJavascript("(window.__uu||'')", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                String value = unquote(raw);
                if (value.startsWith("OK|")) {
                    String[] parts = value.split("\\|");
                    dropProbe();
                    cb.onStream(parts.length > 1 ? parts[1] : null);
                    return;
                }
                if (value.startsWith("NOMUX") || value.startsWith("ERR")) {
                    dropProbe();
                    cb.onStream(null);
                    return;
                }
                if (attempt < 20) {
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            poll(view, attempt + 1, cb);
                        }
                    }, 700);
                } else {
                    dropProbe();
                    cb.onStream(null);
                }
            }
        });
    }

    /**
     * Some video ids are deleted, private or region-locked, and the provider
     * answers with its own "Video currently unavailable" card. That card never
     * goes away, so the slot is dropped the moment it appears instead of
     * holding the feed until the dwell timer expires.
     */
    /**
     * Decides what a page-hosted player is doing. A provider player gives no
     * completion event, so the video element itself is read: finished clips
     * advance at once, an "unavailable" card is dropped, and a player that
     * never starts is abandoned.
     */
    private static final String WATCH_JS =
            "(function(){try{"
                    + "var b=(document.body&&document.body.innerText)||'';"
                    + "var t=b.toLowerCase();"
                    + "if(t.indexOf('currently unavailable')>=0"
                    + "||t.indexOf('video unavailable')>=0)return 'GONE';"
                    // The playhead is the only signal that cannot lie about a
                    // provider player. When it stops moving, or the element sits
                    // paused, the clip is over - however the page chose to say so.
                    + "var v=document.querySelector('video');"
                    + "if(!v)return 'NOSTREAM';"
                    + "var now=Date.now();"
                    + "var w=window.__uuWatch=window.__uuWatch||{t:-1,at:now,pausedAt:0};"
                    + "if(v.ended)return 'END';"
                    + "if(v.paused){"
                    + "if(!w.pausedAt)w.pausedAt=now;"
                    + "if(now-w.pausedAt>3500)return 'END';"
                    + "}else{w.pausedAt=0;}"
                    + "if(v.currentTime!==w.t){w.t=v.currentTime;w.at=now;}"
                    + "else if(now-w.at>4500)return 'END';"
                    + "return '';}catch(e){return '';}})()";

    private void watchForUnavailable(final WebView view, final int attempt) {
        if (attempt > 600) {
            return;
        }
        view.evaluateJavascript(WATCH_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                boolean gone = value != null && value.contains("GONE");
                boolean ended = value != null && value.contains("END");
                // A player whose playhead cannot be seen gets a bounded grace
                // period before the feed moves on rather than sitting on it.
                boolean blind = value != null && value.contains("NOSTREAM");
                int next = blind ? attempt + 1 : 0;
                boolean giveUp = gone || ended || (blind && next >= 8);
                boolean isActive = active != null && active.web == view;
                if (isActive && !gone && !ended && !blind) {
                    active.rendered = true;   // something is genuinely on screen
                    setLoading(false);
                }
                if (giveUp && isActive) {
                    next();
                    return;
                }
                // Otherwise keep watching. The slot is usually still pending
                // when the page finishes loading, so a check that gave up here
                // would leave the clip unwatched once it came on screen.
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        watchForUnavailable(view, next);
                    }
                }, 1100);
            }
        });
    }

    private void dropProbe() {
        WebView w = probe;
        probe = null;
        if (w != null) {
            root.removeView(w);
            w.destroy();
        }
    }

    private String unquote(String raw) {
        if (raw == null || "null".equals(raw) || raw.length() < 2) {
            return "";
        }
        String s = raw;
        if (s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Runs inside the video's own page: asks YouTube's player endpoint for the
     * streams and returns the one muxed MP4. Ads are separate streams injected
     * by the player, so playing the media directly has none.
     */
    /**
     * Hides the page's social furniture by its own component names, leaving the
     * player and the video completely alone.
     *
     * The outer overlay renderer is NOT hidden: it also holds the video, and
     * hiding it produces a black screen. Only the inner pieces are taken out -
     * the channel bar with its subscribe button, the metapanel, the title, and
     * the action strip that carries like, comment and share.
     */
    private static final String CHROME_CSS =
            "(function(){if(window.__uuH){return;}window.__uuH=1;"
            + "var st=document.createElement('style');"
            + "st.textContent='"
            + ".ytReelPlayerOverlayViewModelMetadataContainer,"
            + ".ytReelPlayerOverlayViewModelHorizontalContainer,"
            + ".ytReelPlayerOverlayViewModelMetadataContainerMetapanel,"
            + "yt-reel-metapanel-view-model,"
            + "yt-reel-channel-bar-view-model,"
            + "yt-shorts-video-title-view-model,"
            + "[class*=\'ReelActionBar\'],[class*=\'ReelPlayerActionBar\'],"
            + "[class*=\'OverlayViewModelActionContainer\'],"
            + ".ytp-unmute,.ytp-unmute-button"
            + "{display:none!important}';"
            + "document.head.appendChild(st);"
            + "var tries=0;var t=setInterval(function(){tries++;"
            + "var u=document.querySelector('.ytp-unmute,.ytp-unmute-button');"
            + "if(u){u.click();clearInterval(t);}"
            + "if(tries>12){clearInterval(t);}},600);})()";

    /** Layout census of the player's overlay, for deciding what to hide. */
    private static final String DOM_JS =
            "(function(){var out=[];try{var els=document.body.getElementsByTagName('*');"
            + "for(var i=0;i<els.length&&out.length<140;i++){var el=els[i];"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width<8||r.height<8)continue;"
            + "if(r.top>window.innerHeight||r.left>window.innerWidth)continue;"
            + "var cls='';try{cls=(el.className||'').toString();}catch(e){}"
            + "var tag=el.tagName.toLowerCase();"
            + "var hit=/reel|shorts|overlay|action|subscri|engagement|chip|button|like|comment|share/i"
            + ".test(cls+' '+tag);"
            + "if(!hit)continue;"
            + "out.push(tag+' | '+cls.slice(0,80)+' | '+Math.round(r.left)+','+Math.round(r.top)"
            + "+' '+Math.round(r.width)+'x'+Math.round(r.height));}}catch(e){out.push('ERR '+e);}"
            + "return out.join('\\n');})()";

    private static String ytStartJs(String videoId) {
        return "(function(){try{"
                + "var c=(window.ytcfg&&window.ytcfg.data_)?window.ytcfg.data_:null;"
                + "if(!c){window.__uu='ERR no-ytcfg';return;}"
                + "var key=c.INNERTUBE_API_KEY,ctx=c.INNERTUBE_CONTEXT;"
                + "if(!key||!ctx){window.__uu='ERR no-context';return;}"
                + "var body=JSON.stringify({context:ctx,videoId:'" + videoId + "'});"
                + "fetch('/youtubei/v1/player?key='+key,{method:'POST',"
                + "headers:{'Content-Type':'application/json'},body:body})"
                + ".then(function(r){return r.json();})"
                + ".then(function(j){"
                + "var sd=j.streamingData||{};"
                + "var f=(sd.formats||[]).concat(sd.adaptiveFormats||[]);"
                + "var best=null;"
                + "for(var i=0;i<f.length;i++){var x=f[i];if(!x.url)continue;"
                + "if((x.mimeType||'').indexOf('video/mp4')!==0)continue;"
                + "if(!x.audioQuality)continue;"
                + "if(!best||(x.height||0)>(best.height||0))best=x;}"
                + "window.__uu=best?('OK|'+best.url):'NOMUX '+f.length;"
                + "})"
                + ".catch(function(e){window.__uu='ERR '+e;});"
                + "}catch(e){window.__uu='ERRX '+e;}})()";
    }

    private String pickSource(Set<String> enabled) {
        List<String> options = new ArrayList<String>();
        for (String s : Sources.ALL) {
            if (enabled.contains(s) && !s.equals(lastSource)) {
                options.add(s);
            }
        }
        if (options.isEmpty()) {
            options.addAll(enabled);
        }
        return options.get(rand.nextInt(options.size()));
    }

    /**
     * Draws a subject from a shuffled bag rather than independently at random.
     *
     * Independent draws repeat: with sixty-odd words a repeat is likely within a dozen picks, and
     * a repeated word returns the same search results, so the feed repeats with it. A bag spends
     * every word once before any comes back, and it is stored, so quitting the app does not hand
     * the same word out again.
     */
    private String drawFromBag(Vocab.Lang lang) {
        java.util.List<String> bag = new ArrayList<String>();
        String stored = Prefs.wordBag(this, lang.code);
        if (stored != null && !stored.isEmpty()) {
            for (String w : stored.split("\n")) {
                if (!w.isEmpty()) {
                    bag.add(w);
                }
            }
        }
        if (bag.isEmpty()) {
            for (String w : lang.words) {
                bag.add(w);
            }
            Collections.shuffle(bag, rand);
        }

        String word = bag.remove(bag.size() - 1);
        StringBuilder b = new StringBuilder();
        for (String w : bag) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(w);
        }
        Prefs.setWordBag(this, lang.code, b.toString());

        synchronized (recentWords) {
            recentWords.addFirst(word);
            while (recentWords.size() > 12) {
                recentWords.removeLast();
            }
        }
        return word;
    }

    private String pickWord() {
        Set<String> langs = Prefs.languages(this);
        List<Vocab.Lang> pool = new ArrayList<Vocab.Lang>();
        for (String code : langs) {
            Vocab.Lang l = Vocab.byCode(code);
            if (l != null) {
                pool.add(l);
            }
        }
        if (pool.isEmpty()) {
            pool.add(Vocab.byCode("en"));
        }
        Vocab.Lang chosen = pool.get(rand.nextInt(pool.size()));
        return drawFromBag(chosen);
    }

    private boolean rememberIfNew(Sources.Item item) {
        synchronized (seen) {
            if (seen.contains(item.toString())) {
                return false;
            }
        }
        remember(item.toString());
        return true;
    }

    private List<Sources.Item> filter(List<Sources.Item> in) {
        Set<String> langs = Prefs.languages(this);
        Set<String> sources = Prefs.sources(this);
        List<Sources.Item> out = new ArrayList<Sources.Item>();
        for (Sources.Item it : in) {
            if (!sources.contains(it.source)
                    || (!it.portraitOk() && !Prefs.landscapeAllowed(this))) {
                continue;
            }
            synchronized (seen) {
                if (seen.contains(it.toString())) {
                    continue;
                }
            }
            String detected = Vocab.detect(it.title);
            if (detected != null && !langs.contains(detected)) {
                continue;
            }
            out.add(it);
        }
        Collections.shuffle(out, rand);
        return out;
    }

    private void pump() {
        if (pending != null) {
            return;
        }
        Ready r;
        synchronized (ready) {
            r = ready.pollFirst();
        }
        if (r == null) {
            fill();
            return;
        }
        Slot slot = new Slot();
        slot.item = r.item;
        slot.url = r.url;
        if (r.page) {
            WebView w = new WebView(this);
            WebSettings st = w.getSettings();
            st.setJavaScriptEnabled(true);
            st.setDomStorageEnabled(true);
            st.setMediaPlaybackRequiresUserGesture(false);
            st.setUserAgentString("Mozilla/5.0 (Linux; Android 15; Pixel 8) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/155.0.0.0 Mobile Safari/537.36");
            w.setBackgroundColor(Color.BLACK);
            w.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public void onPermissionRequest(
                        android.webkit.PermissionRequest request) {
                    request.deny();
                }
            });
            w.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return true; // the page may never navigate away by itself
                }

                @Override
                public void onPageFinished(final WebView view, String url) {
                    watchForUnavailable(view, 0);
                    // The feed shows a video and two of our buttons, nothing else.
                    // Their page draws its own overlay on every Short, so it is
                    // hidden as it loads.
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            view.evaluateJavascript(DOM_JS, new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    try {
                                        java.io.FileOutputStream f = new java.io.FileOutputStream(
                                                new java.io.File(getFilesDir(), "page-dom.txt"));
                                        f.write(unquote(value).replace("\\n", "\n")
                                                .getBytes("UTF-8"));
                                        f.close();
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                        }
                    }, 4000);
                    for (int delay : new int[]{300, 1500, 3500}) {
                        view.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                view.evaluateJavascript(CHROME_CSS, null);
                            }
                        }, delay);
                    }
                }

                @Override
                public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                    // A Short that is not really a Short is redirected to the
                    // watch page, which brings comments, a subscribe button and
                    // the player chrome. Drop it instead of showing that.
                    if (url != null && url.contains("/watch")) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                next();
                            }
                        });
                    }
                }
            });
            w.loadUrl(r.url);
            slot.web = w;
            slot.url = r.url;
            slot.rootView = w;
            slot.rootView.setVisibility(View.GONE);
            stage.addView(slot.rootView, match());
            pending = slot;
            fill();
            if (active == null || wantNext) {
                wantNext = false;
                next();
            }
            return;
        }
        final Slot created = slot;
        androidx.media3.datasource.DefaultHttpDataSource.Factory http =
                new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/131.0.0.0 Safari/537.36")
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(15000);
        // YouTube's stream host validates the session and referrer that produced
        // the URL. Sending those to another provider's CDN is what made X
        // answer 403, so they are attached only for YouTube streams.
        String host = r.url == null ? "" : r.url;
        boolean youtubeStream = host.contains("googlevideo.com")
                || host.contains("youtube.com");
        if (youtubeStream) {
            java.util.Map<String, String> extra = new java.util.HashMap<String, String>();
            String cookie = android.webkit.CookieManager.getInstance()
                    .getCookie("https://www.youtube.com");
            if (cookie != null && !cookie.isEmpty()) {
                extra.put("Cookie", cookie);
            }
            extra.put("Referer", "https://www.youtube.com/");
            extra.put("Origin", "https://www.youtube.com");
            http.setDefaultRequestProperties(extra);
        }
        slot.player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                        new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                                new androidx.media3.datasource.DefaultDataSource.Factory(
                                        this, http)))
                .build();
        slot.player.setRepeatMode(Player.REPEAT_MODE_OFF);
        slot.player.setVolume(0f);
        slot.view = new PlayerView(this);
        slot.view.setUseController(false);
        slot.view.setResizeMode(r.landscape
                ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        slot.view.setPlayer(slot.player);
        slot.rootView = slot.view;
        slot.rootView.setVisibility(View.GONE);
        stage.addView(slot.rootView, match());
        slot.player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (active != created) {
                    return;
                }
                // Any buffering shows the face, not only the wait before a clip's first frame:
                // a stall halfway through a video looked like a frozen picture.
                if (playbackState == Player.STATE_BUFFERING && active == created) {
                    setLoading(true);
                }
                if (playbackState == Player.STATE_BUFFERING && active == created
                        && !created.rendered) {
                    setLoading(true);
                }
                if (playbackState == Player.STATE_READY && !paused) {
                    created.rendered = true;
                    setLoading(false);
                    created.player.play();
                } else if (playbackState == Player.STATE_ENDED) {
                    // The feed never stops: a finished clip rolls straight on to
                    // the next one, which is already buffered behind it.
                    next();
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                setStatus("skipped: " + e.getErrorCodeName());
                if (pending == created) {
                    pending = null;
                    created.player.release();
                    stage.removeView(created.rootView);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            pump();
                        }
                    });
                } else if (active == created) {
                    next();
                }
            }
        });
        slot.player.setMediaItem(MediaItem.fromUri(r.url));
        slot.player.prepare();
        pending = slot;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pending == created
                        && created.player.getPlaybackState() != Player.STATE_READY) {
                    setStatus("skipped: not ready");
                    pending = null;
                    created.player.release();
                    stage.removeView(created.rootView);
                    pump();
                }
            }
        }, 25000);
        fill();
        if (active == null || wantNext) {
            wantNext = false;
            next();
        }
    }

    private void next() {
        if (pending == null) {
            refreshLoading();
            // Nothing buffered: remember the swipe and swap as soon as a slot is
            // ready, so a fast scroll never appears to do nothing.
            wantNext = true;
            pump();
            return;
        }
        wantNext = false;
        Slot old = active;
        active = pending;
        pending = null;
        current = active.item;
        remember(current.toString());
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "play.log"), true);
            f.write((current.source + " " + current.id + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
        if (old != null) {
            history.push(new Ready(old.item, old.url, false, false));
            if (history.size() > 20) {
                history.removeLast();
            }
            if (old.player != null) {
                old.player.stop();
                old.player.release();
            }
            if (old.web != null) {
                old.web.destroy();
            }
            stage.removeView(old.rootView);
        }
        active.rootView.setVisibility(View.VISIBLE);
        if (active.player != null
                && active.player.getPlaybackState() == Player.STATE_READY) {
            active.rendered = true;
        }
        if (active.web != null) {
            // The page paints its own surface; the face is only for a feed with
            // nothing on screen at all.
            setLoading(false);
            // A provider player reports nothing when a clip finishes - and an
            // advert can hold it indefinitely - so a page slot is capped and
            // the feed moves on.
            final Slot shown = active;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (active == shown) {
                        next();
                    }
                }
            }, PAGE_DWELL_MILLIS);
        }
        if (active.player != null) {
            active.player.setVolume(1f);
            active.player.play();
        }
        paused = false;
        setStatus(null);
        handler.post(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        });
    }

    private void previous() {
        Ready back;
        synchronized (history) {
            back = history.pollFirst();
        }
        if (back == null) {
            return;
        }
        // The stored entry already carries its stream, so going back costs
        // nothing: the discarded slot is dropped and the previous one is
        // prepared and swapped in exactly like a forward move.
        if (pending != null) {
            // A page slot holds a WebView, not a player.
            if (pending.player != null) {
                pending.player.release();
            }
            if (pending.web != null) {
                pending.web.destroy();
            }
            stage.removeView(pending.rootView);
            pending = null;
        }
        synchronized (ready) {
            ready.addFirst(back);
        }
        wantNext = true;
        pump();
    }

    private void togglePause() {
        if (active == null) {
            return;
        }
        if (active.player == null) {
            return;
        }
        if (active.player.isPlaying()) {
            active.player.pause();
            paused = true;
        } else {
            active.player.play();
            paused = false;
        }
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text == null ? "" : text);
        }
    }

    private void share() {
        if (current == null) {
            return;
        }
        String link;
        if (Sources.YOUTUBE.equals(current.source)) {
            link = "https://youtu.be/" + current.id;
        } else if (Sources.TIKTOK.equals(current.source)) {
            link = "https://www.tiktok.com/@x/video/" + current.id;
        } else if (Sources.X.equals(current.source)) {
            link = "https://x.com/i/status/" + current.id;
        } else {
            link = current.url;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, link);
        startActivity(Intent.createChooser(send, "Share to UU Downloader"));
    }

    private void toggleSettings() {
        if (settingsPanel != null) {
            root.removeView(settingsPanel);
            settingsPanel = null;
            if (active != null && active.player != null && !paused) {
                active.player.play();
            }
            return;
        }
        if (active != null && active.player != null) {
            active.player.pause();
        }
        settingsPanel = buildSettings();
        root.addView(settingsPanel, match());
    }

    private View buildSettings() {
        return SettingsUi.build(this, new Runnable() {
            @Override
            public void run() {
                toggleSettings();
            }
        });
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#66CCFF"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    @Override
    public void onBackPressed() {
        if (settingsPanel != null) {
            toggleSettings();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (active != null && active.player != null) {
            active.player.pause();
        }
        // Leaving the app is the natural point to write down what has been shown.
        flushSeen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (active != null && active.player != null) {
            active.player.release();
        }
        if (pending != null && pending.player != null) {
            pending.player.release();
        }
        dropProbe();
        if (ytResolver != null) {
            ytResolver.destroy();
            ytResolver = null;
        }
    }
}
