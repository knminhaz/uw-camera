package com.example.referencecamera

import android.app.Application
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.referencecamera.camera.*
import com.example.referencecamera.model.OnnxReferenceEnhancer
import com.example.referencecamera.processing.ProcessingPipeline
import com.example.referencecamera.processing.ProcessingResult
import com.example.referencecamera.storage.CaptureStore
import com.example.referencecamera.storage.SavedCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import org.json.JSONObject

enum class Stage { ULTRAWIDE, RESULT }
data class CameraUiState(
    val report: CameraReport? = null, val support: PairSupport? = null,
    val busy: Boolean = false, val capturing: Boolean = false,
    val progress: String = "Allow camera access to begin.", val error: String? = null,
    val previewStatus: String = "", val previewLensSpecific: Boolean = false, val stage: Stage = Stage.ULTRAWIDE,
    val forceSequential: Boolean = false, val result: ProcessingResult? = null,
    val sessionDirectory: File? = null, val modelInstalled: Boolean = false,
    val hasBothInputs: Boolean = false, val savedResults: List<SavedCapture> = emptyList()
) {
    val usePair get() = support?.supported == true && !forceSequential
    val previewLens get() = report?.wide
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = CameraEngine(application)
    private val enhancer = OnnxReferenceEnhancer(application)
    private val store = CaptureStore(application)
    private val pipeline = ProcessingPipeline(enhancer)
    private val mutable = MutableStateFlow(CameraUiState(modelInstalled = enhancer.modelFile.exists()))
    val state = mutable.asStateFlow()
    private var surface: Surface? = null
    private var previewJob: Job? = null
    private var previewRoute: Pair<String, Surface>? = null
    private var previewToken: Any? = null
    private var operation: Job? = null
    private var active = false
    private var wide: CapturedFrame? = null
    private var main: CapturedFrame? = null
    private var simultaneous = false

    init { refreshSavedResults() }

    private fun refreshSavedResults() = viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) { store.savedResults() }
        mutable.update { it.copy(savedResults = saved) }
    }

    fun openSavedResult(id: String) {
        if (mutable.value.busy) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, progress = "Opening saved result…") }
            cancelPreview()
            try {
                engine.stop()
                val (directory, result) = withContext(Dispatchers.IO) { store.openResult(id) }
                wide = null; main = null
                mutable.update { it.copy(stage = Stage.RESULT, sessionDirectory = directory, result = result, hasBothInputs = false, progress = "Saved result opened. Export PNG to choose a folder visible in Files.") }
            } catch (e: Exception) { if (e is CancellationException) throw e; fail(e, "Could not open saved result") }
            finally { mutable.update { it.copy(busy = false) }; startPreview() }
        }
    }

    fun initialize() {
        if (mutable.value.busy || mutable.value.report != null) { startPreview(); return }
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, progress = "Inspecting Camera2 lenses and stream combinations…") }
            try {
                val report = withContext(Dispatchers.IO) { CameraDiscovery(getApplication()).inspect() }
                val support = engine.probePair(report)
                withContext(Dispatchers.IO) {
                    File(getApplication<Application>().filesDir, "camera-report.json").writeText(report.json().put("pairProbe", support.message).toString(2))
                }
                mutable.update { it.copy(report = report, support = support, progress = "Ready. Choose a still, well-lit scene.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    runCatching { File(getApplication<Application>().filesDir, "camera-report.json").writeText(JSONObject().put("error", e.message).toString(2)) }
                }
                fail(e, "Camera discovery failed")
            }
            catch (_: OutOfMemoryError) { fail(null, "Camera inspection exceeded available memory. Close other apps and retry.") }
            finally { mutable.update { it.copy(busy = false) }; startPreview() }
        }
    }

    fun attachSurface(value: Surface) { surface = value; startPreview() }
    fun detachSurface(value: Surface) {
        if (surface !== value) return
        surface = null; cancelPreview(); viewModelScope.launch { engine.stop() }
    }
    fun setActive(value: Boolean) {
        active = value
        if (value) startPreview()
        else {
            cancelPreview()
            if (mutable.value.capturing) operation?.cancel()
            viewModelScope.launch { engine.stop() }
        }
    }
    fun sequential(value: Boolean) { mutable.update { it.copy(forceSequential = value) } }

    fun capture(displayDegrees: Int) {
        if (mutable.value.busy || mutable.value.report == null) return
        operation = viewModelScope.launch {
            val report = mutable.value.report!!
            val pair = mutable.value.usePair
            mutable.update { it.copy(busy = true, capturing = true, error = null, progress = "Settling lens exposure and focus…") }
            try {
                previewJob?.cancelAndJoin()
                previewRoute = null; previewToken = null
                val directory = mutable.value.sessionDirectory ?: withContext(Dispatchers.IO) { store.create() }
                mutable.update { it.copy(sessionDirectory = directory) }
                wide = null; main = null
                val frames = if (pair) {
                    try {
                        engine.capture(listOf(report.wide, report.main), directory, displayDegrees) { message -> mutable.update { it.copy(progress = message) } }
                            .also { simultaneous = true }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        mutable.update { it.copy(support = PairSupport(false, "Pair capture failed: ${e.message}. Automatic sequential capture is active."), forceSequential = true,
                            progress = "Pair unavailable. Keep still: taking ultrawide, then main automatically…") }
                        captureSequential(report, directory, displayDegrees)
                    }
                } else captureSequential(report, directory, displayDegrees)
                wide = frames[0]; main = frames[1]
                withContext(Dispatchers.IO) { store.metadata(directory, report, mutable.value.support, listOfNotNull(wide, main), simultaneous) }
                mutable.update { it.copy(capturing = false, hasBothInputs = wide != null && main != null) }
                processInputs(directory)
            } catch (e: CancellationException) {
                mutable.update { it.copy(progress = "Capture interrupted. Inputs already saved remain on the device.") }; throw e
            } catch (e: Exception) {
                fail(e, if (mutable.value.hasBothInputs) "Processing failed; original inputs are saved" else "Capture failed; retry with the phone still")
            } catch (_: OutOfMemoryError) { fail(null, "Not enough memory for capture. Close other camera apps and retry.") }
            finally { mutable.update { it.copy(busy = false, capturing = false) }; startPreview() }
        }
    }

    private suspend fun captureSequential(report: CameraReport, directory: File, displayDegrees: Int): List<CapturedFrame> {
        simultaneous = false
        val first = engine.capture(listOf(report.wide), directory, displayDegrees) { message -> mutable.update { it.copy(progress = "1/2 Ultrawide · $message") } }.single()
        withContext(Dispatchers.IO) { store.metadata(directory, report, mutable.value.support, listOf(first), false) }
        val second = engine.capture(listOf(report.main), directory, displayDegrees) { message -> mutable.update { it.copy(progress = "2/2 Main · $message") } }.single()
        return listOf(first, second)
    }

    fun retryProcessing() {
        val directory = mutable.value.sessionDirectory ?: return
        if (mutable.value.busy || wide == null || main == null) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { processInputs(directory) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e, "Processing failed; original inputs are saved") }
            catch (_: OutOfMemoryError) { fail(null, "Processing exceeded the memory budget. Inputs are saved; retry after closing other apps.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun processInputs(directory: File) {
        mutable.update { it.copy(capturing = false, stage = Stage.RESULT, progress = "Processing captured inputs…") }
        val result = pipeline.process(wide!!, main!!, directory) { message -> mutable.update { it.copy(progress = message) } }
        mutable.update { it.copy(result = result, progress = "Result saved locally. Compare before sharing.") }
        refreshSavedResults()
    }

    fun newCapture() {
        if (mutable.value.busy) return
        wide = null; main = null
        mutable.update { it.copy(stage = Stage.ULTRAWIDE, sessionDirectory = null, result = null, error = null, hasBothInputs = false, progress = "Ready for a new capture.") }
        startPreview()
    }

    fun retryCamera() {
        mutable.update { it.copy(error = null) }
        if (mutable.value.report == null) initialize() else startPreview()
    }

    fun export(uri: Uri, session: Boolean) = fileOperation("Exporting…") {
        val current = mutable.value
        if (session) store.exportSession(current.sessionDirectory ?: error("No session to export"), uri)
        else store.export(current.result?.output ?: error("No result to export"), uri)
    }

    fun importModel(uri: Uri) = fileOperation("Importing optional model…") {
        val target = enhancer.modelFile
        target.parentFile!!.mkdirs()
        val temp = File(target.parentFile, "import.onnx.part")
        try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 512L * 1024 * 1024) { "POC model limit is 512 MiB" }; output.write(buffer, 0, n) }
                }
            } ?: error("Cannot read model file")
            require(temp.length() > 0) { "Model file is empty" }
            check(temp.renameTo(target)) { "Cannot install model" }
            mutable.update { it.copy(modelInstalled = true) }
        } finally { temp.delete() }
    }

    private fun fileOperation(message: String, work: () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, progress = message) }
            try { withContext(Dispatchers.IO) { work() }; mutable.update { it.copy(progress = "Saved locally.") } }
            catch (e: Exception) { if (e is CancellationException) throw e; fail(e, "File operation failed") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private fun startPreview() {
        val current = mutable.value
        val lens = current.previewLens ?: return
        val target = surface ?: return
        if (!active || current.busy || current.stage == Stage.RESULT || !target.isValid) return
        val route = "${lens.logicalId}:${lens.endpoint}" to target
        if (route == previewRoute) return
        previewJob?.cancel()
        previewRoute = route
        val token = Any(); previewToken = token
        previewJob = viewModelScope.launch {
            try {
                mutable.update { it.copy(previewStatus = "Opening preview…", previewLensSpecific = false) }
                val preview = engine.preview(lens, target)
                mutable.update { it.copy(previewStatus = preview.message, previewLensSpecific = preview.lensSpecific) }
            }
            catch (e: CancellationException) { if (previewToken === token) previewRoute = null; throw e }
            catch (e: Exception) { if (previewToken === token) previewRoute = null; mutable.update { it.copy(previewStatus = "Preview unavailable", previewLensSpecific = false, error = e.message) } }
        }
    }
    private fun cancelPreview() { previewJob?.cancel(); previewRoute = null; previewToken = null }
    private fun fail(error: Throwable?, message: String) { mutable.update { it.copy(progress = message, error = listOfNotNull(message, error?.message).joinToString(": ")) } }
    override fun onCleared() { engine.close() }
}
