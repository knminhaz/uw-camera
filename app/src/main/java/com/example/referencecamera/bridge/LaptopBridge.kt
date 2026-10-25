package com.example.referencecamera.bridge

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.example.referencecamera.model.OnnxReferenceEnhancer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class LaptopConnection(val ready: Boolean, val message: String, val companionId: String? = null)

/** Metadata-only USB bridge. Images become transferable only after an explicit confirmation. */
class LaptopBridge(private val context: Context) {
    val instanceId: String = UUID.randomUUID().toString()
    private val directory = File(context.filesDir, "bridge").apply { mkdirs() }
    @Volatile private var request: JSONObject? = null
    private val durations = linkedMapOf<String, Long>()
    private val completed = linkedSetOf<String>()
    private var step: String? = null
    private var stepStarted = 0L

    @Synchronized fun begin() {
        durations.clear(); completed.clear()
        step = "capture"; stepStarted = SystemClock.elapsedRealtime()
        clearRequest()
    }

    @Synchronized fun progress(message: String) {
        val next = when {
            message.startsWith("Saving stage") -> "finalize"
            message.contains("ONNX", true) || message.startsWith("AI ") -> "ai"
            message.contains("confidence", true) || message.contains("fusion", true) -> "fusion"
            message.startsWith("Aligning") -> "align"
            message.startsWith("Preparing") || message.startsWith("Memory pressure") -> "prepare"
            message.startsWith("1/2") || message.startsWith("2/2") || message.startsWith("Settling") -> "capture"
            else -> null
        } ?: return
        if (next != step) {
            finishStep()
            step = next; stepStarted = SystemClock.elapsedRealtime()
        }
    }

    @Synchronized fun complete() { finishStep(); step = null }

    private fun finishStep() {
        step?.let { current ->
            durations[current] = (durations[current] ?: 0) + SystemClock.elapsedRealtime() - stepStarted
            completed += current
        }
    }

    @Synchronized fun timings(): JSONObject = JSONObject().apply {
        durations.forEach { (key, value) -> put(key, value) }
        step?.let { put(it, (durations[it] ?: 0) + SystemClock.elapsedRealtime() - stepStarted) }
    }

    @Synchronized fun publish(snapshot: JSONObject) {
        snapshot.put("protocol", 1).put("instanceId", instanceId)
            .put("package", context.packageName).put("deviceName", Build.MODEL)
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("currentStep", step ?: JSONObject.NULL)
            .put("stageTimingsMs", timings())
            .put("stepsCompleted", JSONArray(completed.toList()))
            .put("transferRequest", request ?: JSONObject.NULL)
        atomicWrite(File(directory, "status.json"), snapshot)
    }

    fun connection(): LaptopConnection {
        val heartbeat = readJson(File(directory, "heartbeat.json"))
            ?: return LaptopConnection(false, "Laptop companion not connected")
        val age = SystemClock.elapsedRealtime() - heartbeat.optLong("echoElapsedMs", -1)
        if (heartbeat.optInt("protocol") != 1 || heartbeat.optString("appInstanceId") != instanceId || age !in 0..8000)
            return LaptopConnection(false, "Laptop companion not connected")
        val id = heartbeat.optString("companionId").takeIf { it.isNotBlank() }
        val ready = heartbeat.optBoolean("ready") && id != null
        return LaptopConnection(ready, heartbeat.optString("message", "Laptop not ready"), id)
    }

    fun hasRequest(): Boolean = request != null

    fun prepareTransfer(session: File, relativeDirectory: String): JSONObject {
        val link = connection()
        check(link.ready) { "Laptop companion is not ready" }
        require(relativeDirectory == "cache/pending-captures/" + session.name ||
            relativeDirectory == "files/captures/" + session.name) { "Invalid session directory" }
        require(session.name.matches(Regex("[0-9]{10,16}-[a-z0-9]{6,32}"))) { "Invalid session ID" }
        val files = session.listFiles().orEmpty().filter {
            it.isFile && it.name != "transfer-manifest.json" && !it.name.endsWith(".part")
        }.sortedBy { it.name }
        require(files.size in 1..128 && files.sumOf { it.length() } <= 2L * 1024 * 1024 * 1024) { "Session exceeds transfer limits" }
        val entries = JSONArray()
        files.forEach { file ->
            require(file.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) && !file.name.contains(".."))
            entries.put(JSONObject().put("name", file.name).put("bytes", file.length())
                .put("sha256", OnnxReferenceEnhancer.sha256(file)))
        }
        val manifest = JSONObject().put("protocol", 1).put("sessionId", session.name).put("files", entries)
        val text = manifest.toString()
        val current = connection()
        check(current.ready && current.companionId == link.companionId) { "Laptop connection changed while preparing transfer" }
        val value = JSONObject().put("protocol", 1).put("requestId", UUID.randomUUID().toString())
            .put("appInstanceId", instanceId).put("companionId", link.companionId)
            .put("relativeDirectory", relativeDirectory).put("manifestJson", text)
            .put("manifestSha256", sha256(text.toByteArray(Charsets.UTF_8)))
        atomicWrite(File(session, "transfer-manifest.json"), manifest)
        File(directory, "ack.json").delete()
        request = value
        atomicWrite(File(directory, "request.json"), value)
        return value
    }

    suspend fun awaitAcknowledgement(expected: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val ack = readJson(File(directory, "ack.json"))
            if (ack != null && ack.optString("requestId") == expected.getString("requestId") &&
                ack.optString("appInstanceId") == instanceId &&
                ack.optString("companionId") == expected.getString("companionId") &&
                ack.optString("manifestSha256") == expected.getString("manifestSha256")) {
                check(ack.optBoolean("success")) { ack.optString("error", "Laptop transfer was not verified") }
                val expectedCount = JSONObject(expected.getString("manifestJson")).getJSONArray("files").length()
                check(ack.optInt("fileCount") == expectedCount && ack.optString("archiveId").isNotBlank()) {
                    "Laptop acknowledgement is incomplete"
                }
                return@withContext ack
            }
            val link = connection()
            check(link.ready && link.companionId == expected.getString("companionId")) {
                "Laptop connection lost. Preview is temporary; retry OK or discard."
            }
            delay(250)
        }
        error("Laptop verification timed out. Preview has not been discarded.")
    }

    @Synchronized fun clearRequest() {
        request = null
        File(directory, "request.json").delete()
        File(directory, "ack.json").delete()
    }

    companion object {
        fun readJson(file: File): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()
        fun atomicWrite(file: File, value: JSONObject) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val temporary = File(file.parentFile, file.name + ".part")
            try {
                temporary.writeText(value.toString())
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
