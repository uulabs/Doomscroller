# 0.1.27 recovery

0.1.26 was withdrawn to draft after the owner reported silent playback and unwanted TikTok UI. The public latest endpoint reverted to 0.1.25. Withdrawing cannot undo APKs already downloaded or installed.

Recovery restores all application source and assets to commit 4b024a5 (0.1.25). Only `app/build.gradle` versionName/versionCode differ: 0.1.27/28. This intentionally rolls back all 0.1.26 features, including discovery expansion, source badges and history/geometry rewrites. Existing Doomscroller controls and provider playback behavior are restored unchanged. Original 0.1.25 limitations also remain.

Both signed release variants built successfully. Signer continuity, package/version metadata and installed-byte equality were checked. Recommended APK installed over 0.1.26 on S98 without clearing app data. The supplied 0.1.25 APK hashes identically to the preserved original device APK. Evidence is retained under `/Volumes/970P/UULabs/Artifacts/UUDoomscroller/0.1.27`.

The earlier 0.1.26 validation did not establish audible playback or adequate provider-control behavior. Screenshots and geometry tests were insufficient; that release should not have been published on that evidence. This recovery is awaiting a direct sound/control check before public release.
