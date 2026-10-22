package com.example.referencecamera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import android.util.Size as AndroidSize
import com.example.referencecamera.camera.CapturedFrame
import com.example.referencecamera.camera.LensCalibration
import com.example.referencecamera.camera.LensChoice
import com.example.referencecamera.model.OnnxReferenceEnhancer
import com.example.referencecamera.processing.ProcessingPipeline
import kotlinx.coroutines.runBlocking
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import com.example.referencecamera.alignment.FeatureAligner
import com.example.referencecamera.fusion.MultiscaleFusion
import com.example.referencecamera.fusion.FusionDiagnostics
import com.example.referencecamera.processing.MatResources
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.Random

@RunWith(AndroidJUnit4::class)
class BaselineInstrumentedTest {
    @Before fun loadOpenCv() { assertTrue(OpenCVLoader.initLocal()); Core.setNumThreads(1); Core.setRNGSeed(47) }

    @Test fun zeroConfidencePreservesEveryUltrawidePixel() = MatResources().use { r ->
        val wide = r.own(texture(641, 479))
        val reference = r.own(Mat(wide.size(), wide.type(), Scalar(255.0, 0.0, 0.0)))
        val mask = r.own(Mat.zeros(wide.size(), CvType.CV_32F))
        val result = r.own(MultiscaleFusion().fuse(wide, reference, mask))
        assertEquals(wide.size(), result.size())
        assertEquals(0.0, Core.norm(wide, result, Core.NORM_INF), 0.0)
    }

    @Test fun fusionChangesDetailOnlyInsideConfidenceSupport() = MatResources().use { r ->
        val reference = r.own(texture(640, 480)); val wide = r.own(Mat())
        Imgproc.GaussianBlur(reference, wide, Size(9.0, 9.0), 2.0)
        val confidence = r.own(Mat.zeros(wide.size(), CvType.CV_32F))
        Imgproc.rectangle(confidence, Point(160.0, 120.0), Point(479.0, 359.0), Scalar(1.0), -1)
        val result = r.own(MultiscaleFusion().fuse(wide, reference, confidence))
        val outside = r.own(Mat()); Imgproc.threshold(confidence, outside, .01, 255.0, Imgproc.THRESH_BINARY_INV); outside.convertTo(outside, CvType.CV_8U)
        val delta = r.own(Mat()); Core.absdiff(result, wide, delta)
        assertEquals(0.0, Core.mean(delta, outside).`val`.sum(), 0.0)
        assertTrue(Core.norm(result, wide, Core.NORM_L1) > 0)
        assertEquals(wide.size(), result.size())
        val metrics = FusionDiagnostics.measure(wide, result, confidence)
        assertEquals(0, metrics.outsideMaskChangedPixels)
        assertTrue(metrics.changedPixelPercent > 0)
    }

    @Test fun knownCentralCropAlignsWithoutExpandingCanvas() = MatResources().use { r ->
        val wide = r.own(texture(960, 720))
        val crop = r.own(wide.submat(Rect(240, 180, 480, 360)))
        val reference = r.own(Mat()); Imgproc.resize(crop, reference, Size(640.0, 480.0))
        val wideValid = r.own(Mat(wide.size(), CvType.CV_8U, Scalar(255.0)))
        val refValid = r.own(Mat(reference.size(), CvType.CV_8U, Scalar(255.0)))
        FeatureAligner().align(wide, reference, wideValid, refValid).use { alignment ->
            assertTrue(alignment.diagnostic, alignment.accepted)
            assertEquals(wide.size(), alignment.warped.size())
            assertEquals(0.0, alignment.confidence.get(10, 10)[0], 0.0)
            assertTrue(Core.countNonZero(alignment.confidence) > 0)
        }
    }

    @Test fun featurelessReferenceIsRejected() = MatResources().use { r ->
        val wide = r.own(Mat(480, 640, CvType.CV_8UC3, Scalar(90.0, 90.0, 90.0)))
        val mask = r.own(Mat(wide.size(), CvType.CV_8U, Scalar(255.0)))
        FeatureAligner().align(wide, wide, mask, mask).use { alignment ->
            assertFalse(alignment.accepted); assertEquals(0, Core.countNonZero(alignment.confidence))
        }
    }

