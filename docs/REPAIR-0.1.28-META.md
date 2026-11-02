# 0.1.28 follow-up repair — 3 November 2026

**Current checkpoint:** the rendered-search experiment below was rejected and removed. A bundled public permalink cache now supplies the identical release/debug discovery path; see META-DISCOVERY.md. S98 Instagram-only admitted nine clips across nine forward swipes, with moving-frame verification and a measured 1280×720 landscape rejection. Facebook-only admitted seven clips across six forward swipes, also with moving-frame verification. These are diagnostic acceptance observations, not a published replacement. The historical blockers below are retained as chronology and are superseded by this checkpoint. Mixed-source stress and final signed-release checks remain pending.

Owner reports Meta-only feed stuck on doomer, TikTok provider bar visible and progressive scrolling issues. 0.1.28 remains the published release; these repairs are uncommitted and unpublished. Its earlier fixed-clip tests did not establish real discovery reliability.

## Confirmed findings

- Instagram/Facebook Java regexes contained four backslashes before the hostname dot instead of two. Ordinary reel URLs were missed.
- A zero-result Meta query disabled that source for ten minutes. Empty fill() completed and posted pump(), which called fill() again, creating an unbounded empty-discovery scheduling loop. This is a concrete CPU/UI-churn mechanism; it has not yet been proven to explain every reported scroll defect.
- Debug-only seed IDs masked release discovery failures. They have now been removed from normal source code, so debug and release exercise the same discovery path.
- Meta fetched/removes batches of six pooled links, but produce() returns after the first successful resolution. Remaining links in that local batch were discarded. Now only one pooled item is removed per draw.
- TikTok URLs omitted controls=0. Official documentation says controls defaults to 1: https://developers.tiktok.com/docs/en/embed-player . Both URL constructors now specify controls=0; no mute parameter or audio-script rewrite was added.
- Host live Brave requests returned 429 for both Meta sources. DuckDuckGo HTML returned ten real links each for the technology query. Both tested Facebook permalinks resolved sd_src URLs on the host. This is extraction evidence, NOT device playback or speech/audio acceptance.
- Actual seed-free S98 search returned Brave 429 and DuckDuckGo 202 for an ordinary sampled subject (geragogy), then no candidates. Therefore the added independent index fallback is not sufficient to claim reliable Meta on-device operation.

## Current local repairs

Main.java: discovery retry pacing, waiting until all-selected-source retry deadlines, cancellation on destroy, immediate retry reconsideration after settings changes, existing loader text explains selected sources unavailable, corrected null/finishing guard for Instagram resolver. Sources.java: regex repair, independent index fallback, URL-decoded uddg link support, per-index HTTP backoff, volatile/atomic retry-state snapshots, one-at-a-time pool consumption, automatic debug seeds removed, controls=0 for TikTok. New source cooldown checks do not block other eligible sources or nonempty pools.

Captured-response tests compile the actual Java patterns and Sources class: both index fixtures yield ten links; blocked Meta waits approximately ten minutes, enabling YouTube returns zero delay, a pooled Facebook item remains eligible. Diagnostic build succeeds, git diff --check passes. Latest diagnostic APK is preserved here with source patch and hash manifest. Production version remains 0.1.28; do not publish changed bytes under that version/tag.

## Device testing limit

Several natural-feed scrolling attempts stopped BEFORE swiping because S98 was not resumed/foreground. Power/window state showed sleep timeout, NotificationShade focus and mDreamingLockscreen=true. Immediate screenshots after wake were black. Crash buffer was empty. These are invalid swipe tests and must not be counted as app freezes or successful checks. Device unlocking requested from owner. Source-status logs did execute behind the lock screen; they establish HTTP responses, not visual playback.

S98 production app was force-stopped for isolated diagnostic testing; its installed 0.1.28/data were not replaced or cleared. The .diagnostic app alone had its test settings changed. Blade untouched. No visual verification of controls=0 or sustained natural scrolling has succeeded yet.

## Required continuation

