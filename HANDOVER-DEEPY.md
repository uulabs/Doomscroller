# Doomscroller — Deepy handover (live draft)

This document is being maintained during investigation. Do not treat the working tree or any unpublished APK as accepted. The owner asked Codex to continue until the account usage meter reaches 65% remaining, then halt and leave a detailed handover. Initial verified reading was 84% remaining (weekly account-wide allowance). No new release is authorized to bypass actual sound, scrolling and autoplay acceptance.

## Immediate problem and owner requirements

The owner reports progressive playback/navigation failure: after several swipes the picture freezes while audio changes; a later swipe can make that picture start moving; in fewer than ten swipes navigation can become very slow, lose its animation or appear to stop. The owner tested 0.1.27 on Xiaomi and reported this behavior. Exact Xiaomi model, OS and affected provider(s) are not yet established. They also saw poor scrolling/autoplay on S98. Do not conclude this is only an S98 issue.

The original Doomscroller playback/navigation bar must be preserved. Do not substitute provider controls, add a second bar or rewrite audio behavior while implementing unrelated features. The owner still wants the earlier requested features in the eventual working release: broad multilingual discovery, minimal source identification, reliable portrait handling, immediate rotate action for measured landscape when enabled, forgiving back/forward history, and shutdown race protection. X and PeerTube should default off without overwriting saved selections.

## What went wrong in this session

Codex published 0.1.26 too early after broad player changes. The owner immediately reported broken sound and unwanted TikTok UI; their screenshot shows TikTok's More videos panel and a muted provider control bar. Codex changed TikTok URLs to include muted=1 and replaced the original WATCH_JS logic, dropping provider end-panel/stall checks. Those changes are concrete regression suspects. A screenshot showing playback does not prove sound or correct navigation. Prior reports overstated acceptance; do not rely on them as proof the release worked.

The original native controls must not be blamed for provider UI. The source badges and separate rotate button in 0.1.26 were additional UI; current retry plans source text inside the existing bar instead. No company icons are needed.

## Versions and repository state

Repository: /Volumes/970P/UULabs/UUDoomscroller
Remote: git@github-doomscroller:uulabs/Doomscroller.git

- 4b024a5: original pre-task 0.1.25, versionCode 26.
- 716d01d: failed 0.1.26 feature release, versionCode 27. Published, then withdrawn to draft. Public latest endpoint was verified back at v0.1.25. Existing downloaded/installed APKs cannot be recalled by withdrawing a release.
- b7180bf: added the required distribution notice to release notes.
- c3edc52: 0.1.27 recovery source, versionCode 28. All 52 application files other than app/build.gradle were byte-equal to 4b024a5. Only the version fields differed in the Gradle file. Built and installed, never publicly released. Owner tested on Xiaomi and rejected smoothness.
- efab61f: current HEAD/main pushed to origin; exact tracked tree restoration to 4b024a5, retaining Git history. No app behavior difference in this commit from pre-task baseline.
- Current dirty working tree: tentative 0.1.28/code29 changes on top of efab61f. Compiled as standardDebug and tested through an isolated .diagnostic copy; now installed locally on S98 for owner acceptance, but not committed, published or accepted. Do not confuse source version with installed version.

S98 serial: S980000000000014309. Current install is local unpublished 0.1.28/code29, installed preserving app data for owner acceptance. Before that, read-only verification reported original 0.1.25/code26. Installed APK was byte-checked against the user's attached original: SHA256 1158cb829709ccb2d294f93b39a80f7018e5579586c9a4f4f43950612a93660d. Android accepted `adb install -r -d` of the original; no uninstall or data clear was needed. Blade was not modified.

0.1.27 recommended and compatibility alias SHA256: 9496f4e8490a078b86cba9c868ca19258710444229d648fb90ca51ec46d3748d.
0.1.27 manual-update SHA256: 234097c35ae3fcd95d5e9599d2db9a9a95ebbf7fbbfa49676349280b6b294453.
These remain in dist. Do not publish them as a fix; user rejected their scrolling.

## Dirty retry edits: unverified, not a patch to blindly ship

Main.java retains original WATCH_JS, JS_ACTIVE/JS_IDLE, provider URLs and playback-bar control logic. Tentative changes add a forward history stack, preserve discovery prefetch when revisiting history, bound each direction at 100, nudge while waiting for a portrait measurement, add executor shutdown guards, register native pending before prepare, extend native timeout to unknown geometry, prevent rejected discovery prefetch from automatically advancing the active clip, measure page geometry even with landscape enabled, add a manual rotate action and put plain source text into the existing controls row.

