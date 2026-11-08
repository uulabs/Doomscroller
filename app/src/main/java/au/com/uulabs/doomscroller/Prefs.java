package au.com.uulabs.doomscroller;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** The two things the settings page owns: which languages, which sources. */
public final class Prefs {

    private static final String FILE = "doomscroller";
    private static final String KEY_LANGS = "languages";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_LANDSCAPE = "allow_landscape";
    private static final String KEY_DISCLAIMER = "disclaimer_accepted";
    private static final String KEY_UPDATE_AT = "update_checked_at";
    private static final String KEY_CONTROLS = "show_controls";
    private static final String KEY_SEEN_IDS = "seen_ids";
    private static final String KEY_BAG = "bag_";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Set<String> languages(Context c) {
        Set<String> s = sp(c).getStringSet(KEY_LANGS, null);
        if (s == null || s.isEmpty()) {
            s = new LinkedHashSet<String>();
            s.add("en");
        }
        return new LinkedHashSet<String>(s);
    }

    public static void setLanguages(Context c, Set<String> langs) {
        Set<String> copy = new LinkedHashSet<String>(langs);
        if (copy.isEmpty()) {
            copy.add("en");
        }
        sp(c).edit().putStringSet(KEY_LANGS, copy).apply();
    }

    /**
     * X and PeerTube are opt-in on new installs. Existing choices are retained.
     */
    private static Set<String> defaultSources() {
        Set<String> out = new LinkedHashSet<String>();
        for (String source : Sources.ALL) {
            if (!Sources.PEERTUBE.equals(source) && !Sources.X.equals(source)) {
                out.add(source);
            }
        }
        return out;
    }

    /** Debug only: feed one known landscape stream, to test the portrait gate. */
    public static boolean probeLandscape(Context c) {
        return sp(c).getBoolean("probe_landscape", false);
    }

    /** Whether a touch brings up the playback bar. */
    public static boolean controlsEnabled(Context c) {
        return sp(c).getBoolean(KEY_CONTROLS, true);
    }

    public static void setControlsEnabled(Context c, boolean enabled) {
        sp(c).edit().putBoolean(KEY_CONTROLS, enabled).apply();
    }

    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong(KEY_UPDATE_AT, 0L);
    }

    public static void setLastUpdateCheck(Context c, long at) {
        sp(c).edit().putLong(KEY_UPDATE_AT, at).apply();
    }

    public static boolean disclaimerAccepted(Context c) {
        return sp(c).getBoolean(KEY_DISCLAIMER, false);
    }

    public static void setDisclaimerAccepted(Context c, boolean accepted) {
        sp(c).edit().putBoolean(KEY_DISCLAIMER, accepted).apply();
    }

    /**
     * Whether a clip whose language cannot be told is skipped. Off, such a
     * clip is allowed through, which keeps music-only clips but lets some
     * foreign ones in; on, only clips with a clear language signal play.
     */
    public static boolean knownLanguageOnly(Context c) {
        return sp(c).getBoolean("known_language_only", false);
    }

    public static void setKnownLanguageOnly(Context c, boolean on) {
        sp(c).edit().putBoolean("known_language_only", on).apply();
    }

    /** Random letters in place of a subject from the bundled word lists. */
    public static boolean randomLetters(Context c) {
        return sp(c).getBoolean("random_letters", false);
    }

    public static void setRandomLetters(Context c, boolean on) {
        sp(c).edit().putBoolean("random_letters", on).apply();
    }

    /** Landscape clips are letterboxed rather than cropped, and only if allowed. */
    public static boolean landscapeAllowed(Context c) {
        return sp(c).getBoolean(KEY_LANDSCAPE, false);
    }

    public static void setLandscapeAllowed(Context c, boolean allowed) {
        sp(c).edit().putBoolean(KEY_LANDSCAPE, allowed).apply();
    }

    public static Set<String> sources(Context c) {
        SharedPreferences prefs = sp(c);
        // Defaults apply only when no source selection has been saved.
        Set<String> s = prefs.getStringSet(KEY_SOURCES, null);
        if (s == null) {
            s = defaultSources();
        }
        return new LinkedHashSet<String>(s);
    }

    public static void setSources(Context c, Set<String> srcs) {
        Set<String> copy = new LinkedHashSet<String>(srcs);
        if (copy.isEmpty()) {
            copy.add(Sources.YOUTUBE);
        }
        // An explicit choice is current by definition and is never migrated over.
        sp(c).edit().putStringSet(KEY_SOURCES, copy).apply();
    }

    /**
     * Videos that have already played, across restarts.
     *
     * They used to live in a HashSet on the activity, so quitting the app forgot every clip and
     * the same handful came round again next launch - which is what "the same video twice" looked
     * like from the outside.
     */
    /**
     * A random value fixed at install. Two phones running the same build then
     * differ in which results they reach rather than marching in step.
     */
    public static long installSalt(Context c) {
        long salt = sp(c).getLong("install_salt", 0L);
        if (salt == 0L) {
            salt = new java.util.Random().nextLong();
            sp(c).edit().putLong("install_salt", salt).apply();
        }
        return salt;
    }

    public static String playedIds(Context c) {
        return sp(c).getString("played_ids", "");
    }

    public static void setPlayedIds(Context c, String csv) {
        sp(c).edit().putString("played_ids", csv).apply();
    }

    public static String seenIds(Context c) {
        return sp(c).getString(KEY_SEEN_IDS, "");
    }

    public static void setSeenIds(Context c, String csv) {
        sp(c).edit().putString(KEY_SEEN_IDS, csv).apply();
    }

    /** What is left of a language's subject words before the bag is refilled and shuffled. */
    public static String wordBag(Context c, String lang) {
        return sp(c).getString(KEY_BAG + lang, "");
    }

    public static void setWordBag(Context c, String lang, String csv) {
        sp(c).edit().putString(KEY_BAG + lang, csv).apply();
    }
}
