# Pixel 7 deployment follow-up — 25 October 2026

- Connected to the user's authorized Pixel 7 through an ADB server started in the normal Windows session. Direct sandbox USB access was denied, despite the USB driver working.
- The installed v0.1.6 certificate differed from the new local debug certificate. Built with `-PcameraPreview=true` and installed `com.example.referencecamera.preview`, labeled **Reference Camera AI Preview**, version **0.1.7-preview** (versionCode 8). This preserves the differently signed original app and all ten of its existing capture directories.
- Copied only the original app's camera-strategy preferences so remembered dual-delivery failures remain effective. No camera images were copied or deleted.
- Ran all 19 Android instrumentation tests on the Pixel: **19 passed**, 0 failures, in 10.344 seconds. The checks included actual bundled ONNX inference on a small synthetic image, checksum verification, exact zero-confidence preservation, custom-reference-model inference, non-finite output rejection, disabling, cancellation, OpenCV pipeline/storage tests, and stage exports. No real photographs were taken for these checks. See docs/validation/pixel-onnx-instrumentation.txt.
- Removed the temporary test harness and successfully launched the preview MainActivity. Android reported a 786 ms cold activity launch and the preview as the top resumed activity. This is launcher timing, not AI inference latency. No AndroidRuntime crash was reported for the launched process.
- Real-capture model latency and photographic improvement remain unmeasured. See docs/validation/pixel-onnx-deployment.json for the deployment record.

# Version 0.1.7 — bundled ONNX restoration — 25 October 2026

- Added an actual pretrained Real-ESRGAN general x4v3 ONNX graph (4.87 MB), exported from the authors' checkpoint with unchanged weights, a pinned source checksum, and the upstream BSD 3-Clause notice. The phone has no runtime model download or INTERNET permission.
- This is single-image neural restoration after OpenCV camera fusion. The neural network does not directly consume the main-camera image. Native 4x output is area-resized back to the existing canvas; it is not a full-resolution 4x output claim.
- Added bounded tiled CPU inference, per-tile progress, cancellation between tiles, a persistent AI toggle, custom-model validation before replacement, and restoration of the bundled model. Zero-confidence pixels are preserved exactly by the final blend.
- Saved stages now include prepared inputs, a lossless aligned reference, the confidence visualization, OpenCV baseline, model proposal, blended AI result, and final image. Stage selection, sharing, export, legacy sessions, and stale-stage cleanup are supported. Model ID/checksum, tile count, and stage duration are saved in processing metadata.
- Host model checks passed: maximum absolute PyTorch/ONNX error 4.083e-6; tiled/full-frame discrepancy 0.0 on the synthetic fixture; zero-confidence tiles skipped and outside-mask pixels preserved. See docs/validation/onnx-host-checks.json. These are numerical implementation checks, not photographic quality scores.
- Final build checks passed: :app:assembleDebug, :app:assembleDebugAndroidTest, :app:lintDebug, and :app:testDebugUnitTest. All 13 JVM tests passed. Lint reported 0 errors and 7 dependency-version warnings. APK signature and packaged model/license were verified. See docs/validation/onnx-build-checks.json.
- The 19 Android instrumentation tests compiled but were not executed. No Android device was visible in Windows device enumeration; ADB also could not initialize its default .android directory within the sandbox. No phone installation, camera capture, device latency test, or real-photo quality assessment was performed.
- Verification used an isolated JDK 17 and Android SDK 35 with build-tools 34.0.0 under the task's work directory. local.properties was updated to that SDK. Java and Gradle environment settings were scoped to the verification processes.
- This is a new local debug signing key; compatibility with any already-installed APK has not been checked. Preserve app-private captures before any future installation workflow that might require uninstalling.

APK SHA-256: 5bb910879a91c4619ee4b9ab1d0b3db637c46e3f49adb71cb7de9801d06a3218

# Code review follow-up — 25 October 2026

- Reviewed the uncommitted output-stage changes and their interactions with processing, saved-session reopening, sharing, and export.
- Fixed stage exports: Share stage and Save stage now use the image selected in the result screen. The document-picker callback remembers the requested stage, and export accepts only known output files for the current result.
- Preserved older AI sessions: when `aiApplied` is true and `onnx-result.png` is absent, the existing `result.png` is treated as the AI stage. Missing baselines can fall back to displaying the saved final image.
- Prevented stale model stages: loading checks the session's `aiApplied` metadata, and a successful baseline-only retry removes a previous standalone ONNX result.
- Added regression tests for these storage/export paths and for synthetic model-stage saving followed by a no-model retry. The synthetic enhancer is a test double, not an ONNX model or a quality demonstration.
- Source review and `git diff --check` completed. Attempted `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest --offline`, but Gradle stopped before running because `JAVA_HOME` is unset and no `java` executable is available on PATH. The configured Android SDK path also belongs to the prior machine's user folder. No build, lint, JVM tests, or Android instrumentation tests passed as part of this follow-up.
- No trained ONNX weights were added, no camera capture was performed, and no photographic quality improvement was established. Earlier validation records below describe earlier versions and environments.

