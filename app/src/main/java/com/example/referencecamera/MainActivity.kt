package com.example.referencecamera

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.example.referencecamera.camera.LensChoice
import com.example.referencecamera.ui.previewGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraViewModel
    private var rotationDegrees by mutableIntStateOf(0)
    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) { rotationDegrees = readDisplayDegrees() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ViewModelProvider(this)[CameraViewModel::class.java]
        setContent {
            val context = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(context) else darkColorScheme(primary = Color(0xFFB7D4FF))
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { CameraApp(controller, { rotationDegrees }, ::share) } }
        }
    }
    override fun onStart() {
        super.onStart(); rotationDegrees = readDisplayDegrees()
        getSystemService(DisplayManager::class.java).registerDisplayListener(rotationListener, Handler(Looper.getMainLooper()))
        controller.setActive(true)
    }
    override fun onStop() { getSystemService(DisplayManager::class.java).unregisterDisplayListener(rotationListener); controller.setActive(false); super.onStop() }
    @Suppress("DEPRECATION")
    private fun readDisplayDegrees(): Int = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Reference camera result", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share processed result"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val exportResult = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { it?.let { uri -> controller.export(uri, false) } }
    val exportSession = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> controller.export(uri, true) } }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::importModel) }
    LaunchedEffect(permission) { if (permission) controller.initialize() }
    var showResults by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showResults) AlertDialog(onDismissRequest = { showResults = false }, title = { Text("Saved results") }, text = {
        if (state.savedResults.isEmpty()) Text("No completed results yet. Press the shutter once to capture both lenses. OpenCV processing starts automatically; no ONNX model is needed.")
        else LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.savedResults, key = { it.id }) { saved ->
                TextButton(onClick = { showResults = false; controller.openSavedResult(saved.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(saved.createdAt)))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showResults = false }) { Text("Close") } })

    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Camera settings", style = MaterialTheme.typography.headlineSmall)
            InfoCard("One shutter, two real lenses", "The app captures ultrawide and main images, then runs OpenCV alignment and fusion automatically. If the pair request fails, it takes the two shots sequentially. Keep the phone and scene still.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sequential capture", style = MaterialTheme.typography.titleSmall)
                    Text("One tap takes ultrawide, then main.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.forceSequential || state.support?.supported != true, onCheckedChange = controller::sequential,
                    enabled = !state.busy && state.support?.supported == true, modifier = Modifier.semantics { contentDescription = "Use automatic sequential capture" })
            }
            InfoCard("Optional AI model", if (state.modelInstalled) "A model file is installed. Its input/output contract is checked during processing; incompatible models leave the OpenCV baseline available."
                else "OpenCV processing works without a model. Import is only for a trusted reference-SR ONNX model matching the documented contract. Arbitrary .onnx files will not work.")
            OutlinedButton(onClick = { showSettings = false; controller.retryDual() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Retry dual-camera detection") }
            OutlinedButton(onClick = { importModel.launch(arrayOf("application/octet-stream", "application/onnx", "*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Advanced: import ONNX model (optional)") }
            InfoCard("Where results are saved", "Completed comparisons are in Results at the bottom left of the camera screen. Export PNG saves a copy to a folder you choose in Files. App-private originals do not appear in Photos automatically.")
            state.error?.let { InfoCard("Last error", it) }
            InfoCard("Camera diagnostics", listOfNotNull(state.support?.message, state.report?.let { "Ultrawide: ${it.wide.route}\nMain: ${it.main.route}\nSynchronization: ${it.syncType}\n\n${it.inventory}" }).joinToString("\n\n").ifEmpty { "Camera inspection is not complete." })
            Text("Research POC. Output is capped at 1280 pixels on its longest edge. No image-quality improvement has been measured.", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (state.stage == Stage.RESULT) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = controller::newCapture, enabled = !state.busy) { Icon(Icons.Outlined.ArrowBack, "Back to camera") }
                Text("Result", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showResults = true }, enabled = !state.busy) { Text("Saved (${state.savedResults.size})") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress, style = MaterialTheme.typography.bodyMedium)
            state.error?.let { CaptureError(state, controller) }
            state.result?.let { result ->
                Text(when { result.aiApplied -> "ONNX + OpenCV"; result.referenceUsed -> "OpenCV reference fusion"; else -> "Ultrawide retained • reference not reliable" }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                key(result.output.absolutePath, result.output.lastModified()) { Comparison(result.before, result.output) }
                result.metrics?.let { metrics ->
                    Text(String.format(java.util.Locale.US, "Reference area %.1f%% · changed pixels %.1f%% (≥2/255)", metrics.referenceAreaPercent, metrics.changedPixelPercent), style = MaterialTheme.typography.bodySmall)
                    Text("Pixel changes measure activity, not improved quality. These numbers describe the OpenCV baseline.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Move the slider to compare. Zoom and drag to inspect detail. Saved in Results.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { share(result.output) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Share") }
                    FilledTonalButton(onClick = { exportResult.launch("reference-result.png") }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Save to Files") }
                }
                var notes by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { notes = !notes }) { Text(if (notes) "Hide processing details" else "Processing details") }
                if (notes) InfoCard("Processing notes", result.message)
                OutlinedButton(onClick = { exportSession.launch("reference-camera-session.zip") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Export inputs & diagnostics") }
                Button(onClick = controller::newCapture, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Back to camera") }
            }
            if (!state.busy && state.result == null) OutlinedButton(onClick = controller::newCapture) { Text("Back to camera") }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("REFERENCE CAMERA", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Text(if (state.usePair) "DUAL REQUEST" else "SEQUENTIAL", color = Color(0xFFFFD66E), style = MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            val lens = state.previewLens
            when {
                !permission -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Allow camera access to begin.", color = Color.White)
                    Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("App permission settings") }
                }
                lens != null -> {
                    key(lens.endpoint, displayDegrees()) { CameraPreview(lens, displayDegrees(), controller, Modifier.fillMaxSize()) }
                    Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color.Black.copy(alpha = .72f), shape = RoundedCornerShape(24.dp)) {
                        Text(if (state.previewLensSpecific) "ULTRAWIDE + 1×" else "PREVIEW SOURCE · SEE SETTINGS", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                state.busy -> CircularProgressIndicator()
                else -> Text("Camera unavailable. See the error below.", Modifier.padding(24.dp), color = Color.White)
            }
            if (state.busy && lens != null) Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .8f)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(state.progress, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        state.error?.let { CaptureError(state, controller, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PHOTO", color = Color(0xFFFFD66E), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { showResults = true }, enabled = !state.busy, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.PhotoLibrary, "Open saved results", tint = Color.White, modifier = Modifier.size(28.dp)) }
                    Text("Results (${state.savedResults.size})", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                Box(Modifier.size(92.dp).border(3.dp, if (state.busy) Color.Gray else Color.White, CircleShape).padding(6.dp)) {
                    FilledIconButton(onClick = { controller.capture(displayDegrees()) }, enabled = permission && state.report != null && !state.busy,
                        modifier = Modifier.fillMaxSize(), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black, disabledContainerColor = Color.DarkGray)) {
                        Icon(Icons.Outlined.CameraAlt, "Capture both lenses and process", Modifier.size(32.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { showSettings = true }, enabled = !state.busy, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.Settings, "Camera settings and optional model", tint = Color.White, modifier = Modifier.size(28.dp)) }
                    Text("Settings", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(if (state.usePair) "One tap captures both lenses" else "One tap takes two shots • keep still", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Text(if (state.modelInstalled) "OpenCV + optional ONNX" else "OpenCV processing • no model required", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CaptureError(state: CameraUiState, controller: CameraViewModel, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(state.error.orEmpty().substringBefore("\nCamera2 inventory:"), style = MaterialTheme.typography.bodySmall, maxLines = 5)
            if (!state.busy) TextButton(onClick = if (state.hasBothInputs) controller::retryProcessing else controller::retryCamera) { Text(if (state.hasBothInputs) "Retry processing" else "Retry camera") }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
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
                val geometry = previewGeometry(lens.previewSize.width, lens.previewSize.height, lens.sensorOrientation, displayDegrees, width, height)
                setTransform(Matrix().apply {
                    setScale(geometry.unscaleX, geometry.unscaleY, width / 2f, height / 2f)
                    postRotate(geometry.displayRotation, width / 2f, height / 2f)
                    postScale(geometry.fitScale, geometry.fitScale, width / 2f, height / 2f)
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
    var mask by remember(beforeFile, afterFile) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(beforeFile, afterFile) { mutableStateOf(false) }
    LaunchedEffect(beforeFile, afterFile) {
        try {
            images = withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                val before = BitmapFactory.decodeFile(beforeFile.absolutePath, options)
                val after = BitmapFactory.decodeFile(afterFile.absolutePath, options)
                if (before == null || after == null) null else before.asImageBitmap() to after.asImageBitmap()
            }
            loadFailed = images == null
        } catch (_: OutOfMemoryError) { loadFailed = true }
        // Optional diagnostics must not prevent viewing/exporting the comparison.
        try {
            mask = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(File(afterFile.parentFile, "confidence.png").absolutePath)?.let { source ->
                    try {
                        val pixels = IntArray(source.width * source.height)
                        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                        for (i in pixels.indices) pixels[i] = ((((pixels[i] ushr 16) and 255) / 2) shl 24) or 0x0000FFD0
                        Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                    } finally { source.recycle() }
                }
            }
        } catch (_: OutOfMemoryError) { mask = null }
    }
    var fraction by remember { mutableFloatStateOf(.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var showMask by remember { mutableStateOf(false) }
    val pair = images
    if (pair == null) { Text(if (loadFailed) "Comparison preview unavailable. The saved result can still be exported." else "Loading local comparison…"); return }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(1f, 2f, 4f).forEach { scale ->
                FilterChip(selected = zoom == scale, onClick = { zoom = scale; pan = Offset.Zero }, label = { Text(if (scale == 1f) "Fit" else "${scale.toInt()}×") })
            }
            if (mask != null) FilterChip(selected = showMask, onClick = { showMask = !showMask }, label = { Text("Mask") })
        }
        if (showMask) Text("Cyan = reference confidence, not a quality score.", style = MaterialTheme.typography.bodySmall)
        val transform = Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
        }
        Box(Modifier.fillMaxWidth().aspectRatio(pair.first.width.toFloat() / pair.first.height).onSizeChanged { viewport = it }
            .clip(RoundedCornerShape(18.dp)).background(Color.Black)
            .pointerInput(zoom) {
                if (zoom > 1f) detectDragGestures { change, delta ->
                    change.consume()
                    val limitX = viewport.width * (zoom - 1) / 2
                    val limitY = viewport.height * (zoom - 1) / 2
                    pan = Offset((pan.x + delta.x).coerceIn(-limitX, limitX), (pan.y + delta.y).coerceIn(-limitY, limitY))
                }
            }
            .drawWithContent { drawContent(); drawLine(Color.White, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 2.dp.toPx()) }) {
            Image(pair.first, "Original ultrawide", transform, contentScale = ContentScale.Fit)
            Box(Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() } }) {
                Image(pair.second, "Processed result", transform, contentScale = ContentScale.Fit)
            }
            if (showMask) mask?.let { Image(it, "Reference confidence overlay", transform, contentScale = ContentScale.Fit) }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .7f)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RESULT", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text("ORIGINAL", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        Slider(value = fraction, onValueChange = { fraction = it }, modifier = Modifier.semantics { contentDescription = "Before and after comparison: processed fraction" })
    }
}
