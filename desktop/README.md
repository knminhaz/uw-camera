# Camera Lab desktop admin

A local website and USB companion for UW camera. No cloud account or hosting is needed. The dashboard listens on **127.0.0.1:8765 only**.

## Start on this laptop

Double-click `Start-Camera-Lab.cmd` in the project root. Keep its console open while capturing. It starts the normal Windows ADB server, starts the local service, and opens the dashboard. Stop with Ctrl+C.

Open **UW camera** on the USB-connected, unlocked phone. USB debugging must already be authorized. The dashboard and phone both say when the companion is ready.

1. Capture and process on the phone. All new stage files go to temporary app cache.
2. Review the comparison slider on the phone. Only progress metadata reaches the laptop at this point.
3. Press **OK — Save all stages** while the laptop is ready.
4. The companion copies every stage and metadata file, checks declared sizes and SHA-256 hashes, atomically commits the archive, and acknowledges it.
5. The phone clears that temporary session after the acknowledgement. The dashboard can inspect all archived stages.

When the companion is unavailable, OK discards the temporary session. It is not queued. A failed or interrupted transfer keeps the current preview for retry or discard. Temporary cache is not a durable photo library: app restart or Android cache eviction may remove previews. Existing captures from earlier versions are retained; only newly created temporary sessions are automatically cleared.

## Dashboard

- Overview: live capture / preparation / alignment / fusion / optional AI / finalization status, transfer progress, and verified archives.
- Inspector: stage selection, original or OpenCV baseline comparison, slider, difference map, fit / 100% / 200% zoom, per-stage downloads and complete session ZIPs.
- Capture sessions: searchable archive history, explicit synthetic QA labels.
- Quality matrix: PSNR, Gaussian RGB SSIM, and processing times across archived sessions; CSV export.
- Settings: connected phone, accept/pause archival, local archive path and free space.
- Session notes are saved to the local index.

Photo previews on the laptop appear only after approval. During phone processing, progress messages and timings update live; unapproved image files are not transferred.

## Quality measurements

PSNR and SSIM require a clean image of the same scene **already aligned** to the final result, with exactly matching dimensions. Use **Add ground truth** and confirm registration. Images are never automatically resized or registered for scoring. Metrics are RGB PSNR (255 range) and Gaussian-window SSIM (11x11, sigma 1.5, population covariance). Perfect-match PSNR displays infinity; SSIM is unavailable below 11 pixels on either axis.

The main reference camera is not automatically clean ground truth. Coverage, pixel change, mean absolute delta, and the difference map are processing diagnostics, not evidence of improvement. Synthetic QA sessions are transfer tests, not camera-quality benchmarks.

## Storage and protocol

Default archive: `desktop-data/sessions/<capture-id>-<manifest-hash>/` beside the project. Original stage files and metadata plus `transfer-manifest.json` are preserved. `analysis/` stores optional reference scores and thumbnails. `index.sqlite3` tracks sessions and notes. Back up the entire `desktop-data` folder.

The companion uses the existing authorized ADB USB connection with the app's debug-only `run-as` access. The app does not open a network listener or require Android INTERNET permission. The website rejects nonlocal Host and Origin values. This is a development companion, not a production OEM or consumer deployment protocol.

Only a request tied to the current phone process and companion can start a transfer. Stale heartbeats expire. Only flat manifest filenames are accepted; tar paths, links, missing files and checksum mismatches are rejected. If acknowledgement is lost after commit, retry verifies and reuses the existing archive. Low disk space or failed verification never produces a success acknowledgement.

Live timing is sampled from phone progress callbacks and is diagnostic, not a GPU profiler. Processing still happens on the phone. Laptop connectivity does not offload ONNX inference.

## Another machine

Install Python 3.11+ and Android platform-tools from their official distributions. Run `python -m pip install -r desktop/requirements.txt`. Create the ignored `desktop/launch.local.json` with absolute `python` and `adb` paths; optional `archive`, `port` (default 8765), and `package` (default `com.example.referencecamera.preview`). Then use the root launcher. Alternatively:

```powershell
python desktop/server.py --adb C:/Android/platform-tools/adb.exe
```

The current launcher configuration uses this laptop's bundled Python and the SDK installed for this project. It is intentionally not committed.

## Validate

```powershell
python -m unittest discover -s desktop/tests -v
```

Android instrumentation adds isolated bridge readiness, manifest, acknowledgement and safe-cleanup checks. The real USB test is opt-in, requires the companion running, and creates a labeled synthetic chart without opening the camera:

```powershell
adb shell am instrument -w -e class com.example.referencecamera.LaptopBridgeInstrumentedTest#explicitSyntheticUsbArchiveRoundTrip -e usbArchive true com.example.referencecamera.preview.test/androidx.test.runner.AndroidJUnitRunner
```
