package com.example.referencecamera.camera

import org.junit.Assert.*
import org.junit.Test

class FrameMatcherTest {
    @Test fun imageBeforeMetadataMatchesTheExactTimestamp() {
        val frames = FrameMatcher<String>()
        assertNull(frames.image(10, "warmup")); assertNull(frames.image(20, "still"))
        assertEquals("still", frames.result(20)?.value)
    }
    @Test fun metadataBeforeImageRejectsOldFrames() {
        val frames = FrameMatcher<String>()
        assertNull(frames.result(20)); assertNull(frames.image(10, "warmup"))
        assertEquals("still", frames.image(20, "still")?.value)
    }
    @Test fun boundedQueueDropsOnlyTheOldestPendingImage() {
        val frames = FrameMatcher<String>(2)
        frames.image(10, "old"); frames.image(20, "middle"); frames.image(30, "latest")
        assertNull(frames.result(10))
    }
}
