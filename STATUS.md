# UU Doomscroller — status

Prototype · Android · Java · updated 4 November 2026
Code: `/Volumes/970P/UULabs/UUDoomscroller` · Research: `research/FINDINGS.md` ·
Working record: `HANDOVER-DEEPY.md`

This file is the short, current picture. The detailed history of what was tried,
measured and rejected lives in `HANDOVER-DEEPY.md`; the source choices are
argued in `research/FINDINGS.md`; what the app is and how it picks is in
`README.md`.

## Where it stands

- Latest published release: 0.1.49 (versionCode 50), at
  https://github.com/uulabs/Doomscroller/releases/tag/v0.1.49, in two
  distributions (`selfupdate` and `standard`, the latter never installs anything
  itself). `dist/` holds the built files and `SHA256SUMS`.
- Sources on by default: YouTube, TikTok, Instagram, Facebook. Opt in: X,
  PeerTube. The language set defaults to English.
- Settings sits top left with share beneath it; swipe up for next, down for
  previous; tap brings up the playback bar. A first-run notice gates playback.

## How it plays

| Source | Discovery | Playback |
| --- | --- | --- |
| YouTube | search results page (Shorts filter unless landscape is allowed) | muxed MP4 resolved by `youtubei.js` in a hidden WebView, played by ExoPlayer |
| TikTok | tag embed pages | TikTok's `player/v1` embed in a WebView, watched by `WATCH_JS` |
| Instagram | bundled URL cache, then public search indexes | media URL read from the embed page, played by ExoPlayer |
| Facebook | bundled URL cache, then public search indexes | mp4 read from the video plugin, played by ExoPlayer |
| X | syndication timeline | direct mp4 (opt in) |
| PeerTube | Sepia Search + instance lookup | mp4 or HLS (opt in; rarely portrait) |

Two slots are kept: one on screen, one prefetched. Portrait is decided from the
decoded geometry, never from what a source claimed. Played ids, shown titles and
resolved media fingerprints are remembered so a clip does not come round twice;
subjects are drawn from shuffled per-language bags that survive a restart.

## Known limits

- Every source is an undocumented public surface and can close at any time.
  TikTok throttles the tag endpoint; the Meta search indexes throttle quickly, so
  Instagram and Facebook build up supply over time.
- Language filtering is best effort: it reads the YouTube title; for TikTok
  the speech-recognition subtitle language when the clip has auto-captions,
  then the caption, then the country it was made in (one-language countries
  only, so some English clips made abroad are skipped); the Instagram caption, and for Facebook the post text, canonical
  slug and page name from the video plugin, plus the search index's title for
  live-discovered Meta reels. A script none of the fifteen languages uses is a
  clear mismatch. A clip is only rejected when the answer is clear, so a
  wordless or name-only post still passes.
- Audio focus, keep-screen-on and resume-on-return arrived in 0.1.46 with only
  a short S98 smoke test. The handover warns that earlier player changes broke
  sound; listen with TikTok enabled before trusting them.

## Build

Gradle 9.2.1, AGP 9.0.1, compileSdk 36, targetSdk 35, minSdk 26, JDK 21.
There is no wrapper in the repository; `tools/build-release.py` finds the Gradle
9.2.1 distribution and builds into the 970P scratch area:

```
python3 /Users/frasertag/Work/Codex/UULabs/tools/run-on-970p.py \
  --project UUDoomscroller --owner <owner> -- \
  python3 tools/build-release.py :app:assembleStandardDebug
```

Release builds sign with the keystore described in `app/build.gradle`; without it
they come out unsigned. `app/build.sh` predates Gradle and does not build the
current sources.

Diagnostics in debug builds are written to the app's files directory
(`resolve.log`, `ig.log`, `word.log`, `play.log`, `motion.log`, `controls.log`,
`loading.log`) and read with `adb shell run-as au.com.uulabs.doomscroller`.
Release builds write none of them.
