package com.propdf.editor.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.ui.theme.pdf_amber
import com.propdf.editor.ui.theme.pdf_blue
import com.propdf.editor.ui.theme.pdf_green
import com.propdf.editor.ui.theme.pdf_orange
import com.propdf.editor.ui.theme.pdf_purple
import com.propdf.editor.ui.theme.pdf_red
import com.propdf.editor.ui.theme.pdf_teal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Tool metadata — NO lambda fields (lambdas in data classes crash LazyColumn keying)
data class Tool(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val color: ComposeColor,
    val category: ToolCategory,
    val needsSinglePdf: Boolean = true,
    val needsMultiPdf: Boolean = false,
    val route: String? = null // non-null = navigate to route instead of picking PDF
)

enum class ToolCategory(val displayName: String) {
    EDIT("Edit"),
    ORGANIZE("Organize"),
    OPTIMIZE("Optimize"),
    SECURITY("Security"),
    EXPORT("Export")
}

private val ALL_TOOLS = listOf(
    Tool("Merge PDFs", "Combine multiple PDFs into one", Icons.Default.MergeType, pdf_blue, ToolCategory.ORGANIZE, needsMultiPdf = true, needsSinglePdf = false),
    Tool("Split PDF", "Extract pages or ranges", Icons.Default.ContentCut, pdf_red, ToolCategory.ORGANIZE),
    Tool("Delete Pages", "Remove unwanted pages", Icons.Default.Delete, pdf_red, ToolCategory.ORGANIZE),
    Tool("Rotate Pages", "Rotate selected pages", Icons.Default.RotateRight, pdf_orange, ToolCategory.EDIT),
    Tool("Add Text", "Insert text annotations", Icons.Default.TextFields, pdf_green, ToolCategory.EDIT),
    Tool("Add Image", "Insert images into PDF", Icons.Default.Image, pdf_purple, ToolCategory.EDIT),
    Tool("OCR", "Extract text from scanned PDF", Icons.Default.Search, pdf_blue, ToolCategory.EDIT, route = "ocr"),
    Tool("Extract Text", "Copy all text from PDF", Icons.Default.ContentCopy, pdf_green, ToolCategory.EDIT),
    Tool("Compress", "Reduce file size", Icons.Default.Straighten, pdf_teal, ToolCategory.OPTIMIZE),
    Tool("Protect", "Password protect PDF", Icons.Default.Lock, pdf_amber, ToolCategory.SECURITY, route = "security"),
    Tool("Share", "Share via Android Sharesheet", Icons.Default.Share, pdf_teal, ToolCategory.EXPORT),
    Tool("Export Images", "Save PDF pages as images", Icons.Default.PictureAsPdf, pdf_purple, ToolCategory.EXPORT),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedCategory by remember { mutableStateOf<ToolCategory?>(null) }
    var isMerging by remember { mutableStateOf(false) }

    // Single PDF picker — opens viewer with the chosen file
    val singlePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            navController.navigate("viewer/${Uri.encode(uri.toString())}")
        }
    }

    // Multi PDF picker — for merge
    val multiPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null && uris.size >= 2) {
            isMerging = true
            scope.launch {
                val result = mergePdfs(context, uris)
                isMerging = false
                snackbarHostState.showSnackbar(result)
            }
        } else if (uris != null && uris.isNotEmpty()) {
            scope.launch {
                snackbarHostState.showSnackbar("Please select at least 2 PDFs to merge")
            }
        }
    }

    fun onToolClicked(tool: Tool) {
        when {
            tool.route != null -> navController.navigate(tool.route)
            tool.needsMultiPdf -> multiPdfLauncher.launch(arrayOf("application/pdf"))
            tool.needsSinglePdf -> singlePdfLauncher.launch(arrayOf("application/pdf"))
        }
    }

    val filtered = if (selectedCategory != null)
        ALL_TOOLS.filter { it.category == selectedCategory }
    else
        ALL_TOOLS

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tools")
                        Text(
                            "${ALL_TOOLS.size} offline tools",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CategoryFilterRow(selectedCategory) { selectedCategory = it }
            }
            items(filtered, key = { it.name }) { tool ->
                ToolCard(tool = tool, onClick = { onToolClicked(tool) })
            }
        }
    }
}

