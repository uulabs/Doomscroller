# Meta discovery and maintenance

Instagram and Facebook use a bundled cache of public reel permalinks to start without a search engine accepting every device request. This is the same path in release and debug builds. Automatic debug-only seed clips have been removed.

The current cache contains 6,000 Instagram and 2,181 Facebook IDs. It was sampled from public Internet Archive CDX index pages across index bands, deduplicated and shuffled. `meta-catalog-receipt.json` records source requests, response hashes, sampling seed, errors and the resulting asset hash. Facebook sampling stopped after a TLS timeout; its coverage is narrower than the intended 24 bands. This is a finite, biased sample of indexed public URLs, not uniform access to all Meta videos.

Only URLs are bundled. Playback resolves the current public provider page, then uses the native player. Removed/private/unavailable clips are skipped. No archived video files, accounts, cookies, credentials or new hosted service are supplied. Geometry still comes from decoded video size: unknown and landscape clips stay hidden/silent in portrait-only mode. Embedded black borders inside an intrinsically portrait video do not make its decoded geometry landscape.

Candidates are drawn without replacement during the process lifetime. Loading the asset again does not replenish consumed entries. Existing persisted seen-history filtering also applies. Pools retain unused candidates rather than discarding a batch after its first successful result. Once the cache is exhausted, live Brave/DDG/CDX discovery remains available with backoff. Those services have refused or timed out on S98, so exhaustion can still produce an unavailable state. The cache is not automatically refreshed in the background.

## Refresh before a future release

Run from the repository using the required external scratch wrapper:

```sh
python3 /Users/frasertag/Work/Codex/UULabs/tools/run-on-970p.py --project UUDoomscroller --owner meta-catalog --purpose refresh-public-reel-urls --reserve-gb 0.1 -- python3 tools/refresh-meta-catalog.py
```

Review both `app/src/main/assets/meta-reels.tsv` and `docs/meta-catalog-receipt.json`. Requests are paced and stop on refusal/error. Each provider must yield at least 1,000 unique URLs or the existing asset is left unchanged. Archive timestamps indicate indexing, not publication date or present availability. Test Instagram-only and Facebook-only on a device using ordinary random draws before accepting a refresh. Never replace these tests with a fixed known-working clip sequence.

## Variety and language limits

The existing topic sampler covers 33 families and 15 languages; 491 localized broad family labels supplement detailed terms when live Meta search has no results. The bundled URL cache itself has no dependable topic or spoken-language metadata, so a selected query does not describe the cached clip drawn. It broadens supply but cannot promise category balance, fresh content or spoken-language percentages. Existing title-based language heuristics cannot reliably classify clips with missing titles. Do not advertise this as strict language filtering.

## Regression checks

- Cold start with only Instagram, then only Facebook; confirm actual moving video and repeated distinct promotions.
- Disable YouTube/TikTok while a clip is queued; disabled-source queued/history items must not appear afterward.
- Perform 30+ forward swipes and alternating back/forward; inspect frame motion, loading gaps, retained WebViews and crashes.
- Verify TikTok embeds specify `controls=0`, while the original Doomscroller playback bar remains available.
- Check portrait rejection, landscape-enabled rotate affordance and settings-off revalidation.
- Report speaker audio separately from screenshots/decoder evidence.
