package com.example.referencecamera.alignment

import com.example.referencecamera.processing.MatResources
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class Alignment(val warped: Mat, val confidence: Mat, val diagnostic: String, val accepted: Boolean) : AutoCloseable {
    override fun close() { warped.release(); confidence.release() }
}

/** A conservative homography plus spatial inlier and local photometric confidence.
 * Local confidence rejects parallax/motion; it does not claim to solve arbitrary 3D scenes. */
class FeatureAligner {
    fun align(wide: Mat, reference: Mat, wideValidity: Mat, referenceValidity: Mat): Alignment = MatResources().use { r ->
        Core.setRNGSeed(47)
        val wg = r.own(Mat()); val rg = r.own(Mat())
        Imgproc.cvtColor(wide, wg, Imgproc.COLOR_BGR2GRAY); Imgproc.cvtColor(reference, rg, Imgproc.COLOR_BGR2GRAY)
        val wk = r.own(MatOfKeyPoint()); val rk = r.own(MatOfKeyPoint())
        val wd = r.own(Mat()); val rd = r.own(Mat())
        val detector = ORB.create(6500, 1.2f, 10)
        try { detector.detectAndCompute(wg, wideValidity, wk, wd); detector.detectAndCompute(rg, referenceValidity, rk, rd) }
        finally { detector.clear() }
        if (wd.empty() || rd.empty()) return@use rejected(wide, "Not enough textured features. Ultrawide retained.")
        val matches = mutableListOf<MatOfDMatch>()
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        try { matcher.knnMatch(rd, wd, matches, 2) } finally { matcher.clear() }
        val good = matches.mapNotNull { match ->
            val pair = match.toArray(); match.release()
            if (pair.size == 2 && pair[0].distance < .73f * pair[1].distance) pair[0] else null
        }.distinctBy { it.trainIdx }
        if (good.size < 20) return@use rejected(wide, "Only ${good.size} reliable feature matches. Ultrawide retained.")
        val refKeys = rk.toArray(); val wideKeys = wk.toArray()
        val source = r.own(MatOfPoint2f(*good.map { refKeys[it.queryIdx].pt }.toTypedArray()))
        val target = r.own(MatOfPoint2f(*good.map { wideKeys[it.trainIdx].pt }.toTypedArray()))
        val inliers = r.own(Mat())
        val h = r.own(Calib3d.findHomography(source, target, Calib3d.RANSAC, 3.0, inliers, 3000, .995))
        if (h.empty()) return@use rejected(wide, "Homography estimation failed. Ultrawide retained.")
        val selected = good.indices.filter { inliers.get(it, 0)[0] > 0 }
        if (selected.size < 18 || selected.size.toDouble() / good.size < .35) return@use rejected(wide, "Low geometric confidence (${selected.size}/${good.size} inliers). Ultrawide retained.")
        val corners = r.own(MatOfPoint2f(Point(0.0, 0.0), Point(reference.cols().toDouble(), 0.0), Point(reference.cols().toDouble(), reference.rows().toDouble()), Point(0.0, reference.rows().toDouble())))
        val projected = r.own(MatOfPoint2f()); Core.perspectiveTransform(corners, projected, h)
        val polygon = r.own(MatOfPoint(*projected.toArray()))
        val area = abs(Imgproc.contourArea(polygon)) / (wide.cols().toDouble() * wide.rows())
        if (!area.isFinite() || area !in .07..1.05 || !Imgproc.isContourConvex(polygon)) return@use rejected(wide, "Implausible projected overlap. Ultrawide retained.")
        val warped = r.own(Mat()); Imgproc.warpPerspective(reference, warped, h, wide.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT)
        val valid = r.own(Mat()); Imgproc.warpPerspective(referenceValidity, valid, h, wide.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT)
        Core.bitwise_and(valid, wideValidity, valid)
        val kernel = r.own(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0)))
        Imgproc.erode(valid, valid, kernel)
        // Limit transfer to the convex hull actually supported by inlier correspondences.
        val supportPoints = r.own(MatOfPoint(*selected.map { wideKeys[good[it].trainIdx].pt }.toTypedArray()))
        val hullIndices = r.own(MatOfInt()); Imgproc.convexHull(supportPoints, hullIndices)
        val points = supportPoints.toArray()
        val hull = r.own(MatOfPoint(*hullIndices.toArray().map { points[it] }.toTypedArray()))
        val support = r.own(Mat.zeros(wide.size(), CvType.CV_8UC1)); Imgproc.fillConvexPoly(support, hull, Scalar(255.0))
        Core.bitwise_and(valid, support, valid)
        val warpedGray = r.own(Mat()); Imgproc.cvtColor(warped, warpedGray, Imgproc.COLOR_BGR2GRAY)
        // Normalize exposure using overlap mean/deviation before checking local residuals.
        val wm = r.own(MatOfDouble()); val ws = r.own(MatOfDouble()); val rm = r.own(MatOfDouble()); val rs = r.own(MatOfDouble())
        Core.meanStdDev(wg, wm, ws, valid); Core.meanStdDev(warpedGray, rm, rs, valid)
        val gain = (ws.toArray()[0] / max(rs.toArray()[0], 1.0)).coerceIn(.65, 1.55)
        warped.convertTo(warped, -1, gain, wm.toArray()[0] - rm.toArray()[0] * gain)
        val refinement = LocalRefiner().refine(wg, warped, valid)
        Imgproc.cvtColor(warped, warpedGray, Imgproc.COLOR_BGR2GRAY)
        val wa = r.own(Mat()); val ra = r.own(Mat())
        Imgproc.GaussianBlur(wg, wa, Size(5.0, 5.0), 0.0); Imgproc.GaussianBlur(warpedGray, ra, Size(5.0, 5.0), 0.0)
        val residual = r.own(Mat()); Core.absdiff(wa, ra, residual)
        val local = r.own(Mat()); Imgproc.threshold(residual, local, 23.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.erode(local, local, kernel); Core.bitwise_and(valid, local, valid)
        // Distance to both overlap seams and rejected regions forms a confidence weight.
        val distance = r.own(Mat()); Imgproc.distanceTransform(valid, distance, Imgproc.DIST_L2, 3)
        Core.multiply(distance, Scalar(1.0 / 28.0), distance); Imgproc.threshold(distance, distance, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        val confidence = r.own(Mat()); distance.convertTo(confidence, CvType.CV_32F)
        val coverage = Core.countNonZero(valid).toDouble() / (wide.rows() * wide.cols())
        if (coverage < .015) return@use rejected(wide, "Overlap rejected by local residual confidence. Ultrawide retained.")
        Alignment(warped.clone(), confidence.clone(), "${selected.size}/${good.size} homography inliers; ${(coverage * 100).roundToInt()}% reliable canvas coverage. $refinement Nearby 3D objects and occlusions can still fail.", true)
    }

    private fun rejected(wide: Mat, reason: String) = Alignment(wide.clone(), Mat.zeros(wide.size(), CvType.CV_32F), reason, false)
}
