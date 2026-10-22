package com.example.referencecamera.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer

class YuvPlanesTest {
    @Test fun planarChromaBecomesVuWithoutRowPadding() {
        val y = PixelPlane(ByteBuffer.wrap(byteArrayOf(1,2,3,4,99,99, 5,6,7,8,99,99, 9,10,11,12,99,99, 13,14,15,16)), 6, 1)
        val u = PixelPlane(ByteBuffer.wrap(byteArrayOf(21,22,99, 23,24)), 3, 1)
        val v = PixelPlane(ByteBuffer.wrap(byteArrayOf(31,32,99, 33,34)), 3, 1)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16, 31,21,32,22,33,23,34,24), YuvPlanes.toNv21(4,4,0,0,listOf(y,u,v)))
    }

    @Test fun interleavedChromaHonorsBufferOffsetsAndPixelStride() {
        val chroma = byteArrayOf(77,31,21,32,22,99,99,33,23,34,24)
        val y = PixelPlane(ByteBuffer.wrap(byteArrayOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)), 4, 1)
        val u = PixelPlane(ByteBuffer.wrap(chroma).apply { position(2) }, 6, 2)
        val v = PixelPlane(ByteBuffer.wrap(chroma).apply { position(1) }, 6, 2)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16, 31,21,32,22,33,23,34,24), YuvPlanes.toNv21(4,4,0,0,listOf(y,u,v)))
    }

    @Test fun sensorCropUsesSubsampledChromaOffsets() {
        val y = PixelPlane(ByteBuffer.wrap(ByteArray(36) { it.toByte() }), 6, 1)
        val u = PixelPlane(ByteBuffer.wrap(ByteArray(9) { (40+it).toByte() }), 3, 1)
        val v = PixelPlane(ByteBuffer.wrap(ByteArray(9) { (60+it).toByte() }), 3, 1)
        assertArrayEquals(byteArrayOf(14,15,20,21,64,44), YuvPlanes.toNv21(2,2,2,2,listOf(y,u,v)))
    }
}
