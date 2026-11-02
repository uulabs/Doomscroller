# UU Doomscroller — status and handover

Prototype · Android · Java · 1 November 2026
Code: `/Volumes/970P/UULabs/UUDoomscroller` · Research: `research/FINDINGS.md`

## What this is

A serverless, keyless, login-free Android app that plays a random portrait video feed
(doomscroll) assembled from several sources. There is no account, no UU Labs server and no API
key: every video is found on the device, when it is needed, from a random subject word.

The interface is one video surface and two buttons. Settings sits top right. Share sits bottom
right and fires an `ACTION_SEND` chooser, so a link lands in UU Downloader, which is an existing
share target. Swipe up for the next video, swipe down for the previous one, tap to pause. The
settings panel holds the About text, fifteen language checkboxes and one checkbox per source.

## Current state

*(device)* marks results observed on a Blade A3 Plus, Android 15, WebView 155.0.8059.43; the
other rows follow from the code and the research log.

### Working

| Area | State | Detail |
|---|---|---|
| YouTube discovery | works *(device)* | Shorts-filtered search results page, desktop user agent; 25–76 ids per query. |
| YouTube playback | works *(device)* | Resolved muxed MP4 played by ExoPlayer fullscreen; no provider page is shown, so no ads, subscribe button, comment counts or share chrome. |
| Portrait filtering | works *(device)* | Uses the real width and height the resolver reports; a landscape clip never reaches the player. |
| Feed mechanics | works *(device)* | Strict source rotation (never the same source twice in a row), seen-set dedupe, prefetch depth 3 over two ExoPlayer slots — a swipe swaps to an already-buffered video instead of showing a loader; rotation is untested until a second source plays. |
| TikTok discovery | works, rate-limited | Sitemap first, then tag embeds; the embed endpoint returns 503 if paced faster than about seven seconds, treated as a miss. |
| X discovery | works | Syndication timeline widget; returns `video.twimg.com` MP4 references with dimensions. |
| PeerTube, Dailymotion discovery | works | Sepia Search API and the public Dailymotion API; both include or filter on width and height. |
| Settings | works *(device)* | Fifteen languages and five sources, persisted in shared preferences. |

### Not working

| Area | State | Detail |
|---|---|---|
| X playback | fails | The syndication CDN returns 403 to the player, so resolved X items never start. |
| PeerTube playback | effectively inert | Almost everything public is landscape long-form, so the portrait filter rejects nearly all of it. |
| Dailymotion playback | inert | Same landscape problem, and its items are embeds the current build cannot resolve (next row). |
| TikTok playback | not wired | TikTok items are emitted with kind `frame`; `Main.resolve()` accepts only `ytprobe` and `mp4`, so every TikTok candidate is discarded before it can play. Discovery works; playback does not exist yet. |
| Instagram, Facebook | not implemented | No anonymous discovery of any kind; both are login walls. |
| Swipe down (previous) | no-op on YouTube | `previous()` returns early for `ytprobe` items because re-resolving would block the UI thread, so a YouTube-only feed cannot go back. |
| Language filter on titles | ineffective on YouTube | YouTube items are created with an empty title, so `Vocab.detect()` has nothing to read; language only shapes the search words. |
| TikTok share link | broken | Built as `tiktok.com/@x/video/<id>`; the handle is unknown to the app, so the link does not resolve. |

Default settings are YouTube only, which is the honest choice: the others are wired for
discovery but refused (X), filtered out (PeerTube, Dailymotion) or have no playback path (TikTok).

## How playback works

In plain terms:

1. Discovery hands the feed an eleven-character YouTube id.
2. A worker thread asks `YoutubeResolver` for a stream, blocking with a 45 s timeout. The
   resolver owns one hidden WebView loaded with `file:///android_asset/externalmedia/uu-resolver.html`.
3. That page's content security policy forbids network access, so it imports a vendored
   youtubei.js 18.1.0 from the assets folder (MIT, licence beside it) and replaces the library's
   own fetch with a mediated one.
