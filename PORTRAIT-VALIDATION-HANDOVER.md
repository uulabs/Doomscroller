# Portrait-only playback: code review and implementation instructions

Date: 2 November 2026 (Australia/Sydney)
Reviewed checkout HEAD: `999ac0cae2d04f37218d6e056b327846279029c1`
Scope: source review and implementation handover only. No app source changes, builds or device tests performed. Line references describe the working files read during review and may move; locate the named methods before editing. HEAD identifies the checkout, not proof that working files match the commit.

## Required outcome

When “Allow landscape videos” is off, do not reveal or audibly play a landscape video. Verify the actual playable video's display aspect ratio before promotion into the visible feed. Unknown dimensions are an unverified state, never evidence of portrait orientation.

Keep the existing treatment of square clips as allowed for this fix (display width <= display height). Strictly taller-than-wide would be a separate product change. Landscape imagery baked into a portrait canvas with bars is outside scope: stream dimensions cannot identify the inner picture.

Use FIT presentation as the safe default for all clips, accepting bars where aspect ratios differ rather than cropping content. Retain portrait activity orientation when landscape is disabled and the existing user-controlled orientation when enabled. A custom rotate button is not needed for the preferred fix. Android's system rotation suggestion is device/navigation dependent; do not promise it always appears.

## Confirmed findings

| Location | Finding and consequence |
|---|---|
| `Sources.java:65`, `Item.portraitOk()` | Returns true when either dimension is unknown. Useful for allowing discovery candidates through, but insufficient as a final playback check. |
| `Main.java:109`, `produce()` around 763, `resolve()` around 771 | Mutable `lastLandscape` is copied into `Ready`. Facebook and Instagram return URLs without assigning it. These items inherit the last resolver's classification. This is stale shared state, even with serial resolution. |
| `Main.java:794` and 826 | YouTube and MP4 branches use resolver/item dimensions; neither provides a universal decoded-stream check. |
| `Main.java:1382` | `r.landscape` decides FIT versus ZOOM; unknown or stale false classifications select ZOOM. |
| `Main.java:1389`, player listener | No `onVideoSizeChanged` validation. State callbacks ignore pending slots, and STATE_READY is treated as rendered content. |
| `Main.java:1440–1462`, `pump()` | Preparation is followed immediately by `next()` when active is absent or a swipe is waiting. Pending is not synonymous with verified or ready. |
| `Main.java:1568`, `next()` | Promotes any pending slot, makes it visible and unmutes/plays it without an aspect-ratio gate. |
| `Main.java:1601`, history push | Saves every old clip as `new Ready(..., false)`, losing its landscape classification. Back navigation can therefore select ZOOM for a previously landscape clip. |
| `Main.java:1932`, `toggleSettings()` | Closing settings changes activity orientation and search filtering but does not revalidate active, pending, queued or history entries. Previously admitted landscape clips can survive disabling the setting. |
| `Main.java:1255`, WebView branch | Provider frame players bypass native ExoPlayer validation altogether. TikTok cannot be ignored when claiming portrait-only works across the app. |
| `Main.java:1921`, `applyOrientation()` | Portrait lock is deliberate when landscape is off. The primary defect is content admission, not this lock. |

## Implementation sequence

### 1. Make orientation evidence local to a candidate/slot

Remove `lastLandscape`. Have resolution return a structured result, or construct `Ready` with local metadata. Represent UNKNOWN explicitly, with any resolver dimensions retained as hints. Facebook/Instagram must begin UNKNOWN when only a stream URL is returned.

Add slot-owned display dimensions/aspect ratio, verification status, preparation status and disposed/generation state. All promotion and player mutations should run on the main thread. Preserve measured metadata in history for diagnostics/layout, but reverify a newly prepared historical stream before admission.

Keep discovery-stage checks as an optimisation. Rename/document `portraitOk()` so callers cannot mistake “not known landscape” for “verified portrait”. Do not reject all unknown items at discovery: Instagram/Facebook need to reach preparation to learn their size.