These edits need further review, especially rejection during history navigation, queued callbacks referencing retired slots, gesture animation while measurements are pending, rotation state across clips, and page timeout. The original page path had an unavailable-pending deadlock; see the confirmed fix and replay below. No claim of working 0.1.28 is made.

TopicSampler.java, Vocab.java and app/src/main/assets/topics are restored from the discovery-only portions of 716d01d. Catalogue: 5,142 concepts, 42,283 localized queries, 15 languages, 33 imported families plus existing local discovery. Balanced language/family attempts are not uniform internet sampling or equal spoken-language playback. Catalogue generator and receipt are present. Prefs changes only the new-install source defaults to exclude X and PeerTube.

## Findings to investigate before further feature work

1. Original page pending slot can wait indefinitely for measurable geometry: next() sets wantNext and returns, pump() refuses to replace non-null pending, WATCH_JS blind/end results advance only active, and there is no equivalent 25-second native deadline for the pending page. This is a concrete code-level deadlock risk, not yet proven to be the Xiaomi symptom.
2. Original native deadline only checked STATE_READY; STATE_READY with unknown geometry could similarly hold the slot. Retry extends that condition, untested.
3. Original watcher callbacks do not immediately stop when their WebView is no longer active/pending. onPageFinished can start more than one chain. Native retirement is delayed 280ms; WebView destruction another 400ms. Instrument live slot, watcher and decoder counts rather than assuming a leak.
4. Original animation uses default PlayerView/SurfaceView. State READY is used as rendered evidence; it is not a first-rendered-frame callback. The reported audio/picture mismatch suggests investigating surface handoff, but it is not proof of a SurfaceView bug. Do not switch surface type without device evidence and repeated-scroll testing.
5. Original next() has no visible acknowledgement when pending is unverified; this can look like a dead swipe even when gestures are received.
6. Original history pushes outgoing into the same stack during back navigation, explaining A/B loops. Forward replay needs its own history direction and must preserve prefetched discovery.
7. Original fill/pump can run after executor shutdown. Owner supplied a Blade RejectedExecutionException report; Blade was not used for this session.

## Evidence and its limits

Preserved authoritative evidence:
- /Volumes/970P/UULabs/Artifacts/UUDoomscroller/0.1.26 (includes original APK, synthetic tests, screenshots, failed release receipt and cleanup record).
- /Volumes/970P/UULabs/Artifacts/UUDoomscroller/0.1.27 (owner screenshot, exact-baseline diff, signer/install checks, recovery logs).

The former 0.1.26 synthetic geometry/history checks do not establish audible output, smooth repeated swiping, or provider-page behavior. Live Meta checks were seed-assisted; seed-free discovery was throttled. No live X/PeerTube acceptance was established. 0.1.27 build/signature/install checks passed, but owner rejected navigation; it is not an accepted recovery.

A first fresh swipe diagnostic under codex-retry/20261102T122637Z-577cfaf6b64f operated while the launcher was visible. It is invalid for app swipe conclusions. A repeat under 20261102T122738Z-ed3e2588b004 checks mResumedActivity before each swipe and captures pre/post screenshots, meminfo and gfxinfo. Findings will be appended after review. Existing gfxinfo values are cumulative; compare deltas or reset counters before claiming per-test jank. S98 media volume previously read zero; that explains why unattended screenshots were not a sound check, not why the owner's phone was muted.

## Required next validation

First isolate TikTok-only, native-only and mixed-source repeated scrolling using identical version/settings where possible. Capture at least 30 forward swipes, rapid swipes, A-B-C-D then repeated back/forward, and cold/warm starts. Record gesture receipt, selected clip ID, pending readiness/size, first-frame time, audio ownership, surface visibility/translation, slot disposal, active watcher count and memory/decoder counts. Preserve actual original slide animations and bar play/pause/seek behavior. A bounded spinner/skip is preferable to an indefinitely occupied pending slot, but do not hide a regression by silently skipping everything.

Verify audible sound directly on the user's Xiaomi or with valid audio capture; verify sound stops for outgoing clips and matches the visible clip. Test autoplay, end-card handling, pause/resume, seek and settings transitions. Recheck portrait-only and landscape rotation after navigation is reliable. Obtain owner acceptance of a local APK before publishing. No release should be published based only on host tests, package metadata or screenshots.

