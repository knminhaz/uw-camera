package com.example.referencecamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class LensCalibration(
    val intrinsics: FloatArray?, val distortion: FloatArray?,
    val preCorrectionArray: Rect?, val activeArray: Rect?, val correctionModes: IntArray
)

data class LensChoice(
    val logicalId: String, val physicalId: String?, val label: String,
    val focalLengthMm: Float, val normalizedFocal: Float,
    val captureSize: Size, val previewSize: Size, val sensorOrientation: Int,
    val calibration: LensCalibration, val autofocusModes: IntArray, val streams: String
) {
    val endpoint: String get() = physicalId ?: logicalId
    val route: String get() = "logical $logicalId / physical ${physicalId ?: "direct"}"
}

data class CameraReport(
    val wide: LensChoice, val main: LensChoice,
    val inventory: String, val concurrentLogicalSets: List<Set<String>>,
    val syncType: String
) {
    fun json() = JSONObject().apply {
        put("inventory", inventory)
        put("concurrentLogicalSets", JSONArray(concurrentLogicalSets.map { it.joinToString() }))
        put("wideRoute", wide.route); put("mainRoute", main.route); put("syncType", syncType)
    }
}

data class PairSupport(val supported: Boolean, val message: String)

class CameraDiscovery(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun inspect(): CameraReport {
        val candidates = mutableListOf<LensChoice>()
        val lines = mutableListOf<String>()
        val sync = mutableMapOf<String, String>()
        manager.cameraIdList.forEach { id ->
            val c = manager.getCameraCharacteristics(id)
            val ids = c.physicalCameraIds
            lines += "ID $id: facing=${c.get(CameraCharacteristics.LENS_FACING)}, physical=${ids.joinToString().ifEmpty { "none" }}, timestampSource=${c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)}, capabilities=${c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString()}"
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@forEach
            sync[id] = when (c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)) {
                CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "calibrated"
                CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "approximate"
                else -> "not reported"
            }
            if (ids.size >= 2) ids.forEach { physicalId ->
                runCatching { makeChoice(id, physicalId, manager.getCameraCharacteristics(physicalId)) }
                    .onSuccess { if (it != null) candidates += it }
                    .onFailure { lines += "Physical $physicalId cannot be inspected: ${it.message}" }
            } else makeChoice(id, null, c)?.let(candidates::add)
        }
        lines += candidates.joinToString("\n") { "${it.route}: ${it.focalLengthMm} mm, normalized focal=${it.normalizedFocal}, AF modes=${it.autofocusModes.joinToString()}, YUV ${it.captureSize}; ${it.streams}" }
        // Prefer two routed endpoints in one logical group. Never infer a lens from zoom ratios.
        val groups = candidates.groupBy { it.logicalId }.values.filter { it.map { lens -> lens.endpoint }.distinct().size >= 2 }
        val pool = groups.maxByOrNull { group -> group.maxOf { it.normalizedFocal } / group.minOf { it.normalizedFocal } }
            ?: candidates.distinctBy { it.endpoint }
        val ordered = pool.sortedBy { it.normalizedFocal }
        val wide = ordered.firstOrNull() ?: error("No rear Camera2 YUV camera is available.\nCamera2 inventory:\n${lines.joinToString("\n")}")
        val main = ordered.firstOrNull { it.endpoint != wide.endpoint && it.normalizedFocal > wide.normalizedFocal * 1.18f }
            ?: error("Camera2 did not expose two distinct rear lenses with different fields of view. Digital zoom is disabled.\nCamera2 inventory:\n${lines.joinToString("\n")}")
        val concurrent = if (Build.VERSION.SDK_INT >= 30) manager.concurrentCameraIds.toList() else emptyList()
        return CameraReport(wide.copy(label = "Ultrawide"), main.copy(label = "Main 1×"), lines.joinToString("\n"), concurrent, sync[wide.logicalId] ?: "not reported")
    }

    private fun makeChoice(logicalId: String, physicalId: String?, c: CameraCharacteristics): LensChoice? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        if (yuv.isEmpty()) return null
        // Prefer a bounded 4:3 stream to retain the full sensor field and limit native memory.
        val fourThree = yuv.filter { abs(it.width.toDouble() / it.height - 4.0 / 3.0) < .04 }
        val sizes = fourThree.ifEmpty { yuv.toList() }
        val capture = sizes.filter { it.width.toLong() * it.height <= 4_100_000L }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minBy { it.width.toLong() * it.height }
        val preview = (map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: sizes)
            .filter { it.width <= 1920 && it.height <= 1440 }
            .minByOrNull { abs(it.width.toDouble() / it.height - capture.width.toDouble() / capture.height) * 1e6 - it.width * it.height }
            ?: Size(1280, 720)
        return LensChoice(logicalId, physicalId, "Camera ${physicalId ?: logicalId}", focal,
            focal / sensor.width, capture, preview,
            c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            LensCalibration(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION), c.get(CameraCharacteristics.LENS_DISTORTION),
                c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE), c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: intArrayOf(0)),
            c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf(0),
            "YUV=${yuv.joinToString()}, JPEG=${map.getOutputSizes(ImageFormat.JPEG)?.joinToString() ?: "none"}")
    }
}
