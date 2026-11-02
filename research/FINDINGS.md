# UU Doomscroller — anonymous discovery spike

**Date:** 1 November 2026
**Question:** can an anonymous client, given a randomly chosen subject, obtain a
fresh, portrait, playable video — on each of the five providers?

**Method:** plain HTTPS from macOS with a desktop-class Chrome user agent, no
cookies, no login, no API key. Rate-limited by hand (2–5 s between requests).
Executed through `tools/run-on-970p.py`; raw responses retained under
`/Volumes/970P/UULabs/Scratch/uu-doomscroller/rnd/`.

**Limit of this evidence:** these results bound the **server-rendered** surface
only. The app will use an Android WebView with JavaScript, cookies and a real
fingerprint, which can behave differently — particularly for TikTok and
Instagram. Brave was present on this host but its headless mode does not run in
this environment (`CVDisplayLinkCreateWithCGDisplay failed`, then hang), so the
JS-rendered surface was **not** tested. That is the one remaining unknown.

---

## Headline

**Resolution is broadly solvable. Discovery is not.**

Only YouTube will generate its own supply anonymously. The other four can be
*played* if you already know an item id, but they will not tell you what exists.

---

## Discovery — "give me a fresh item for subject X"

| Provider | Result | What happened |
|---|---|---|
| **YouTube** | **PASS, reliable** | `youtube.com/results?search_query=X&sp=EgIYAQ%3D%3D` (Shorts filter) returns server-rendered HTML with 25 unique `videoId`s per query, every time. Requires a **desktop** user agent — a mobile UA returns a different rendering that yielded 0 ids. |
| **TikTok** | **FAIL** | `/tag/X` and `/search?q=X` return `__UNIVERSAL_DATA_FOR_REHYDRATION__` but **zero** item ids, flagged as a bot check. |
| **Instagram** | **FAIL** | `/explore/tags/X/` 302s to `/accounts/login/`. `/popular/X/` returns a 507 KB JS shell with no shortcodes. `/api/v1/tags/web_info/` returns 200 with a bot-check payload. |
| **X** | **FAIL** | `/1.1/search/tweets.json` and `/2/search/adaptive.json` are **404 — removed**. `/search?q=` returns a 28 KB JS shell with no tweets. A guest token is still issued (200), but there is nothing to spend it on. |
| **Facebook** | **FAIL** | Login-walled; not probed further. |

### Indexes as a substitute for platform discovery

| Index | Result |
|---|---|
| DuckDuckGo HTML | Worked **once** — 3 unique TikTok video ids from `site:tiktok.com /video/ cake` — then returned 202 (throttled) for every subsequent query. Not a backbone. |
| Mojeek | 403 on `site:` queries. |
| Startpage | 200, JS shell, no ids. |
| Marginalia | 200, no provider ids. |
| Common Crawl | Index reachable (129 collections, latest `CC-MAIN-2026-43`) but the CDX queries returned 404/502/504. Even if fixed, a crawl index is *months stale* — a static pool, which is the thing the brief rejects. |
| Reddit JSON | 403. |

---

## Resolution — "given an id, give me a playable stream"

| Provider | Result | Detail |
|---|---|---|
| **TikTok** | **PASS** | `tiktok.com/oembed?url=…/@x/video/<id>` returns `width: 325, height: 578` — **portrait confirmed before play** — plus the official embed player `tiktok.com/player/v1/<id>`. `embed/v2/<id>` also returns dimensions when it does not 503. |
| **X** | **PASS** | `cdn.syndication.twimg.com/tweet-result?id=<id>&lang=en&token=<any>` returns tweet JSON including video variants. **The `token` parameter is required** — without it the response is `{}`. |
| **YouTube** | **PASS, but not by raw HTTP** | A raw InnerTube `POST /youtubei/v1/player` answers 200 with `UNPLAYABLE — Video unavailable`, i.e. a bot check wearing a normal response. UU Chat already solves this by running `youtubei.js` in a real WebView; that path is shipped and proven on-device. |
| **Instagram** | **PASS on-device (inherited)** | Not reproducible from plain HTTP here, but UU Chat already resolves public reels by id through its provider page and ships it. Not new work; reuse it. |
| **Facebook** | **FAIL** | No path found. |

