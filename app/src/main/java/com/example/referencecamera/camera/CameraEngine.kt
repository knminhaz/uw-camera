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
    private val preferences = context.getSharedPreferences("camera-strategies", Context.MODE_PRIVATE)
    private val thread = HandlerThread("camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val cameraDispatcher = executor.asCoroutineDispatcher()
    private val lock = Mutex()
    private var device: CameraDevice? = null
    private var deviceClosed: CompletableDeferred<Unit>? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var disposed = false
    private var preferredPair: List<LensChoice>? = null
    private fun failureKey(report: CameraReport) = "logical-preview-v1:${Build.FINGERPRINT}:${report.wide.route}:${report.main.route}"
    fun forgetPairFailure(report: CameraReport) { preferences.edit().remove(failureKey(report)).apply() }

    private fun pairCandidates(report: CameraReport): List<List<LensChoice>> {
        val lenses = listOf(report.wide, report.main)
        if (lenses.any { it.physicalId == null } || lenses.map { it.logicalId }.distinct().size != 1) return emptyList()
        val common = (lenses.map { lens ->
            manager.getCameraCharacteristics(lens.endpoint).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.toSet().orEmpty()
        } + listOf(manager.getCameraCharacteristics(report.wide.logicalId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toSet().orEmpty())).reduce { a, b -> a intersect b }
        val sizes = listOf(2048, 1280, 640).mapNotNull { edge -> common
            .filter { maxOf(it.width, it.height) <= edge && kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3.0) < .04 }
            .maxByOrNull { it.width.toLong() * it.height } }.distinct()
        return sizes.map { size -> lenses.map { it.copy(captureSize = size) } }
    }

    /** Tries actual frame delivery; configuration acceptance alone is insufficient. */
    suspend fun capturePair(report: CameraReport, directory: File, displayDegrees: Int, progress: (String) -> Unit): List<CapturedFrame> {
        val candidates = (listOfNotNull(preferredPair) + pairCandidates(report)).distinctBy { it.first().captureSize }
        val attempts = JSONArray()
        for (lenses in candidates) {
            try {
                progress("Trying dual streams at ${lenses.first().captureSize}…")
                val frames = capture(lenses, directory, displayDegrees, progress)
                val gap = kotlin.math.abs(frames[0].timestampNs - frames[1].timestampNs) / 1_000_000.0
                require(gap <= 50.0) { "Dual exposure gap $gap ms exceeds the POC's 50 ms limit" }
                preferredPair = lenses
                preferences.edit().remove(failureKey(report)).apply()
                attempts.put(JSONObject().put("size", lenses.first().captureSize.toString()).put("delivered", true).put("exposureGapMs", gap))
                withContext(Dispatchers.IO) { File(directory, "pair-attempts.json").writeText(attempts.toString(2)) }
                return frames
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val diagnostic = withContext(Dispatchers.IO) { File(directory, "capture-error.txt").takeIf { it.exists() }?.readText()?.substringBefore("java.lang.") }
                attempts.put(JSONObject().put("size", lenses.first().captureSize.toString()).put("delivered", false).put("error", e.message).put("diagnostic", diagnostic))
                withContext(Dispatchers.IO) { File(directory, "pair-attempts.json").writeText(attempts.toString(2)) }
            }
        }
        preferences.edit().putBoolean(failureKey(report), true).apply()
        error("Dual-stream delivery failed at all ${candidates.size} supported candidate sizes. Failure remembered; retry explicitly in Settings. See pair-attempts.json; using sequential capture.")
    }

    suspend fun probePair(report: CameraReport): PairSupport = lock.withLock {
        if (preferences.getBoolean(failureKey(report), false)) return@withLock PairSupport(false, "Dual-stream delivery previously failed on this camera/OS configuration. Using sequential capture without repeating the slow attempts. Settings can retry dual detection.")
        val wide = report.wide; val main = report.main
        if (wide.logicalId != main.logicalId || wide.physicalId == null || main.physicalId == null) {
            return@withLock PairSupport(false, "No shared logical camera exposes both selected physical lenses. Concurrent logical sets: ${report.concurrentLogicalSets}. This POC uses sequential capture for separate devices.")
        }
        val readers = mutableListOf<ImageReader>()
        try {
            val candidates = pairCandidates(report)
            require(candidates.isNotEmpty()) { "No common bounded 4:3 YUV size is advertised by both physical lenses and the logical camera" }
            val selected = candidates.last()
            selected.forEach { readers += reader(it) }
            disconnect()
            val camera = open(wide.logicalId)
            val outputs = readers.zip(selected).map { (r, lens) -> output(r.surface, lens.physicalId, true) }
            val query = if (Build.VERSION.SDK_INT >= 29) runCatching {
                camera.isSessionConfigurationSupported(configuration(outputs, SessionSignals()))
            }.getOrNull() else null
            // A query may be unimplemented. An actual configure attempt is the final check.
            configure(camera, outputs)
            PairSupport(true, "Dual physical YUV configuration accepted at ${selected.first().captureSize}; query=$query. Delivery is unverified until capture. Candidate sizes: ${candidates.map { it.first().captureSize }}. Sensor synchronization: ${report.syncType}.")
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

    suspend fun capture(lenses: List<LensChoice>, directory: File, displayDegrees: Int, progress: (String) -> Unit = {}): List<CapturedFrame> = lock.withLock {
        require(lenses.isNotEmpty() && lenses.map { it.logicalId }.distinct().size == 1)
        val readers = mutableListOf<ImageReader>()
        var logicalReader: ImageReader? = null
        var phase = "Opening real camera lenses"
        var frameMatchers: List<FrameMatcher<ByteArray>> = emptyList()
        fun stage(message: String) { phase = message; progress(message) }
        try {
            stage("Opening real camera lenses…")
            lenses.forEach { readers += reader(it) }
            disconnect()
            val camera = open(lenses.first().logicalId)
            // Keep a logical PRIVATE stream alongside the two physical YUV outputs.
            // Its frames are discarded and can never become either saved camera input.
            if (lenses.size == 2) {
                val map = manager.getCameraCharacteristics(lenses.first().logicalId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val size = map?.getOutputSizes(ImageFormat.PRIVATE)?.filter { maxOf(it.width, it.height) <= 640 }
                    ?.maxByOrNull { it.width * it.height } ?: error("No bounded logical PRIVATE output advertised")
                logicalReader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 2).apply {
                    setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                }
            }
            val outputs = readers.zip(lenses).map { (r, lens) -> output(r.surface, lens.physicalId, true) } +
                listOfNotNull(logicalReader?.let { output(it.surface, null) })
            val signals = configure(camera, outputs)
            stage("Settling exposure and focus…")
            val images = readers.map { CompletableDeferred<TimestampedFrame<ByteArray>>() }
            val matchers = readers.map { FrameMatcher<ByteArray>() }
            frameMatchers = matchers
            var captureArmed = false // Accessed only on cameraDispatcher / callback handler.
            readers.forEachIndexed { index, r -> r.setOnImageAvailableListener({ source ->
                try {
                    // Bound work so repeating image delivery cannot starve result callbacks.
                    var drained = 0
                    while (drained++ < 2) {
                        val image = source.acquireNextImage() ?: break
                        try {
                            if (captureArmed && !images[index].isCompleted) {
                                matchers[index].image(image.timestamp, yuvJpeg(image))?.let { images[index].complete(it) }
                            }
                        } finally { image.close() }
                    }
                } catch (e: Exception) { images[index].completeExceptionally(e) }
                catch (e: OutOfMemoryError) { images[index].completeExceptionally(e) }
            }, handler) }
            val settled = CompletableDeferred<Unit>()
            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                readers.forEach { addTarget(it.surface) }; controls(lenses)
                logicalReader?.let { addTarget(it.surface) }
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
            val streamingPair = lenses.size == 2
            if (!streamingPair) {
                stage("Finishing warmup frames…")
                signals.ready = CompletableDeferred()
                signals.session.stopRepeating()
                cameraDeadline("Stopping camera warmup", 5000) { signals.ready.await() }
            }

            val result = CompletableDeferred<TotalCaptureResult>()
            var selectedFrameNumber: Long? = null
            val lostFrames = ArrayDeque<Long>()
            stage("Capturing ${lenses.size} real lens image${if (lenses.size == 1) "" else "s"}…")
            val request = camera.createCaptureRequest(if (streamingPair) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                readers.forEach { addTarget(it.surface) }; controls(lenses)
                logicalReader?.let { addTarget(it.surface) }
            }.build()
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, value: TotalCaptureResult) {
                    if (result.isCompleted) return
                    if (value.frameNumber in lostFrames) return
                    if (streamingPair) {
                        val physical = if (Build.VERSION.SDK_INT >= 31) value.physicalCameraTotalResults.keys else value.physicalCameraResults.keys
                        if (lenses.any { it.physicalId !in physical }) {
                            result.completeExceptionally(IllegalStateException("Dual request omitted physical camera metadata")); return
                        }
                        if (lenses.any { physicalResult(value, it).get(CaptureResult.SENSOR_TIMESTAMP) == null }) {
                            result.completeExceptionally(IllegalStateException("Dual request omitted physical sensor timestamps")); return
                        }
                    }
                    lenses.forEachIndexed { index, lens ->
                        val timestamp = physicalResult(value, lens).get(CaptureResult.SENSOR_TIMESTAMP)
                        matchers[index].result(timestamp)?.let { images[index].complete(it) }
                    }
                    selectedFrameNumber = value.frameNumber
                    result.complete(value)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    result.completeExceptionally(IllegalStateException("Still capture failed (reason ${failure.reason})"))
                }
                override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                    result.completeExceptionally(IllegalStateException("Camera2 aborted the still capture sequence"))
                }
                override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                    // Repeating capture may lose a later frame while our selected frame arrives.
                    if (streamingPair && selectedFrameNumber != frameNumber) {
                        lostFrames.addLast(frameNumber)
                        while (lostFrames.size > 16) lostFrames.removeFirst()
                        return
                    }
                    val index = readers.indexOfFirst { it.surface == target }
                    if (index >= 0) images[index].completeExceptionally(IllegalStateException("Camera2 lost the ${lenses[index].label} image buffer"))
                }
            }
            withContext(cameraDispatcher) {
                // Do not swap listeners. Drain and arm on the same thread as image delivery.
                readers.forEach { reader -> repeat(4) { reader.acquireNextImage()?.close() } }
                captureArmed = true
                if (streamingPair) signals.session.setRepeatingRequest(request, callback, handler)
                else signals.session.capture(request, callback, handler)
            }
            cameraDeadline("Receiving camera images and capture metadata", if (streamingPair) 5000 else 15_000) {
                val metadata = result.await()
                stage("Saving camera inputs locally…")
                images.mapIndexed { index, frame ->
                    val received = frame.await()
                    val bytes = received.value; val timestamp = received.timestamp
                    val selectedLens = lenses[index]
                    val physical = physicalResult(metadata, selectedLens)
                    val lens = selectedLens.copy(calibration = selectedLens.calibration.copy(
                        intrinsics = physical.get(CaptureResult.LENS_INTRINSIC_CALIBRATION) ?: selectedLens.calibration.intrinsics,
                        distortion = physical.get(CaptureResult.LENS_DISTORTION) ?: selectedLens.calibration.distortion))
                    val expectedTimestamp = physical.get(CaptureResult.SENSOR_TIMESTAMP)
                    val file = File(directory, if (lens.label == "Ultrawide") "ultrawide.jpg" else "main.jpg")
                    val rotation = (lens.sensorOrientation - displayDegrees + 360) % 360
                    withContext(Dispatchers.IO) {
                        file.writeBytes(bytes)
                        ExifInterface(file).apply {
                            setAttribute(ExifInterface.TAG_ORIENTATION, when (rotation) { 90 -> ExifInterface.ORIENTATION_ROTATE_90; 180 -> ExifInterface.ORIENTATION_ROTATE_180; 270 -> ExifInterface.ORIENTATION_ROTATE_270; else -> ExifInterface.ORIENTATION_NORMAL }.toString())
                            saveAttributes()
                        }
                    }
                    require(expectedTimestamp == null || expectedTimestamp == timestamp) { "${lens.label} timestamp mismatch (image=$timestamp, result=$expectedTimestamp). Input saved for diagnosis; reference rejected." }
                    CapturedFrame(file, lens, timestamp, rotation,
                        physical.get(CaptureResult.SCALER_CROP_REGION), physical.get(CaptureResult.DISTORTION_CORRECTION_MODE),
                        physical.get(CaptureResult.LENS_FOCAL_LENGTH), System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            val frameState = withContext(NonCancellable + cameraDispatcher) {
                lenses.zip(frameMatchers).joinToString("\n") { (lens, frames) -> "${lens.label}: ${frames.diagnostic()}" }
            }
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { File(directory, "capture-error.txt").writeText("Stage: $phase\n$frameState\n${e.stackTraceToString()}") }
            }
            throw e
        } finally {
            withContext(NonCancellable) { disconnect(); releaseReaders(readers + listOfNotNull(logicalReader)) }
        }
    }

    suspend fun stop() = lock.withLock { disconnect() }

    private fun reader(lens: LensChoice) = ImageReader.newInstance(lens.captureSize.width, lens.captureSize.height, ImageFormat.YUV_420_888, 4)
    private suspend fun releaseReaders(readers: List<ImageReader>) = withContext(cameraDispatcher) {
        // Run after any in-flight YUV conversion before freeing its native image buffers.
        readers.forEach { it.setOnImageAvailableListener(null, null); it.close() }
    }
    private fun output(surface: Surface, physicalId: String?, sensorTimestamp: Boolean = false) = OutputConfiguration(surface).apply {
        if (physicalId != null) setPhysicalCameraId(physicalId)
        if (sensorTimestamp && Build.VERSION.SDK_INT >= 33) setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR)
    }

    @Suppress("DEPRECATION")
    private fun physicalResult(result: TotalCaptureResult, lens: LensChoice): CaptureResult = lens.physicalId?.let { id ->
        if (Build.VERSION.SDK_INT >= 31) result.physicalCameraTotalResults[id] else result.physicalCameraResults[id]
    } ?: result

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
        try { session = cameraDeadline("Configuring Camera2 streams", 7000) { state.configured.await() } }
        catch (e: Exception) { state.configured.cancel(); throw e }
        return state
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(id: String): CameraDevice = cameraDeadline("Opening camera $id", 7000) {
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

    private suspend fun <T> cameraDeadline(label: String, milliseconds: Long, block: suspend () -> T): T = try {
        withTimeout(milliseconds) { block() }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("$label timed out after ${milliseconds / 1000} seconds. Retry with the camera still; sequential capture may be required.", e)
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
