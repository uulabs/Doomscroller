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
    /** True while the opening notice is up: nothing may play behind it. */
    private boolean gateOpen;
    private LinearLayout controlsBar;
    private TextView controlsToggle;
    private TextView controlsTime;
    private android.widget.SeekBar controlsSeek;
    private boolean seeking;
    private long lastActivePing;
    /** +1 for a forward swap, -1 when going back, so motion matches the swipe. */
    private float swapDirection = 1f;
    private boolean lastLandscape;
    private GestureDetector gestures;
    private WebView probe;
    private YoutubeResolver ytResolver;
    private InstagramResolver igResolver;

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
        long shownAt;
        boolean rendered;
        boolean sizeKnown;     // the decoded stream has reported its geometry
        float displayRatio;    // width * pixel ratio / height
        boolean rejected;      // refused admission, so never history
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
                stage.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CLOCK_TICK);
                if (dy < 0) {
                    next();
                } else {
                    previous();
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (settingsPanel == null && Prefs.controlsEnabled(Main.this)) {
                    // The bar carries the play control, so a tap only brings it
                    // up rather than also pausing what you were watching.
                    showControls();
                    return true;
                }
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
        Updater.check(this);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            showControls();
        }
        if (gestures != null) {
            gestures.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void buildUi() {
        Vocab.load(getAssets());
        applyOrientation();
        Sources.setAllowLandscape(Prefs.landscapeAllowed(this));
        ytResolver = new YoutubeResolver(this);
        igResolver = new InstagramResolver(this);
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
        controlsBar = new LinearLayout(this);
        controlsBar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable barBackground = new GradientDrawable();
        barBackground.setColor(0xE617171A);
        barBackground.setCornerRadius(dp(20));
        controlsBar.setBackground(barBackground);
        controlsBar.setPadding(dp(18), dp(10), dp(18), dp(10));

        LinearLayout barTop = new LinearLayout(this);
        barTop.setOrientation(LinearLayout.HORIZONTAL);
        barTop.setGravity(Gravity.CENTER_VERTICAL);

        controlsToggle = new TextView(this);
        controlsToggle.setText("\u25B6");
        controlsToggle.setTextColor(Color.WHITE);
        controlsToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        controlsToggle.setPadding(0, 0, dp(16), 0);
        controlsToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePause();
                updateControls();
            }
        });
        barTop.addView(controlsToggle);

        controlsTime = new TextView(this);
        controlsTime.setTextColor(Color.parseColor("#BBBBBB"));
        controlsTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        barTop.addView(controlsTime);
        controlsBar.addView(barTop);

        controlsSeek = new android.widget.SeekBar(this);
        controlsSeek.setMax(1);
        controlsSeek.setPadding(0, dp(2), 0, 0);
        if (controlsSeek.getProgressDrawable() != null) {
            controlsSeek.getProgressDrawable().setTint(0xFF66CCFF);
        }
        if (controlsSeek.getThumb() != null) {
            controlsSeek.getThumb().setTint(0xFF66CCFF);
        }
        controlsSeek.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(android.widget.SeekBar seekBar,
                                                  int progress, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        if (active == null) {
                            return;
                        }
                        if (active.player != null) {
                            active.player.seekTo(progress * 1000L);
                            updateTimeLabel();
                        } else if (active.web != null) {
                            final String seek = "(function(){try{var v=document.querySelector"
                                    + "('video');if(v)v.currentTime=" + progress
                                    + ";}catch(e){}})()";
                            active.web.evaluateJavascript(seek, null);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                        seeking = true;
                        handler.removeCallbacks(hideControls);
                    }

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                        seeking = false;
                        handler.postDelayed(hideControls, 2600);
                    }
                });
        controlsBar.addView(controlsSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        controlsBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM;
        barParams.leftMargin = dp(20);
        barParams.rightMargin = dp(20);
        barParams.bottomMargin = dp(44);
        root.addView(controlsBar, barParams);
        // Keep the bar above the navigation bar rather than underneath it.
        final FrameLayout.LayoutParams liftParams = barParams;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v,
                                                                 android.view.WindowInsets insets) {
                int bottom;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    bottom = insets.getInsets(
                            android.view.WindowInsets.Type.navigationBars()).bottom;
                } else {
                    bottom = insets.getSystemWindowInsetBottom();
                }
                liftParams.bottomMargin = dp(44) + bottom;
                if (controlsBar != null) {
                    controlsBar.setLayoutParams(liftParams);
                }
                return insets;
            }
        });
        root.requestApplyInsets();

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
    /**
     * Hides the face once something is on screen. It deliberately never shows
     * it: appearing is left to the watch, which waits out a short blank first.
     */
    private void refreshLoading() {
        if (!screenIsBlank()) {
            setLoading(false);
        }
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
        // Once a clip has put a frame on screen it is never covered again, even
        // if it stutters back into buffering. Otherwise a player that re-buffers
        // would flash the face over a video that is plainly playing.
        if (active.rootView != null
                && Math.abs(active.rootView.getTranslationY()) > 1f) {
            return true;   // the clip has left the screen and nothing replaced it
        }
        if (active.rendered) {
            return false;
        }
        if (active.player != null) {
            int state = active.player.getPlaybackState();
            return state != Player.STATE_READY && state != Player.STATE_ENDED;
        }
        return true;
    }

    /** A steady tick keeps the face in step with what is actually visible. */
    /**
     * Shown once, before anything random can appear. The feed is unfiltered by
     * design, so the person has to be told that before they see it.
     */
    private void showDisclaimerIfNeeded() {
        if (settingsPanel != null) {
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
                gateOpen = false;
                if (active != null && active.web != null) {
                    active.web.evaluateJavascript(JS_ACTIVE, null);
                }
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
        // The notice is a gate, not a banner: playback waits for it.
        gateOpen = true;
        if (active != null && active.player != null) {
            active.player.pause();
        }
        if (active != null && active.web != null) {
            active.web.evaluateJavascript(JS_IDLE, null);
        }
    }

    private void startLoadingWatch() {
        handler.postDelayed(new Runnable() {
            long blankSince;

            @Override
            public void run() {
                if (loadingView != null) {
                    boolean blank = screenIsBlank();
                    long now = System.currentTimeMillis();
                    if (!blank) {
                        blankSince = 0;
                    } else if (blankSince == 0) {
                        blankSince = now;
                    }
                    if (active != null && active.web != null && !gateOpen
                            && now - lastActivePing > 1200) {
                        lastActivePing = now;
                        active.web.evaluateJavascript(JS_ACTIVE, null);

                    }
                    boolean settled = blank && blankSince != 0
                            && now - blankSince > 700;
                    if (settled != (loadingView.getVisibility() == View.VISIBLE)) {
                        setLoading(settled);
                    }
                }
                handler.postDelayed(this, 400);
            }
        }, 400);
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

    /** Debug only: a known 1280x720 stream, whatever the sources say. */
    private Ready landscapeProbe() {
        String url = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/"
                + "Big_Buck_Bunny_360_10s_1MB.mp4";
        Sources.Item item = new Sources.Item("Probe", "landscape", "probe",
                "mp4", url, 640, 360);
        return new Ready(item, url, false, true);
    }

    private Ready produce() {
        if (BuildConfig.DEBUG && Prefs.probeLandscape(this)) {
            return landscapeProbe();
        }
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
        if ("fbprobe".equals(item.kind)) {
            String media = Sources.facebookMedia(item.id);
            igTrace("fb " + item.id + " -> " + (media == null ? "NOMEDIA" : "ok"));
            return media;
        }
        if ("igprobe".equals(item.kind)) {
            if (igResolver == null || !isFinishing()) {
                String media = igResolver.resolveBlocking(item.id, 20000);
                igTrace(item.id + " -> " + (media == null ? "NOURL" :
                        media.substring(0, Math.min(70, media.length()))));
                return media;
            }
            return null;
        }
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
                    + "if(t.indexOf('more videos on tiktok')>=0)return 'END';"
                    // A reel that has been deleted since the index saw it says so.
                    + "if(t.indexOf('may be broken')>=0"
                    + "||t.indexOf('post may have been removed')>=0)return 'GONE';"
                    + "var v=document.querySelector('video');"
                    + "if(!v)return 'NOSTREAM';"
                    + "if(window.__uuActive&&v.muted){v.muted=false;}"
                    + "if(window.__uuActive&&v.paused&&!v.ended"
                    + "&&v.currentTime<0.6){try{v.play();}catch(e){}}"
                    + "if(v.ended)return 'END';"
                    // A stalled playhead is only an ending when the element is
                    // not waiting for data. Buffering looks identical otherwise,
                    // which is what cut clips off a few seconds in.
                    + "var now=Date.now();"
                    + "var w=window.__uuWatch=window.__uuWatch||{t:-1,at:now,pausedAt:0};"
                    + "var buffering=v.readyState<3;"
                    + "if(v.currentTime!==w.t){w.t=v.currentTime;w.at=now;}"
                    + "if(v.paused){"
                    + "if(!w.pausedAt)w.pausedAt=now;"
                    + "if(!buffering&&now-w.pausedAt>4000)return 'END';"
                    + "}else{w.pausedAt=0;}"
                    + "var stalled=now-w.at;"
                    + "if(!buffering&&stalled>4000)return 'END';"
                    + "if(buffering&&stalled>20000)return 'END';"
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
                final boolean isActive = active != null && active.web == view;
                final Slot watched = isActive ? active
                        : (pending != null && pending.web == view ? pending : null);
                if (watched != null && !Prefs.landscapeAllowed(Main.this)) {
                    view.evaluateJavascript(PAGE_SIZE_JS, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String size) {
                            String clean = size == null ? "?" : size.replace("\"", "");
                            int bar = clean.indexOf('x');
                            if (bar <= 0) {
                                return;      // still unknown, keep waiting
                            }
                            int w;
                            int h;
                            try {
                                w = Integer.parseInt(clean.substring(0, bar).trim());
                                h = Integer.parseInt(clean.substring(bar + 1).trim());
                            } catch (NumberFormatException e) {
                                return;
                            }
                            if (w <= 0 || h <= 0) {
                                return;
                            }
                            watched.sizeKnown = true;
                            watched.displayRatio = (float) w / (float) h;
                            igTrace("page size " + w + "x" + h + " src=" + watched.item.source);
                            if (watched.displayRatio > 1.0f) {
                                if (watched == pending) {
                                    rejectSlot(watched);
                                } else {
                                    disposeActiveAndAdvance(watched);
                                }
                            } else if (watched == pending) {
                                maybePromotePending();
                            }
                        }
                    });
                }
                if (isActive && !gone && !ended && !blind) {
                    active.rendered = true;   // something is genuinely on screen
                    setLoading(false);
                }
                if (giveUp && isActive) {
                    // A page can carry its end-of-clip text as it loads, which
                    // skipped the clip the moment it appeared. It has to have
                    // been on screen for a moment before that is believed.
                    if (System.currentTimeMillis() - active.shownAt < 1500) {
                        view.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                watchForUnavailable(view, 0);
                            }
                        }, 900);
                        return;
                    }
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
    /**
     * Restyles Instagram's embed. Hiding the whole post around the clip was
     * tried and stops it rendering, so this only removes the obvious chrome and
     * leaves the player's own boxes alone.
     */
    private static final String INSTAGRAM_STYLE_JS =
            "(function(){try{var s=document.createElement('style');"
                    + "s.textContent='header,footer,nav{display:none!important}'"
                    + "+'html,body{background:#000!important;margin:0!important}';"
                    + "document.head.appendChild(s);return '1';}catch(e){return '0';}})()";

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
     * Independent draws repeat: even over a large list, a repeat is likely
     * within a session, and a repeated word returns the same search results, so
     * the feed repeats with it. A bag spends every subject once before any comes
     * back, and it is stored, so quitting the app does not hand out the same
     * word again either.
     */
    private String pickWord() {
        Set<String> langs = Prefs.languages(this);
        List<String> pool = Vocab.pool(langs);
        if (pool.isEmpty()) {
            Set<String> fallback = new LinkedHashSet<String>();
            fallback.add("en");
            pool = Vocab.pool(fallback);
        }
        if (pool.isEmpty()) {
            return "cake";
        }
        Set<String> allowed = new HashSet<String>(pool);

        List<String> bag = new ArrayList<String>();
        String stored = Prefs.wordBag(this, "all");
        if (stored != null && !stored.isEmpty()) {
            for (String w : stored.split("\n")) {
                // Drop anything belonging to a language since switched off.
                if (!w.isEmpty() && allowed.contains(w)) {
                    bag.add(w);
                }
            }
        }
        if (bag.isEmpty()) {
            bag.addAll(pool);
            Collections.shuffle(bag, rand);
        }

        String word = bag.remove(bag.size() - 1);
        StringBuilder remaining = new StringBuilder();
        for (String w : bag) {
            if (remaining.length() > 0) {
                remaining.append('\n');
            }
            remaining.append(w);
        }
        Prefs.setWordBag(this, "all", remaining.toString());
        traceWord(word);
        return word;
    }

    /** Records each subject drawn, so variety can be measured rather than felt. */
    private void igTrace(String line) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "ig.log"), true);
            f.write((line + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
    }

    private void traceWord(String word) {
        if (BuildConfig.DEBUG) try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "word.log"), true);
            f.write((word + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
    }

    /** Claims an item; false when another pass already has or has shown it. */
    private boolean rememberIfNew(Sources.Item item) {
        synchronized (seen) {
            return seen.add(item.toString());
        }
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
                    if (url != null && url.contains("instagram.com")) {
                        view.evaluateJavascript(INSTAGRAM_STYLE_JS, null);
                    }
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
            if ((active == null || wantNext) && (portraitVerified(slot)
                    || Prefs.landscapeAllowed(Main.this))) {
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
        // FIT always: cropping to fill hides part of the picture and made a
        // landscape clip look like a portrait one that had been zoomed.
        slot.view.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        slot.view.setPlayer(slot.player);
        slot.rootView = slot.view;
        slot.rootView.setVisibility(View.VISIBLE);
        stage.addView(slot.rootView, 0, match());
        slot.player.addListener(new Player.Listener() {

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                float ratio = 0f;
                boolean known = false;
                if (size.height > 0 && size.width > 0 && size.pixelWidthHeightRatio > 0
                        && !Float.isNaN(size.pixelWidthHeightRatio)
                        && !Float.isInfinite(size.pixelWidthHeightRatio)) {
                    ratio = size.width * size.pixelWidthHeightRatio / size.height;
                    known = true;
                }
                created.sizeKnown = known;
                created.displayRatio = ratio;
                igTrace("size " + size.width + "x" + size.height
                        + " ratio=" + ratio + " src=" + created.item.source);
                if (!Prefs.landscapeAllowed(Main.this) && known && ratio > 1.0f) {
                    // Landscape must never reach the screen in portrait-only.
                    if (pending == created) {
                        rejectSlot(created);
                    } else if (active == created) {
                        setLoading(true);
                        disposeActiveAndAdvance(created);
                    }
                } else if (known && pending == created && portraitVerified(created)) {
                    maybePromotePending();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                igTrace("state " + playbackState + " src=" + created.item.source);
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
                    if (portraitVerified(created)) {
                        created.player.play();
                    } else {
                        // Measured geometry has not arrived yet; stay silent.
                        created.player.pause();
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    // The feed never stops: a finished clip rolls straight on to
                    // the next one, which is already buffered behind it.
                    next();
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                igTrace("player error: " + e.getErrorCodeName()
                        + " " + (e.getCause() == null ? "" : e.getCause()));
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
        igTrace("prepare " + r.item.source + " url=" + r.url.substring(0, Math.min(80, r.url.length())));
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

    /**
     * Slides a clip in from the direction it was swiped from. Without this a
     * swap is instant and the gesture is indistinguishable from one that was
     * never received.
     */
    /**
     * Page players keep running when their tab is not visible. A prefetched
     * TikTok is loaded long before it is shown, so it must stay silent until it
     * becomes the clip on screen, and be silenced again when it is dropped -
     * otherwise its audio plays over whatever replaced it.
     */
    private static final String JS_ACTIVE =
            "(function(){window.__uuActive=true;"
                    + "try{var vs=document.querySelectorAll('video');"
                    + "for(var i=0;i<vs.length;i++){vs[i].muted=false;}}catch(e){}})()";

    /** Reads the page player's intrinsic size; layout size would prove nothing. */
    private static final String PAGE_SIZE_JS =
            "(function(){try{var v=document.querySelector('video');"
                    + "if(!v||!v.videoWidth||!v.videoHeight)return '?';"
                    + "return v.videoWidth+'x'+v.videoHeight;}catch(e){return '?';}})()";

    /** Reads the page player's position and length, in milliseconds. */
    private static final String PAGE_TIME_JS =
            "(function(){try{var v=document.querySelector('video');"
                    + "if(!v||!isFinite(v.duration)||v.duration<=0)return '0|0';"
                    + "return Math.round(v.currentTime*1000)+'|'"
                    + "+Math.round(v.duration*1000)+'|'"
                    + "+(v.paused?1:0);}catch(e){return '0|0|1';}})()";

    private static final String JS_IDLE =
            "(function(){window.__uuActive=false;"
                    + "try{var vs=document.querySelectorAll('video');"
                    + "for(var i=0;i<vs.length;i++){vs[i].muted=true;"
                    + "vs[i].pause();}}catch(e){}})()";

    private void silenceAndDestroy(final WebView web) {
        try {
            web.evaluateJavascript(JS_IDLE, null);
            web.loadUrl("about:blank");
        } catch (Exception ignored) {
        }
        // Destroying straight away can skip the unload above, leaving the
        // element playing with nothing left to stop it.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    web.destroy();
                } catch (Exception ignored) {
                }
            }
        }, 400);
    }

    private void slideIn(View v, float from) {
        int h = stage.getHeight() > 0 ? stage.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        v.animate().cancel();
        v.setTranslationY(from * h);
        v.setAlpha(0.6f);
        if (BuildConfig.DEBUG) try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "motion.log"), true);
            f.write(("slide " + from + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
        v.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Carries the current clip off the screen in the direction swiped. */
    private void slideOut(final View v, float direction) {
        if (v == null) {
            return;
        }
        int h = stage.getHeight() > 0 ? stage.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        v.animate().cancel();
        v.animate()
                .translationY(direction * h)
                .alpha(0.9f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();
    }

    /** Acknowledges a swipe that could not swap yet: the screen lifts and returns. */
    private void nudge(float direction) {
        if (active == null || active.rootView == null) {
            return;
        }
        int h = stage.getHeight() > 0 ? stage.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        final View v = active.rootView;
        v.animate().cancel();
        v.animate()
                .translationY(direction * h * 0.035f)
                .setDuration(110)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.animate().translationY(0f).setDuration(150).start();
                    }
                })
                .start();
    }

    /**
     * Portrait-only is decided from the decoded stream, not from what a source
     * claimed. Unknown geometry is not evidence of portrait, so an unmeasured
     * clip is never admitted while landscape is disallowed.
     */
    private boolean portraitVerified(Slot slot) {
        if (slot == null) {
            return false;
        }
        if (Prefs.landscapeAllowed(this)) {
            return true;
        }
        return slot.sizeKnown && slot.displayRatio <= 1.0f;
    }

    /** Promotes a verified clip only when the feed is actually waiting for one. */
    private void maybePromotePending() {
        if (pending == null || !portraitVerified(pending)) {
            return;
        }
        if (active == null || wantNext) {
            wantNext = false;
            next();
        }
    }

    /** Hides a clip that must not be seen, then asks for a replacement. */
    private void disposeActiveAndAdvance(Slot slot) {
        if (slot != active) {
            return;
        }
        slot.rejected = true;
        if (slot.player != null) {
            try {
                slot.player.setVolume(0f);
                slot.player.pause();
            } catch (Exception ignored) {
            }
        }
        if (slot.rootView != null) {
            slot.rootView.setVisibility(View.INVISIBLE);
        }
        wantNext = true;
        next();
    }

    private void rejectSlot(Slot slot) {
        if (slot == null) {
            return;
        }
        if (slot == pending) {
            pending = null;
        }
        if (slot.player != null) {
            try {
                slot.player.setVolume(0f);
                slot.player.stop();
                slot.player.release();
            } catch (Exception ignored) {
            }
        }
        if (slot.web != null) {
            silenceAndDestroy(slot.web);
        }
        if (slot.rootView != null && slot.rootView.getParent() == stage) {
            stage.removeView(slot.rootView);
        }
        wantNext = true;
        handler.post(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        });
    }

    private void next() {
        if (pending == null) {
            if (active != null) {
                // Nothing is buffered yet, so the clip leaves the screen and the
                // next one arrives when it is ready.
                slideOut(active.rootView, -swapDirection);
                // The clip is leaving, so the face may cover the wait instead of
                // a black screen.
                active.rendered = false;
            }
            refreshLoading();
            // Nothing buffered: remember the swipe and swap as soon as a slot is
            // ready, so a fast scroll never appears to do nothing.
            wantNext = true;
            pump();
            return;
        }
        if (!portraitVerified(pending) && !Prefs.landscapeAllowed(this)) {
            // Not admitted yet: keep it hidden and silent, wait for a measurement.
            wantNext = true;
            return;
        }
        wantNext = false;
        Slot old = active;
        active = pending;
        pending = null;
        current = active.item;
        remember(current.toString());
        if (BuildConfig.DEBUG) try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "play.log"), true);
            f.write((current.source + " " + current.id + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
        if (old != null) {
            if (!old.rejected) {
                history.push(new Ready(old.item, old.url, old.web != null, false));
            }
            if (history.size() > 20) {
                history.removeLast();
            }
            // Audio stops at once, but the surface stays alive long enough to
            // finish sliding out - releasing the player first would blank it
            // mid-animation and the motion would be lost.
            if (old.player != null) {
                old.player.setVolume(0f);
                old.player.pause();
            }
            if (old.rootView.getTranslationY() == 0f) {
                slideOut(old.rootView, -swapDirection);
            }
            final Slot going = old;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (going.player != null) {
                        going.player.stop();
                        going.player.release();
                    }
                    if (going.web != null) {
                        silenceAndDestroy(going.web);
                    }
                    stage.removeView(going.rootView);
                }
            }, 280);
        }
        active.rootView.setVisibility(View.VISIBLE);
        active.rootView.bringToFront();
        active.shownAt = System.currentTimeMillis();
        slideIn(active.rootView, swapDirection);
        swapDirection = 1f;
        if (active.player != null
                && active.player.getPlaybackState() == Player.STATE_READY) {
            active.rendered = true;
        }
        if (active.web != null) {
            // The page paints its own surface; the face is only for a feed with
            // nothing on screen at all.
            setLoading(false);
            active.web.evaluateJavascript(JS_ACTIVE, null);
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
            if (gateOpen) {
                active.player.pause();
            } else {
                active.player.play();
            }
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
                silenceAndDestroy(pending.web);
            }
            stage.removeView(pending.rootView);
            pending = null;
        }
        synchronized (ready) {
            ready.addFirst(back);
        }
        swapDirection = -1f;
        wantNext = true;
        pump();
    }

    /** The bar appears on touch and fades away by itself. */
    private void showControls() {
        if (controlsBar == null || settingsPanel != null
                || !Prefs.controlsEnabled(this)) {
            return;
        }
        controlsBar.setVisibility(View.VISIBLE);
        controlsBar.animate().cancel();
        controlsBar.animate().alpha(1f).setDuration(120).start();
        updateControls();
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 2600);
    }

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            if (controlsBar == null) {
                return;
            }
            controlsBar.animate().alpha(0f).setDuration(220).withEndAction(
                    new Runnable() {
                        @Override
                        public void run() {
                            controlsBar.setVisibility(View.GONE);
                        }
                    }).start();
        }
    };

    private final Runnable controlsTick = new Runnable() {
        @Override
        public void run() {
            if (controlsBar != null && controlsBar.getVisibility() == View.VISIBLE) {
                updateControls();
                handler.postDelayed(this, 500);
            }
        }
    };

    private void updateControls() {
        if (controlsBar == null) {
            return;
        }
        handler.removeCallbacks(controlsTick);
        handler.postDelayed(controlsTick, 500);
        boolean playable = active != null && active.player != null;
        controlsToggle.setAlpha(playable ? 1f : 0.35f);
        if (!playable) {
            if (active != null && active.web != null) {
                // The page keeps its own clock, so the bar has to read it.
                controlsToggle.setAlpha(1f);
                readPageTime(active.web);
                return;
            }
            controlsToggle.setText("\u25B6");
            controlsTime.setText("");
            if (controlsSeek != null) {
                controlsSeek.setEnabled(false);
                controlsSeek.setAlpha(0.35f);
            }
            return;
        }
        controlsToggle.setText(active.player.isPlaying() ? "\u2016\u2016" : "\u25B6");
        long duration = active.player.getDuration();
        if (duration > 0 && controlsSeek != null) {
            int seconds = (int) (duration / 1000);
            if (controlsSeek.getMax() != seconds) {
                controlsSeek.setMax(seconds);
            }
            if (!seeking) {
                controlsSeek.setProgress((int) (active.player.getCurrentPosition() / 1000));
            }
            controlsSeek.setEnabled(true);
            controlsSeek.setAlpha(1f);
        } else if (controlsSeek != null) {
            controlsSeek.setEnabled(false);
            controlsSeek.setAlpha(0.35f);
            if (active != null && active.web != null) {
                readPageTime(active.web);
            } else {
                controlsTime.setText("");
            }
        }
    }

    /** A provider page keeps its own clock; ask it rather than guess. */
    private void readPageTime(final WebView web) {
        web.evaluateJavascript(PAGE_TIME_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (active == null || active.web != web || value == null) {
                    return;
                }
                String clean = unquote(value);
                if (BuildConfig.DEBUG) try {
                    java.io.FileOutputStream f = new java.io.FileOutputStream(
                            new java.io.File(getFilesDir(), "controls.log"), true);
                    f.write((clean + "\n").getBytes("UTF-8"));
                    f.close();
                } catch (Exception ignored) {
                }
                String[] parts = clean.split("\\|");
                if (parts.length < 2) {
                    return;
                }
                long position;
                long duration;
                try {
                    position = Long.parseLong(parts[0].trim());
                    duration = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException e) {
                    return;
                }
                if (parts.length > 2) {
                    controlsToggle.setText("1".equals(parts[2].trim())
                            ? "\u25B6" : "\u2016\u2016");
                }
                if (duration <= 0) {
                    controlsTime.setText("");
                    controlsSeek.setEnabled(false);
                    controlsSeek.setAlpha(0.35f);
                    return;
                }
                int seconds = (int) (duration / 1000);
                if (controlsSeek.getMax() != seconds) {
                    controlsSeek.setMax(seconds);
                }
                controlsSeek.setEnabled(true);
                controlsSeek.setAlpha(1f);
                if (!seeking) {
                    controlsSeek.setProgress((int) (position / 1000));
                }
                controlsTime.setText(formatTime(position) + "   -"
                        + formatTime(duration - position));
            }
        });
    }

    private void updateTimeLabel() {
        if (active == null || active.player == null) {
            controlsTime.setText("");
            igTrace("bar: no player");
            return;
        }
        long duration = active.player.getDuration();
        long position = active.player.getCurrentPosition();
        igTrace("bar: dur=" + duration + " pos=" + position
                + " txt=" + controlsTime.getText());
        if (duration > 0) {
            controlsTime.setText(formatTime(position) + "   -" + formatTime(duration - position));
        } else {
            controlsTime.setText(formatTime(position));
        }
    }

    private static String formatTime(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long total = millis / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60);
    }

    private void togglePause() {
        if (active == null) {
            return;
        }
        if (active.player == null) {
            // Provider pages own their player, so ask it directly.
            if (active.web != null) {
                active.web.evaluateJavascript(
                        "(function(){try{var v=document.querySelector('video');"
                                + "if(!v)return;if(v.paused){v.play();}else{v.pause();}}"
                                + "catch(e){}})()", null);
            }
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

    /**
     * Landscape is only permitted when the person asked for it. When it is, the
     * activity follows the phone's own rotation setting: with auto-rotate on it
     * turns by itself, and with auto-rotate off Android offers its rotate
     * button when the phone is tilted. Nothing here overrides the user.
     */
    private void applyOrientation() {
        int wanted = Prefs.landscapeAllowed(this)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (getRequestedOrientation() != wanted) {
            setRequestedOrientation(wanted);
        }
    }

    private void toggleSettings() {
        if (settingsPanel != null) {
            root.removeView(settingsPanel);
            settingsPanel = null;
            applyOrientation();
            Sources.setAllowLandscape(Prefs.landscapeAllowed(this));
            igTrace("settings close: landscape=" + Prefs.landscapeAllowed(this)
                    + " active=" + (active != null)
                    + " verified=" + (active != null && portraitVerified(active))
                    + " known=" + (active != null && active.sizeKnown)
                    + " ratio=" + (active == null ? -1 : active.displayRatio));
            if (active != null && !portraitVerified(active)) {
                disposeActiveAndAdvance(active);
            }
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
        }, new Runnable() {
            @Override
            public void run() {
                Updater.check(Main.this, true);
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
