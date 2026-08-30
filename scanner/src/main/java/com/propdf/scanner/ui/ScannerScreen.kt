package com.propdf.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.scanner.engine.ColorMode
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onPdfCreated: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capturedPages by viewModel.capturedPages.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    var showFilterMenu by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showGalleryPicker by remember { mutableStateOf(false) }
    var showCornerAdjust by remember { mutableStateOf<List<PointF>?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    val importState by viewModel.importState.collectAsStateWithLifecycle()

    // Previously ActivityResultContracts.GetContent() -- returns exactly one Uri, so
    // only one photo could ever be imported per tap, and decoding happened at full
    // resolution synchronously right here on the composition/main thread. Now uses
    // GetMultipleContents() for real multi-select, and all decoding/processing happens
    // inside ScannerViewModel.importFromGallery() off the main thread with bounded
    // downsampling -- see that function for why.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importFromGallery(uris)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Auto-navigate when PDF is created. The generated Uri is passed through
    // unchanged to the viewer. lastOutputUri is cleared immediately after
    // being consumed so that returning to this screen (ScannerViewModel may
    // be scoped beyond a single visit) doesn't leave a stale completed-export
    // Uri sitting in state.
    LaunchedEffect(uiState.lastOutputUri) {
        uiState.lastOutputUri?.let { uri ->
            onPdfCreated(uri.toString())
            viewModel.consumeLastOutputUri()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document Scanner") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (capturedPages.isNotEmpty()) {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.Save, contentDescription = "Export")
                        }
                    }
                    IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (capturedPages.isEmpty() || currentPage == null) {
                // Camera view
                if (hasCameraPermission) {
                    // Holds the capture trigger exposed by CameraPreview once
                    // CameraX has bound ImageCapture, so the shutter button
                    // below can actually fire a capture instead of being a
                    // decorative no-op.
                    var triggerCapture by remember { mutableStateOf<(() -> Unit)?>(null) }

                    val liveEdgeState by viewModel.liveEdgeState.collectAsStateWithLifecycle()
                    val autoCaptureEnabled by viewModel.autoCaptureEnabled.collectAsStateWithLifecycle()
                    val autoCaptureTrigger by viewModel.autoCaptureTrigger.collectAsStateWithLifecycle()

                    CameraPreview(
                        onCapture = { bitmap ->
                            viewModel.captureDocument(bitmap)
                            viewModel.resetLiveDetection()
                        },
                        executor = cameraExecutor,
                        onCaptureReady = { trigger -> triggerCapture = trigger },
                        onFrameAnalyzed = { bitmap, w, h -> viewModel.onAnalyzedFrame(bitmap, w, h) },
                        onError = { message -> viewModel.reportError(message) }
                    )

                    // Auto-capture: fires the same shutter path a manual tap would,
                    // once the live detector has seen a stable, confident document
                    // outline for several consecutive frames (see
                    // ScannerViewModel.onAnalyzedFrame). autoCaptureTrigger is a
                    // monotonically increasing counter rather than a one-shot event so
                    // this LaunchedEffect fires reliably on every new detection,
                    // including back-to-back pages in multi-page mode.
                    LaunchedEffect(autoCaptureTrigger) {
                        if (autoCaptureTrigger > 0) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            triggerCapture?.invoke()
                        }
                    }

                    // Capture button overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 32.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(
                                onClick = { triggerCapture?.invoke() },
                                modifier = Modifier.size(72.dp)
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = "Capture",
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // Auto Capture toggle
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 16.dp, end = 16.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        FilterChip(
                            selected = autoCaptureEnabled,
                            onClick = { viewModel.setAutoCaptureEnabled(!autoCaptureEnabled) },
                            label = { Text("Auto Capture") },
                            leadingIcon = {
                                Icon(
                                    if (autoCaptureEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }

                    // Live document-boundary overlay: dashed guide while searching,
                    // solid quad tracking the actual detected corners once a document
                    // is found (yellow while still settling, green once stable enough
                    // that auto-capture -- if enabled -- is about to fire).
                    LiveEdgeOverlay(state = liveEdgeState)

                    // OpenCV native processing can be unavailable on a given device
                    // (see OpenCvAvailability) -- when it is, auto-detection silently
                    // never finds a document, so this makes that explicit rather than
                    // leaving the user staring at a guide box that will never light
                    // up. Manual capture (the shutter button above) doesn't depend on
                    // OpenCV at all and keeps working exactly as before.
                    if (liveEdgeState is ScannerViewModel.LiveEdgeState.AutoDetectionUnavailable) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            ) {
                                Text(
                                    "Automatic detection unavailable — manual capture enabled",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    PermissionRationale(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                }
            } else {
                // Review captured pages
                Column(modifier = Modifier.fillMaxSize()) {
                    // Current page preview
                    currentPage?.let { page ->
                        Image(
                            bitmap = page.bitmap.asImageBitmap(),
                            contentDescription = "Scanned page",
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(16.dp)
                        )
                    }

                    // Filter controls. Previously only exposed 3 of the 8 ColorMode
                    // values the engine actually implements (Original/Grayscale/Magic),
                    // and the "B&W" chip was mislabeled -- it called GRAYSCALE, not the
                    // separate BLACK_WHITE mode, so true black & white was unreachable
                    // from the UI. `selected` was also hardcoded false on every chip, so
                    // there was no indication of which filter was actually active.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = currentPage?.appliedColorMode == ColorMode.ORIGINAL,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.ORIGINAL) },
                            label = { Text("Original") }
                        )
                        FilterChip(
                            selected = currentPage?.appliedColorMode == ColorMode.GRAYSCALE,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.GRAYSCALE) },
                            label = { Text("Grayscale") }
                        )
                        FilterChip(
                            selected = currentPage?.appliedColorMode == ColorMode.BLACK_WHITE,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.BLACK_WHITE) },
                            label = { Text("B&W") }
                        )
                        FilterChip(
                            selected = currentPage?.appliedColorMode == ColorMode.HIGH_CONTRAST,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.HIGH_CONTRAST) },
                            label = { Text("High Contrast") }
                        )
                        FilterChip(
                            selected = currentPage?.appliedColorMode == ColorMode.MAGIC_COLOR,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.MAGIC_COLOR) },
                            label = { Text("Magic") }
                        )
                        // Automatic edge detection can be imperfect (a shadow, a curled
                        // page edge, low contrast against the background) -- this opens
                        // CornerAdjustDialog so the user can manually correct the quad
                        // and re-run perspective correction instead of being stuck with
                        // whatever the automatic pass produced.
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    val corners = viewModel.detectCornersForCurrent()
                                    if (corners.size == 4) showCornerAdjust = corners
                                }
                            },
                            label = { Text("Adjust Corners") },
                            leadingIcon = { Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }

                    // Thumbnail strip
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(capturedPages, key = { it.meta.index }) { page ->
                            val isSelected = currentPage?.meta?.index == page.meta.index
                            Surface(
                                onClick = { viewModel.selectPage(page.meta.index) },
                                shape = MaterialTheme.shapes.small,
                                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Image(
                                    bitmap = page.bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp, 100.dp)
                                )
                            }
                        }
                    }

                    // Action bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.removePage(currentPage?.meta?.index ?: 0) },
                            enabled = currentPage != null
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Text("Remove")
                        }
                        Button(
                            onClick = { viewModel.generateSearchablePdf() },
                            enabled = !isProcessing && capturedPages.isNotEmpty()
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Save, null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save PDF")
                        }
                    }
                }
            }

            if (isProcessing) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (importState.isImporting) {
                        Text(
                            text = "Importing ${importState.current} of ${importState.total}"
                                + if (importState.failed > 0) " (${importState.failed} failed)" else "",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            uiState.error?.let { error ->
                LaunchedEffect(error) {
                    snackbarHostState.showSnackbar(error)
                }
            }

            uiState.message?.let { message ->
                LaunchedEffect(message) {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    // Export menu
    if (showExportMenu) {        ModalBottomSheet(onDismissRequest = { showExportMenu = false }) {
            Column(modifier = Modifier.padding(16.dp)) {
                ListItem(
                    headlineContent = { Text("Searchable PDF") },
                    supportingContent = { Text("With OCR text layer") },
                    leadingContent = { Icon(Icons.Default.TextFields, null) },
                    modifier = Modifier.clickable {
                        showExportMenu = false
                        viewModel.generateSearchablePdf()
                    }
                )
                ListItem(
                    headlineContent = { Text("Image PDF") },
                    supportingContent = { Text("Pages as images") },
                    leadingContent = { Icon(Icons.Default.Image, null) },
                    modifier = Modifier.clickable {
                        showExportMenu = false
                        viewModel.generateImagePdf()
                    }
                )
                ListItem(
                    headlineContent = { Text("Save as JPEGs") },
                    leadingContent = { Icon(Icons.Default.PhotoLibrary, null) },
                    modifier = Modifier.clickable {
                        showExportMenu = false
                        viewModel.saveAsJpegs()
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    showCornerAdjust?.let { corners ->
        currentPage?.let { page ->
            CornerAdjustDialog(
                sourceBitmap = page.originalBitmap,
                initialCorners = corners,
                onConfirm = { adjusted ->
                    viewModel.adjustCornersCurrent(adjusted)
                    showCornerAdjust = null
                },
                onDismiss = { showCornerAdjust = null }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

@Composable
private fun LiveEdgeOverlay(state: ScannerViewModel.LiveEdgeState) {
    val color = when {
        state is ScannerViewModel.LiveEdgeState.DocumentDetected && state.stable -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // green: stable
        state is ScannerViewModel.LiveEdgeState.DocumentDetected -> androidx.compose.ui.graphics.Color(0xFFFFC107) // amber: detected, settling
        state is ScannerViewModel.LiveEdgeState.AutoDetectionUnavailable -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f) // dimmed: no live detection possible
        else -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f) // searching
    }

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is ScannerViewModel.LiveEdgeState.Searching, is ScannerViewModel.LiveEdgeState.AutoDetectionUnavailable -> {
                // Dashed guide box, same footprint as the old static placeholder, so
                // there's still a hint of where to aim before a document is found.
                val inset = 48.dp.toPx()
                val w = size.width - inset * 2
                val h = w / 0.7f
                val top = (size.height - h) / 2
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, top.coerceAtLeast(inset)),
                    size = androidx.compose.ui.geometry.Size(w, h.coerceAtMost(size.height - inset * 2)),
                    style = Stroke(width = 3.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(24f, 16f))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f)
                )
            }
            is ScannerViewModel.LiveEdgeState.DocumentDetected -> {
                // Map corners from analysis-frame space (state.sourceWidth x
                // sourceHeight) into overlay space. CameraX's ImageAnalysis stream is
                // typically rotated 90 degrees relative to the portrait preview, and
                // the preview uses a center-crop fill rather than a plain stretch, so
                // this is an approximation (axis swap + uniform scale-to-fill) rather
                // than a pixel-exact mapping -- close enough to show the user roughly
                // where the detected document is, not intended for precision cropping
                // (final crop still runs against the full-resolution captured frame).
                val srcW = state.sourceHeight.toFloat().coerceAtLeast(1f) // swapped: analysis frame is landscape, preview is portrait
                val srcH = state.sourceWidth.toFloat().coerceAtLeast(1f)
                val scale = maxOf(size.width / srcW, size.height / srcH)
                val offsetX = (size.width - srcW * scale) / 2f
                val offsetY = (size.height - srcH * scale) / 2f

                fun mapPoint(x: Float, y: Float): androidx.compose.ui.geometry.Offset {
                    // Swap axes to account for the sensor/analysis-buffer rotation.
                    val mx = y * scale + offsetX
                    val my = x * scale + offsetY
                    return androidx.compose.ui.geometry.Offset(mx, my)
                }

                if (state.corners.size == 4) {
                    val points = state.corners.map { mapPoint(it.x, it.y) }
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(points[0].x, points[0].y)
                        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                        close()
                    }
                    drawPath(path, color = color, style = Stroke(width = 4.dp.toPx()))
                    points.forEach { p ->
                        drawCircle(color = color, radius = 6.dp.toPx(), center = p)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onCapture: (Bitmap) -> Unit,
    executor: ExecutorService,
    onCaptureReady: (() -> Unit) -> Unit = {},
    onFrameAnalyzed: (Bitmap, Int, Int) -> Unit = { _, _, _ -> },
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var lastAnalyzerRunAt by remember { mutableStateOf(0L) }

    // Shared capture function. Both the on-screen shutter button and a tap
    // on the preview call this once CameraX has finished binding, so
    // capture works no matter which one the user uses.
    fun performCapture(imageCapture: ImageCapture) {
        val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    // Previously silent -- the user would tap Capture and
                    // nothing would visibly happen at all on a genuine
                    // camera/storage failure (full disk, camera
                    // disconnected mid-capture, hardware error).
                    Log.e("ScannerScreen", "Image capture failed", exc)
                    onError("Capture failed. Please try again.")
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                        onCapture(bmp)
                    }
                }
            }
        )
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    ) { view ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Live edge detection previously didn't exist at all -- no ImageAnalysis
            // use case was ever bound to the camera, so DetectEdgesUseCase (a real,
            // working OpenCV pipeline) never received a single frame. This analyzer
            // downscales each frame to a small preview bitmap (document detection
            // doesn't need full sensor resolution) and throttles at the source so most
            // frames are dropped before any conversion cost is paid at all; the
            // remaining throttle/skip-while-busy logic lives in
            // ScannerViewModel.onAnalyzedFrame.
            val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(executor) { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (now - lastAnalyzerRunAt < 300L) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastAnalyzerRunAt = now
                        try {
                            val bitmap = imageProxy.toBitmapCompat()
                            onFrameAnalyzed(bitmap, imageProxy.width, imageProxy.height)
                        } catch (e: Exception) {
                            // Corrupt/unsupported frame -- just skip it, next frame will retry.
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )

                // Expose the capture trigger to the shutter button.
                onCaptureReady { performCapture(imageCapture) }

                // Tap-to-capture on the preview itself still works too.
                view.setOnClickListener { performCapture(imageCapture) }
            } catch (e: Exception) {
                // Previously silent -- if binding failed (camera already in
                // use by another app, hardware unavailable, a permission
                // race), the user would just see a black/frozen preview
                // indistinguishable from normal loading, with the shutter
                // button wired to nothing (onCaptureReady never called).
                Log.e("ScannerScreen", "Camera bind failed", e)
                onError("Unable to start the camera. Please try again.")
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

@Composable
private fun PermissionRationale(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Camera Permission Required", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "ProPDF needs camera access to scan documents",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

/**
 * `ImageProxy.toBitmap()` doesn't exist in camera-core 1.3.x (this module is on 1.3.1;
 * it was added later, in 1.4.0) -- the build failed with "Unresolved reference:
 * toBitmap" once this got compiled against the actual dependency version. This is the
 * standard manual YUV_420_888 -> NV21 -> JPEG -> Bitmap path CameraX apps use on 1.3.x:
 * concatenate the Y/U/V planes into an NV21 byte array, let YuvImage do the color
 * conversion via its built-in JPEG compressor, then decode that JPEG back to a Bitmap.
 * It only needs to be "good enough for edge detection on a downscaled preview frame",
 * not artifact-free, so a mid-range JPEG quality is fine and keeps this cheap.
 */
private fun ImageProxy.toBitmapCompat(): Bitmap {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val nv21 = ByteArray(width * height * 3 / 2)
    var pos = 0

    // Y plane: rows can be padded (rowStride > width), so copy row-by-row rather than
    // assuming the buffer is tightly packed.
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    for (row in 0 until height) {
        yBuffer.position(row * yRowStride)
        yBuffer.get(nv21, pos, width)
        pos += width
    }

    // U/V planes are commonly semi-planar with pixelStride == 2 (interleaved VU/UV
    // bytes sharing a plane), not the tightly-packed pixelStride == 1 case a naive
    // "just copy the remaining bytes" conversion assumes -- that mismatch is what
    // would otherwise produce a scrambled/incorrectly-colored (or, for chroma
    // subsampled 4:2:0, misaligned) image. NV21 wants interleaved V,U per 2x2 luma
    // block, so walk both planes together using their real strides.
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * vRowStride + col * vPixelStride
            val uIndex = row * uRowStride + col * uPixelStride
            nv21[pos++] = vBuffer.get(vIndex)
            nv21[pos++] = uBuffer.get(uIndex)
        }
    }

    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
    val jpegBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}
