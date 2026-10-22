package com.example.referencecamera.processing

import org.opencv.core.Mat

/** Explicit lifetime for native OpenCV allocations, including error paths. */
class MatResources : AutoCloseable {
    private val mats = mutableListOf<Mat>()
    fun <T : Mat> own(mat: T): T { mats += mat; return mat }
    override fun close() { mats.asReversed().forEach { it.release() }; mats.clear() }
}