## Publication and credentials

Use the existing signer from ~/Work/Codex/UULabs/UULabsKeys/Doomscroller/keystore.properties. Signer SHA256 is 93b7263c3359ff80b7c071cce7e2a648bc49f2e9bf547fcaf2b0c01e56a16358. Never print key properties.

Git push uses the repo-specific SSH remote above. Ordinary gh authentication can read but returned 403 on release creation. Owner explicitly supplied macOS Keychain service doomscroller-release-token, account uulabs; retrieve into GH_TOKEN only for the release subprocess, never print/log/commit it. Release 0.1.26 was changed to draft with that credential. Do not delete its evidence or republish it. Use a new higher version/code after acceptance, retain recommended/manual-update/compatibility alias names and verify uploaded hashes. Follow docs/RELEASE-NOTES-TEMPLATE.md.

## Storage and ownership

All new scratch must use /Volumes/970P/UULabs/Scratch/<project>/<owner>/ through tools/run-on-970p.py. No deliberate /tmp writes. Build through the existing canonical exclusive scheduler. Source work belongs to this checkout; no subagents were used. Do not touch Blade or foreign device leases.

An S98 lease currently exists with token codex-exact-revert-20261102, owner codex-exact-revert; it must be released at final handoff. Hotfix scratch is roughly 664 MiB and currently held as review work; new retry diagnostic scratch is small. Previous 0.1.26 cleanup disposed 758,566,811 logical bytes and retained release/evidence copies. No new cleanup has yet been performed for 0.1.27/retry. Final handoff must record retention/disposal totals and any still-running commands.

## Investigation update — original-build foreground swipe run

The checked run completed ten swipes with Doomscroller confirmed foreground before each. `pre-00.png` shows a DadlifeJason TikTok clip; `post-09.png` shows the same clip at its More videos on TikTok panel. This reproduces failure to advance on the original APK, but does not yet identify which pending item blocks it. Read all per-swipe images before asserting the exact transition count.

At captures 00/04/09: WebViews stayed 3; Activities stayed 1; Views stayed 23. PSS fell 309,872 → 207,984 → 190,560 KiB. This run does not support a monotonically growing WebView-count leak. Cumulative rendered-frame counters were 3,886 → 4,148 → 4,148 and janky-frame counters 948 → 1,116 → 1,116; no additional counted app frames appeared between captures 04 and 09. Do not mistake zero additional app UI frames for proof a video decoder stopped: WebView/video rendering has separate paths.

A separate diagnostic copy of original 4b024a5 is being built at `/Volumes/970P/UULabs/Scratch/UUDoomscroller/codex-retry/baseline-diagnostic`, application suffix `.diagnostic`. Changes in that copy are logging of fling/UP/next/previous/pump/watch/geometry and app ID only. It is not the retry implementation, not a candidate for publication, and must not replace production. Its purpose is to identify whether gestures arrive while pending remains unknown. Production S98 APK stays original 0.1.25.

A source contract check confirmed the dirty retry's WATCH_JS, JS_ACTIVE, JS_IDLE, CHROME_CSS and PAGE_TIME_JS are byte-identical to original 4b024a5, and Sources.java is entirely unchanged. This guards against repeating the prior mute/end-panel changes; it does not establish runtime correctness.

## Confirmed failure: unavailable pending TikTok blocks all later navigation

The isolated original-code TikTok-only diagnostic reproduced the deadlock. Trace at 23:33:46–23:34:12 (device local time): each swipe logs touch UP, fling dy=-1200 / vy≈-4000, then next(). Active remains TikTok:6990369018175556870; pending remains TikTok:6869437819937492229; pending known=false, ratio=0, wantNext=true, queue=3, history=3. The pending watch repeatedly reports GONE and geometry `?`, while the active video's geometry remains 576x1024. Original watchForUnavailable only acts on giveUp when isActive; it ignores an unavailable pending clip. Thus this is a confirmed blocked-slot bug, not missing gesture delivery in this reproduction.

Failing sequence observed: 7172282969351130411, 6974212043025042694, 7252894398021782826, 6990369018175556870, then unavailable 6869437819937492229. Trace/screenshot run: codex-retry/20261102T123341Z-e55e99268f48. Original diagnostic APK and Main source preserved in `/Volumes/970P/UULabs/Artifacts/UUDoomscroller/retry-investigation`.

