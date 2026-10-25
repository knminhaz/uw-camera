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
    private val pendingRoot get() = File(context.cacheDir, "pending-captures")
    private val roots get() = listOf(pendingRoot, capturesRoot)
    private val sessionPattern = Regex("[0-9]+-[a-z0-9]{6,32}")

    fun savedResults(): List<SavedCapture> = roots.flatMap { it.listFiles().orEmpty().toList() }
        .filter { it.isDirectory && File(it, "result.png").isFile && File(it, "comparison-original.png").isFile && File(it, "processing.json").isFile }
        .map { SavedCapture(it.name, it.name.substringBefore('-').toLongOrNull() ?: it.lastModified()) }
        .distinctBy { it.id }
        .sortedByDescending { it.createdAt }

    fun openResult(id: String): Pair<File, ProcessingResult> {
        require(sessionPattern.matches(id)) { "Invalid saved capture" }
        val directory = roots.map { File(it, id).canonicalFile }.firstOrNull { candidate ->
            candidate.isDirectory && roots.any { candidate.parentFile == it.canonicalFile }
        } ?: error("Saved capture is unavailable")
        val before = File(directory, "comparison-original.png"); val output = File(directory, "result.png")
        require(before.isFile && output.isFile) { "Saved result is incomplete or unavailable" }
        val metadata = runCatching { JSONObject(File(directory, "processing.json").readText()) }.getOrNull()
        val notes = metadata?.optString("notes")?.takeIf { it.isNotBlank() } ?: "Saved result loaded. Processing notes are unavailable."
        val metrics = metadata?.optJSONObject("fusionMetrics")?.let { FusionMetrics(it.getDouble("referenceAreaPercent"), it.getDouble("changedPixelPercent"), it.getDouble("meanOverlapDelta"), it.getInt("outsideMaskChangedPixels")) }
        val aiApplied = metadata?.optBoolean("aiApplied", false) ?: false
        // Older AI sessions saved only result.png. Metadata also prevents a stale
        // standalone model file from being offered after a later baseline-only run.
        val aiOutput = if (aiApplied) File(directory, "onnx-result.png").takeIf { it.isFile } ?: output else null
        val aiProposal = if (aiApplied) File(directory, "onnx-proposal.png").takeIf { it.isFile } else null
        return directory to ProcessingResult(before, output, File(directory, "baseline.png"), notes,
            metadata?.optBoolean("alignmentAccepted", false) ?: false, aiApplied, metrics, aiOutput, aiProposal)
    }

    fun create(): File = File(pendingRoot, System.currentTimeMillis().toString() + "-" + UUID.randomUUID().toString().take(6))
        .apply { check(mkdirs()) { "Cannot create temporary capture directory. Free some device storage." } }

    fun isTemporary(directory: File): Boolean = directory.canonicalFile.parentFile == pendingRoot.canonicalFile

    fun relativeDirectory(directory: File): String {
        val canonical = directory.canonicalFile
        require(sessionPattern.matches(canonical.name)) { "Invalid session directory" }
        return when (canonical.parentFile) {
            pendingRoot.canonicalFile -> "cache/pending-captures/" + canonical.name
            capturesRoot.canonicalFile -> "files/captures/" + canonical.name
            else -> error("Session is outside app capture storage")
        }
    }

    fun discardTemporary(directory: File): Boolean {
        if (!isTemporary(directory)) return false
        require(sessionPattern.matches(directory.name)) { "Invalid temporary session" }
        check(!directory.exists() || directory.deleteRecursively()) { "Cannot clear temporary capture" }
        return true
    }

    fun clearAbandonedTemporarySessions() {
        pendingRoot.listFiles().orEmpty().filter { it.isDirectory && sessionPattern.matches(it.name) }
            .forEach { discardTemporary(it) }
    }

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
