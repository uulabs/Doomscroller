# Instagram and Facebook Reels: discovery handover

Date: 2 November 2026 (Australia/Sydney)
Status: investigation proposal; no implementation or on-device validation performed in this review.

## Purpose

Explore whether UUDoomscroller can add Instagram or Facebook Reels without requiring the user to supply individual video links. This is a focused feasibility investigation, not a general code review or request to rebuild the app.

A random-Reel API is not required in principle: discover valid Reel permalinks through another source, shuffle the candidates, then resolve playback. The unresolved question is whether discovery and playback can both work dependably within the project's constraints.

## Current constraints and evidence

The README describes a serverless, keyless, login-free Android toy. Subjects come from shuffled vocabulary pools; there is no maintained catalogue of videos or creators. Preserving those properties is the default. A backend, credentials, curated catalogue or signed-in mode would be a product decision, not an incidental implementation detail.

Reviewed:

- `README.md`
- `app/src/main/java/au/com/uulabs/doomscroller/Sources.java`
- Relevant sections of `STATUS.md` and `research/FINDINGS.md`

The existing research is meaningful and should not be discarded:

- Instagram tag/profile discovery attempts encountered login walls or bot-check responses, including probes in an Android WebView.
- DuckDuckGo HTML discovery returned results once and then throttled. Bing queries did not reliably respect the intended site restriction.
- The research records inherited UUChat support for playing public Instagram Reels by known ID. This is historical evidence to revalidate, not a playback result established by this review.
- Facebook discovery and playback have not been established as usable paths.
- `STATUS.md` already identifies a keyed search API or a UU Labs discovery service as possible changes to the constraints.

The accurate conclusion is: **no dependable anonymous discovery path has been demonstrated under the current constraints**. Requiring a permalink for playback does not itself make random selection impossible.

## First experiment: external discovery, then shuffle

Proposed flow:

1. Draw a subject from the existing vocabulary mechanism.
2. Ask an external search index for candidate pages using queries such as:
   - `site:instagram.com/reel/ surfing`
   - `site:facebook.com/reel/ surfing`
3. Extract canonical Reel permalinks, validate their host/path, remove duplicates and shuffle the candidates.
4. Resolve candidates for playback, skipping unavailable, private, deleted or login-required results.
5. Return playable items to the existing feed; let existing sources continue when this source produces nothing.

These queries are examples, not verified working discovery endpoints. This review did not demonstrate a successful end-to-end Reel discovery/playback sequence. Existing search-engine failures make anonymous search-page scraping a fragile hypothesis rather than an implementation-ready solution.

Random selection would be from the discovered pool, not uniformly from all Reels. Search ranking and index coverage still shape that pool, just as upstream search results shape the existing YouTube source.

## Start with Instagram

Instagram is the better first target because the project research points to an existing UUChat playback resolver. Inspect that implementation and establish whether it can still play a small, varied set of public Reel URLs in the actual Android environment.

Keep these questions separate:

1. Can we discover fresh URLs without a supplied video list?
2. Can we play those discovered URLs anonymously?
3. Can we sustain both at a useful rate without stalling the feed?

A successful single embed does not prove discovery or feed reliability. Likewise, finding a URL does not prove playback. Facebook requires its own playback experiment rather than assuming Instagram's resolver transfers across.

## Alternatives if anonymous discovery fails

| Option | Benefit | Constraint changed / limitation |
|---|---|---|
| Search API | Structured external discovery of candidate permalinks | Requires credentials, potentially cost and a backend; does not guarantee playable results |
| Rolling discovery service | Amortises discovery and provides a pool the app can sample | Adds a server and maintained catalogue; upstream discovery still needs a viable source |
| Submitted or curated Reel links | Simple way to establish random playback from known candidates | Adds a catalogue; does not meet the current no-video-list premise |
| Official Instagram API | Documented hashtagged-media and professional-account capabilities | Requires credentials and permissions; is not unrestricted discovery across Instagram |
| Optional signed-in browsing | Potential access to the platform's own feed | Requires login and inherits platform selection/personalisation; integration remains unproven |

Meta's official Instagram API collection describes hashtagged-media discovery and professional-account capabilities, along with access-token requirements and account limitations:
https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api

Check the exact current permissions, review requirements and returned media fields before selecting an API route. Do not assume official Instagram capabilities apply to Facebook or provide arbitrary native video playback.

The Meta oEmbed documentation could not be retrieved in this review because it returned HTTP 429. No conclusion about current embed requirements was validated from that page.

## Suggested integration shape, if feasibility is proved

`Sources.fetch(source, word)` is the natural discovery integration point. Keep candidate discovery separate from playback resolution so their failures can be measured independently.

- Preserve a canonical permalink separately from any temporary playback URL, including for sharing.
- Use bounded request timeouts, limited candidate attempts and backoff when throttled.
- Deduplicate by provider and Reel identifier.
- Avoid blocking the existing feed while an experimental source is unavailable.
- Verify playback controls, swipe navigation and failure recovery in the actual app.
- Inspect the current player dispatch before adding an item kind; comments and historical research are not sufficient evidence of a working player branch.
- Keep experimental support opt-in until repeated runs establish usefulness.

Do not embed a shared secret in the APK if a credentialled approach is chosen. Any change to the no-server/no-key/no-login premise should be made explicit to the owner before implementing that alternative.

## Evidence to collect before claiming success

Run a small bounded feasibility probe before full integration. Record:

- Discovery source, query, time and response outcome.
- Number of unique candidate URLs and duplicate rate.
- Playback success/failure for discovered candidates, separately from supplied test URLs.
- Login walls, throttling, unavailable content and time-to-first-play.
- Whether subsequent runs continue to find different playable videos.
- Device/WebView environment and any credentials or cookies required.

Report outcomes as discovery works/fails, playback works/fails, and sustained supply established/not established. Preserve failures as well as successes. Stop retrying a blocked source and fall back rather than treating login or bot-check pages as media.

Follow the workspace's 970P scratch, resource and retention instructions for any probes or builds. This handover authorises no builds, device use, credential setup or changes to existing source acceptance rules by itself.

## Recommendation

Investigate Instagram first as an opportunistic source. Revalidate known-link playback, then attempt a tightly bounded external-discovery proof. Do not repeat the already-failed anonymous endpoints indefinitely.

If this fails under the current constraints, report that result clearly. A small rolling link pool is a practical architectural alternative if the owner accepts a catalogue/backend, but it still needs a viable way to acquire links. Treat Facebook as a separate discovery-and-playback investigation.

No source code was changed and no builds or device tests were run as part of this review.
