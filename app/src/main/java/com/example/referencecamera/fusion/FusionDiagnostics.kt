package com.example.referencecamera.fusion

import com.example.referencecamera.processing.MatResources
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

data class FusionMetrics(val referenceAreaPercent: Double, val changedPixelPercent: Double, val meanOverlapDelta: Double, val outsideMaskChangedPixels: Int) {
    fun json() = JSONObject().put("referenceAreaPercent", referenceAreaPercent).put("changedPixelPercent", changedPixelPercent)
        .put("meanOverlapDelta", meanOverlapDelta).put("outsideMaskChangedPixels", outsideMaskChangedPixels)
}

/** Pixel-change diagnostics against the prepared UW input; these are not image-quality scores. */
object FusionDiagnostics {
    fun measure(input: Mat, fused: Mat, confidence: Mat): FusionMetrics = MatResources().use { r ->
        val diff = r.own(Mat()); Core.absdiff(input, fused, diff)
        val channels = mutableListOf<Mat>(); Core.split(diff, channels); channels.forEach { r.own(it) }
        val largest = r.own(Mat()); Core.max(channels[0], channels[1], largest); Core.max(largest, channels[2], largest)
        val changed = r.own(Mat()); Imgproc.threshold(largest, changed, 1.0, 255.0, Imgproc.THRESH_BINARY)
        val support = r.own(Mat()); Imgproc.threshold(confidence, support, .01, 255.0, Imgproc.THRESH_BINARY); support.convertTo(support, CvType.CV_8U)
        val outside = r.own(Mat()); Core.bitwise_not(support, outside); Core.bitwise_and(outside, changed, outside)
        val count = input.rows().toDouble() * input.cols()
        FusionMetrics(Core.countNonZero(support) / count * 100, Core.countNonZero(changed) / count * 100,
            Core.mean(diff, support).`val`.take(3).average(), Core.countNonZero(outside))
    }
}
