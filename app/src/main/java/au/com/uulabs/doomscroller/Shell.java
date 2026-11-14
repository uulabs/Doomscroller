package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * An empty screen that sits underneath the feed, in the helper process.
 *
 * Some graphics drivers crash the feed's process while the system WebView
 * draws a video frame (see Main's stall watchdog). A process cannot catch
 * its own native crash, and Android does not let a background process open
 * a screen, so nothing could bring the feed back from outside. But when the
 * top screen's process dies, Android returns to the screen beneath it. This
 * is that screen: it lives in a process the crash does not touch, and when
 * it finds itself in front without the feed having said goodbye, it starts
 * the feed again. When the feed was closed normally, it simply leaves too.
 */
public final class Shell extends Activity {
    private static final long RELAUNCH_GAP_MS = 60_000L;
    private static long lastRelaunchAt;

    /** True from starting the feed until it has covered this screen. */
    private boolean awaitingFeed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) {
            startFeed(getIntent() != null && getIntent().getBooleanExtra(Restart.EXTRA_RECOVERED, false));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        awaitingFeed = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingFeed) return;
        long now = System.currentTimeMillis();
        if (Keeper.feedOnScreen && now - lastRelaunchAt >= RELAUNCH_GAP_MS) {
            lastRelaunchAt = now;
            Log.w("Doom", "feed process died on screen, starting it again");
            startFeed(true);
            return;
        }
        if (Keeper.feedOnScreen) Log.w("Doom", "feed died again within a minute, leaving it closed");
        Keeper.feedOnScreen = false;
        finish();
        overridePendingTransition(0, 0);
    }

    private void startFeed(boolean recovered) {
        awaitingFeed = true;
        Keeper.feedOnScreen = false;
        Intent feed = new Intent(this, Main.class);
        feed.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        feed.putExtra(Main.EXTRA_VIA_SHELL, true);
        if (recovered) feed.putExtra(Restart.EXTRA_RECOVERED, true);
        startActivity(feed);
        overridePendingTransition(0, 0);
    }
}
