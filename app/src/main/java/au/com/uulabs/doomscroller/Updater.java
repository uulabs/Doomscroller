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
        check(activity, false);
    }

    /** A deliberate check skips the hourly throttle and always reports a result. */
    static void check(final Activity activity, final boolean force) {
        final long now = System.currentTimeMillis();
        long last = Prefs.lastUpdateCheck(activity);
        if (!force && now - last < HOUR) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject release = fetch(LATEST);
                    if (release == null) {
                        return;   // offline: the next launch tries again
                    }
                    // Stamped only once an answer arrived, so a launch without
                    // a network does not silence the check for an hour.
                    Prefs.setLastUpdateCheck(activity, now);
                    final String tag = release.optString("tag_name", "");
                    final String page = release.optString("html_url", "");
                    String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    if (!newer(latest, BuildConfig.VERSION_NAME)) {
                        if (force) {
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    upToDate(activity);
                                }
                            });
                        }
                        return;
                    }
                    final String[] apk = BuildConfig.SELF_UPDATE ? apkUrl(release) : null;
                    final String[] sums = BuildConfig.SELF_UPDATE ? assetUrl(release, "SHA256SUMS") : null;
                    final String notes = notes(release.optString("body", ""));
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            offer(activity, latest, page, apk, sums, notes);
                        }
                    });
                } catch (Exception ignored) {
                    // An update check is never allowed to disturb playback.
                }
            }
        }).start();
    }

    private static void upToDate(final Activity activity) {
        if (activity.isFinishing()) {
            return;
        }
        AppDialog.show(activity, "Up to date",
                "You are running " + BuildConfig.VERSION_NAME
                        + ", the latest published version.",
                "OK", null, null);
    }

    /** What changed, as the release describes it, cut down to fit a dialog. */
    private static String notes(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("|")
                    || line.startsWith("---") || line.startsWith("```")
                    || line.toLowerCase().startsWith("which file")
                    || line.toLowerCase().startsWith("verify")
                    || line.toLowerCase().startsWith("to check a download")) {
                if (line.startsWith("#") && kept.length() > 0) {
                    break;   // stop at the next section
                }
                continue;
            }
            kept.append(line).append('\n');
            if (kept.length() > 420) {
                break;
            }
        }
        String text = kept.toString().trim();
        // Keep the bullets on their own lines; only tidy the whitespace inside.
        text = text.replaceAll("[ \t]+", " ").replaceAll("\n{3,}", "\n\n");
        return text.length() > 420 ? text.substring(0, 417) + "..." : text;
    }

    private static void offer(final Activity activity, String version,
                              final String page, final String[] apk, final String[] sums,
                              final String notes) {
        if (activity.isFinishing()) {
            return;
        }
        String lead = apk != null
                ? "Download and install it now?"
                : "Open the release page to download it?";
        String body = notes.isEmpty() ? lead : notes + "\n\n" + lead;
        AppDialog.show(activity, "UU Doomscroller " + version, body,
                apk != null ? "Download" : "Open", new Runnable() {
                    @Override
                    public void run() {
                        if (apk != null) {
                            downloadAndInstall(activity, apk, sums, page);
                        } else if (!page.isEmpty()) {
                            activity.startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                        }
                    }
                }, "Later");
    }

    /**
     * selfupdate only: fetch the APK, check it against the SHA256SUMS the same
     * release publishes, and hand it to the system installer. Nothing is
     * installed that does not match, and a failure is reported rather than
     * leaving a tapped Download button that did nothing.
     */
    private static void downloadAndInstall(final Activity activity, final String[] url,
                                           final String[] sums, final String page) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure;
                try {
                    File dir = new File(activity.getCacheDir(), "updates");
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new java.io.IOException("no cache directory");
                    }
                    File apk = new File(dir, "doomscroller.apk");
                    String expected = expectedHash(sums, url);
                    if (expected == null) {
                        throw new java.io.IOException(sums == null
                                ? "no published checksum" : "the checksum list could not be fetched");
                    }
                    String actual = download(url, apk);
                    if (!expected.equalsIgnoreCase(actual)) {
                        apk.delete();
                        throw new java.io.IOException("checksum mismatch");
                    }
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
                    return;
                } catch (Exception e) {
                    failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final String why = failure;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing()) {
                            return;
                        }
                        AppDialog.show(activity, "Update not installed",
                                "The download could not be completed or verified ("
                                        + why + "). You can get it from the release page instead.",
                                page == null || page.isEmpty() ? "OK" : "Open page",
                                page == null || page.isEmpty() ? null : new Runnable() {
                                    @Override
                                    public void run() {
                                        activity.startActivity(
                                                new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                                    }
                                }, page == null || page.isEmpty() ? null : "Later");
                    }
                });
            }
        }).start();
    }

    /** Streams the file to disk and returns its SHA-256 as lower-case hex. */
    private static String download(String[] urls, File target) throws Exception {
        HttpURLConnection c = open(urls);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        } finally {
            c.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** The hash SHA256SUMS lists for the file the APK is named after, or null. */
    private static String expectedHash(String[] sumsUrls, String[] apkUrls) {
        if (sumsUrls == null || apkUrls == null) {
            return null;
        }
        String apkUrl = apkUrls[apkUrls.length - 1];
        String name = apkUrl.substring(apkUrl.lastIndexOf('/') + 1);
        HttpURLConnection c = null;
        try {
            c = open(sumsUrls);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[0].length() == 64
                        && parts[parts.length - 1].replace("*", "").equals(name)) {
                    return parts[0];
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * Opens the first address that answers. A release asset has two: the API
     * address, which hands the file over from GitHub's object store, and the
     * web address, whose front end has been seen refusing every file for a
     * while. Either one alone has failed; together they have not.
     */
    private static HttpURLConnection open(String[] urls) throws java.io.IOException {
        java.io.IOException last = null;
        for (String url : urls) {
            if (url == null || url.isEmpty()) {
                continue;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setInstanceFollowRedirects(true);
            try {
                int code = c.getResponseCode();
                if (code == 200) {
                    return c;
                }
                last = new java.io.IOException("HTTP " + code);
            } catch (java.io.IOException e) {
                last = e;
            }
            c.disconnect();
        }
        throw last == null ? new java.io.IOException("no download address") : last;
    }

    /** The download addresses of the release asset with this exact name, or null. */
    private static String[] assetUrl(JSONObject release, String wanted) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && wanted.equals(asset.optString("name", ""))) {
                return addresses(asset);
            }
        }
        return null;
    }

    /** API address first, web address last; the file's name is read from the last. */
    private static String[] addresses(JSONObject asset) {
        return new String[]{asset.optString("url", null), asset.optString("browser_download_url", null)};
    }

    /**
     * The self-updating build is preferred. The release also carries a build
     * that installs nothing by itself, and updating into that one would quietly
     * take the updater away, so it is only used if nothing else is there.
     */
    private static String[] apkUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        String[] fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (!name.endsWith(".apk")) {
                continue;
            }
            String[] url = addresses(asset);
            if (name.contains("no-self-update")) {
                if (fallback == null) {
                    fallback = url;
                }
                continue;
            }
            return url;
        }
        return fallback;
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
