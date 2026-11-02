# UU Doomscroller

A serverless, keyless, login-free Android app that plays an endless random portrait
video feed assembled from several public sources.

> **Experimental, and just for a laugh.** This exists to see what a feed with no
> algorithm at all would look like: no profile, no history, no ranking. It is a
> toy, not a product. There is no support, no roadmap and no promise it keeps
> working. Every source it uses is an undocumented surface that the platform it
> belongs to can close whenever it likes.

- **No account, no server, no API key.** Every video is found on the device at the
  moment it is needed, from a randomly chosen subject word.
- **No provider player for YouTube.** A hidden WebView runs `youtubei.js`; the
  app mediates its requests and plays the resulting stream itself, so there are no
  adverts, no subscribe button and no browser chrome.
- **Two controls.** Settings top left, share beneath it. Swipe up for the next
  video, swipe down for the previous, tap to pause.
- **Unfiltered by design.** A first-run notice says so before anything plays.

| First run | Settings | Playing |
| --- | --- | --- |
| ![First run](docs/shot-disclaimer.png) | ![Settings](docs/shot-settings.png) | ![Playing](docs/shot-video.png) |

![TikTok](docs/shot-tiktok.png)


## How it picks

Nothing is ranked, recommended or remembered about the person watching.

Each draw takes a subject at random, searches every enabled source for it and
plays whatever comes back first. Subjects are spent from a shuffled pool, so
nothing returns until everything else has been used, including across
restarts.

There is no list of videos, channels or creators anywhere. A subject decides
only what gets *asked for*, never what comes back; the results are whatever the
platform serves at that moment.

## Sources

| Source | Discovery | Playback |
| --- | --- | --- |
| YouTube | Shorts-filtered search results | native stream via `youtubei.js` |
| TikTok | sitemap / tag embeds | TikTok's own embeddable player |
| X | syndication timeline | direct mp4 |
| PeerTube | Sepia Search + per-instance lookup | HLS via ExoPlayer |

## Build

```sh
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+, Android SDK platform 36 and Gradle 9.x. `local.properties` must
point at your SDK; it is not committed.

## Documentation

- `STATUS.md`: current state, what works, what does not, and next steps.
- `research/FINDINGS.md`: the research record behind the source choices.

## Releases

UU Doomscroller is **not published on Google Play, and will not be**. It is
distributed here as a single APK.

That APK updates itself: when a newer version exists it says so, downloads it
and hands it to the Android installer. Installing anything over the top keeps
your settings.

> **On Play Protect.** Downloading and launching an installer is the shape of a
> dropper, so this build can be flagged. In testing so far it has not been. That
> verdict is Google's and can change without notice, so if you are asked whether
> to trust the install, the honest description is: it updates itself and does
> nothing else.

A build that never installs anything, and only opens this page when an update
exists, can still be assembled from source:

```sh
gradle :app:assembleStandardRelease
```

Verify a download with:

```sh
shasum -a 256 -c SHA256SUMS
```
