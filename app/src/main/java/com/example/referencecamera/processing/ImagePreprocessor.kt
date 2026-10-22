package com.example.referencecamera.processing

import android.hardware.camera2.CaptureResult
import com.example.referencecamera.camera.CapturedFrame
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min

data class PreparedImage(val image: Mat, val validity: Mat, val correction: String) : AutoCloseable {
    override fun close() { image.release(); validity.release() }
}

class ImagePreprocessor {
    fun prepare(frame: CapturedFrame, maxEdge: Int): PreparedImage = MatResources().use { scope ->
        val original = scope.own(Imgcodecs.imread(frame.file.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION))
        require(!original.empty()) { "Cannot decode ${frame.file.name}" }
        val scaled = scope.own(Mat())
        val factor = min(1.0, maxEdge.toDouble() / maxOf(original.cols(), original.rows()))
        Imgproc.resize(original, scaled, Size(), factor, factor, Imgproc.INTER_AREA)
        val valid = scope.own(Mat(scaled.size(), CvType.CV_8UC1, Scalar(255.0)))
        val corrected = scope.own(Mat())
        val correctedMask = scope.own(Mat())
        val calibration = frame.lens.calibration
        val rawArea = calibration.preCorrectionArray
        val crop = frame.crop
        val intrinsic = calibration.intrinsics
        val d = calibration.distortion
        // Android intrinsics use the pre-correction sensor coordinate system. Only apply the
        // mapping when the actual crop is known, so processed JPEGs cannot be double-corrected.
        val suitable = frame.distortionMode == CaptureResult.DISTORTION_CORRECTION_MODE_OFF &&
            rawArea != null && crop != null && intrinsic?.size == 5 && d?.size == 5 &&
            intrinsic.all { it.isFinite() } && intrinsic[0] > 0 && intrinsic[1] > 0 && d.all { it.isFinite() } &&
            rawArea.contains(crop) &&
            crop.width() > 0 && crop.height() > 0 &&
            abs(crop.width().toDouble() / crop.height() - original.cols().toDouble() / original.rows()) < .03
        val message: String
        if (suitable) {
            val sx = scaled.cols().toDouble() / crop!!.width()
            val sy = scaled.rows().toDouble() / crop.height()
            val k = scope.own(Mat.eye(3, 3, CvType.CV_64F))
            k.put(0, 0, intrinsic!![0] * sx, intrinsic[4] * sx, (intrinsic[2] - crop.left) * sx,
                0.0, intrinsic[1] * sy, (intrinsic[3] - crop.top) * sy, 0.0, 0.0, 1.0)
            // Android [k1,k2,k3,p1,p2] -> OpenCV [k1,k2,p1,p2,k3].
            val distortion = scope.own(MatOfDouble(d!![0].toDouble(), d[1].toDouble(), d[3].toDouble(), d[4].toDouble(), d[2].toDouble()))
            Calib3d.undistort(scaled, corrected, k, distortion, k)
            Calib3d.undistort(valid, correctedMask, k, distortion, k)
            Imgproc.threshold(correctedMask, correctedMask, 254.0, 255.0, Imgproc.THRESH_BINARY)
            // Keep the original UW pixels at any undefined remap boundaries.
            val inverse = scope.own(Mat()); Core.bitwise_not(correctedMask, inverse)
            scaled.copyTo(corrected, inverse)
            message = "OpenCV calibrated undistortion applied; original boundary pixels retained."
        } else {
            scaled.copyTo(corrected); valid.copyTo(correctedMask)
            message = when (frame.distortionMode) {
                CaptureResult.DISTORTION_CORRECTION_MODE_FAST, CaptureResult.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "Camera HAL distortion correction reported; no second correction applied."
                else -> "Suitable crop/calibration data unavailable; lens distortion left as captured."
            }
        }
        val oriented = Mat(); val orientedMask = Mat()
        val code = when (frame.rotationDegrees) { 90 -> Core.ROTATE_90_CLOCKWISE; 180 -> Core.ROTATE_180; 270 -> Core.ROTATE_90_COUNTERCLOCKWISE; else -> -1 }
        if (code == -1) { corrected.copyTo(oriented); correctedMask.copyTo(orientedMask) }
        else { Core.rotate(corrected, oriented, code); Core.rotate(correctedMask, orientedMask, code) }
        PreparedImage(oriented, orientedMask, message)
    }
}
