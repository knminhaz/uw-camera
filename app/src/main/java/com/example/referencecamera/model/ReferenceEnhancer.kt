package com.example.referencecamera.model

import android.content.Context
import ai.onnxruntime.*
import com.example.referencecamera.processing.MatResources
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer

data class Enhancement(val image: Mat?, val message: String)

interface ReferenceEnhancer {
    fun enhance(target: Mat, alignedReference: Mat, confidence: Mat): Enhancement
}

/** Optional explicitly supplied model. This is an IO adapter, not a set of model weights. */
class OnnxReferenceEnhancer(context: Context) : ReferenceEnhancer {
    val modelFile = File(context.filesDir, "models/reference_sr.onnx")

    override fun enhance(target: Mat, alignedReference: Mat, confidence: Mat): Enhancement {
        if (!modelFile.isFile) return Enhancement(null, "AI unavailable: no compatible reference_sr.onnx model installed. Showing OpenCV baseline.")
        return try {
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                env.createSession(modelFile.absolutePath, options).use { session ->
                    val h = target.rows(); val w = target.cols()
                    val expected = mapOf("target" to 3L, "reference" to 3L, "confidence" to 1L)
                    require(session.inputNames == expected.keys) { "Expected inputs target, reference, confidence" }
                    expected.forEach { (name, channels) -> validate(session.inputInfo.getValue(name).info, channels, h, w, name) }
                    require(session.outputNames == setOf("enhanced")) { "Expected a single output named enhanced" }
                    validate(session.outputInfo.getValue("enhanced").info, 3, h, w, "enhanced")
                    val tensors = mutableMapOf<String, OnnxTensor>()
                    try {
                        tensors["target"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw(target)), longArrayOf(1, 3, h.toLong(), w.toLong()))
                        tensors["reference"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw(alignedReference)), longArrayOf(1, 3, h.toLong(), w.toLong()))
                        val mask = FloatArray(h * w); confidence.get(0, 0, mask)
                        tensors["confidence"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, h.toLong(), w.toLong()))
                        session.run(tensors).use { result ->
                            val tensor = result[0] as? OnnxTensor ?: error("Output is not a tensor")
                            require(tensor.info.shape.contentEquals(longArrayOf(1, 3, h.toLong(), w.toLong()))) { "Unexpected runtime output shape" }
                            val values = FloatArray(h * w * 3); tensor.floatBuffer.get(values)
                            require(values.all { it.isFinite() && it in -.05f..1.05f }) { "Output must contain finite RGB values in [0,1]" }
                            val bgr = ByteArray(values.size); val plane = h * w
                            for (i in 0 until plane) for (channel in 0..2) bgr[i * 3 + (2 - channel)] = (values[channel * plane + i].coerceIn(0f, 1f) * 255).toInt().toByte()
                            val candidate = Mat(h, w, CvType.CV_8UC3); candidate.put(0, 0, bgr)
                            try {
                                // An optional model may change only the trustworthy overlap.
                                val output = MatResources().use { resources ->
                                    val mask3 = resources.own(Mat()); Core.merge(listOf(confidence, confidence, confidence), mask3)
                                    val base = resources.own(Mat()); val proposed = resources.own(Mat())
                                    target.convertTo(base, CvType.CV_32FC3); candidate.convertTo(proposed, CvType.CV_32FC3)
                                    Core.subtract(proposed, base, proposed); Core.multiply(proposed, mask3, proposed); Core.add(base, proposed, proposed)
                                    Mat().also { proposed.convertTo(it, CvType.CV_8UC3) }
                                }
                                Enhancement(output, "Optional ONNX reference model executed. Image quality has not been measured.")
                            } finally { candidate.release() }
                        }
                    } finally { tensors.values.forEach { it.close() } }
                }
            }
        } catch (e: Exception) {
            Enhancement(null, "AI unavailable: ${e.message}. Showing OpenCV baseline.")
        } catch (_: OutOfMemoryError) {
            Enhancement(null, "AI unavailable: inference exceeded memory budget. Showing OpenCV baseline.")
        }
    }

    private fun validate(info: ValueInfo, channels: Long, h: Int, w: Int, name: String) {
        val tensor = info as? TensorInfo ?: error("$name must be a tensor")
        require(tensor.type == OnnxJavaType.FLOAT) { "$name must be float32" }
        val actual = tensor.shape; val expected = longArrayOf(1, channels, h.toLong(), w.toLong())
        require(actual.size == 4 && actual.indices.all { actual[it] < 0 || actual[it] == expected[it] }) { "$name must have compatible NCHW dimensions" }
    }

    private fun nchw(image: Mat): FloatArray {
        val bytes = ByteArray(image.rows() * image.cols() * 3); image.get(0, 0, bytes)
        val plane = image.rows() * image.cols(); val values = FloatArray(bytes.size)
        for (i in 0 until plane) for (c in 0..2) values[c * plane + i] = (bytes[i * 3 + 2 - c].toInt() and 255) / 255f
        return values
    }
}
