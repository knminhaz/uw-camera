# Reference Camera POC

Native Kotlin Android research app for a Pixel 7. It captures a real ultrawide frame and a real main-camera reference, aligns their overlap, and transfers detail into the ultrawide canvas. All processing and storage stay on the device. No network permission, services, accounts, analytics, or model downloads are included.

**Device camera support and image-quality improvement have not been established on a physical Pixel 7.** The app discovers and probes support on the phone. A successful build or synthetic processing test cannot establish physical-camera availability, exposure synchronization, or real-world quality.

## Open and run

Requirements: Android Studio, JDK 17, Android SDK Platform 35, Build Tools (Gradle can install licensed missing components), and internet access for the initial Gradle dependency download. The checked-in Gradle wrapper uses Gradle 8.14. The app supports API 28+, targets API 35, and uses Camera2 directly.

1. Open this directory as an Android Studio project and allow Gradle sync. Set the local Android SDK location when asked; `local.properties` is machine-specific and ignored by Git.
2. On the Pixel 7, enable Developer options and USB debugging. Connect by USB, unlock the phone, and accept its debugging authorization prompt.
3. Select the Pixel 7 in Android Studio and press Run. Alternatively, from PowerShell with the SDK `platform-tools` on `PATH`:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   adb devices
   adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n com.example.referencecamera/.MainActivity
   ```

4. Allow camera access. Close Google Camera or another app if Camera2 reports that the camera is busy.

The app does not need photo-library or storage permission. Its inputs and results are kept in app-private storage. The Share action grants temporary access to the result; Android's document picker handles exports.

## Capture to result

1. The app lists logical camera IDs, their physical IDs, focal lengths, sensor sizes, YUV/JPEG stream sizes, and advertised concurrent logical-camera sets. It selects two distinct rear lenses using focal length relative to sensor width, preferring a shared logical camera. IDs and zoom ratios are never hardcoded.
2. It opens the logical camera, queries the two selected physical YUV stream configuration where the API implements that query, and attempts to configure that exact session. A successful configure is recorded as evidence for that particular combination of format and sizes. It does not establish support for every stream combination.
3. If the pair session configured, **Capture both lenses** warms up both cameras and sends a single request to two physical outputs. Exposure timestamps and synchronization metadata are saved. A pair capture failure visibly disables this mode and enables the two-shot fallback. Guided two-shot mode can also be selected manually.
4. Otherwise, **Capture ultrawide** saves the first image. The preview switches to the real main lens. Keep the phone in the same position, keep the scene still, then press **Capture main reference**. The sequential timing limitation stays visible on screen.
5. OpenCV processes the captured images on a worker dispatcher. The UI shows progress, an original/result comparison slider, processing notes, and export actions. Unreliable alignment returns the ultrawide baseline with an explanation.

The dual-session probe covers two physical cameras behind one logical camera. Separate logical camera concurrency is inventoried but this POC does not implement dual-device capture. Those devices use the explicit sequential path even if they advertise another concurrent combination. Session rejection is a configuration-specific result, not a claim that the hardware can never capture two cameras together.

Physical PRIVATE preview streams are optional in Camera2. If a selected physical preview cannot be configured, the app may show the logical camera's preview with an explicit **LOGICAL PREVIEW / SEE STATUS** label explaining that the preview lens may differ. Still capture always uses the selected physical YUV output; a preview fallback never changes the source of a captured input.

## Baseline and component boundaries

| Component | Responsibility |
|---|---|
| `camera/CameraDiscovery.kt` | Camera2 inventory, two distinct lens endpoints, bounded stream sizes, calibration inventory |
| `camera/CameraEngine.kt` | Physical routing, session probe, preview, 3A warmup, pair/sequential capture, frame timestamps, stride-aware YUV conversion |
| `processing/ImagePreprocessor.kt` | Bounded decode/resize, lens correction when safe, rotation, invalid-border mask |
| `alignment/FeatureAligner.kt` | ORB/Hamming ratio matches, RANSAC homography, spatial inlier support, local confidence |
| `alignment/LocalRefiner.kt` | Small optical-flow correction with forward/backward consistency and displacement limits |
| `fusion/MultiscaleFusion.kt` | Four-level Laplacian detail transfer, ultrawide low-frequency base, exact outside-mask preservation |
| `model/ReferenceEnhancer.kt` | Optional ONNX model IO adapter and compatibility checks |
| `processing/ProcessingPipeline.kt` | Stage orchestration, deterministic baseline, diagnostics, bounded-memory retry |
| `storage/CaptureStore.kt` | Local sessions, metadata, PNG and ZIP export |
| `CameraViewModel.kt`, `MainActivity.kt` | Capture state, lifecycle, permission, progress/errors, Material 3 UI |

The processing canvas is at most **1280 pixels on its longest edge** (960 on an out-of-memory retry), with a reference prepared at up to 1.25 times that edge length. Camera YUV captures prefer a supported 4:3 stream of at most 4.1 MP. The full selected frame is retained; processing reduces resolution without cropping the canvas. If 4:3 is unavailable, the device-selected stream's field of view is used and the size is recorded.

Alignment maps main-camera coordinates into ultrawide coordinates. It requires enough homography inliers and a plausible projected overlap. The mask combines the valid warped region, inlier convex hull, bounded local optical flow with forward/backward consistency, and exposure-normalized local residual rejection. Distance from rejected areas feathers the confidence. Four-level Laplacian transfer adds main-camera detail in that mask and retains the ultrawide low-frequency base. It is not a full-image alpha overlay.

Distortion correction prefers the camera HAL's advertised FAST mode. The **actual result mode** is recorded. No second correction is applied to a HAL-corrected frame. When correction is OFF, OpenCV undistortion is enabled only with five intrinsic parameters, five distortion coefficients, a reported crop, and a compatible stream aspect ratio. Android's coefficient order is converted to OpenCV's order. Undefined remap boundaries retain original pixels and are excluded from fusion. If these conditions are absent, processing leaves distortion as captured and reports that limitation. A calibrated correction may change interior pixel geometry; there is no additional crop or output expansion.

## Optional ONNX reference model

No model weights are provided, fetched, or assumed. Until a compatible file is explicitly supplied, the app says **AI unavailable** and shows the deterministic OpenCV result. Generic sharpening is never presented as AI super-resolution.

Use **Camera & model details → Import trusted ONNX model** to copy a file chosen on the device to:

```text
<app files directory>/models/reference_sr.onnx
```

For a debug build, a developer can also put the model at that path using `adb shell run-as com.example.referencecamera`. The runtime file path belongs to the model adapter; camera, alignment, and fusion code need no changes. The importer accepts a single self-contained ONNX file up to 512 MiB. External tensor files are not supported by the import flow.

The adapter contract is deliberately explicit:

| Name | Type / shape | Meaning |
|---|---|---|
| `target` | float32 `[1,3,H,W]` | OpenCV baseline, RGB, range `[0,1]` |
| `reference` | float32 `[1,3,H,W]` | Main frame aligned to the ultrawide canvas, RGB, range `[0,1]` |
| `confidence` | float32 `[1,1,H,W]` | Trusted reference weight in `[0,1]`; zero outside reliable overlap |
| `enhanced` | float32 `[1,3,H,W]` | Single output, same canvas, RGB values in `[0,1]` |

`H,W` must match the prepared canvas, including its orientation. Dynamic spatial dimensions are recommended. Batch must be 1; the adapter rejects incompatible names, types, shapes, or invalid output values and retains the baseline. The model should be trained for registered references and this normalization. A model that internally performs super-resolution must return its output at the requested target-canvas size. Tiling, arbitrary vendor-model adaptation, model training, and external-weight resolution are not implemented. A model needing other inputs or scales can be supported by a different `ReferenceEnhancer` implementation.

ONNX Runtime runs on-device with two CPU threads. Its output is gated by the same confidence mask before it is saved. Merely loading or running an ONNX file is not proof of better quality. Memory use and speed depend on the supplied model; model failure or memory exhaustion leaves the baseline available.

## Files and diagnostics

Each capture session is stored under `files/captures/<time>-<id>/`:

- `ultrawide.jpg`, `main.jpg`: original camera YUV inputs encoded locally as JPEG quality 97, with EXIF orientation. These are not Google Camera HDR+ images or sensor RAW files.
- `capture.json`: logical/physical routes, selected stream sizes, calibration, actual crop/correction/focal-length metadata, capture mode, sensor timestamps, and wall-clock times.
- `comparison-original.png`: oriented, resized ultrawide before optional geometric correction.
- `aligned-reference.jpg`, `confidence.png`: alignment and mask diagnostics.
- `baseline.png`: deterministic OpenCV result.
- `result.png`: baseline or optional model result.
- `processing.json`: alignment acceptance, notes, processing size, and input timestamp difference.

`files/camera-report.json` stores the latest discovery/probe report. Partial inputs remain on disk if capture or processing fails. Export the ZIP to preserve inputs, output, and diagnostics. App-private sessions survive closing the app but are removed by clearing app data or uninstalling. A session browser or automatic process-death restoration is not included; export before leaving an important session. For debug recovery, `adb shell run-as com.example.referencecamera ls files/captures` can list saved sessions.

## Limitations and evaluation

- Physical camera IDs and stream support depend on the Pixel's OS and Camera2 implementation. This app cannot establish the user's Pixel 7 topology until run there. There is no lens fallback to a crop or digital zoom. If two suitable lenses are hidden, discovery fails visibly.
- A successful pair session does not guarantee zero exposure skew. Sensor timestamp differences are recorded; compare only timestamps from compatible clock domains. The selected logical sensor synchronization type is also reported. Independent cameras, rolling shutter, autofocus, and different exposure/noise processing can cause mismatches.
- Warmup allows automatic exposure/focus to settle with a timeout. Continuous autofocus is enabled when all participating lenses advertise it; otherwise the shared request uses AF OFF and the lens's default focus position. This is especially relevant to a fixed-focus ultrawide paired with the main camera: start with distant scenes, or use sequential capture for independent main-camera autofocus. This POC does not lock exposure, synchronize manual sensor controls, or provide Google Camera multi-frame processing.
- Different viewpoints cause parallax. A global homography plus a small local flow cannot reconstruct disoccluded surfaces or arbitrary nearby objects. Confidence rejection can miss a bad match or reject useful detail. Use a tripod, distant static textured scene, and good lighting initially.
- Field of view is preserved relative to the selected ultrawide Camera2 stream, which may differ from the Google Camera app's proprietary correction/crop. Processing resolution is intentionally below the capture resolution.
- There is no quality-improvement claim. Compare the baseline against the same ultrawide resized with the same filter, inspect alignment/ghosting and the seam, and record latency and timestamp skew. For quantitative claims, collect registered ground truth and report center-region PSNR/SSIM or a validated perceptual metric, plus failure cases across depth, motion, lighting, and texture. A natural-scene visual comparison alone is not a measurement of recovered detail.

## Build and checks

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

JVM checks cover padded/interleaved YUV planes, buffer positions, chroma order, and sensor crop offsets. Instrumentation checks use synthetic images to check canvas preservation, exact preservation outside zero-confidence regions, detail-band transfer inside a mask, known-crop alignment, rejection of featureless references, and a complete oriented capture-file-to-baseline pipeline with no model. They require an emulator or Android device and OpenCV's Android native library. They do not validate Pixel physical-camera support or prove photographic quality.

Primary API references: [Camera2 multi-camera](https://developer.android.com/media/camera/camera2/multi-camera), [physical OutputConfiguration routing](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#setPhysicalCameraId(java.lang.String)), [ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html), and [OpenCV](https://docs.opencv.org/4.x/).
