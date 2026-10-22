package com.example.referencecamera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewGeometryTest {
    @Test fun portraitDoesNotApplySensorRotationTwice() {
        val transform = previewGeometry(1440, 1080, 90, 0, 1080, 1440)
        assertEquals(0f, transform.displayRotation, 0f)
        assertEquals(1f, transform.unscaleX * transform.fitScale, .0001f)
        assertEquals(1f, transform.unscaleY * transform.fitScale, .0001f)
    }
    @Test fun landscapeCompensatesOnlyTheDisplayRotation() {
        val transform = previewGeometry(1440, 1080, 90, 90, 1440, 1080)
        assertEquals(-90f, transform.displayRotation, 0f)
        assertEquals(.75f, transform.unscaleX, .0001f)
        assertEquals(4f/3, transform.unscaleY, .0001f)
        assertEquals(1f, transform.fitScale, .0001f)
    }
    @Test fun portraitSensor270IsAlsoAlreadyOrientedByTextureView() {
        assertEquals(0f, previewGeometry(1440,1080,270,0,1080,1440).displayRotation, 0f)
    }
    @Test fun squareViewportLetterboxesWithoutStretching() {
        val transform = previewGeometry(1440,1080,90,0,500,500)
        assertEquals(.75f, transform.unscaleX * transform.fitScale, .0001f)
        assertEquals(1f, transform.unscaleY * transform.fitScale, .0001f)
    }
}
