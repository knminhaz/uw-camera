package com.example.referencecamera.ui

import kotlin.math.min

data class PreviewGeometry(val unscaleX: Float, val unscaleY: Float, val displayRotation: Float, val fitScale: Float)

/** TextureView already applies SENSOR_ORIENTATION. Only compensate display rotation here. */
fun previewGeometry(bufferWidth: Int, bufferHeight: Int, sensorDegrees: Int, displayDegrees: Int, viewWidth: Int, viewHeight: Int): PreviewGeometry {
    require(bufferWidth > 0 && bufferHeight > 0 && viewWidth > 0 && viewHeight > 0)
    val naturalWidth = if (sensorDegrees % 180 == 0) bufferWidth.toFloat() else bufferHeight.toFloat()
    val naturalHeight = if (sensorDegrees % 180 == 0) bufferHeight.toFloat() else bufferWidth.toFloat()
    val rotatedWidth = if (displayDegrees % 180 == 0) naturalWidth else naturalHeight
    val rotatedHeight = if (displayDegrees % 180 == 0) naturalHeight else naturalWidth
    return PreviewGeometry(naturalWidth / viewWidth, naturalHeight / viewHeight, -displayDegrees.toFloat(), min(viewWidth / rotatedWidth, viewHeight / rotatedHeight))
}