A targeted fix has now been applied to both the isolated diagnostic and dirty retry: reject a pending page on giveUp; bound unknown portrait-only pending pages to 25 seconds; stop watcher callbacks when the WebView no longer belongs to active/pending; start only one watcher chain per slot; ignore stale geometry callbacks; make rejection idempotent; preserve existing wantNext state when rejecting prefetch; acknowledge an unverified-pending swipe with the existing nudge animation. WATCH_JS/audio scripts/provider URLs remain unchanged. This fix is being built/tested; do not yet claim acceptance. It addresses confirmed navigation deadlock, not necessarily the separate audio/picture mismatch.

## Second reproduced failure: prefetched native surface covers active video

Native fixture run `codex-retry/20261102T123801Z-cee084b790bd` used three 12-second animated 270x480 testsrc2 clips with hues 0/90/180 and 440/660/880 Hz audio tracks. Thirty flings reached navigation; active IDs traversed A/B/C correctly and first-frame callbacks occurred. Autoplay advanced after the final clip's duration. However `swipe-29.png` visibly shows B's time-zero frame while the trace says active A, pending B. Reference frames extracted under `20261102T123936Z-687e9d0928a0` identify the hues unambiguously. This reproduces the owner's audio-changing/frozen-next-picture symptom: the pending SurfaceView is visually covering the active player despite stage index 0.

Thus first-frame callback counts and changing active IDs are not sufficient acceptance! The visible image must match the active clip and its time must advance. The native run's view count grew 146 → 524 → 818 → 860 while PSS rose about 112 → 137 MiB; delayed callbacks/GC retention warrant a further settled measurement, but this is not proof of a decoder leak. Keep the concrete surface mismatch separate from any leak hypothesis.

Targeted rendering experiment now uses a PlayerView inflated with surface_type=texture_view, resize_mode=fit and use_controller=false. No new playback UI or audio script. Both diagnostic and dirty retry contain this change; the experiment is under test. Media3 generally prefers SurfaceView for power/HDR/secure output, but TextureView participates in the normal UI composition and can solve this measured stacking requirement. References: https://developer.android.com/media/media3/ui/surface and https://source.android.com/docs/core/graphics/arch-tv . Do not cite pre-API24 animation caveats as proof of this API31 bug; the evidence here is the paired screenshot and active/pending trace.

The page deadlock fix replay completed 20 flings, 19 observed active-ID states cycling through three valid clips, seven GONE reports for the bad ID, and zero promotions of the bad ID. It continued past the previously blocking item. This validates that specific blocked-slot repair; it is not yet full release acceptance or a direct audible-sound check.

## TextureView result and full retry compile

The paired 30-swipe TextureView run (`20261102T124101Z-f853b4a53b45`) shows active A at frame 12/time 0.800s, then frame 42/time 2.800s, while B is pending. This corrects the observed pending-surface overlay. Settings/share and the original playback bar are visibly present. Autoplay subsequently promotes B. Evidence/source/hash manifest preserved in `Artifacts/UUDoomscroller/retry-investigation/textureview`. No audible-sound claim. Full dirty retry standardDebug compiled successfully in 5 seconds; it has not been installed over production or published. A separate diagnostic copy is being prepared for full retry history/rotation validation.

## Full retry navigation check

Isolated full-retry diagnostic (`candidate-diagnostic`, same .diagnostic app ID) passed the deterministic sequence A → B → C → B → C → B → C → B → A → B → C for up/up/down/up/down/up/down/down/up/up. Run `20261102T124703Z-9ad419813b2f` contains trace and each screenshot. Promotion logging occurs before outgoing history is pushed, so its stack counters are transitional. Production remains original 0.1.25.

Code review found pending native views were still visible while decoding, which could expose an unverified frame during startup or between slide animations even with correct stacking. Pending TextureViews now start alpha=0; the unchanged slideIn restores alpha after admission. This latest guard needs rebuilt geometry/scroll checks.

## Latest validation and remaining limits

