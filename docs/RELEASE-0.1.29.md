# 0.1.29 — Meta discovery and scrolling repair

- Instagram/Facebook now start from a broad cache of public reel links, resolving live media through the native player. Failed search requests no longer leave discovery spinning. Removed/private clips are skipped.
- TikTok's provider playback controls are disabled; the original Doomscroller playback bar remains.
- Cancelled stale player timers, isolated Instagram resolver requests and stopped outgoing audio during an unbuffered swipe wait.
- Disabling a source also removes its queued/history clips. Back/forward history, measured portrait admission, FIT display, landscape rotate button and neutral source label are retained.
- Multilingual topic sampling remains, with localized broader search retries. Meta's URL cache has no dependable language/category metadata, so it does not guarantee spoken-language or category balance.

The bundled cache contains 6,000 Instagram and 2,181 Facebook links, not downloaded videos. It is a finite sample; links can expire and provider/network delays can still cause loading gaps. See docs/META-DISCOVERY.md for provenance and maintenance.


Validation: S98 mixed-source diagnostic completed 32 forward swipes plus six alternating back/forward swipes. The exact signed release played Instagram and Facebook through five Meta-only swipes with moving-frame verification. Builds, release lint, signatures and packaged assets verified. Speaker listening and Xiaomi15Ultra acceptance were not independently verified.

## Two files, and one honest warning

UU Doomscroller is not on Google Play and never will be; it is distributed here.

| File | Use |
| --- | --- |
| `...apk` | The app. Checks for a newer version, downloads it and hands it to the Android installer. |
| `...-no-self-update.apk` | The same app, except it never installs anything: it opens this page and you install it yourself. |

**On Play Protect.** The self-updating build has not been flagged in testing, so
it is treated as safe by the devices used here. That is Google's verdict to
change, and "not flagged yet" is not the same as "safe": an app that downloads
and launches an installer is, by shape, doing what a dropper does.

If you would rather not grant that, use the second file. The only function you
give up is in-app updating. Both share a signer and application id, so they
install over one another and keep your settings.
