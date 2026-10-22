package com.example.referencecamera.fusion

import com.example.referencecamera.processing.MatResources
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Four-level Laplacian detail transfer. UW remains the canvas and low-frequency base. */
class MultiscaleFusion {
    fun fuse(wide: Mat, reference: Mat, confidence: Mat): Mat = MatResources().use { r ->
        val wg = mutableListOf(r.own(Mat()))
        val rg = mutableListOf(r.own(Mat()))
        val masks = mutableListOf(r.own(confidence.clone()))
        wide.convertTo(wg[0], CvType.CV_32FC3, 1.0 / 255)
        reference.convertTo(rg[0], CvType.CV_32FC3, 1.0 / 255)
        repeat(4) {
            val nextW = r.own(Mat()); val nextR = r.own(Mat()); val nextM = r.own(Mat())
            Imgproc.pyrDown(wg.last(), nextW); Imgproc.pyrDown(rg.last(), nextR); Imgproc.pyrDown(masks.last(), nextM)
            wg += nextW; rg += nextR; masks += nextM
        }
        val bands = mutableListOf<Mat>()
        for (level in 0 until 4) {
            val wu = r.own(Mat()); val ru = r.own(Mat())
            Imgproc.pyrUp(wg[level + 1], wu, wg[level].size()); Imgproc.pyrUp(rg[level + 1], ru, rg[level].size())
            val wl = r.own(Mat()); val rl = r.own(Mat())
            Core.subtract(wg[level], wu, wl); Core.subtract(rg[level], ru, rl)
            val mask3 = r.own(Mat()); Core.merge(listOf(masks[level], masks[level], masks[level]), mask3)
            // Add only the masked difference between detail bands. The UW base is retained.
            val delta = r.own(Mat()); Core.subtract(rl, wl, delta); Core.multiply(delta, mask3, delta)
            val band = r.own(Mat()); Core.add(wl, delta, band); bands += band
        }
        var reconstructed = r.own(wg.last().clone())
        for (level in 3 downTo 0) {
            val up = r.own(Mat()); Imgproc.pyrUp(reconstructed, up, bands[level].size())
            val next = r.own(Mat()); Core.add(up, bands[level], next); reconstructed = next
        }
        val candidate = r.own(Mat()); reconstructed.convertTo(candidate, CvType.CV_8UC3, 255.0)
        val support = r.own(Mat()); Imgproc.threshold(confidence, support, .01, 255.0, Imgproc.THRESH_BINARY); support.convertTo(support, CvType.CV_8U)
        val result = wide.clone(); candidate.copyTo(result, support)
        result
    }
}