- Transparent prefetch passed twelve mixed W/A/B swipes. The 480×270 W clip is cleanly letterboxed; pending A no longer shows behind it. Rotate control appears immediately and rotates successfully. Evidence: `retry-investigation/transparent-prefetch`.
- Full retry live TikTok replay completed thirty swipes and 26 promotions through the three available IDs; unavailable 6869437819937492229 was never promoted. It did not permanently block. Evidence: `retry-investigation/live-tiktok-30`. The pending rejection condition was narrowed to GONE or persistent NOSTREAM: WATCH_JS END can also mean intentionally paused prefetch and must not discard a healthy measured pending clip merely for that. Unknown geometry still has the deadline.
- 15,000 real-catalogue sampler draws produced exactly 1,000 per enabled language across 110 sampler reconstructions; every language reached all 33 imported families. Switching to Japanese-only immediately restricted subsequent draws. This checks search-attempt balance, not returned-video language, provider supply or relevance. Evidence: `retry-investigation/sampling`.
- Saved source sets from legacy installs are now retained irrespective of default-version marker. Only missing selections use the new defaults.
- Still no speaker-audio acceptance or Xiaomi retest. Original provider audio scripts and URLs remain unchanged. Do not publish yet.

The six Back/reopen cycles in `20261102T125233Z-5cbb58fcbe28` had no fatal/RejectedExecutionException, and the first portrait-only sequence never promoted W. However Android 12 root-activity Back can move the task to the background instead of destroying it. Do not call these six confirmed onDestroy tests. A diagnostic-only long-press finish hook is being added to exercise actual destruction.

## Review checkpoint — 2 November 2026, 23:58 AEDT

Account allowance reads 81% remaining, above the requested 65% stopping threshold. The implementation has reached a local candidate awaiting the owner's direct sound/sustained-scroll check; no claim that the usage threshold was reached. No public release was created. Keep this report current if work resumes before the requested handover threshold.

The exact rejected-history failure was reproduced: A → W → B → C, disable landscape, back to B, back across W incorrectly promoted C. The repair queues the next older entry with backward direction before pumping; exhausted backward history leaves the active clip in place. Native prefetch errors now use the same rejection path. Replay now promotes A → W → B → C → B → A. Both failing and corrected traces/screens are retained in retry-investigation/rejected-history-before and rejected-history-after.

Lifecycle testing recorded five finish requests and five onDestroy callbacks with no FATAL EXCEPTION or RejectedExecutionException, including settled logs. The automated script requested six gestures and asserted six callbacks, so its final assertion failed with count=5. Do not relabel that script as a full pass: one gesture did not yield a finish request. Five actual destruction cycles are the supported observation. Diagnostic hooks only: long-press settings calls finish; long-press share toggles the landscape setting through the real settings-close validation. These hooks and fixture intents are absent from the production candidate.

Signed local artifacts are in `/Volumes/970P/UULabs/Artifacts/UUDoomscroller/0.1.28-local-candidate`:

- `UUDoomscroller-0.1.28.apk`: SHA256 f50366a868c3311f350546357008765b18547efd81ec2523e821dface5a02be9.
- `UUDoomscroller-0.1.28-no-self-update.apk`: SHA256 415ddc43e47df7ef7291c287c5877bd0c9b698e755a8336104d6ab9ae808d00a.
- Both package au.com.uulabs.doomscroller, versionCode 29, versionName 0.1.28, existing signer verified by apksigner. Both release builds and lintVital passed. Build took 26 seconds. receipt.json, signer results, build.log, working-tree.patch and source-hashes.json bind the artifacts. No diagnostic fixture hook is in those APKs.

S98 was updated with the self-update candidate using `adb install -r`, preserving settings/data. Startup notice was dismissed. Production is now this local candidate, not original 0.1.25. Do not overwrite the candidate during the owner's listening check. The separate `.diagnostic` app remains installed and force-stopped with owned synthetic fixtures; it can be removed after review. Do not uninstall production. Blade remains untouched. No host test/build process is left running at this checkpoint.

Owner questions pending: Xiaomi model/Android version, and audible sound/original bar/30 swipes including back-forward on S98. Speaker sound has NOT been confirmed remotely. Do not publish based on the screenshots or trace counters. Source and assets are frozen at the local candidate while awaiting that feedback; if source changes, rebuild, hash and retest instead of silently reusing these APK names.

### Concrete next steps for Deepy

