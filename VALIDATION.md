# Validation record — 22 October 2026

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

## APK

`app/build/outputs/apk/debug/app-debug.apk` — universal debug build, approximately 253 MiB, including OpenCV and ONNX Runtime native libraries.

SHA-256:

```text
423049c3a481856a7fa576ba6abb6d6e8e8f3b7eaa8b5a4b8c872a738d7d195b
```

See [README](README.md) for Pixel installation, capture instructions, local storage, limitations, and the ONNX adapter contract.
