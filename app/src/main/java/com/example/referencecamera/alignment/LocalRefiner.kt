package com.example.referencecamera.alignment

import com.example.referencecamera.processing.MatResources
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.*

/** Small residual refinement only. Large displacement, occlusion, and inconsistent flow are rejected. */
class LocalRefiner {
    fun refine(targetGray: Mat, warpedBgr: Mat, valid: Mat): String = MatResources().use { r ->
        val scale = min(1.0, 640.0 / max(targetGray.rows(), targetGray.cols()))
        val target = r.own(Mat()); Imgproc.resize(targetGray, target, Size(), scale, scale, Imgproc.INTER_AREA)
        val referenceGray = r.own(Mat()); Imgproc.cvtColor(warpedBgr, referenceGray, Imgproc.COLOR_BGR2GRAY)
        val reference = r.own(Mat()); Imgproc.resize(referenceGray, reference, target.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        val forward = r.own(Mat()); val backward = r.own(Mat())
        Video.calcOpticalFlowFarneback(target, reference, forward, .5, 3, 19, 3, 5, 1.2, 0)
        Video.calcOpticalFlowFarneback(reference, target, backward, .5, 3, 19, 3, 5, 1.2, 0)
        val w = target.cols(); val h = target.rows()
        val f = FloatArray(w * h * 2); val b = FloatArray(f.size)
        forward.get(0, 0, f); backward.get(0, 0, b)
        val accepted = ByteArray(w * h)
        fun sample(x: Float, y: Float, channel: Int): Float {
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val a = x - x0; val c = y - y0
            return b[(y0 * w + x0) * 2 + channel] * (1-a) * (1-c) +
                b[(y0 * w + x0 + 1) * 2 + channel] * a * (1-c) +
                b[((y0+1) * w + x0) * 2 + channel] * (1-a) * c +
                b[((y0+1) * w + x0+1) * 2 + channel] * a * c
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 2; val dx = f[i]; val dy = f[i+1]
            val rx = x + dx; val ry = y + dy
            if (rx.isFinite() && ry.isFinite() && rx >= 0 && ry >= 0 && rx < w-1 && ry < h-1 && hypot(dx.toDouble(), dy.toDouble()) <= 8.0 * scale) {
                val error = hypot((dx + sample(rx, ry, 0)).toDouble(), (dy + sample(rx, ry, 1)).toDouble())
                if (error <= .8) accepted[y * w + x] = 255.toByte()
            }
        }
        val lowMask = r.own(Mat(h, w, CvType.CV_8U)); lowMask.put(0, 0, accepted)
        val fullMask = r.own(Mat()); Imgproc.resize(lowMask, fullMask, targetGray.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val resizedFlow = r.own(Mat()); Imgproc.resize(forward, resizedFlow, targetGray.size())
        val fullW = targetGray.cols(); val fullH = targetGray.rows()
        val values = FloatArray(fullW * fullH * 2); resizedFlow.get(0, 0, values)
        val xs = FloatArray(fullW * fullH); val ys = FloatArray(xs.size)
        for (y in 0 until fullH) for (x in 0 until fullW) {
            val i = y * fullW + x
            xs[i] = x + values[i*2] * fullW / w
            ys[i] = y + values[i*2+1] * fullH / h
        }
        val mx = r.own(Mat(fullH, fullW, CvType.CV_32F)); mx.put(0, 0, xs)
        val my = r.own(Mat(fullH, fullW, CvType.CV_32F)); my.put(0, 0, ys)
        val refined = r.own(Mat()); val refinedValid = r.own(Mat())
        Imgproc.remap(warpedBgr, refined, mx, my, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT)
        Imgproc.remap(valid, refinedValid, mx, my, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT)
        Core.bitwise_and(valid, refinedValid, valid); Core.bitwise_and(valid, fullMask, valid)
        refined.copyTo(warpedBgr)
        "Bounded local optical flow applied (≤8 canvas pixels; forward/backward consistency checked)."
    }
}
