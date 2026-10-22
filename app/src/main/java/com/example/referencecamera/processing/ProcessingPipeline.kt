package com.example.referencecamera.processing

import com.example.referencecamera.alignment.FeatureAligner
import com.example.referencecamera.camera.CapturedFrame
import com.example.referencecamera.fusion.MultiscaleFusion
import com.example.referencecamera.fusion.FusionDiagnostics
import com.example.referencecamera.fusion.FusionMetrics
import com.example.referencecamera.model.ReferenceEnhancer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

data class ProcessingResult(val before: File, val output: File, val baseline: File, val message: String, val referenceUsed: Boolean = false, val aiApplied: Boolean = false, val metrics: FusionMetrics? = null)

class ProcessingPipeline(private val enhancer: ReferenceEnhancer) {
    suspend fun process(wide: CapturedFrame, main: CapturedFrame, directory: File, progress: (String) -> Unit): ProcessingResult = withContext(Dispatchers.Default) {
        check(OpenCVLoader.initLocal()) { "OpenCV native library could not be loaded." }
        Core.setNumThreads(2)
        try { run(wide, main, directory, 1280, progress) }
        catch (_: OutOfMemoryError) {
            progress("Memory pressure: retrying at a 960-pixel canvas…")
            run(wide, main, directory, 960, progress)
        }
    }

    private fun run(wideFrame: CapturedFrame, mainFrame: CapturedFrame, directory: File, maxEdge: Int, progress: (String) -> Unit): ProcessingResult {
        val preprocessor = ImagePreprocessor()
        progress("Preparing images and lens correction…")
        preprocessor.prepare(wideFrame, maxEdge).use { wide ->
            preprocessor.prepare(mainFrame, (maxEdge * 1.25).toInt()).use { main ->
                val before = File(directory, "comparison-original.png")
                // Comparison is the oriented, resized original, before geometric lens correction.
                MatResources().use { r ->
                    val raw = r.own(Imgcodecs.imread(wideFrame.file.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION))
                    val oriented = r.own(Mat())
                    val rotation = when (wideFrame.rotationDegrees) { 90 -> Core.ROTATE_90_CLOCKWISE; 180 -> Core.ROTATE_180; 270 -> Core.ROTATE_90_COUNTERCLOCKWISE; else -> -1 }
                    if (rotation == -1) raw.copyTo(oriented) else Core.rotate(raw, oriented, rotation)
                    val resized = r.own(Mat()); org.opencv.imgproc.Imgproc.resize(oriented, resized, wide.image.size(), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
                    check(Imgcodecs.imwrite(before.absolutePath, resized)) { "Cannot save comparison input" }
                }
                progress("Aligning main-camera reference…")
                FeatureAligner().align(wide.image, main.image, wide.validity, main.validity).use { alignment ->
                    progress("Building confidence mask and multiscale fusion…")
                    val baselineImage = if (alignment.accepted) MultiscaleFusion().fuse(wide.image, alignment.warped, alignment.confidence) else wide.image.clone()
                    try {
                        val metrics = FusionDiagnostics.measure(wide.image, baselineImage, alignment.confidence)
                        val baseline = File(directory, "baseline.png"); check(Imgcodecs.imwrite(baseline.absolutePath, baselineImage))
                        MatResources().use { r ->
                            val mask = r.own(Mat()); alignment.confidence.convertTo(mask, CvType.CV_8U, 255.0)
                            check(Imgcodecs.imwrite(File(directory, "confidence.png").absolutePath, mask))
                            check(Imgcodecs.imwrite(File(directory, "aligned-reference.jpg").absolutePath, alignment.warped))
                        }
                        progress("Checking optional ONNX enhancement…")
                        val enhancement = if (alignment.accepted) enhancer.enhance(baselineImage, alignment.warped, alignment.confidence)
                            else com.example.referencecamera.model.Enhancement(null, "AI unavailable: reliable reference alignment is unavailable. Showing OpenCV baseline.")
                        val output = File(directory, "result.png")
                        try { check(Imgcodecs.imwrite(output.absolutePath, enhancement.image ?: baselineImage)) { "Cannot save result" } }
                        finally { enhancement.image?.release() }
                        val notes = listOf(alignment.diagnostic, "Ultrawide: ${wide.correction}", "Main: ${main.correction}", enhancement.message,
                            "Input exposure gap: ${kotlin.math.abs(wideFrame.timestampNs - mainFrame.timestampNs) / 1_000_000.0} ms. Capture sizes: ${wideFrame.lens.captureSize} / ${mainFrame.lens.captureSize}.",
                            "Output ${wide.image.cols()}×${wide.image.rows()}; ultrawide canvas retained. Inputs are YUV frames encoded locally as JPEG, without Google Camera computational processing.")
                        File(directory, "processing.json").writeText(JSONObject().apply {
                            put("alignmentAccepted", alignment.accepted); put("notes", notes.joinToString("\n")); put("canvasMaxEdge", maxEdge)
                            put("aiApplied", enhancement.image != null)
                            put("fusionMetrics", metrics.json())
                            put("timestampDeltaMs", kotlin.math.abs(wideFrame.timestampNs - mainFrame.timestampNs) / 1_000_000.0)
                        }.toString(2))
                        return ProcessingResult(before, output, baseline, notes.joinToString("\n\n"), alignment.accepted, enhancement.image != null, metrics)
                    } finally { baselineImage.release() }
                }
            }
        }
    }
}
