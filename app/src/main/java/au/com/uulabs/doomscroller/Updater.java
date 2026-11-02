package au.com.uulabs.doomscroller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks GitHub Releases for a newer build.
 *
 * The two distributions diverge here. `play` may only tell the person a newer
 * version exists and open the release page in a browser: downloading and
 * launching an installer is what Play Protect calls a dropper. `selfupdate`
 * does download it, because that build is distributed outside Play.
 *
 * Nothing here runs at launch more than once an hour, and any failure is
 * silent - an update check must never interrupt the feed.
 */
final class Updater {

    private static final String LATEST =
            "https://api.github.com/repos/uulabs/Doomscroller/releases/latest";
    private static final long HOUR = 60L * 60L * 1000L;

    private Updater() {
    }

    static void check(final Activity activity) {
        final long now = System.currentTimeMillis();
        long last = Prefs.lastUpdateCheck(activity);
        if (now - last < HOUR) {
            return;
        }
        Prefs.setLastUpdateCheck(activity, now);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject release = fetch(LATEST);
                    if (release == null) {
                        return;
                    }
                    final String tag = release.optString("tag_name", "");
                    final String page = release.optString("html_url", "");
                    String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    if (!newer(latest, BuildConfig.VERSION_NAME)) {
                        return;
                    }
                    final String apk = BuildConfig.SELF_UPDATE ? apkUrl(release) : null;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            offer(activity, latest, page, apk);
                        }
                    });
                } catch (Exception ignored) {
                    // An update check is never allowed to disturb playback.
                }
            }
        }).start();
    }

    private static void offer(final Activity activity, String version,
                              final String page, final String apk) {
        if (activity.isFinishing()) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle("UU Doomscroller " + version + " is available");
        b.setMessage(apk != null
                ? "Download and install it now?"
                : "Open the release page to download it?");
        b.setPositiveButton(apk != null ? "Download" : "Open", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                if (apk != null) {
                    downloadAndInstall(activity, apk);
                } else if (!page.isEmpty()) {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                }
            }
        });
        b.setNegativeButton("Later", null);
        b.show();
    }

    /** selfupdate only: fetch the APK and hand it to the system installer. */
    private static void downloadAndInstall(final Activity activity, final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(activity.getCacheDir(), "updates");
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        return;
                    }
                    File apk = new File(dir, "doomscroller.apk");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("Accept", "application/octet-stream");
                    InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(apk);
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.close();
                    in.close();
                    c.disconnect();
                    final Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            activity, activity.getPackageName() + ".updates", apk);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Intent install = new Intent(Intent.ACTION_VIEW);
                            install.setDataAndType(uri,
                                    "application/vnd.android.package-archive");
                            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(install);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private static String apkUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (name.endsWith(".apk") && name.contains("selfupdate")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static JSONObject fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) {
                return null;
            }
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static boolean newer(String candidate, String installed) {
        String[] a = candidate.split("\\.");
        String[] b = installed.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int number(String s) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else {
                break;
            }
        }
        try {
            return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
