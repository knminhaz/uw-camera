package com.example.referencecamera

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.referencecamera.model.OnnxReferenceEnhancer
import com.example.referencecamera.processing.MatResources
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnnxEnhancerInstrumentedTest {
    @Before fun loadOpenCv() { assertTrue(OpenCVLoader.initLocal()); Core.setNumThreads(1) }

    private fun isolated(work: (Context, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "onnx-test-" + System.nanoTime()).apply { mkdirs() }
        val wrapper = object : ContextWrapper(context) { override fun getFilesDir() = root }
        try { work(wrapper, root) } finally { root.deleteRecursively() }
    }

    private fun installFixture(enhancer: OnnxReferenceEnhancer, name: String) {
        enhancer.modelFile.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("models/" + name).use { input ->
            enhancer.modelFile.outputStream().use { input.copyTo(it) }
        }
    }

    @Test fun bundledWeightsExecuteAtOddDimensionsAndPreserveZeroConfidenceExactly() = isolated { context, root ->
        MatResources().use { r ->
            val target = r.own(Mat(49, 65, CvType.CV_8UC3))
            val pixels = ByteArray(49 * 65 * 3) { ((it * 29 + it / 17) % 256).toByte() }; target.put(0, 0, pixels)
            val before = target.clone()
            try {
                val confidence = r.own(Mat.zeros(target.size(), CvType.CV_32FC1))
                Imgproc.rectangle(confidence, Point(12.0, 9.0), Point(52.0, 39.0), Scalar(.6), -1)
                val enhancer = OnnxReferenceEnhancer(context)
                assertTrue(enhancer.isModelAvailable)
                val updates = mutableListOf<String>()
                val result = enhancer.enhance(target, target, confidence) { updates += it }
                val output = result.image ?: error(result.message)
                val proposal = result.proposal ?: error("Missing model proposal")
                try {
                    assertEquals(target.size(), output.size()); assertEquals(target.size(), proposal.size())
                    assertEquals(OnnxReferenceEnhancer.MODEL_ID, result.modelId)
                    assertTrue(result.tilesProcessed > 0)
                    assertTrue(updates.any { it.contains("tile") })
                    val zero = r.own(Mat()); Imgproc.threshold(confidence, zero, 0.0, 255.0, Imgproc.THRESH_BINARY_INV)
                    zero.convertTo(zero, CvType.CV_8U)
                    assertEquals(0.0, Core.norm(target, output, Core.NORM_INF, zero), 0.0)
                    assertEquals(0.0, Core.norm(before, target, Core.NORM_INF), 0.0)
                    val manifest = context.assets.open("models/model.json").bufferedReader().use { JSONObject(it.readText()) }
                    assertEquals(manifest.getString("sha256"), result.modelSha256)
                    assertEquals(manifest.getString("sha256"), OnnxReferenceEnhancer.sha256(File(root, "models/" + OnnxReferenceEnhancer.BUNDLED_NAME)))
                } finally { output.release(); proposal.release() }
            } finally { before.release() }
        }
    }

    @Test fun importedReferenceContractStillExecutesAndCanBeValidated() = isolated { context, _ ->
        val enhancer = OnnxReferenceEnhancer(context, includeBundled = false)
        installFixture(enhancer, "reference-fixture.onnx")
        assertTrue(enhancer.validateModel(enhancer.modelFile).contains("Reference"))
        MatResources().use { r ->
            val target = r.own(Mat(17, 23, CvType.CV_8UC3, Scalar(40.0, 60.0, 80.0)))
            val mask = r.own(Mat(target.size(), CvType.CV_32FC1, Scalar(1.0)))
            val result = enhancer.enhance(target, target, mask)
            val image = result.image ?: error(result.message)
            try {
                assertEquals("imported-reference-model", result.modelId)
                assertTrue(Core.norm(target, image, Core.NORM_L1) > 0)
            } finally { image.release(); result.proposal?.release() }
        }
    }

    @Test fun nonFiniteModelOutputFallsBackWithoutChangingTheInput() = isolated { context, _ ->
        val enhancer = OnnxReferenceEnhancer(context, includeBundled = false)
        installFixture(enhancer, "nonfinite-fixture.onnx")
        MatResources().use { r ->
            val target = r.own(Mat(13, 19, CvType.CV_8UC3, Scalar(50.0, 70.0, 90.0)))
            val original = r.own(target.clone())
            val mask = r.own(Mat(target.size(), CvType.CV_32FC1, Scalar(1.0)))
            val result = enhancer.enhance(target, target, mask)
            assertNull(result.image); assertNull(result.proposal)
            assertTrue(result.message.contains("non-finite"))
            assertEquals(0.0, Core.norm(original, target, Core.NORM_INF), 0.0)
        }
    }

    @Test fun disablingAiDoesNotProvisionOrRunTheBundledModel() = isolated { context, root ->
        val enhancer = OnnxReferenceEnhancer(context).apply { enabled = false }
        MatResources().use { r ->
            val target = r.own(Mat(9, 11, CvType.CV_8UC3, Scalar(10.0, 20.0, 30.0)))
            val mask = r.own(Mat(target.size(), CvType.CV_32FC1, Scalar(1.0)))
            val result = enhancer.enhance(target, target, mask)
            assertNull(result.image); assertTrue(result.message.contains("disabled"))
            assertFalse(File(root, "models/" + OnnxReferenceEnhancer.BUNDLED_NAME).exists())
        }
    }

    @Test fun cancellationIsPropagatedInsteadOfBecomingABaselineResult() = isolated { context, _ ->
        MatResources().use { r ->
            val target = r.own(Mat(9, 11, CvType.CV_8UC3, Scalar(20.0, 40.0, 60.0)))
            val mask = r.own(Mat(target.size(), CvType.CV_32FC1, Scalar(1.0)))
            try {
                OnnxReferenceEnhancer(context).enhance(target, target, mask) { throw CancellationException("Fixture cancellation") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
        }
    }
}