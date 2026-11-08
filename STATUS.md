# UU Doomscroller — status

Prototype · Android · Java · updated 4 November 2026
Code: `/Volumes/970P/UULabs/UUDoomscroller` · Research: `research/FINDINGS.md` ·
Working record: `HANDOVER-DEEPY.md`

This file is the short, current picture. The detailed history of what was tried,
measured and rejected lives in `HANDOVER-DEEPY.md`; the source choices are
argued in `research/FINDINGS.md`; what the app is and how it picks is in
`README.md`.

## Where it stands

- Latest published release: 0.1.54 (versionCode 55), at
  https://github.com/uulabs/Doomscroller/releases/tag/v0.1.54, in two
  distributions (`selfupdate` and `standard`, the latter never installs anything
  itself). `dist/` holds the built files and `SHA256SUMS`. The self-updater
  fetches each release asset through GitHub's API address first and the web
  download address second: on 8 Nov 2026 the web front end answered 504 to
  every file for over half an hour while the API path served them, which made
  0.1.52 and 0.1.53 report "no published checksum".
- Sources on by default: YouTube, TikTok, Instagram, Facebook. Opt in: X,
  PeerTube. The language set defaults to English.
- Settings sits top left with share beneath it; swipe up for next, down for
  previous; tap brings up the playback bar. A first-run notice gates playback.

## How it plays

| Source | Discovery | Playback |
| --- | --- | --- |
| YouTube | search results page (Shorts filter unless landscape is allowed) | muxed MP4 resolved by `youtubei.js` in a hidden WebView, played by ExoPlayer |
| TikTok | tag embed pages | TikTok's `player/v1` embed in a WebView, watched by `WATCH_JS` |
| Instagram | live search of public indexes for the drawn subject; bundled list only while the indexes refuse | media URL read from the embed page, played by ExoPlayer |
| Facebook | live search of public indexes for the drawn subject; bundled list only while the indexes refuse | mp4 read from the video plugin, played by ExoPlayer |
| X | live search of public indexes for the drawn subject while one answers, else a random run of posts cited on English Wikipedia (its public external-links table, picked by a random link number); candidates are read from the syndication post JSON (text, language, renditions) four at a time and only ones with a playable video in an allowed language are handed on; the bundled list (posts cited on Wikipedia, screened for video when built) is the last resort | direct mp4 (opt in) |
| PeerTube | Sepia Search + instance lookup | mp4 or HLS (opt in; rarely portrait) |

Two slots are kept: one on screen, one prefetched. Portrait is decided from the
decoded geometry, never from what a source claimed. Played ids, shown titles and
resolved media fingerprints are remembered so a clip does not come round twice;
subjects are drawn from shuffled per-language bags that survive a restart.

## Known limits

- Every source is an undocumented public surface and can close at any time.
  TikTok throttles the tag endpoint; the search indexes throttle quickly. Up to
  0.1.51 Instagram and Facebook drew from the bundled list first and only
  searched live once it ran out, so those two sources were random within a
  fixed list of about 8,000 reels rather than across the web. Since 0.1.52 the
  live search for the drawn subject comes first and the list is reached only
  while every index refuses or answers empty. X exhausted itself in 0.1.51 and
  0.1.52: one index answer gave a handful of posts and then both indexes refused
  the phone for ten minutes at a time. Since 0.1.53 it screens candidates
  before handing them on and has the Wikipedia citation table and a bundled
  list behind the indexes.
- The Sources setting "Random letters instead of subjects" replaces the topic
  lists with three or four random letters as the search term, for people who
  do not want any list behind the feed. Results are stranger and sparser.
- The feed moves on by itself when nothing is on screen and a verified clip is
  ready: a ready player or a loaded page is promoted as soon as the feed waits
  for one, not only on a geometry report (which some pages never give while
  paused), and a watchdog promotes within about a second if a trigger is
  missed. Before 0.1.53 that state needed a swipe.
- Language filtering is best effort: it reads the YouTube title; for TikTok
  the speech-recognition subtitle language when the clip has auto-captions,
  then the caption, then the creator's own name and bio, then the country it
  was made in (one-language countries only, so some English clips made abroad
  are skipped); for X the language X recorded for the post, else its text; the
  Instagram caption, and for Facebook the post text, canonical slug and page
  name from the video plugin, plus the search index's title for live-discovered
  Meta reels. A script none of the fifteen languages uses is a clear mismatch,
  and so are letters English never uses (ñ, ã, ß, ı, ł, Vietnamese tone marks)
  when no English marker is present. A clip is only rejected when the answer is
  clear, so a wordless or name-only post still passes, unless the Languages
  setting "Only clips with a known language" is on, which skips TikTok,
  Instagram, Facebook and X clips that give no language clue at all.
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
