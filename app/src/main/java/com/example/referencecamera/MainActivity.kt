package com.example.referencecamera

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.net.Uri
import android.provider.Settings
import com.example.referencecamera.camera.LensChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ViewModelProvider(this)[CameraViewModel::class.java]
        setContent {
            val context = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(context) else darkColorScheme(primary = Color(0xFFB7D4FF))
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { CameraApp(controller, ::displayDegrees, ::share) } }
        }
    }
    override fun onStart() { super.onStart(); controller.setActive(true) }
    override fun onStop() { controller.setActive(false); super.onStop() }
    @Suppress("DEPRECATION")
    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Reference camera result", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share processed result"))
    }
}

@Composable
private fun CameraApp(controller: CameraViewModel, displayDegrees: () -> Int, share: (File) -> Unit) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    DisposableEffect(context) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted; if (granted) controller.initialize()
    }
    val exportResult = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { it?.let { uri -> controller.export(uri, false) } }
    val exportSession = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> controller.export(uri, true) } }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::importModel) }
    LaunchedEffect(permission) { if (permission) controller.initialize() }
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("REFERENCE / CAMERA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Wider view.\nA second reference.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("Pixel 7 research POC • Processing stays on this device", style = MaterialTheme.typography.bodySmall)
        }
        if (!permission) {
            InfoCard("Camera access", "Allow camera access to inspect the rear lenses, show the preview, and capture two real camera images. If permission was permanently denied, enable Camera in Android app settings.")
            Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Allow camera") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open app permission settings") }
        } else {
            state.support?.let { support ->
                InfoCard(if (state.usePair) "Same-request pair available" else "Guided sequential capture", if (state.usePair)
                    "Both physical streams configured. The two exposure timestamps will be saved; simultaneous delivery does not guarantee identical exposure timing."
                    else "Two real lenses, two moments. Keep the phone and scene still between shots. Moving subjects and nearby objects may be rejected.")
                if (support.supported && state.stage == Stage.ULTRAWIDE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.forceSequential, onCheckedChange = controller::sequential, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Use guided two-shot mode" })
                        Text("Use guided two-shot mode", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.stage != Stage.RESULT && state.previewLens != null) {
                val lens = state.previewLens!!
                val rotation = (lens.sensorOrientation - displayDegrees() + 360) % 360
                val aspect = if (rotation % 180 == 0) lens.previewSize.width.toFloat() / lens.previewSize.height else lens.previewSize.height.toFloat() / lens.previewSize.width
                Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                    key(lens.endpoint) { CameraPreview(lens, displayDegrees(), controller, Modifier.fillMaxSize()) }
                    Surface(modifier = Modifier.align(Alignment.TopStart).padding(14.dp), color = Color.Black.copy(alpha = .7f), shape = RoundedCornerShape(24.dp)) {
                        Text(if (!state.previewLensSpecific) "LOGICAL PREVIEW / SEE STATUS" else if (state.usePair) "ULTRAWIDE PREVIEW" else if (state.stage == Stage.MAIN) "02 / MAIN 1×" else "01 / ULTRAWIDE", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(state.previewStatus, Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .7f)).fillMaxWidth().padding(12.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                Text(if (state.stage == Stage.MAIN) "Keep the phone in place. Capture the main-camera reference of the same center area."
                    else "Start with a still, distant scene in good light. The output keeps this ultrawide field of view.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { controller.capture(displayDegrees()) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (state.usePair) "Capture both lenses" else if (state.stage == Stage.MAIN) "Capture main reference" else "Capture ultrawide")
                }
                if (state.stage == Stage.MAIN) TextButton(onClick = controller::newCapture, enabled = !state.busy) { Text("Start a new pair") }
            }
            state.result?.let { result ->
                Text("Before / after", style = MaterialTheme.typography.titleLarge)
                key(result.output.lastModified()) { Comparison(result.before, result.output) }
                Text("Original ultrawide → processed result", style = MaterialTheme.typography.bodySmall)
                InfoCard("Processing notes", result.message)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { share(result.output) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Share result") }
                    OutlinedButton(onClick = { exportResult.launch("reference-result.png") }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Export PNG") }
                }
                OutlinedButton(onClick = { exportSession.launch("reference-camera-session.zip") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Export inputs, result & diagnostics") }
                Button(onClick = controller::newCapture, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("New capture") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            state.error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it.substringBefore("\nCamera2 inventory:"), color = MaterialTheme.colorScheme.onErrorContainer)
                        if (!state.busy) TextButton(onClick = if (state.hasBothInputs) controller::retryProcessing else controller::retryCamera) { Text(if (state.hasBothInputs) "Retry processing" else "Retry camera") }
                    }
                }
            }
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide camera & model details" else "Camera & model details") }
            if (details) {
                InfoCard("Runtime capability report", listOfNotNull(state.report?.inventory, if (state.report == null) state.error else null, state.support?.message,
                    state.report?.let { "Concurrent logical sets: ${it.concurrentLogicalSets}. Sync type: ${it.syncType}." }).joinToString("\n\n").ifEmpty { "Camera inspection is not complete." })
                InfoCard("Optional ONNX model", if (state.modelInstalled) "A user-supplied model file is installed. Its IO contract will be checked during processing. Model presence alone does not prove compatibility."
                    else "AI unavailable. No model weights are bundled. Capture, fusion, and comparison use the deterministic OpenCV baseline.")
                OutlinedButton(onClick = { importModel.launch(arrayOf("application/octet-stream", "application/onnx", "*/*")) }, enabled = !state.busy) { Text("Import trusted ONNX model") }
                if (state.hasBothInputs && !state.busy) OutlinedButton(onClick = controller::retryProcessing) { Text("Process saved pair again") }
                state.sessionDirectory?.let { Text("Saved in app-private storage: captures/${it.name}. Export the session before uninstalling.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("No image-quality improvement has been measured. Check fine details and ghosting before using a result.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CameraPreview(lens: LensChoice, displayDegrees: Int, controller: CameraViewModel, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        TextureView(context).apply {
            var output: Surface? = null
            fun transform() {
                if (width == 0 || height == 0) return
                val rotation = (lens.sensorOrientation - displayDegrees + 360) % 360
                val bw = lens.previewSize.width.toFloat(); val bh = lens.previewSize.height.toFloat()
                val rotatedW = if (rotation % 180 == 0) bw else bh
                val rotatedH = if (rotation % 180 == 0) bh else bw
                val scale = min(width / rotatedW, height / rotatedH)
                setTransform(Matrix().apply {
                    setScale(bw / width, bh / height, width / 2f, height / 2f)
                    postRotate(rotation.toFloat(), width / 2f, height / 2f)
                    postScale(scale, scale, width / 2f, height / 2f)
                })
            }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                    texture.setDefaultBufferSize(lens.previewSize.width, lens.previewSize.height); transform()
                    output = Surface(texture).also(controller::attachSurface)
                }
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = transform()
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                    output?.let { controller.detachSurface(it); it.release() }; output = null; return true
                }
            }
        }
    })
}

@Composable
private fun Comparison(beforeFile: File, afterFile: File) {
    var images by remember(beforeFile, afterFile) { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    var loadFailed by remember(beforeFile, afterFile) { mutableStateOf(false) }
    LaunchedEffect(beforeFile, afterFile) {
        try {
            images = withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                val before = BitmapFactory.decodeFile(beforeFile.absolutePath, options)
                val after = BitmapFactory.decodeFile(afterFile.absolutePath, options)
                if (before == null || after == null) null else before.asImageBitmap() to after.asImageBitmap()
            }
            loadFailed = images == null
        } catch (_: OutOfMemoryError) { loadFailed = true }
    }
    var fraction by remember { mutableFloatStateOf(.5f) }
    val pair = images
    if (pair == null) { Text(if (loadFailed) "Comparison preview unavailable. The saved result can still be exported." else "Loading local comparison…"); return }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(pair.first.width.toFloat() / pair.first.height).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
            Image(pair.first, "Original ultrawide", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Image(pair.second, "Processed result", Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() } }, contentScale = ContentScale.Fit)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .7f)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RESULT", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text("ORIGINAL", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        Slider(value = fraction, onValueChange = { fraction = it }, modifier = Modifier.semantics { contentDescription = "Before and after comparison: processed fraction" })
    }
}