### 2. Verify native playback using Media3's output size

The project declares Media3 1.11.1 and minSdk 26 in `app/build.gradle`.

Add `Player.Listener.onVideoSizeChanged(VideoSize)` for each native slot and read `player.getVideoSize()` when evaluating readiness. Store the latest measurement and reevaluate admission from BOTH size and playback-state callbacks; callback ordering must not matter.

Use the output display ratio `width * pixelWidthHeightRatio / height` with double precision. Non-positive dimensions or a non-finite/non-positive pixel ratio remain UNKNOWN. Ratio <= 1 admits square/portrait; ratio > 1 is landscape.

Media3 documents rotation as internally applied and the deprecated unapplied-rotation field as zero on supported Android versions. Do not swap the output dimensions again using raw container rotation. Verify with a rotated-metadata fixture. See [VideoSize reference](https://developer.android.com/reference/androidx/media3/common/VideoSize).

Do not infer aspect ratio from the PlayerView size, thumbnail size, URL, provider label or “Reel/Short” classification. Avoid a second full media download or a new probing player when the existing prepared player can supply the information.

### 3. Gate promotion, not just discovery

Centralise a predicate such as `canPromote(slot)` and a helper such as `maybePromotePending()`:

- Slot is current, undisposed and prepared sufficiently for playback.
- If landscape is off, output dimensions are verified and ratio <= 1.
- Current preferences are reread at admission, not captured only during discovery.
- If landscape is on, unknown dimensions need not block forever; FIT remains mandatory and size callbacks still update metadata.

Set pending ownership BEFORE initiating preparation so callbacks can identify the slot. Keep unverified slots hidden and silent, with playWhenReady false. Use a preparation surface that actually permits video-size discovery; test whether the current GONE PlayerView delays surface creation. If it does, prepare behind an opaque loading cover with a valid surface, without revealing or audibly starting the candidate.

Change `next()` so unverified pending content sets/retains `wantNext` and waits; it must not reveal/unmute it. A verified slot should promote automatically if there is no active clip or a next request is waiting. Preserve swipe direction for that eventual promotion. A prefetch that becomes ready must not interrupt an active clip unless advancement was requested.

Keep a bounded deadline (the existing 25-second preparation budget is a reasonable initial bound) covering BOTH readiness and verification. STATE_READY with UNKNOWN dimensions must still expire in portrait-only mode. Cancel deadlines when slots are released/promoted; stale callbacks must not touch newer slots.

Separate “ready”, “orientation verified” and “first frame rendered”. Use `onRenderedFirstFrame` for first-frame bookkeeping rather than setting rendered=true solely on STATE_READY. Allow the loading cover to remain until the admitted clip has a frame. Ensure this also works when a first frame was rendered while prefetched.

### 4. Reject safely and continue

Provide a single idempotent slot disposal path: mute/pause, cancel callbacks, release player or silence/destroy WebView, remove its view, and clear matching ownership only. Avoid double release from error, timeout, transition-animation and rejection callbacks.

Reject landscape or expired-unknown pending clips without calling a generic next path that could accidentally promote them. Schedule another pump/fill, retaining the outstanding next request. Use bounded retry/backoff so an all-landscape or broken source cannot spin indefinitely.

Continue checking output size after admission (adaptive streams can change). If an active clip becomes landscape while disallowed, immediately cover/hide and mute/pause it, dispose it safely, then request a replacement. Do not add rejected/unverified content to playback history.

When a swipe leaves no usable pending clip, silence/pause the outgoing active clip; the current no-pending branch only slides it away. Avoid hidden playback during the wait.

### 5. Handle the WebView path explicitly

Do not leave `frame` items automatically classified as portrait. For an accessible actual HTML video element, obtain intrinsic `videoWidth`/`videoHeight` after metadata becomes available; CSS/layout dimensions do not prove orientation. Keep the provider muted and covered until verification. Associate each result with the correct slot/navigation and ignore late results.

If the actual media is inside an inaccessible cross-origin iframe, or the provider gives no trustworthy size signal, classify UNKNOWN. In portrait-only mode, skip after a bounded attempt, with a source cooldown where appropriate. Do not silently exempt TikTok. Its availability may decrease; report this honestly.

Audit the existing `JS_ACTIVE`, `JS_IDLE`, autoplay setup, unavailable-page callbacks and delayed scripts: they must not unmute, reveal, advance or otherwise mutate a different active slot. All playback paths, including provider pages, must honour the opening disclaimer gate, user pause, settings and background state.

### 6. Settings, history and presentation

On disabling landscape, validate the active clip before resuming it when settings close. Stop/hide an active landscape or unverified clip and request a replacement. Reevaluate pending and queued entries; in-flight worker results must encounter the same latest-preference gate. Do not rely only on clearing the queue, because an already-running producer can refill it.

Back navigation must pass through the same gate. Skip a now-disallowed historical item and continue searching history or fall back gracefully; do not loop forever. Preserve history direction and avoid inserting rejected entries.

Replace the current conditional ZOOM/FIT selection with FIT for native video. Ensure provider-page presentation does not force a crop. Keep `applyOrientation()` policy as described above. Enabling landscape should admit both orientations and follow the device's user rotation setting, without forcing sensor rotation over a user lock.

## Acceptance checks

Use deterministic local fixtures for logic/player checks, then smoke-test real providers. Live random feeds alone cannot reliably reproduce these cases.

| Case | Required result |
|---|---|
| 1080x1920 portrait, 1920x1080 landscape, square | Portrait/square accepted; landscape rejected before visible frame/audio with landscape off |
| Unknown resolver dimensions, real stream landscape | Rejected by player gate; no portrait assumption |
| Landscape YouTube followed by Instagram/Facebook, and reverse sequence | No inherited orientation or layout state |
| Encoded landscape dimensions with 90/270-degree rotation yielding portrait display | Correct output classification without double rotation |
| Non-square pixels changing the display aspect ratio | Classifies by displayed geometry, not raw width/height |
| STATE_READY before size, and size before STATE_READY | Same admission result; no premature promotion or stalled valid clip |
| STATE_READY with permanently unknown dimensions; audio-only media | Bounded rejection in portrait-only mode; no endless pending slot |
| First app launch and repeated fast swipes while preparing | No invalid flashes/audio; latest waiting transition completes |
| Landscape enabled, then disabled with active/pending/queued landscape content | Immediately enforced before resume/promotion, including late worker results |
| Back to a landscape clip after disabling landscape | Clip skipped; history cannot bypass policy |
| Adaptive size change to landscape | Active clip is covered/silenced and replaced |
| TikTok verified portrait, verified landscape, inaccessible video size | Same policy as native sources; inaccessible size cannot pass as portrait |
| Landscape allowed, portrait and landscape device orientations | FIT shows full content; device rotation policy respected |
| Timeout/error/rejection racing with back, settings or destruction | No double release, stale promotion, hidden audio or crash |
| Pause, opening disclaimer, backgrounding during preparation | No callback starts playback against current playback gates |

Add focused classifier and admission-state tests rather than broad unrelated refactoring. Exercise actual player behaviour on an authorised test device, particularly surface creation and callback timing. Record provider/ID, measured dimensions, classification and rejection reason in debug diagnostics; avoid logging signed stream URLs or cookies.

## Delivery and scope boundaries

Keep the repair focused on admission, slot metadata/lifecycle and presentation. Do not alter the working Facebook/Instagram discovery implementations unnecessarily. Do not implement image-analysis detection of letterboxed inner content or a new rotate-button feature in this patch.

Report changed methods, deterministic test results, provider smoke-test results and any remaining WebView limitation. Do not claim universal portrait-only support if any player route bypasses verification.

Follow the owner workspace rules for any subsequent work: 970P-only scratch through `tools/run-on-970p.py`, applicable build scheduling/device ownership, and scratch retention/cleanup. This review created only this handover file; it generated no scratch runs or builds and deleted nothing.