1. Obtain S98 unlock, acquire/check the device lease, install the preserved diagnostic or rebuild current source with only .diagnostic suffix/logging. No implicit seed list. Test TikTok-only actual discovery, verify provider bar absent while original native bar remains, sound/autoplay and at least 30 real swipes including back/forward.
2. Test selected-source cooldown UI and CPU after all indexes refuse. Changing sources must immediately wake discovery. No busy retry while cooldown is active.
3. Owner was asked whether to allow a broad periodically refreshed public reel-link catalogue fallback or keep live discovery only with honest unavailable state. No answer yet; no catalogue or new service has been created. Do not call live Meta reliable while both indexes refuse device requests. If catalogue is accepted, implement an auditable broad refreshed source rather than a tiny hard-coded seed demo, then prove unseeded/cold-start release behaviour.
4. Test Instagram/Facebook-only end-to-end: discovery, native resolution, measured geometry, playback, repeat swipes, exhaustion/retry. Native stream extraction on the host is insufficient.
5. Only after actual validation build a new higher-version release; never replace published 0.1.28 assets silently. Preserve original playback bar and audio behaviour.

Latest account reading: 79% remaining, not the requested 65% threshold. Current blockers are device unlock and fallback preference; do not claim a completed repair or invent acceptance. Evidence and current source held for review; no new deletion in this checkpoint.

Device checkpoint settled: diagnostic app force-stopped; owned S98 lease released pending unlock. No owned build/test process remains running. Latest diagnostic APK is built but not installed over production.

## Unlocked S98 verification — 3 November 2026

Owner unlocked S98; foreground was confirmed before every swipe. Latest local diagnostic uses actual TikTok discovery, no injected clip sequence and no automatic Meta seeds. Production 0.1.28 has not been replaced. No additional release published.

First natural run: 30 forward swipes, 22 promotions, no permanent stall. TikTok provider playback bar absent, original Doomscroller bar visible in captured screens. Some loading delays remain; this is not proof of uniformly smooth transitions or speaker sound. WebView counts at 0/9/19/29 were 6/11/18/21; PSS 342370/337994/356742/384887 KiB.

A targeted additional repair assigns pending-geometry timeouts and active dwell timers to their Slot using Handler token scheduling, cancelling them on promotion, retirement, rejection, history replacement and destruction. Delayed animation/player release tasks remain uncancelled. Native pending timeout now uses the common rejection path, preserving backward-navigation direction.

Second natural run: 30 forward swipes followed by six alternating back/forward swipes; 32 promotions. Final replay alternated 7530323819823680774 and 6991202494126820613 three times as expected. WebViews at 0/9/19/29 were 4/3/2/4, with one Activity throughout. After reversals, WebViews briefly reached 14; a subsequent naturally settled sample returned to 3 (PSS 332575 KiB). These are observations, not a claim that all memory/performance problems are eliminated. A proposed forced-GC diagnostic to /dev/null was rejected by Android SELinux before it ran; the settled count of 3 was captured BEFORE that failed command, so no forced-GC result is claimed and no heap dump was produced.

Meta-only unlocked run: one failed discovery attempt and no repeated fill/pump churn over the 30-second observation. The existing loader label now reads selected sources unavailable/retrying automatically. No Meta video was promoted. The saved cpuinfo row referenced the prior process PID, so it is not evidence of the new process's CPU percentage. HTTP-refusal evidence from the earlier instrumented run remains the basis for the discovery blocker.

Evidence is in Artifacts/UUDoomscroller/0.1.28-meta-repair/unlocked-checks, including before/after screenshots, traces, memory snapshots, current repair patch, Main/Sources snapshots, built diagnostic APK and hashes. Build and diff whitespace check passed. S98 is left in the separate diagnostic app for observation; no further host test is running. The device lease is released at this checkpoint. The fallback-catalogue preference remains unanswered: no catalogue or service has been introduced, and Meta end-to-end acceptance remains outstanding.

## Continued repair — live rendered search, not a bundled reel catalogue

Owner directed continued repair. A normal-UA standalone Android WebView on S98 rendered Google results for Instagram cooking and Facebook wildlife, where plain HTTP did not. Three ordinary Instagram result redirects resolved to real reel URLs on the host. Internet Archive CDX also returned hundreds of candidate URLs, but many are old and no archive-based production fallback was adopted. Public profile embeds did not reveal candidate IDs; Common Crawl requests timed out.

Current uncommitted experiment adds request-owned ReelsSearchResolver and Google public search as a third, paced fallback after Brave/DDG. It does not interact with login, consent or bot challenges. Ordinary result redirects and HTML destination notices are parsed. It is not yet accepted: the first mixed-source run admitted two Facebook items with native portrait sizes, but its initial screenshot was behind the startup notice. That is decoder evidence, not visual playback acceptance. The notice was subsequently dismissed. Instagram searches returned unrelated web results and no items. A repeated Instagram-only run is still being investigated. Do not publish this experiment or claim Meta fixed from these results.

