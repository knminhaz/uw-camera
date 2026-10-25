package com.example.referencecamera

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.referencecamera.storage.CaptureStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SavedResultsInstrumentedTest {
    private fun withStore(work: (CaptureStore, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "result-store-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = root; override fun getCacheDir() = File(root, "cache").apply { mkdirs() } }
        try { work(CaptureStore(isolated), root) } finally { root.deleteRecursively() }
    }

    private fun complete(root: File, id: String): File = File(root, "captures/$id").apply {
        mkdirs()
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        try { listOf("result.png", "baseline.png", "comparison-original.png").forEach { name ->
            File(this, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } } finally { bitmap.recycle() }
        File(this, "processing.json").writeText("{\"notes\":\"OpenCV baseline fixture\"}")
    }

    @Test fun completedResultsReopenAfterAStoreIsRecreated() = withStore { store, root ->
        val directory = complete(root, "200-abcdef")
        val restarted = CaptureStore(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) { override fun getFilesDir() = root; override fun getCacheDir() = File(root, "cache").apply { mkdirs() } })
        assertEquals("200-abcdef", restarted.savedResults().single().id)
        val (loaded, result) = restarted.openResult(store.savedResults().single().id)
        assertEquals(directory.canonicalFile, loaded)
        assertTrue(result.output.isFile); assertEquals("OpenCV baseline fixture", result.message)
    }

    @Test fun partialCapturesStayOutOfResultsAndNewestAppearsFirst() = withStore { store, root ->
        complete(root, "100-abcdef"); complete(root, "300-abcdef")
        File(root, "captures/400-abcdef").apply { mkdirs(); File(this, "ultrawide.jpg").writeBytes(byteArrayOf(1)) }
        assertEquals(listOf("300-abcdef", "100-abcdef"), store.savedResults().map { it.id })
    }

    @Test fun standaloneAndLegacyAiStagesReopenWithoutLosingTheBaseline() = withStore { store, root ->
        val directory = complete(root, "200-abcdef").canonicalFile
        File(directory, "processing.json").writeText("{\"aiApplied\":true}")
        val standalone = File(directory, "onnx-result.png").apply { writeBytes(File(directory, "result.png").readBytes()) }
        val proposal = File(directory, "onnx-proposal.png").apply { writeBytes(standalone.readBytes()) }
        val (_, result) = store.openResult(directory.name)
        assertEquals(proposal, result.aiProposal)
        assertEquals(proposal, result.exportImage(proposal.absolutePath))
        assertTrue(result.aiApplied)
        assertEquals(standalone, result.aiOutput)
        assertEquals(result.baseline, result.comparisonImage(false))
        assertEquals(standalone, result.comparisonImage(true))
        assertTrue(standalone.delete()); assertTrue(proposal.delete())
        val (_, legacy) = store.openResult(directory.name)
        assertTrue(legacy.aiApplied); assertNull(legacy.aiProposal)
        assertEquals(legacy.output, legacy.aiOutput)
        assertEquals(legacy.output, legacy.comparisonImage(true))
    }

    @Test fun staleModelFileIsNotOfferedWhenMetadataSaysNoAi() = withStore { store, root ->
        val directory = complete(root, "200-abcdef").canonicalFile
        File(directory, "onnx-result.png").writeBytes(File(directory, "result.png").readBytes())
        val (_, result) = store.openResult(directory.name)
        assertFalse(result.aiApplied)
        assertNull(result.aiOutput)
        assertEquals(result.baseline, result.comparisonImage(true))
    }

    @Test fun stageExportCopiesSelectedBaselineAndRejectsOtherFiles() = withStore { store, root ->
        val directory = complete(root, "200-abcdef").canonicalFile
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xffff0000.toInt())
            File(directory, "baseline.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val (_, result) = store.openResult(directory.name)
        val destination = File(root, "exported-baseline.png")
        store.export(result.exportImage(result.baseline.absolutePath), Uri.fromFile(destination))
        assertArrayEquals(result.baseline.readBytes(), destination.readBytes())
        assertFalse(result.output.readBytes().contentEquals(destination.readBytes()))
        try { result.exportImage(File(root, "unrelated.png").absolutePath); fail("Expected unknown-stage rejection") }
        catch (_: IllegalStateException) { }
    }

    @Test fun missingBaselineStillAllowsViewingTheSavedFinal() = withStore { store, root ->
        val directory = complete(root, "200-abcdef")
        assertTrue(File(directory, "baseline.png").delete())
        val (_, result) = store.openResult(directory.name)
        assertEquals(result.output, result.comparisonImage(false))
    }

    @Test fun resultOpeningCannotEscapeCaptureStorage() = withStore { store, _ ->
        try { store.openResult("../.."); fail("Expected traversal rejection") }
        catch (_: IllegalArgumentException) { }
    }
}
