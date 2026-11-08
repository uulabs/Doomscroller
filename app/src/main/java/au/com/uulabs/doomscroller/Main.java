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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    /** Raised when the feed has run dry, so it refills faster than it drains. */
    private static final int PREFETCH_STARVING = 6;
    private volatile boolean starving;
    /** Sources that have been failing, and how long to leave them alone. */
    private final java.util.Map<String, Long> sourceRest = new java.util.HashMap<String, Long>();
    private final java.util.Map<String, Integer> sourceStrikes = new java.util.HashMap<String, Integer>();

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
    private Random rand = new Random();
    private final ArrayDeque<String> recentWords = new ArrayDeque<String>();
    private final ArrayDeque<String> recentChannels = new ArrayDeque<String>();
    private final ArrayDeque<Ready> ready = new ArrayDeque<Ready>();
    private final ArrayDeque<Ready> history = new ArrayDeque<Ready>();
    private final Set<String> seen = new LinkedHashSet<String>();
    /**
     * Normalised titles already shown. Re-uploads of one clip carry different ids
     * and often the same title, so ids alone cannot catch them.
     */
    private final Set<String> recentTitles = new LinkedHashSet<String>();
    /** Ids that have actually been shown, so forward scrolling cannot repeat one. */
    private final Set<String> played = new LinkedHashSet<String>();
    private boolean seenDirty;
    private int seenAdds;

    /** How many played ids are remembered. Small enough to keep, large enough to matter. */
    private static final int SEEN_LIMIT = 10000;

    private Slot active;
    private Slot pending;
    private Sources.Item current;
    private String lastSource;
    /** Discovery passes running now: one while a clip plays, two while the screen is empty. */
    private final java.util.concurrent.atomic.AtomicInteger fillers =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Sources a discovery pass is working on, so a second pass takes a different one. */
    private final Set<String> inFlight = new HashSet<String>();
    /** Mirror of screenIsBlank() for the discovery threads, which may not touch the player. */
    private volatile boolean blankNow = true;
    private long lastStackDump;
    private volatile boolean destroyed;
    private TopicSampler topicSampler;
    private TextView loadingMessage;
    private DoomerView doomer;
    private boolean foreground;
    private final ArrayDeque<Ready> forward = new ArrayDeque<Ready>();
    private TextView controlsSource;
    private TextView rotateButton;
    private boolean manualRotation;
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
    /** Geometry verdict of the item this discovery thread last resolved. */
    private final ThreadLocal<Boolean> lastLandscape = new ThreadLocal<Boolean>();
    private GestureDetector gestures;
    private YoutubeResolver ytResolver;
    private InstagramResolver igResolver;
    // Cold Meta discovery starts broadly; an empty indexed subject broadens the
    // next attempt in that source's selected language/family, without learning preferences.
    private final Set<String> broadenMeta = new HashSet<String>(
            java.util.Arrays.asList(Sources.INSTAGRAM, Sources.FACEBOOK));

    private static final class Ready {
        String title = "";
        final Sources.Item item;
        final String url;
        final boolean page;
        final boolean landscape;
        int navigation;

        Ready(Sources.Item item, String url, boolean page, boolean landscape) {
            this.item = item;
            this.url = url;
            this.page = page;
            this.landscape = landscape;
        }
    }

    private static final class Slot {
        String url;
        String title = "";     // what this clip is called, for repeat checking
        int navigation;
        long shownAt;
        long preparedAt;       // when loading began, to notice a candidate that is taking too long
        boolean rendered;
        boolean sizeKnown;     // the decoded stream has reported its geometry
        float displayRatio;    // width * pixel ratio / height
        boolean rejected;      // refused admission, so never history
        int langTries;         // how often the page's text has been read
        boolean watcherStarted;
        ExoPlayer player;      // null for page slots
        PlayerView view;       // null for page slots
        WebView web;           // set for page slots
        View rootView;
        Sources.Item item;
    }

    @Override
    protected void onCreate(Bundle state) {
        loadSeen();
        loadPlayed();
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
        long salt = Prefs.installSalt(this);
        rand = new Random(salt ^ System.nanoTime());
        Sources.setSalt(salt);
        try { Sources.loadReelCache(getAssets().open("meta-reels.tsv")); }
        catch (java.io.IOException unavailable) { /* Live discovery remains available. */ }
        topicSampler = new TopicSampler(Vocab.topics(getAssets()), rand, new TopicSampler.State() {
            public String get(String key) { return Prefs.wordBag(Main.this, key); }
            public void put(String key, String value) { Prefs.setWordBag(Main.this, key, value); }
        });
        applyOrientation();
        Sources.setAllowLandscape(Prefs.landscapeAllowed(this));
        Sources.setLanguageFilter(Prefs.languages(this), Prefs.knownLanguageOnly(this));
        if (BuildConfig.TRACE) {
            Sources.trace = new java.util.function.Consumer<String>() {
                @Override public void accept(String line) { igTrace(line); }
            };
        }
        ytResolver = new YoutubeResolver(this);
        igResolver = new InstagramResolver(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // A feed is watched without touching the screen, so the screen must not
        // dim and lock partway through a clip. PlayerView does not do this itself.
        root.setKeepScreenOn(true);
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
        rotateButton = glyph("↻");
        rotateButton.setContentDescription("Rotate video");
        FrameLayout.LayoutParams rotateParams = cornerParams(Gravity.TOP | Gravity.RIGHT);
        rotateParams.rightMargin = dp(14);
        root.addView(rotateButton, rotateParams);
        rotateButton.setVisibility(View.GONE);
        rotateButton.setOnClickListener(v -> {
            manualRotation = !manualRotation;
            setRequestedOrientation(manualRotation
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            updateRotateControl();
        });

        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        doomer = new DoomerView(this);
        loading.addView(doomer, new LinearLayout.LayoutParams(dp(210), dp(210)));
        TextView label = new TextView(this);
        loadingMessage = label;
        doomer.attachLabel(label);
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
        controlsSource = new TextView(this);
        controlsSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        controlsSource.setTextColor(0xFFBBBBBB);
        controlsSource.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        barTop.addView(controlsSource, new LinearLayout.LayoutParams(0, -2, 1f));
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
        // The bar is lifted above the navigation bar in applyInsets(); a view
        // holds one insets listener, so it must not be set here as well.

        root.addView(loadingView, match());
        loadingView.setVisibility(View.VISIBLE);
        syncDoomer();
        videoShown = false;

        setContentView(root);
    }

    /** Restores what has been shown, so a restart cannot repeat a clip either. */
    private void loadPlayed() {
        String csv = Prefs.playedIds(this);
        if (csv == null || csv.isEmpty()) {
            return;
        }
        synchronized (played) {
            for (String one : csv.split(",")) {
                if (!one.isEmpty()) {
                    played.add(one);
                }
            }
        }
        igTrace("played loaded=" + played.size());
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
    private void markPlayed(String id) {
        boolean added;
        synchronized (played) {
            added = played.add(id);
            while (played.size() > 10000) {
                java.util.Iterator<String> it = played.iterator();
                it.next();
                it.remove();
            }
            if (added) {
                playedDirty = true;
                playedAdds++;
            }
        }
        // Serialising ten thousand ids on every swipe, on the main thread, was
        // a needless stall; the list is written every eighth add and on pause.
        if (added && playedAdds % 8 == 0) {
            flushPlayed();
        }
    }

    private boolean playedDirty;
    private int playedAdds;

    private void flushPlayed() {
        String csv;
        synchronized (played) {
            if (!playedDirty) {
                return;
            }
            StringBuilder b = new StringBuilder();
            for (String one : played) {
                if (b.length() > 0) {
                    b.append(',');
                }
                b.append(one);
            }
            csv = b.toString();
            playedDirty = false;
        }
        Prefs.setPlayedIds(this, csv);
    }

    private boolean isPlayed(String id) {
        synchronized (played) {
            return played.contains(id);
        }
    }

    /** Lower-case letters only, so punctuation, emoji and casing do not matter. */
    private static String titleKey(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lower.length() && out.length() < 80; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ' ') {
                out.append(c);
            } else if (c > 127) {
                out.append(c);   // keep other scripts as they are
            }
        }
        return out.toString().replaceAll(" +", " ").trim();
    }

    private final Set<String> mediaSeen = new LinkedHashSet<String>();

    /** Fingerprint of the resolved stream, so one video cannot arrive twice. */
    private boolean mediaIsNew(String url) {
        if (url == null || url.isEmpty()) {
            return true;
        }
        String key;
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            key = hex.toString();
        } catch (Exception e) {
            key = url.substring(0, Math.min(60, url.length()));
        }
        synchronized (mediaSeen) {
            if (mediaSeen.contains(key)) {
                return false;
            }
            mediaSeen.add(key);
            while (mediaSeen.size() > 4000) {
                java.util.Iterator<String> it = mediaSeen.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }

    /** True when this title has not been shown; records it when new. */
    private boolean titleIsNew(String title) {
        String key = titleKey(title);
        if (key.length() < 6) {
            return true;         // too short to judge, do not block on it
        }
        synchronized (recentTitles) {
            if (recentTitles.contains(key)) {
                return false;
            }
            recentTitles.add(key);
            while (recentTitles.size() > 4000) {
                java.util.Iterator<String> it = recentTitles.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }

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
                syncDoomer();
                if (active != null && active.web != null) {
                    active.web.evaluateJavascript(JS_ACTIVE, null);
                }
                if (active != null && active.player != null && !paused && portraitVerified(active) && !active.rejected) {
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
        syncDoomer();
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
                if (destroyed) return;
                if (loadingView != null) {
                    boolean blank = screenIsBlank();
                    blankNow = blank;
                    long now = System.currentTimeMillis();
                    if (BuildConfig.TRACE && blank && blankSince != 0 && now - blankSince > 30000
                            && now - lastStackDump > 30000) {
                        lastStackDump = now;
                        for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                                : Thread.getAllStackTraces().entrySet()) {
                            String name = e.getKey().getName();
                            if (!name.startsWith("pool") && !name.equals("main")) continue;
                            StringBuilder b = new StringBuilder("stack " + name + " " + e.getKey().getState());
                            int n = 0;
                            for (StackTraceElement f : e.getValue()) {
                                if (n++ > 14) break;
                                b.append(" | ").append(f.getClassName().replaceAll(".*\\.", ""))
                                        .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
                            }
                            igTrace(b.toString());
                        }
                    }
                    if (blank && pending != null && !pending.rejected
                            && !portraitVerified(pending) && pending.preparedAt != 0
                            && now - pending.preparedAt > PENDING_SLOW_MILLIS) {
                        boolean another;
                        synchronized (ready) { another = !ready.isEmpty(); }
                        if (another) {
                            // Nothing is on screen and this candidate has still
                            // not shown its geometry while a resolved one waits
                            // behind it. Only one clip loads at a time, so the
                            // slow one gives way rather than holding the face up.
                            igTrace("slow pending " + pending.item.source + " after "
                                    + (now - pending.preparedAt) + "ms, giving way");
                            rejectSlot(pending);
                        }
                    }
                    if (loadingMessage != null && blank && blankSince != 0
                            && now - blankSince > 1500 && pending == null) {
                        // The clip has left and nothing is being measured or
                        // promoted, so say what the wait actually is.
                        doomer.say("finding more");
                    }
                    if (!blank) {
                        blankSince = 0;
                    } else if (blankSince == 0) {
                        blankSince = now;
                    }
                    if (active != null && active.web != null && !gateOpen
                            && !blank && !active.rejected
                            && now - lastActivePing > 1200) {
                        lastActivePing = now;
                        igTrace("keepalive blank=" + blank + " src=" + active.item.source);
                        active.web.evaluateJavascript(JS_ACTIVE, null);
                    }
                    boolean settled = blank && blankSince != 0
                            && now - blankSince > 700;
                    if (settled != (loadingView.getVisibility() == View.VISIBLE)) {
                        setLoading(settled);
                    }
                    if (settled && now - blankSince > 1200 && !gateOpen && settingsPanel == null
                            && pendingReady() && portraitVerified(pending)
                            && (active == null || active.rejected || (active.rootView != null
                            && Math.abs(active.rootView.getTranslationY()) > 1f))) {
                        // Nothing is on screen and a verified clip is waiting: whatever
                        // trigger was missed, the feed moves on as a swipe would.
                        igTrace("watchdog promote: active=" + (active == null ? "none"
                                : active.item.source + (active.rejected ? " rejected" : " away"))
                                + " pending=" + pending.item.source + " wantNext=" + wantNext);
                        next();
                    }
                    if (blank && blankSince != 0 && stuckActive()) {
                        long waited = now - blankSince;
                        if (waited > STUCK_ADVANCE_MILLIS && portraitVerified(pending)
                                && pendingReady()) {
                            // The clip on screen has never shown a frame but
                            // the next one is ready: roll on as a swipe would.
                            igTrace("stuck active " + active.item.source + " after "
                                    + waited + "ms, advancing to " + pending.item.source);
                            setStatus("skipped: stuck");
                            blankSince = now;
                            next();
                        } else if (waited > STUCK_DROP_MILLIS) {
                            // Nothing ready yet either: give the dead clip up so
                            // the next verified one is promoted on arrival.
                            igTrace("stuck active " + active.item.source + " after "
                                    + waited + "ms, dropping");
                            setStatus("skipped: stuck");
                            blankSince = now;
                            disposeActiveAndAdvance(active);
                        }
                    }
                }
                handler.postDelayed(this, 400);
            }
        }, 400);
    }

    /** How long the face may sit on a clip that has not shown a frame while another is ready. */
    private static final long STUCK_ADVANCE_MILLIS = 8000;
    /** How long an unmeasured candidate may keep an empty screen waiting when another clip is resolved. */
    private static final long PENDING_SLOW_MILLIS = 8000;
    /** How long before such a clip is given up even with nothing ready behind it. */
    private static final long STUCK_DROP_MILLIS = 25000;

    /**
     * True when the clip on screen is the reason the face is showing: it is in
     * place, not already leaving or rejected, and nobody has paused the feed.
     * A swipe already rescues this state; the watchdog does the same by itself.
     */
    private boolean stuckActive() {
        if (active == null || active.rejected || active.rendered || paused
                || gateOpen || settingsPanel != null) {
            return false;
        }
        return active.rootView == null || Math.abs(active.rootView.getTranslationY()) <= 1f;
    }

    /** True when the buffered clip could be shown this instant. */
    private boolean pendingReady() {
        if (pending == null || pending.rejected) {
            return false;
        }
        if (pending.player != null) {
            return pending.player.getPlaybackState() == Player.STATE_READY;
        }
        return pending.web != null && (pending.sizeKnown
                || (pending.watcherStarted && Prefs.landscapeAllowed(this)));
    }

    private void setLoading(boolean show) {
        if (loadingView == null) {
            return;
        }
        if (show && BuildConfig.DEBUG) {
            try {
                java.io.FileOutputStream f = new java.io.FileOutputStream(
                        new java.io.File(getFilesDir(), "loading.log"), true);
                f.write("SHOW\n".getBytes("UTF-8"));
                f.close();
            } catch (Exception ignored) {
            }
        }
        loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
        syncDoomer();
        if (show && active != null && active.web != null) {
            // Nothing may keep playing behind the face; a provider page that is
            // still running is heard as a second audio stream.
            active.web.evaluateJavascript(JS_IDLE, null);
        }
        if (show && controlsBar != null) {
            // A play/pause control over a loading screen looks broken.
            handler.removeCallbacks(hideControls);
            controlsBar.animate().cancel();
            controlsBar.setVisibility(View.GONE);
        }
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

    /** The face only moves while it is actually on screen and the app is in front. */
    private void syncDoomer() {
        if (doomer == null) return;
        boolean on = !destroyed && foreground && !gateOpen && loadingView != null
                && loadingView.getVisibility() == View.VISIBLE;
        if (on) doomer.start(); else doomer.stop();
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
        if (rotateButton != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) rotateButton.getLayoutParams();
            p.topMargin = topInset + dp(12);
            rotateButton.setLayoutParams(p);
        }
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
                if (rotateButton != null) {
                    int right = Build.VERSION.SDK_INT >= 30
                            ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).right
                            : insets.getSystemWindowInsetRight();
                    FrameLayout.LayoutParams rp = (FrameLayout.LayoutParams) rotateButton.getLayoutParams();
                    rp.rightMargin = dp(14) + right;
                    rotateButton.setLayoutParams(rp);
                }
                if (controlsBar != null) {
                    // Keep the bar above the navigation bar rather than underneath it.
                    FrameLayout.LayoutParams bp =
                            (FrameLayout.LayoutParams) controlsBar.getLayoutParams();
                    bp.bottomMargin = dp(44) + bottom;
                    controlsBar.setLayoutParams(bp);
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

    private volatile long nextDiscoveryAt;
    private final Runnable retryDiscovery = new Runnable() {
        @Override public void run() { if (!destroyed) pump(); }
    };

    private void fill() {
        if (destroyed) {
            return;
        }
        igTrace("fill: entered, passes=" + fillers.get() + " pending=" + (pending != null));
        // While nothing is on screen a second discovery pass runs on another
        // source, so one slow provider cannot hold the first clip back.
        boolean empty;
        synchronized (ready) { empty = ready.isEmpty(); }
        int limit = blankNow && empty && pending == null ? 2 : 1;
        synchronized (inFlight) {
            // A second pass is only worth starting when a source is free for it.
            int free = 0;
            for (String s : Prefs.sources(this)) if (!inFlight.contains(s)) free++;
            limit = Math.min(limit, Math.max(1, free));
        }
        if (fillers.get() >= limit) {
            return;
        }
        igTrace("fill: pass " + (fillers.get() + 1) + " of " + limit);
        long wait = Math.max(nextDiscoveryAt - android.os.SystemClock.uptimeMillis(),
                Sources.retryDelayMillis(Prefs.sources(this)));
        if (wait > 0) {
            igTrace("fill: waiting " + wait + "ms");
            if (wait > 5000 && active == null && pending == null && loadingMessage != null)
                doomer.say("Selected sources unavailable\nRetrying automatically");
            handler.removeCallbacks(retryDiscovery);
            handler.postDelayed(retryDiscovery, wait);
            return;
        }
        if (doomer != null) doomer.say("loading");
        if (fillers.incrementAndGet() > limit) {
            fillers.decrementAndGet();
            return;
        }
        try { io.execute(new Runnable() {
            @Override
            public void run() {
                boolean found = false;
                int attempts = 0;
                try {
                    while (!destroyed) {
                        synchronized (ready) {
                            if (ready.size() >= (starving ? PREFETCH_STARVING : PREFETCH)) {
                                break;
                            }
                        }
                        if (++attempts > 10) {
                            break;   // give up for now rather than spin forever
                        }
                        if (Sources.retryDelayMillis(Prefs.sources(Main.this)) > 0) break;
                        Ready r = produce();
                        if (destroyed) return;
                        if (r == null) {
                            boolean idle;
                            synchronized (inFlight) { idle = inFlight.isEmpty(); }
                            if (!idle && fillers.get() > 1) {
                                break;      // the other pass holds every source; it will refill
                            }
                            continue;   // this subject was refused; try another
                        }
                        found = true;
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
                    if (!found) {
                        // Nothing came back from this source: leave starving mode
                        // so the next pass rotates instead of insisting on one.
                        starving = false;
                        if (attempts > 0) {
                            nextDiscoveryAt = android.os.SystemClock.uptimeMillis() + 2000;
                        }
                    }
                    fillers.decrementAndGet();
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pump();
                    }
                });
            }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) {
            fillers.decrementAndGet();
            if (!destroyed) throw stopped;
        }
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
        String source;
        synchronized (inFlight) {
            source = pickSource(sources);
            if (source == null) {
                igTrace("produce: every enabled source is busy");
                return null;   // every enabled source is already being worked on
            }
            if (starving && sources.contains(Sources.YOUTUBE)
                    && !Sources.YOUTUBE.equals(lastSource)
                    && !inFlight.contains(Sources.YOUTUBE)) {
                // YouTube resolves in about a second, but forcing it every attempt
                // starves the feed when it is the thing that is failing.
                source = Sources.YOUTUBE;
            }
            inFlight.add(source);
        }
        try {
            return produceFrom(sources, source);
        } finally {
            synchronized (inFlight) { inFlight.remove(source); }
        }
    }

    private Ready produceFrom(Set<String> sources, String source) {
        String word = pickWord(source);
        List<Sources.Item> candidates;
        try {
            candidates = Sources.fetch(source, word);
        } catch (Exception e) {
            return null;
        }
        if (Sources.INSTAGRAM.equals(source) || Sources.FACEBOOK.equals(source)) {
            if (candidates.isEmpty()) broadenMeta.add(source);
            else broadenMeta.remove(source);
        }
        lastSource = source;
        igTrace("candidates " + source + " " + candidates.size()
                + " word=" + word + " " + Sources.supplyAudit());
        List<Sources.Item> filtered = filter(candidates);
        for (Sources.Item item : filtered) {
            String url = resolve(item);
            if (url != null) {
                if (!rememberIfNew(item)) {
                    continue;   // already queued or played by another pass
                }
                String shownTitle = Sources.YOUTUBE.equals(item.source)
                        ? ytResolver.lastTitle()
                        : (Sources.INSTAGRAM.equals(item.source)
                        || Sources.TIKTOK.equals(item.source)
                        || Sources.FACEBOOK.equals(item.source)
                        || Sources.X.equals(item.source))
                        ? Sources.lastCaption : item.title;
                noteSource(source, true);
                if (!titleIsNew(shownTitle)) {
                    igTrace("repeat title refused");
                    continue;
                }
                if ((Sources.INSTAGRAM.equals(item.source)
                        || Sources.FACEBOOK.equals(item.source))
                        && !mediaIsNew(url)) {
                    igTrace("repeat media refused " + item.source);
                    continue;
                }
                Ready ready = new Ready(item, url, isPage(item), Boolean.TRUE.equals(lastLandscape.get()));
                ready.title = shownTitle == null ? "" : shownTitle;
                return ready;
            }
            remember(item.toString());
        }
        igTrace("produce none " + source);
        noteSource(source, false);
        return null;
    }

    /** Every source must yield a direct stream; anything else is dropped. */
    private String resolve(Sources.Item item) {
        if (Sources.TIKTOK.equals(item.source)) {
            String spoken = Sources.tiktokCaptionLanguage(item.id);
            igTrace("tiktok " + item.id + " -> " + (spoken == null ? "none" : spoken) + " by "
                    + Sources.lastTiktokSignal + " (" + Sources.lastCaptionNote + ")");
            if (spoken != null && !Prefs.languages(this).contains(spoken)) {
                igTrace("skip tiktok caption " + spoken);
                return null;
            }
            if (spoken == null && Prefs.knownLanguageOnly(this)) {
                igTrace("skip tiktok unknown language");
                return null;
            }
        }
        if ("xprobe".equals(item.kind)) {
            String media = Sources.xMedia(item.id);
            String spoken = Sources.lastXLanguage;
            igTrace("x " + item.id + " -> " + (media == null ? "NOREAD" : media.isEmpty() ? "NOVIDEO" : "ok")
                    + " lang " + (spoken == null ? "none" : spoken) + " (" + Sources.lastCaptionNote + ")");
            if (media == null || media.isEmpty()) {
                return null;
            }
            if (spoken != null && !Prefs.languages(this).contains(spoken)) {
                return null;
            }
            if (spoken == null && Prefs.knownLanguageOnly(this)) {
                return null;
            }
            boolean wide = Sources.lastXWidth > 0 && Sources.lastXHeight > 0
                    && Sources.lastXWidth > Sources.lastXHeight;
            lastLandscape.set(wide);
            if (wide && !Prefs.landscapeAllowed(this)) {
                return null;
            }
            return media;
        }
        if ("fbprobe".equals(item.kind)) {
            String media = Sources.facebookMedia(item.id);
            String spoken = Sources.lastFacebookLanguage;
            igTrace("fb " + item.id + " -> " + (media == null ? "NOMEDIA" : "ok")
                    + " caption " + (spoken == null ? "none" : spoken)
                    + " (" + Sources.lastCaptionNote + ")");
            if (spoken != null && !Prefs.languages(this).contains(spoken)) {
                igTrace("skip fb caption " + spoken);
                return null;
            }
            if (spoken == null && Prefs.knownLanguageOnly(this)) {
                igTrace("skip fb unknown language");
                return null;
            }
            return media;
        }
        if ("igprobe".equals(item.kind)) {
            String spoken = Sources.instagramCaptionLanguage(item.id);
            igTrace("ig caption " + (spoken == null ? "none" : spoken)
                    + " (" + Sources.lastCaptionNote + ")");
            if (spoken != null && !Prefs.languages(this).contains(spoken)) {
                igTrace("skip ig caption " + spoken);
                return null;
            }
            if (spoken == null && Prefs.knownLanguageOnly(this)) {
                igTrace("skip ig unknown language");
                return null;
            }
            String direct = Sources.instagramMedia(item.id);
            if (direct != null) {
                igTrace(item.id + (direct.isEmpty() ? " -> public embed unavailable" : " -> direct metadata"));
                return direct.isEmpty() ? null : direct;
            }
            if (igResolver != null && !destroyed && !isFinishing()) {
                String media = igResolver.resolveBlocking(item.id, 6000);
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
            // A full wait is fine behind a playing clip; with the screen empty the
            // other sources are better asked after a short one.
            YoutubeResolver.Resolved r = ytResolver.resolveBlocking(item.id, blankNow ? 15000 : 45000);
            if (r == null) {
                return null;
            }
            boolean wide = r.width > 0 && r.height > 0 && r.height < r.width;
            lastLandscape.set(wide);
            if (wide && !Prefs.landscapeAllowed(this)) {
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
            lastLandscape.set(false);
            return item.url;
        }
        if ("mp4".equals(item.kind)) {
            boolean wide = !item.portraitOk();
            lastLandscape.set(wide);
            if (wide && !Prefs.landscapeAllowed(this)) {
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
                    // A reel that has been deleted since the index saw it says so.
                    + "if(t.indexOf('may be broken')>=0"
                    + "||t.indexOf('post may have been removed')>=0)return 'GONE';"
                    + "var v=document.querySelector('video');"
                    + "if(!v)return 'NOSTREAM';"
                    + "if(window.__uuActive&&v.muted){v.muted=false;}"
                    // A provider player can sit paused after it loads, not only
                    // at the very start. Nudging it anywhere before the end gets
                    // a stalled clip going again without fighting its end card.
                    + "if(window.__uuActive&&v.paused&&!v.ended"
                    + "&&(!isFinite(v.duration)||v.duration<=0"
                    + "||v.currentTime<v.duration-1)){try{v.play();}catch(e){}}"
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

    /** One-shot description of the page player, for diagnosing a frozen clip. */
    private static final String PAGE_STATE_JS =
            "(function(){try{var v=document.querySelector('video');"
                    + "if(!v)return 'novideo';"
                    + "return 'paused='+v.paused+' rs='+v.readyState+' t='+v.currentTime"
                    + "+' dur='+v.duration+' muted='+v.muted+' err='+(v.error?v.error.code:'none')"
                    + "+' net='+(v.networkState);"
                    + "}catch(e){return 'err '+e;}})()";

    private boolean ownsPage(WebView view) {
        if (destroyed) return false;
        return (active != null && active.web == view && !active.rejected)
                || (pending != null && pending.web == view && !pending.rejected);
    }

    private void watchForUnavailable(final WebView view, final int attempt) {
        if (!ownsPage(view)) return;
        if (attempt > 600) {
            return;
        }
        if (attempt == 10 || attempt == 30) {
            view.evaluateJavascript(PAGE_STATE_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String state) {
                    igTrace("page state " + (pending != null && pending.web == view
                            ? pending.item.source : active == null ? "?" : active.item.source)
                            + " " + state);
                }
            });
        }
        view.evaluateJavascript(WATCH_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!ownsPage(view)) return;
                if (value != null && value.contains("END")) {
                    igTrace("page end " + (pending != null && pending.web == view
                            ? pending.item.source : active == null ? "?" : active.item.source)
                            + " " + value.replace("\"", ""));
                }
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
                // A prefetched page is intentionally paused: END can mean only
                // that its playhead has not moved. Reject unavailable/missing
                // streams here, not a healthy paused prefetch.
                if ((gone || (blind && next >= 8)) && watched == pending) {
                    rejectSlot(watched);
                    return;
                }
                if (watched == pending && !gone && portraitVerified(watched)) {
                    // A loaded page needs no geometry report when landscape is allowed.
                    maybePromotePending();
                }
                if (watched != null) {
                    view.evaluateJavascript(PAGE_SIZE_JS, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String size) {
                            if (watched != active && watched != pending) return;
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
                            updateRotateControl();
                            igTrace("page size " + w + "x" + h + " src=" + watched.item.source);
                            if (watched.displayRatio > 1.0f && !Prefs.landscapeAllowed(Main.this)) {
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
     * Restyles Instagram's embed. Hiding the whole post around the clip was
     * tried and stops it rendering, so this only removes the obvious chrome and
     * leaves the player's own boxes alone.
     */
    private static final String INSTAGRAM_STYLE_JS =
            "(function(){try{var s=document.createElement('style');"
                    + "s.textContent='header,footer,nav{display:none!important}'"
                    + "+'html,body{background:#000!important;margin:0!important}';"
                    + "document.head.appendChild(s);return '1';}catch(e){return '0';}})()";

    /**
     * Records whether a source came back with anything. A source that keeps
     * failing is rested for a while: hammering it makes throttling worse, which
     * is what leaves the feed on the loading face.
     */
    private synchronized void noteSource(String source, boolean ok) {
        if (ok) {
            sourceStrikes.remove(source);
            sourceRest.remove(source);
            return;
        }
        int strikes = sourceStrikes.containsKey(source) ? sourceStrikes.get(source) + 1 : 1;
        sourceStrikes.put(source, strikes);
        if (strikes >= 2) {
            // Measured: TikTok answers again about 15 seconds after refusing, so
            // the rest starts there and doubles only while refusals continue.
            long seconds = Math.min(120L, 15L << Math.min(3, strikes - 2));
            sourceRest.put(source, System.currentTimeMillis() + seconds * 1000);
            igTrace("resting " + source + " for " + seconds + "s");
        }
    }

    private synchronized boolean resting(String source) {
        Long until = sourceRest.get(source);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            sourceRest.remove(source);
            return false;
        }
        return true;
    }

    private String pickSource(Set<String> enabled) {
        Set<String> busy;
        synchronized (inFlight) { busy = new HashSet<String>(inFlight); }   // re-entrant from produce()
        List<String> options = new ArrayList<String>();
        for (String s : Sources.ALL) {
            if (enabled.contains(s) && !s.equals(lastSource) && !resting(s) && !busy.contains(s)) {
                options.add(s);
            }
        }
        if (options.isEmpty()) {
            for (String s : Sources.ALL) {
                if (enabled.contains(s) && !s.equals(lastSource) && !busy.contains(s)) {
                    options.add(s);
                }
            }
        }
        if (options.isEmpty()) {
            for (String s : enabled) {
                if (!busy.contains(s)) options.add(s);
            }
        }
        if (options.isEmpty()) {
            return null;
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
    private String pickWord(String source) {
        if (Prefs.randomLetters(this)) {
            String letters = randomLetters();
            traceWord(letters);
            return letters;
        }
        TopicSampler.Topic topic = topicSampler.next(Prefs.languages(this));
        String query = broadenMeta.contains(source) ? Vocab.familyQuery(topic) : topic.query;
        traceWord(query);
        return query;
    }

    /** Three or four letters with no list behind them; the sources decide what they mean. */
    private String randomLetters() {
        int n = 3 + rand.nextInt(2);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            out.append((char) ('a' + rand.nextInt(26)));
        }
        return out.toString();
    }

    /** Records each subject drawn, so variety can be measured rather than felt. */
    private void igTrace(String line) {
        if (BuildConfig.TRACE) {
            android.util.Log.d("Doom", line);
        }
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

    private void postSlotTask(Slot slot, Runnable task, long delay) {
        handler.postAtTime(task, slot, android.os.SystemClock.uptimeMillis() + delay);
    }

    private boolean sourceEnabled(Sources.Item item) {
        return Prefs.sources(this).contains(item.source)
                || (BuildConfig.DEBUG && "Probe".equals(item.source) && Prefs.probeLandscape(this));
    }

    private void pump() {
        if (destroyed) return;
        if (pending != null) {
            return;
        }
        Ready selected;
        do {
            synchronized (ready) { selected = ready.pollFirst(); }
        } while (selected != null && !sourceEnabled(selected.item));
        final Ready r = selected;
        if (r == null) {
            fill();
            return;
        }
        Slot slot = new Slot();
        slot.preparedAt = System.currentTimeMillis();
        slot.item = r.item;
        slot.title = r.title == null ? "" : r.title;
        slot.navigation = r.navigation;
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
                    if (!ownsPage(view) || slot.watcherStarted) return;
                    slot.watcherStarted = true;
                    watchForUnavailable(view, 0);
                    if (url != null && url.contains("instagram.com")) {
                        view.evaluateJavascript(INSTAGRAM_STYLE_JS, null);
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
            slot.web = w;
            slot.url = r.url;
            slot.rootView = w;
            slot.rootView.setVisibility(View.GONE);
            stage.addView(slot.rootView, match());
            pending = slot;
            w.loadUrl(r.url);
            postSlotTask(slot, () -> {
                if (pending == slot && !portraitVerified(slot)) rejectSlot(slot);
            }, 25000);
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
                // Behave like a media app: take audio focus when a clip plays, so
                // other apps' music stops, and pause for a call or when headphones
                // come out instead of carrying on through the speaker.
                .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        slot.player.setRepeatMode(Player.REPEAT_MODE_OFF);
        slot.player.setVolume(0f);
        // Prefetch must compose beneath the active view. A separate SurfaceView
        // showed the pending clip's paused first frame over the active video on S98.
        slot.view = (PlayerView) getLayoutInflater().inflate(R.layout.video_player, stage, false);
        slot.view.setUseController(false);
        // FIT always: cropping to fill hides part of the picture and made a
        // landscape clip look like a portrait one that had been zoomed.
        slot.view.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        slot.view.setPlayer(slot.player);
        slot.rootView = slot.view;
        slot.rootView.setVisibility(View.VISIBLE);
        // Keep a decoding surface without exposing unverified prefetch frames.
        // slideIn restores alpha only after this slot is admitted.
        slot.rootView.setAlpha(0f);
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
                updateRotateControl();
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
                if (pending == created && playbackState == Player.STATE_READY
                        && portraitVerified(created)) {
                    // With landscape allowed no geometry report is awaited: a ready
                    // clip is shown as soon as the feed is waiting for one.
                    maybePromotePending();
                }
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
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                // Recorded so a device run can tell whether a provider page took
                // the focus from the clip on screen.
                if (!playWhenReady && reason
                        == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    igTrace("audio focus lost src=" + created.item.source
                            + (active == created ? " (active)" : ""));
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                igTrace("player error: " + e.getErrorCodeName()
                        + " " + (e.getCause() == null ? "" : e.getCause()));
                setStatus("skipped: " + e.getErrorCodeName());
                if (pending == created) {
                    rejectSlot(created);
                } else if (active == created) {
                    next();
                }
            }
        });
        slot.player.setMediaItem(MediaItem.fromUri(r.url));
        igTrace("prepare " + r.item.source + " url=" + r.url.substring(0, Math.min(80, r.url.length())));
        pending = slot;
        slot.player.prepare();
        postSlotTask(created, new Runnable() {
            @Override
            public void run() {
                if (pending == created
                        && (created.player.getPlaybackState() != Player.STATE_READY
                            || !portraitVerified(created))) {
                    setStatus("skipped: not ready");
                    rejectSlot(created);
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

    /** A bounded sample of the page's own words, to tell what language it is. */
    private static final String PAGE_TEXT_JS =
            """
            (function(){try{
              var h=document.documentElement.innerHTML, out='';
              function grab(key){
                var i=h.indexOf(key), guard=0;
                while(i>=0 && guard++<4){
                  var c=h.indexOf(':', i+key.length);
                  if(c<0)break;
                  var q1=h.indexOf('"', c), q2=h.indexOf('"', q1+1);
                  if(q1>0 && q2>q1) return h.substring(q1+1, q2);
                  i=h.indexOf(key, i+key.length);
                }
                return '';
              }
              out=grab('"desc"')+' '+grab('"caption"')+' '+grab('"text"');
              if(out.replace(/ /g,'').length<3){
                out=(document.body&&document.body.innerText)||'';
              }
              return out.slice(0,400);
            }catch(e){return '';}})()
            """;

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
        igTrace("silence+destroy page");
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
    /**
     * Commits a swipe whose destination is not ready. The clip leaves at once
     * and the face covers the wait, so the gesture is answered with movement
     * rather than a bounce that reads as "that did not work".
     */
    private void rollAwayToLoading(float direction) {
        if (active != null) {
            slideOut(active.rootView, direction);
            if (active.player != null) {
                active.player.setVolume(0f);
                active.player.pause();
            }
            if (active.web != null) {
                active.web.evaluateJavascript(JS_IDLE, null);
            }
            active.rendered = false;
        }
        refreshLoading();
    }

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
        return !slot.rejected && slot.sizeKnown && slot.displayRatio > 0f && slot.displayRatio <= 1.0f;
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
        if (slot == null || slot.rejected) {
            return;
        }
        handler.removeCallbacksAndMessages(slot);
        slot.rejected = true;
        if (slot == pending) {
            pending = null;
            if (slot.navigation < 0) {
                // A disallowed/unavailable historical clip must not turn a
                // backward swipe into forward replay or fresh discovery.
                Ready older = history.pollFirst();
                if (older != null) {
                    older.navigation = -1;
                    synchronized (ready) { ready.addFirst(older); }
                    wantNext = true;
                    swapDirection = -1f;
                } else {
                    wantNext = false;
                    swapDirection = 1f;
                }
            }
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
        handler.post(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        });
    }

    private void next() {
        if (destroyed) return;
        if (pending != null && !sourceEnabled(pending.item)) {
            wantNext = true;
            rejectSlot(pending);
            return;
        }
        if (!forward.isEmpty() && (pending == null || pending.navigation == 0)) {
            prepareHistory(forward.pollFirst(), 1);
            return;
        }
        if (pending == null) {
            if (active != null) {
                // Nothing is buffered yet, so the clip leaves the screen and the
                // next one arrives when it is ready.
                rollAwayToLoading(-swapDirection);
            }
            starving = true;
            refreshLoading();
            // Nothing buffered: remember the swipe and swap as soon as a slot is
            // ready, so a fast scroll never appears to do nothing.
            wantNext = true;
            pump();
            return;
        }
        if (!portraitVerified(pending) && !Prefs.landscapeAllowed(this)) {
            // A clip is on its way but has not been measured yet. Commit the
            // swipe anyway: it leaves and the face covers the wait, so the
            // gesture never looks rejected.
            rollAwayToLoading(-swapDirection);
            starving = true;
            wantNext = true;
            return;
        }
        if (pending.navigation == 0 && isPlayed(pending.item.toString())) {
            // Already watched. Only history may replay a clip.
            rejectSlot(pending);
            starving = true;
            return;
        }
        wantNext = false;
        starving = false;
        markPlayed(pending.item.toString());
        Slot old = active;
        active = pending;
        pending = null;
        handler.removeCallbacksAndMessages(active);
        if (old != null) handler.removeCallbacksAndMessages(old);
        current = active.item;
        updateRotateControl();
        remember(current.toString());
        if (BuildConfig.DEBUG) try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "play.log"), true);
            f.write((current.source + "\t" + current.id + "\t"
                    + titleKey(active.title) + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
        if (old != null) {
            if (!old.rejected) {
                ArrayDeque<Ready> destination = active.navigation < 0 ? forward : history;
                destination.push(new Ready(old.item, old.url, old.web != null, false));
                if (destination.size() > 100) destination.removeLast();
            }
            if (history.size() > 100) {
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
            igTrace("promote page " + active.item.source);
            active.web.evaluateJavascript(JS_ACTIVE, null);
            // A provider player reports nothing when a clip finishes - and an
            // advert can hold it indefinitely - so a page slot is capped and
            // the feed moves on.
            final Slot shown = active;
            postSlotTask(shown, new Runnable() {
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
        // A provider page can still be holding audio focus when the next clip
        // starts, which leaves the player prepared but not playing. Nothing was
        // asked to pause here, so a short check puts it right.
        if (active.player != null) {
            final Slot started = active;
            for (int delay : new int[]{700, 1800}) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || gateOpen || paused || active != started
                                || started.player == null || started.rejected) {
                            return;
                        }
                        if (started.player.getPlaybackState() == Player.STATE_READY
                                && !started.player.isPlaying()) {
                            igTrace("resumed stalled clip " + started.item.source);
                            started.player.play();
                        }
                    }
                }, delay);
            }
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        });
    }

    private void previous() {
        if (destroyed) return;
        if (pending != null && pending.navigation != 0) {
            nudge(1f);
            return;
        }
        Ready back = history.pollFirst();
        if (back == null) { nudge(1f); return; }
        prepareHistory(back, -1);
    }

    private void prepareHistory(Ready entry, int direction) {
        if (pending != null) {
            handler.removeCallbacksAndMessages(pending);
            // Preserve the unused discovery clip while retracing history.
            Ready parked = new Ready(pending.item, pending.url, pending.web != null, false);
            synchronized (ready) { ready.addFirst(parked); }
            if (pending.player != null) pending.player.release();
            if (pending.web != null) silenceAndDestroy(pending.web);
            stage.removeView(pending.rootView);
            pending = null;
        }
        entry.navigation = direction;
        synchronized (ready) { ready.addFirst(entry); }
        swapDirection = direction;
        nudge(-direction);
        wantNext = true;
        pump();
    }

    /** The bar appears on touch and fades away by itself. */
    private void showControls() {
        if (controlsBar == null || settingsPanel != null
                || !Prefs.controlsEnabled(this)) {
            return;
        }
        // Nothing to control while the feed has nothing on screen, and a bar
        // reading 0:00 over the loading face looks broken.
        if ((loadingView != null && loadingView.getVisibility() == View.VISIBLE)
                || screenIsBlank()) {
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
        controlsSource.setText(active == null ? "" : active.item.source);
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

    /**
     * The link for the clip on screen.
     *
     * Each source has its own shape of permalink and several only ever hand back
     * a stream, so a shared link is built from the source and its id rather than
     * from whatever URL happened to play. An empty link is never shared.
     */
    private String shareLink(Sources.Item item) {
        if (item == null) {
            return null;
        }
        if (Sources.YOUTUBE.equals(item.source)) {
            return "https://youtu.be/" + item.id;
        }
        if (Sources.INSTAGRAM.equals(item.source)) {
            return "https://www.instagram.com/reel/" + item.id + "/";
        }
        if (Sources.FACEBOOK.equals(item.source)) {
            return "https://www.facebook.com/reel/" + item.id + "/";
        }
        if (Sources.TIKTOK.equals(item.source)) {
            // The handle is unknown here; TikTok serves any handle for a known
            // video id, which is what the caption lookup already relies on.
            return "https://www.tiktok.com/@x/video/" + item.id;
        }
        if (Sources.PEERTUBE.equals(item.source)) {
            // The stream URL carries the instance; its watch page is what a
            // person can open.
            try {
                java.net.URI uri = java.net.URI.create(item.url);
                if (uri.getHost() != null) {
                    return uri.getScheme() + "://" + uri.getHost() + "/w/" + item.id;
                }
            } catch (Exception ignored) {
            }
            return null;
        }
        if (Sources.X.equals(item.source)) {
            // A discovered post is known by its id; this address opens it
            // without the handle, which the syndication read does not give.
            if (item.id != null && item.id.matches("[0-9]{15,20}")) {
                return "https://x.com/i/status/" + item.id;
            }
            return item.url != null && item.url.startsWith("http") ? item.url : null;
        }
        return item.url != null && item.url.startsWith("http") ? item.url : null;
    }

    private void share() {
        if (current == null) {
            return;
        }
        String link = shareLink(current);
        igTrace("share " + current.source + " " + current.id + " -> " + link);
        if (link == null || link.isEmpty()) {
            setStatus("no shareable link for this clip");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    setStatus(null);
                }
            }, 2500);
            return;
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
    private void updateRotateControl() {
        if (rotateButton == null) return;
        boolean upright = getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean wide = active != null && active.sizeKnown && active.displayRatio > 1f;
        rotateButton.setVisibility(Prefs.landscapeAllowed(this)
                && (manualRotation || (upright && wide)) ? View.VISIBLE : View.GONE);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        root.requestApplyInsets();
        updateRotateControl();
    }

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
            if (settingsButton != null) settingsButton.setVisibility(View.VISIBLE);
            if (shareButton != null) shareButton.setVisibility(View.VISIBLE);
            if (rotateButton != null) updateRotateControl();
            manualRotation = false;
            applyOrientation();
            updateRotateControl();
            Sources.setAllowLandscape(Prefs.landscapeAllowed(this));
            Sources.setLanguageFilter(Prefs.languages(this), Prefs.knownLanguageOnly(this));
            igTrace("settings close: landscape=" + Prefs.landscapeAllowed(this)
                    + " active=" + (active != null)
                    + " verified=" + (active != null && portraitVerified(active))
                    + " known=" + (active != null && active.sizeKnown)
                    + " ratio=" + (active == null ? -1 : active.displayRatio));
            synchronized (ready) { ready.removeIf(entry -> !sourceEnabled(entry.item)); }
            history.removeIf(entry -> !sourceEnabled(entry.item));
            forward.removeIf(entry -> !sourceEnabled(entry.item));
            if (pending != null && !sourceEnabled(pending.item)) rejectSlot(pending);
            if (active != null && (!sourceEnabled(active.item) || !portraitVerified(active))) {
                disposeActiveAndAdvance(active);
            }
            if (active != null && active.player != null && !paused && portraitVerified(active) && !active.rejected) {
                active.player.play();
            }
            // Newly enabled sources must not inherit the previous selection's wait.
            nextDiscoveryAt = 0;
            handler.removeCallbacks(retryDiscovery);
            pump();
            fill();
            return;
        }
        if (active != null && active.player != null) {
            active.player.pause();
        }
        settingsPanel = buildSettings();
        // The overlay buttons would sit on top of the panel's own text.
        if (settingsButton != null) settingsButton.setVisibility(View.GONE);
        if (shareButton != null) shareButton.setVisibility(View.GONE);
        if (rotateButton != null) rotateButton.setVisibility(View.GONE);
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
        foreground = false;
        syncDoomer();
        if (active != null && active.player != null) {
            active.player.pause();
        }
        // Leaving the app is the natural point to write down what has been shown.
        flushSeen();
        flushPlayed();
    }

    /**
     * Coming back resumes the clip that was on screen, unless the person had
     * paused it themselves. Without this every return left a frozen frame that
     * had to be tapped back into motion.
     */
    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        syncDoomer();
        if (active != null && active.player != null && !paused && !gateOpen
                && settingsPanel == null && !active.rejected && portraitVerified(active)) {
            active.player.play();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (doomer != null) doomer.stop();
        if (active != null) handler.removeCallbacksAndMessages(active);
        if (pending != null) handler.removeCallbacksAndMessages(pending);
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(controlsTick);
        handler.removeCallbacks(retryDiscovery);
        // Keep delayed retiring-player releases alive; pump/fill/watch stop via destroyed.
        io.shutdownNow();
        if (igResolver != null) {
            igResolver.destroy();
            igResolver = null;
        }
        if (active != null && active.web != null) silenceAndDestroy(active.web);
        if (pending != null && pending.web != null) silenceAndDestroy(pending.web);
        if (active != null && active.player != null) {
            active.player.release();
        }
        if (pending != null && pending.player != null) {
            pending.player.release();
        }
        if (ytResolver != null) {
            ytResolver.destroy();
            ytResolver = null;
        }
    }
}