4. Every request the library makes is posted as JSON over a JavaScript bridge
   (`UUChatResolver.postMessage` → `YoutubeResolver.Bridge`); the app performs it natively with
   `HttpURLConnection` and returns the status, content type and base64 body.
5. The page asks the player endpoint under five clients in turn — WEB, ANDROID, IOS, MWEB, TV —
   and stops at the first returning status OK with a usable format: a muxed MP4 (audio and video
   in one file), no DRM, height 720 or less, closest to 360p.
6. The format's address is deciphered with the session's player object, then returned to the app
   with its real width and height.
7. `Resolved.portrait()` rejects anything taller than it is wide. Unknown dimensions (zero) are
   allowed through rather than guessed.
8. ExoPlayer (media3 1.11.1) plays it in a `PlayerView`, controller off, zoom-to-fill, muted
   until its slot is active, sending the WebView cookie with `Referer` and `Origin`.
9. Failures do not interrupt: a player error, or a slot still not ready after 25 s, is released
   and skipped, a short status line records it, and the next candidate is pumped in.

No YouTube page is ever displayed, so there is no ad break, subscribe button, comment count or
share strip in the feed — the reason the resolver exists. Videos loop rather than auto-advance,
so the feed only moves on a swipe.

## How discovery works

Each source is queried with one word from a randomly chosen enabled language. The pipeline then
drops repeats, landscape items and titles in languages not enabled, shuffles, and rotates sources.

**YouTube.** `results?search_query=<word>&sp=EgIYAQ%3D%3D`, the Shorts filter, with a consent
cookie and a desktop user agent; video ids are scraped from the HTML, up to 20 per query.

**TikTok.** `/embed/tag/<word>` first, falling back to `www.tiktok.com/sitemap.xml`, which
enumerates video URLs directly. Its tags are largely CJK and uncurated.

**X.** `syndication.twitter.com/srv/timeline-profile/screen-name/<word>` — the word is used as a
handle. Its `__NEXT_DATA__` JSON yields `video.twimg.com` MP4s with dimensions from the path.

**PeerTube.** `sepiasearch.org/api/v1/search/videos?search=<word>&count=15` federates thousands
of instances. Live items are skipped, and only a portrait MP4 file per video is kept.

**Dailymotion.** `api.dailymotion.com/videos?search=<word>&limit=15` with `width,height` in the
fields; landscape items are dropped at parse time.

The research log records the supply limits: Instagram and Facebook are hard login walls even in a
real Android WebView, and TikTok's and X's own search and tag surfaces are closed (captcha, login
wall, removed endpoints). Only YouTube, PeerTube and Dailymotion expose anonymous, documented
discovery; TikTok and X are reachable only through the undocumented surfaces above, and X's CDN
then refuses the player. The rule that follows: playing a provider's own page always risks ads,
playing the resolved stream never does.

## Settings

The panel is a full-screen scroll view over the video. Opening it pauses playback; "Done" or back
closes it and resumes.

- About text, as shipped: "A doomscrolling app that supports multiple social media channels with
  no login required, no algorithm is ever built on you and the output is completely random."
- A second paragraph states that subjects come from the allowed languages, that every video is
  found on the device, and that only a direct stream is played.
- Languages: fifteen checkboxes — English, Japanese, Chinese, Korean, Spanish, Portuguese, French,
  German, Italian, Russian, Arabic, Hindi, Indonesian, Turkish, Thai. Each contributes a short
  word list used as search subjects; these are vocabulary, never a content list.
- Sources: one checkbox each for YouTube, TikTok, X, PeerTube and Dailymotion.
- Storage: `SharedPreferences` file `doomscroller`, keys `languages` and `sources`; defaults are
  English and YouTube only, and an empty selection falls back to them.
- A change applies to the next items produced, not to the video already on screen.

## Known limitations and next steps

1. **Only YouTube genuinely plays.** Say so plainly; keep the default source set as YouTube only.
2. **TikTok is the cheapest second source.** Discovery already returns portrait ids; playback
   needs its `player/v1` embed or a resolved stream — wire it or hide the checkbox.
