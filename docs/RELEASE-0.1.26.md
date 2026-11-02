# UU Doomscroller 0.1.26

## Changes

- Broader exploration: 5,142 imported topic concepts and 42,283 localized queries across all 15 languages, plus the existing local vocabulary. Languages and 34 subject families (including local discovery) get balanced shuffled turns. Vocabulary size no longer determines language frequency, and newly enabled languages enter immediately.
- Small neutral text source labels for YouTube, TikTok, Instagram, Facebook, X and PeerTube. X and PeerTube default off for new installations; existing saved choices are preserved.
- A measured landscape clip immediately offers a rotate control while upright when landscape is allowed. Tap to rotate, then tap again to return. Full-frame FIT presentation avoids cropping, in either orientation.
- Portrait admission uses actual player/display geometry. Unknown dimensions remain unverified and time out. Rotation metadata and non-square pixels are handled through the player output. Settings changes cannot resume rejected content.
- Back/forward navigation retains up to 100 previous clips, retraces forward history before new discovery, and preserves a prefetched new clip while browsing history.
- Shutdown guards cover late pump/fill callbacks, rejected executor submissions and players retiring during an animation. Paused/background/settings/disclaimer states share one playback gate.
- Corrected over-escaped Instagram/Facebook search-link patterns; fixed debug seed videos no longer mask discovery failures. The hidden Instagram resolver stays muted.

## Distribution

`UUDoomscroller-0.1.26.apk` is the recommended self-updating build.
`UUDoomscroller-0.1.26-no-self-update.apk` is the manual-update alternative.
`UUDoomscroller-0.1.26-selfupdate.apk` is a byte-identical compatibility alias for older updaters.
All use versionCode 27 and the existing app signer. SHA256SUMS binds the uploaded files.

## Validation

See the accompanying S98 validation and artifact receipt for observed checks. Host checks cover display geometry, normal/escaped Reel links, wrong-host rejection, balanced language/family draws, selection changes and bag persistence. Signed APK verification checks package, version and continuity with the installed signer.

## Practical limits

Discovery remains anonymous and best effort. Search indexes and provider sites may throttle or change; the external Reel index returned HTTP 429 during this review. A provider page whose actual video cannot be measured is skipped in portrait-only mode. Balanced query attempts do not guarantee equal spoken-language playback. The title-language heuristic remains conservative, not speech recognition. FIT cannot reconstruct content already cropped by its uploader. The catalogue is broad but not an exhaustive or uniform sample of the internet.
