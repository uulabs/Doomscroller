# UU Doomscroller 0.1.28

Fixes two reproduced scrolling failures: an unavailable pending TikTok clip could block the feed, and a prepared native video could cover the video actually playing. Pending native frames stay hidden until admission; videos use FIT without cropping.

- Preserve the original Doomscroller playback bar and provider audio scripts.
- Replay forward history after swiping back; skip disallowed history entries in the requested direction.
- Show a rotate button immediately for measured landscape videos when landscape is enabled.
- Show the source name inside the existing playback bar.
- Broaden discovery with 5,142 concepts and 42,283 localized queries across 15 languages, balancing language and topic-family attempts.
- Keep X and PeerTube opt-in for new installs and preserve saved source selections.
- Guard background fill/pump work during activity shutdown.

Validation: repeated native and live TikTok swipes on S98, back/forward and rejected-history replays, portrait admission, landscape rotation, release builds/signatures, and multilingual sampling checks. Published at the owner's request. Direct speaker listening and Xiaomi 15 Ultra acceptance were not independently verified by the agent. Discovery balance does not guarantee returned-video language or uniform coverage across the internet. Cross-origin video geometry that cannot be measured is skipped in portrait-only mode.


## Two files, and one honest warning

UU Doomscroller is not on Google Play and never will be; it is distributed here.

| File | Use |
| --- | --- |
| `UUDoomscroller-0.1.28.apk` | The app. Checks for a newer version, downloads it and hands it to the Android installer. |
| `UUDoomscroller-0.1.28-no-self-update.apk` | The same app, except it never installs anything: it opens this page and you install it yourself. |

**On Play Protect.** The self-updating build has not been flagged in testing, so
it is treated as safe by the devices used here. That is Google's verdict to
change, and "not flagged yet" is not the same as "safe": an app that downloads
and launches an installer is, by shape, doing what a dropper does.

If you would rather not grant that, use the second file. The only function you
give up is in-app updating. Both share a signer and application id, so they
install over one another and keep your settings.

`UUDoomscroller-0.1.28-selfupdate.apk` is an identical compatibility alias of the recommended APK. Verify downloads using SHA256SUMS.
