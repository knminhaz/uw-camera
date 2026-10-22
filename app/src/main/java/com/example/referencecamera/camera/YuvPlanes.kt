package com.example.referencecamera.camera

import java.nio.ByteBuffer

data class PixelPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/** Camera2 does not promise planar or NV21 layout, contiguous rows, or zero buffer position. */
object YuvPlanes {
    fun toNv21(width: Int, height: Int, cropLeft: Int, cropTop: Int, planes: List<PixelPlane>): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && cropLeft % 2 == 0 && cropTop % 2 == 0) { "YUV crop must use positive even dimensions and even offsets" }
        require(planes.size == 3)
        val data = ByteArray(width * height * 3 / 2)
        planes.forEachIndexed { planeIndex, plane ->
            val base = plane.buffer.position()
            val shift = if (planeIndex == 0) 0 else 1
            for (row in 0 until (height shr shift)) for (col in 0 until (width shr shift)) {
                val src = base + (row + (cropTop shr shift)) * plane.rowStride + (col + (cropLeft shr shift)) * plane.pixelStride
                val dst = if (planeIndex == 0) row * width + col else width * height + row * width + col * 2 + if (planeIndex == 1) 1 else 0
                data[dst] = plane.buffer.get(src)
            }
        }
        return data
    }
}
