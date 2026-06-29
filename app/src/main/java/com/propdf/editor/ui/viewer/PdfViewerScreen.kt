package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(uri: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var pageCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var bitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    val fileName = remember(uri) {
        Uri.parse(uri).lastPathSegment ?: "Document"
    }

    // Open and render PDF
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = openParcelFileDescriptor(context, Uri.parse(uri))
                    ?: throw Exception("Cannot open file")
                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer
                pageCount = renderer.pageCount
                val rendered = mutableListOf<Bitmap>()
                val displayWidth = context.resources.displayMetrics.widthPixels
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val ratio = displayWidth.toFloat() / page.width
                    val bmpWidth = displayWidth
                    val bmpHeight = (page.height * ratio).toInt()
                    val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                    // Fill white background
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    rendered.add(bmp)
                }
                bitmaps = rendered
                isLoading = false
            } catch (e: Exception) {
                errorMsg = "Cannot open PDF: ${e.message}"
                isLoading = false
            }
        }
    }

    // Clean up renderer when leaving
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMsg != null -> {
                    Column(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                        Text(
                            text = errorMsg ?: "Unknown error",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
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
                            AndroidView(
                                factory = { ctx ->
                                    ImageView(ctx).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                        adjustViewBounds = true
                                    }
                                },
                                update = { view ->
                                    view.setImageBitmap(bitmap)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer(scaleX = scale, scaleY = scale)
                            )
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

/** Opens a ParcelFileDescriptor for both content:// and file:// URIs */
private fun openParcelFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
    return when (uri.scheme) {
        "content" -> {
            // Copy to a temp file so PdfRenderer can seek
            val tmp = File(context.cacheDir, "pdf_view_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        "file" -> {
            ParcelFileDescriptor.open(
                File(uri.path ?: return null),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        }
        else -> null
    }
}
