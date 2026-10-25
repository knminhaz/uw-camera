package com.example.referencecamera.model

import android.content.Context
import ai.onnxruntime.*
import com.example.referencecamera.processing.MatResources
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.roundToInt

data class Enhancement(
    val image: Mat?, val message: String, val proposal: Mat? = null,
    val modelId: String? = null, val modelSha256: String? = null,
    val durationMs: Long? = null, val tilesProcessed: Int = 0
)

interface ReferenceEnhancer {
    fun enhance(target: Mat, alignedReference: Mat, confidence: Mat, progress: (String) -> Unit = {}): Enhancement
}

/** Bundled single-image restoration after OpenCV fusion, or an explicit imported reference model. */
class OnnxReferenceEnhancer(private val context: Context, private val includeBundled: Boolean = true) : ReferenceEnhancer {
    val modelFile = File(context.filesDir, "models/reference_sr.onnx")
    @Volatile var enabled = true
    private val bundledAvailable get() = includeBundled && runCatching {
        context.assets.list("models").orEmpty().contains(BUNDLED_NAME)
    }.getOrDefault(false)
    val isModelAvailable get() = modelFile.isFile || bundledAvailable
    val hasCustomModel get() = modelFile.isFile
    val modelLabel get() = when {
        hasCustomModel -> "Imported ONNX model"
        bundledAvailable -> "Real-ESRGAN general x4v3 (bundled)"
        else -> "No model available"
    }

    private enum class Kind { REFERENCE, REAL_ESRGAN }

    /** Inspect the supported tensor contract before an import replaces a working model. */
    fun validateModel(file: File): String {
        options().use { options ->
            OrtEnvironment.getEnvironment().createSession(file.absolutePath, options).use { session ->
                return when (kind(session)) {
                    Kind.REFERENCE -> "Reference model: target + reference + confidence"
                    Kind.REAL_ESRGAN -> "Real-ESRGAN general x4v3: single-image restoration"
                }
            }
        }
    }