### Incidental

- TikTok's `player/v1/<id>` is **TikTok's own documented embed player**. Playing
  through it is provider-rendered, not scraped — a materially better posture
  than rehosting or resolving private endpoints.
- YouTube's Shorts filter (`sp=EgIYAQ%3D%3D`) reliably returns vertical items,
  so a portrait filter is mostly redundant there — but not free, since a
  "Shorts" search still surfaces horizontal items.

---

## What this means for the design

The brief's promise — *"plays completely randomly between each social"* — needs
a supply of ids for four providers that currently refuse to supply one. Three
honest options:

1. **YouTube only.** Genuinely unbounded, genuinely random, works today,
   ships in days. One provider, and the settings page has one switch.
2. **Add a search API with a key** (Brave Search, Google CSE). A documented,
   paying, machine-facing index is the only way to get cross-platform ids
   without either a maintained list or a UU Labs server. Cost: a key, a
   dependency, and a third party seeing every random subject.
3. **A UU Labs discovery service.** Best supply, worst fit: it makes UU Labs the
   operator, changes the "no server" posture UU Downloader documents, and is the
   one option that reintroduces something that looks like a feed.

Everything else — the feed, the rotation, the two buttons, the silent-skip
failure model — is unaffected by this. Only the supply is.

## Still unknown, and cheap to settle

Whether TikTok and Instagram serve usable content to a **real Android WebView**.
Two devices are attached to this host (`Blade_A3_Plus`, `S98`); a ten-minute
WebView probe on one would answer it. That is a device test, not a design
decision, and it should happen before the source list is fixed.

---

## Device verification — real Android WebView

The HTTP results above were bounded to the server-rendered surface, so the
open question was whether a **real WebView** (JavaScript, cookies, Chrome user
agent) behaves differently. It does not.

**Setup:** Blade A3 Plus, Android 15 (API 35), WebView `155.0.8059.43`,
Chrome mobile user agent (WebView's default `wv` marker removed), JavaScript and
DOM storage enabled, 10 s settle then DOM inspection. Probe source:
`webprobe/`. Raw output: `research/device-webview-run.txt`.

| Provider | What the real WebView got | Verdict |
|---|---|---|
| **YouTube** | Redirected to `m.youtube.com/results…`; DOM contained **76** `videoId` occurrences | **WORKS** |
| **Instagram** | `/explore/tags/cake/` → **`/accounts/login/`** (77 login markers), 0 shortcodes. `/popular/<tag>/` behaved identically | **LOGIN WALL** |
| **X** | `/search?q=…&f=live` → **`/i/jf/onboarding/web?…&mode=login`**, rendering "Continue with phone / Google / Apple / Email or username" | **LOGIN WALL** |
| **TikTok** | `/tag/cake` fired a **deep-link intent** to the native app (`snssdk1233://challenge/detail/…`) instead of rendering; `/search?q=` returned a **captcha** page (3 captcha markers) with 0 video ids | **BLOCKED** |

### Conclusions

1. **YouTube is the only provider that supplies anonymous discovery**, over
   plain HTTP and in a real WebView alike. 76 ids per query, repeatable.
2. **Instagram and X are login walls** for first-time anonymous visitors, not
   bot checks. This is a product decision by the providers, not something a
   better fingerprint will fix.
3. **TikTok** is both captcha'd and deep-link hijacking. The deep-link is
   fixable in-app (`shouldOverrideUrlLoading` must reject non-http schemes), but
   the captcha is not.
4. The strict cross-platform rotation described in the brief **cannot be
   populated** as things stand. Any honest v1 is YouTube-shaped.

### Notes for implementation

- TikTok/Facebook/X pages will attempt app hand-off. An embedded WebView must
  refuse non-`http(s)` schemes or it will silently bounce the user into another
  app.
- `am`/`adb` tooling notes: this device's logd drops app logs (64 KiB ring
  buffers), so results were written to app-private storage and read with
  `run-as`. Rebuilds regenerate the signing key, so `install -r` fails
  silently — uninstall before installing again.

---

## The supply wall, and what is actually open

Discovery was then tested against every remaining keyless surface:

