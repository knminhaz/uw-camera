package com.example.referencecamera

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.referencecamera.bridge.LaptopBridge
import com.example.referencecamera.storage.CaptureStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class LaptopBridgeInstrumentedTest {
    private fun isolated(work: (Context, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "bridge-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try { work(context, root) } finally { root.deleteRecursively() }
    }
    private fun heartbeat(context: Context, bridge: LaptopBridge, age: Long = 0, ready: Boolean = true, instance: String = bridge.instanceId) {
        LaptopBridge.atomicWrite(File(context.filesDir,"bridge/heartbeat.json"), JSONObject()
            .put("protocol",1).put("appInstanceId",instance).put("companionId","test-companion")
            .put("ready",ready).put("echoElapsedMs",SystemClock.elapsedRealtime()-age))
    }
    @Test fun staleRestartedOrPausedCompanionIsNotReady() = isolated { context,_ ->
        val bridge=LaptopBridge(context)
        assertFalse(bridge.connection().ready)
        heartbeat(context,bridge); assertTrue(bridge.connection().ready)
        heartbeat(context,bridge,age=9000); assertFalse(bridge.connection().ready)
        heartbeat(context,bridge,instance="old-process"); assertFalse(bridge.connection().ready)
        heartbeat(context,bridge,ready=false); assertFalse(bridge.connection().ready)
    }
    @Test fun transferNeedsExplicitReadyConfirmationAndMatchingAck() = isolated { context,_ ->
        val store=CaptureStore(context); val session=store.create(); File(session,"result.png").writeBytes(byteArrayOf(1,2,3))
        val bridge=LaptopBridge(context); bridge.publish(JSONObject().put("phase","preview"))
        assertFalse(bridge.hasRequest()); assertFalse(File(context.filesDir,"bridge/request.json").exists())
        assertTrue(runCatching { bridge.prepareTransfer(session,store.relativeDirectory(session)) }.isFailure)
        heartbeat(context,bridge)
        val request=bridge.prepareTransfer(session,store.relativeDirectory(session))
        assertTrue(bridge.hasRequest())
        val manifest=JSONObject(request.getString("manifestJson"))
        assertEquals(1,manifest.getJSONArray("files").length())
        assertEquals(3,manifest.getJSONArray("files").getJSONObject(0).getInt("bytes"))
        val ack=JSONObject(request.toString()).put("success",true).put("fileCount",1).put("archiveId","test-archive")
        LaptopBridge.atomicWrite(File(context.filesDir,"bridge/ack.json"),ack)
        assertEquals("test-archive",runBlocking { bridge.awaitAcknowledgement(request) }.getString("archiveId"))
        assertTrue(session.exists()) // Acknowledgement itself must never delete an image.
        bridge.clearRequest(); assertFalse(bridge.hasRequest())
    }
    @Test fun failedAcknowledgementPreservesPreview() = isolated { context,_ ->
        val store=CaptureStore(context); val session=store.create(); File(session,"result.png").writeText("fixture")
        val bridge=LaptopBridge(context); heartbeat(context,bridge)
        val request=bridge.prepareTransfer(session,store.relativeDirectory(session))
        LaptopBridge.atomicWrite(File(context.filesDir,"bridge/ack.json"), JSONObject(request.toString()).put("success",false).put("error","Checksum failed"))
        val failure=runCatching { runBlocking { bridge.awaitAcknowledgement(request) } }
        assertTrue(failure.isFailure); assertTrue(session.exists())
    }
    @Test fun temporaryDiscardNeverDeletesLegacyCaptures() = isolated { context,_ ->
        val store=CaptureStore(context); val temporary=store.create()
        val legacy=File(context.filesDir,"captures/1792913430268-abcdef").apply { mkdirs(); File(this,"result.png").writeText("keep") }
        assertFalse(store.discardTemporary(legacy)); assertTrue(legacy.exists())
        assertTrue(store.discardTemporary(temporary)); assertFalse(temporary.exists()); assertTrue(legacy.exists())
        assertTrue(runCatching { store.relativeDirectory(context.filesDir) }.isFailure)
    }

    /** Opt-in real USB test. Uses generated pixels only; never opens the camera. */
    @Test fun explicitSyntheticUsbArchiveRoundTrip() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usbArchive") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=CaptureStore(context); val session=store.create(); val bridge=LaptopBridge(context)
        val running=AtomicBoolean(true)
        val publisher=Thread {
            while(running.get()) {
                bridge.publish(JSONObject().put("phase",if(bridge.hasRequest()) "transferring" else "preview")
                    .put("sessionId",session.name).put("progress","Synthetic USB verification; no camera photo"))
                Thread.sleep(400)
            }
        }
        try {
            val bitmap=Bitmap.createBitmap(480,320,Bitmap.Config.ARGB_8888)
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width)
                bitmap.setPixel(x,y,Color.rgb(x*255/480,y*255/320,if((x/32+y/32)%2==0) 80 else 160))
            listOf("comparison-original.png","prepared-ultrawide.png","prepared-main.png","aligned-reference.png","baseline.png","result.png").forEach { name ->
                File(session,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            bitmap.eraseColor(Color.WHITE)
            File(session,"confidence.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            File(session,"capture.json").writeText(JSONObject().put("captureMode","synthetic USB validation").put("syntheticFixture",true).toString())
            File(session,"processing.json").writeText(JSONObject().put("aiApplied",false).put("alignmentAccepted",false)
                .put("notes","Generated test chart for transfer validation. This is not a captured photo or an image-quality benchmark.").toString())
            publisher.start()
            val deadline=SystemClock.elapsedRealtime()+30_000
            while(!bridge.connection().ready && SystemClock.elapsedRealtime()<deadline) Thread.sleep(250)
            assertTrue("Start the laptop companion first",bridge.connection().ready)
            Thread.sleep(2000)
            assertFalse(bridge.hasRequest()); assertFalse(File(context.filesDir,"bridge/ack.json").exists())
            val request=bridge.prepareTransfer(session,store.relativeDirectory(session))
            val ack=runBlocking { bridge.awaitAcknowledgement(request) }
            assertEquals(9,ack.getInt("fileCount")); assertTrue(ack.getBoolean("success"))
            assertTrue(store.discardTemporary(session)); assertFalse(session.exists())
        } finally {
            running.set(false); if(publisher.isAlive) publisher.join(3000)
            bridge.clearRequest(); if(session.exists()) store.discardTemporary(session)
        }
    }
}