    override fun enhance(target: Mat, alignedReference: Mat, confidence: Mat, progress: (String) -> Unit): Enhancement {
        if (!enabled) return Enhancement(null, "AI enhancement disabled. Showing OpenCV baseline.")
        if (!isModelAvailable) return Enhancement(null, "AI unavailable: no model installed. Showing OpenCV baseline.")
        val started = System.nanoTime()
        return try {
            require(target.type() == CvType.CV_8UC3 && target.size() == alignedReference.size())
            require(confidence.type() == CvType.CV_32FC1 && confidence.size() == target.size())
            val mask = FloatArray(target.rows() * target.cols()); confidence.get(0, 0, mask)
            require(mask.all { it.isFinite() && it in 0f..1f }) { "Invalid confidence values" }
            if (mask.none { it > 0f }) return Enhancement(null, "AI unavailable: no trusted reference region. Showing OpenCV baseline.")
            progress("Loading ONNX model on device…")
            val file = if (modelFile.isFile) modelFile else prepareBundled()
            val checksum = sha256(file)
            val env = OrtEnvironment.getEnvironment()
            options().use { options ->
                env.createSession(file.absolutePath, options).use { session ->
                    val kind = kind(session)
                    val id = if (kind == Kind.REAL_ESRGAN) MODEL_ID else "imported-reference-model"
                    val prediction = when (kind) {
                        Kind.REFERENCE -> referencePrediction(session, env, target, alignedReference, confidence, progress)
                        Kind.REAL_ESRGAN -> tiledPrediction(session, env, target, confidence, progress)
                    }
                    try {
                        val output = gate(target, prediction.image, confidence)
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        val explanation = if (kind == Kind.REAL_ESRGAN)
                            "Real-ESRGAN single-image restoration of the OpenCV baseline, resized from native 4× output to the original processing canvas. The neural model does not directly use the main-camera reference. Only trusted overlap is processed and confidence-blended."
                        else "Imported reference ONNX model executed and confidence-blended."
                        Enhancement(output, explanation + " Image-quality improvement has not been measured.",
                            prediction.image, id, checksum, elapsed, prediction.tiles)
                    } catch (error: Throwable) {
                        prediction.image.release()
                        throw error
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Enhancement(null, "AI unavailable: " + e.message + ". Showing OpenCV baseline.")
        } catch (_: OutOfMemoryError) {
            Enhancement(null, "AI unavailable: inference exceeded memory budget. Showing OpenCV baseline.")
        }
    }

    private data class Prediction(val image: Mat, val tiles: Int)

    private fun referencePrediction(session: OrtSession, env: OrtEnvironment, target: Mat, reference: Mat, confidence: Mat, progress: (String) -> Unit): Prediction {
        val h = target.rows(); val w = target.cols()
        val expected = mapOf("target" to 3L, "reference" to 3L, "confidence" to 1L)
        expected.forEach { (name, c) -> compatible(session.inputInfo.getValue(name).info, c, h, w, name) }
        compatible(session.outputInfo.getValue("enhanced").info, 3, h, w, "enhanced")
        progress("Running imported reference ONNX model…")
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            tensors["target"] = tensor(env, target)
            tensors["reference"] = tensor(env, reference)
            val mask = FloatArray(h * w); confidence.get(0, 0, mask)
            tensors["confidence"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, h.toLong(), w.toLong()))
            session.run(tensors).use { result ->
                val output = result[0] as? OnnxTensor ?: error("Output is not a tensor")
                val values = rgbOutput(output, h, w)
                require(values.all { it in -.05f..1.05f }) { "Reference model output must be RGB in [0,1]" }
                return Prediction(bgr8(values, h, w), 1)
            }
        } finally { tensors.values.forEach { it.close() } }
    }

    private fun tiledPrediction(session: OrtSession, env: OrtEnvironment, target: Mat, confidence: Mat, progress: (String) -> Unit): Prediction {
        val tiles = ModelTiling.plan(target.cols(), target.rows()).filter { tile ->
            val region = confidence.submat(Rect(tile.x, tile.y, tile.width, tile.height))
            try { Core.countNonZero(region) > 0 } finally { region.release() }
        }
        val proposal = target.clone()
        try {
            tiles.forEachIndexed { index, tile ->
                progress("ONNX restoration: tile " + (index + 1) + "/" + tiles.size + "…")
                MatResources().use { r ->
                    val region = r.own(target.submat(Rect(tile.left, tile.top, tile.right - tile.left, tile.bottom - tile.top)))
                    val input = r.own(region.clone())
                    tensor(env, input).use { inputTensor ->
                        session.run(mapOf("image" to inputTensor)).use { result ->
                            val output = result[0] as? OnnxTensor ?: error("Output is not a tensor")
                            val h = input.rows() * 4; val w = input.cols() * 4
                            val values = rgbOutput(output, h, w)
                            val pixels = FloatArray(values.size); val plane = h * w
                            for (i in 0 until plane) for (c in 0..2)
                                pixels[i * 3 + 2 - c] = values[c * plane + i].coerceIn(0f, 1f)
                            val high = r.own(Mat(h, w, CvType.CV_32FC3)); high.put(0, 0, pixels)
                            val low = r.own(Mat()); Imgproc.resize(high, low, input.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                            val lowBytes = r.own(Mat()); low.convertTo(lowBytes, CvType.CV_8UC3, 255.0)
                            val core = r.own(lowBytes.submat(Rect(tile.x - tile.left, tile.y - tile.top, tile.width, tile.height)))
                            val destination = r.own(proposal.submat(Rect(tile.x, tile.y, tile.width, tile.height)))
                            core.copyTo(destination)
                        }
                    }
                }
            }
            return Prediction(proposal, tiles.size)
        } catch (error: Throwable) {
            proposal.release()
            throw error
        }
    }

    private fun kind(session: OrtSession): Kind {
        if (session.inputNames == setOf("target", "reference", "confidence") && session.outputNames == setOf("enhanced")) {
            mapOf("target" to 3L, "reference" to 3L, "confidence" to 1L).forEach { (name, channels) ->
                declared(session.inputInfo.getValue(name).info, channels, name)
            }
            declared(session.outputInfo.getValue("enhanced").info, 3, "enhanced")
            return Kind.REFERENCE
        }
        if (session.inputNames == setOf("image") && session.outputNames == setOf("upscaled")) {
            require(session.metadata.customMetadata["camera.model_id"] == MODEL_ID) {
                "Single-image model must be the documented Real-ESRGAN export"
            }
            val input = declared(session.inputInfo.getValue("image").info, 3, "image")
            val output = declared(session.outputInfo.getValue("upscaled").info, 3, "upscaled")
            require(input.shape.drop(2).all { it < 0 } && output.shape.drop(2).all { it < 0 }) {
                "Real-ESRGAN export must have dynamic height and width"
            }
            require(session.metadata.customMetadata["camera.scale"] == "4") { "Expected native scale 4" }
            return Kind.REAL_ESRGAN
        }
        error("Unsupported ONNX contract. Import a documented reference model or the bundled-model export.")
    }

    private fun declared(info: ValueInfo, channels: Long, name: String): TensorInfo {
        val tensor = info as? TensorInfo ?: error(name + " must be a tensor")
        require(tensor.type == OnnxJavaType.FLOAT) { name + " must be float32" }
        require(tensor.shape.size == 4 && (tensor.shape[0] < 0 || tensor.shape[0] == 1L) &&
            (tensor.shape[1] < 0 || tensor.shape[1] == channels)) {
            name + " must have batch 1 and the documented NCHW channel count"
        }
        return tensor
    }

    private fun compatible(info: ValueInfo, channels: Long, h: Int, w: Int, name: String) {
        val tensor = declared(info, channels, name)
        require((tensor.shape[2] < 0 || tensor.shape[2] == h.toLong()) &&
            (tensor.shape[3] < 0 || tensor.shape[3] == w.toLong())) { name + " dimensions do not match the image" }
    }

    private fun tensor(env: OrtEnvironment, image: Mat): OnnxTensor {
        val h = image.rows(); val w = image.cols(); val plane = h * w
        val bytes = ByteArray(plane * 3); image.get(0, 0, bytes)
        val values = FloatArray(bytes.size)
        for (i in 0 until plane) for (c in 0..2)
            values[c * plane + i] = (bytes[i * 3 + 2 - c].toInt() and 255) / 255f
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    private fun rgbOutput(tensor: OnnxTensor, h: Int, w: Int): FloatArray {
        require(tensor.info.type == OnnxJavaType.FLOAT &&
            tensor.info.shape.contentEquals(longArrayOf(1, 3, h.toLong(), w.toLong()))) { "Unexpected model output shape or type" }
        val values = FloatArray(h * w * 3); tensor.floatBuffer.get(values)
        require(values.all { it.isFinite() }) { "Model returned non-finite pixels" }
        return values
    }

    private fun bgr8(values: FloatArray, h: Int, w: Int): Mat {
        val bytes = ByteArray(values.size); val plane = h * w
        for (i in 0 until plane) for (c in 0..2)
            bytes[i * 3 + 2 - c] = (values[c * plane + i].coerceIn(0f, 1f) * 255).roundToInt().toByte()
        return Mat(h, w, CvType.CV_8UC3).also { it.put(0, 0, bytes) }
    }

    private fun gate(target: Mat, candidate: Mat, confidence: Mat): Mat = MatResources().use { r ->
        val mask3 = r.own(Mat()); Core.merge(listOf(confidence, confidence, confidence), mask3)
        val base = r.own(Mat()); target.convertTo(base, CvType.CV_32FC3)
        val proposed = r.own(Mat()); candidate.convertTo(proposed, CvType.CV_32FC3)
        Core.subtract(proposed, base, proposed); Core.multiply(proposed, mask3, proposed); Core.add(base, proposed, proposed)
        val output = Mat()
        try {
            proposed.convertTo(output, CvType.CV_8UC3)
            val zero = r.own(Mat()); Imgproc.threshold(confidence, zero, 0.0, 255.0, Imgproc.THRESH_BINARY_INV)
            zero.convertTo(zero, CvType.CV_8U); target.copyTo(output, zero)
            output
        } catch (error: Throwable) { output.release(); throw error }
    }

    private fun prepareBundled(): File {
        val metadata = context.assets.open("models/model.json").bufferedReader().use { JSONObject(it.readText()) }
        val expected = metadata.getString("sha256")
        val target = File(context.filesDir, "models/" + BUNDLED_NAME)
        if (target.isFile && sha256(target) == expected) return target
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create model directory" }
        val temporary = File(target.parentFile, BUNDLED_NAME + ".part")
        try {
            context.assets.open("models/" + BUNDLED_NAME).use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            }
            check(sha256(temporary) == expected) { "Bundled model checksum mismatch" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { temporary.delete() }
    }

    companion object {
        const val MODEL_ID = "realesr-general-x4v3"
        const val BUNDLED_NAME = "realesr-general-x4v3.onnx"
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun options() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2); setInterOpNumThreads(1)
        }
    }
}