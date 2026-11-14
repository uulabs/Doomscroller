package au.com.uulabs.doomscroller;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

/**
 * Remembers, in the helper process, whether the feed was on screen.
 *
 * The feed tells it each time it comes to the front or leaves. If the feed's
 * process then dies without having said it left, {@link Shell}, which shares
 * this process and sits underneath the feed, knows the feed crashed rather
 * than was closed, and starts it again.
 */
public final class Keeper extends Service {
    static final int MSG_FOREGROUND = 2;  // arg1: 1 while the feed is on screen

    /** Last thing the feed said about itself. Read by Shell on the same main thread. */
    static volatile boolean feedOnScreen;

    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            if (m.what == MSG_FOREGROUND) feedOnScreen = m.arg1 == 1;
        }
    });

    @Override
    public IBinder onBind(Intent intent) {
        return inbox.getBinder();
    }
}