3. **Delete the dead provider-page path** before anyone mistakes it for live: `isPage()` always
   returns false, the WebView branch in `pump()` is unreachable, and `startProbe()`, `poll()`,
   `resolveYouTube()`, `CHROME_CSS`, `DOM_JS` and `ytStartJs()` are unused, as is `debugText`.
4. **Swipe down does nothing on YouTube.** Either re-resolve the previous id asynchronously
   behind a holding screen, or remove the gesture.
5. **The language title filter has no effect on YouTube** because titles are discarded during
   discovery; parsing the title out of the same results page would make the filter real.
6. **The TikTok share link is malformed** (placeholder handle). YouTube, X and Dailymotion links
   are well formed; PeerTube falls back to the direct media URL.
7. **Instagram and Facebook are out of scope** on this evidence. Nothing short of a keyed search
   API or a UU Labs discovery service changes that, and both break the no-key, no-server posture.
8. **Housekeeping:** the seen set grows for the life of the session, and `resolve.log` plus the
   dead path's `page-dom.txt` are written unconditionally into app storage.

## How to build and test

**Toolchain.** Gradle 9.2.1, AGP 9.0.1, compileSdk 36, targetSdk 35, minSdk 26, Java 17 sources
built with JDK 21. Dependencies: `androidx.media3:media3-exoplayer` and `media3-ui` 1.11.1; the
rest is the framework. SDK path in `local.properties` (`~/Library/Android/sdk`), build-tools
36.0.0, platform android-36.

```
cd /Volumes/970P/UULabs/UUDoomscroller
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, `debuggable` true,
versionCode 1, versionName 0.1). There is no wrapper, so `gradle` must be on `PATH`. Ignore
`app/build.sh`: it predates the media3 and youtubei.js work and will not compile the sources.

Install on the attached device:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If that fails with a signature mismatch, an earlier install came from `app/build.sh`, which signs
with `app/keystore.jks`; uninstall `au.com.uulabs.doomscroller` once and install again. On the
Blade A3 Plus (Android 15, WebView 155), confirm: a portrait video within a few seconds; swipe
up moves without a long loader; tap pauses; share offers a working link; settings opens and its
checkboxes survive a restart.

**Diagnostics.** The app is debuggable, so traces can be read without root:

```
adb shell run-as au.com.uulabs.doomscroller cat files/resolve.log
```

It logs each attempt and outcome, including the per-client tally behind a skip (`WEB:0/14/OK`:
fourteen adaptive formats, no muxed one). Do not rely on `logcat` here — the ring buffers drop
app output.

## Changes — 2 November 2026

Reported from use: the same videos and the same subjects came round again, and a stall midway
through a clip looked like a frozen picture.

| Change | Detail |
|---|---|
| Played videos survive a restart | The seen set was a `HashSet` on the activity, so quitting forgot every clip and the next launch replayed them. It is now loaded from and written back to preferences (`seen_ids`), capped at 1500 ids, flushed every eighth add and on pause. |
| Subjects are drawn from a bag | Independent random draws repeat: with sixty-odd words, one comes round within a dozen picks, and a repeated word returns the same search results — which is how the same video, or the same person, kept appearing. Each language now has a shuffled bag in preferences (`bag_<lang>`); every word is spent before any returns, and the bag survives a restart. |
| The loader covers every buffering state | It used to appear only while the playhead was still at zero. Any `STATE_BUFFERING` now shows the doomer face, so a mid-video stall no longer looks frozen. |

Verified on an S98 (Android 12): the loader shows on launch, playback works, `seen_ids` holds
16 entries after a few swipes and `bag_en` 59 remaining subjects — both read back from
`shared_prefs/doomscroller.xml` after a restart.

Discovery itself is unchanged, so the tail of the problem remains: one word still returns one
narrow set of clips, and the feed now spends the whole vocabulary before a subject returns.