/**
 * Merges multiple PDF files into a single output PDF using Android's built-in APIs.
 *
 * Approach:
 * 1. Copy each selected URI to a temporary file (handling content:// URIs properly)
 * 2. Render each page of each source PDF to a bitmap using PdfRenderer
 * 3. Create a new PDF using PdfDocument, drawing each bitmap onto new pages
 * 4. Save the merged PDF to Downloads/ProPDF/
 * 5. Clean up temporary files
 *
 * @param context Application context
 * @param uris List of content URIs selected by the user
 * @return Human-readable result message
 */
suspend fun mergePdfs(context: Context, uris: List<Uri>): String = withContext(Dispatchers.IO) {
    if (uris.size < 2) return@withContext "Please select at least 2 PDFs to merge"

    val pdfDocument = PdfDocument()
    var totalPageCount = 0

    try {
        for ((pdfIndex, uri) in uris.withIndex()) {
            // Step 1: Copy URI content to a temp file so PdfRenderer can read it
            val tempFile = File(context.cacheDir, "merge_in_${pdfIndex}_${System.currentTimeMillis()}.pdf")
            copyUriToFile(context, uri, tempFile)

            // Step 2: Render each page of this PDF
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)

            for (i in 0 until renderer.pageCount) {
                val srcPage = renderer.openPage(i)

                // Create a bitmap at the source page's native resolution
                val bmp = Bitmap.createBitmap(srcPage.width, srcPage.height, Bitmap.Config.ARGB_8888)
                // Fill white background (in case PDF has transparent areas)
                Canvas(bmp).drawColor(Color.WHITE)
                // Render the source page into the bitmap
                srcPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Create a page in the output PDF with the same dimensions
                val pageInfo = PdfDocument.PageInfo.Builder(srcPage.width, srcPage.height, totalPageCount).create()
                val destPage = pdfDocument.startPage(pageInfo)
                // Draw the rendered bitmap onto the destination page canvas
                destPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdfDocument.finishPage(destPage)

                // Clean up
                bmp.recycle()
                srcPage.close()
                totalPageCount++
            }

            renderer.close()
            pfd.close()
            tempFile.delete() // Clean up temp file
        }

        // Step 3: Save merged PDF to Downloads/ProPDF/
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ProPDF"
        )
        downloadsDir.mkdirs()
        val outputFile = File(downloadsDir, "merged_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outputFile).use { fos ->
            pdfDocument.writeTo(fos)
        }
        pdfDocument.close()

        "Merged ${uris.size} PDFs ($totalPageCount pages) into ${outputFile.name}"
    } catch (e: Exception) {
        try { pdfDocument.close() } catch (_: Exception) { }
        "Merge failed: ${e.localizedMessage ?: e.message}"
    }
}

/**
 * Copies content from a URI to a local file.
 * Handles content:// URIs (including Downloads provider msf: scheme)
 * by using openInputStream which works with transient permissions.
 */
private fun copyUriToFile(context: Context, uri: Uri, destFile: File) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(destFile).use { output ->
            input.copyTo(output)
            output.flush()
        }
    } ?: throw IllegalStateException("Cannot open input stream for $uri")
}

@Composable
fun CategoryFilterRow(
    selectedCategory: ToolCategory?,
    onCategorySelected: (ToolCategory?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        item {
            CategoryChip("All", selectedCategory == null) { onCategorySelected(null) }
        }
        items(ToolCategory.values().toList()) { cat ->
            CategoryChip(cat.displayName, selectedCategory == cat) { onCategorySelected(cat) }
        }
    }
}

@Composable
fun CategoryChip(name: String, isSelected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chip_scale"
    )
    Card(
        onClick = onClick,
        modifier = Modifier.scale(scale),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 2.dp else 0.dp
        )
    ) {
        Text(
            name,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun ToolCard(tool: Tool, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tool.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(tool.icon, null, tint = tool.color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tool.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    tool.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
