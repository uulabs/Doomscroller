# UU Doomscroller

A serverless, keyless, login-free Android app that plays an endless random portrait
video feed assembled from several public sources.

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

- `STATUS.md` — current state, what works, what does not, and next steps.
- `research/FINDINGS.md` — the research record behind the source choices.

## Releases

Each release publishes two APKs and a `SHA256SUMS` file:

| File | Use |
| --- | --- |
| `UUDoomscroller-<version>-play.apk` | Google Play build. Checks for a newer version and opens the release page; it never downloads or installs anything. |
| `UUDoomscroller-<version>-selfupdate.apk` | Direct build. Downloads the new version and hands it to the system installer. |

Both are signed with the same key, so one installs over the other and over any
earlier release, keeping settings.

> **Google Play:** the `-selfupdate` build must never be uploaded there. It
> downloads and launches an installer, which Play Protect classifies as a
> dropper. Only the `-play` build is eligible for Play submission. This note
> belongs in every release description as well.

Verify a download with:

```sh
shasum -a 256 -c SHA256SUMS
```
