# Validation record — 22 October 2026

## Version 0.1.3 update

- Built and installed on the connected Pixel 7 without clearing saved sessions. Lint passed; 10 JVM checks and 9 synthetic image/storage instrumentation checks passed.
- Added full processing-resolution comparison loading, 2×/4× zoom, shared panning, and a cyan reference-confidence overlay. New sessions record baseline pixel-change diagnostics; these are not quality scores.
- Fixed the comparison resize filter to match preprocessing (INTER_AREA). A new regression check verifies exact comparison/result equality when a reference is rejected on a downscaled canvas. Fusion diagnostics also verify zero changed pixels outside confidence support on a synthetic fixture.
- Actual saved capture metadata confirmed sequential delivery from both physical lenses and accepted reference fusion: one session had 798/1045 homography inliers, approximately 10% reliable canvas coverage, and approximately 1.79 seconds between exposures. The pair request configured but lost the main buffer; the automatic sequential fallback completed. Only metadata was inspected; camera images remain on the phone.
- Preview orientation still needs the user's visual confirmation. No measured photographic improvement or compatible ONNX model inference is established.

Current v0.1.3 APK SHA-256:

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
