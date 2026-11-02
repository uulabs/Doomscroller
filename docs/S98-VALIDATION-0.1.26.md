# S98 validation — 0.1.26

Tested 2 November 2026 on S98, Android 12/API 31. Blade was not used.
Baseline source: 4b024a5. Final source is the commit carrying this report/tag v0.1.26.

## Observed checks

- Standard debug, recommended self-update release and standard release builds succeeded.
- Pure Java checks passed for unknown/portrait/landscape/square/non-square-pixel geometry, Reel URL parsing and wrong-host rejection, language/family balance despite unequal vocabulary sizes, changed language selection and persisted shuffled bags. A final repeat initially used an incorrect Java package name; correcting the invocation passed without source changes.
- Isolated `.review` app exercised synthetic clips: landscape rejected before promotion in portrait-only mode; 90-degree rotation metadata correctly displayed a portrait frame; square admitted; audio-only unknown geometry timed out without entering playback history.
- Landscape allowed: rotate button appeared immediately on the upright wide clip. Rotation and return worked; final landscape screenshot shows the complete button clear of system navigation.
- Turning landscape off removed the active wide clip and admitted the next eligible portrait clip.
- Back/forward retraced R → A → B → E → B → A → R → A → B → E. Discovery prefetch survives history navigation. The implementation bounds each history direction at 100; the device check exercised this smaller sequence, not 100 individual clips.
- Synthetic WebView videos: portrait A and B admitted, landscape C rejected, inaccessible cross-origin video timed out. Provider-page measurements use intrinsic video dimensions.
- Background/foreground and actual activity destruction/recreation checks produced no observed app fatal exception or rejected-executor crash. These are bounded checks, not proof against every scheduling race.
- Live YouTube portrait playback was observed. The signed production build also played a live TikTok clip. Earlier Meta playback used debug seed assistance; those seeds were subsequently removed. Corrected Reel discovery parsing has host checks, but live search returned HTTP 429, so seed-free end-to-end Meta discovery is not claimed here. X/PeerTube live playback was not checked.
- All six source labels were exercised through synthetic source assignments. These confirm presentation, not provider availability.
- Fullscreen review: player and stage match the parent; settings/share/source/rotate controls overlay it and reserve no row. Portrait fixture borders remain intact. Aspect-ratio margins are expected with FIT on the tall S98 display. Production TikTok screenshot shows footage behind the settings control.
- Signed recommended APK installed over production with `adb install -r`, preserving app data. Package reports 0.1.26/code 27; installed APK bytes equal the release file. Signer matches the original installed 0.1.25 APK. No production fatal exception was observed in the captured smoke-check log.

## Evidence and retention

`S98-ARTIFACTS-0.1.26.json` binds preserved screenshots, logs, synthetic fixtures and the original installed APK under `/Volumes/970P/UULabs/Artifacts/UUDoomscroller/0.1.26`. This is outside disposable scratch. Release APKs/checksums remain in `dist/` and are published as release assets. The original 0.1.25 backup is retained. Disposable compiler output, raw catalogue retrieval cache and redundant scratch copies may be removed after publication; raw catalogue fetch replay is not promised. The generator, shipped catalogues and catalogue receipt remain in source control.