# Validation record — 22 October 2026

## Version 0.1.6: callback workload and timing diagnostics

- Removed JPEG compression from image callbacks. Only selected inputs are JPEG-compressed, outside the camera callback thread. A bounded rolling collector selects two images with exact physical timestamps from a common capture result; its window retains at most three NV21 copies per lens and twelve metadata candidates. The existing 50 ms pair-gap limit remains in force.
- Explicit dual retries stop starting additional size attempts once a 10-second search budget is exhausted. An active attempt and resource cleanup can exceed that budget. Existing remembered failures are preserved, so the user's ordinary next capture stays sequential rather than rerunning the experiment.
- New result screens and metadata separate capture duration from processing duration. Actual dual delivery for this implementation remains unconfirmed until an explicit retry is requested in Settings.
- Build/lint passed (0 errors; dependency-version warnings remain). v0.1.6/versionCode 7 was installed and launched on the Pixel without clearing data; its saved dual-failure preference was confirmed present after updating. A fresh normal-capture duration check is pending. Automated tests were not run for this version.

Current v0.1.6 APK SHA-256:

```text
5481eeaa16c97bd5b189dd4a45ce1a4dbdb8bf8a32fe54dd3a21b662c917d50e
```

### Actual v0.1.5 capture observation

All three dual sizes timed out. At 2048×1536 and 1280×960, both lens image callbacks and physical metadata were present, but a timestamp-matched pair was not selected; this is not evidence that the hardware cannot deliver both streams. JPEG compression in each incoming-frame callback and pinning the first result were identified as application-side weaknesses and changed in v0.1.6. Their contribution to the failure has not been measured separately.

The fallback completed: session creation to both input saves was approximately 40 seconds; output metadata was written approximately 1–2 seconds later (second-resolution modification time). Sequential exposure gap was 1432.493094 ms. Confidence covered 27.60% of the canvas, 21.75% of pixels changed by the diagnostic threshold, and zero changes occurred outside mask support. Failure persistence was confirmed in the app's preferences. Photos stayed on-device.

## Version 0.1.5: logical preview experiment

- Added a logical PRIVATE output alongside both physical YUV outputs. The logical frames are discarded, never saved as a lens input. This is an additional configuration experiment, not established simultaneous-capture support. The startup probe still checks only the two physical outputs; actual capture configures all three outputs.
- Dual-delivery failure is remembered across restarts for the strategy, camera routes, and OS build. Subsequent normal captures use the sequential path directly. Settings provides an explicit retry action. The first diagnostic attempt can still be slow if all candidate sizes time out.
- Richer per-attempt diagnostics include the selected/observed image timestamps. Existing inputs/results remain on-device.
- Build and lint passed (0 errors, 7 dependency-version warnings). Installed versionCode 6/versionName 0.1.5 was confirmed on the Pixel. Automated tests were not rerun. Actual delivery for the new three-output configuration is still pending a user-triggered capture.

Superseded v0.1.5 APK SHA-256:

```text
573fedfe9bf8b7438dbac1ba06b2fe391502252a09b0f7dcbbb0505efef365ee
```

## Actual v0.1.4 capture observation

The user triggered a capture. Both-only repeating streams timed out at 2048×1536, 1280×960, and 640×480. Last-attempt diagnostics showed main-camera image callbacks and no ultrawide images; no usable paired metadata was selected. The automatic sequential fallback then completed.

Session creation to both saved inputs was approximately 52 seconds, mostly unsuccessful dual capture attempts. The processing metadata file was written approximately 4 seconds after the main input was saved (file modification time is second-resolution, so this is an estimate). Sequential exposure gap was 1680.70615 ms. Alignment accepted 746/959 homography inliers; confidence covered 23.89% of the canvas, with 19.10% of baseline pixels changed by at least 2/255 and zero changed pixels outside mask support. These are activity/integrity diagnostics, not image-quality scores. No photos were copied off the phone.

## Version 0.1.4: dual repeating-stream capture

The high-level flow diagram was pushed to the main README before this work started.