| Surface | Result |
|---|---|
| DuckDuckGo HTML | 3 TikTok ids once, then permanently 202-throttled |
| Bing (mobile + desktop, redirect-decoded) | **Ignores `site:`** — returned local cake shops and forum archives |
| Mojeek | 403 on `site:`, nothing usable plain |
| Startpage / Marginalia | JS shell / no provider links |
| Common Crawl | 404/502/504 on CDX queries; stale even if fixed |
| Mastodon public timelines | 9 platform links per 40 posts, mostly YouTube — too thin |
| Bluesky public AppView | 403 |
| Reddit JSON / RSS | 403 / no platform links |

**Conclusion: no on-device, keyless, serverless discovery exists for TikTok,
Instagram or X.** Those three can only be *played* given an id.

Platforms that **do** expose keyless public discovery:

| Platform | Endpoint | Result |
|---|---|---|
| **YouTube** | `results?search_query=…&sp=EgIYAQ%3D%3D` | 25–76 ids per query, repeatable |
| **PeerTube** | `sepiasearch.org/api/v1/search/videos?search=` (federates the fediverse) | 15 results, public, keyless |
| **Dailymotion** | `api.dailymotion.com/videos?search=…` | 20 results, includes width/height |
| Bilibili | `api.bilibili.com/x/web-interface/search/type` | 412 — needs wbi signature |
| Odysee | backend proxy | 404 — endpoint retired |

A serverless, keyless, multi-source random feed is therefore buildable on
**YouTube + PeerTube + Dailymotion**, with genuine cultural breadth from
PeerTube's federation. It is not buildable on the four named apps.

---

## Reversal: the earlier "impossible" was premature

The first pass tested the *obvious* surfaces — search pages, tag pages, APIs.
Pushed harder, the **surfaces built for crawlers** are open, and three of the
four providers are reachable serverlessly, keylessly, on-device.

### YouTube — unchanged

`results?search_query=…&sp=EgIYAQ%3D%3D`, desktop UA → 25–76 portrait ids per
query, repeatable.

### TikTok — NEW: open via sitemap

`https://www.tiktok.com/sitemap.xml` → 200, 5,660 URLs:
**362 direct video URLs**, **1,136 tag slugs**, 126 profiles.
The tags are overwhelmingly CJK (`中央大学`, `僕らの青春`, `セルフ解説`) — auto-generated,
uncurated, and exactly the cultural breadth the brief asked for.

Every sampled sitemap id resolved through **oEmbed: `325×578`, portrait, 200 OK**,
with an official `player/v1` embed URL.

`/embed/tag/<tag>` returns 8–10 further ids per tag. It 503s when paced faster
than ~7 s, so it needs retry/backoff — but it works, including Japanese tags
(`修一朗` → 10 ids, `セルフ解説` → 8 ids).

### X — NEW: open via syndication timeline + handle graph

`https://syndication.twitter.com/srv/timeline-profile/screen-name/<handle>`
→ 200, keyless. `/screen-name/cake` yielded **56 post ids, 28 `video/mp4`
references and 17 distinct screen names**. Expanding to a harvested handle
(`cakewallet`) returned **42 more mp4 refs and 20 new handles**.

That is a self-expanding graph with no curated list: seed a random dictionary
word as a handle, harvest statuses and handles, expand.

`x.com/sitemap.xml` is 404 — declared in robots.txt but not served — so handle
expansion comes from the timelines themselves.

### Instagram — still closed

Every route returned the login page: tag pages over HTTP, tag pages in a real
Android WebView, `/api/v1/tags/web_info/`, `/api/v1/users/web_profile_info/`
(401), `mbasic`, `m.`, crawler user agents. `robots.txt` declares no sitemaps.

Instagram remains the one provider with no anonymous discovery path.

### Revised verdict

| Provider | Serverless keyless discovery | Status |
|---|---|---|
| YouTube | search results, Shorts filter | **works** |
| TikTok | sitemap index + tag embeds | **works**, rate-limited |
| X | syndication timeline + handle graph | **works** |
| Instagram | none found | **closed** |

Caveat that must carry into the design: none of these three surfaces is
documented or promised. They are stable enough to build on, and the silent-skip
failure model already assumes any source can vanish mid-session.