InstagramResolver now owns each request's result, WebView and callbacks; timed-out callbacks cannot complete/destroy a later request, and Activity destruction closes it. This code builds but its regression checks and real Instagram playback acceptance are pending.

Sparse Meta indexing also motivates localized broad retry subjects: 491 root labels from Wikidata, matching the existing 33 families and 15 languages. Main starts Meta searches broadly, returns to detailed terms after candidates are found and broadens after an empty attempt. No viewing preference learning or fixed creator/video list. Asset generation is reproducible with tools/refresh-topic-families.py under the 970P wrapper. Four missing labels fall back to the already selected localized detailed term. This supply change remains part of the unpublished experiment.


### Mixed-source acceptance, 3 November 08:26–08:32 AEDT

Latest diagnostic completed 32 forward swipes and six alternating back/forward swipes with Instagram, Facebook and TikTok enabled. Final six promotions alternate TikTok:7541498962910530838 and Instagram:DLIzWfhNoxE three times. No permanent feed stall or app crash was observed. Original native playback bar is visible in the final Instagram capture; TikTok frame captures show no provider playback toolbar. Logcat is a rolling buffer, so its final count is not a full-session promotion total.

Memory at rounds 3/6/9/12/15/21/27/30: WebViews 1/2/1/3/1/1/2/2, one Activity throughout; PSS at round30 was267132KiB. Android reported an active media track, not proof of audible speaker output. Network waits still occur; do not claim perfectly smooth or instantaneous scrolling. Selected evidence is preserved at Artifacts/UUDoomscroller/0.1.29-local-candidate/evidence/mixed-feed.

Signed 0.1.29/code30 standard and selfupdate candidates built successfully, release lint passed, signer matched 93b7263c3359ff80b7c071cce7e2a648bc49f2e9bf547fcaf2b0c01e56a16358, application ID and packaged discovery assets matched production. Candidates are held externally; publication is still pending final settings/rotation/release-install checks.


### Source settings and landscape checks

Disabling TikTok through settings, with YouTube already off, produced six subsequent Meta-only promotions including backward/forward replay. The initial host assertion failed because its string-prefix subtraction assumed logcat retained the entire earlier buffer. Preserved evidence was revalidated against the recorded 08:32:30 start boundary; all six later promotions are Instagram/Facebook. This is a harness correction, not an app failure being hidden.

An explicitly enabled diagnostic-only 640x360 stream measured ratio1.7777778, displayed letterboxed with the rotate button already visible in portrait orientation. Turning landscape off reported active=true, verified=false, known=true, ratio1.7777778. The immediate post-toggle screenshot was black and is not claimed as a complete visual recovery test. Real Instagram portrait-only testing separately rejected1280x720 before promotion. Production never enables the debug probe.

The exact signed 0.1.29 recommended APK was installed with adb install -r on S98, preserving production data. Installed base.apk bytes matched the held candidate hash. Existing source choices (including X enabled) and playback-bar-off preference were observed after update; temporary smoke-test changes will be restored. Production release Meta-only visual smoke is in progress.


### Signed release acceptance and publication preparation

The exact signed 0.1.29/code30 recommended APK played Instagram and Facebook in a Meta-only release smoke on S98, with five forward swipes and paired frames showing motion for both providers. Original native controls and plain source names were visible when temporarily enabled. Crash log for the release process was empty. Owner's original YouTube/TikTok/X/Instagram/Facebook enabled, PeerTube disabled, landscape disabled and playback-bar disabled choices were restored after testing. No production data was cleared. Speaker listening and Xiaomi15Ultra acceptance remain unverified; network loading gaps remain possible.

Owner again explicitly requested publication. Publish the held, verified artifacts as v0.1.29; do not rebuild or overwrite earlier version assets. Recommended/compatibility SHA256: bc4befc5d669aadeb6b6a946afc8f9be8c2fa3989c4e4e086437c8ed952bfd32. Manual-update SHA256: ef52dadc7fb270657c322ca03730af99f7d9cf5de3947998bba2e00b87f8ef21. Authoritative artifacts: /Volumes/970P/UULabs/Artifacts/UUDoomscroller/0.1.29-local-candidate.


## Publication complete

0.1.29/code30 published from5c842a6, public latest and downloaded hashes verified. Signed release tested onS98; settings restored, lease released. See RELEASE-0.1.29-RECEIPT.json and HANDOVER-DEEPY.md.