1. Read this checkpoint and inspect `git diff` plus untracked files; HEAD is still efab61f, not the candidate. Preserve the untracked TopicSampler, topic assets, XML layout and tools. Compare source-hashes.json to the working files before associating results with a build.
2. Obtain the owner's actual listening/repeated-scroll result. Check TikTok separately from native YouTube/Instagram/Facebook, and the mixed feed. Confirm no provider-control regression. X/PeerTube remain opt-in; no live acceptance is claimed for them.
3. If further reproduction is needed, the isolated copy is `Scratch/UUDoomscroller/codex-retry/candidate-diagnostic`. It uses `.diagnostic`, plus `diagnostic_ids` and `diagnostic_native` intent extras. A/B/C are animated 270×480 12-second fixtures, W is 480×270; files reside in its private files directory and the external av-fixtures directory. Do not ship its hooks. Use existing scheduler/wrapper invocation as described below.
4. Recheck both initial unknown geometry and landscape-to-portrait setting changes. Pending native alpha=0 must still yield measured geometry and promotion; FIT must show only the active video with clean letterboxing. Reject history in the same navigation direction. Watch actual moving frames, not only first-frame callbacks.
5. Keep original provider scripts unless a separately demonstrated defect requires a focused repair. Remaining baseline review items include pause/background behaviour (loading watch can reissue JS_ACTIVE), delayed retiring WebView silence, and control-bar right inset in landscape. They are not proven fixed by these scrolling checks. Avoid bundling speculative redesigns.
6. After owner acceptance, update release notes, commit reviewed source/new assets, push only via the configured SSH remote, build/verify final assets, then publish using the Keychain-sourced release credential. Public latest must remain the known recovery baseline until acceptance. Never publish the diagnostic APK, old 0.1.26, or rejected 0.1.27.

### Build command shape

Run the outer `tools/run-on-970p.py --project UUChat --owner codex-doomscroller-build --purpose <purpose> --reserve-gb 0.2` wrapper, then `UU Chat Concurrent GIt/m6-acceptance-integration/tools/one-heavy-job.py --owner codex-doomscroller --scope doomscroller-release-candidate --exclusive --worktree /Volumes/970P/UULabs/UUDoomscroller`, then inner `tools/run-on-970p.py --project UUDoomscroller --owner codex-retry --purpose <purpose> --reserve-gb 0.2 -- python3 -B /Volumes/970P/UULabs/UUDoomscroller/tools/build-release.py :app:assembleStandardRelease :app:assembleSelfupdateRelease`. Wrapper and scheduler live under `/Users/frasertag/Work/Codex/UULabs`. Use no internal temporary paths. Build helper writes to the reusable external codex-retry/build directory, so preserve a needed diagnostic APK before a later debug build overwrites it.

### Retention at this review checkpoint

Read-only project audit reports 1,542,942,720 allocated bytes across UUDoomscroller Scratch and no budget breach. Current source, current diagnostic fixtures, release candidates and source-bound evidence are held for review; none are described as accepted. No new scratch deletion in this retry checkpoint (0 bytes disposed). Older hotfix build/cache is obsolete once its preserved artifact/source receipts are checked; settle it under the scratch policy. Current diagnostic/build/cache retention is a review exception owned by codex-retry, review by 9 November 2026 or immediately after owner acceptance. Dispose redundant generated output within 48 hours after settlement. The S98 lease token remains codex-exact-revert-20261102; release it at actual final handoff, after checking no owned device command is active.

## Publication authorization — 3 November 2026

Owner identified the Xiaomi as a 15 Ultra and explicitly instructed “Publish please”. This authorizes publication of the unchanged verified local 0.1.28 candidate; it is not a statement that listening/30-swipe acceptance passed. Source hashes and both APK hashes were rechecked before publication. Earlier “do not publish yet” instructions in this chronological report are superseded by this owner authorization.

## Published — 3 November 2026

0.1.28 is public and verified as GitHub latest: https://github.com/uulabs/Doomscroller/releases/tag/v0.1.28 . Release source commit bd6e89c was pushed over the configured SSH remote. The three APK assets and SHA256SUMS were downloaded from the public release and matched local hashes. Receipt: `/Volumes/970P/UULabs/Artifacts/UUDoomscroller/0.1.28-local-candidate/published-receipt.json`. The earlier unpublished/release-blocked descriptions are historical and superseded. Publication followed the owner’s explicit instruction; no extra audible acceptance claim is implied. No new device interactions were needed for publication. The owned S98 lease was released; production 0.1.28 remains installed, and the force-stopped diagnostic app remains available for follow-up evidence.
