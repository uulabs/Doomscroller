package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Brings the feed back after the main process had to end itself.
 *
 * It runs in its own process so that it outlives the one being replaced:
 * the stalled process starts this, then ends, and this starts the feed
 * afresh and leaves. See Main's stall watchdog for why that is needed.
 */
public final class Restart extends Activity {
    static final String EXTRA_RECOVERED = "au.com.uulabs.doomscroller.RECOVERED";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent feed = new Intent(this, Main.class);
        feed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        feed.putExtra(EXTRA_RECOVERED, true);
        startActivity(feed);
        finish();
        overridePendingTransition(0, 0);
        Runtime.getRuntime().exit(0);
    }
}