    @Test fun completePipelineSavesAnOrientedBaselineWithoutAModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "pipeline-test-${System.nanoTime()}").apply { mkdirs() }
        val modelContext = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            MatResources().use { r ->
                val image = r.own(texture(960, 720)); val crop = r.own(image.submat(Rect(240, 180, 480, 360)))
                val ref = r.own(Mat()); Imgproc.resize(crop, ref, Size(640.0, 480.0))
                val wideFile = File(directory, "ultrawide.jpg"); val mainFile = File(directory, "main.jpg")
                assertTrue(Imgcodecs.imwrite(wideFile.absolutePath, image)); assertTrue(Imgcodecs.imwrite(mainFile.absolutePath, ref))
                val inputBytes = wideFile.readBytes()
                val calibration = LensCalibration(null, null, null, null, intArrayOf(0))
                val lens = LensChoice("0", "2", "Ultrawide", 2.0f, .3f, AndroidSize(960,720), AndroidSize(960,720), 90, calibration, intArrayOf(0), "synthetic fixture")
                val wide = CapturedFrame(wideFile, lens, 1000000, 90, null, null, 2f, 0)
                val main = CapturedFrame(mainFile, lens.copy(physicalId = "1", label = "Main 1×"), 2000000, 90, null, null, 6f, 1)
                val result = ProcessingPipeline(OnnxReferenceEnhancer(modelContext)).process(wide, main, directory) {}
                assertTrue(result.message, result.message.contains("AI unavailable"))
                assertTrue(result.before.exists()); assertTrue(File(directory, "confidence.png").exists())
                val output = r.own(Imgcodecs.imread(result.output.absolutePath)); val baseline = r.own(Imgcodecs.imread(result.baseline.absolutePath))
                assertEquals(720, output.cols()); assertEquals(960, output.rows())
                assertEquals(0.0, Core.norm(output, baseline, Core.NORM_INF), 0.0)
                assertArrayEquals(inputBytes, wideFile.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectedReferenceUsesIdenticalComparisonResize() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "resize-test-${System.nanoTime()}").apply { mkdirs() }
        val modelContext = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            MatResources().use { r ->
                val image = r.own(texture(1600, 1200))
                val reference = r.own(Mat(1200, 1600, CvType.CV_8UC3, Scalar(80.0, 80.0, 80.0)))
                val wideFile = File(directory, "ultrawide.jpg"); val mainFile = File(directory, "main.jpg")
                assertTrue(Imgcodecs.imwrite(wideFile.absolutePath, image)); assertTrue(Imgcodecs.imwrite(mainFile.absolutePath, reference))
                val calibration = LensCalibration(null, null, null, null, intArrayOf(0))
                val lens = LensChoice("0", "2", "Ultrawide", 2f, .3f, AndroidSize(1600, 1200), AndroidSize(1600, 1200), 0, calibration, intArrayOf(0), "synthetic fixture")
                val wide = CapturedFrame(wideFile, lens, 1000000, 0, null, null, 2f, 0)
                val main = CapturedFrame(mainFile, lens.copy(physicalId = "1"), 2000000, 0, null, null, 6f, 0)
                val result = ProcessingPipeline(OnnxReferenceEnhancer(modelContext)).process(wide, main, directory) {}
                assertFalse(result.referenceUsed)
                val before = r.own(Imgcodecs.imread(result.before.absolutePath)); val output = r.own(Imgcodecs.imread(result.output.absolutePath))
                assertEquals(1280, output.cols())
                assertEquals(0.0, Core.norm(before, output, Core.NORM_INF), 0.0)
                assertEquals(0.0, result.metrics!!.changedPixelPercent, 0.0)
            }
        } finally { directory.deleteRecursively() }
    }

    private fun texture(width: Int, height: Int): Mat {
        val image = Mat(height, width, CvType.CV_8UC3, Scalar(35.0, 45.0, 55.0))
        val random = Random(47)
        repeat(700) {
            val p = Point(random.nextInt(width).toDouble(), random.nextInt(height).toDouble())
            val color = Scalar(random.nextInt(256).toDouble(), random.nextInt(256).toDouble(), random.nextInt(256).toDouble())
            Imgproc.circle(image, p, random.nextInt(12) + 3, color, if (it % 2 == 0) -1 else 2)
        }
        for (y in 40 until height step 80) for (x in 30 until width step 100) Imgproc.putText(image, "$x:$y", Point(x.toDouble(), y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, .4, Scalar(255.0, 255.0, 255.0), 1)
        return image
    }
}
