package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.propdf.annotations.ui.AnnotationOverlay
import com.propdf.annotations.ui.AnnotationViewModel

/** Standard sepia tone matrix. */
private val SEPIA_MATRIX = ColorMatrix(
    floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
)

/** Inverted luminance for a dark, low-glare night reading mode. */
private val NIGHT_MATRIX = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
)

private fun colorFilterFor(mode: PdfColorMode) = when (mode) {
    PdfColorMode.NORMAL -> null
    PdfColorMode.SEPIA -> ColorMatrixColorFilter(SEPIA_MATRIX)
    PdfColorMode.NIGHT -> ColorMatrixColorFilter(NIGHT_MATRIX)
}

@Composable
fun PdfPageView(
    pageIndex: Int,
    pdfViewModel: PdfViewerViewModel,
    annotationViewModel: AnnotationViewModel,
    targetWidthPx: Int,
    colorMode: PdfColorMode,
    showAnnotations: Boolean,
    searchMatches: List<PdfSearchMatch>,
    activeSearchMatch: PdfSearchMatch?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(pageIndex, targetWidthPx) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(pageIndex, targetWidthPx) { mutableStateOf(false) }
    val pages by pdfViewModel.pages.collectAsState()
    val pageInfo = pages.getOrNull(pageIndex)

    LaunchedEffect(pageIndex, targetWidthPx) {
        bitmap = null
        failed = false
        val result = pdfViewModel.renderPage(pageIndex, targetWidthPx)
        if (result == null) failed = true else bitmap = result
    }

    Box(modifier = modifier.fillMaxWidth()) {
        val bmp = bitmap
        when {
            bmp != null -> {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                        }
                    },
                    update = { view ->
                        view.setImageBitmap(bmp)
                        view.colorFilter = colorFilterFor(colorMode)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (searchMatches.isNotEmpty() && pageInfo != null && pageInfo.widthPt > 0f) {
                    val renderRatio = bmp.width.toFloat() / pageInfo.widthPt
                    Canvas(modifier = Modifier.matchParentSize()) {
                        searchMatches.forEach { match ->
                            val isActive = match === activeSearchMatch
                            drawRect(
                                color = if (isActive) Color(0xFFFF9800) else Color(0xFFFFEE58),
                                topLeft = Offset(match.rect.left * renderRatio, match.rect.top * renderRatio),
                                size = Size(
                                    (match.rect.width() * renderRatio).coerceAtLeast(1f),
                                    (match.rect.height() * renderRatio).coerceAtLeast(1f)
                                ),
                                alpha = if (isActive) 0.65f else 0.35f
                            )
                        }
                    }
                }

                if (showAnnotations && pageInfo != null && pageInfo.widthPt > 0f) {
                    val renderRatio = bmp.width.toFloat() / pageInfo.widthPt
                    AnnotationOverlay(
                        viewModel = annotationViewModel,
                        pageIndex = pageIndex,
                        pageWidth = pageInfo.widthPt,
                        pageHeight = pageInfo.heightPt,
                        pageScale = renderRatio,
                        pageOffset = Offset.Zero,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
            failed -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Failed to render page ${pageIndex + 1}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
