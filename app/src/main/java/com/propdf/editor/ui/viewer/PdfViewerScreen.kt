package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

    // PDF rendering state
    var bitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var showAnnotations by remember { mutableStateOf(false) }

    val fileName by remember(uri) { mutableStateOf(resolveDisplayName(context, Uri.parse(uri))) }

    // Per-page PDF point dimensions (needed to map annotation coordinates, which are
    // stored in PDF point space, to the rendered bitmap's pixel space)
    var pageSizes by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    // Initialize annotation document
    LaunchedEffect(uri) {
        annotationViewModel.initializeDocument(uri)
    }

    // Render PDF pages with proper permission handling for Downloads provider
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = openParcelFileDescriptorSafe(context, Uri.parse(uri))
                    ?: throw Exception("Cannot open file: unable to obtain read access")
                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer
                val displayWidth = context.resources.displayMetrics.widthPixels
                val rendered = mutableListOf<Bitmap>()
                val sizes = mutableListOf<Pair<Float, Float>>()
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val ratio = displayWidth.toFloat() / page.width
                    val bmp = Bitmap.createBitmap(
                        displayWidth,
                        (page.height * ratio).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    sizes.add(page.width.toFloat() to page.height.toFloat())
                    page.close()
                    rendered.add(bmp)
                }
                bitmaps = rendered
                pageSizes = sizes
                isLoading = false
            } catch (e: SecurityException) {
                errorMsg = "Cannot open PDF: permission denied. Please re-select the file using the file picker."
                isLoading = false
            } catch (e: Exception) {
                errorMsg = "Cannot open PDF: ${e.localizedMessage ?: e.message}"
                isLoading = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pdfRenderer?.close()
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    Scaffold(
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
            // Annotation toolbar — shown when pencil icon is active
            if (showAnnotations) {
                AnnotationToolbar(
                    viewModel = annotationViewModel
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
                    bitmaps.isNotEmpty() -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                                    }
                                }
                        ) {
                            itemsIndexed(bitmaps) { index, bitmap ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer(scaleX = scale, scaleY = scale)
                                ) {
                                    // PDF page image
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

                                    // Annotation overlay on top of each page
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
                                if (index < bitmaps.lastIndex) {
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
 * Safely opens a ParcelFileDescriptor for a URI, handling the Downloads provider
 * and other content providers that may not support direct PFD opening.
 *
 * For msf: URIs and other content URIs that throw SecurityException on direct access,
 * this copies the content to a temporary cache file first, then opens a PFD on that.
 */
private fun openParcelFileDescriptorSafe(context: Context, uri: Uri): ParcelFileDescriptor? {
    var lastError: Exception? = null

    // Try direct file URI first
    if (uri.scheme == "file") {
        val path = uri.path
        if (path != null) {
            try {
                return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                // Scoped storage (Android 10+) often blocks direct file:// access even
                // with storage permission granted. Don't give up here — fall through to
                // the content resolver / copy-to-cache approach below.
                lastError = e
            }
        }
    }

    // Try direct content resolver openFileDescriptor for content URIs
    if (uri.scheme == "content" || uri.scheme == "file") {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { return it }
        } catch (e: SecurityException) {
            // Downloads provider (msf:) and other providers may deny direct PFD access.
            // Fall through to copy-to-cache approach.
            lastError = e
        } catch (e: Exception) {
            lastError = e
        }
    }

    // Fallback: copy content to cache file, then open PFD on cache file.
    // This works because openInputStream() typically succeeds even when
    // openFileDescriptor() fails for certain providers.
    return try {
        val tmpFile = File(context.cacheDir, "pdf_safe_${System.currentTimeMillis()}.pdf")
        val opened = context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmpFile).use { output ->
                input.copyTo(output)
                output.flush()
            }
            true
        } ?: false
        if (opened && tmpFile.exists() && tmpFile.length() > 0) {
            ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            if (lastError != null) throw lastError
            null
        }
    } catch (e: Exception) {
        throw lastError ?: e
    }
}
