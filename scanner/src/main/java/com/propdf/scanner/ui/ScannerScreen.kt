package com.propdf.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.ui.platform.LocalContext
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

    val snackbarHostState = remember { SnackbarHostState() }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, it)
                ) { decoder, _, _ -> decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            }
            bitmap?.let { bmp ->
                viewModel.captureDocument(bmp)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Auto-navigate when PDF is created
    LaunchedEffect(uiState.lastOutputUri) {
        uiState.lastOutputUri?.let { uri ->
            onPdfCreated(uri.toString())
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

                    CameraPreview(
                        onCapture = { bitmap ->
                            viewModel.captureDocument(bitmap)
                        },
                        executor = cameraExecutor,
                        onCaptureReady = { trigger -> triggerCapture = trigger }
                    )

                    // Capture button overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 32.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
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

                    // Edge detection guide overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.7f),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            shape = MaterialTheme.shapes.medium
                        ) { }
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

                    // Filter controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.ORIGINAL) },
                            label = { Text("Original") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.GRAYSCALE) },
                            label = { Text("B&W") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyFilterToCurrent(ColorMode.MAGIC_COLOR) },
                            label = { Text("Magic") }
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
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            uiState.error?.let { error ->
                LaunchedEffect(error) {
                    snackbarHostState.showSnackbar(error)
                }
            }
        }
    }

    // Export menu
    if (showExportMenu) {
        ModalBottomSheet(onDismissRequest = { showExportMenu = false }) {
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

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

@Composable
private fun CameraPreview(
    onCapture: (Bitmap) -> Unit,
    executor: ExecutorService,
    onCaptureReady: (() -> Unit) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

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
                override fun onError(exc: ImageCaptureException) { }
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

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                // Expose the capture trigger to the shutter button.
                onCaptureReady { performCapture(imageCapture) }

                // Tap-to-capture on the preview itself still works too.
                view.setOnClickListener { performCapture(imageCapture) }
            } catch (e: Exception) { }
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
