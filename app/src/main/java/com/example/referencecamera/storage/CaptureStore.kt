package com.example.referencecamera.storage

import android.content.Context
import android.net.Uri
import com.example.referencecamera.camera.CameraReport
import com.example.referencecamera.camera.CapturedFrame
import com.example.referencecamera.camera.PairSupport
import com.example.referencecamera.processing.ProcessingResult
import com.example.referencecamera.fusion.FusionMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SavedCapture(val id: String, val createdAt: Long)

class CaptureStore(private val context: Context) {
    private val capturesRoot get() = File(context.filesDir, "captures")

    fun savedResults(): List<SavedCapture> = capturesRoot.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "result.png").isFile && File(it, "comparison-original.png").isFile && File(it, "processing.json").isFile }
        .map { SavedCapture(it.name, it.name.substringBefore('-').toLongOrNull() ?: it.lastModified()) }
        .sortedByDescending { it.createdAt }

    fun openResult(id: String): Pair<File, ProcessingResult> {
        val directory = File(capturesRoot, id).canonicalFile
        require(directory.parentFile == capturesRoot.canonicalFile) { "Invalid saved capture" }
        val before = File(directory, "comparison-original.png"); val output = File(directory, "result.png")
        require(before.isFile && output.isFile) { "Saved result is incomplete or unavailable" }
        val metadata = runCatching { JSONObject(File(directory, "processing.json").readText()) }.getOrNull()
        val notes = metadata?.optString("notes")?.takeIf { it.isNotBlank() } ?: "Saved result loaded. Processing notes are unavailable."
        val metrics = metadata?.optJSONObject("fusionMetrics")?.let { FusionMetrics(it.getDouble("referenceAreaPercent"), it.getDouble("changedPixelPercent"), it.getDouble("meanOverlapDelta"), it.getInt("outsideMaskChangedPixels")) }
        return directory to ProcessingResult(before, output, File(directory, "baseline.png"), notes,
            metadata?.optBoolean("alignmentAccepted", false) ?: false, metadata?.optBoolean("aiApplied", false) ?: false, metrics)
    }

    fun create(): File = File(context.filesDir, "captures/${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}")
        .apply { check(mkdirs()) { "Cannot create local capture directory. Free some device storage." } }

    fun metadata(directory: File, report: CameraReport, support: PairSupport?, frames: List<CapturedFrame>, simultaneous: Boolean) {
        File(directory, "capture.json").writeText(JSONObject().apply {
            put("cameraReport", report.json()); put("pairProbe", support?.message)
            put("captureMode", if (simultaneous) "same-request physical pair" else "sequential")
            put("frames", JSONArray(frames.map { it.json() }))
            put("inputEncoding", "Camera2 YUV_420_888 converted locally to JPEG quality 97; sensor axes with EXIF orientation")
            put("qualityClaim", "No image-quality improvement has been measured")
        }.toString(2))
    }

    fun export(file: File, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open export destination")
        output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    fun exportSession(directory: File, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open export destination")
        ZipOutputStream(output).use { zip ->
            directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}
