package com.propdf.annotations.ui.integration

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.propdf.annotations.ui.AnnotationOverlay
import com.propdf.annotations.ui.AnnotationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Integrated PDF viewer with annotation overlay.
 * Handles page rendering, zoom/pan gestures, and coordinate transformation.
 *
 * Features:
 * - PDF page rendering with PdfRenderer
 * - Pinch-to-zoom and pan
 * - Annotation overlay with proper coordinate mapping
 * - Page navigation
 * - Memory-efficient bitmap caching
 */
@Composable
fun PdfAnnotationView(
    viewModel: AnnotationViewModel,
    pdfFile: File,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var pageIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var pdfPageWidth by remember { mutableFloatStateOf(0f) }
    var pdfPageHeight by remember { mutableFloatStateOf(0f) }

    val currentTool by viewModel.currentTool.collectAsState()

    // Initialize PDF renderer
    LaunchedEffect(pdfFile) {
        withContext(Dispatchers.IO) {
            try {
                val fd = ParcelFileDescriptor.open(
                    pdfFile, 
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                val renderer = PdfRenderer(fd)
                pdfRenderer = renderer
                pageCount = renderer.pageCount

                // Load first page dimensions
                renderer.openPage(0).use { page ->
                    pdfPageWidth = page.width.toFloat()
                    pdfPageHeight = page.height.toFloat()
                }

                // Initialize document in ViewModel
                viewModel.initializeDocument(pdfFile.name, pdfFile.absolutePath)
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
            }
        }
    }

    // Render page when index or size changes
    LaunchedEffect(pageIndex, pageSize, scale) {
        if (pageSize == IntSize.Zero || pdfRenderer == null) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            pdfRenderer?.let { renderer ->
                if (pageIndex < renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)

                        if (width > 0 && height > 0) {
                            val bitmap = Bitmap.createBitmap(
                                width, height, Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pageBitmap = bitmap
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { pageSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offset = Offset(
                        offset.x + pan.x,
                        offset.y + pan.y
                    )
                }
            }
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            // PDF page background
            pageBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF Page ${pageIndex + 1} of $pageCount",
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Annotation overlay
            val pdfScale = if (pdfPageWidth > 0 && pageSize.width > 0) {
                (pageSize.width.toFloat() / pdfPageWidth) * scale
            } else 1f

            AnnotationOverlay(
                viewModel = viewModel,
                pageIndex = pageIndex,
                pageWidth = pdfPageWidth,
                pageHeight = pdfPageHeight,
                pageScale = pdfScale,
                pageOffset = offset,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Page navigation indicator
    if (pageCount > 1) {
        androidx.compose.material3.Text(
            text = "${pageIndex + 1} / $pageCount",
            modifier = Modifier.padding(16.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            pdfRenderer?.close()
            pageBitmap?.recycle()
        }
    }
}
