# Topic coverage and sampling (0.1.26)

The app now bundles a multilingual topic catalogue from Wikidata's structured CC0 data. No requests to Wikidata are made by the app and no AI service, key or server is required at runtime. Labels are search subjects, not a catalogue of videos or creators.

Sources: [query service](https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service), [structured-data licence](https://www.wikidata.org/wiki/Wikidata:Licensing). Root concepts, retrieval date and file hashes are in `topic-catalog-receipt.json`. `tools/build-topic-catalog.py` regenerates the files, using external scratch through the workspace wrapper. CATALOG_CACHE may point to a retained download directory. Queries are bounded to 4,500 label rows per family; coverage is a snapshot, not an exhaustive ontology. Use a fresh cache when changing root/query definitions.

## Behaviour

1. Draw an enabled language from a persistent shuffled bag: English does not receive more turns because its catalogue is bigger.
2. Draw a family from that language's shuffled family bag. There are 33 imported families plus a local-discovery family holding the existing native vocabulary.
3. Draw a subject without replacement within that language/family. Skip recently drawn concept IDs where alternatives exist, including across languages.
4. Ask a provider for that subject, shuffle candidates and apply the player admission rules.

Changed language selection takes effect on the next draw. Changed catalogue content invalidates the affected subject bag. State persists across restarts. This balances discovery attempts, not guaranteed spoken-language percentages among played videos: provider indexing, titles, availability and portrait validation still affect supply. No watch-time ranking or preference learning is introduced.

Uncertain Latin-script title language stays unknown rather than defaulting to English. Han-only text stays unknown because it could be Chinese or Japanese. This remains a conservative title heuristic, not speech recognition.

## Coverage

- ar: 2769 imported queries
- de: 3819 imported queries
- en: 5102 imported queries
- es: 3745 imported queries
- fr: 4119 imported queries
- hi: 845 imported queries
- id: 1800 imported queries
- it: 2977 imported queries
- ja: 3507 imported queries
- ko: 2012 imported queries
- pt: 2584 imported queries
- ru: 3216 imported queries
- th: 890 imported queries
- tr: 1899 imported queries
- zh: 2999 imported queries

5,142 distinct imported concept IDs. Existing local vocabulary is also retained and deduplicated per language. Concepts with fewer than four supported labels and namespace-style labels were excluded to reduce low-quality imports. This favours documented concepts; local-discovery preserves native-specific terms that would otherwise be excluded.

Labels are not all equally natural search phrases. The catalogue contains objects, disciplines, activities, models and regional names, not just categories. Future expansion can add reviewed native phrase variants and more families without changing sampling. It does not claim all internet topics, uniform selection across all videos, or equal success from every provider. X still interprets subjects as account handles and TikTok's sitemap fallback is topic-independent; both are limitations of the existing discovery adapters. X and PeerTube default off on new installs; saved source choices are preserved.

Debug `word.log` records language/family/concept/query; `play.log` retains the originating language/family when known, allowing discovery-versus-playback coverage audits. No signed stream URLs are included in these new records.
