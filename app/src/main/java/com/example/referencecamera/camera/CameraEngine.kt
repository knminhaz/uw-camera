package com.example.referencecamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CapturedFrame(
    val file: File, val lens: LensChoice, val timestampNs: Long,
    val rotationDegrees: Int, val crop: Rect?, val distortionMode: Int?,
    val actualFocalLength: Float?, val wallTimeMs: Long
) {
    fun json() = JSONObject().apply {
        put("file", file.name); put("route", lens.route); put("sensorTimestampNs", timestampNs)
        put("wallTimeMs", wallTimeMs); put("rotationDegrees", rotationDegrees)
        put("size", lens.captureSize.toString()); put("crop", crop?.flattenToString())
        put("distortionMode", distortionMode); put("actualFocalLengthMm", actualFocalLength)
        put("intrinsics", lens.calibration.intrinsics?.let { JSONArray(it.toList()) })
        put("distortion", lens.calibration.distortion?.let { JSONArray(it.toList()) })
        put("activeArray", lens.calibration.activeArray?.flattenToString())
        put("preCorrectionArray", lens.calibration.preCorrectionArray?.flattenToString())
    }
}

data class PreviewStatus(val lensSpecific: Boolean, val message: String)

/** Owns all Camera2 resources. No capture uses CONTROL_ZOOM_RATIO or a synthetic crop. */
class CameraEngine(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val cameraDispatcher = executor.asCoroutineDispatcher()
    private val lock = Mutex()
    private var device: CameraDevice? = null
    private var deviceClosed: CompletableDeferred<Unit>? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var disposed = false

    suspend fun probePair(report: CameraReport): PairSupport = lock.withLock {
        val wide = report.wide; val main = report.main
        if (wide.logicalId != main.logicalId || wide.physicalId == null || main.physicalId == null) {
            return@withLock PairSupport(false, "No shared logical camera exposes both selected physical lenses. Concurrent logical sets: ${report.concurrentLogicalSets}. This POC uses sequential capture for separate devices.")
        }
        val readers = mutableListOf<ImageReader>()
        try {
            listOf(wide, main).forEach { readers += reader(it) }
            disconnect()
            val camera = open(wide.logicalId)
            val outputs = readers.zip(listOf(wide, main)).map { (r, lens) -> output(r.surface, lens.physicalId) }
            val query = if (Build.VERSION.SDK_INT >= 29) runCatching {
                camera.isSessionConfigurationSupported(configuration(outputs, SessionSignals()))
            }.getOrNull() else null
            // A query may be unimplemented. An actual configure attempt is the final check.
            configure(camera, outputs)
            PairSupport(true, "Two physical YUV streams configured successfully (${wide.captureSize} + ${main.captureSize}); query=$query. Sensor synchronization: ${report.syncType}. Same-request capture is available; timestamps are saved for inspection.")
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            PairSupport(false, "Dual physical YUV session unavailable: ${e.message}. Use two sequential shots; motion and parallax can prevent fusion.")
        } catch (_: OutOfMemoryError) {
            PairSupport(false, "Not enough memory to configure a dual physical session. Sequential capture is active.")
        } finally {
            withContext(NonCancellable) { disconnect(); releaseReaders(readers) }
        }
    }

    suspend fun preview(lens: LensChoice, surface: Surface): PreviewStatus = lock.withLock {
        disconnect()
        try {
            var camera = open(lens.logicalId)
            var routed = true
            val signals = try { configure(camera, listOf(output(surface, lens.physicalId))) }
            catch (e: Exception) {
                if (e is CancellationException || lens.physicalId == null) throw e
                // PRIVATE physical preview is optional even when physical YUV capture works.
                disconnect(); camera = open(lens.logicalId); routed = false
                configure(camera, listOf(output(surface, null)))
            }
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface); controls(listOf(lens))
            }.build()
            signals.session.setRepeatingRequest(request, null, handler)
            PreviewStatus(routed, if (routed) "${lens.label} • ${lens.route}" else "Logical preview only: physical preview unsupported. Preview lens may differ; still captures remain routed to the selected real lens.")
        } catch (e: Exception) {
            withContext(NonCancellable) { disconnect() }
            if (e is CancellationException) throw e
            throw IllegalStateException("${lens.label} preview is unsupported or unavailable: ${e.message}. Retry camera access.", e)
        }
    }

    suspend fun capture(lenses: List<LensChoice>, directory: File, displayDegrees: Int): List<CapturedFrame> = lock.withLock {
        require(lenses.isNotEmpty() && lenses.map { it.logicalId }.distinct().size == 1)
        val readers = mutableListOf<ImageReader>()
        try {
            lenses.forEach { readers += reader(it) }
            disconnect()
            val camera = open(lenses.first().logicalId)
            val signals = configure(camera, readers.zip(lenses).map { (r, lens) -> output(r.surface, lens.physicalId) })
            readers.forEach { r -> r.setOnImageAvailableListener({ source ->
                try { source.acquireLatestImage()?.close() } catch (_: IllegalStateException) { /* Closing reader. */ }
            }, handler) }
            val settled = CompletableDeferred<Unit>()
            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                readers.forEach { addTarget(it.surface) }; controls(lenses)
            }.build()
            signals.session.setRepeatingRequest(previewRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val af = result.get(CaptureResult.CONTROL_AF_STATE)
                    if ((ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) &&
                        (af == null || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_INACTIVE)) settled.complete(Unit)
                }
            }, handler)
            // Give each real lens time to settle exposure/focus after switching.
            delay(350)
            withTimeoutOrNull(1800) { settled.await() }
            signals.ready = CompletableDeferred()
            signals.session.stopRepeating()
            withTimeout(5000) { signals.ready.await() }

            val images = readers.map { CompletableDeferred<Pair<ByteArray, Long>>() }
            readers.forEachIndexed { index, r ->
                while (true) { val old = r.acquireLatestImage() ?: break; old.close() }
                r.setOnImageAvailableListener({ source ->
                    var image: Image? = null
                    try {
                        image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                        if (!images[index].isCompleted) images[index].complete(yuvJpeg(image) to image.timestamp)
                    } catch (e: Exception) { images[index].completeExceptionally(e) }
                    catch (e: OutOfMemoryError) { images[index].completeExceptionally(e) }
                    finally { image?.close() }
                }, handler)
            }
            val result = CompletableDeferred<TotalCaptureResult>()
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                readers.forEach { addTarget(it.surface) }; controls(lenses)
            }.build()
            signals.session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, value: TotalCaptureResult) { result.complete(value) }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    result.completeExceptionally(IllegalStateException("Still capture failed (reason ${failure.reason})"))
                }
            }, handler)
            withTimeout(15_000) {
                val metadata = result.await()
                images.mapIndexed { index, frame ->
                    val (bytes, timestamp) = frame.await()
                    val selectedLens = lenses[index]
                    @Suppress("DEPRECATION")
                    val physical: CaptureResult = selectedLens.physicalId?.let { id ->
                        if (Build.VERSION.SDK_INT >= 31) metadata.physicalCameraTotalResults[id] else metadata.physicalCameraResults[id]
                    } ?: metadata
                    val lens = selectedLens.copy(calibration = selectedLens.calibration.copy(
                        intrinsics = physical.get(CaptureResult.LENS_INTRINSIC_CALIBRATION) ?: selectedLens.calibration.intrinsics,
                        distortion = physical.get(CaptureResult.LENS_DISTORTION) ?: selectedLens.calibration.distortion))
                    val expectedTimestamp = physical.get(CaptureResult.SENSOR_TIMESTAMP)
                    require(expectedTimestamp == null || expectedTimestamp == timestamp) { "Capture timestamp mismatch; frame rejected." }
                    val file = File(directory, if (lens.label == "Ultrawide") "ultrawide.jpg" else "main.jpg")
                    val rotation = (lens.sensorOrientation - displayDegrees + 360) % 360
                    withContext(Dispatchers.IO) {
                        file.writeBytes(bytes)
                        ExifInterface(file).apply {
                            setAttribute(ExifInterface.TAG_ORIENTATION, when (rotation) { 90 -> ExifInterface.ORIENTATION_ROTATE_90; 180 -> ExifInterface.ORIENTATION_ROTATE_180; 270 -> ExifInterface.ORIENTATION_ROTATE_270; else -> ExifInterface.ORIENTATION_NORMAL }.toString())
                            saveAttributes()
                        }
                    }
                    CapturedFrame(file, lens, timestamp, rotation,
                        physical.get(CaptureResult.SCALER_CROP_REGION), physical.get(CaptureResult.DISTORTION_CORRECTION_MODE),
                        physical.get(CaptureResult.LENS_FOCAL_LENGTH), System.currentTimeMillis())
                }
            }
        } finally {
            withContext(NonCancellable) { disconnect(); releaseReaders(readers) }
        }
    }

    suspend fun stop() = lock.withLock { disconnect() }

    private fun reader(lens: LensChoice) = ImageReader.newInstance(lens.captureSize.width, lens.captureSize.height, ImageFormat.YUV_420_888, 4)
    private suspend fun releaseReaders(readers: List<ImageReader>) = withContext(cameraDispatcher) {
        // Run after any in-flight YUV conversion before freeing its native image buffers.
        readers.forEach { it.setOnImageAvailableListener(null, null); it.close() }
    }
    private fun output(surface: Surface, physicalId: String?) = OutputConfiguration(surface).apply { if (physicalId != null) setPhysicalCameraId(physicalId) }

    private class SessionSignals {
        lateinit var session: CameraCaptureSession
        @Volatile var ready = CompletableDeferred<Unit>()
        val configured = CompletableDeferred<CameraCaptureSession>()
    }

    private fun configuration(outputs: List<OutputConfiguration>, state: SessionSignals) = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR, outputs, executor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { if (state.configured.isActive) { state.session = s; state.configured.complete(s) } else s.close() }
            override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); state.configured.completeExceptionally(IllegalStateException("Camera2 rejected this stream combination")) }
            override fun onReady(s: CameraCaptureSession) { state.ready.complete(Unit) }
        }
    )

    private suspend fun configure(camera: CameraDevice, outputs: List<OutputConfiguration>): SessionSignals {
        val state = SessionSignals()
        camera.createCaptureSession(configuration(outputs, state))
        try { session = withTimeout(7000) { state.configured.await() } }
        catch (e: Exception) { state.configured.cancel(); throw e }
        return state
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(id: String): CameraDevice = withTimeout(7000) {
        suspendCancellableCoroutine { continuation ->
            val closed = CompletableDeferred<Unit>()
            deviceClosed = closed
            try { manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive && !disposed) { device = camera; continuation.resume(camera) } else camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera disconnected")) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera error $error")) }
                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            }, handler) } catch (e: Exception) { closed.complete(Unit); if (continuation.isActive) continuation.resumeWithException(e) }
            continuation.invokeOnCancellation { device?.close() }
        }
    }

    private suspend fun disconnect() {
        session?.close(); session = null
        val current = device; device = null
        val closed = deviceClosed; deviceClosed = null
        current?.close()
        // A canceled open may not have delivered onOpened yet. Wait for its close callback
        // before attempting another open, otherwise quick lifecycle changes can race.
        if (closed != null && !closed.isCompleted) withTimeoutOrNull(7000) { closed.await() }
    }

    private fun CaptureRequest.Builder.controls(lenses: List<LensChoice>) {
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val focusModes = lenses.map { it.autofocusModes.toSet() }.reduce { a, b -> a intersect b }
        val autofocus = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in focusModes) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
        set(CaptureRequest.CONTROL_AF_MODE, autofocus)
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val modes = lenses.map { it.calibration.correctionModes.toSet() }.reduce { a, b -> a intersect b }
        if (CaptureRequest.DISTORTION_CORRECTION_MODE_FAST in modes) set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
        else if (CaptureRequest.DISTORTION_CORRECTION_MODE_OFF in modes) set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
    }

    override fun close() {
        if (disposed) return
        disposed = true
        // The owning ViewModel cancels work before clearing. Let its non-cancellable cleanup
        // finish under the mutex before shutting down the callback thread.
        CoroutineScope(Dispatchers.Default).launch {
            try { lock.withLock { disconnect() } } finally { thread.quitSafely() }
        }
    }

    companion object {
        /** Handles padded row strides and interleaved or planar chroma, without assuming NV21 input. */
        private fun yuvJpeg(image: Image): ByteArray {
            val crop = image.cropRect
            val w = crop.width(); val h = crop.height()
            val nv21 = YuvPlanes.toNv21(w, h, crop.left, crop.top, image.planes.map { PixelPlane(it.buffer.duplicate(), it.rowStride, it.pixelStride) })
            return java.io.ByteArrayOutputStream().use { output ->
                check(YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 97, output))
                output.toByteArray()
            }
        }
    }
}
