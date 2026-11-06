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
 * TopicSampler draws languages and families separately, so a larger vocabulary
 * does not give one language more discovery attempts.
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
    private static final Map<String, String> FAMILY_QUERIES = new HashMap<>();

    private Vocab() {
    }

    /** Reads every subject file once. Safe to call more than once. */
    public static synchronized void load(AssetManager assets) {
        if (loaded) {
            return;
        }
        loaded = true;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                assets.open("topic-families.tsv"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\\t", 3);
                if (fields.length == 3) FAMILY_QUERIES.put(fields[0]+"|"+fields[1], fields[2]);
            }
        } catch (java.io.IOException missing) { /* Existing detailed terms still work. */ }

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

    public static String familyQuery(TopicSampler.Topic topic) {
        String query = FAMILY_QUERIES.get(topic.language + "|" + topic.domain);
        return query == null ? topic.query : query;
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

    /** Bundled labelled concepts plus a small lane for the original local vocabulary. */
    public static List<TopicSampler.Topic> topics(AssetManager assets) {
        List<TopicSampler.Topic> out = new ArrayList<>();
        for (Lang lang : ALL) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    assets.open("topics/" + lang.code + ".tsv"), "UTF-8"))) {
                String line;
                while ((line=r.readLine()) != null) {
                    if(line.startsWith("#"))continue;
                    String[] p=line.split("\\t",3);
                    if(p.length==3)out.add(new TopicSampler.Topic(lang.code,p[0],p[1],p[2]));
                }
            } catch (java.io.IOException e) { throw new IllegalStateException("Missing topics: "+lang.code,e); }
            List<String> words=WORDS.get(lang.code);
            if(words!=null)for(String word:words)out.add(new TopicSampler.Topic(
                    lang.code,"local-discovery","local-"+lang.code+"-"+word,word));
        }
        return out;
    }

    /** How many subjects this language carries, for documentation and tests. */
    public static int count(String code) {
        List<String> held = WORDS.get(code);
        return held == null ? 0 : held.size();
    }

    private static final String[][] LATIN_MARKERS = {
        {"es", " el la los las que de del y en un una para con por es su sus más pero como muy al se no lo" },
        {"pt", " que não uma para com isso dos das um uma você mais como mas ao se na no por sua seu" },
        {"fr", " le la les des que est une un pour avec dans sur pas plus au ce cette il elle nous vous" },
        {"de", " der die das und ist nicht ein eine mit für auf sich dem den des auch wie bei aus" },
        {"it", " il la che di un una per con non sono questo questa più anche come ma del della" },
        {"id", " yang dan di ini itu dengan untuk tidak dari ke pada saya kami mereka akan sudah ada" },
    };

    private static final String[] ENGLISH_MARKERS = ("the and of to is in you that it for on with "
            + "this are was but not have from they his her what about just").split(" ");

    /**
     * Guesses a Latin-script language from its most common words. Two distinct
     * markers of one language, and more of them than English has, is required:
     * a stray foreign word in an English caption is not a foreign caption.
     */
    public static String detectLatin(String text) {
        if (text == null || text.length() < 12) {
            return null;
        }
        String padded = " " + text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L} ]", " ").replaceAll("\\s+", " ") + " ";
        int english = 0;
        for (String w : ENGLISH_MARKERS) {
            if (padded.contains(" " + w + " ")) {
                english++;
            }
        }
        String best = null;
        int bestHits = 0;
        for (String[] lang : LATIN_MARKERS) {
            int hits = 0;
            for (String w : lang[1].trim().split(" ")) {
                if (padded.contains(" " + w + " ")) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = lang[0];
            }
        }
        if (bestHits >= 2 && bestHits > english) {
            return best;
        }
        // Short captions often carry one clear word and nothing else. When no
        // English marker is present at all, one of these is good evidence.
        if (english == 0 && best != null) {
            for (String strong : STRONG) {
                String[] parts = strong.split(":");
                if (parts[0].equals(best) && padded.contains(" " + parts[1] + " ")) {
                    return best;
                }
            }
        }
        if (english == 0) {
            return byLetters(padded);
        }
        return null;
    }

    /**
     * Letters that English never uses and that belong to one language, or to
     * languages the app does not offer. Two of a kind in a caption with no
     * English marker at all settle it; letters shared by several languages
     * (é, ü, ç) are left alone.
     */
    static String byLetters(String lower) {
        int es = 0, pt = 0, de = 0, tr = 0, other = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case 'ñ': es++; break;
                case 'ã': case 'õ': pt++; break;
                case 'ß': de++; break;
                case 'ğ': case 'ı': case 'ş': tr++; break;
                // Polish, Czech, Slovak, Romanian, Hungarian, Nordic, Vietnamese.
                case 'ł': case 'ż': case 'ś': case 'ę': case 'ą': case 'ź': case 'ć': case 'ń':
                case 'ř': case 'ě': case 'ů': case 'ď': case 'ť': case 'ň': case 'ľ': case 'ĺ':
                case 'ț': case 'ș': case 'ő': case 'ű':
                case 'ø': case 'å': case 'æ': case 'ð': case 'þ':
                case 'đ': case 'ơ': case 'ư': case 'ạ': case 'ả': case 'ấ': case 'ầ': case 'ậ':
                case 'ế': case 'ề': case 'ệ': case 'ố': case 'ồ': case 'ộ': case 'ớ': case 'ờ':
                case 'ợ': case 'ứ': case 'ừ': case 'ự': case 'ỉ': case 'ị': case 'ỏ': case 'ọ':
                case 'ủ': case 'ụ': case 'ỹ': case 'ỵ': case 'ỷ': case 'ỳ': case 'ẽ': case 'ẫ':
                case 'ẩ': case 'ẳ': case 'ẵ': case 'ặ': case 'ắ': case 'ằ': case 'ĩ': case 'ũ':
                case 'ữ': case 'ử': case 'ổ': case 'ỗ': case 'ỡ': case 'ở':
                    other++; break;
                default: break;
            }
        }
        if (other >= 2 && other >= es + pt + de + tr) return OTHER;
        if (es >= 2 && pt + de + tr == 0) return "es";
        if (pt >= 2 && es + de + tr == 0) return "pt";
        if (de >= 2 && es + pt + tr == 0) return "de";
        if (tr >= 2 && es + pt + de == 0) return "tr";
        return null;
    }

    /** Words that rarely appear in English, so one alone settles a short caption. */
    private static final String[] STRONG = {
        "es:que", "es:para", "es:los", "es:las", "es:una",
        "pt:não", "pt:uma", "pt:muito", "pt:isso",
        "fr:les", "fr:une", "fr:avec", "fr:pour", "fr:très",
        "de:und", "de:nicht", "de:eine", "de:auch",
        "it:che", "it:non", "it:della", "it:sono",
        "id:yang", "id:dan", "id:untuk", "id:tidak", "id:dengan",
        "tr:bir", "tr:için", "tr:değil",
        "nl:het", "nl:niet", "nl:voor",
        "pl:się", "pl:jest", "pl:nie",
        "vi:của", "vi:không", "vi:những",
        "tl:ang", "tl:mga", "tl:hindi",
    };

    /** Latin-script stops, enough to separate the common European languages. */
    private static final String[][] LATIN_STOPS = {
        {"en", "the and with this that from your are is"},
        {"es", "el la los las que del una por con para esto esta muy pero como"},
        {"pt", "não uma dos das que com para isso muito mas como quando"},
        {"fr", "les des une est dans pour avec mais très cette nous vous"},
        {"de", "der die und das ist nicht mit ein eine auch sich wird"},
        {"it", "che non una dei della sono molto anche questo gli"},
        {"id", "yang dan ini itu dengan untuk tidak dari akan"},
        {"tr", "bir ve bu için ile çok daha olarak değil"},
        {"nl", "het een van dat niet met voor zijn maar ook nog wel"},
        {"pl", "nie i się jest na to jak ale po tak bardzo"},
        {"vi", "của và là không có được người những cho với"},
        {"tl", "ang ng mga sa na hindi ito para kaso ako siya"},
    };

    /** Returned by detect() for a script that matches none of the offered languages. */
    public static final String OTHER = "other";

    /** Letters of scripts the app has no language for; digits and punctuation are not counted. */
    private static boolean isUnofferedScript(char c) {
        return (c >= 0x0370 && c <= 0x03FF)      // Greek
                || (c >= 0x0530 && c <= 0x058F)  // Armenian
                || (c >= 0x0590 && c <= 0x05FF)  // Hebrew
                || (c >= 0x0980 && c <= 0x0DFF)  // Bengali through Sinhala
                || (c >= 0x0E80 && c <= 0x0EFF)  // Lao
                || (c >= 0x0F00 && c <= 0x0FFF)  // Tibetan
                || (c >= 0x1000 && c <= 0x109F)  // Myanmar
                || (c >= 0x10A0 && c <= 0x10FF)  // Georgian
                || (c >= 0x1200 && c <= 0x137F)  // Ethiopic
                || (c >= 0x1780 && c <= 0x17FF)  // Khmer
                || (c >= 0x1800 && c <= 0x18AF); // Mongolian
    }

    /**
     * Best-effort language of a title. Script is decisive where it is
     * unambiguous; for Latin text a small stop-word vote is used, leaving uncertain results
     * unknown. This is a filter, not a classifier - an item is only rejected
     * when the answer is clear.
     */
    public static String detect(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int kana = 0, hangul = 0, han = 0, arabic = 0, deva = 0, cyr = 0, thai = 0, latin = 0;
        int other = 0;   // a script none of the offered languages is written in
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isUnofferedScript(c)) {
                other++;
            } else if (c >= 0x3040 && c <= 0x30FF) {
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
        // A few kana or hangul at the end of a long Latin caption are tag
        // decoration, not the language of the clip; they decide only when they
        // are a real share of the letters.
        if (kana > 0 && kana * 4 >= latin) {
            return "ja";
        }
        if (hangul > 0 && hangul * 4 >= latin) {
            return "ko";
        }
        if (han > latin * 2) {
            // Kana was absent, so this is not written Japanese. Treating it as
            // unknown let Chinese clips through an English-only filter.
            return "zh";
        }
        if (han > latin) {
            return null;
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
        if (other > latin) {
            // Bengali, Tamil, Hebrew, Greek and the like: whatever this is, it
            // cannot be one of the fifteen languages a person can enable.
            return OTHER;
        }
        if (latin == 0) {
            return null;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String[] tokens = lower.split("[^\\p{L}]+");
        String best = null;
        int runnerUp = 0;
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
                runnerUp = bestScore;
                bestScore = score;
                best = row[0];
            } else if (score > runnerUp) { runnerUp = score; }
        }
        return bestScore >= 2 && bestScore > runnerUp ? best : null;
    }
}
