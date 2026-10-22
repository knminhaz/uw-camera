package com.example.referencecamera.storage

import android.content.Context
import android.net.Uri
import com.example.referencecamera.camera.CameraReport
import com.example.referencecamera.camera.CapturedFrame
import com.example.referencecamera.camera.PairSupport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CaptureStore(private val context: Context) {
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
