package au.com.uulabs.doomscroller;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The subjects the feed may draw from.
 *
 * The words live in asset files, one subject per line, so the vocabulary can
 * grow without touching code. They are vocabulary, not content: a subject
 * decides only what gets *asked for*, never what comes back, and nothing here
 * lists videos, channels or creators.
 *
 * A draw takes a word from the whole pool of the enabled languages, so a
 * language with a longer list contributes proportionally more.
 */
public final class Vocab {

    public static final class Lang {
        public final String code;
        public final String name;

        Lang(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    public static final Lang[] ALL = {
        new Lang("en", "English"),
        new Lang("ja", "Japanese"),
        new Lang("zh", "Chinese"),
        new Lang("ko", "Korean"),
        new Lang("es", "Spanish"),
        new Lang("pt", "Portuguese"),
        new Lang("fr", "French"),
        new Lang("de", "German"),
        new Lang("it", "Italian"),
        new Lang("ru", "Russian"),
        new Lang("ar", "Arabic"),
        new Lang("hi", "Hindi"),
        new Lang("id", "Indonesian"),
        new Lang("tr", "Turkish"),
        new Lang("th", "Thai"),
    };

    private static final Map<String, List<String>> WORDS =
            new HashMap<String, List<String>>();
    private static boolean loaded;

    private Vocab() {
    }

    /** Reads every subject file once. Safe to call more than once. */
    public static synchronized void load(AssetManager assets) {
        if (loaded) {
            return;
        }
        loaded = true;
        for (Lang lang : ALL) {
            WORDS.put(lang.code, read(assets, lang.code));
        }
    }

    private static List<String> read(AssetManager assets, String code) {
        List<String> words = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    assets.open("subjects/" + code + ".txt"), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    words.add(line);
                }
            }
        } catch (Exception ignored) {
            // A missing file simply contributes no subjects.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return words;
    }

    public static Lang byCode(String code) {
        for (Lang lang : ALL) {
            if (lang.code.equals(code)) {
                return lang;
            }
        }
        return null;
    }

    /** Every subject of every enabled language, as one pool. */
    public static List<String> pool(Set<String> enabled) {
        List<String> words = new ArrayList<String>();
        for (Lang lang : ALL) {
            if (enabled.contains(lang.code)) {
                List<String> held = WORDS.get(lang.code);
                if (held != null) {
                    words.addAll(held);
                }
            }
        }
        return words;
    }

    /** How many subjects this language carries, for documentation and tests. */
    public static int count(String code) {
        List<String> held = WORDS.get(code);
        return held == null ? 0 : held.size();
    }

    /** Latin-script stops, enough to separate the common European languages. */
    private static final String[][] LATIN_STOPS = {
        {"es", "el la los las que del una por con para esto esta muy pero como"},
        {"pt", "não uma dos das que com para isso muito mas como quando"},
        {"fr", "les des une est dans pour avec mais très cette nous vous"},
        {"de", "der die und das ist nicht mit ein eine auch sich wird"},
        {"it", "che non una dei della sono molto anche questo gli"},
        {"id", "yang dan ini itu dengan untuk tidak dari akan"},
        {"tr", "bir ve bu için ile çok daha olarak değil"},
    };

    /**
     * Best-effort language of a title. Script is decisive where it is
     * unambiguous; for Latin text a small stop-word vote is used, defaulting to
     * English. This is a filter, not a classifier - an item is only rejected
     * when the answer is clear.
     */
    public static String detect(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int kana = 0, hangul = 0, han = 0, arabic = 0, deva = 0, cyr = 0, thai = 0, latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3040 && c <= 0x30FF) {
                kana++;
            } else if (c >= 0xAC00 && c <= 0xD7AF) {
                hangul++;
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                han++;
            } else if (c >= 0x0600 && c <= 0x06FF) {
                arabic++;
            } else if (c >= 0x0900 && c <= 0x097F) {
                deva++;
            } else if (c >= 0x0400 && c <= 0x04FF) {
                cyr++;
            } else if (c >= 0x0E00 && c <= 0x0E7F) {
                thai++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        if (kana > 0) {
            return "ja";
        }
        if (hangul > 0) {
            return "ko";
        }
        if (han > latin) {
            return "zh";
        }
        if (arabic > latin) {
            return "ar";
        }
        if (deva > latin) {
            return "hi";
        }
        if (cyr > latin) {
            return "ru";
        }
        if (thai > latin) {
            return "th";
        }
        if (latin == 0) {
            return null;
        }
        String lower = text.toLowerCase();
        String[] tokens = lower.split("[^\\p{L}]+");
        String best = "en";
        int bestScore = 0;
        for (String[] row : LATIN_STOPS) {
            String[] stops = row[1].split(" ");
            int score = 0;
            for (String t : tokens) {
                for (String s : stops) {
                    if (t.equals(s)) {
                        score++;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = row[0];
            }
        }
        return best;
    }
}
