package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.propdf.annotations.ui.AnnotationOverlay
import com.propdf.annotations.ui.AnnotationToolbar
import com.propdf.annotations.ui.AnnotationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    uri: String,
    onBack: () -> Unit,
    annotationViewModel: AnnotationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // PDF document state (lightweight: renderer handle + page metadata, no bitmaps yet)
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var localFile by remember { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var pageSizes by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var showAnnotations by remember { mutableStateOf(false) }

    // Per-page rendered bitmap cache, filled in lazily as pages scroll into view.
    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    val pageErrors = remember { mutableStateMapOf<Int, String>() }
    val renderMutex = remember { Mutex() }

    val fileName by remember(uri) { mutableStateOf(resolveDisplayName(context, Uri.parse(uri))) }

    // Open the document and read page metadata only (cheap: no bitmap allocation here).
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val file = resolvePdfToLocalFile(context, Uri.parse(uri))
                    ?: throw Exception("Cannot open file: unable to obtain read access")
                localFile = file
                val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                pdfRenderer = renderer
                pageCount = renderer.pageCount
                val sizes = mutableListOf<Pair<Float, Float>>()
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    sizes.add(page.width.toFloat() to page.height.toFloat())
                    page.close()
                }
                pageSizes = sizes
                annotationViewModel.initializeDocument(uri, documentPath = file.absolutePath)
                isLoading = false
            } catch (e: SecurityException) {
                errorMsg = "Cannot open PDF: permission denied. Please re-select the file using the file picker."
                isLoading = false
            } catch (e: Exception) {
                errorMsg = "Cannot open PDF: ${e.localizedMessage ?: e.message}"
                isLoading = false
            } catch (e: OutOfMemoryError) {
                errorMsg = "Cannot open PDF: file is too large for available memory."
                isLoading = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pdfRenderer?.close()
            pageBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAnnotations = !showAnnotations }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Toggle annotations",
                            tint = if (showAnnotations)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Annotation toolbar — shown when pencil icon is active. Collapsed by default
            // (see AnnotationToolbar) so it no longer eats most of the screen.
            if (showAnnotations) {
                AnnotationToolbar(
                    viewModel = annotationViewModel,
                    snackbarHostState = snackbarHostState
                )
            }

            // PDF content area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    errorMsg != null -> Text(
                        text = errorMsg ?: "",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    pageCount > 0 -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                                    }
                                }
                        ) {
                            items(count = pageCount) { index ->
                                // Render this page's bitmap on demand, once, the first time
                                // it's composed (i.e. as it scrolls into/near view).
                                LaunchedEffect(index, pdfRenderer) {
                                    val renderer = pdfRenderer ?: return@LaunchedEffect
                                    if (pageBitmaps.containsKey(index) || pageErrors.containsKey(index)) return@LaunchedEffect
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val displayWidth = context.resources.displayMetrics.widthPixels
                                            val bmp = renderMutex.withLock {
                                                val page = renderer.openPage(index)
                                                try {
                                                    val ratio = displayWidth.toFloat() / page.width
                                                    val bitmap = Bitmap.createBitmap(
                                                        displayWidth,
                                                        (page.height * ratio).toInt().coerceAtLeast(1),
                                                        Bitmap.Config.ARGB_8888
                                                    )
                                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                    bitmap
                                                } finally {
                                                    page.close()
                                                }
                                            }
                                            pageBitmaps[index] = bmp
                                        } catch (e: OutOfMemoryError) {
                                            pageErrors[index] = "Not enough memory to render this page"
                                        } catch (e: Exception) {
                                            pageErrors[index] = e.localizedMessage ?: "Failed to render page"
                                        }
                                    }
                                }

                                val bitmap = pageBitmaps[index]
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer(scaleX = scale, scaleY = scale)
                                ) {
                                    when {
                                        bitmap != null -> {
                                            AndroidView(
                                                factory = { ctx ->
                                                    ImageView(ctx).apply {
                                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                                        adjustViewBounds = true
                                                    }
                                                },
                                                update = { view -> view.setImageBitmap(bitmap) },
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            if (showAnnotations) {
                                                val pdfSize = pageSizes.getOrNull(index)
                                                val pdfPageWidth = pdfSize?.first ?: bitmap.width.toFloat()
                                                val pdfPageHeight = pdfSize?.second ?: bitmap.height.toFloat()
                                                val renderRatio = if (pdfPageWidth > 0f) bitmap.width.toFloat() / pdfPageWidth else 1f
                                                AnnotationOverlay(
                                                    viewModel = annotationViewModel,
                                                    pageIndex = index,
                                                    pageWidth = pdfPageWidth,
                                                    pageHeight = pdfPageHeight,
                                                    pageScale = renderRatio * scale,
                                                    pageOffset = Offset.Zero,
                                                    modifier = Modifier.matchParentSize()
                                                )
                                            }
                                        }
                                        pageErrors.containsKey(index) -> {
                                            Text(
                                                text = pageErrors[index] ?: "Failed to render page",
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(24.dp),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        else -> {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(200.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator()
                                            }
                                        }
                                    }
                                }
                                if (index < pageCount - 1) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resolves a human-readable display name for a URI.
 *
 * For content:// URIs from providers like the Downloads provider (msf:...),
 * Uri.lastPathSegment returns the provider's raw internal document ID
 * (e.g. "msf:1000000123"), not the actual filename. Querying
 * OpenableColumns.DISPLAY_NAME via the ContentResolver gives the real name.
 */
private fun resolveDisplayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (e: Exception) {
            // Fall through to path-segment fallback below.
        }
    }
    val lastSegment = uri.lastPathSegment
    return lastSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() && !it.startsWith("msf:") }
        ?: "Document"
}

/**
 * Resolves any URI (file:// or content://, including providers like the Downloads
 * provider's msf: URIs that block direct PFD access) to a local cache File.
 *
 * Having a real File (rather than juggling a content:// Uri) is also what lets
 * flatten/burn/export operate on the document, since PDFBox and PdfRenderer both
 * need a File/PFD, not a Uri.
 */
private fun resolvePdfToLocalFile(context: Context, uri: Uri): File? {
    // Direct file:// URI - use as-is if readable.
    if (uri.scheme == "file") {
        val path = uri.path
        if (path != null) {
            val file = File(path)
            if (file.exists() && file.canRead()) return file
        }
    }

    // Otherwise copy the content stream to a stable cache file. This works even for
    // providers (like Downloads' msf: URIs) that deny direct ParcelFileDescriptor access,
    // since openInputStream() typically still succeeds.
    return try {
        val tmpFile = File(context.cacheDir, "pdf_view_${uri.hashCode()}_${System.currentTimeMillis()}.pdf")
        val opened = context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmpFile).use { output ->
                input.copyTo(output)
                output.flush()
            }
            true
        } ?: false
        if (opened && tmpFile.exists() && tmpFile.length() > 0) tmpFile else null
    } catch (e: Exception) {
        null
    }
}
