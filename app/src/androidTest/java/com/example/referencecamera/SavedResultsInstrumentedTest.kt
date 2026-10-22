package com.example.referencecamera

import android.content.ContextWrapper
import android.graphics.Bitmap
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
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = root }
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
        val restarted = CaptureStore(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) { override fun getFilesDir() = root })
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

    @Test fun resultOpeningCannotEscapeCaptureStorage() = withStore { store, _ ->
        try { store.openResult("../.."); fail("Expected traversal rejection") }
        catch (_: IllegalArgumentException) { }
    }
}