- Replaced the pair's still-capture request with a repeating YUV request targeting both physical lenses. Both saved frames must match physical timestamps from the same capture result. Metadata from the logical camera cannot substitute for missing physical metadata in this path.
- Added descending common 4:3 stream-size candidates at caps of 2048, 1280, and 640 pixels. Candidates come from the intersection of both physical cameras' and the logical camera's advertised YUV sizes. Session probing uses the smallest candidate and explicitly does not claim frame delivery.
- Successful pairs must have an exposure gap of at most 50 ms, a POC acceptance threshold. Actual size and timing are recorded; exact exposure synchronization remains device-dependent. Failures are saved in `pair-attempts.json`, followed by the existing automatic sequential fallback.
- Image callback work is bounded so repeating frames do not indefinitely block metadata handling. Buffer-loss callbacks for other frame numbers do not reject an already selected frame pair.
- Build and lint passed (0 errors, 7 dependency-version warnings); v0.1.4 was installed and launched on the Pixel without clearing saved sessions. The new on-device report confirmed a 640×480 dual session configured with query=true, and candidates 2048×1536, 1280×960, and 640×480. Real dual image delivery and its measured timing still require a shutter capture; older sequential results do not validate this new path. No new photographic quality claim is made. Automated tests were not rerun for this version.

Superseded v0.1.4 APK SHA-256:

```text
fdda14f11a32c1a8741d2bb60e692c10aa6afb88be095923441d8298ae03d0c7
```

## Version 0.1.3 update

- Built and installed on the connected Pixel 7 without clearing saved sessions. Lint passed; 10 JVM checks and 9 synthetic image/storage instrumentation checks passed.
- Added full processing-resolution comparison loading, 2×/4× zoom, shared panning, and a cyan reference-confidence overlay. New sessions record baseline pixel-change diagnostics; these are not quality scores.
- Fixed the comparison resize filter to match preprocessing (INTER_AREA). A new regression check verifies exact comparison/result equality when a reference is rejected on a downscaled canvas. Fusion diagnostics also verify zero changed pixels outside confidence support on a synthetic fixture.
- Actual saved capture metadata confirmed sequential delivery from both physical lenses and accepted reference fusion: one session had 798/1045 homography inliers, approximately 10% reliable canvas coverage, and approximately 1.79 seconds between exposures. The pair request configured but lost the main buffer; the automatic sequential fallback completed. Only metadata was inspected; camera images remain on the phone.
- Preview orientation still needs the user's visual confirmation. No measured photographic improvement or compatible ONNX model inference is established.

Superseded v0.1.3 APK SHA-256:

```text
3aceb87d154810becc5fa56cc7bc6ea6c0a1d9c8a3942c5c2a95ec4c281bce93
```

## Version 0.1.2 update

- Built and installed on the physical Pixel 7. Android lint passed with 0 errors and 7 dependency-version warnings.
- 10 JVM checks passed: 3 YUV-plane, 4 preview-geometry, and 3 image/result timestamp-order checks.
- All 8 generated-image/storage instrumentation checks passed on the Pixel using direct `am instrument`, without uninstalling the target app or clearing its app-private files. The checks did not take camera photos or read the user's photos.
- The main interface now has a large viewfinder, bottom shutter, Results gallery button, and Settings. One shutter triggers both lenses, with an automatic sequential fallback after a pair failure. OpenCV processing starts after both inputs are saved.
- A persistent image listener is armed on the camera callback thread and correlates still images with capture metadata. Capture outputs explicitly use sensor timestamps on API 33+. Lost buffers, aborted sequences, and operation timeouts are reported. A failed attempt records expected/received timestamps for diagnosis.
- The result screen identifies whether reference fusion, optional ONNX enhancement, or retained ultrawide output was used. Optional AI import is under Settings.
- The layout was visually inspected in the emulator's unsupported-camera state: [camera UI](docs/validation/camera-v012-emulator.png). No live camera image from the user's Pixel was copied for this inspection.

Subsequent saved-session metadata confirmed successful sequential still capture and processing in v0.1.2 (see above). Physical preview orientation awaits the user's on-phone check. These observations do not establish photographic quality.

Superseded v0.1.2 APK SHA-256:

```text
e22da5ff73b369a508151656754d4c17d505250c6cd5cba86771331d44524900
```

## Version 0.1.1 update

- Final build and Android lint passed (0 errors; 7 dependency-version warnings).
- 7 JVM checks passed: 3 YUV-plane checks and 4 preview-geometry checks.
- 8 generated-image/storage instrumentation checks passed on the connected physical Pixel 7 (runner reported Android 17). These exercise OpenCV and result reopening without reading the user's photos or taking real camera images.
- Runtime discovery on that Pixel exposed logical rear camera 0, with ultrawide physical ID 3 (2.35 mm) and main physical ID 2 (6.81 mm). The two 2048×1536 YUV output session configured; its support query returned true. Sensor synchronization is approximate.
- The user reported a sideways preview and "Capture interrupted" in v0.1.0. The update removes duplicate sensor rotation, compensates display changes, distinguishes camera-operation timeouts from lifecycle cancellation, and records the failed stage in `capture-error.txt`.
- A top-level Results list reopens complete comparisons after app restart, with exports available. ONNX import is explicitly optional.

