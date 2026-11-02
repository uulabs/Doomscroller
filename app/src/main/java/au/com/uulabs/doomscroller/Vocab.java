package au.com.uulabs.doomscroller;

/**
 * The languages the feed may draw from, and the words each one contributes as
 * random subjects.
 *
 * A language setting does two jobs: it decides which vocabulary a subject is
 * drawn from, and it gives the sampler a way to reject an item whose title is
 * plainly in a language the user did not ask for.
 *
 * The word lists are vocabulary, not content. They are not a list of videos,
 * channels or creators - they never limit what can appear, they only decide
 * what we search for.
 */
public final class Vocab {

    public static final class Lang {
        public final String code;
        public final String name;
        public final String[] words;

        Lang(String code, String name, String... words) {
            this.code = code;
            this.name = name;
            this.words = words;
        }
    }

    public static final Lang[] ALL = {
        new Lang("en", "English",
            "cake", "skateboard", "sunset", "haircut", "thunderstorm", "fishing",
            "breakfast", "roadtrip", "sneakers", "origami", "camping", "boxing",
            "lantern", "pancakes", "surfing", "goat", "marathon", "puppy",
            "beekeeping", "trombone", "espresso", "bonsai", "tattoo", "submarine",
            "fireworks", "accordion", "glacier", "wrestling", "bakery", "moped",
            "aquarium", "snowboard", "violin", "harbour", "avalanche", "pottery",
            "hanggliding", "mushroom", "clockwork", "desert", "kayak", "pigeon",
            "trampoline", "lighthouse", "samurai", "cheesemaking", "windmill",
            "puppetry", "skydiving", "cactus", "fjord", "blacksmith", "marble",
            "hummingbird", "birdwatching", "whistling", "knitting", "iceberg",
            "steam train", "carnival", "tugboat", "yodel", "hedgehog", "falconry",
            "neon sign", "clockmaker", "cathedral", "salt flat", "jellyfish"),
        new Lang("ja", "Japanese",
            "ラーメン", "花火", "釣り", "猫", "旅行", "料理", "電車", "富士山",
            "相撲", "カラオケ", "雪", "桜", "ゲーム", "犬", "祭り", "寿司"),
        new Lang("zh", "Chinese",
            "火锅", "熊猫", "高铁", "广场舞", "长城", "包子", "太极", "象棋",
            "风筝", "庙会", "早市", "书法", "麻将", "饺子"),
        new Lang("ko", "Korean",
            "김치", "축구", "한복", "불꽃", "등산", "라면", "고양이", "태권도",
            "노래방", "바다", "떡볶이"),
        new Lang("es", "Spanish",
            "paella", "fútbol", "flamenco", "playa", "tacos", "baile", "mercado",
            "guitarra", "tormenta", "bicicleta"),
        new Lang("pt", "Portuguese",
            "futebol", "samba", "praia", "churrasco", "carnaval", "café",
            "capoeira", "chuva", "mercado"),
        new Lang("fr", "French",
            "fromage", "vélo", "marché", "cuisine", "montagne", "rugby",
            "boulangerie", "plage", "orage"),
        new Lang("de", "German",
            "wandern", "brezel", "fussball", "kuchen", "berg", "schnee",
            "fahrrad", "markt"),
        new Lang("it", "Italian",
            "pizza", "vespa", "mare", "calcio", "cucina", "montagna", "gelato",
            "temporale"),
        new Lang("ru", "Russian",
            "балет", "хоккей", "зима", "медведь", "поезд", "снег", "рыбалка"),
        new Lang("ar", "Arabic",
            "قهوة", "صحراء", "خيل", "مطر", "سوق", "بحر", "مخبز"),
        new Lang("hi", "Hindi",
            "क्रिकेट", "चाय", "नृत्य", "बारिश", "पहाड़", "खाना", "बाज़ार"),
        new Lang("id", "Indonesian",
            "pantai", "sepakbola", "nasi", "gunung", "hujan", "pasar"),
        new Lang("tr", "Turkish",
            "kahve", "futbol", "deniz", "kebap", "kar", "pazar"),
        new Lang("th", "Thai",
            "ทะเล", "ก๋วยเตี๋ยว", "ฝน", "ตลาด", "ภูเขา", "หมา"),
    };

    private Vocab() {
    }

    public static Lang byCode(String code) {
        for (Lang l : ALL) {
            if (l.code.equals(code)) {
                return l;
            }
        }
        return null;
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
