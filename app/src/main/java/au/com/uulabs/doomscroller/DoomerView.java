package au.com.uulabs.doomscroller;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Random;

/**
 * The doomer face while the feed is loading: it breathes, blinks, sighs after
 * a while and eventually sheds a tear. Everything is plain view animation over
 * the one drawing plus a closed-eyes overlay; nothing runs while the face is
 * hidden, so a playing clip costs nothing extra.
 */
final class DoomerView extends FrameLayout {
    private static final String[] PLAIN = {"loading", "finding more"};

    private final ImageView face;
    private final ImageView lids;
    private final Tear tear;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private TextView label;
    private String base = "loading";
    private boolean running;
    private long startedAt;
    private ObjectAnimator breathing;
    private Animator sigh;
    private ValueAnimator tearDrop;

    DoomerView(Context context) {
        super(context);
        face = new ImageView(context);
        face.setImageResource(R.drawable.doomer);
        addView(face, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        lids = new ImageView(context);
        lids.setImageResource(R.drawable.doomer_blink);
        lids.setVisibility(INVISIBLE);
        addView(lids, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        tear = new Tear(context);
        tear.setVisibility(INVISIBLE);
        addView(tear, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** The caption under the face; the view keeps it in step with how long the wait has been. */
    void attachLabel(TextView label) {
        this.label = label;
    }

    /** Sets the caption. Plain waits get bleaker as time passes; anything else is shown as given. */
    void say(String text) {
        base = text;
        if (label != null) label.setText(phrase());
    }

    private String phrase() {
        boolean plain = false;
        for (String p : PLAIN) if (p.equals(base)) plain = true;
        if (!plain || !running) return base;
        long waited = System.currentTimeMillis() - startedAt;
        if (waited < 8000) return base;
        if (waited < 20000) return "still " + base;
        if (waited < 40000) return "this is fine";
        return "it is what it is";
    }

    void start() {
        if (running) return;
        running = true;
        startedAt = System.currentTimeMillis();
        breathing = ObjectAnimator.ofPropertyValuesHolder(face,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.015f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.015f));
        breathing.setDuration(2100);
        breathing.setRepeatMode(ValueAnimator.REVERSE);
        breathing.setRepeatCount(ValueAnimator.INFINITE);
        breathing.setInterpolator(new AccelerateDecelerateInterpolator());
        breathing.start();
        handler.postDelayed(blink, 1200 + random.nextInt(600));
        handler.postDelayed(sighTick, 6000);
        handler.postDelayed(tearTick, 12000);
        handler.postDelayed(labelTick, 8000);
    }

    void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (breathing != null) breathing.cancel();
        if (sigh != null) sigh.cancel();
        if (tearDrop != null) tearDrop.cancel();
        face.setScaleX(1f);
        face.setScaleY(1f);
        setScaleX(1f);
        setScaleY(1f);
        setRotation(0f);
        lids.setVisibility(INVISIBLE);
        tear.setVisibility(INVISIBLE);
        if (label != null) label.setText(base);
    }

    private final Runnable blink = new Runnable() {
        @Override public void run() {
            if (!running) return;
            lids.setVisibility(VISIBLE);
            handler.postDelayed(unblink, 110);
        }
    };

    private final Runnable unblink = new Runnable() {
        @Override public void run() {
            if (!running) return;
            lids.setVisibility(INVISIBLE);
            // Mostly a single blink; now and then a quick double.
            int next = random.nextInt(5) == 0 ? 180 : 2600 + random.nextInt(3200);
            handler.postDelayed(blink, next);
        }
    };

    /**
     * A sigh: a quick breath in that lifts the shoulders, eyes closing on the
     * long breath out as he slumps below where he started, then a slow settle.
     * Everything scales from the bottom edge so nothing floats.
     */
    private final Runnable sighTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight());
            ObjectAnimator in = ObjectAnimator.ofPropertyValuesHolder(DoomerView.this,
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.012f));
            in.setDuration(650);
            in.setInterpolator(new DecelerateInterpolator(1.6f));
            final ObjectAnimator out = ObjectAnimator.ofPropertyValuesHolder(DoomerView.this,
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.04f, 0.975f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1.012f, 0.995f),
                    PropertyValuesHolder.ofFloat(View.ROTATION, 0f, -2.2f));
            out.setDuration(1100);
            out.setInterpolator(new AccelerateDecelerateInterpolator());
            final ObjectAnimator settle = ObjectAnimator.ofPropertyValuesHolder(DoomerView.this,
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.975f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.995f, 1f),
                    PropertyValuesHolder.ofFloat(View.ROTATION, -2.2f, 0f));
            settle.setDuration(900);
            settle.setInterpolator(new DecelerateInterpolator());
            in.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (!running) return;
                    handler.removeCallbacks(blink);
                    handler.removeCallbacks(unblink);
                    lids.setVisibility(VISIBLE);
                    sigh = out;
                    out.start();
                }
            });
            out.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (!running) return;
                    sigh = settle;
                    settle.start();
                    // Eyes stay shut a moment into the settle, then normal blinking resumes.
                    handler.postDelayed(unblink, 350);
                }
            });
            sigh = in;
            in.start();
            handler.postDelayed(this, 11000 + random.nextInt(6000));
        }
    };

    private final Runnable tearTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            tearDrop = ValueAnimator.ofFloat(0f, 1f);
            tearDrop.setDuration(2600);
            tearDrop.setInterpolator(new AccelerateInterpolator(1.2f));
            tearDrop.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    tear.setProgress((Float) a.getAnimatedValue());
                }
            });
            tearDrop.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { tear.setVisibility(INVISIBLE); }
            });
            tear.setVisibility(VISIBLE);
            tearDrop.start();
            handler.postDelayed(this, 16000 + random.nextInt(9000));
        }
    };

    private final Runnable labelTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (label != null) label.setText(phrase());
            handler.postDelayed(this, 2000);
        }
    };

    /** One tear running down from the left eye bag, drawn in the drawing's own coordinates. */
    private static final class Tear extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path drop = new Path();
        private float progress;

        Tear(Context context) {
            super(context);
            fill.setColor(0xE6A9C2E4);
            shine.setColor(0xC0FFFFFF);
            ink.setColor(0xFF1C1B1E);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
        }

        void setProgress(float p) {
            progress = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float s = getWidth() / 600f;
            // Starts under the inner corner of the eye bag and follows the cheek.
            float x = (286f - 14f * progress) * s;
            float y = (335f + 150f * progress) * s;
            float r = (3.6f + 1.2f * progress) * s;
            float alpha = progress < 0.85f ? 1f : (1f - progress) / 0.15f;
            fill.setAlpha((int) (0xE6 * alpha));
            shine.setAlpha((int) (0xC0 * alpha));
            ink.setAlpha((int) (0xFF * alpha));
            ink.setStrokeWidth(Math.max(1.5f, 2.2f * s));
            drop.reset();
            drop.moveTo(x, y - 2.6f * r);
            drop.quadTo(x + 1.3f * r, y - 0.3f * r, x + r, y + 0.4f * r);
            drop.arcTo(x - r, y - 0.6f * r, x + r, y + 1.4f * r, 0f, 180f, false);
            drop.quadTo(x - 1.3f * r, y - 0.3f * r, x, y - 2.6f * r);
            drop.close();
            canvas.drawPath(drop, fill);
            canvas.drawPath(drop, ink);
            canvas.drawCircle(x - 0.35f * r, y + 0.15f * r, 0.3f * r, shine);
        }
    }
}