The v0.1.1 APK was installed and launched on the Pixel 7. Physical preview orientation and successful still capture require a user check; successful session configuration and generated-image tests do not establish either.

Superseded v0.1.1 APK SHA-256:

```text
9cf1190a3f2822fd05374ada0625244cdaf15be169b21b568712a790a9fede38
```

## Original v0.1.0 validation

The source project and a debug APK were built locally. Automated validation used JDK 17, Gradle 8.14, Android Gradle Plugin 8.7.3, SDK Platform 35, and the existing `Medium_Phone_API_36.1` emulator. No physical Pixel 7 was connected during those checks.

## Completed checks

- `:app:assembleDebug`: passed for the final source.
- `:app:lintDebug`: passed with **0 errors and 7 dependency-version warnings**. The pinned dependencies have newer releases; lint reported no application correctness errors.
- `:app:testDebugUnitTest`: **3 passed**, 0 failures. Tests cover planar YUV with padded rows, interleaved chroma with nonzero buffer positions, NV21 V/U ordering, and crop offsets.
- `:app:connectedDebugAndroidTest`: **5 passed**, 0 failures. Synthetic checks cover zero-confidence exact preservation, detail transfer restricted to mask support, known central-crop alignment, featureless-reference rejection, and the complete file-to-result pipeline with orientation and no model.
- Final APK installed and launched on the emulator. The permission-not-granted screen showed the access action and app-settings link. After granting permission, discovery correctly rejected the emulator's single rear-camera endpoint, displayed a useful error, and persisted the Camera2 inventory. No application crash was observed in the inspected AndroidRuntime log.
- `aapt dump permissions` confirmed the packaged APK has no `INTERNET` permission. The app requests Camera plus AndroidX's own non-exported receiver permission.

UI evidence: [permission screen](docs/validation/permission.png) and [unsupported-camera screen](docs/validation/unsupported-camera.png).

## Not established by these checks

After the automated checks, the debug APK was successfully installed on a connected physical Pixel 7 using ADB and `MainActivity` was launched. Camera capture and the phone's runtime capability report were not inspected as part of that installation.

Physical Pixel 7 logical/physical camera exposure, physical preview support, the two-YUV stream configuration, successful pair or sequential captures, actual exposure skew, calibration accuracy, rolling-shutter behavior, and real photographic quality require running on the phone. The emulator exposes a single rear endpoint; it cannot establish those properties.

No ONNX weights were supplied. The model-absent baseline path was exercised; compatible-model inference, model latency, and model memory limits remain to be validated with a suitable trusted model. Synthetic alignment/fusion checks do not establish an improvement in image quality.

## Original v0.1.0 APK (superseded)

`app/build/outputs/apk/debug/app-debug.apk` — universal debug build, approximately 253 MiB, including OpenCV and ONNX Runtime native libraries.

SHA-256:

```text
423049c3a481856a7fa576ba6abb6d6e8e8f3b7eaa8b5a4b8c872a738d7d195b
```

See [README](README.md) for Pixel installation, capture instructions, local storage, limitations, and the ONNX adapter contract.


## UW camera 0.1.8: laptop admin and USB archival (2026-10-25)

Local dashboard and USB companion implemented. Nine desktop tests pass for exact metric values, archive checksums, missing/unsafe transfers, duplicate/damaged archives, explicit ground-truth registration, and cross-site rejection. Pixel instrumentation ordinary suite reports OK (24 tests; opt-in USB case skipped); the explicit synthetic USB round-trip then passed separately, verifying nine files and removing its temporary phone session only after acknowledgement. JVM tests pass. Browser checks covered rendering, loaded stage images, slider keyboard movement, zoom, difference view, search, QA filter, matrix, notes, reference dialog and settings. No browser console errors were observed.

The ARM64-only APK installed successfully after the universal debug APK exceeded Android's free-space installation budget. The bundled ONNX asset SHA-256 remains unchanged. Existing saved sessions in both packages were retained. The app launcher label and camera heading are UW camera; the preview package identifier is retained so its data and signing identity remain compatible.

The archived synthetic chart is explicitly marked QA and does not establish image-quality improvement. No matched regular Pixel Camera image was available to quantify the user's top/bottom framing observation. An existing app capture records a 2048x1536 stream and ultrawide crop equal to its reported 4032x3016 active array. This supports no explicit digital crop in our capture request; it does not prove equal field of view to Pixel Camera's processing.

See `docs/validation/desktop-usb-admin.json` and `desktop/README.md`. Real camera capture timing and quality remain device/scene dependent.
